package stabs.model;

import java.util.List;

public final class EnumType implements SType {
	public record Enumerator(String name, long value) {
	}

	private final List<Enumerator> values;
	private final CompileUnit unit;
	private String name;

	public EnumType(List<Enumerator> values, CompileUnit unit) {
		this.values = values;
		this.unit = unit;
	}

	public List<Enumerator> values() {
		return values;
	}

	/** @return the unit in which the enum was defined */
	public CompileUnit unit() {
		return unit;
	}

	@Override
	public String name() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@Override
	public String toString() {
		return "enum " + (name != null ? name : "<anon>");
	}
}
