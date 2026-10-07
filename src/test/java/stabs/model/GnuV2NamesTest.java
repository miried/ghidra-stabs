package stabs.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class GnuV2NamesTest {

	@Test
	void operators() {
		assertEquals("operator=", GnuV2Names.operatorName("__as"));
		assertEquals("operator+=", GnuV2Names.operatorName("__apl"));
		assertEquals("operator[]", GnuV2Names.operatorName("__vc"));
		assertEquals("operator new", GnuV2Names.operatorName("__nw"));
		assertEquals("operator_cast", GnuV2Names.operatorName("__opPf"));
		assertNull(GnuV2Names.operatorName("__exception"));
		assertNull(GnuV2Names.operatorName("Remove"));
	}

	@Test
	void constructorsAndDestructors() {
		assertTrue(GnuV2Names.isConstructor("__6Widget"));
		assertTrue(GnuV2Names.isConstructor("__t9Container1Z6item_t"));
		assertFalse(GnuV2Names.isConstructor("__as__6WidgetRC6Widget"));
		assertTrue(GnuV2Names.isDestructor("_._6Widget"));
		assertFalse(GnuV2Names.isDestructor("Remove__6WidgetP5Event"));
	}

	@Test
	void freeFunctions() {
		assertEquals("FindItem", GnuV2Names.freeFunctionName("FindItem__Fi"));
		assertEquals("Subdivide",
			GnuV2Names.freeFunctionName("Subdivide__FG6VectorN20R6VectorN23"));
		assertEquals("operator<<", GnuV2Names.freeFunctionName("__ls__FR7ostreamPCc"));
		assertEquals("operator+", GnuV2Names.freeFunctionName("__pl__FRC3strf"));
		assertNull(GnuV2Names.freeFunctionName("BeginFrame"));
	}

	@Test
	void classOfPhysname() {
		assertEquals("6Widget", GnuV2Names.classOfPhysname("__as__6WidgetRC6Widget", "__as"));
		assertEquals("6Widget", GnuV2Names.classOfPhysname("_._6Widget", "Widget"));
		assertEquals("t9Container1Z6item_t", GnuV2Names.classOfPhysname(
			"__t9Container1Z6item_tRCt9Container1Z6item_t", "Container"));
		assertEquals("t3Foo1Zi", GnuV2Names.classOfPhysname("size__Ct3Foo1Zi", "size"));
		String block = "Q2t10BlockAlloc2ZQ2t3Set2ZP5EventZQ2t3Map2ZP5EventZ3Def5Entry5Entry" +
			"ZA255_c7block_s";
		assertEquals(block, GnuV2Names.classOfPhysname("__as__" + block + "RC" + block, "__as"));

		List<String> parts = GnuV2Names.qualifiedComponents(block);
		assertEquals(2, parts.size());
		assertEquals("7block_s", parts.get(1));
		assertEquals("block_s", GnuV2Names.simpleName(parts.get(1)));
		assertNull(GnuV2Names.simpleName(parts.get(0)));
		assertEquals("t3Bar2Zii42", GnuV2Names.classOfPhysname("f__t3Bar2Zii42Pc", "f"));
	}

	@Test
	void splitQualified() {
		assertEquals(List.of("Set<K,Map<K,V>::Entry>", "Entry"),
			GnuV2Names.splitQualified("Set<K,Map<K,V>::Entry>::Entry"));
		assertEquals(List.of("Entry"), GnuV2Names.splitQualified("Entry"));
	}
}
