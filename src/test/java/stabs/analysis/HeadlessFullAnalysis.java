package stabs.analysis;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ghidra.GhidraApplicationLayout;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.plugin.core.analysis.AutoAnalysisManager;
import ghidra.base.project.GhidraProject;
import ghidra.framework.Application;
import ghidra.framework.HeadlessGhidraApplicationConfiguration;
import ghidra.framework.options.Options;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.task.TaskMonitor;

/**
 * Runs full auto analysis on a throwaway import, with or without the STABS analyzer, lists the
 * functions marked noreturn, decompiles the given functions and counts how many imported
 * register locals the decompiler uses (in the first 300 functions that have any): {@code ./gradlew fullAnalysis [-Pfile=<elf>] [-Pargs="stabs|nostabs [functions to decompile]"]}.
 * <p>
 * System properties (pass them through {@code JAVA_TOOL_OPTIONS}): {@code stabs.disable=<file>}
 * disables the analyzers named in the file, one per line; {@code stabs.listAnalyzers=true}
 * lists the enabled analyzers; {@code stabs.noReturnReasons=true} prints why calls look
 * non-returning, see {@link NoReturnReasons}.
 */
public final class HeadlessFullAnalysis {
	public static void main(String[] args) throws Exception {
		Application.initializeApplication(new GhidraApplicationLayout(),
			new HeadlessGhidraApplicationConfiguration());
		File elf = new File(args[0]);
		boolean stabs = !(args.length > 1 && args[1].equals("nostabs"));
		List<String> decompile = Arrays.asList(args).subList(Math.min(args.length, 2),
			args.length);
		File dir = Files.createTempDirectory("stabs-full").toFile();
		GhidraProject project = GhidraProject.createProject(dir.getPath(), "p", true);
		try {
			Program program = project.importProgram(elf);
			int tx = program.startTransaction("options");
			Options opts = program.getOptions(Program.ANALYSIS_PROPERTIES);
			opts.setBoolean(StabsAnalyzer.NAME, stabs);
			if (System.getProperty("stabs.disable") != null) {
				for (String a : Files.readAllLines(new File(System.getProperty("stabs.disable")).toPath())) {
					opts.setBoolean(a, false);
				}
			}
			program.endTransaction(tx, true);
			AutoAnalysisManager mgr = AutoAnalysisManager.getAnalysisManager(program);
			tx = program.startTransaction("analysis");
			mgr.initializeOptions();
			mgr.reAnalyzeAll(null);
			mgr.startAnalysis(TaskMonitor.DUMMY);
			program.endTransaction(tx, true);
			if (Boolean.getBoolean("stabs.listAnalyzers")) {
				for (String o : opts.getOptionNames()) {
					if (!o.contains(".") && opts.getType(o) ==
						ghidra.framework.options.OptionType.BOOLEAN_TYPE && opts.getBoolean(o, false)) {
						System.out.println("ANALYZER " + o);
					}
				}
			}
			System.out.println("STABS loaded: " + program.getOptions(Program.PROGRAM_INFO)
					.getBoolean(StabsAnalyzer.STABS_LOADED_OPTION, false));
			int n = 0;
			for (Function f : program.getFunctionManager().getFunctions(true)) {
				if (f.hasNoReturn()) {
					n++;
					System.out.println("noreturn " + f.getEntryPoint() + " " + f.getName(true));
				}
			}
			System.out.println("noreturn total " + n + ", functions " +
				program.getFunctionManager().getFunctionCount());

			if (Boolean.getBoolean("stabs.noReturnReasons")) {
				NoReturnReasons.print(program);
			}
			DecompInterface decomp = new DecompInterface();
			decomp.openProgram(program);
			for (Function f : program.getFunctionManager().getFunctions(true)) {
				if (decompile.contains(f.getName()) || decompile.contains(f.getName(true))) {
					for (Variable v : f.getLocalVariables()) {
						System.out.printf("    local %-30s %-14s first use +%x%n",
							v.getDataType().getDisplayName() + " " + v.getName(),
							v.getVariableStorage(), v.getFirstUseOffset());
					}
					DecompileResults r = decomp.decompileFunction(f, 60, TaskMonitor.DUMMY);
					System.out.println(r.decompileCompleted()
							? r.getDecompiledFunction().getC() : r.getErrorMessage());
				}
			}
			// how many imported register locals the decompiler actually uses
			int declared = 0, used = 0, funcs = 0;
			for (Function f : program.getFunctionManager().getFunctions(true)) {
				List<Variable> regs = new ArrayList<>();
				for (Variable v : f.getLocalVariables()) {
					if (v.isRegisterVariable() && v.getSource() == SourceType.IMPORTED) {
						regs.add(v);
					}
				}
				if (regs.isEmpty() || funcs++ >= 300) {
					continue;
				}
				DecompileResults r = decomp.decompileFunction(f, 60, TaskMonitor.DUMMY);
				if (!r.decompileCompleted()) {
					continue;
				}
				String c = r.getDecompiledFunction().getC();
				for (Variable v : regs) {
					declared++;
					Matcher m = Pattern.compile("\\b" + Pattern.quote(v.getName()) + "\\b")
							.matcher(c);
					int count = 0;
					while (m.find()) {
						count++;
					}
					if (count > 1) {
						used++;
					}
				}
			}
			System.out.println("register locals used by the decompiler: " + used + " of " +
				declared + " in " + Math.min(funcs, 300) + " functions");
			decomp.dispose();
		}
		finally {
			project.close();
		}
	}
}
