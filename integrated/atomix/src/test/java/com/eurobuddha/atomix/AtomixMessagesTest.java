package com.eurobuddha.atomix;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Reuses the FutureMessages/Parlons fixed lookup, literal-capture and bounded-recursion regressions. */
public class AtomixMessagesTest {
 static Object field(Object value,String name)throws Exception{Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);}
 static int resource(String key)throws Exception{return R.string.class.getField("l10n_"+key.replaceAll("([a-z0-9])([A-Z])","$1_$2").replace('.','_').toLowerCase(Locale.ROOT)).getInt(null);}
 static JSONObject source()throws Exception{return new JSONObject(new String(Files.readAllBytes(Path.of("../../localization/atomix-messages.json")), java.nio.charset.StandardCharsets.UTF_8));}
 @Test public void everyDeclaredFixedMessageResolves()throws Exception{
  JSONObject fixed=source().getJSONObject("fixed");assertEquals(56,fixed.length());
  for(Iterator<String>it=fixed.keys();it.hasNext();){String raw=it.next();Object value=AtomixMessages.message(raw);assertTrue(raw,value instanceof Language.Message);assertEquals(resource(fixed.getString(raw)),field(value,"resource"));assertEquals(0,((Object[])field(value,"arguments")).length);}
 }
 @Test public void everyTemplatePreservesLiteralCaptures()throws Exception{
  JSONArray patterns=source().getJSONArray("patterns");assertEquals(24,patterns.length());Map<String,String> literals=new HashMap<>();literals.put("address","0x0123456789abcdef0123456789abcdef01234567");literals.put("number","00012.34000");literals.put("integer","000123");literals.put("hex","0xAbCd1234");literals.put("command","txnpost");literals.put("literal","Copy 中文 $1 {0} <img>");literals.put("diagnostic","Copy 中文 $1 {0} <img>");
  for(int i=0;i<patterns.length();i++){JSONObject row=patterns.getJSONObject(i);JSONArray kinds=row.getJSONArray("kinds");String raw=row.getString("template");Object[] args=new Object[kinds.length()];for(int j=0;j<args.length;j++){args[j]=literals.get(kinds.getString(j));assertNotNull(args[j]);raw=raw.replace("{"+j+"}",(String)args[j]);}Object value=AtomixMessages.message(raw);assertTrue(raw,value instanceof Language.Message);assertEquals(raw,resource(row.getString("key")),field(value,"resource"));assertArrayEquals(raw,args,(Object[])field(value,"arguments"));}
 }
 @Test public void publicDataAndUnknownDiagnosticsRemainLiteral()throws Exception{
  for(String value:new String[]{"0x"+"ab".repeat(20),"Send 中文 Русский Українська {amount}","This swap needs about NaN more ETH for gas. Send ETH on Ethereum to 0x1234"})assertEquals(value,AtomixMessages.message(value));
  Object result=AtomixMessages.message("Reclaimed 00012.34000 mxUSDT — confirmed on-chain (the counterparty never locked their side before the timeout)");Object[] args=(Object[])field(result,"arguments");assertEquals("00012.34000",args[0]);assertEquals("mxUSDT",args[1]);assertEquals(resource("atomixNotice.refundUnlocked"),field(args[2],"resource"));
 }
 @Test public void mismatchMessageKeepsSharedStorageWarning(){
  com.eurobuddha.atomix.IdentityWatch.resetForTest();final String[] message=new String[2];com.eurobuddha.atomix.IdentityWatch.noteMinimaOrphaned("0xABCD",new com.eurobuddha.atomix.swap.SwapEngine.Notifier(){public void notify(String title,String body){message[0]=title;message[1]=body;}public void onSwapsChanged(){}});
  assertTrue(com.eurobuddha.atomix.IdentityWatch.halted());assertTrue(message[1].contains("Do not clear Pandamonium storage"));assertFalse(message[1].contains("reinstall"));assertTrue(AtomixMessages.message(message[1]) instanceof Language.Message);com.eurobuddha.atomix.IdentityWatch.resetForTest();
 }
}
