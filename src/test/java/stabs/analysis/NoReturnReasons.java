package stabs.analysis;

import java.util.*;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;

/**
 * Replays the per-call checks of Ghidra's FindNoReturnFunctionsAnalyzer ("Non-Returning
 * Functions - Discovered") and prints, per called function, which one fires where.
 */
final class NoReturnReasons {
	static void print(Program program) {
		Listing listing = program.getListing();
		FunctionManager fm = program.getFunctionManager();
		ReferenceManager rm = program.getReferenceManager();
		Map<String, List<String>> byTarget = new TreeMap<>();
		for (Instruction call : listing.getInstructions(true)) {
			if (!call.getFlowType().isCall() || !call.getFlowType().hasFallthrough()) {
				continue;
			}
			Address[] flows = call.getFlows();
			if (flows.length == 0) {
				continue;
			}
			Function target = fm.getFunctionAt(flows[0]);
			Function caller = fm.getFunctionContaining(call.getAddress());
			String reason = null;
			Address ft = call.getFallThrough();
			Address next = null;
			FunctionIterator it = fm.getFunctions(ft, true);
			if (it.hasNext()) {
				next = it.next().getEntryPoint();
			}
			while (ft != null && reason == null) {
				if (ft.equals(next)) {
					reason = "function after call at " + ft;
					break;
				}
				CodeUnit cu = listing.getCodeUnitAt(ft);
				if (cu == null || cu instanceof Data) {
					reason = "falls into data at " + ft;
					break;
				}
				if (next != null && cu.contains(next)) {
					reason = "function inside instruction at " + ft;
					break;
				}
				for (Reference r : rm.getReferencesTo(ft)) {
					RefType t = r.getReferenceType();
					if ((t.isRead() || t.isWrite()) && (caller == null ||
						r.getFromAddress().compareTo(ft) < 0 &&
							caller.equals(fm.getFunctionContaining(r.getFromAddress())))) {
						reason = t + " ref to " + ft + " from " + r.getFromAddress() + " " +
							listing.getInstructionAt(r.getFromAddress());
						break;
					}
					if (t.isCall()) {
						reason = "call ref to " + ft + " from " + r.getFromAddress();
						break;
					}
				}
				Instruction in = (Instruction) cu;
				if (reason == null && in.getMnemonicString().equals("INT3")) {
					reason = "INT3 at " + ft;
				}
				ft = in.getFlowType().isFallthrough() ? in.getFallThrough() : null;
			}
			if (reason != null) {
				byTarget.computeIfAbsent(target != null ? target.getName(true) : "" + flows[0],
					k -> new ArrayList<>()).add(call.getAddress() + " in " +
						(caller != null ? caller.getName(true) : "?") + ": " + reason);
			}
		}
		byTarget.forEach((t, rs) -> {
			System.out.println("NORETURN? " + t + " (" + rs.size() + ")");
			rs.stream().limit(4).forEach(r -> System.out.println("    " + r));
		});
	}
}
