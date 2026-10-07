package stabs.model;

/** A builtin scalar type: integer, character, boolean, floating point, complex or void. */
public final class BaseType implements SType {
	public enum Kind {
		VOID, INT, CHAR, BOOL, FLOAT, COMPLEX
	}

	private final Kind kind;
	private final int size;
	private final boolean unsigned;
	private String name;

	public BaseType(Kind kind, int size, boolean unsigned) {
		this.kind = kind;
		this.size = size;
		this.unsigned = unsigned;
	}

	public Kind kind() {
		return kind;
	}

	/** @return size in bytes (0 for void) */
	public int size() {
		return size;
	}

	public boolean isUnsigned() {
		return unsigned;
	}

	@Override
	public String name() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public BaseType withSize(int newSize) {
		BaseType b = new BaseType(kind, newSize, unsigned);
		b.name = name;
		return b;
	}

	@Override
	public String toString() {
		return name != null ? name
				: kind.name().toLowerCase() + (size > 0 ? size * 8 : "") + (unsigned ? "u" : "");
	}
}
