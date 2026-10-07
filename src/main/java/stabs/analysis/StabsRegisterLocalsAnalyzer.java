package stabs.analysis;

import java.util.List;

import ghidra.app.services.*;
import ghidra.app.util.importer.MessageLog;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * Adds the register locals found by {@link StabsAnalyzer} once the code has been disassembled,
 * see {@link StabsRegisterLocals}.
 */
public class StabsRegisterLocalsAnalyzer extends AbstractAnalyzer {
	private static final String NAME = "STABS Register Locals";
	private static final String DESCRIPTION =
		"Names and types the decompiler's variables that hold the STABS register locals. " +
			"Decompiles the functions that have any. Controlled by the STABS analyzer's " +
			"register locals option.";

	public StabsRegisterLocalsAnalyzer() {
		super(NAME, DESCRIPTION, AnalyzerType.BYTE_ANALYZER);
		setDefaultEnablement(true);
		setPriority(AnalysisPriority.FUNCTION_ANALYSIS.after());
	}

	@Override
	public boolean canAnalyze(Program program) {
		return StabsAnalyzer.hasStabs(program);
	}

	@Override
	public boolean added(Program program, AddressSetView set, TaskMonitor monitor,
			MessageLog log) throws CancelledException {
		List<StabsRegisterLocals.Pending> pending = StabsRegisterLocals.take(program);
		if (pending == null) {
			return false;
		}
		monitor.setMessage("STABS: applying register locals");
		StabsRegisterLocals locals = new StabsRegisterLocals();
		locals.apply(program, pending, monitor);
		String summary = String.format("STABS applied: %d register local variables (%d skipped)",
			locals.applied, locals.skipped) + (locals.reasons.isEmpty() ? "" : " " + locals.reasons);
		Msg.info(this, summary);
		log.appendMsg(summary);
		return true;
	}
}
