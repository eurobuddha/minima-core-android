package com.eurobuddha.futurecashnext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AuditEvidenceTest {
    private static <T> List<T> parse(String method, JSONObject... rows) throws Exception {
        Method parser = AuditApi.class.getDeclaredMethod(method, JSONArray.class, List.class);
        parser.setAccessible(true);
        JSONArray json = new JSONArray();
        for (JSONObject row : rows) json.put(row);
        List<T> result = new ArrayList<>();
        parser.invoke(null, json, result);
        return result;
    }
    @Test public void missingOrMalformedReuseVerdictIsNotClean() throws Exception {
        for (Object value : new Object[]{JSONObject.NULL, "false", 0}) {
            JSONObject row = new JSONObject().put("address", "0xABC").put("reuse_count", 0).put("reused", value);
            assertTrue(parse("parseReuse", row).isEmpty());
        }
        assertTrue(parse("parseReuse", new JSONObject().put("address", "0xABC").put("reused", false)).isEmpty());
    }
    @Test public void explicitNegativeAndPositiveEvidenceAreRetained() throws Exception {
        List<KeyAudit.Reuse> rows = parse("parseReuse",
                new JSONObject().put("address", "0xABC").put("reused", false).put("reuse_count", 0),
                new JSONObject().put("address", "0xDEF").put("reused", true).put("reuse_count", 1));
        assertEquals(2, rows.size());
        assertFalse(rows.get(0).reused);
        assertTrue(rows.get(1).reused);
    }
    @Test public void corruptUsageCountsCannotBecomeZeroUsage() throws Exception {
        for (Object count : new Object[]{JSONObject.NULL, "oops", -1, 0.5, "9223372036854775808"}) {
            JSONObject row = new JSONObject().put("publickey", "0xABC").put("address", "0xDEF")
                    .put("spend_blocks", count).put("spent_coins", 1);
            assertTrue(parse("parseUsage", row).isEmpty());
        }
    }
    @Test public void backendErrorsCannotBeTreatedAsCompletedRows() throws Exception {
        assertTrue(parse("parseReuse", new JSONObject().put("address", "0xABC")
                .put("reused", false).put("reuse_count", 0).put("error", "unavailable")).isEmpty());
        assertTrue(parse("parseUsage", new JSONObject().put("publickey", "0xABC")
                .put("spend_blocks", 0).put("spent_coins", 0).put("error", "unavailable")).isEmpty());
    }
}
