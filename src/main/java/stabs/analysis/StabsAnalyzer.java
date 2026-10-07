package stabs.analysis;

import java.util.List;

import ghidra.app.services.*;
import ghidra.app.util.importer.MessageLog;
import ghidra.app.util.opinion.ElfLoader;
import ghidra.framework.options.Options;
import ghidra.program.database.mem.FileBytes;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.*;
import ghidra.util.Msg;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;
import stabs.format.StabEntry;
import stabs.format.StabSectionReader;
import stabs.model.StabsProgram;
import stabs.parse.StabsParser;

/**
 * Imports STABS debug information from the {@code .stab}/{@code .stabstr} sections of an ELF
 * program. The import runs once per program; afterwards {@value #STABS_LOADED_OPTION} is set in
 * the program info, the same way the DWARF analyzer does it.
 */
public class StabsAnalyzer extends AbstractAnalyzer {
	static final String NAME = "STABS";
	private static final String DESCRIPTION =
		"Imports STABS debug information (data types, C++ classes, function signatures) " +
			"from .stab/.stabstr sections, as emitted by gcc 2.x.";
	static final String STABS_LOADED_OPTION = "STABS Loaded";

	private static final String OPTION_LOCALS = "Import Local Variables";
	private static final String OPTION_LOCALS_DESC =
		"Adds the stack-frame locals of each function, including those of inlined functions.";
	private static final String OPTION_REGISTER_LOCALS = "Import Register Local Variables";
	private static final String OPTION_REGISTER_LOCALS_DESC =
		"Also names and types the decompiler's variables for locals that live in a register. " +
			"Applied by the STABS Register Locals analyzer, which decompiles the functions " +
			"that have any.";

	private boolean importLocals = true;
	private boolean importRegisterLocals = true;

	private static final String STAB_SECTION = ".stab";
	private static final String STABSTR_SECTION = ".stabstr";

	public StabsAnalyzer() {
		super(NAME, DESCRIPTION, AnalyzerType.BYTE_ANALYZER);
		setDefaultEnablement(true);
		setPriority(AnalysisPriority.FORMAT_ANALYSIS.after());
		setSupportsOneTimeAnalysis();
	}

	@Override
	public void registerOptions(Options options, Program program) {
		options.registerOption(OPTION_LOCALS, importLocals, null, OPTION_LOCALS_DESC);
		options.registerOption(OPTION_REGISTER_LOCALS, importRegisterLocals, null,
			OPTION_REGISTER_LOCALS_DESC);
	}

	@Override
	public void optionsChanged(Options options, Program program) {
		importLocals = options.getBoolean(OPTION_LOCALS, importLocals);
		importRegisterLocals = options.getBoolean(OPTION_REGISTER_LOCALS, importRegisterLocals);
	}

	@Override
	public boolean canAnalyze(Program program) {
		return hasStabs(program);
	}

	static boolean hasStabs(Program program) {
		Memory mem = program.getMemory();
		return ElfLoader.ELF_NAME.equals(program.getExecutableFormat()) &&
			mem.getBlock(STAB_SECTION) != null &&
			mem.getBlock(STABSTR_SECTION) != null;
	}

	@Override
	public boolean added(Program program, AddressSetView set, TaskMonitor monitor, MessageLog log)
			throws CancelledException {
		Options info = program.getOptions(Program.PROGRAM_INFO);
		if (info.getBoolean(STABS_LOADED_OPTION, false)) {
			Msg.info(this, "STABS already imported, skipping.");
			return false;
		}

		try {
			monitor.setMessage("Reading STABS");
			Memory mem = program.getMemory();
			byte[] stab = originalBytes(mem.getBlock(STAB_SECTION));
			byte[] stabstr = originalBytes(mem.getBlock(STABSTR_SECTION));
			List<StabEntry> entries =
				StabSectionReader.read(stab, stabstr, program.getLanguage().isBigEndian());

			monitor.setMessage("Parsing STABS");
			StabsProgram stabs = StabsParser.parse(entries);
			monitor.checkCancelled();

			long delta = imageBaseDelta(program);
			String summary = String.format(
				"STABS: %d units, %d named types, %d functions, %d globals, %d parse issues " +
					"(address delta 0x%x)",
				stabs.units().size(), stabs.namedTypes().size(), stabs.functions().size(),
				stabs.globals().size(), stabs.issues().size(), delta);
			Msg.info(this, summary);
			log.appendMsg(summary);
			stabs.issues().stream().limit(50).forEach(i -> log.appendMsg("STABS: " + i));

			StabsImporter importer = new StabsImporter(program, delta, log, monitor);
			importer.setLocals(importLocals, importRegisterLocals);
			importer.apply(stabs);
			StabsRegisterLocals.put(program, importer.registerLocals());
			Msg.info(this, importer.summary());
			log.appendMsg(importer.summary());
			info.setBoolean(STABS_LOADED_OPTION, true);
			return true;
		}
		catch (MemoryAccessException e) {
			log.appendMsg("STABS: unable to read sections: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Reads a block's bytes as they are in the file, i.e. before Ghidra applied any relocations
	 * (the stab values are link-time addresses and must not be relocated twice).
	 */
	static byte[] originalBytes(MemoryBlock block) throws MemoryAccessException {
		byte[] buf = new byte[(int) block.getSize()];
		List<MemoryBlockSourceInfo> infos = block.getSourceInfos();
		if (infos.size() == 1 && infos.get(0).getFileBytes().isPresent()) {
			FileBytes fb = infos.get(0).getFileBytes().get();
			try {
				int n = fb.getOriginalBytes(infos.get(0).getFileBytesOffset(), buf);
				if (n == buf.length) {
					return buf;
				}
			}
			catch (java.io.IOException e) {
				// fall back to the block contents
			}
		}
		block.getBytes(block.getStart(), buf);
		return buf;
	}

	/** @return how far Ghidra moved the image from its link-time base */
	static long imageBaseDelta(Program program) {
		Long original = ElfLoader.getElfOriginalImageBase(program);
		long base = program.getImageBase().getOffset();
		return original != null ? base - original : 0;
	}
}
