package stabs.analysis;

import java.util.*;

import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.decompiler.parallel.DecompilerCallback;
import ghidra.app.decompiler.parallel.ParallelDecompiler;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.data.DataType;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.Msg;
import ghidra.util.exception.*;
import ghidra.util.task.TaskMonitor;

/**
 * Register locals waiting for code to exist.
 * <p>
 * STABS only say which register holds a variable in its lexical block. The decompiler attaches
 * a register local only through the name representative of a HighVariable (its earliest
 * definition), and it often merges a variable with copies held in other registers, so no
 * address computed from the STABS alone attaches reliably. Instead the functions are
 * decompiled, and a HighVariable that is written to the register inside the variable's block
 * is committed under the STABS name and type, the same way renaming it in the decompiler does.
 * The decompiler may move a variable out of the register (into its unique space), so the
 * outputs of the p-code of instructions that write the register count as well. gcc also puts
 * temporaries into that register, so of those HighVariables the one with the most definitions
 * is taken: a variable that lives in a register for a whole block is usually updated more than
 * once (loops), a temporary is set once.
 * <p>
 * {@link StabsAnalyzer} runs before disassembly and hands the locals over to
 * {@link StabsRegisterLocalsAnalyzer}, which runs after function analysis.
 */
final class StabsRegisterLocals {

	/**
	 * @param blockStart start of the lexical block relative to the function entry
	 * @param blockEnd end of the block relative to the function entry, -1 if unknown
	 */
	record Pending(Address function, String name, DataType type, Register register,
			long blockStart, long blockEnd) {
	}

	private record Match(Pending local, HighSymbol symbol) {
	}

	/** A variable's lexical block, and the instructions in it that write its register. */
	private record Block(AddressSet range, Set<Address> writes) {
	}

	private static final Map<Program, List<Pending>> PENDING =
		Collections.synchronizedMap(new WeakHashMap<>());

	int applied;
	int skipped;
	/** skipped locals per reason */
	final Map<String, Integer> reasons = new TreeMap<>();

	private void skip(String why, Pending p) {
		skipped++;
		reasons.merge(why, 1, Integer::sum);
	}

	static void put(Program program, List<Pending> locals) {
		if (!locals.isEmpty()) {
			PENDING.put(program, locals);
		}
	}

	static List<Pending> take(Program program) {
		return PENDING.remove(program);
	}

	void apply(Program program, List<Pending> locals, TaskMonitor monitor)
			throws CancelledException {
		FunctionManager fm = program.getFunctionManager();
		Map<Function, Map<Pending, Block>> byFunction = new LinkedHashMap<>();
		for (Pending p : locals) {
			Function func = fm.getFunctionAt(p.function());
			Block block = func == null ? null : block(program, func, p);
			if (block == null || block.writes().isEmpty()) {
				// "no write": e.g. only read in this block (an inlined function's parameter), or
				// the code after a call that Ghidra thinks doesn't return is missing
				skip(func == null ? "no function" : block == null ? "empty block" : "no write", p);
				continue;
			}
			if (byFunction.computeIfAbsent(func, f -> new LinkedHashMap<>()).put(p,
				block) != null) {
				skip("duplicate", p); // the same inlined function expanded twice in a block
			}
		}

		DecompilerCallback<List<Match>> callback = new DecompilerCallback<>(program, d -> {
			DecompileOptions options = new DecompileOptions();
			options.grabFromProgram(program);
			d.setOptions(options);
			d.toggleCCode(false);
			d.toggleSyntaxTree(true);
			d.setSimplificationStyle("decompile");
		}) {
			@Override
			public List<Match> process(DecompileResults results, TaskMonitor m) {
				HighFunction hf = results.getHighFunction();
				Map<Pending, Block> blocks = byFunction.get(results.getFunction());
				return hf == null || blocks == null ? List.of() : match(program, hf, blocks);
			}
		};
		callback.setTimeout(60);
		List<List<Match>> results;
		try {
			results = ParallelDecompiler.decompileFunctions(callback, byFunction.keySet(),
				monitor);
		}
		catch (CancelledException | InterruptedException e) {
			throw new CancelledException();
		}
		catch (Exception e) {
			Msg.error(this, "STABS: decompiling for register locals failed", e);
			return;
		}
		finally {
			callback.dispose();
		}

		Set<Pending> matched = new HashSet<>();
		for (List<Match> ms : results) {
			if (ms == null) {
				continue;
			}
			for (Match m : ms) {
				matched.add(m.local());
				commit(m);
			}
		}
		for (Map<Pending, Block> blocks : byFunction.values()) {
			for (Pending p : blocks.keySet()) {
				if (!matched.contains(p)) {
					skip("no candidate", p);
				}
			}
		}
	}

	/**
	 * @return the decompiler's variables for the given locals; a variable that already has a
	 *         name of its own (a parameter, a stack local) or that an outer block's local has
	 *         claimed is left alone
	 */
	private static List<Match> match(Program program, HighFunction hf,
			Map<Pending, Block> blocks) {
		List<Match> matches = new ArrayList<>();
		Set<HighSymbol> claimed = new HashSet<>();
		for (Map.Entry<Pending, Block> e : blocks.entrySet()) {
			Register base = e.getKey().register().getBaseRegister();
			Block block = e.getValue();
			// definitions per decompiler variable: in the register inside the block, or by an
			// instruction that writes the register
			Map<HighSymbol, Integer> defs = new LinkedHashMap<>();
			Iterator<PcodeOpAST> ops = hf.getPcodeOps();
			while (ops.hasNext()) {
				PcodeOpAST op = ops.next();
				Varnode out = op.getOutput();
				Address at = op.getSeqnum().getTarget();
				if (out == null || !block.range().contains(at)) {
					continue;
				}
				boolean candidate;
				if (out.isRegister()) {
					Register r = program.getRegister(out.getAddress(), out.getSize());
					candidate = r != null && r.getBaseRegister().equals(base);
				}
				else {
					candidate = out.isUnique() && block.writes().contains(at);
				}
				HighVariable high = candidate ? out.getHigh() : null;
				HighSymbol sym = high != null ? high.getSymbol() : null;
				if (sym != null && !sym.isParameter() && !sym.isGlobal() &&
					!sym.isNameLocked() && !claimed.contains(sym)) {
					defs.merge(sym, 1, Integer::sum);
				}
			}
			defs.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(best -> {
				claimed.add(best.getKey());
				matches.add(new Match(e.getKey(), best.getKey()));
			});
		}
		return matches;
	}

	private void commit(Match m) {
		Function func = m.symbol().getHighFunction().getFunction();
		DataType dt = m.local().type();
		try {
			HighFunctionDBUtil.updateDBVariable(m.symbol(), uniqueName(func, m.local().name()),
				dt.getLength() == m.symbol().getSize() ? dt : null, SourceType.IMPORTED);
			applied++;
		}
		catch (InvalidInputException | DuplicateNameException | IllegalArgumentException |
				UnsupportedOperationException e) {
			skip("commit failed", m.local());
		}
	}

	/** @return the variable's lexical block, null if it is empty */
	private static Block block(Program program, Function func, Pending p) {
		Address entry = func.getEntryPoint();
		if (p.blockEnd() >= 0 && p.blockEnd() <= p.blockStart()) {
			return null; // gcc emptied the block, e.g. an inlined function's optimized out
		}
		Address start = entry.add(Math.max(0, p.blockStart()));
		Address end = p.blockEnd() >= 0 ? entry.add(p.blockEnd() - 1)
				: func.getBody().getMaxAddress();
		if (end.compareTo(start) < 0) {
			return null;
		}
		AddressSet range = new AddressSet(start, end).intersect(func.getBody());
		Register base = p.register().getBaseRegister();
		Set<Address> writes = new HashSet<>();
		for (Instruction in : program.getListing().getInstructions(range, true)) {
			for (Object o : in.getResultObjects()) {
				if (o instanceof Register r && r.getBaseRegister().equals(base)) {
					writes.add(in.getAddress());
				}
			}
		}
		return new Block(range, writes);
	}

	private static String uniqueName(Function func, String name) {
		Set<String> names = new HashSet<>();
		for (Variable v : func.getAllVariables()) {
			names.add(v.getName());
		}
		String unique = name;
		for (int n = 1; names.contains(unique); n++) {
			unique = name + "_" + n;
		}
		return unique;
	}
}
