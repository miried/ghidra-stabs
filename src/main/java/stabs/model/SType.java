package stabs.model;

/**
 * A type parsed from STABS. Types referenced by type number are wrapped in a {@link TypeRef}
 * whose target is filled in when the definition is seen; call {@link #resolve()} to look
 * through references, typedef-free.
 */
public interface SType {

	/** @return the name of the type, or null if it is anonymous or unnamed */
	default String name() {
		return null;
	}

	/** @return this type with {@link TypeRef}s and resolved {@link CrossRefType}s followed */
	default SType resolve() {
		return this;
	}

	/** Follows references and typedefs down to the underlying type. */
	static SType strip(SType t) {
		for (int guard = 0; guard < 1000; guard++) {
			SType r = t.resolve();
			if (r instanceof TypedefType td) {
				t = td.target();
				continue;
			}
			return r;
		}
		return t;
	}
}
