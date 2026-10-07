package stabs.parse;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import stabs.model.StabsProgram;
import stabs.tools.StabsCheck;
import stabs.tools.StabsDump;

/** Parses the MOHAA Linux binaries, if present, and checks the result is self-consistent. */
class RealBinariesTest {
	private static final Path DIR =
		Path.of(System.getProperty("user.home"), "mohaa-lnxclient-beta1");

	@ParameterizedTest
	@ValueSource(strings = { "cgame.so", "fgame.so", "mohaa_lnx" })
	void parsesCleanly(String file) throws Exception {
		Path elf = DIR.resolve(file);
		Assumptions.assumeTrue(Files.exists(elf), "missing " + elf);

		StabsProgram prog = StabsDump.load(elf);
		assertEquals(0, prog.issues().size(), () -> "issues: " + prog.issues().subList(0,
			Math.min(10, prog.issues().size())));

		StabsCheck check = StabsCheck.run(prog);
		assertEquals(0, check.undefinedRefs.size(), "undefined type refs " + check.undefinedRefs);
		assertTrue(check.memberFunctions > 500);
		assertEquals(0, check.unmatchedMemberFunctions.size(),
			"member functions without class physname: " + check.unmatchedMemberFunctions);
	}
}
