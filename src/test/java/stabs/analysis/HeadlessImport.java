package stabs.analysis;

import java.io.File;
import java.nio.file.Files;
import java.util.Iterator;

import ghidra.GhidraApplicationLayout;
import ghidra.app.util.importer.MessageLog;
import ghidra.base.project.GhidraProject;
import ghidra.framework.Application;
import ghidra.framework.HeadlessGhidraApplicationConfiguration;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.Symbol;
import ghidra.util.task.TaskMonitor;

/**
 * Imports an ELF into a throwaway Ghidra project, runs the STABS analyzer on it (without the
 * rest of auto analysis) and prints what it applied:
 * {@code ./gradlew importStabs [-Pfile=<elf>] [-Pargs="<struct names / function filters>"]}.
 */
public final class HeadlessImport {
	public static void main(String[] args) throws Exception {
		Application.initializeApplication(new GhidraApplicationLayout(),
			new HeadlessGhidraApplicationConfiguration());
		File elf = new File(args[0]);
		File dir = Files.createTempDirectory("stabs-headless").toFile();
		GhidraProject project = GhidraProject.createProject(dir.getPath(), "p", true);
		try {
			Program program = project.importProgram(elf);
			int tx = program.startTransaction("STABS");
			MessageLog log = new MessageLog();
			StabsAnalyzer analyzer = new StabsAnalyzer();
			if (!analyzer.canAnalyze(program)) {
				System.out.println("analyzer does not apply");
				return;
			}
			analyzer.added(program, program.getMemory(), TaskMonitor.DUMMY, log);
			program.endTransaction(tx, true);
			System.out.println(log);

			DataTypeManager dtm = program.getDataTypeManager();
			Category cat = dtm.getCategory(StabsTypeImporter.ROOT);
			System.out.println("types in /STABS: " + (cat == null ? 0 : cat.getDataTypes().length));
			int conflicts = 0;
			for (Iterator<DataType> it = dtm.getAllDataTypes(); it.hasNext();) {
				DataType dt = it.next();
				if (dt.getName().contains(".conflict")) {
					if (conflicts++ < 40) {
						System.out.println("  conflict: " + dt.getPathName());
					}
				}
			}
			System.out.println("conflict types: " + conflicts);
			if (cat != null && System.getenv("STABS_LIST") != null) {
				for (DataType dt : cat.getDataTypes()) {
					System.out.println("  type: " + dt.getName());
				}
			}

			for (Function f : program.getFunctionManager().getFunctions(true)) {
				if (f.hasCustomVariableStorage()) {
					System.out.printf("custom storage: %s  %s%n", f.getEntryPoint(),
						f.getSignature().getPrototypeString(true));
				}
			}

			for (int i = 1; i < args.length; i++) {
				String q = args[i];
				// nested classes live in subcategories (/STABS/con_set<...>/Entry)
				for (Iterator<DataType> it = dtm.getAllDataTypes(); it.hasNext();) {
					DataType dt = it.next();
					if (dt.getName().equals(q) &&
						dt.getCategoryPath().isAncestorOrSelf(StabsTypeImporter.ROOT)) {
						print(dt);
					}
				}
				for (Function f : program.getFunctionManager().getFunctions(true)) {
					if (f.getName(true).contains(q)) {
						System.out.printf("%s  %s [%s]%n", f.getEntryPoint(),
							f.getSignature().getPrototypeString(true),
							f.hasCustomVariableStorage() ? "custom"
									: f.getCallingConventionName());
						for (Variable v : f.getLocalVariables()) {
							System.out.printf("    local %-24s %-12s first use +%x%n",
								v.getDataType().getDisplayName() + " " + v.getName(),
								v.getVariableStorage(), v.getFirstUseOffset());
						}
					}
				}
				for (Symbol s : program.getSymbolTable().getSymbols(q)) {
					Data d = program.getListing().getDataAt(s.getAddress());
					if (d != null) {
						System.out.printf("%s  data %s : %s (primary %s)%n", s.getAddress(),
							s.getName(true), d.getDataType().getPathName(),
							program.getSymbolTable().getPrimarySymbol(s.getAddress()).getName(true));
					}
				}
			}
		}
		finally {
			project.close();
		}
	}

	private static void print(DataType dt) {
		System.out.println(dt.getPathName() + " (" + dt.getLength() + " bytes)");
		if (dt instanceof Composite c) {
			for (DataTypeComponent comp : c.getDefinedComponents()) {
				System.out.printf("  +%-4d %-30s %s%n", comp.getOffset(),
					comp.getDataType().getDisplayName(), comp.getFieldName());
			}
		}
		else if (dt instanceof TypeDef td) {
			System.out.println("  = " + td.getDataType().getPathName());
		}
	}
}
