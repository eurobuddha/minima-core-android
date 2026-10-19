package com.eurobuddha.ethwallet;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Adapts Parlons SystemMessages tests: exact lookup, literal captures and bounded recursion. */
public class EthMessagesTest {
    static Object field(Object value,String name)throws Exception {
        Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    static int resource(String key)throws Exception {
        String name="l10n_"+key.replaceAll("([a-z0-9])([A-Z])","$1_$2").replace('.','_').toLowerCase(Locale.ROOT);
        return R.string.class.getField(name).getInt(null);
    }
    static JSONObject source()throws Exception {
        return new JSONObject(new String(Files.readAllBytes(Path.of("../../localization/eth-errors.json")), java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void everyDeclaredFixedFailureResolves()throws Exception {
        JSONObject fixed=source().getJSONObject("fixed");assertEquals(6, fixed.length());
        for(Iterator<String> it=fixed.keys();it.hasNext();) {
            String raw=it.next();
            CharSequence value=EthMessages.message(raw);assertTrue(raw,value instanceof Language.Message);
            assertEquals(raw,resource(fixed.getString(raw)),field(value,"resource"));
            assertEquals(0,((Object[])field(value,"arguments")).length);
        }
    }
    @Test public void everyTemplateKeepsItsLiteralCaptures()throws Exception {
        JSONArray patterns=source().getJSONArray("patterns");assertEquals(8,patterns.length());
        Map<String,String> literals=new HashMap<>();literals.put("number","00012.34000");literals.put("integer","000123");
        literals.put("hex","0xAbCd1234");literals.put("address","Mx"+"Ab9".repeat(12));literals.put("boolean","false");literals.put("command","txnpost");
        literals.put("literal","Copy 中文 $1 {0} <img>");literals.put("diagnostic","Copy 中文 $1 {0} <img>");
        for(int i=0;i<patterns.length();i++) {
            JSONObject row=patterns.getJSONObject(i);JSONArray kinds=row.getJSONArray("kinds");
            String raw=row.getString("template");Object[] args=new Object[kinds.length()];
            for(int j=0;j<args.length;j++){args[j]=literals.get(kinds.getString(j));raw=raw.replace("{"+j+"}",(String)args[j]);}
            CharSequence value=EthMessages.message(raw);assertTrue(raw,value instanceof Language.Message);
            assertEquals(resource(row.getString("key")),field(value,"resource"));assertArrayEquals(args,(Object[])field(value,"arguments"));
        }
    }
    @Test public void remoteDetailsStayLiteralEvenWhenTheyMatchLocalFailures()throws Exception {
        String raw="Node returned an all-zero key. Not using it.";
        Object value=EthMessages.message("eth_getBalance failed on all RPCs: "+raw);
        assertArrayEquals(new Object[]{"eth_getBalance",raw},(Object[])field(value,"arguments"));
        for(String input:new String[]{"Ethereum", "Copy 中文 $1 {0}", "invalid opcode: revert",
                "Node returned a malformed key (NaN chars, expected 64). Not using it.",
                "Refusing to sign: gas price 1e9 gwei exceeds the 1500 gwei safety cap. Check your RPC endpoint."})
            assertEquals(input,EthMessages.message(input));
    }
    @Test public void diagnosticNumbersAndNullRemainUnchanged()throws Exception {
        Object value=EthMessages.message("Refusing to sign: gas price 00012.34000 gwei exceeds the 1500 gwei safety cap. Check your RPC endpoint.");
        assertArrayEquals(new Object[]{"00012.34000","1500"},(Object[])field(value,"arguments"));
        value=EthMessages.message("Refusing to sign: gas limit null is outside the safe range (max 1000000). Check your RPC endpoint.");
        assertArrayEquals(new Object[]{"null","1000000"},(Object[])field(value,"arguments"));
        assertEquals("",EthMessages.message(null));
        assertEquals("unrecognized detail",EthMessages.error(new Exception("unrecognized detail")));
    }
}
