package stabs.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The types of one file in the STABS type numbering scheme: either the main source file of a
 * compilation unit (file number 0) or a header file opened with N_BINCL. Headers excluded with
 * N_EXCL in later units share the table of the N_BINCL that defined them.
 */
public final class TypeTable {
	private final String name;
	private final long hash;
	private final CompileUnit owner;
	private final List<TypeRef> slots = new ArrayList<>();

	public TypeTable(String name, long hash, CompileUnit owner) {
		this.name = name;
		this.hash = hash;
		this.owner = owner;
	}

	public String name() {
		return name;
	}

	public long hash() {
		return hash;
	}

	/** @return the compilation unit in which this table's types were defined */
	public CompileUnit owner() {
		return owner;
	}

	public TypeRef slot(int index) {
		while (slots.size() <= index) {
			slots.add(null);
		}
		TypeRef r = slots.get(index);
		if (r == null) {
			r = new TypeRef(this, index);
			slots.set(index, r);
		}
		return r;
	}

	@Override
	public String toString() {
		return name;
	}
}
