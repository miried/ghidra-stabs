package stabs.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Just enough of the g++ 2.x (gnu-v2) name mangling to give functions readable names and to
 * find the enclosing class of nested classes. Argument types are not demangled: they come from
 * the STABS instead.
 */
public final class GnuV2Names {
	private GnuV2Names() {
	}

	/** Operator codes from libiberty {@code cplus-dem.c}'s {@code optable} (ANSI forms). */
	private static final Map<String, String> OPERATORS = Map.ofEntries(
		Map.entry("nw", " new"), Map.entry("dl", " delete"), Map.entry("vn", " new[]"),
		Map.entry("vd", " delete[]"), Map.entry("as", "="), Map.entry("ne", "!="),
		Map.entry("eq", "=="), Map.entry("ge", ">="), Map.entry("gt", ">"),
		Map.entry("le", "<="), Map.entry("lt", "<"), Map.entry("pl", "+"),
		Map.entry("apl", "+="), Map.entry("mi", "-"), Map.entry("ami", "-="),
		Map.entry("ml", "*"), Map.entry("aml", "*="), Map.entry("dv", "/"),
		Map.entry("adv", "/="), Map.entry("md", "%"), Map.entry("amd", "%="),
		Map.entry("ls", "<<"), Map.entry("als", "<<="), Map.entry("rs", ">>"),
		Map.entry("ars", ">>="), Map.entry("aa", "&&"), Map.entry("oo", "||"),
		Map.entry("nt", "!"), Map.entry("pp", "++"), Map.entry("mm", "--"),
		Map.entry("ad", "&"), Map.entry("aad", "&="), Map.entry("or", "|"),
		Map.entry("aor", "|="), Map.entry("er", "^"), Map.entry("aer", "^="),
		Map.entry("co", "~"), Map.entry("cl", "()"), Map.entry("rf", "->"),
		Map.entry("rm", "->*"), Map.entry("vc", "[]"), Map.entry("cm", ","),
		Map.entry("cn", "?:"), Map.entry("mx", ">?"), Map.entry("mn", "<?"));

	/**
	 * @return the source name for an encoded operator name such as {@code __as}
	 *         ({@code operator=}) or {@code __opPCc} (a conversion operator), else null
	 */
	public static String operatorName(String name) {
		if (name == null || !name.startsWith("__") || name.length() < 4) {
			return null;
		}
		String code = name.substring(2);
		String op = OPERATORS.get(code);
		if (op != null) {
			return "operator" + op;
		}
		if (code.startsWith("op")) {
			return "operator_cast";
		}
		return null;
	}

	/** @return whether a physname is that of a destructor ({@code _._Class} / {@code _$_Class}) */
	public static boolean isDestructor(String physname) {
		return physname.startsWith("_._") || physname.startsWith("_$_");
	}

	/**
	 * @return whether a physname is that of a constructor, i.e. starts directly with the class
	 *         part: {@code __<n>Class}, {@code __Q<n>...} or {@code __t<n>...}
	 */
	public static boolean isConstructor(String physname) {
		if (!physname.startsWith("__") || physname.length() < 3) {
			return false;
		}
		char c = physname.charAt(2);
		return Character.isDigit(c) || c == 'Q' || c == 't';
	}

	/**
	 * Returns the unqualified source name of a free (non-member) function from its mangled
	 * name, e.g. {@code FindBeamList} for {@code FindBeamList__Fi} and {@code operator<<} for
	 * {@code __ls__FR7ostreamPCc}.
	 *
	 * @return the name, or null if it does not look like a mangled free function
	 */
	public static String freeFunctionName(String mangled) {
		if (mangled == null) {
			return null;
		}
		int i = mangled.indexOf("__F", 1);
		if (mangled.startsWith("__")) {
			// operator: __ls__F..., the "__F" is after the operator code
			i = mangled.indexOf("__F", 2);
		}
		if (i <= 0) {
			return null;
		}
		String base = mangled.substring(0, i);
		String op = operatorName(base);
		return op != null ? op : base;
	}

	/**
	 * Splits a qualified source name such as {@code con_set<K,con_map<K,V>::Entry>::Entry} at
	 * the {@code ::} separators outside template arguments.
	 */
	public static List<String> splitQualified(String name) {
		List<String> parts = new ArrayList<>();
		int depth = 0;
		int start = 0;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == '<') {
				depth++;
			}
			else if (c == '>') {
				depth--;
			}
			else if (c == ':' && depth == 0 && name.startsWith("::", i)) {
				parts.add(name.substring(start, i));
				start = i + 2;
				i++;
			}
		}
		parts.add(name.substring(start));
		return parts;
	}

	/**
	 * Returns the mangled class part of a member function's physname, e.g.
	 * {@code Q2t7con_map2ZiZi5Entry} for {@code __as__Q2t7con_map2ZiZi5EntryRCQ2...}.
	 *
	 * @param method the method's name as given in the STABS ({@code __as}, {@code Entry})
	 * @return the class mangling, or null if the physname does not have the expected shape
	 */
	public static String classOfPhysname(String physname, String method) {
		if (physname == null) {
			return null;
		}
		int i;
		if (isDestructor(physname)) {
			i = 3;
		}
		else if (method != null && physname.startsWith(method + "__")) {
			i = method.length() + 2;
		}
		else if (isConstructor(physname)) {
			i = 2;
		}
		else {
			return null;
		}
		while (i < physname.length() && "CVS".indexOf(physname.charAt(i)) >= 0) {
			i++;
		}
		int end = skipClass(physname, i);
		return end < 0 ? null : physname.substring(i, end);
	}

	/**
	 * Splits a qualified class mangling ({@code Q<n>...}) into the manglings of its components.
	 *
	 * @return the components, outermost first; a single element for an unqualified class; or
	 *         null if malformed
	 */
	public static List<String> qualifiedComponents(String mangled) {
		List<String> parts = new ArrayList<>();
		if (!mangled.startsWith("Q")) {
			if (skipClass(mangled, 0) != mangled.length()) {
				return null;
			}
			parts.add(mangled);
			return parts;
		}
		int[] pos = { 1 };
		int n = count(mangled, pos);
		int i = pos[0];
		for (int k = 0; k < n; k++) {
			int end = skipClass(mangled, i);
			if (end < 0 || mangled.startsWith("Q", i)) {
				return null;
			}
			parts.add(mangled.substring(i, end));
			i = end;
		}
		return i == mangled.length() ? parts : null;
	}

	/** @return the mangling of a qualified class name made of the given components */
	public static String qualify(List<String> components) {
		if (components.size() == 1) {
			return components.get(0);
		}
		int n = components.size();
		return (n < 10 ? "Q" + n : "Q_" + n + "_") + String.join("", components);
	}

	/**
	 * @return the identifier of a plain (non-template) name component such as {@code 5Entry},
	 *         or null
	 */
	public static String simpleName(String component) {
		int[] pos = { 0 };
		int len = number(component, pos);
		return len > 0 && pos[0] + len == component.length() ? component.substring(pos[0])
				: null;
	}

	/** @return the index after the class name starting at {@code i}, or -1 */
	public static int skipClass(String s, int i) {
		if (i >= s.length()) {
			return -1;
		}
		char c = s.charAt(i);
		if (Character.isDigit(c)) {
			int[] pos = { i };
			int len = number(s, pos);
			return len > 0 && pos[0] + len <= s.length() ? pos[0] + len : -1;
		}
		if (c == 'Q') {
			int[] pos = { i + 1 };
			int n = count(s, pos);
			int j = pos[0];
			for (int k = 0; k < n && j >= 0; k++) {
				j = skipClass(s, j);
			}
			return n > 0 ? j : -1;
		}
		if (c == 't') {
			int j = skipClass(s, i + 1);
			if (j < 0 || j >= s.length() || !Character.isDigit(s.charAt(j))) {
				return -1;
			}
			int[] pos = { j };
			int n = count(s, pos);
			j = pos[0];
			for (int k = 0; k < n && j >= 0; k++) {
				if (j < s.length() && s.charAt(j) == 'Z') {
					j = skipType(s, j + 1);
				}
				else {
					j = skipTemplateValue(s, j);
				}
			}
			return j;
		}
		return -1;
	}

	/** @return the index after the type starting at {@code i}, or -1 */
	static int skipType(String s, int i) {
		for (int guard = 0; guard < 1000; guard++) {
			if (i >= s.length()) {
				return -1;
			}
			char c = s.charAt(i);
			switch (c) {
				case 'C', 'V', 'U', 'S', 'P', 'R', 'G' -> i++;
				case 'A' -> {
					int[] pos = { i + 1 };
					number(s, pos);
					if (pos[0] >= s.length() || s.charAt(pos[0]) != '_') {
						return -1;
					}
					i = pos[0] + 1;
				}
				case 'F' -> {
					i++;
					while (i >= 0 && i < s.length() && s.charAt(i) != '_') {
						i = skipType(s, i);
					}
					if (i < 0 || i >= s.length()) {
						return -1;
					}
					i++;
				}
				case 'M', 'O' -> {
					i = skipClass(s, i + 1);
					if (i < 0) {
						return -1;
					}
					if (c == 'O' && i < s.length() && s.charAt(i) == '_') {
						i++;
					}
				}
				case 'T' -> {
					int[] pos = { i + 1 };
					count(s, pos);
					return pos[0];
				}
				case 'N' -> {
					int[] pos = { i + 1 };
					count(s, pos);
					count(s, pos);
					return pos[0];
				}
				case 'X' -> {
					int[] pos = { i + 1 };
					count(s, pos);
					count(s, pos);
					return pos[0];
				}
				case 'v', 'b', 'c', 's', 'i', 'l', 'x', 'f', 'd', 'r', 'w', 'e' -> {
					return i + 1;
				}
				default -> {
					return skipClass(s, i);
				}
			}
		}
		return -1;
	}

	/** Skips a non-type template argument: its type followed by its value. */
	private static int skipTemplateValue(String s, int i) {
		int j = skipType(s, i);
		if (j < 0) {
			return -1;
		}
		char kind = s.charAt(j - 1);
		boolean pointer = false;
		for (int k = i; k < j; k++) {
			if (s.charAt(k) == 'P' || s.charAt(k) == 'R') {
				pointer = true;
				break;
			}
		}
		int[] pos = { j };
		if (pointer) {
			int len = number(s, pos);
			return len > 0 && pos[0] + len <= s.length() ? pos[0] + len : -1;
		}
		if (j < s.length() && s.charAt(j) == 'm') {
			pos[0]++;
		}
		int start = pos[0];
		while (pos[0] < s.length() && (Character.isDigit(s.charAt(pos[0])) ||
			(kind == 'f' || kind == 'd' || kind == 'r') && ".e".indexOf(s.charAt(pos[0])) >= 0)) {
			pos[0]++;
		}
		return pos[0] > start ? pos[0] : -1;
	}

	/** Reads a decimal number at {@code pos[0]}, advancing it. */
	private static int number(String s, int[] pos) {
		int n = 0;
		int i = pos[0];
		while (i < s.length() && Character.isDigit(s.charAt(i)) && n < 100_000_000) {
			n = n * 10 + (s.charAt(i++) - '0');
		}
		if (i == pos[0]) {
			return -1;
		}
		pos[0] = i;
		return n;
	}

	/**
	 * Reads a count as libiberty's {@code get_count} does: one digit, or several digits if
	 * they are followed by an underscore; {@code _<n>_} is accepted as well.
	 */
	private static int count(String s, int[] pos) {
		int i = pos[0];
		if (i < s.length() && s.charAt(i) == '_') {
			int[] p = { i + 1 };
			int n = number(s, p);
			if (n >= 0 && p[0] < s.length() && s.charAt(p[0]) == '_') {
				pos[0] = p[0] + 1;
				return n;
			}
			return -1;
		}
		if (i >= s.length() || !Character.isDigit(s.charAt(i))) {
			return -1;
		}
		int[] p = { i };
		int n = number(s, p);
		if (p[0] - i > 1 && p[0] < s.length() && s.charAt(p[0]) == '_') {
			pos[0] = p[0] + 1;
			return n;
		}
		pos[0] = i + 1;
		return s.charAt(i) - '0';
	}
}
