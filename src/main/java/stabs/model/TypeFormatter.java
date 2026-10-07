package stabs.model;

import java.util.stream.Collectors;

import stabs.model.StructType.*;

/** Renders model types as C++-like text, for logs and dumps. */
public final class TypeFormatter {
	private static final int MAX_DEPTH = 12;

	private TypeFormatter() {
	}

	/** @return a short reference to the type, e.g. {@code const char *} */
	public static String name(SType t) {
		return name(t, 0);
	}

	private static String name(SType t, int depth) {
		if (t == null) {
			return "<null>";
		}
		if (depth > MAX_DEPTH) {
			return "...";
		}
		SType r = t.resolve();
		if (r instanceof TypeRef ref) {
			return "<undefined " + ref + ">";
		}
		if (r instanceof CrossRefType x) {
			return x.kind().name().toLowerCase() + " " + x.tag() + " /*opaque*/";
		}
		if (r.name() != null) {
			return r.name();
		}
		int d = depth + 1;
		return switch (r) {
			case PointerType p -> name(p.target(), d) + " *";
			case ReferenceType p -> name(p.target(), d) + " &";
			case ConstType c -> "const " + name(c.target(), d);
			case VolatileType v -> "volatile " + name(v.target(), d);
			case ArrayType a -> name(a.element(), d) + "[" + a.count() + "]";
			case FunctionType f -> name(f.returnType(), d) + " ()";
			case MethodType m -> name(m.returnType(), d) + " (" +
				(m.domain() != null ? name(m.domain(), d) + "::" : "") + ")(" +
				(m.args() == null ? "?"
						: m.args().stream().map(a -> name(a, d)).collect(Collectors.joining(", ")) +
							(m.varargs() ? ", ..." : "")) +
				")";
			case MemberPointerType mp -> name(mp.memberType(), d) + " " + name(mp.domain(), d) +
				"::*";
			case StructType st -> (st.isUnion() ? "union" : "struct") + " <anon " + st.size() +
				">";
			case EnumType en -> "enum <anon>";
			case BaseType b -> b.toString();
			case RangeType rt -> "range(" + name(rt.base(), d) + ";" + rt.lower() + ";" +
				rt.upper() + ")";
			default -> r.toString();
		};
	}

	/** @return a multi-line definition of a struct, union or enum, or the typedef target */
	public static String definition(SType t) {
		StringBuilder sb = new StringBuilder();
		SType r = t instanceof TypedefType ? t : t.resolve();
		switch (r) {
			case TypedefType td -> sb.append("typedef ").append(name(td.target())).append(' ')
					.append(td.name()).append(';');
			case EnumType en -> {
				sb.append("enum ").append(en.name() != null ? en.name() : "").append(" {");
				for (EnumType.Enumerator e : en.values()) {
					sb.append("\n    ").append(e.name()).append(" = ").append(e.value()).append(',');
				}
				sb.append("\n};");
			}
			case StructType st -> structDefinition(st, sb);
			default -> sb.append(name(r));
		}
		return sb.toString();
	}

	private static void structDefinition(StructType st, StringBuilder sb) {
		sb.append(st.isUnion() ? "union " : st.isCppClass() ? "class " : "struct ")
				.append(st.name() != null ? st.name() : "<anon>");
		if (!st.baseClasses().isEmpty()) {
			sb.append(" : ").append(st.baseClasses().stream()
					.map(b -> b.visibility().name().toLowerCase() + (b.isVirtual() ? " virtual " : " ") +
						name(b.type()) + " @" + b.bitOffset() / 8)
					.collect(Collectors.joining(", ")));
		}
		sb.append(" { // size ").append(st.size());
		if (st.ownVptr()) {
			sb.append(", own vptr");
		}
		else if (st.vptrBase() != null) {
			sb.append(", vptr from ").append(name(st.vptrBase()));
		}
		for (Field f : st.fields()) {
			sb.append("\n    /* ").append(f.bitOffset() / 8);
			if (f.bitOffset() % 8 != 0) {
				sb.append('.').append(f.bitOffset() % 8);
			}
			sb.append(" */ ").append(name(f.type())).append(' ').append(f.name());
			if (f.kind() == FieldKind.VPTR) {
				sb.append(" /* vptr of ").append(name(f.context())).append(" */");
			}
			sb.append("; // bits ").append(f.bitSize());
		}
		for (StaticField f : st.staticFields()) {
			sb.append("\n    static ").append(name(f.type())).append(' ').append(f.name())
					.append("; // ").append(f.physname());
		}
		for (Method m : st.methods()) {
			sb.append("\n    ");
			if (m.kind() == MethodKind.VIRTUAL) {
				sb.append("virtual ");
			}
			else if (m.kind() == MethodKind.STATIC) {
				sb.append("static ");
			}
			sb.append(m.type() != null ? name(m.type().returnType()) : "?").append(' ')
					.append(m.name()).append("()");
			if (m.isConst()) {
				sb.append(" const");
			}
			sb.append("; // ").append(m.physname());
			if (m.kind() == MethodKind.VIRTUAL) {
				sb.append(" [vtable ").append(m.vtableIndex()).append(']');
			}
		}
		sb.append("\n};");
	}
}
