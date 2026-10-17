package com.eurobuddha.atomix;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class ReportMessagesTest {
 static JSONObject source()throws Exception{return new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../../localization/atomix-report-messages.json")),java.nio.charset.StandardCharsets.UTF_8));}
 @Test public void fixedReportStatesResolveToDeclaredMessages()throws Exception{
  JSONObject fixed=source().getJSONObject("fixed");assertEquals(28,fixed.length());for(Iterator<String>it=fixed.keys();it.hasNext();){String raw=it.next();Object v=ReportMessages.message(raw);assertEquals(AtomixMessagesTest.resource(fixed.getString(raw)),AtomixMessagesTest.field(v,"resource"));}
 }
 @Test public void allTemplatesKeepLiteralEvidenceArguments()throws Exception{
  JSONArray patterns=source().getJSONArray("patterns");assertEquals(17,patterns.length());for(int i=0;i<patterns.length();i++){JSONObject row=patterns.getJSONObject(i);String raw=row.getString("template");Object[]args=new Object[row.getJSONArray("kinds").length()];for(int j=0;j<args.length;j++){args[j]="data"+j+"中文РусскийУкраїнська$1{x}";raw=raw.replace("{"+j+"}",(String)args[j]);}Object v=ReportMessages.message(raw);assertEquals(raw,AtomixMessagesTest.resource(row.getString("key")),AtomixMessagesTest.field(v,"resource"));assertArrayEquals(raw,args,(Object[])AtomixMessagesTest.field(v,"arguments"));}
 }
 @Test public void nestedLegStatesTranslateWhileAmountsAndSymbolsStayExact()throws Exception{
  Object value=ReportMessages.message("Sell 00012.34000 MINIMA → 0001.2000 USDT  ·  started");Object[]args=(Object[])AtomixMessagesTest.field(value,"arguments");assertEquals("00012.34000",args[0]);assertEquals("MINIMA",args[1]);assertEquals("0001.2000",args[2]);assertEquals("USDT",args[3]);assertEquals(AtomixMessagesTest.resource("atomixHistory.waiting"),AtomixMessagesTest.field(args[4],"resource"));
  value=ReportMessages.message("• Counterparty USDT leg: FOUND 0012.34000 USDT — locked; waiting for the secret");args=(Object[])AtomixMessagesTest.field(value,"arguments");assertEquals("USDT",args[0]);Object[]found=(Object[])AtomixMessagesTest.field(args[1],"arguments");assertEquals("0012.34000",found[0]);assertEquals("USDT",found[1]);assertEquals(AtomixMessagesTest.resource("atomixReport.lockedWaiting"),AtomixMessagesTest.field(found[2],"resource"));
  value=ReportMessages.message("• Your 1 MINIMA: LOCKED — refundable at block 001234 (~56 min)");Object inner=((Object[])AtomixMessagesTest.field(value,"arguments"))[2];assertArrayEquals(new Object[]{"001234","56"},(Object[])AtomixMessagesTest.field(inner,"arguments"));
 }
 @Test public void idsEventsWarningsAndDiagnosticTextAreNeverTranslated()throws Exception{
  String raw="0x"+"12".repeat(64)+" 中文 $1 {0}";Object[]args=(Object[])AtomixMessagesTest.field(ReportMessages.message("Recorded complete transaction: "+raw),"arguments");assertArrayEquals(new Object[]{"complete",raw},args);
  assertEquals("⚠ "+raw,ReportMessages.message("⚠ "+raw));assertEquals(raw,ReportMessages.message(raw));assertEquals("",ReportMessages.message(null));
  args=(Object[])AtomixMessagesTest.field(ReportMessages.message("Check failed (RPC/node): No record of this swap."),"arguments");assertEquals("No record of this swap.",args[0]);assertTrue(args[0] instanceof String);
 }
}
