package stabs.model;

/**
 * A variable or parameter.
 *
 * @param value meaning depends on storage: frame offset, register number or link-time address
 */
public record Variable(String name, SType type, Storage storage, long value) {

	public enum Storage {
		/** Global; the address must be looked up in the symbol table ({@code G}). */
		GLOBAL,
		/** File-level static at {@code value} ({@code S}). */
		STATIC,
		/** Function-local static at {@code value} ({@code V}). */
		LOCAL_STATIC,
		/** On the stack at frame-pointer offset {@code value}. */
		STACK,
		/** In register {@code value} (gcc register numbering). */
		REGISTER,
		/** Parameter passed by reference, its address at frame offset {@code value} ({@code v}). */
		REF_STACK,
		/** Parameter passed by reference, its address in register {@code value} ({@code a}). */
		REF_REGISTER
	}
}
