package com.eurobuddha.pandapools;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

/** Reuses AtomiX ReportMessagesTest's literal-evidence assertions. */
public class PoolsRecoveryMessagesTest {
    private static Object field(Object value, String name) throws Exception {
        Field f = Language.Message.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(value);
    }

    @Test public void postedWithdrawalUsesSubmittedStateAndKeepsExactEvidence() throws Exception {
        String id = "0x" + "ab".repeat(64) + " 中文 Русский Українська $1 {id}";
        Object message = PoolsMessages.message("Withdrawn ✓ " + id);
        assertEquals(R.string.l10n_pools_recovery_withdrawal_submitted, field(message, "resource"));
        assertArrayEquals(new Object[]{id}, (Object[]) field(message, "arguments"));
        assertEquals("prefix\r\nSUBMITTED " + id + "\r\n\nend\n",
                reportText(PoolsMessages.report("prefix\r\nWithdrawn ✓ " + id + "\r\n\nend\n")));
    }

    @Test public void unknownReportLinesKeepWhitespaceIdentifiersAndLiteralBraces() throws Exception {
        String raw = "  public data 中文 $1 {count}\r\n\r\n0x" + "12".repeat(64)
                + "\nnode diagnostic: Withdrawn ✓ does not match the whole line\n\n";
        assertEquals(raw, reportText(PoolsMessages.report(raw)));
        assertEquals("", PoolsMessages.report(null));
        assertEquals("", PoolsMessages.report(""));
    }

    // Inspect immutable messages without requiring Android resources or a real node.
    private static String reportText(Object value) throws Exception {
        if (value instanceof String) return (String) value;
        int resource = (Integer) field(value, "resource");
        Object[] arguments = (Object[]) field(value, "arguments");
        if (resource == R.string.l10n_pools_recovery_withdrawal_submitted) {
            assertEquals(1, arguments.length);
            return "SUBMITTED " + arguments[0];
        }
        assertEquals("Unexpected translation of a literal report line", 0, resource);
        StringBuilder result = new StringBuilder();
        for (Object argument : arguments) result.append(reportText(argument));
        return result.toString();
    }
}
