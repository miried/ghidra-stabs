package stabs.model;

import java.util.ArrayList;
import java.util.List;

/** Everything parsed from one binary's STABS. */
public final class StabsProgram {

	/** A file-level global or static variable and the unit that defined it. */
	public record Global(Variable variable, CompileUnit unit) {
	}

	private final List<CompileUnit> units = new ArrayList<>();
	private final List<SType> namedTypes = new ArrayList<>();
	private final List<Function> functions = new ArrayList<>();
	private final List<Global> globals = new ArrayList<>();
	private final List<ParseIssue> issues = new ArrayList<>();

	public List<CompileUnit> units() {
		return units;
	}

	/**
	 * @return the named struct, union, enum, typedef and base types in definition order; types
	 *         from headers shared through N_EXCL appear once
	 */
	public List<SType> namedTypes() {
		return namedTypes;
	}

	public List<Function> functions() {
		return functions;
	}

	/** @return file-level globals and statics */
	public List<Global> globals() {
		return globals;
	}

	public List<ParseIssue> issues() {
		return issues;
	}
}
