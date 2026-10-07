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
		assertTrue(GnuV2Names.isConstructor("__8Listener"));
		assertTrue(GnuV2Names.isConstructor("__t9Container1Z13emittertime_t"));
		assertFalse(GnuV2Names.isConstructor("__as__8ListenerRC8Listener"));
		assertTrue(GnuV2Names.isDestructor("_._8Listener"));
		assertFalse(GnuV2Names.isDestructor("Remove__8ListenerP14Event_CGAMEDLL"));
	}

	@Test
	void freeFunctions() {
		assertEquals("FindBeamList", GnuV2Names.freeFunctionName("FindBeamList__Fi"));
		assertEquals("CG_Subdivide",
			GnuV2Names.freeFunctionName("CG_Subdivide__FG6VectorN20R6VectorN23"));
		assertEquals("operator<<", GnuV2Names.freeFunctionName("__ls__FR7ostreamPCc"));
		assertEquals("operator+", GnuV2Names.freeFunctionName("__pl__FRC3strf"));
		assertNull(GnuV2Names.freeFunctionName("CG_MultiBeamBegin"));
	}

	@Test
	void classOfPhysname() {
		assertEquals("8Listener", GnuV2Names.classOfPhysname("__as__8ListenerRC8Listener", "__as"));
		assertEquals("8Listener", GnuV2Names.classOfPhysname("_._8Listener", "Listener"));
		assertEquals("t9Container1Z13emittertime_t", GnuV2Names.classOfPhysname(
			"__t9Container1Z13emittertime_tRCt9Container1Z13emittertime_t", "Container"));
		assertEquals("t3Foo1Zi", GnuV2Names.classOfPhysname("size__Ct3Foo1Zi", "size"));
		String block = "Q2t14MEM_BlockAlloc2ZQ2t7con_set2ZP14Event_CGAMEDLLZQ2t7con_map2Z" +
			"P14Event_CGAMEDLLZ8EventDef5Entry5EntryZA255_c7block_s";
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
		assertEquals(List.of("con_set<K,con_map<K,V>::Entry>", "Entry"),
			GnuV2Names.splitQualified("con_set<K,con_map<K,V>::Entry>::Entry"));
		assertEquals(List.of("Entry"), GnuV2Names.splitQualified("Entry"));
	}
}
