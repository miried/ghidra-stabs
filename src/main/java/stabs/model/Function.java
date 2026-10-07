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

	/** A local variable or local type-less entry, with the block nesting depth it appeared at. */
	public record Local(Variable variable, int blockDepth) {
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
