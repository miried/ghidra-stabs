package stabs.parse;

import static org.junit.jupiter.api.Assertions.*;
import static stabs.format.StabType.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stabs.format.StabEntry;
import stabs.model.*;
import stabs.model.BaseType.Kind;
import stabs.model.StructType.*;

class StabsParserTest {

	/** Builds a stab stream: one unit followed by the given (type, string[, value]) entries. */
	private static StabsProgram parse(Object... typeAndString) {
		List<StabEntry> entries = new ArrayList<>();
		entries.add(new StabEntry(0, N_SO, 0, 0, 0x1000, "/src/"));
		entries.add(new StabEntry(1, N_SO, 0, 0, 0x1000, "test.cpp"));
		for (int i = 0; i < typeAndString.length;) {
			int type = (Integer) typeAndString[i++];
			String s = (String) typeAndString[i++];
			long value = 0;
			if (i < typeAndString.length && typeAndString[i] instanceof Long v) {
				value = v;
				i++;
			}
			entries.add(new StabEntry(entries.size(), type, 0, 0, value, s));
		}
		StabsProgram p = StabsParser.parse(entries);
		assertEquals(List.of(), p.issues());
		return p;
	}

	private static SType named(StabsProgram p, String name) {
		return p.namedTypes().stream().filter(t -> name.equals(t.name())).findFirst()
				.orElseThrow(() -> new AssertionError("no type " + name));
	}

	private static void assertBase(SType t, Kind kind, int size, boolean unsigned) {
		BaseType b = assertInstanceOf(BaseType.class, t.resolve());
		assertEquals(kind, b.kind(), "kind of " + b);
		assertEquals(size, b.size(), "size of " + b);
		assertEquals(unsigned, b.isUnsigned(), "signedness of " + b);
	}

	@Test
	void builtinRanges() {
		StabsProgram p = parse(
			N_LSYM, "int:t(0,1)=r(0,1);0020000000000;0017777777777;",
			N_LSYM, "char:t(0,2)=r(0,2);0;127;",
			N_LSYM, "unsigned int:t(0,4)=r(0,1);0000000000000;0037777777777;",
			N_LSYM, "long long int:t(0,6)=r(0,1);01000000000000000000000;0777777777777777777777;",
			N_LSYM, "long long unsigned int:t(0,7)=r(0,1);0000000000000;01777777777777777777777;",
			N_LSYM, "short int:t(0,8)=r(0,8);-32768;32767;",
			N_LSYM, "unsigned char:t(0,11)=r(0,11);0;255;",
			N_LSYM, "float:t(0,12)=r(0,1);4;0;",
			N_LSYM, "long double:t(0,14)=r(0,1);12;0;",
			N_LSYM, "complex float:t(0,16)=r(0,16);4;0;",
			N_LSYM, "bool:t(0,19)=@s8;-16;",
			N_LSYM, "void:t(0,20)=(0,20)");
		assertBase(named(p, "int"), Kind.INT, 4, false);
		assertBase(named(p, "char"), Kind.CHAR, 1, false);
		assertBase(named(p, "unsigned int"), Kind.INT, 4, true);
		assertBase(named(p, "long long int"), Kind.INT, 8, false);
		assertBase(named(p, "long long unsigned int"), Kind.INT, 8, true);
		assertBase(named(p, "short int"), Kind.INT, 2, false);
		assertBase(named(p, "unsigned char"), Kind.INT, 1, true);
		assertBase(named(p, "float"), Kind.FLOAT, 4, false);
		assertBase(named(p, "long double"), Kind.FLOAT, 12, false);
		assertBase(named(p, "complex float"), Kind.COMPLEX, 8, false);
		assertBase(named(p, "bool"), Kind.BOOL, 1, true);
		assertBase(named(p, "void"), Kind.VOID, 0, false);
	}

	@Test
	void typedefsAndPointers() {
		StabsProgram p = parse(
			N_LSYM, "int:t(0,1)=r(0,1);0020000000000;0017777777777;",
			N_LSYM, "size_t:t(0,2)=(0,1)",
			N_LSYM, "intptr:t(0,3)=*(0,1)",
			N_LSYM, "cfunc:t(0,4)=*(0,5)=f(0,1)");
		TypedefType size = assertInstanceOf(TypedefType.class, named(p, "size_t"));
		assertEquals("int", size.target().name());
		TypedefType ptr = assertInstanceOf(TypedefType.class, named(p, "intptr"));
		assertEquals("int *", TypeFormatter.name(ptr.target()));
		assertEquals("int ()", TypeFormatter.name(
			((PointerType) ((TypedefType) named(p, "cfunc")).target().resolve()).target()));
	}

	@Test
	void cStructWithBitfieldsArrayAndSelfReference() {
		StabsProgram p = parse(
			N_LSYM, "int:t(0,1)=r(0,1);0020000000000;0017777777777;",
			N_LSYM, "node:T(0,2)=s16next:(0,3)=*(0,2),0,32;flags:(0,1),32,3;" +
				"arr:(0,4)=ar(0,1);0;1;(0,1),64,64;;");
		StructType st = assertInstanceOf(StructType.class, named(p, "node"));
		assertEquals(16, st.size());
		assertFalse(st.isCppClass());
		assertEquals(List.of("next", "flags", "arr"),
			st.fields().stream().map(Field::name).toList());
		PointerType next = assertInstanceOf(PointerType.class, st.fields().get(0).type().resolve());
		assertSame(st, next.target().resolve());
		assertEquals(3, st.fields().get(1).bitSize());
		ArrayType arr = assertInstanceOf(ArrayType.class, st.fields().get(2).type().resolve());
		assertEquals(2, arr.count());
	}

	@Test
	void anonymousTypedefStructTakesTypedefName() {
		StabsProgram p = parse(
			N_LSYM, "int:t(0,1)=r(0,1);0020000000000;0017777777777;",
			N_LSYM, "vec_t:t(0,2)=s8x:(0,1),0,32;y:(0,1),32,32;;");
		StructType st = assertInstanceOf(StructType.class, named(p, "vec_t"));
		assertEquals(2, st.fields().size());
	}

	@Test
	void typedefThroughAliasNamesAnonymousTypes() {
		StabsProgram p = parse(
			N_LSYM, "int:t(0,1)=r(0,1);0020000000000;0017777777777;",
			N_LSYM, " :T(0,2)=eRT_MODEL:0,RT_SPRITE:1,;",
			N_LSYM, "refEntityType_t:t(0,3)=(0,2)",
			N_LSYM, "polyVert_t:t(0,4)=(0,5)=s4x:(0,1),0,32;;");
		assertInstanceOf(EnumType.class, named(p, "refEntityType_t"));
		assertInstanceOf(StructType.class, named(p, "polyVert_t"));
		assertTrue(p.namedTypes().stream().noneMatch(t -> t instanceof TypedefType));
	}

	@Test
	void crossReferencePrefersDefinitionFromSameLanguage() {
		List<StabEntry> entries = new ArrayList<>();
		java.util.function.BiConsumer<Integer, String> add =
			(type, str) -> entries.add(new StabEntry(entries.size(), type, 0, 0, 0, str));
		add.accept(N_SO, "math.c");
		add.accept(N_LSYM, "int:t(0,1)=r(0,1);0020000000000;0017777777777;");
		add.accept(N_LSYM, "exception:T(0,2)=s32type:(0,1),0,32;;");
		add.accept(N_SO, "");
		add.accept(N_SO, "a.cpp");
		add.accept(N_LSYM, "int:t(0,1)=r(0,1);0020000000000;0017777777777;");
		add.accept(N_LSYM, "bad_cast:Tt(0,2)=s4!1,020,(0,3)=xsexception:;;");
		add.accept(N_SO, "");
		add.accept(N_SO, "b.cpp");
		add.accept(N_LSYM, "exception:Tt(0,1)=s4;");
		StabsProgram p = StabsParser.parse(entries);
		assertEquals(List.of(), p.issues());
		StructType badCast = (StructType) named(p, "bad_cast");
		StructType base = (StructType) badCast.baseClasses().get(0).type().resolve();
		assertEquals(4, base.size());
	}

	@Test
	void nestedClassesAreQualifiedAndLookedUpFromTheirScope() {
		// g++ 2.95 names both mapA<int>::Entry and setB<int>::Entry just "Entry"
		StabsProgram p = parse(
			N_LSYM, "int:t(0,1)=r(0,1);-2147483648;2147483647;",
			N_LSYM, "mapA<int>:Tt(0,2)=s4table:/0(0,3)=*(0,4)=xsEntry:,0,32;" +
				"__as::(0,5)=##(0,6)=&(0,2);:t4mapA1ZiRCt4mapA1Zi;2A.;;",
			N_LSYM, "setB<int>:Tt(0,7)=s4table:/0(0,8)=*(0,9)=xsEntry:,0,32;" +
				"__as::(0,10)=##(0,11)=&(0,7);:t4setB1ZiRCt4setB1Zi;2A.;;",
			N_LSYM, "Entry:Tt(0,12)=s4key:(0,1),0,32;" +
				"__as::(0,13)=##(0,14)=&(0,12);:Q2t4mapA1Zi5EntryRCQ2t4mapA1Zi5Entry;2A.;;",
			N_LSYM, "Entry:Tt(0,15)=s8key:(0,1),0,32;next:(0,1),32,32;" +
				"__as::(0,16)=##(0,17)=&(0,15);:Q2t4setB1Zi5EntryRCQ2t4setB1Zi5Entry;2A.;;");
		StructType a = assertInstanceOf(StructType.class, named(p, "mapA<int>::Entry"));
		StructType b = assertInstanceOf(StructType.class, named(p, "setB<int>::Entry"));
		assertEquals("__as__Q2t4mapA1Zi5EntryRCQ2t4mapA1Zi5Entry", a.methods().get(0).physname());

		StructType mapA = assertInstanceOf(StructType.class, named(p, "mapA<int>"));
		StructType setB = assertInstanceOf(StructType.class, named(p, "setB<int>"));
		assertSame(a, pointee(mapA.fields().get(0).type()));
		assertSame(b, pointee(setB.fields().get(0).type()));
	}

	private static SType pointee(SType t) {
		return assertInstanceOf(PointerType.class, t.resolve()).target().resolve();
	}

	@Test
	void enumType() {
		StabsProgram p = parse(N_LSYM, "color_t:T(0,1)=eRED:0,GREEN:1,BLUE:-2,;");
		EnumType e = assertInstanceOf(EnumType.class, named(p, "color_t"));
		assertEquals(List.of(new EnumType.Enumerator("RED", 0), new EnumType.Enumerator("GREEN", 1),
			new EnumType.Enumerator("BLUE", -2)), e.values());
	}

	@Test
	void crossReferenceWithTemplateNameResolves() {
		StabsProgram p = parse(
			N_LSYM, "int:t(0,1)=r(0,1);0020000000000;0017777777777;",
			N_LSYM, "holder:T(0,2)=s4p:(0,3)=*(0,4)=xsBox<a::b,int>:,0,32;;",
			N_LSYM, "Box<a::b,int>:Tt(0,5)=s4v:(0,1),0,32;;");
		StructType holder = (StructType) named(p, "holder");
		PointerType ptr = (PointerType) holder.fields().get(0).type().resolve();
		assertSame(named(p, "Box<a::b,int>"), ptr.target().resolve());
	}

	@Test
	void cppClassWithBaseVtableStaticsAndMethods() {
		// Shapes taken from cgame.so: a class whose vptr lives in its base class
		StabsProgram p = parse(
			N_LSYM, "int:t(0,1)=r(0,1);0020000000000;0017777777777;",
			N_LSYM, "void:t(0,20)=(0,20)",
			N_LSYM, "Base:Tt(0,2)=s8x:/0(0,1),0,32;.vf(0,2):(0,3)=*(0,1),32;" +
				"Base::(0,4)=##(0,5)=*(0,2);:;2A.;get::(0,6)=##(0,1);:;2B*2;(0,2);;" +
				"make::(0,7)=f(0,5):;2A?;;~%(0,2);",
			N_LSYM, "Derived:Tt(0,8)=s12!1,020,(0,2);y:/1(0,1),64,32;" +
				"count:/2(0,1):_7Derived.count;" +
				"set::(0,9)=##(0,20);:iPCc;2A.(0,10)=#(0,8),(0,20),(0,11)=*(0,8),(0,1),(0,20);" +
				":set__7Derivedi;2A.;" +
				"op::(0,12)=#(0,8),(0,20),(0,11),(0,1),(0,20);:i;2A.;" +
				"~Derived::(0,13)=##(0,20);:_._7Derived;2A*3;(0,2);;~%(0,2);");

		StructType base = (StructType) named(p, "Base");
		assertTrue(base.ownVptr());
		assertEquals(FieldKind.VPTR, base.fields().get(1).kind());
		assertEquals(32, base.fields().get(1).bitOffset());
		Method get = base.methods().get(1);
		assertEquals("get__C4Base", get.physname());
		assertEquals(MethodKind.VIRTUAL, get.kind());
		assertEquals(2, get.vtableIndex());
		assertEquals("__4Base", base.methods().get(0).physname());
		Method make = base.methods().get(2);
		assertEquals(MethodKind.STATIC, make.kind());
		assertEquals("make__4Base", make.physname());

		StructType derived = (StructType) named(p, "Derived");
		assertEquals(1, derived.baseClasses().size());
		BaseClass bc = derived.baseClasses().get(0);
		assertSame(base, bc.type().resolve());
		assertEquals(Visibility.PUBLIC, bc.visibility());
		assertFalse(bc.isVirtual());
		assertSame(base, derived.vptrBase().resolve());
		assertEquals(Visibility.PROTECTED, derived.fields().get(0).visibility());
		assertEquals("_7Derived.count", derived.staticFields().get(0).physname());
		assertEquals(List.of("set__7DerivediPCc", "set__7Derivedi", "op__7Derivedi",
			"_._7Derived"), derived.methods().stream().map(Method::physname).toList());
		MethodType full = derived.methods().get(1).type();
		assertFalse(full.isStub());
		assertEquals(2, full.args().size()); // this, int
	}

	@Test
	void functionWithParamsLocalsAndLines() {
		StabsProgram p = parse(
			N_LSYM, "int:t(0,1)=r(0,1);0020000000000;0017777777777;",
			N_FUN, "add:F(0,1)", 0x1010L,
			N_PSYM, "a:p(0,1)", 8L,
			N_PSYM, "b:p(0,1)", 12L,
			N_SLINE, "", 3L,
			N_LSYM, "sum:(0,1)", -4L,
			N_RSYM, "i:r(0,1)", 1L,
			N_LBRAC, "", 3L,
			N_LSYM, "t:(0,1)", -8L,
			N_LBRAC, "", 6L,
			N_RBRAC, "", 10L,
			N_RBRAC, "", 20L,
			N_FUN, "helper:f(0,1)", 0x1040L,
			N_STSYM, "counter:S(0,1)", 0x2000L,
			N_GSYM, "total:G(0,1)");
		assertEquals(2, p.functions().size());
		Function add = p.functions().get(0);
		assertTrue(add.global());
		assertEquals(0x1010, add.address());
		assertEquals(List.of("a", "b"), add.params().stream().map(Variable::name).toList());
		assertEquals(12, add.params().get(1).value());
		assertEquals(3, add.locals().size());
		assertEquals(Variable.Storage.REGISTER, add.locals().get(1).variable().storage());
		// a block's variables come right before its N_LBRAC
		assertEquals(new Function.Local(add.locals().get(0).variable(), 1, 3, 20),
			add.locals().get(0));
		assertEquals(new Function.Local(add.locals().get(2).variable(), 2, 6, 10),
			add.locals().get(2));
		assertEquals(0x1013, add.lines().get(0).address());
		assertFalse(p.functions().get(1).global());
		assertEquals(List.of("counter", "total"),
			p.globals().stream().map(g -> g.variable().name()).toList());
	}

	@Test
	void headerTypesAreSharedThroughExcl() {
		List<StabEntry> e = new ArrayList<>();
		e.add(new StabEntry(0, N_SO, 0, 0, 0x1000, "a.c"));
		e.add(new StabEntry(1, N_BINCL, 0, 0, 1234, "common.h"));
		e.add(new StabEntry(2, N_LSYM, 0, 0, 0, "int:t(1,1)=r(1,1);0020000000000;0017777777777;"));
		e.add(new StabEntry(3, N_LSYM, 0, 0, 0, "pt:T(1,2)=s4x:(1,1),0,32;;"));
		e.add(new StabEntry(4, N_EINCL, 0, 0, 0, ""));
		e.add(new StabEntry(5, N_SO, 0, 0, 0x1100, ""));
		e.add(new StabEntry(6, N_SO, 0, 0, 0x1100, "b.c"));
		e.add(new StabEntry(7, N_EXCL, 0, 0, 1234, "common.h"));
		e.add(new StabEntry(8, N_GSYM, 0, 0, 0, "g:G(1,2)"));
		StabsProgram p = StabsParser.parse(e);
		assertEquals(List.of(), p.issues());
		assertEquals(2, p.units().size());
		assertEquals(2, p.namedTypes().size());
		assertSame(named(p, "pt"), p.globals().get(0).variable().type().resolve());
	}
}
