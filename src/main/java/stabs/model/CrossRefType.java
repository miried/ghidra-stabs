package stabs.model;

/**
 * A reference to a struct, union or enum by tag name ({@code xsName:}), usually because it is
 * incomplete in this compilation unit. Resolved to a definition after all units are parsed.
 */
public final class CrossRefType implements SType {
	public enum Kind {
		STRUCT, UNION, ENUM
	}

	private final Kind kind;
	private final String tag;
	private final CompileUnit unit;
	private SType resolved;
	private StructType owner;

	public CrossRefType(Kind kind, String tag, CompileUnit unit) {
		this.kind = kind;
		this.tag = tag;
		this.unit = unit;
	}

	public Kind kind() {
		return kind;
	}

	public String tag() {
		return tag;
	}

	public CompileUnit unit() {
		return unit;
	}

	/** @return the definition, or null if none was found (the type stays opaque) */
	public SType resolved() {
		return resolved;
	}

	public void setResolved(SType resolved) {
		this.resolved = resolved;
	}

	/**
	 * @return the struct whose definition contains this reference, or null; nested classes are
	 *         looked up from its scope, the way C++ name lookup works
	 */
	public StructType owner() {
		return owner;
	}

	public void setOwner(StructType owner) {
		this.owner = owner;
	}

	@Override
	public String name() {
		return tag;
	}

	@Override
	public SType resolve() {
		return resolved != null ? resolved.resolve() : this;
	}

	@Override
	public String toString() {
		return "xref " + kind.name().toLowerCase() + " " + tag;
	}
}
