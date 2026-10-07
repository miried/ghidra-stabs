package stabs.parse;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import stabs.model.StabsProgram;
import stabs.tools.ElfSections;
import stabs.tools.StabsCheck;
import stabs.tools.StabsDump;

/**
 * Parses every ELF with STABS in the directory given by the {@code stabs.testBinaries} system
 * property (see build.gradle) and checks the result is self-consistent. Skipped when unset.
 */
class RealBinariesTest {

	@TestFactory
	Stream<DynamicTest> parsesCleanly() throws IOException {
		String dir = System.getProperty("stabs.testBinaries");
		Assumptions.assumeTrue(dir != null && !dir.isEmpty(), "stabs.testBinaries not set");
		List<Path> elfs;
		try (Stream<Path> files = Files.list(Path.of(dir))) {
			elfs = files.filter(Files::isRegularFile).filter(RealBinariesTest::hasStabs)
				.sorted().toList();
		}
		Assumptions.assumeFalse(elfs.isEmpty(), "no STABS binaries in " + dir);
		return elfs.stream().map(elf -> DynamicTest.dynamicTest(elf.getFileName().toString(),
			() -> check(elf)));
	}

	private static boolean hasStabs(Path file) {
		try {
			ElfSections elf = ElfSections.read(file);
			return elf.section(".stab") != null && elf.section(".stabstr") != null;
		}
		catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static void check(Path elf) throws IOException {
		StabsProgram prog = StabsDump.load(elf);
		assertEquals(0, prog.issues().size(), () -> "issues: " + prog.issues().subList(0,
			Math.min(10, prog.issues().size())));

		StabsCheck check = StabsCheck.run(prog);
		assertEquals(0, check.undefinedRefs.size(), "undefined type refs " + check.undefinedRefs);
		assertEquals(0, check.unmatchedMemberFunctions.size(),
			"member functions without class physname: " + check.unmatchedMemberFunctions);
	}
}
