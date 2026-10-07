package stabs.parse;

import static stabs.format.StabType.*;

import java.util.*;

import stabs.format.StabEntry;
import stabs.model.*;
import stabs.model.Variable.Storage;

/**
 * Turns a stream of {@link StabEntry}s into a {@link StabsProgram}.
 * <p>
 * Type numbers {@code (file,index)} are scoped per compilation unit: file 0 is the unit's main
 * file, and every N_BINCL or N_EXCL takes the next file number. N_EXCL reuses the type table of
 * the identically named and hashed N_BINCL from an earlier unit, which is how header types are
 * shared (and deduplicated) across units.
 */
public final class StabsParser {
	private final StabsProgram program = new StabsProgram();
	private final Map<String, TypeTable> binclTables = new HashMap<>();
	private final List<CrossRefType> crossRefs = new ArrayList<>();
	private final Set<SType> registeredNames = Collections.newSetFromMap(new IdentityHashMap<>());

	private CompileUnit unit;
	private List<TypeTable> files = new ArrayList<>();
	private final Deque<TypeTable> binclStack = new ArrayDeque<>();
	private String pendingSoDir;
	private String pendingSoFile;
	private long pendingSoValue;
	private Function function;
	private int blockDepth;
	private String currentSource;
	private int entryIndex;

	private final TypeContext ctx = new TypeContext() {
		@Override
		public TypeRef slot(int file, int index) {
			ensureUnit();
			if (file < 0 || file >= files.size()) {
				throw new StabsParseException("type file number " + file + " out of range");
			}
			return files.get(file).slot(index);
		}

		@Override
		public CompileUnit unit() {
			return unit;
		}

		@Override
		public void crossRef(CrossRefType ref) {
			crossRefs.add(ref);
		}
	};

	public static StabsProgram parse(List<StabEntry> entries) {
		StabsParser p = new StabsParser();
		for (StabEntry e : entries) {
			p.entryIndex = e.index();
			try {
				p.process(e);
			}
			catch (StabsParseException | NumberFormatException ex) {
				p.program.issues().add(new ParseIssue(e.index(), ex.getMessage(), e.string()));
			}
		}
		p.endFunction();
		p.resolveCrossRefs();
		return p.program;
	}

	private void process(StabEntry e) {
		int type = e.type();
		String str = e.string();

		if (pendingSoFile != null && (type != N_SO || str.isEmpty() || e.value() != pendingSoValue)) {
			startUnit();
		}

		switch (type) {
			case N_SO -> {
				endFunction();
				if (str.isEmpty()) {
					return;
				}
				if (pendingSoFile == null) {
					pendingSoDir = null;
					pendingSoFile = str;
				}
				else if (str.startsWith("/")) {
					pendingSoDir = null;
					pendingSoFile = str;
				}
				else {
					pendingSoDir = pendingSoFile;
					pendingSoFile = str;
				}
				pendingSoValue = e.value();
			}
			case N_BINCL -> {
				ensureUnit();
				String key = str + "\0" + e.value();
				TypeTable t = new TypeTable(str, e.value(), unit);
				binclTables.putIfAbsent(key, t);
				files.add(t);
				binclStack.push(t);
				currentSource = str;
			}
			case N_EINCL -> {
				if (!binclStack.isEmpty()) {
					binclStack.pop();
				}
				currentSource = binclStack.isEmpty() ? unit.fileName() : binclStack.peek().name();
			}
			case N_EXCL -> {
				ensureUnit();
				TypeTable t = binclTables.get(str + "\0" + e.value());
				if (t == null) {
					issue("N_EXCL without matching N_BINCL", str);
					t = new TypeTable(str, e.value(), unit);
				}
				files.add(t);
			}
			case N_SOL -> currentSource = str;
			case N_SLINE -> {
				if (function != null) {
					function.lines().add(new Function.Line(function.address() + e.value(),
						e.desc(), currentSource));
				}
			}
			case N_LBRAC -> blockDepth++;
			case N_RBRAC -> blockDepth = Math.max(0, blockDepth - 1);
			case N_FUN -> {
				if (str.isEmpty()) {
					endFunction();
					return;
				}
				parseSymbol(e);
			}
			case N_OPT, N_OBJ, N_MAIN, N_ENDM, N_BCOMM, N_ECOMM -> {
			}
			default -> {
				if (!str.isEmpty()) {
					parseSymbol(e);
				}
			}
		}
	}

	private void startUnit() {
		unit = new CompileUnit(program.units().size(), pendingSoDir, pendingSoFile,
			pendingSoValue);
		program.units().add(unit);
		files = new ArrayList<>();
		files.add(new TypeTable(pendingSoFile, 0, unit));
		binclStack.clear();
		currentSource = pendingSoFile;
		pendingSoDir = null;
		pendingSoFile = null;
	}

	private void ensureUnit() {
		if (unit == null) {
			pendingSoFile = pendingSoFile != null ? pendingSoFile : "<unknown>";
			startUnit();
		}
	}

	private void endFunction() {
		function = null;
		blockDepth = 0;
	}

	private void issue(String message, String stab) {
		program.issues().add(new ParseIssue(entryIndex, message, stab));
	}

	private void parseSymbol(StabEntry e) {
		ensureUnit();
		String str = e.string();
		int colon = str.indexOf(':');
		if (colon < 0) {
			return;
		}
		while (colon + 1 < str.length() && str.charAt(colon + 1) == ':') {
			colon = str.indexOf(':', colon + 2);
			if (colon < 0) {
				throw new StabsParseException("no symbol descriptor");
			}
		}

		String name = str.substring(0, colon);
		if (name.startsWith("$")) {
			name = name.startsWith("$t") ? "this" : null;
		}
		else if (name.isEmpty() || name.equals(" ")) {
			name = null;
		}

		int p = colon + 1;
		if (p >= str.length()) {
			throw new StabsParseException("missing symbol descriptor");
		}
		char desc = str.charAt(p);
		if (Character.isDigit(desc) || desc == '(' || desc == '-') {
			desc = 'l';
		}
		else {
			p++;
		}

		TypeParser tp = new TypeParser(str, p, ctx);
		switch (desc) {
			case 'c' -> {
				// constant (c=i, c=r, c=e); nothing to import
			}
			case 'f', 'F' -> {
				SType ret = tp.parseType(null);
				while (tp.peek() == ';') {
					tp.expect(';');
					tp.parseType(null);
				}
				if (e.type() == N_FUN) {
					endFunction();
					function = new Function(name, desc == 'F', e.value(), ret, unit);
					program.functions().add(function);
				}
			}
			case 'G' -> addGlobal(name, tp.parseType(null), Storage.GLOBAL, e.value());
			case 'S' -> addGlobal(name, tp.parseType(null), Storage.STATIC, e.value());
			case 'V' -> addLocal(name, tp.parseType(null), Storage.LOCAL_STATIC, e.value());
			case 'l', 's' -> addLocal(name, tp.parseType(null), Storage.STACK, e.value());
			case 'r' -> addLocal(name, tp.parseType(null), Storage.REGISTER, e.value());
			case 'p' -> {
				SType t;
				if (tp.peek() == 'F') {
					tp.expect('F');
					t = new PointerType(new FunctionType(tp.parseType(null)));
				}
				else {
					t = tp.parseType(null);
				}
				addParam(name, t, Storage.STACK, e.value());
			}
			case 'P' -> {
				if (e.type() == N_FUN) {
					while (tp.peek() == ';') {
						tp.expect(';');
						tp.parseType(null);
					}
				}
				else {
					addParam(name, tp.parseType(null), Storage.REGISTER, e.value());
				}
			}
			case 'R' -> addParam(name, tp.parseType(null), Storage.REGISTER, e.value());
			case 'v' -> addParam(name, tp.parseType(null), Storage.REF_STACK, e.value());
			case 'a' -> addParam(name, tp.parseType(null), Storage.REF_REGISTER, e.value());
			case 'X', 'C' -> tp.parseType(null);
			case 't' -> defineTypedef(name, tp);
			case 'T' -> defineTag(name, tp);
			default -> throw new StabsParseException("unknown symbol descriptor '" + desc + "'");
		}
	}

	private void addGlobal(String name, SType type, Storage storage, long value) {
		program.globals().add(new StabsProgram.Global(new Variable(name, type, storage, value),
			unit));
	}

	private void addLocal(String name, SType type, Storage storage, long value) {
		if (function == null) {
			if (storage == Storage.LOCAL_STATIC) {
				addGlobal(name, type, Storage.STATIC, value);
			}
			return;
		}
		function.locals().add(
			new Function.Local(new Variable(name, type, storage, value), blockDepth));
	}

	private void addParam(String name, SType type, Storage storage, long value) {
		if (function == null) {
			issue("parameter outside of function", name);
			return;
		}
		function.params().add(new Variable(name, type, storage, value));
	}

	/**
	 * {@code name:t...}: names a type. A type defined right here (a builtin range, or an
	 * anonymous struct/enum) takes the name; anything else becomes a {@link TypedefType}.
	 */
	private void defineTypedef(String name, TypeParser tp) {
		SType t = tp.parseType(name);
		if (name == null) {
			return;
		}
		if (!(t instanceof TypeRef slot) || slot.target() == null) {
			registerNamed(new TypedefType(name, t));
			return;
		}
		// look through aliases such as "foo_t:t(83,7)=(83,6)", where (83,6) is an anonymous
		// "typedef struct {...}" or an enum defined by an earlier " :T(83,6)=e..."
		SType def = slot.target();
		while (def instanceof TypeRef r && r.target() != null && r.target() != slot) {
			def = r.target();
		}
		if (def instanceof BaseType b && b.name() == null) {
			b.setName(name);
			registerNamed(b);
		}
		else if (def instanceof StructType st && st.name() == null) {
			st.setName(name);
			registerNamed(st);
		}
		else if (def instanceof EnumType en && en.name() == null) {
			en.setName(name);
			registerNamed(en);
		}
		else if (!name.equals(def.resolve().name())) {
			TypedefType td = new TypedefType(name, def);
			slot.setTarget(td);
			registerNamed(td);
		}
	}

	/** {@code name:T...} or {@code name:Tt...}: a struct, union or enum tag. */
	private void defineTag(String name, TypeParser tp) {
		if (tp.peek() == 't') {
			tp.expect('t'); // C++: tag is also a typedef name, which is implicit
		}
		SType t = tp.parseType(name);
		if (name == null) {
			return;
		}
		SType def = t instanceof TypeRef slot ? slot.target() : t;
		if (def instanceof StructType st) {
			if (st.name() == null) {
				st.setName(name);
			}
			registerNamed(st);
		}
		else if (def instanceof EnumType en) {
			if (en.name() == null) {
				en.setName(name);
			}
			registerNamed(en);
		}
		// a CrossRefType here is a self reference ("fleep:T20=xsfleep:"), nothing to define
	}

	private void registerNamed(SType t) {
		if (registeredNames.add(t)) {
			program.namedTypes().add(t);
		}
	}

	/**
	 * Resolves {@code xs}/{@code xu}/{@code xe} references by tag name, preferring a definition
	 * from the same compilation unit, then one from a unit of the same language, then any.
	 * <p>
	 * The language check matters for names C and C++ both use: g++'s {@code bad_cast} derives
	 * from {@code xsexception:}, which must not resolve to {@code <math.h>}'s
	 * {@code struct exception} from a C unit (g++ renames that one {@code __exception}).
	 */
	private void resolveCrossRefs() {
		Map<String, List<SType>> byTag = new HashMap<>();
		for (SType t : program.namedTypes()) {
			if (t instanceof StructType || t instanceof EnumType) {
				byTag.computeIfAbsent(t.name(), k -> new ArrayList<>()).add(t);
			}
		}
		for (CrossRefType x : crossRefs) {
			List<SType> candidates = byTag.get(x.tag());
			if (candidates == null) {
				continue;
			}
			SType best = null;
			int bestScore = -1;
			for (SType c : candidates) {
				if (!kindMatches(x, c)) {
					continue;
				}
				CompileUnit u = unitOf(c);
				int score = (u == x.unit() ? 2 : 0) +
					(u != null && isCpp(u) == isCpp(x.unit()) ? 1 : 0);
				if (score > bestScore) {
					best = c;
					bestScore = score;
				}
			}
			x.setResolved(best);
		}
	}

	private static boolean kindMatches(CrossRefType x, SType t) {
		return switch (x.kind()) {
			case ENUM -> t instanceof EnumType;
			case UNION -> t instanceof StructType st && st.isUnion();
			case STRUCT -> t instanceof StructType st && !st.isUnion();
		};
	}

	private static boolean isCpp(CompileUnit u) {
		String f = u.fileName();
		return f.endsWith(".cpp") || f.endsWith(".cc") || f.endsWith(".cxx") ||
			f.endsWith(".C") || f.endsWith(".c++");
	}

	private static CompileUnit unitOf(SType t) {
		return t instanceof StructType st ? st.unit() : t instanceof EnumType en ? en.unit() : null;
	}
}
