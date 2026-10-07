package com.eurobuddha.atomix;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Util.fmt5 — the 5-decimal-place DISPLAY truncation for Minima balances. Truncates (floor), never rounds,
 *  trims trailing zeros, and leaves non-numeric placeholders untouched. */
public class UtilTest {

    @Test public void truncatesHighPrecisionTailToFiveDp() {
        // the real on-screen value — a long high-precision tail must show as a clean 5dp
        assertEquals("23466.53282", Util.fmt5("23466.53282076419999999999999999999999999999997"));
        assertEquals("23587.42687", Util.fmt5("23587.4268707641999999999999999999999999999997"));
    }

    @Test public void neverRoundsUp() {
        assertEquals("0.99999", Util.fmt5("0.999999"));      // NOT 1.0
        assertEquals("120.89405", Util.fmt5("120.894059"));  // NOT ...06
        assertEquals("0", Util.fmt5("0.000009"));            // truncated below 5dp → 0
    }

    @Test public void trimsTrailingZerosAndHandlesIntegers() {
        assertEquals("100", Util.fmt5("100"));
        assertEquals("100", Util.fmt5("100.000000"));
        assertEquals("12.5", Util.fmt5("12.50000"));
        assertEquals("0", Util.fmt5("0"));
    }

    @Test public void passesThroughPlaceholdersAndEmpty() {
        assertEquals("…", Util.fmt5("…"));
        assertEquals("— (node didn't answer)", Util.fmt5("— (node didn't answer)"));
        assertEquals("0", Util.fmt5(""));
        assertEquals("0", Util.fmt5(null));
    }
}
