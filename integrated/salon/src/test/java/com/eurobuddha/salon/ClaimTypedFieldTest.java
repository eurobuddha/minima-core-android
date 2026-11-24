package com.eurobuddha.salon;

import static org.junit.Assert.*;

import org.junit.Test;

/** The claim screen names the field and the first character it refuses. */
public class ClaimTypedFieldTest {
    @Test public void theRefusalNamesTheCharacter() {
        assertEquals("]", Args.badChar("Alice] amount:1"));
        assertEquals("[", Args.badChar("a[b"));
        assertEquals("\"", Args.badChar("say \"hi\""));
        assertEquals("\\", Args.badChar("back\\slash"));
        assertEquals("{", Args.badChar("{x}"));
        assertEquals(";", Args.badChar("a;b"));
        assertEquals("line break / control character", Args.badChar("a\nb"));
        assertNull(Args.badChar("Alice"));
    }
}
