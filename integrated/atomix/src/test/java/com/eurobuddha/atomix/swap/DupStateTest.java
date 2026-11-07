package com.eurobuddha.atomix.swap;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Review 7 Nov (0.2.8): a transaction can carry two entries for one state port. Proven on a -test chain: the coin
 * landed with port 3 = [1, 9]; a script asserting PREVSTATE(3) EQ 1 spent it, one asserting EQ 9 failed; simplestate
 * JSON showed 9. So the app must read the state ARRAY and take the FIRST entry, as KISS does.
 */
public class DupStateTest {
    @Test public void readerTakesTheFirstEntryLikeKiss() throws Exception {
        JSONObject coin = new JSONObject().put("state", new JSONArray()
                .put(new JSONObject().put("port", 3).put("type", 1).put("data", "100"))
                .put(new JSONObject().put("port", 3).put("type", 1).put("data", "999999")));
        assertEquals("100", MinimaHtlc.stateAt(coin, 3));
    }

    @Test public void fundDecidingScansNeverAskForSimplestate() throws Exception {
        String base = "src/main/java/com/eurobuddha/atomix/swap/";
        for (String f : new String[]{"MinimaHtlc.java", "OtcBook.java", "SwapOrderBook.java"}) {
            String src = new String(Files.readAllBytes(Paths.get(base + f)), "UTF-8");
            for (String line : src.split("\n")) {
                if (!line.contains("simplestate:true") || line.trim().startsWith("//") || line.trim().startsWith("*")) continue;
                assertTrue(f + " still asks for simplestate outside the wallet diagnostic: " + line.trim(),
                        line.contains("coins relevant:true tokenid:\" + token + \" simplestate:true"));
            }
        }
    }
}
