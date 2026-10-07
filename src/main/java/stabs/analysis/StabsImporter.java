package stabs.analysis;

import java.util.*;
import java.util.regex.Pattern;

import ghidra.app.util.importer.MessageLog;
import ghidra.app.util.NamespaceUtils;
import ghidra.program.model.address.*;
import ghidra.program.database.function.OverlappingFunctionException;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.listing.Function.FunctionUpdateType;
import ghidra.program.model.symbol.*;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.util.Msg;
import ghidra.util.exception.*;
import ghidra.util.task.TaskMonitor;
import stabs.model.*;
import stabs.model.StructType.Method;
import stabs.model.Variable.Storage;

/**
 * Applies a parsed {@link StabsProgram} to a Ghidra program: data types, function names and
 * signatures, and the types of global variables.
 */
final class StabsImporter {
	private static final String CALLING_CONVENTION = "__cdecl";

	private final Program program;
	private final long delta;
	private final MessageLog log;
	private final TaskMonitor monitor;
	private final StabsTypeImporter types;
	private final AddressSpace space;
	private final Map<String, Owner> methodsByPhysname = new HashMap<>();
	private final Set<String> passedByReference = new HashSet<>();

	private record Owner(StructType type, Method method) {
	}

	int functionsApplied;
	int functionsCreated;
	int customStorage;
	int globalsApplied;
	int failures;

	StabsImporter(Program program, long delta, MessageLog log, TaskMonitor monitor) {
		this.program = program;
		this.delta = delta;
		this.log = log;
		this.monitor = monitor;
		this.types = new StabsTypeImporter(program.getDataTypeManager(),
			program.getMemory().isBigEndian(), log);
		this.space = program.getAddressFactory().getDefaultAddressSpace();
	}

	void apply(StabsProgram stabs) throws CancelledException {
		monitor.setMessage("STABS: importing data types");
		types.importTypes(stabs.namedTypes());
		monitor.checkCancelled();

		for (SType t : stabs.namedTypes()) {
			if (t instanceof StructType st) {
				st.methods().forEach(m -> methodsByPhysname.putIfAbsent(m.physname(),
					new Owner(st, m)));
			}
		}

		findClassesPassedByReference(stabs.functions());

		monitor.setMessage("STABS: applying functions");
		monitor.initialize(stabs.functions().size());
		for (stabs.model.Function f : stabs.functions()) {
			monitor.increment();
			try {
				applyFunction(f);
			}
			catch (UsrException | OverlappingFunctionException | IllegalArgumentException e) {
				fail("function " + f.name() + ": " + e.getMessage());
			}
		}

		monitor.setMessage("STABS: applying globals");
		for (StabsProgram.Global g : stabs.globals()) {
			monitor.checkCancelled();
			applyGlobal(g.variable(), program.getGlobalNamespace());
		}
		for (stabs.model.Function f : stabs.functions()) {
			Function func = null;
			for (stabs.model.Function.Local l : f.locals()) {
				if (l.variable().storage() == Storage.LOCAL_STATIC) {
					if (func == null) {
						func = program.getListing().getFunctionAt(addr(f.address()));
					}
					applyGlobal(l.variable(),
						func != null ? func : program.getGlobalNamespace());
				}
			}
		}
		for (SType t : stabs.namedTypes()) {
			if (t instanceof StructType st) {
				for (StructType.StaticField sf : st.staticFields()) {
					applyData(findSymbolAddress(sf.physname()), sf.type(), null, null);
				}
			}
		}
	}

	String summary() {
		return String.format("STABS applied: %d function signatures (%d created, %d with " +
			"custom storage), %d global variables, %d type warnings, %d other warnings",
			functionsApplied, functionsCreated, customStorage, globalsApplied, types.failures(),
			failures);
	}

	private Address addr(long linkTimeAddress) {
		return space.getAddress(linkTimeAddress + delta);
	}

	// ---------------------------------------------------------------- functions

	/**
	 * g++ 2.95 passes classes that need a copy constructor by invisible reference, but the
	 * STABS give the parameter the class type. Such a class shows up as a parameter whose
	 * stack slot is only pointer sized; collect those classes by name.
	 */
	private void findClassesPassedByReference(List<stabs.model.Function> functions) {
		for (stabs.model.Function f : functions) {
			List<stabs.model.Variable> ps = f.params();
			for (int i = 0; i + 1 < ps.size(); i++) {
				stabs.model.Variable p = ps.get(i);
				stabs.model.Variable next = ps.get(i + 1);
				if (p.storage() == Storage.STACK && next.storage() == Storage.STACK &&
					SType.strip(p.type()) instanceof StructType st && st.name() != null &&
					st.size() > 4 && next.value() - p.value() == 4) {
					passedByReference.add(st.name());
				}
			}
		}
	}

	private boolean isPassedByReference(stabs.model.Variable v) {
		return v.storage() == Storage.STACK && SType.strip(v.type()) instanceof StructType st &&
			st.name() != null && passedByReference.contains(st.name());
	}

	private void applyFunction(stabs.model.Function f)
			throws UsrException, OverlappingFunctionException {
		Address entry = addr(f.address());
		Listing listing = program.getListing();
		Function func = listing.getFunctionAt(entry);
		if (func == null) {
			func = listing.createFunction(null, entry, new AddressSet(entry),
				SourceType.IMPORTED);
			functionsCreated++;
		}
		if (func.getSignatureSource() == SourceType.USER_DEFINED) {
			return;
		}
		applyName(func, f);
		applySignature(func, f);
		functionsApplied++;
	}

	/**
	 * Gives the function its source name, inside its class namespace for member functions, and
	 * keeps the mangled name as a secondary label.
	 */
	private void applyName(Function func, stabs.model.Function f)
			throws UsrException {
		String mangled = f.name();
		if (mangled == null || func.getSymbol().getSource() == SourceType.USER_DEFINED) {
			return;
		}
		Namespace ns = program.getGlobalNamespace();
		String name;
		Owner owner = methodsByPhysname.get(mangled);
		if (owner != null && owner.type().name() != null) {
			String cls = owner.type().name();
			ns = classNamespace(cls);
			name = methodName(cls, owner.method());
		}
		else {
			name = GnuV2Names.freeFunctionName(mangled);
		}
		if (name == null || (name.equals(mangled) && ns.isGlobal())) {
			return;
		}
		name = SymbolUtilities.replaceInvalidChars(name, true);
		Symbol sym = func.getSymbol();
		if (sym.getName().equals(name) && sym.getParentNamespace().equals(ns)) {
			return;
		}
		sym.setNameAndNamespace(name, ns, SourceType.IMPORTED);
		SymbolTable st = program.getSymbolTable();
		if (st.getSymbol(mangled, func.getEntryPoint(), program.getGlobalNamespace()) == null) {
			st.createLabel(func.getEntryPoint(), mangled, SourceType.IMPORTED);
		}
	}

	private static String methodName(String cls, Method m) {
		String bare = cls.contains("<") ? cls.substring(0, cls.indexOf('<')) : cls;
		if (GnuV2Names.isDestructor(m.physname())) {
			return "~" + bare;
		}
		if (GnuV2Names.isConstructor(m.physname())) {
			return bare;
		}
		String op = GnuV2Names.operatorName(m.name());
		return op != null ? op : m.name();
	}

	private Namespace classNamespace(String cls)
			throws InvalidInputException, DuplicateNameException {
		String name = SymbolUtilities.replaceInvalidChars(cls, true);
		SymbolTable st = program.getSymbolTable();
		Namespace ns = st.getNamespace(name, program.getGlobalNamespace());
		if (ns == null) {
			return st.createClass(program.getGlobalNamespace(), name, SourceType.IMPORTED);
		}
		if (!(ns instanceof GhidraClass) && ns.getSymbol().getSymbolType() == SymbolType.NAMESPACE) {
			return NamespaceUtils.convertNamespaceToClass(ns);
		}
		return ns;
	}

	/**
	 * Applies return type and parameters. Parameters are first given dynamic cdecl storage; if
	 * Ghidra's layout disagrees with the frame offsets in the STABS (e.g. structs returned in
	 * memory, which gcc 2.95 does for every struct), custom stack storage is used instead.
	 */
	private void applySignature(Function func, stabs.model.Function f)
			throws InvalidInputException, DuplicateNameException {
		DataType ret = types.get(f.returnType());
		List<Variable> params = new ArrayList<>();
		List<Integer> offsets = new ArrayList<>();
		boolean allStack = true;
		Set<String> names = new HashSet<>();
		for (int i = 0; i < f.params().size(); i++) {
			stabs.model.Variable v = f.params().get(i);
			DataType dt = types.get(v.type());
			if (v.storage() == Storage.REF_STACK || v.storage() == Storage.REF_REGISTER ||
				isPassedByReference(v)) {
				dt = program.getDataTypeManager().getPointer(dt);
			}
			if (dt.getLength() <= 0) {
				dt = Undefined4DataType.dataType;
			}
			String name = v.name() != null ? v.name() : Function.DEFAULT_PARAM_PREFIX + (i + 1);
			while (!names.add(name)) {
				name = name + "_";
			}
			params.add(new ParameterImpl(name, dt, program, SourceType.IMPORTED));
			boolean stack = v.storage() == Storage.STACK || v.storage() == Storage.REF_STACK;
			allStack &= stack;
			// STABS frame offsets are relative to %ebp; Ghidra's to %esp at function entry
			offsets.add(stack ? (int) v.value() - 4 : null);
		}

		ReturnParameterImpl retParam = new ReturnParameterImpl(ret, program);
		func.updateFunction(CALLING_CONVENTION, retParam, params,
			FunctionUpdateType.DYNAMIC_STORAGE_ALL_PARAMS, true, SourceType.IMPORTED);

		if (!allStack || params.isEmpty() || matchesStabs(func, offsets)) {
			return;
		}

		// Custom storage straight from the STABS frame offsets.
		List<Variable> custom = new ArrayList<>();
		DataType customRet = ret;
		if (ret instanceof Composite || ret instanceof TypeDef td &&
			td.getBaseDataType() instanceof Composite) {
			// returned in memory: the caller passes the address as a hidden first argument
			// and gets it back in %eax
			customRet = program.getDataTypeManager().getPointer(ret);
			if (offsets.get(0) > 4) {
				custom.add(new ParameterImpl(Function.RETURN_PTR_PARAM_NAME, customRet, 4,
					program, SourceType.IMPORTED));
			}
		}
		for (int i = 0; i < params.size(); i++) {
			Variable p = params.get(i);
			custom.add(new ParameterImpl(p.getName(), p.getDataType(), offsets.get(i), program,
				SourceType.IMPORTED));
		}
		VariableStorage retStorage = func.getReturn().getVariableStorage();
		if (customRet != ret && program.getLanguage().getRegister("EAX") != null) {
			retStorage = new VariableStorage(program, program.getLanguage().getRegister("EAX"));
		}
		func.updateFunction(CALLING_CONVENTION,
			new ReturnParameterImpl(customRet, retStorage, true, program), custom,
			FunctionUpdateType.CUSTOM_STORAGE, true, SourceType.IMPORTED);
		customStorage++;
	}

	private static boolean matchesStabs(Function func, List<Integer> offsets) {
		Parameter[] ps = func.getParameters();
		int j = 0;
		for (Parameter p : ps) {
			if (p.isAutoParameter()) {
				continue;
			}
			if (j >= offsets.size()) {
				return false;
			}
			VariableStorage s = p.getVariableStorage();
			if (!s.isStackStorage() || s.getStackOffset() != offsets.get(j)) {
				return false;
			}
			j++;
		}
		return j == offsets.size();
	}

	// ---------------------------------------------------------------- globals

	private void applyGlobal(stabs.model.Variable v, Namespace ns) {
		Address a = switch (v.storage()) {
			case GLOBAL -> findSymbolAddress(v.name());
			case STATIC, LOCAL_STATIC -> addr(v.value());
			default -> null;
		};
		applyData(a, v.type(), v.name(), ns);
	}

	private Address findSymbolAddress(String name) {
		if (name == null) {
			return null;
		}
		for (Symbol s : program.getSymbolTable().getGlobalSymbols(name)) {
			if (s.getAddress().isMemoryAddress() && !s.isExternal()) {
				return s.getAddress();
			}
		}
		return null;
	}

	private void applyData(Address a, SType type, String name, Namespace ns) {
		if (a == null || !program.getMemory().contains(a)) {
			return;
		}
		DataType dt = types.get(type);
		if (dt == null || dt.getLength() <= 0) {
			return;
		}
		Listing listing = program.getListing();
		if (listing.getInstructionContaining(a) != null) {
			return;
		}
		try {
			DataUtilities.createData(program, a, dt, -1,
				DataUtilities.ClearDataMode.CLEAR_ALL_CONFLICT_DATA);
			globalsApplied++;
			if (name != null) {
				applyDataName(a, name, ns);
			}
		}
		catch (CodeUnitInsertionException | InvalidInputException e) {
			fail("data " + name + " at " + a + ": " + e.getMessage());
		}
	}

	/**
	 * Labels data with its STABS name. gcc assembles function-local statics as {@code name.N}
	 * (N is a label counter), so that ELF symbol is replaced as the primary name; any other
	 * existing symbol is kept.
	 */
	private void applyDataName(Address a, String name, Namespace ns) throws InvalidInputException {
		SymbolTable st = program.getSymbolTable();
		Symbol primary = st.getPrimarySymbol(a);
		if (primary != null && !primary.getName().matches(Pattern.quote(name) + "\\.\\d+")) {
			return;
		}
		Symbol s = st.getSymbol(name, a, ns);
		if (s == null) {
			s = st.createLabel(a, name, ns, SourceType.IMPORTED);
		}
		if (!s.isPrimary()) {
			s.setPrimary();
		}
	}

	private void fail(String msg) {
		failures++;
		if (failures <= 50) {
			log.appendMsg("STABS: " + msg);
		}
		Msg.debug(this, msg);
	}
}
