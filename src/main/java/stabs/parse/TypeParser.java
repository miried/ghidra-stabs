package stabs.parse;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import stabs.model.*;
import stabs.model.BaseType.Kind;
import stabs.model.StructType.*;

/**
 * Parses the type part of a stab string, following the grammar implemented by binutils
 * {@code stabs.c} (parse_stab_type and friends) for the dialect emitted by gcc/g++ 2.95.
 * <p>
 * A type with a type number is returned as its {@link TypeRef} slot; if the type is defined at
 * that point the slot's target is set to the definition.
 */
final class TypeParser {
	private static final BigInteger TWO_63 = BigInteger.ONE.shiftLeft(63);
	private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

	private final String s;
	private final TypeContext ctx;
	private int pos;

	TypeParser(String s, int pos, TypeContext ctx) {
		this.s = s;
		this.pos = pos;
		this.ctx = ctx;
	}

	int pos() {
		return pos;
	}

	char peek() {
		return pos < s.length() ? s.charAt(pos) : '\0';
	}

	private char peek(int ahead) {
		int p = pos + ahead;
		return p < s.length() ? s.charAt(p) : '\0';
	}

	boolean atEnd() {
		return pos >= s.length();
	}

	private char next() {
		char c = peek();
		pos++;
		return c;
	}

	void expect(char c) {
		if (peek() != c) {
			throw error("expected '" + c + "'");
		}
		pos++;
	}

	private StabsParseException error(String msg) {
		return new StabsParseException(msg + " at offset " + pos);
	}

	private static boolean startsTypeNumber(char c) {
		return Character.isDigit(c) || c == '(' || c == '-';
	}

	/** Parses a type; {@code typeName} is the tag or typedef name being defined, if any. */
	SType parseType(String typeName) {
		if (atEnd()) {
			throw error("missing type");
		}
		TypeRef slot = null;
		int sizeAttr = -1;

		if (startsTypeNumber(peek())) {
			int[] nums = parseTypeNumber();
			if (peek() != '=') {
				return lookup(nums);
			}
			slot = lookup(nums);
			pos++; // '='
			while (peek() == '@' && !startsTypeNumber(peek(1))) {
				int end = s.indexOf(';', pos);
				if (end < 0) {
					throw error("unterminated type attribute");
				}
				String attr = s.substring(pos + 1, end);
				pos = end + 1;
				if (attr.startsWith("s")) {
					int bits = Integer.parseInt(attr.substring(1));
					sizeAttr = bits > 0 ? bits / 8 : -1;
				}
			}
		}

		SType def = parseDescriptor(typeName, slot);
		if (sizeAttr > 0 && def instanceof BaseType b && b.size() != sizeAttr) {
			def = b.withSize(sizeAttr);
		}
		if (slot != null) {
			if (def != slot) {
				slot.setTarget(def);
			}
			return slot;
		}
		return def;
	}

	private TypeRef lookup(int[] nums) {
		if (nums[1] < 0) {
			throw error("negative type number used as slot");
		}
		return ctx.slot(nums[0], nums[1]);
	}

	private SType parseDescriptor(String typeName, TypeRef slot) {
		char d = next();
		switch (d) {
			case 'x':
				return parseCrossRef();
			case '-':
			case '(':
			case '0': case '1': case '2': case '3': case '4':
			case '5': case '6': case '7': case '8': case '9': {
				pos--;
				int hold = pos;
				int[] nums = parseTypeNumber();
				if (slot != null && nums[1] >= 0 && ctx.slot(nums[0], nums[1]) == slot) {
					BaseType v = new BaseType(Kind.VOID, 0, false);
					return v;
				}
				if (nums[1] < 0) {
					return builtin(-nums[1]);
				}
				pos = hold;
				return parseType(null);
			}
			case '*':
				return new PointerType(parseType(null));
			case '&':
				return new ReferenceType(parseType(null));
			case 'f':
				return new FunctionType(parseType(null));
			case 'k':
				return new ConstType(parseType(null));
			case 'B':
				return new VolatileType(parseType(null));
			case '@': {
				SType domain = parseType(null);
				expect(',');
				return new MemberPointerType(domain, parseType(null));
			}
			case '#':
				return parseMethodType(slot);
			case 'r':
				return parseRange(typeName, slot);
			case 'e':
				return parseEnum();
			case 's':
			case 'u':
				return parseStruct(typeName, slot, d == 'u');
			case 'a':
				expect('r');
				return parseArray();
			case 'S':
				// Pascal set; not produced by gcc for C/C++, keep the element type
				return parseType(null);
			default:
				throw error("unknown type descriptor '" + d + "'");
		}
	}

	int[] parseTypeNumber() {
		if (peek() == '(') {
			pos++;
			int f = (int) parseNumber().longValue();
			expect(',');
			int i = (int) parseNumber().longValue();
			expect(')');
			return new int[] { f, i };
		}
		return new int[] { 0, (int) parseNumber().longValue() };
	}

	/** Parses a number like strtoul with base 0: decimal, 0-prefixed octal or 0x hex. */
	BigInteger parseNumber() {
		boolean neg = false;
		if (peek() == '-') {
			neg = true;
			pos++;
		}
		int radix = 10;
		if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
			radix = 16;
			pos += 2;
		}
		else if (peek() == '0') {
			radix = 8;
		}
		int start = pos;
		while (!atEnd() && Character.digit(peek(), radix) >= 0) {
			pos++;
		}
		if (start == pos) {
			throw error("expected number");
		}
		BigInteger v = new BigInteger(s.substring(start, pos), radix);
		return neg ? v.negate() : v;
	}

	private SType parseCrossRef() {
		CrossRefType.Kind kind = switch (next()) {
			case 's' -> CrossRefType.Kind.STRUCT;
			case 'u' -> CrossRefType.Kind.UNION;
			case 'e' -> CrossRefType.Kind.ENUM;
			case '\0' -> throw error("truncated cross reference");
			default -> CrossRefType.Kind.STRUCT;
		};
		int end = findNameEnd(pos);
		String tag = s.substring(pos, end);
		pos = end + 1;
		CrossRefType ref = new CrossRefType(kind, tag, ctx.unit());
		ctx.crossRef(ref);
		return ref;
	}

	/**
	 * Finds the ':' ending a type name starting at {@code from}, skipping colons nested in
	 * template argument lists (e.g. {@code con_map<a,b>::Entry}).
	 */
	private int findNameEnd(int from) {
		int colon = s.indexOf(':', from);
		if (colon < 0) {
			throw error("missing ':' after name");
		}
		int lt = s.indexOf('<', from);
		if (lt >= 0 && lt < colon && colon + 1 < s.length() && s.charAt(colon + 1) == ':') {
			int nest = 0;
			for (int i = lt; i < s.length(); i++) {
				char c = s.charAt(i);
				if (c == '<') {
					nest++;
				}
				else if (c == '>') {
					nest--;
				}
				else if (c == ':' && nest == 0) {
					return i;
				}
			}
			throw error("unterminated template name");
		}
		return colon;
	}

	private SType parseMethodType(TypeRef self) {
		if (peek() == '#') {
			pos++;
			SType ret = parseType(null);
			expect(';');
			return new MethodType(null, ret, null, false);
		}
		SType domain = parseType(null);
		expect(',');
		SType ret = parseType(null);
		List<SType> args = new ArrayList<>();
		while (peek() != ';') {
			expect(',');
			args.add(parseType(null));
		}
		pos++;
		boolean varargs = args.isEmpty() || !isVoid(args.get(args.size() - 1));
		if (!varargs) {
			args.remove(args.size() - 1);
		}
		return new MethodType(domain, ret, args, varargs);
	}

	private static boolean isVoid(SType t) {
		return t.resolve() instanceof BaseType b && b.kind() == Kind.VOID;
	}

	private SType parseRange(String typeName, TypeRef slot) {
		int start = pos;
		int[] nums = parseTypeNumber();
		boolean self = slot != null && nums[1] >= 0 && ctx.slot(nums[0], nums[1]) == slot;
		SType indexType = null;
		if (peek() == '=') {
			pos = start;
			indexType = parseType(null);
		}
		if (peek() == ';') {
			pos++;
		}
		BigInteger n2 = parseNumber();
		expect(';');
		BigInteger n3 = parseNumber();
		expect(';');

		if (indexType == null) {
			BaseType b = rangeBaseType(typeName, self, n2, n3);
			if (b != null) {
				return b;
			}
		}
		if (self) {
			throw error("unrecognized self subrange");
		}
		return new RangeType(indexType != null ? indexType : ctx.slot(nums[0], nums[1]),
			n2.longValue(), n3.longValue());
	}

	private static BaseType rangeBaseType(String typeName, boolean self, BigInteger n2,
			BigInteger n3) {
		long lo = n2.longValue();
		boolean n3fits = n3.bitLength() < 64;
		long hi = n3.longValue();
		if (self && lo == 0 && n3.signum() == 0) {
			return new BaseType(Kind.VOID, 0, false);
		}
		if (n3.signum() == 0 && lo > 0) {
			return self ? new BaseType(Kind.COMPLEX, (int) lo * 2, false)
					: new BaseType(Kind.FLOAT, (int) lo, false);
		}
		if (lo == 0 && n3fits && hi == -1) {
			if ("long long int".equals(typeName)) {
				return new BaseType(Kind.INT, 8, false);
			}
			if ("long long unsigned int".equals(typeName)) {
				return new BaseType(Kind.INT, 8, true);
			}
			return new BaseType(Kind.INT, 4, true);
		}
		if (self && lo == 0 && hi == 127) {
			return new BaseType(Kind.CHAR, 1, false);
		}
		if (lo == 0) {
			if (n3fits && hi < 0) {
				return new BaseType(Kind.INT, (int) -hi, true);
			}
			if (n3.equals(BigInteger.valueOf(0xff))) {
				return new BaseType(Kind.INT, 1, true);
			}
			if (n3.equals(BigInteger.valueOf(0xffff))) {
				return new BaseType(Kind.INT, 2, true);
			}
			if (n3.equals(BigInteger.valueOf(0xffffffffL))) {
				return new BaseType(Kind.INT, 4, true);
			}
			if (n3.equals(TWO_64.subtract(BigInteger.ONE))) {
				return new BaseType(Kind.INT, 8, true);
			}
		}
		else if (n3.signum() == 0 && lo < 0 && (self || lo == -8)) {
			return new BaseType(Kind.INT, (int) -lo, false);
		}
		else if (n2.equals(n3.negate().subtract(BigInteger.ONE)) ||
			n2.equals(n3.add(BigInteger.ONE))) {
			// gcc writes signed bounds in octal as unsigned two's complement, e.g. int is
			// 0020000000000;0017777777777; hence the second form

			if (hi == 0x7f) {
				return new BaseType(Kind.INT, 1, false);
			}
			if (hi == 0x7fff) {
				return new BaseType(Kind.INT, 2, false);
			}
			if (hi == 0x7fffffffL) {
				return new BaseType(Kind.INT, 4, false);
			}
			if (n3.equals(TWO_63.subtract(BigInteger.ONE))) {
				return new BaseType(Kind.INT, 8, false);
			}
		}
		return null;
	}

	/** Negative type numbers: the AIX/XCOFF builtin types, as in binutils. */
	private static BaseType builtin(int n) {
		BaseType b = switch (n) {
			case 1, 15, 29 -> new BaseType(Kind.INT, 4, false);
			case 2 -> new BaseType(Kind.CHAR, 1, false);
			case 3, 28 -> new BaseType(Kind.INT, 2, false);
			case 4 -> new BaseType(Kind.INT, 4, false);
			case 5 -> new BaseType(Kind.INT, 1, true);
			case 6, 27 -> new BaseType(Kind.INT, 1, false);
			case 7 -> new BaseType(Kind.INT, 2, true);
			case 8, 9, 10 -> new BaseType(Kind.INT, 4, true);
			case 11 -> new BaseType(Kind.VOID, 0, false);
			case 12, 17 -> new BaseType(Kind.FLOAT, 4, false);
			case 13, 18 -> new BaseType(Kind.FLOAT, 8, false);
			case 14 -> new BaseType(Kind.FLOAT, 8, false);
			case 16, 24 -> new BaseType(Kind.BOOL, 4, true);
			case 20 -> new BaseType(Kind.CHAR, 1, true);
			case 21 -> new BaseType(Kind.BOOL, 1, true);
			case 22 -> new BaseType(Kind.BOOL, 2, true);
			case 23 -> new BaseType(Kind.BOOL, 4, true);
			case 25 -> new BaseType(Kind.COMPLEX, 8, false);
			case 26 -> new BaseType(Kind.COMPLEX, 16, false);
			case 30 -> new BaseType(Kind.CHAR, 2, true);
			case 31, 34 -> new BaseType(Kind.INT, 8, false);
			case 32 -> new BaseType(Kind.INT, 8, true);
			case 33 -> new BaseType(Kind.BOOL, 8, true);
			default -> throw new StabsParseException("unknown builtin type -" + n);
		};
		return b;
	}

	private SType parseArray() {
		SType indexType;
		int hold = pos;
		int[] nums = parseTypeNumber();
		if (nums[0] == 0 && nums[1] == 0 && peek() != '=') {
			indexType = new BaseType(Kind.INT, 4, false);
		}
		else {
			pos = hold;
			indexType = parseType(null);
		}
		expect(';');
		if (!Character.isDigit(peek()) && peek() != '-') {
			pos++; // adjustable (Fortran)
		}
		long lower = parseNumber().longValue();
		expect(';');
		if (!Character.isDigit(peek()) && peek() != '-') {
			pos++;
		}
		long upper = parseNumber().longValue();
		expect(';');
		SType element = parseType(null);
		return new ArrayType(indexType, lower, upper, element);
	}

	private SType parseEnum() {
		if (peek() == '-') {
			int colon = s.indexOf(':', pos);
			if (colon < 0) {
				throw error("bad enum");
			}
			pos = colon + 1;
		}
		List<EnumType.Enumerator> values = new ArrayList<>();
		while (!atEnd() && peek() != ';' && peek() != ',') {
			int colon = s.indexOf(':', pos);
			if (colon < 0) {
				throw error("bad enumerator");
			}
			String name = s.substring(pos, colon);
			pos = colon + 1;
			long value = parseNumber().longValue();
			expect(',');
			values.add(new EnumType.Enumerator(name, value));
		}
		if (peek() == ';') {
			pos++;
		}
		return new EnumType(values, ctx.unit());
	}

	private SType parseStruct(String tagName, TypeRef self, boolean union) {
		long size = parseNumber().longValue();
		StructType st = new StructType(union, size, ctx.unit());
		if (self != null) {
			// make the definition visible to self references in methods and the vptr field
			self.setTarget(st);
		}
		parseBaseClasses(st);
		parseFields(st);
		parseMembers(st, tagName, self);
		parseTilde(st, self);
		return st;
	}

	private void parseBaseClasses(StructType st) {
		if (peek() != '!') {
			return;
		}
		pos++;
		int count = parseNumber().intValue();
		expect(',');
		for (int i = 0; i < count; i++) {
			boolean virt = next() == '1';
			Visibility vis = Visibility.of(next());
			long bitpos = parseNumber().longValue();
			expect(',');
			SType type = parseType(null);
			expect(';');
			st.baseClasses().add(new BaseClass(type, bitpos, virt, vis));
		}
	}

	private void parseFields(StructType st) {
		while (peek() != ';') {
			if (atEnd()) {
				throw error("unterminated struct");
			}
			char c = peek();
			if ((c == '$' || c == '.') && peek(1) != '_') {
				pos++;
				parseCppAbbrev(st);
				continue;
			}
			int colon = s.indexOf(':', pos);
			if (colon < 0) {
				throw error("missing ':' in field");
			}
			if (colon + 1 < s.length() && s.charAt(colon + 1) == ':') {
				return; // member functions start here
			}
			String name = s.substring(pos, colon);
			pos = colon + 1;
			Visibility vis = Visibility.PUBLIC;
			if (peek() == '/') {
				pos++;
				vis = Visibility.of(next());
			}
			SType type = parseType(null);
			if (peek() == ':') {
				pos++;
				int semi = s.indexOf(';', pos);
				if (semi < 0) {
					throw error("unterminated static member");
				}
				st.staticFields().add(new StaticField(name, type, s.substring(pos, semi), vis));
				pos = semi + 1;
				continue;
			}
			expect(',');
			long bitpos = parseNumber().longValue();
			expect(',');
			long bitsize = parseNumber().longValue();
			expect(';');
			if (bitpos == 0 && bitsize == 0) {
				vis = Visibility.IGNORE;
			}
			st.fields().add(new Field(name, type, bitpos, bitsize, vis, FieldKind.NORMAL, null));
		}
	}

	private void parseCppAbbrev(StructType st) {
		expect('v');
		char kind = next();
		SType context = parseType(null);
		expect(':');
		SType type = parseType(null);
		expect(',');
		long bitpos = parseNumber().longValue();
		expect(';');
		FieldKind fk = kind == 'f' ? FieldKind.VPTR : FieldKind.VBASE_PTR;
		String name = kind == 'f' ? "_vptr" : "_vb";
		st.fields().add(new Field(name, type, bitpos, 32, Visibility.PRIVATE, fk, context));
	}

	private void parseMembers(StructType st, String tagName, TypeRef self) {
		while (peek() != ';') {
			if (atEnd()) {
				return;
			}
			int colon = s.indexOf(':', pos);
			if (colon < 0 || colon + 1 >= s.length() || s.charAt(colon + 1) != ':') {
				return;
			}
			String name;
			if (s.startsWith("op$", pos)) {
				pos = colon + 2;
				int dot = s.indexOf('.', pos);
				if (dot < 0) {
					throw error("bad operator name");
				}
				name = s.substring(pos, dot);
				pos = dot + 1;
			}
			else {
				name = s.substring(pos, colon);
				pos = colon + 2;
			}

			do {
				SType type = parseType(null);
				expect(':');
				int semi = s.indexOf(';', pos);
				if (semi < 0) {
					throw error("unterminated method argtypes");
				}
				String argtypes = s.substring(pos, semi);
				pos = semi + 1;

				char vc = next();
				if (vc == '\0') {
					throw error("truncated method");
				}
				Visibility vis = vc == '0' ? Visibility.PRIVATE
						: vc == '1' ? Visibility.PROTECTED : Visibility.PUBLIC;
				boolean isConst = false, isVolatile = false;
				switch (peek()) {
					case 'A' -> pos++;
					case 'B' -> {
						isConst = true;
						pos++;
					}
					case 'C' -> {
						isVolatile = true;
						pos++;
					}
					case 'D' -> {
						isConst = true;
						isVolatile = true;
						pos++;
					}
					default -> {
					}
				}
				MethodKind kind = MethodKind.NORMAL;
				long vindex = 0;
				SType vcontext = null;
				switch (peek()) {
					case '*' -> {
						pos++;
						kind = MethodKind.VIRTUAL;
						vindex = parseNumber().longValue() & 0x7fffffffL;
						expect(';');
						if (peek() != ';' && !atEnd()) {
							vcontext = parseType(null);
							expect(';');
						}
					}
					case '?' -> {
						pos++;
						kind = MethodKind.STATIC;
					}
					case '.' -> pos++;
					default -> {
					}
				}

				SType resolvedType = type.resolve();
				MethodType mt = switch (resolvedType) {
					case MethodType m when m.isStub() -> new MethodType(self, m.returnType(), null,
						false);
					case MethodType m -> m;
					// static methods have a plain function type
					case FunctionType f -> new MethodType(self, f.returnType(), null, false);
					default -> null;
				};
				// The argtypes field holds either the full mangled name or, for abbreviated
				// methods, only the mangled arguments. A method may reuse another overload's
				// full type, so decide by the string rather than by the type (unlike binutils).
				String physname = isFullPhysname(argtypes, name) ? argtypes
						: stubPhysname(name, tagName, argtypes, isConst, isVolatile);
				st.methods().add(new Method(name, physname, mt, vis, isConst, isVolatile, kind,
					vindex, vcontext));
			}
			while (peek() != ';' && !atEnd());
			if (!atEnd()) {
				pos++;
			}
		}
	}

	private static boolean isFullPhysname(String argtypes, String name) {
		return argtypes.startsWith(name + "__") || argtypes.startsWith("__") ||
			argtypes.startsWith("_._") || argtypes.startsWith("_$_") || argtypes.startsWith("_Z");
	}

	/**
	 * Rebuilds the mangled name of an abbreviated method, as gdb's gdb_mangle_name and binutils
	 * parse_stab_argtypes do: {@code name__<len><class><args>}.
	 */
	static String stubPhysname(String name, String tagName, String argtypes, boolean isConst,
			boolean isVolatile) {
		boolean fullCtor = (argtypes.startsWith("__") && argtypes.length() > 2 &&
			(Character.isDigit(argtypes.charAt(2)) || argtypes.charAt(2) == 'Q' ||
				argtypes.charAt(2) == 't')) ||
			argtypes.startsWith("__ct");
		boolean ctor = fullCtor || (tagName != null && name.equals(tagName));
		boolean dtor = argtypes.startsWith("_$_") || argtypes.startsWith("_._") ||
			argtypes.startsWith("__dt");
		if (dtor || fullCtor || argtypes.startsWith("_Z")) {
			return argtypes;
		}
		String cv = (isConst ? "C" : "") + (isVolatile ? "V" : "");
		StringBuilder sb = new StringBuilder();
		if (!ctor) {
			sb.append(name);
		}
		sb.append("__").append(cv);
		if (tagName != null && !tagName.isEmpty() && tagName.indexOf('<') < 0) {
			sb.append(tagName.length()).append(tagName);
		}
		sb.append(argtypes);
		return sb.toString();
	}

	private void parseTilde(StructType st, TypeRef self) {
		if (peek() == ';') {
			pos++;
		}
		if (peek() != '~') {
			return;
		}
		pos++;
		if (peek() == '=' || peek() == '+' || peek() == '-') {
			pos++;
		}
		if (peek() != '%') {
			return;
		}
		pos++;
		int hold = pos;
		int[] nums = parseTypeNumber();
		if (self != null && ctx.slot(nums[0], nums[1]) == self) {
			st.setVptr(null, true);
		}
		else {
			pos = hold;
			st.setVptr(parseType(null), false);
		}
		int semi = s.indexOf(';', pos);
		if (semi < 0) {
			throw error("unterminated vptr info");
		}
		pos = semi + 1;
	}
}
