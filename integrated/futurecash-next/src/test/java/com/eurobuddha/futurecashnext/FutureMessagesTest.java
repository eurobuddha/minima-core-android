package com.eurobuddha.futurecashnext;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Adapts Parlons SystemMessages tests: exact lookup, literal captures and bounded recursion. */
public class FutureMessagesTest {
    static Object field(Object value,String name)throws Exception {
        Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    static int resource(String key)throws Exception {
        String name="l10n_"+key.replaceAll("([a-z0-9])([A-Z])","$1_$2").replace('.','_').toLowerCase(Locale.ROOT);
        return R.string.class.getField(name).getInt(null);
    }
    static JSONObject source()throws Exception {
        return new JSONObject(new String(Files.readAllBytes(Path.of("../../localization/future-messages.json")), java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void everyDeclaredFixedFailureResolves()throws Exception {
        JSONObject fixed=source().getJSONObject("fixed");assertTrue(fixed.length()==99);
        for(Iterator<String> it=fixed.keys();it.hasNext();) {
            String raw=it.next();
            CharSequence value=FutureMessages.message(raw);assertTrue(raw,value instanceof Language.Message);
            assertEquals(raw,resource(fixed.getString(raw)),field(value,"resource"));
            assertEquals(0,((Object[])field(value,"arguments")).length);
        }
    }
    @Test public void everyTemplateKeepsItsLiteralCaptures()throws Exception {
        JSONArray patterns=source().getJSONArray("patterns");assertEquals(53,patterns.length());
        Map<String,String> literals=new HashMap<>();literals.put("number","00012.34000");literals.put("integer","000123");
        literals.put("hex","0xAbCd1234");literals.put("address","Mx"+"Ab9".repeat(12));literals.put("boolean","false");literals.put("command","txnpost");
        literals.put("literal","Copy 中文 $1 {0} <img>");literals.put("diagnostic","Copy 中文 $1 {0} <img>");
        for(int i=0;i<patterns.length();i++) {
            JSONObject row=patterns.getJSONObject(i);JSONArray kinds=row.getJSONArray("kinds");
            String raw=row.getString("template");Object[] args=new Object[kinds.length()];
            for(int j=0;j<args.length;j++){args[j]=literals.get(kinds.getString(j));raw=raw.replace("{"+j+"}",(String)args[j]);}
            CharSequence value=FutureMessages.message(raw);assertTrue(raw,value instanceof Language.Message);
            assertEquals(resource(row.getString("key")),field(value,"resource"));assertArrayEquals(args,(Object[])field(value,"arguments"));
        }
    }
    @Test public void unknownContentAndMalformedNumbersStayLiteral() {
        for(String raw:new String[]{"Future Cash", "Future Cash ⚠️", "Copy 中文 $1 {0}",
                "Secured 1e9 to your safe address", "Collected NaN safely", "Collected 12 safely\n"})
            assertEquals(raw,FutureMessages.message(raw));
        assertEquals("",FutureMessages.message(null));
    }
    @Test public void diagnosticReasonsTranslateAndRecursionIsBounded()throws Exception {
        String prefix="The guardian can't move an at-risk stake to safety — ";
        Object translated=FutureMessages.message(prefix+"no safe destination is set.");
        Object reason=((Object[])field(translated,"arguments"))[0];
        assertEquals(resource("future.reasonNoSafe"),field(reason,"resource"));
        String raw="unknown 0xAbCd 中文 $1 {0}";
        Object unknown=FutureMessages.message(prefix+raw+".");
        assertEquals(raw,((Object[])field(unknown,"arguments"))[0]);
        Object value=FutureMessages.message(prefix.repeat(12)+raw+".".repeat(12));
        for(int i=0;i<8;i++)value=((Object[])field(value,"arguments"))[0];
        assertEquals(prefix.repeat(4)+raw+".".repeat(4),value);
    }

    @Test public void nestedAuditFailureTranslatesButUnknownNodeReplyStaysLiteral()throws Exception {
        String known="Could not reach the audit service. Check your connection and try again.";
        Object value=FutureMessages.message("Couldn't reach the audit service ("+known+").");
        assertEquals(resource("future.errorAuditService"),field(value,"resource"));
        Object nested=((Object[])field(value,"arguments"))[0];
        assertEquals(resource("future.errorAuditConnection"),field(nested,"resource"));
        // A node response is data, even when its words match an app-owned failure.
        for(String stage:new String[]{"txncreate","txninput","txnoutput","txnsign","txnbasics","txnpost"}) {
            Object reply=FutureMessages.message(known+" (at "+stage+")");
            assertEquals(known,((Object[])field(reply,"arguments"))[0]);
        }
        String raw="0xAbCd \nУкраїна 中文 $1 {0}";
        Object address=FutureMessages.message("Couldn't make a fresh address on this node. ("+raw+")");
        assertEquals(raw,((Object[])field(address,"arguments"))[0]);
    }
    @Test public void blockNumbersStayExactAndUnknownStagesDoNotMatch()throws Exception {
        Object value=FutureMessages.message("Not matured yet — unlocks at block 0002350001 (now 0002350000).");
        assertArrayEquals(new Object[]{"0002350001","0002350000"},(Object[])field(value,"arguments"));
        for(String raw:new String[]{"Not matured yet — unlocks at block 2e6 (now 2350000).",
                "Minima Core didn't respond (at seed)","private text (at unknown)",
                "This address still holds NaN coin(s). Sweep them to safety first (they're on an exposed key), then retire it."})
            assertEquals(raw,FutureMessages.message(raw));
    }

    @Test public void logFieldsKeepAddressesAndEntireNestedFailureSeparate()throws Exception {
        String address="Mx"+"Ab9".repeat(12), coin="0xAbCd1234";
        String reason="Couldn't read Minima Core's reply to txnpost — it may have succeeded. Check the Activity log before retrying.";
        Object value=FutureMessages.message("coin:"+coin+" | from:"+address+" — "+reason);
        assertEquals(resource("future.logCoinFromError"),field(value,"resource"));
        Object[] arguments=(Object[])field(value,"arguments");
        assertEquals(coin,arguments[0]);assertEquals(address,arguments[1]);
        assertEquals(resource("future.errorStageUnknownTxnpost"),field(arguments[2],"resource"));
        // Field layout takes precedence over the generic transaction-stage error suffix.
        value=FutureMessages.message("coin:"+coin+" | on:"+address+" — raw node detail (at txnpost)");
        assertEquals(resource("future.logCoinOnError"),field(value,"resource"));
        Object nested=((Object[])field(value,"arguments"))[2];
        assertEquals("raw node detail",((Object[])field(nested,"arguments"))[0]);
        for(String raw:new String[]{"coin:"+coin+" | from:not an address — "+reason,
                "coin:broken | to:"+address,"address:Mxshort", "Sweeping NaN"})
            assertEquals(raw,FutureMessages.message(raw));
    }
}
