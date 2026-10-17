package com.eurobuddha.casino;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Reuses the FutureMessages/Parlons fixed lookup, literal-capture and bounded-recursion regressions. */
public class CasinoMessagesTest {
 static Object field(Object value,String name)throws Exception{Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);}
 static int resource(String key)throws Exception{return R.string.class.getField("l10n_"+key.replaceAll("([a-z0-9])([A-Z])","$1_$2").replace('.','_').toLowerCase(Locale.ROOT)).getInt(null);}
 static JSONObject source()throws Exception{return new JSONObject(new String(Files.readAllBytes(Path.of("../../localization/casino-messages.json")), java.nio.charset.StandardCharsets.UTF_8));}
 @Test public void everyDeclaredFixedMessageResolves()throws Exception{
  JSONObject fixed=source().getJSONObject("fixed");assertEquals(92,fixed.length());
  for(Iterator<String>it=fixed.keys();it.hasNext();){String raw=it.next();Object value=CasinoMessages.message(raw);assertTrue(raw,value instanceof Language.Message);assertEquals(resource(fixed.getString(raw)),field(value,"resource"));assertEquals(0,((Object[])field(value,"arguments")).length);}
 }
 @Test public void everyTemplatePreservesLiteralCaptures()throws Exception{
  JSONArray patterns=source().getJSONArray("patterns");assertEquals(32,patterns.length());Map<String,String> literals=new HashMap<>();literals.put("number","00012.34000");literals.put("integer","000123");literals.put("hex","0xAbCd1234");literals.put("command","txnpost");literals.put("literal","Copy 中文 $1 {0} <img>");literals.put("diagnostic","Copy 中文 $1 {0} <img>");
  for(int i=0;i<patterns.length();i++){JSONObject row=patterns.getJSONObject(i);JSONArray kinds=row.getJSONArray("kinds");String raw=row.getString("template");Object[] args=new Object[kinds.length()];for(int j=0;j<args.length;j++){args[j]=literals.get(kinds.getString(j));assertNotNull(args[j]);raw=raw.replace("{"+j+"}",(String)args[j]);}Object value=CasinoMessages.message(raw);assertTrue(raw,value instanceof Language.Message);assertEquals(raw,resource(row.getString("key")),field(value,"resource"));assertArrayEquals(raw,args,(Object[])field(value,"arguments"));}
 }
 @Test public void unknownCommandsMalformedNumbersAndContentStayLiteral(){
  for(String raw:new String[]{"Zero Edge Casino","Copy 中文 $1 {0}","seed failed : Hash failed","Insufficient USD (need NaN)","Connected · not-an-address","Created Dice bet (12 · NaN:1)","You won +1e9 USD!"})assertEquals(raw,CasinoMessages.message(raw));assertEquals("",CasinoMessages.message((String)null));
 }
 @Test public void diagnosticRecursionIsBoundedAndNodeErrorsRemainRaw()throws Exception{
  String prefix="Create failed: ";Object value=CasinoMessages.message(prefix.repeat(12)+"Hash failed");for(int i=0;i<8;i++)value=((Object[])field(value,"arguments"))[0];assertEquals(prefix.repeat(4)+"Hash failed",value);
  value=CasinoMessages.message("Take failed: Hash failed");assertEquals(resource("casino.hashFailed"),field(((Object[])field(value,"arguments"))[0],"resource"));
  value=CasinoMessages.message("Create failed: Send failed : Hash failed");Object nested=((Object[])field(value,"arguments"))[0];assertEquals(resource("casino.sendError"),field(nested,"resource"));assertEquals("Hash failed",((Object[])field(nested,"arguments"))[0]);
  for(String command:new String[]{"txncreate","txninput","txnoutput","txnstate","txnsign","txnbasics","txnpost","txndelete"}){value=CasinoMessages.message(command+" failed : Hash failed");assertEquals(resource("casino.commandFailedDetail"),field(value,"resource"));assertArrayEquals(new Object[]{command,"Hash failed"},(Object[])field(value,"arguments"));}
 }
 @Test public void pendingWrappersKeepAmountsOddsHeightsAndGameDescriptorsSeparate()throws Exception{
  Object value=CasinoMessages.message("Created Dice bet (00012.34000 USD · 5:1) — confirming, 0002 blocks elapsed (#000123)");assertEquals(resource("casino.pendingProgress"),field(value,"resource"));Object[] args=(Object[])field(value,"arguments");assertEquals("0002",args[1]);assertEquals("000123",args[2]);Object[] created=(Object[])field(args[0],"arguments");assertEquals(resource("casino.dice"),field(created[0],"resource"));assertEquals("00012.34000 USD",created[1]);assertEquals("5",created[2]);
 }
 @Test public void logCompositionPreservesTimestampAndUnknownDiagnosticBytes()throws Exception{
  Object value=CasinoMessages.logLine("12:34:56  Create failed: Hash failed");Object[] args=(Object[])field(value,"arguments");assertEquals("12:34:56  ",args[0]);assertEquals(resource("casino.createFailed"),field(args[1],"resource"));
  value=CasinoMessages.logLine("12:34:56  0xAbCd Україна 中文 $1 {0}\nnode detail");assertArrayEquals(new Object[]{"12:34:56  ","0xAbCd Україна 中文 $1 {0}\nnode detail"},(Object[])field(value,"arguments"));assertEquals("unstructured log",CasinoMessages.logLine("unstructured log"));
 }
}
