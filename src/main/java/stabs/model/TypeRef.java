package stabs.model;

/**
 * A numbered type slot, e.g. {@code (12,3)}. Its target is set when the type's definition is
 * parsed; it stays null for types that are referenced but never defined.
 */
public final class TypeRef implements SType {
	private final TypeTable table;
	private final int index;
	private SType target;

	TypeRef(TypeTable table, int index) {
		this.table = table;
		this.index = index;
	}

	public TypeTable table() {
		return table;
	}

	public int index() {
		return index;
	}

	public SType target() {
		return target;
	}

	public void setTarget(SType target) {
		this.target = target;
	}

	@Override
	public String name() {
		SType r = resolve();
		return r == this ? null : r.name();
	}

	@Override
	public SType resolve() {
		SType t = this;
		for (int guard = 0; guard < 1000; guard++) {
			SType next = t instanceof TypeRef r ? r.target
					: t instanceof CrossRefType x ? x.resolved() : null;
			if (next == null) {
				return t;
			}
			t = next;
		}
		return t;
	}

	@Override
	public String toString() {
		return table.name() + "#" + index;
	}
}
