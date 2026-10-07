package stabs.tools;

import java.util.*;

import stabs.model.*;

/**
 * Consistency statistics over a parsed program, as a sanity check of the parser: types that
 * are referenced but never defined, cross references without a definition, and member function
 * definitions whose mangled name is not among the physnames declared by any class.
 */
public final class StabsCheck {
	public final Set<TypeRef> undefinedRefs = new LinkedHashSet<>();
	public final Set<String> unresolvedCrossRefs = new TreeSet<>();
	public int memberFunctions;
	public final List<String> unmatchedMemberFunctions = new ArrayList<>();

	private final Set<SType> visited = Collections.newSetFromMap(new IdentityHashMap<>());

	public static StabsCheck run(StabsProgram prog) {
		StabsCheck c = new StabsCheck();
		Set<String> physnames = new HashSet<>();
		for (SType t : prog.namedTypes()) {
			c.visit(t);
			if (t instanceof StructType st) {
				st.methods().forEach(m -> physnames.add(m.physname()));
			}
		}
		for (Function f : prog.functions()) {
			c.visit(f.returnType());
			f.params().forEach(p -> c.visit(p.type()));
			f.locals().forEach(l -> c.visit(l.variable().type()));
			if (isMemberFunctionName(f.name())) {
				c.memberFunctions++;
				if (!physnames.contains(f.name())) {
					c.unmatchedMemberFunctions.add(f.name());
				}
			}
		}
		prog.globals().forEach(g -> c.visit(g.variable().type()));
		return c;
	}

	/**
	 * @return whether a gnu-v2 mangled name looks like a member function: {@code name__<n>Class},
	 *         {@code name__C<n>Class}, {@code __<n>Class} (ctor), {@code _._<n>Class} (dtor), or
	 *         a qualified class {@code __Q<n>...}
	 */
	static boolean isMemberFunctionName(String name) {
		if (name == null || name.startsWith("_GLOBAL_") || name.startsWith("__tf") ||
			name.startsWith("__ti")) {
			return false; // static initializers and type_info functions are not declared
		}
		if (name.startsWith("_._") || name.startsWith("_$_")) {
			return true;
		}
		int i = name.indexOf("__", 1);
		if (name.startsWith("__")) {
			i = 0;
		}
		while (i >= 0) {
			int p = i + 2;
			if (p < name.length() && name.charAt(p) == 'C') {
				p++;
			}
			char c = p < name.length() ? name.charAt(p) : '\0';
			char c2 = p + 1 < name.length() ? name.charAt(p + 1) : '\0';
			if (Character.isDigit(c) || ((c == 'Q' || c == 't') && Character.isDigit(c2))) {
				return true;
			}
			if (p < name.length() && name.charAt(p) == 'F') {
				return false; // free function
			}
			i = name.indexOf("__", i + 1);
		}
		return false;
	}

	private void visit(SType t) {
		if (t == null || !visited.add(t)) {
			return;
		}
		switch (t) {
			case TypeRef r -> {
				if (r.target() == null) {
					undefinedRefs.add(r);
				}
				else {
					visit(r.target());
				}
			}
			case CrossRefType x -> {
				if (x.resolved() == null) {
					unresolvedCrossRefs.add(x.kind() + " " + x.tag());
				}
			}
			case PointerType p -> visit(p.target());
			case ReferenceType p -> visit(p.target());
			case ConstType p -> visit(p.target());
			case VolatileType p -> visit(p.target());
			case TypedefType p -> visit(p.target());
			case ArrayType a -> {
				visit(a.indexType());
				visit(a.element());
			}
			case FunctionType f -> visit(f.returnType());
			case MethodType m -> {
				visit(m.returnType());
				if (m.args() != null) {
					m.args().forEach(this::visit);
				}
			}
			case MemberPointerType mp -> {
				visit(mp.domain());
				visit(mp.memberType());
			}
			case RangeType rt -> visit(rt.base());
			case StructType st -> {
				st.baseClasses().forEach(b -> visit(b.type()));
				st.fields().forEach(f -> visit(f.type()));
				st.staticFields().forEach(f -> visit(f.type()));
				st.methods().forEach(m -> visit(m.type()));
				visit(st.vptrBase());
			}
			default -> {
			}
		}
	}

	public String summary() {
		return String.format(
			"undefined type refs: %d, unresolved cross refs: %d, member functions: %d " +
				"(%d without matching class physname)",
			undefinedRefs.size(), unresolvedCrossRefs.size(), memberFunctions,
			unmatchedMemberFunctions.size());
	}
}
