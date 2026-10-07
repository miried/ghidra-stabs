package stabs.model;

import static org.junit.jupiter.api.Assertions.*;

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
}
