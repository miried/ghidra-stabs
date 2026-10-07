package stabs.model;

import java.util.List;

/**
 * A C++ method type ({@code #}). For the abbreviated form {@code ##} only the return type is
 * known; {@code domain} and {@code args} are null and the argument types must be recovered from
 * the method's mangled physical name.
 *
 * @param args argument types excluding the trailing void, including {@code this} for the full
 *             form (g++ lists it first)
 */
public record MethodType(SType domain, SType returnType, List<SType> args, boolean varargs)
		implements SType {

	public boolean isStub() {
		return args == null;
	}
}
