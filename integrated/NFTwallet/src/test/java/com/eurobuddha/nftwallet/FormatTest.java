package com.eurobuddha.nftwallet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Amount display trimming. The rule that matters: a balance is FLOORED, never rounded — showing
 * more money than someone has is the one mistake a wallet must not make.
 */
public class FormatTest {

    /** The real balance that swamped the card, at 4 places. */
    @Test public void trimsALongMinimaBalance() {
        assertEquals("12185.9315…", Format.trim("12185.93151029742999999999999999999999999999999953", 4));
    }

    @Test public void neverRoundsUp() {
        // 0.99999 at 2dp must not become 1.00 — that would claim money that isn't there.
        assertEquals("0.99…", Format.trim("0.99999", 2));
        assertEquals("1.99…", Format.trim("1.999999999", 2));
    }

    @Test public void exactValuesCarryNoEllipsis() {
        assertEquals("12.5", Format.trim("12.5", 4));
        assertEquals("7", Format.trim("7", 4));
        assertEquals("0", Format.trim("0", 4));
    }

    @Test public void fullPrecisionIsUntouched() {
        String raw = "12185.93151029742999999999999999999999999999999953";
        assertEquals(raw, Format.trim(raw, Format.FULL));
    }

    /** A dust amount must not read as a flat zero — that looks like an empty wallet. */
    @Test public void tinyAmountsAreNotShownAsZero() {
        String out = Format.trim("0.00000001", 4);
        assertTrue(out, out.startsWith("<0."));
    }

    @Test public void handlesGarbageWithoutThrowing() {
        assertEquals("0", Format.trim("", 4));
        assertEquals("0", Format.trim(null, 4));
        assertEquals("not-a-number", Format.trim("not-a-number", 4));
    }
}
