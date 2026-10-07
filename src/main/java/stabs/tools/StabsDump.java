package stabs.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import stabs.format.StabEntry;
import stabs.format.StabSectionReader;
import stabs.model.*;
import stabs.parse.StabsParser;

/**
 * Command line dump of the STABS parsed from an ELF file.
 * <p>
 * Usage: {@code StabsDump <elf> [summary|types|functions|globals|issues|raw] [name-filter]}
 */
public final class StabsDump {

	private StabsDump() {
	}

	public static List<StabEntry> readEntries(Path elf) throws IOException {
		ElfSections secs = ElfSections.read(elf);
		byte[] stab = secs.section(".stab");
		byte[] stabstr = secs.section(".stabstr");
		if (stab == null || stabstr == null) {
			throw new IOException(elf + " has no .stab/.stabstr sections");
		}
		return StabSectionReader.read(stab, stabstr, secs.isBigEndian());
	}

	public static StabsProgram load(Path elf) throws IOException {
		return StabsParser.parse(readEntries(elf));
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 1) {
			System.err.println(
				"usage: StabsDump <elf> [summary|types|functions|globals|issues|raw] [filter]");
			System.exit(2);
		}
		Path elf = Path.of(args[0]);
		String mode = args.length > 1 ? args[1] : "summary";
		String filter = args.length > 2 ? args[2] : null;
		PrintStream out = System.out;

		if (mode.equals("raw")) {
			for (StabEntry e : readEntries(elf)) {
				if (filter == null || e.string().contains(filter)) {
					out.println(e);
				}
			}
			return;
		}

		long t0 = System.nanoTime();
		StabsProgram prog = load(elf);
		long ms = (System.nanoTime() - t0) / 1_000_000;

		switch (mode) {
			case "summary" -> summary(prog, ms, out);
			case "types" -> {
				for (SType t : prog.namedTypes()) {
					if (matches(t.name(), filter)) {
						out.println(TypeFormatter.definition(t));
						out.println();
					}
				}
			}
			case "functions" -> {
				for (Function f : prog.functions()) {
					if (matches(f.name(), filter)) {
						out.println(function(f));
					}
				}
			}
			case "globals" -> {
				for (StabsProgram.Global g : prog.globals()) {
					Variable v = g.variable();
					if (matches(v.name(), filter)) {
						out.printf("%s %s %s @0x%x  [%s]%n", v.storage(),
							TypeFormatter.name(v.type()), v.name(), v.value(), g.unit());
					}
				}
			}
			case "issues" -> prog.issues().forEach(out::println);
			case "check" -> {
				StabsCheck c = StabsCheck.run(prog);
				out.println(c.summary());
				c.undefinedRefs.stream().limit(20).forEach(r -> out.println("  undefined " + r));
				c.unresolvedCrossRefs.stream().limit(20)
						.forEach(x -> out.println("  unresolved " + x));
				c.unmatchedMemberFunctions.stream().limit(40)
						.forEach(f -> out.println("  unmatched " + f));
			}
			default -> throw new IllegalArgumentException("unknown mode " + mode);
		}
	}

	private static boolean matches(String name, String filter) {
		return filter == null || (name != null && name.contains(filter));
	}

	static String function(Function f) {
		String params = f.params().stream()
				.map(p -> TypeFormatter.name(p.type()) + " " + p.name() + " /*" +
					p.storage().name().toLowerCase() + " " + p.value() + "*/")
				.collect(Collectors.joining(", "));
		return String.format("0x%08x %s%s %s(%s)  [%s, %d locals, %d lines]", f.address(),
			f.global() ? "" : "static ", TypeFormatter.name(f.returnType()), f.name(), params,
			f.unit(), f.locals().size(), f.lines().size());
	}

	private static void summary(StabsProgram prog, long ms, PrintStream out) {
		Map<String, Integer> kinds = new TreeMap<>();
		int classes = 0;
		for (SType t : prog.namedTypes()) {
			kinds.merge(t.getClass().getSimpleName(), 1, Integer::sum);
			if (t instanceof StructType st && st.isCppClass()) {
				classes++;
			}
		}
		out.printf("parsed in %d ms%n", ms);
		out.printf("compile units: %d%n", prog.units().size());
		out.printf("named types:   %d %s (%d C++ classes)%n", prog.namedTypes().size(), kinds,
			classes);
		out.printf("functions:     %d%n", prog.functions().size());
		out.printf("globals:       %d%n", prog.globals().size());
		out.printf("issues:        %d%n", prog.issues().size());
		prog.issues().stream().limit(20).forEach(i -> out.println("  " + i));
	}
}
