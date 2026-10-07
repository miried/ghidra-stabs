package stabs.model;

import java.util.Map;

/**
 * Just enough of the g++ 2.x (gnu-v2) name mangling to give functions readable names. Argument
 * types are not demangled: they come from the STABS instead.
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
}
