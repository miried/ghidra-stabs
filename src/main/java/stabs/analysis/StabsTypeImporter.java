package stabs.analysis;

import java.util.*;

import ghidra.app.util.importer.MessageLog;
import ghidra.program.model.data.*;
import ghidra.util.Msg;
import stabs.model.*;
import stabs.model.PointerType;
import stabs.model.StructType.BaseClass;
import stabs.model.StructType.Field;
import stabs.model.StructType.FieldKind;

/**
 * Converts STABS model types into Ghidra {@link DataType}s and adds them to a data type
 * manager under {@code /STABS}.
 * <p>
 * Types are first built as unresolved {@code *DataType} impls that reference each other, the
 * way the DWARF importer does it, and then added in one go. A struct is registered before its
 * members are converted, so self references through pointers work.
 * <p>
 * The same header can be N_BINCL'd with different hashes, which gives several identical
 * {@link StructType}s for one class. Named structs, unions, enums and typedefs are therefore
 * deduplicated by name and layout before conversion; anything that slips through is handled by
 * the {@link DataTypeConflictHandler#DEFAULT_HANDLER}, which reuses equivalent types.
 */
final class StabsTypeImporter {
	static final CategoryPath ROOT = new CategoryPath("/STABS");

	/** Names g++ gives to the builtin types, mapped to Ghidra's builtins. */
	private static final Map<String, DataType> BUILTINS = Map.ofEntries(
		Map.entry("void", VoidDataType.dataType),
		Map.entry("char", CharDataType.dataType),
		Map.entry("signed char", SignedCharDataType.dataType),
		Map.entry("unsigned char", UnsignedCharDataType.dataType),
		Map.entry("short int", ShortDataType.dataType),
		Map.entry("short unsigned int", UnsignedShortDataType.dataType),
		Map.entry("int", IntegerDataType.dataType),
		Map.entry("unsigned int", UnsignedIntegerDataType.dataType),
		Map.entry("long int", LongDataType.dataType),
		Map.entry("long unsigned int", UnsignedLongDataType.dataType),
		Map.entry("long long int", LongLongDataType.dataType),
		Map.entry("long long unsigned int", UnsignedLongLongDataType.dataType),
		Map.entry("float", FloatDataType.dataType),
		Map.entry("double", DoubleDataType.dataType),
		Map.entry("long double", LongDoubleDataType.dataType),
		Map.entry("bool", BooleanDataType.dataType),
		Map.entry("wchar_t", WideCharDataType.dataType));

	private final DataTypeManager dtm;
	private final MessageLog log;
	private final boolean bigEndian;
	private final Map<SType, DataType> converted = new IdentityHashMap<>();
	private final Map<String, DataType> byLayout = new HashMap<>();
	private final Map<DataType, DataType> resolved = new IdentityHashMap<>();
	private final Map<String, Integer> nameUses = new HashMap<>();
	private final Set<DataType> filling = Collections.newSetFromMap(new IdentityHashMap<>());
	private int anonCount;
	private int failures;

	StabsTypeImporter(DataTypeManager dtm, boolean bigEndian, MessageLog log) {
		this.dtm = dtm;
		this.bigEndian = bigEndian;
		this.log = log;
	}

	int failures() {
		return failures;
	}

	/** Converts and adds all named types. */
	void importTypes(List<SType> namedTypes) {
		for (SType t : namedTypes) {
			try {
				resolve(convert(t));
			}
			catch (IllegalArgumentException e) {
				warn(t + ": " + e.getMessage());
			}
		}
	}

	/**
	 * Makes names unique, the way Ghidra's conflict handler would ({@code Entry.conflict}).
	 * It has to be done before resolving: g++ 2.95 names nested classes without their
	 * enclosing class, so e.g. two different {@code Entry} classes can contain one another,
	 * which Ghidra would reject as a cyclic dependency.
	 */
	private String uniqueName(String name) {
		int n = nameUses.merge(name, 1, Integer::sum);
		return n == 1 ? name : name + ".conflict" + (n == 2 ? "" : String.valueOf(n - 2));
	}

	/** @return the data type for a STABS type, added to the data type manager */
	DataType get(SType t) {
		return resolve(convert(t));
	}

	private DataType resolve(DataType dt) {
		if (dt == null) {
			return null;
		}
		DataType r = resolved.get(dt);
		if (r == null) {
			try {
				r = dtm.resolve(dt, DataTypeConflictHandler.DEFAULT_HANDLER);
			}
			catch (IllegalArgumentException e) {
				warn(dt.getPathName() + ": " + e.getMessage() + cyclePath(dt));
				r = dt.getLength() > 0 ? Undefined.getUndefinedDataType(dt.getLength())
						: DataType.DEFAULT;
			}
			resolved.put(dt, r);
		}
		return r;
	}

	private static String cyclePath(DataType dt) {
		Deque<String> path = new ArrayDeque<>();
		return findCycle(dt, dt.getName(), path, new HashSet<>()) ? " via " + path : "";
	}

	private static boolean findCycle(DataType dt, String target, Deque<String> path,
			Set<DataType> seen) {
		if (!(dt instanceof Composite c) || !seen.add(dt)) {
			return false;
		}
		for (DataTypeComponent comp : c.getDefinedComponents()) {
			DataType t = comp.getDataType();
			while (t instanceof TypeDef || t instanceof Array) {
				t = t instanceof TypeDef td ? td.getDataType() : ((Array) t).getDataType();
			}
			path.addLast(dt.getName() + "." + comp.getFieldName() + ":" + t.getName());
			if (t.getName().equals(target) || findCycle(t, target, path, seen)) {
				return true;
			}
			path.removeLast();
		}
		return false;
	}

	/** @return the Ghidra type for {@code t}, not necessarily resolved yet */
	DataType convert(SType t) {
		if (t == null) {
			return DataType.DEFAULT;
		}
		DataType dt = converted.get(t);
		if (dt != null) {
			return dt;
		}
		dt = switch (t) {
			case TypeRef r -> r.target() == null || r.resolve() == r ? Undefined4DataType.dataType
					: convert(r.target());
			case CrossRefType x -> x.resolved() != null ? convert(x.resolved()) : opaque(x);
			case BaseType b -> baseType(b);
			case PointerType p -> new PointerDataType(convert(p.target()), dtm);
			case ReferenceType p -> new PointerDataType(convert(p.target()), dtm);
			case ConstType c -> convert(c.target());
			case VolatileType v -> convert(v.target());
			case TypedefType td -> typedef(td);
			case ArrayType a -> array(a);
			case FunctionType f -> functionDef(f.returnType());
			case MethodType m -> functionDef(m.returnType());
			case MemberPointerType mp -> mp.memberType().resolve() instanceof MethodType
					? new PointerDataType(functionDef(((MethodType) mp.memberType().resolve())
							.returnType()), dtm)
					: IntegerDataType.dataType;
			case RangeType rt -> sizedInt(rangeSize(rt), rt.lower() < 0);
			case EnumType en -> enumType(en);
			case StructType st -> struct(st);
			default -> Undefined4DataType.dataType;
		};
		converted.put(t, dt);
		return dt;
	}

	private DataType baseType(BaseType b) {
		if (b.name() != null) {
			DataType builtin = BUILTINS.get(b.name());
			if (builtin != null && (builtin.getLength() <= 0 ||
				builtin.getLength() == b.size() || b.kind() == BaseType.Kind.VOID)) {
				return builtin;
			}
		}
		DataType dt = switch (b.kind()) {
			case VOID -> VoidDataType.dataType;
			case BOOL -> b.size() == 1 ? BooleanDataType.dataType : sizedInt(b.size(), false);
			case CHAR -> b.size() == 1
					? (b.isUnsigned() ? UnsignedCharDataType.dataType : CharDataType.dataType)
					: sizedInt(b.size(), !b.isUnsigned());
			case FLOAT -> {
				DataType f = AbstractFloatDataType.getFloatDataType(b.size(), dtm);
				yield f != null ? f : Undefined.getUndefinedDataType(b.size());
			}
			case COMPLEX -> switch (b.size()) {
				case 8 -> Complex8DataType.dataType;
				case 16 -> Complex16DataType.dataType;
				case 32 -> Complex32DataType.dataType;
				default -> Undefined.getUndefinedDataType(b.size());
			};
			case INT -> sizedInt(b.size(), !b.isUnsigned());
		};
		if (b.name() != null && !b.name().equals(dt.getName()) && dt != VoidDataType.dataType) {
			return new TypedefDataType(ROOT, b.name(), dt, dtm);
		}
		return dt;
	}

	private DataType sizedInt(int size, boolean signed) {
		if (size <= 0) {
			return Undefined4DataType.dataType;
		}
		DataType dt = signed ? AbstractIntegerDataType.getSignedDataType(size, dtm)
				: AbstractIntegerDataType.getUnsignedDataType(size, dtm);
		return dt != null ? dt : Undefined.getUndefinedDataType(size);
	}

	private static int rangeSize(RangeType rt) {
		long span = Math.max(Math.abs(rt.lower()), Math.abs(rt.upper()));
		return span <= 0xff ? 1 : span <= 0xffff ? 2 : span <= 0xffffffffL ? 4 : 8;
	}

	private DataType typedef(TypedefType td) {
		DataType target = convert(td.target());
		if (target == DataType.DEFAULT || target.getLength() == 0 && !(target instanceof
			Composite || target instanceof VoidDataType)) {
			return target;
		}
		// vec3_t is "float[3]" in some units and "vec_t[3]" in others: same type
		String key = "t " + td.name() + " " + canonical(target, 2) + " " + target.getLength();
		DataType dt = byLayout.get(key);
		if (dt == null) {
			dt = new TypedefDataType(ROOT, uniqueName(td.name()), target, dtm);
			byLayout.put(key, dt);
		}
		return dt;
	}

	/** @return a name for {@code dt} that looks through typedefs, pointers and arrays */
	private static String canonical(DataType dt, int depth) {
		return switch (dt) {
			case TypeDef td -> canonical(td.getBaseDataType(), depth);
			case Array a -> canonical(a.getDataType(), depth) + "[" + a.getNumElements() + "]";
			case Pointer p -> depth > 0 && p.getDataType() != null
					? canonical(p.getDataType(), depth - 1) + " *"
					: "*";
			default -> dt.getName();
		};
	}

	private DataType array(ArrayType a) {
		DataType elem = convert(a.element());
		int len = elem.getLength();
		if (len <= 0 || elem instanceof VoidDataType) {
			elem = Undefined1DataType.dataType;
			len = 1;
		}
		long count = a.count();
		if (count > Integer.MAX_VALUE / len) {
			count = 0;
		}
		return new ArrayDataType(elem, (int) count, len, dtm);
	}

	private DataType functionDef(SType returnType) {
		DataType ret = convert(returnType);
		String name = "_func_" + ret.getName().replaceAll("[^A-Za-z0-9_]", "_");
		DataType dt = byLayout.get("f " + name);
		if (dt == null) {
			FunctionDefinitionDataType fd = new FunctionDefinitionDataType(ROOT, name, dtm);
			fd.setReturnType(ret);
			dt = fd;
			byLayout.put("f " + name, dt);
		}
		return dt;
	}

	private DataType opaque(CrossRefType x) {
		String key = "x " + x.kind() + " " + x.tag();
		return byLayout.computeIfAbsent(key, k -> x.kind() == CrossRefType.Kind.ENUM
				? new EnumDataType(ROOT, x.tag(), 4, dtm)
				: x.kind() == CrossRefType.Kind.UNION ? new UnionDataType(ROOT, x.tag(), dtm)
						: new StructureDataType(ROOT, x.tag(), 0, dtm));
	}

	private static boolean isAnonymous(String name) {
		return name == null || name.isEmpty() || name.startsWith("._") ||
			name.startsWith("$_");
	}

	private DataType enumType(EnumType en) {
		boolean anon = isAnonymous(en.name());
		StringBuilder key = new StringBuilder("e ").append(en.name());
		en.values().forEach(v -> key.append(' ').append(v.name()).append('=').append(v.value()));
		if (!anon && byLayout.containsKey(key.toString())) {
			return byLayout.get(key.toString());
		}
		String name = anon ? "_anon_enum_" + (++anonCount) : uniqueName(en.name());
		EnumDataType dt = new EnumDataType(ROOT, name, 4, dtm);
		for (EnumType.Enumerator v : en.values()) {
			try {
				dt.add(v.name(), v.value());
			}
			catch (IllegalArgumentException e) {
				warn("enum " + name + ": " + v.name() + ": " + e.getMessage());
			}
		}
		byLayout.put(key.toString(), dt);
		return dt;
	}

	/**
	 * @return a key that identifies a struct definition by name and layout, including member
	 *         types a few levels deep: different nested classes such as
	 *         {@code con_map<K,V>::Entry} are all just called {@code Entry}
	 */
	private static String layoutKey(StructType st, int depth) {
		StringBuilder sb = new StringBuilder(st.isUnion() ? "u " : "s ");
		sb.append(st.name()).append(' ').append(st.size());
		if (depth <= 0) {
			return sb.toString();
		}
		sb.append(" {");
		for (BaseClass b : st.baseClasses()) {
			sb.append(" :").append(typeKey(b.type(), depth - 1)).append('@')
					.append(b.bitOffset());
		}
		for (Field f : st.fields()) {
			sb.append(' ').append(f.name()).append(':').append(typeKey(f.type(), depth - 1))
					.append('@').append(f.bitOffset()).append('/').append(f.bitSize());
		}
		return sb.append(" }").toString();
	}

	/** Describes a member type for {@link #layoutKey}, looking through typedefs. */
	private static String typeKey(SType t, int depth) {
		SType r = t == null ? null : SType.strip(t);
		return switch (r) {
			case null -> "?";
			case StructType st -> depth > 0 ? layoutKey(st, depth) : String.valueOf(st.name());
			case CrossRefType x -> x.tag();
			case PointerType p -> typeKey(p.target(), 0) + "*";
			case ReferenceType p -> typeKey(p.target(), 0) + "&";
			case ConstType c -> typeKey(c.target(), depth);
			case VolatileType v -> typeKey(v.target(), depth);
			case ArrayType a -> typeKey(a.element(), depth) + "[" + a.count() + "]";
			case BaseType b -> b.kind() + "" + b.size() + (b.isUnsigned() ? "u" : "");
			default -> r.name() != null ? r.name() : r.getClass().getSimpleName();
		};
	}

	private DataType struct(StructType st) {
		boolean anon = isAnonymous(st.name());
		String key = anon ? null : layoutKey(st, 3);
		if (key != null && byLayout.containsKey(key)) {
			return byLayout.get(key);
		}
		String name = anon ? (st.isUnion() ? "_anon_union_" : "_anon_struct_") + (++anonCount)
				: uniqueName(st.name());
		// nested classes go into a category named after the enclosing class, as with DWARF
		CategoryPath cat = ROOT;
		List<String> parts = GnuV2Names.splitQualified(name);
		for (int i = 0; i < parts.size() - 1; i++) {
			cat = new CategoryPath(cat, parts.get(i));
		}
		name = parts.get(parts.size() - 1);
		Composite dt = st.isUnion() ? new UnionDataType(cat, name, dtm)
				: new StructureDataType(cat, name, (int) st.size(), dtm);
		converted.put(st, dt);
		if (key != null) {
			byLayout.put(key, dt);
		}
		filling.add(dt);
		if (st.isUnion()) {
			fillUnion(st, (Union) dt);
		}
		else {
			fillStruct(st, (Structure) dt);
		}
		filling.remove(dt);
		return dt;
	}

	private void fillUnion(StructType st, Union u) {
		for (Field f : st.fields()) {
			DataType ft = convert(f.type());
			try {
				if (isBitfield(f, ft)) {
					u.addBitField(ft, (int) f.bitSize(), fieldName(f), null);
				}
				else if (ft.getLength() > 0) {
					u.add(ft, ft.getLength(), fieldName(f), null);
				}
			}
			catch (IllegalArgumentException | InvalidDataTypeException e) {
				warn("union " + u.getName() + "." + f.name() + ": " + e.getMessage());
			}
		}
	}

	private void fillStruct(StructType st, Structure s) {
		for (BaseClass b : st.baseClasses()) {
			if (b.isVirtual()) {
				continue; // located through the .vb pointer, not at a fixed offset
			}
			DataType bt = convert(b.type());
			if (bt.getLength() <= 0 || bt.isZeroLength()) {
				continue;
			}
			place(s, (int) (b.bitOffset() / 8), bt, "super_" + bt.getName(), null);
		}
		for (Field f : st.fields()) {
			DataType ft = convert(f.type());
			if (isBitfield(f, ft)) {
				int byteOffset = (int) (f.bitOffset() / 8);
				int bitInByte = (int) (f.bitOffset() % 8);
				int width = (int) ((bitInByte + f.bitSize() + 7) / 8);
				int bitOffset = bigEndian ? width * 8 - bitInByte - (int) f.bitSize() : bitInByte;
				try {
					s.insertBitFieldAt(byteOffset, width, bitOffset, ft, (int) f.bitSize(),
						fieldName(f), null);
				}
				catch (InvalidDataTypeException | IllegalArgumentException e) {
					warn("struct " + s.getName() + "." + f.name() + ": " + e.getMessage());
				}
				continue;
			}
			if (containsFilling(ft) || ft.getLength() > 0 && f.bitSize() > 0 &&
				ft.getLength() * 8L != f.bitSize() && f.kind() == FieldKind.NORMAL &&
				!(ft instanceof Array)) {
				// a cross reference to a nested class (they are named without their enclosing
				// class, e.g. "Entry") that was resolved to the wrong definition
				int len = (int) (f.bitSize() / 8);
				if (len > 0) {
					place(s, (int) (f.bitOffset() / 8), new ArrayDataType(
						Undefined1DataType.dataType, len, 1, dtm), fieldName(f),
						"STABS: type " + ft.getName() + " is ambiguous");
				}
				continue;
			}
			place(s, (int) (f.bitOffset() / 8), ft, fieldName(f), null);
		}
	}

	/** @return whether {@code dt} contains, by value, a composite that is still being filled */
	private boolean containsFilling(DataType dt) {
		for (int guard = 0; guard < 100 && dt != null; guard++) {
			if (filling.contains(dt)) {
				return true;
			}
			dt = dt instanceof TypeDef td ? td.getDataType()
					: dt instanceof Array a ? a.getDataType() : null;
		}
		return false;
	}

	/** Places a non-bitfield component, tolerating overlaps and types of unknown size. */
	private void place(Structure s, int offset, DataType dt, String name, String comment) {
		try {
			if (dt.getLength() <= 0 || dt.isZeroLength()) {
				if (offset <= s.getLength()) {
					s.insertAtOffset(offset, dt, 0, name, comment);
				}
				return;
			}
			if (offset + dt.getLength() > s.getLength()) {
				warn("struct " + s.getName() + "." + name + " does not fit");
				return;
			}
			s.replaceAtOffset(offset, dt, dt.getLength(), name, comment);
		}
		catch (IllegalArgumentException e) {
			warn("struct " + s.getName() + "." + name + ": " + e.getMessage());
		}
	}

	private static boolean isBitfield(Field f, DataType ft) {
		if (f.bitSize() <= 0 || ft.getLength() <= 0 || f.bitSize() == ft.getLength() * 8L) {
			return false;
		}
		DataType base = ft instanceof TypeDef td ? td.getBaseDataType() : ft;
		return (base instanceof AbstractIntegerDataType || base instanceof ghidra.program.model.data.Enum ||
			base instanceof BooleanDataType) &&
			(f.bitSize() < ft.getLength() * 8L || f.bitOffset() % 8 != 0);
	}

	private static String fieldName(Field f) {
		if (f.kind() == FieldKind.VPTR) {
			return "_vptr";
		}
		if (f.kind() == FieldKind.VBASE_PTR) {
			return "_vb_" + (f.context() != null ? f.context().name() : "");
		}
		return f.name();
	}

	private void warn(String msg) {
		failures++;
		if (failures <= 50) {
			log.appendMsg("STABS: " + msg);
		}
		Msg.debug(this, msg);
	}
}
