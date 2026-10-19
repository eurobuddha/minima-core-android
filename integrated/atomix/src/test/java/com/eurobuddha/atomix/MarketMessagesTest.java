package com.eurobuddha.atomix;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
/** Uses the existing literal-capture tests against market/oracle display boundaries. */
public class MarketMessagesTest {
 static JSONObject source()throws Exception{return new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../../localization/atomix-market-messages.json")),java.nio.charset.StandardCharsets.UTF_8));}
 @Test public void fixedStatusesResolveWithoutChangingStoredStrings()throws Exception{
  JSONObject fixed=source().getJSONObject("fixed");assertEquals(8,fixed.length());
  for(Iterator<String>it=fixed.keys();it.hasNext();){String raw=it.next();Object value=MarketMessages.message(raw);assertTrue(value instanceof Language.Message);assertEquals(AtomixMessagesTest.resource(fixed.getString(raw)),AtomixMessagesTest.field(value,"resource"));}
 }
 @Test public void allTemplatesPreservePublicAndDiagnosticArguments()throws Exception{
  JSONArray patterns=source().getJSONArray("patterns");assertEquals(19,patterns.length());
  for(int i=0;i<patterns.length();i++){JSONObject row=patterns.getJSONObject(i);JSONArray kinds=row.getJSONArray("kinds");String raw=row.getString("template");Object[] args=new Object[kinds.length()];for(int j=0;j<args.length;j++){args[j]="integer".equals(kinds.getString(j))?"000123":"Data 中文 Русский Українська $1 {0}";raw=raw.replace("{"+j+"}",(String)args[j]);}Object value=MarketMessages.message(raw);assertTrue(raw,value instanceof Language.Message);assertEquals(raw,AtomixMessagesTest.resource(row.getString("key")),AtomixMessagesTest.field(value,"resource"));assertArrayEquals(raw,args,(Object[])AtomixMessagesTest.field(value,"arguments"));}
 }
 @Test public void oracleVariantsKeepScientificPricesStalenessAndLiteralErrors()throws Exception{
  String[][] cases={
   {"MEXC mid 1.2E-7  (bid 1E-7 / ask 1.4E-7)  ·  720s ago  — STALE · defensive spread ×2.3","oracleBookStale"},
   {"Parity mid 1  ·  -1s ago","oracle"},
   {"MEXC: unavailable (remote (timeout) 中文 {0})","oracleUnavailable"}};
  for(String[] row:cases){Object v=MarketMessages.message(row[0]);assertEquals(AtomixMessagesTest.resource("atomixMarket."+row[1]),AtomixMessagesTest.field(v,"resource"));}
  Object[] args=(Object[])AtomixMessagesTest.field(MarketMessages.message(cases[0][0]),"arguments");assertArrayEquals(new Object[]{"MEXC","1.2E-7","1E-7","1.4E-7","720","2.3"},args);
  args=(Object[])AtomixMessagesTest.field(MarketMessages.message(cases[2][0]),"arguments");assertEquals("remote (timeout) 中文 {0}",args[1]);
 }
 @Test public void unknownTextAndNestedDiagnosticsStayLiteral()throws Exception{
  for(String raw:new String[]{"0x"+"ab".repeat(64),"Custom 中文 Русский Українська {0}","MEXC mid 1  ·  NaNs ago"})assertEquals(raw,MarketMessages.message(raw));
  Object[] args=(Object[])AtomixMessagesTest.field(MarketMessages.message("Publish failed: Updating your order…"),"arguments");assertEquals("Updating your order…",args[0]);assertTrue(args[0] instanceof String);
 }
}
