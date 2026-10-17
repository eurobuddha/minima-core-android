package com.eurobuddha.atomix;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
/** Reuses the market mapper's literal-capture contract for operation status displays. */
public class OperationMessagesTest {
 static JSONObject source()throws Exception{return new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../../localization/atomix-operation-messages.json")),java.nio.charset.StandardCharsets.UTF_8));}
 @Test public void fixedStatusesUseDeclaredResources()throws Exception{
  JSONObject fixed=source().getJSONObject("fixed");assertEquals(11,fixed.length());
  for(Iterator<String>it=fixed.keys();it.hasNext();){String raw=it.next();Object value=OperationMessages.message(raw);assertTrue(value instanceof Language.Message);assertEquals(AtomixMessagesTest.resource(fixed.getString(raw)),AtomixMessagesTest.field(value,"resource"));}
 }
 @Test public void everyTemplateKeepsLiteralArguments()throws Exception{
  JSONArray patterns=source().getJSONArray("patterns");assertEquals(18,patterns.length());
  for(int i=0;i<patterns.length();i++){JSONObject row=patterns.getJSONObject(i);String raw=row.getString("template");Object[] args=new Object[row.getJSONArray("kinds").length()];for(int j=0;j<args.length;j++){args[j]="000123 中文 Русский Українська $1 {x}";raw=raw.replace("{"+j+"}",(String)args[j]);}Object value=OperationMessages.message(raw);assertTrue(raw,value instanceof Language.Message);assertEquals(raw,AtomixMessagesTest.resource(row.getString("key")),AtomixMessagesTest.field(value,"resource"));assertArrayEquals(raw,args,(Object[])AtomixMessagesTest.field(value,"arguments"));}
 }
 @Test public void existingMarketMessagesStillResolve()throws Exception{
  for(String raw:new String[]{"Updating your order…","Publish failed: Node 中文 {0}"}){Object a=MarketMessages.message(raw),b=OperationMessages.message(raw);assertTrue(a instanceof Language.Message);assertEquals(AtomixMessagesTest.field(a,"resource"),AtomixMessagesTest.field(b,"resource"));assertArrayEquals((Object[])AtomixMessagesTest.field(a,"arguments"),(Object[])AtomixMessagesTest.field(b,"arguments"));}
 }
 @Test public void rawDiagnosticsAndNestedKnownTextAreNeverReinterpreted()throws Exception{
  assertEquals("",OperationMessages.message(null));
  String raw="0x"+"ab".repeat(64)+" 中文 $1 {0}\nRaw JSON";assertEquals(raw,OperationMessages.message(raw));
  Object[] args=(Object[])AtomixMessagesTest.field(OperationMessages.message("Swap failed: Starting swap…"),"arguments");assertEquals("Starting swap…",args[0]);assertTrue(args[0] instanceof String);
  args=(Object[])AtomixMessagesTest.field(OperationMessages.message("Submission could not be confirmed: "+raw+"\nCheck your balance before retrying."),"arguments");assertEquals(raw,args[0]);
 }
}
