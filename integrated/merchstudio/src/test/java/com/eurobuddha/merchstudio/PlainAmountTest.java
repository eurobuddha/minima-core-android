package com.eurobuddha.merchstudio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Studio writes prices and fees as plain decimals, the same number, so the Shop's plain-price rule accepts them. */
public class PlainAmountTest {
    @Test public void sameNumberWrittenPlain() {
        assertEquals("1000", Util.plainAmount("1e3"));
        assertEquals("5", Util.plainAmount("+5"));
        assertEquals("0.5", Util.plainAmount(" .5 "));
        assertEquals("1.50", Util.plainAmount("1.50"));
        assertEquals("0.000000000000000000000000000000000001", Util.plainAmount("1E-36"));
        assertEquals("", Util.plainAmount(""));          // empty stays empty (fees are written "0" before this)
        assertEquals("1,5", Util.plainAmount(" 1,5"));   // not a number: passed on, the shop refuses it
    }
}
