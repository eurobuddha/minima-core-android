package org.minimarex.minimacore;

import org.junit.Test;
import org.minimarex.minimacore.utils.Format;
import java.math.BigDecimal;
import static org.junit.Assert.*;

public class FormatTest {
    @Test public void summaryLimitsDecimalsWithoutRoundingUp() {
        String exact = "23466.53282076419999999999999999999999999999997";
        assertEquals("23466.53282", Format.summaryAmount(exact));
        assertEquals("0.999999", Format.summaryAmount("0.999999999"));
        assertEquals("120.894059", Format.summaryAmount("120.8940599"));
        assertEquals("12.5", Format.summaryAmount("12.50000000"));
        assertEquals("100", Format.summaryAmount("100.000000"));
        assertEquals("12345678901234567890.123456",
                Format.summaryAmount("12345678901234567890.123456789"));
        assertEquals(exact, Format.tidyAmount(exact));
    }

    @Test public void summaryKeepsTinyBalancesVisible() {
        assertEquals("<0.000001", Format.summaryAmount("0.000000999999"));
        assertEquals("<0.000001", Format.summaryAmount("1.0E-44"));
        assertEquals("<0.000001", Format.summaryAmount("1E-2000000000"));
        assertEquals(">-0.000001", Format.summaryAmount("-1E-44"));
        assertEquals("0.000001", Format.summaryAmount("0.000001"));
        assertEquals("0", Format.summaryAmount("-0.00000000"));
    }

    @Test public void summaryHandlesNotationAndPlaceholders() {
        assertEquals("12000000000", Format.summaryAmount("1.200E+10"));
        assertEquals("1.234567", Format.summaryAmount(" 1.23456789 "));
        assertEquals("0", Format.summaryAmount(null));
        assertEquals("0", Format.summaryAmount(""));
        assertEquals("—", Format.summaryAmount("—"));
        assertEquals("1E+2000000000", Format.summaryAmount("1E+2000000000"));
    }

    @Test public void keepsEverySignificantDecimal() {
        String value = "23466.53282076419999999999999999999999999999997";
        assertEquals(value, Format.tidyAmount(value));
        assertEquals("0.00000000000000000000000000000000000000000001",
                Format.tidyAmount("1E-44"));
    }

    @Test public void exponentZerosNeverChangeTheValue() {
        for (String input : new String[]{"1.0E-10", "1.200E+10", "0.00000", "-0.000", "12.50000", "100"}) {
            assertEquals(input, 0, new BigDecimal(input).compareTo(new BigDecimal(Format.tidyAmount(input))));
        }
        assertEquals("0.0000000001", Format.tidyAmount("1.0E-10"));
        assertEquals("12000000000", Format.tidyAmount("1.200E+10"));
    }

    @Test public void retainsProvenTidyAmountBehavior() {
        // Existing PandaPools UtilTest vectors, unchanged.
        assertEquals("2", Format.tidyAmount("2.000000"));
        assertEquals("2.5", Format.tidyAmount("2.50"));
        assertEquals("100", Format.tidyAmount("100"));
        assertEquals("0", Format.tidyAmount(""));
        assertEquals("0", Format.tidyAmount(null));
        assertEquals("—", Format.tidyAmount("—"));
        assertEquals("1E-2000000000", Format.tidyAmount("1E-2000000000"));
    }
}
