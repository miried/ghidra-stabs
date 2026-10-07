package stabs.model;

public enum Visibility {
	PRIVATE, PROTECTED, PUBLIC, IGNORE;

	/** Decodes a STABS visibility digit; anything unknown is public. */
	public static Visibility of(char c) {
		return switch (c) {
			case '0' -> PRIVATE;
			case '1' -> PROTECTED;
			case '9' -> IGNORE;
			default -> PUBLIC;
		};
	}
}
