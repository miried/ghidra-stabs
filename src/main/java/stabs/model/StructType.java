package stabs.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A struct, union or C++ class ({@code s}/{@code u}). STABS does not distinguish {@code class}
 * from {@code struct}; a type is C++-ish if it has base classes, methods or a vtable pointer.
 */
public final class StructType implements SType {

	public record BaseClass(SType type, long bitOffset, boolean isVirtual, Visibility visibility) {
	}

	public enum FieldKind {
		NORMAL,
		/** The vtable pointer ({@code $vf}/{@code .vf}); {@link Field#context()} is its owner. */
		VPTR,
		/** A virtual base pointer ({@code $vb}/{@code .vb}). */
		VBASE_PTR
	}

	/**
	 * A non-static data member. {@code bitSize} is the declared size in bits (not necessarily a
	 * bitfield; compare against the type size).
	 */
	public record Field(String name, SType type, long bitOffset, long bitSize,
			Visibility visibility, FieldKind kind, SType context) {
	}

	/** A static data member; {@code physname} is the mangled name of its storage. */
	public record StaticField(String name, SType type, String physname, Visibility visibility) {
	}

	public enum MethodKind {
		NORMAL, VIRTUAL, STATIC
	}

	/**
	 * One overload of a member function.
	 *
	 * @param physname mangled name of the method (reconstructed for abbreviated stubs)
	 * @param vtableIndex the vtable index as emitted by g++ (meaningful for VIRTUAL only)
	 * @param vtableContext the class whose vtable holds the slot (VIRTUAL only, may be null)
	 */
	public record Method(String name, String physname, MethodType type, Visibility visibility,
			boolean isConst, boolean isVolatile, MethodKind kind, long vtableIndex,
			SType vtableContext) {
	}

	private final boolean union;
	private final long size;
	private final CompileUnit unit;
	private final List<BaseClass> baseClasses = new ArrayList<>();
	private final List<Field> fields = new ArrayList<>();
	private final List<StaticField> staticFields = new ArrayList<>();
	private final List<Method> methods = new ArrayList<>();
	private String name;
	private SType vptrBase;
	private boolean ownVptr;

	public StructType(boolean union, long size, CompileUnit unit) {
		this.union = union;
		this.size = size;
		this.unit = unit;
	}

	public boolean isUnion() {
		return union;
	}

	/** @return size in bytes */
	public long size() {
		return size;
	}

	/** @return the unit in which the type was defined */
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

	public List<BaseClass> baseClasses() {
		return baseClasses;
	}

	public List<Field> fields() {
		return fields;
	}

	public List<StaticField> staticFields() {
		return staticFields;
	}

	public List<Method> methods() {
		return methods;
	}

	/** @return the base class holding this class's vtable pointer, if not this class itself */
	public SType vptrBase() {
		return vptrBase;
	}

	/** @return whether this class introduces its own vtable pointer */
	public boolean ownVptr() {
		return ownVptr;
	}

	public void setVptr(SType base, boolean own) {
		this.vptrBase = base;
		this.ownVptr = own;
	}

	public boolean isCppClass() {
		return !baseClasses.isEmpty() || !methods.isEmpty() || !staticFields.isEmpty() ||
			vptrBase != null || ownVptr;
	}

	@Override
	public String toString() {
		return (union ? "union " : "struct ") + (name != null ? name : "<anon>");
	}
}
