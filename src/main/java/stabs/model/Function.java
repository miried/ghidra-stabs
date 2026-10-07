package stabs.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A function definition ({@code N_FUN} with {@code F}/{@code f}) together with the parameters,
 * locals and line numbers that follow it.
 *
 * @param name the symbol name as emitted, i.e. mangled for C++
 * @param address link-time address of the function
 */
public record Function(String name, boolean global, long address, SType returnType,
		CompileUnit unit, List<Variable> params, List<Local> locals, List<Line> lines) {

	/**
	 * A local variable with the lexical block it belongs to.
	 *
	 * @param blockDepth nesting depth of the block, 1 for the function body
	 * @param blockStart start of the block relative to the function, -1 if unknown
	 * @param blockEnd end of the block relative to the function, -1 if unknown
	 */
	public record Local(Variable variable, int blockDepth, long blockStart, long blockEnd) {
	}

	/** A line number entry; {@code address} is absolute (link-time). */
	public record Line(long address, int line, String file) {
	}

	public Function(String name, boolean global, long address, SType returnType,
			CompileUnit unit) {
		this(name, global, address, returnType, unit, new ArrayList<>(), new ArrayList<>(),
			new ArrayList<>());
	}
}
