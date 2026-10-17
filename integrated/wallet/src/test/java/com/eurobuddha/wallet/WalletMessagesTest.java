package com.eurobuddha.wallet;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Adapts Parlons SystemMessages tests: exact lookup, literal captures and bounded recursion. */
public class WalletMessagesTest {
    static Object field(Object value,String name)throws Exception {
        Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    static int resource(String key)throws Exception {
        String name="l10n_"+key.replaceAll("([a-z0-9])([A-Z])","$1_$2").replace('.','_').toLowerCase(Locale.ROOT);
        return R.string.class.getField(name).getInt(null);
    }
    static JSONObject source()throws Exception {
        return new JSONObject(new String(Files.readAllBytes(Path.of("../../localization/wallet-errors.json")), java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void everyDeclaredFixedFailureResolves()throws Exception {
        JSONObject fixed=source().getJSONObject("fixed");assertTrue(fixed.length()>80);
        for(Iterator<String> it=fixed.keys();it.hasNext();) {
            String raw=it.next();
            CharSequence value=WalletMessages.message(raw);assertTrue(raw,value instanceof Language.Message);
            assertEquals(raw,resource(fixed.getString(raw)),field(value,"resource"));
            assertEquals(0,((Object[])field(value,"arguments")).length);
        }
    }
    @Test public void everyTemplateKeepsItsLiteralCaptures()throws Exception {
        JSONArray patterns=source().getJSONArray("patterns");assertEquals(25,patterns.length());
        Map<String,String> literals=new HashMap<>();literals.put("number","00012.34000");literals.put("integer","000123");
        literals.put("hex","0xAbCd1234");literals.put("boolean","false");literals.put("command","txnpost");
        literals.put("literal","Copy 中文 $1 {0} <img>");literals.put("diagnostic","Copy 中文 $1 {0} <img>");
        for(int i=0;i<patterns.length();i++) {
            JSONObject row=patterns.getJSONObject(i);JSONArray kinds=row.getJSONArray("kinds");
            String raw=row.getString("template");Object[] args=new Object[kinds.length()];
            for(int j=0;j<args.length;j++){args[j]=literals.get(kinds.getString(j));raw=raw.replace("{"+j+"}",(String)args[j]);}
            CharSequence value=WalletMessages.message(raw);assertTrue(raw,value instanceof Language.Message);
            assertEquals(resource(row.getString("key")),field(value,"resource"));assertArrayEquals(args,(Object[])field(value,"arguments"));
        }
    }
    @Test public void unknownContentAndMalformedNumbersStayLiteral() {
        for(String raw:new String[]{"Copy","Saved 中文 $1 {0}","Insufficient funds: have 1e9 need 2", "Split must be 1..20\n",
                "Key -1 exhausted: NaN / 262144 one-time signatures used", "send failed: Wallet is locked"})
            assertEquals(raw,WalletMessages.message(raw));
        assertEquals("",WalletMessages.message(null));
    }
    @Test public void diagnosticRecursionIsBoundedAndDataIsNeverTranslated()throws Exception {
        String prefix="Coin parse failed: ";Object value=WalletMessages.message(prefix.repeat(12)+"Wallet is locked");
        for(int i=0;i<8;i++)value=((Object[])field(value,"arguments"))[0];
        assertEquals(prefix.repeat(4)+"Wallet is locked",value);
        Object foreign=WalletMessages.message("Coin address is not in our wallet: Wallet is locked");
        assertEquals("Wallet is locked",((Object[])field(foreign,"arguments"))[0]);
        Object command=WalletMessages.message("txnpost failed: Wallet is locked");
        assertEquals("Wallet is locked",((Object[])field(command,"arguments"))[1]);
        Object nested=WalletMessages.message("Coin parse failed: Wallet is locked");
        assertTrue(((Object[])field(nested,"arguments"))[0] instanceof Language.Message);
    }
    @Test public void actualCoinSelectionErrorKeepsOriginalExceptionAndRawAmounts()throws Exception {
        org.minima.objects.base.MiniNumber required=new org.minima.objects.base.MiniNumber("12.34000");
        CoinSelector.InsufficientFundsException error=assertThrows(CoinSelector.InsufficientFundsException.class,
                ()->CoinSelector.selectToCover(null,"0x00",required));
        String original=error.getMessage();CharSequence translated=WalletMessages.error(error);
        assertEquals(original,error.getMessage());assertEquals(resource("wallet.error.funds"),field(translated,"resource"));
        assertArrayEquals(new Object[]{"0",required.toString()},(Object[])field(translated,"arguments"));
    }
}
