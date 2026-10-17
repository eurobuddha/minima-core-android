package com.eurobuddha.atomix;
import com.eurobuddha.minimacore.i18n.Language;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class RecoveryMessagesTest {
 static JSONObject source()throws Exception{return new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../../localization/atomix-recovery-messages.json")),java.nio.charset.StandardCharsets.UTF_8));}
 @Test public void allFourCausesResolveWithCommandAndAge()throws Exception{
  JSONArray patterns=source().getJSONArray("patterns");assertEquals(6,patterns.length());
  for(int i=0;i<4;i++)for(String command:new String[]{"A node write","A 'txnpost' command"})for(String age:new String[]{""," about 12 min ago"}){
   JSONObject row=patterns.getJSONObject(i);Object value=RecoveryMessages.message(row.getString("template").replace("{0}",command+age));assertTrue(value instanceof Language.Message);assertEquals(AtomixMessagesTest.resource(row.getString("key")),AtomixMessagesTest.field(value,"resource"));Object what=((Object[])AtomixMessagesTest.field(value,"arguments"))[0];assertTrue(what instanceof Language.Message);
   if(!age.isEmpty()){assertEquals(AtomixMessagesTest.resource("atomixRecovery.ago"),AtomixMessagesTest.field(what,"resource"));Object[] args=(Object[])AtomixMessagesTest.field(what,"arguments");assertEquals("12",args[1]);what=args[0];}
   assertEquals(AtomixMessagesTest.resource(command.equals("A node write")?"atomixRecovery.write":"atomixRecovery.command"),AtomixMessagesTest.field(what,"resource"));
  }
 }
 @Test public void commandDataIsLiteralEvenWhenItMatchesKnownText()throws Exception{
  String raw="A node write 中文 Русский Українська $1 {0}";Object value=RecoveryMessages.message("A '"+raw+"' command");assertArrayEquals(new Object[]{raw},(Object[])AtomixMessagesTest.field(value,"arguments"));
 }
 @Test public void unknownOrAbsentDiagnosticsStayLiteral()throws Exception{
  assertEquals("",RecoveryMessages.message(null));String raw="Future cause 中文 $1 {0}";assertEquals(raw,RecoveryMessages.message(raw));
  Object value=RecoveryMessages.message("A node write lost its reply and may have completed.");assertEquals(AtomixMessagesTest.resource("atomixRecovery.unknown"),AtomixMessagesTest.field(value,"resource"));
 }
 @Test public void incompleteOutcomesDoNotClaimSuccessOrAssignBlame()throws Exception{
  JSONObject catalog=new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../../localization/catalog.json")),java.nio.charset.StandardCharsets.UTF_8));
  String callback=catalog.getJSONObject("atomixRecovery.callback").getString("en"),stopped=catalog.getJSONObject("atomixRecovery.stopped").getString("en");assertTrue(callback.contains("not confirmed"));assertFalse(callback.contains("most likely completed"));assertTrue(stopped.contains("may have completed"));assertFalse(stopped.contains("never at fault"));
 }
}
