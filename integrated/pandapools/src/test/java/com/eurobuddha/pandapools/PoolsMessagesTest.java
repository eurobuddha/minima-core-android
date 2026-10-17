package com.eurobuddha.pandapools;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.Field;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class PoolsMessagesTest {
 @Test public void declaredApplicationLabelsResolveToTheirOwnResources()throws Exception {
  JSONObject spec=new JSONObject(new String(java.nio.file.Files.readAllBytes(Path.of("../../localization/pools-messages.json")),java.nio.charset.StandardCharsets.UTF_8)).getJSONObject("fixed");assertEquals(353,spec.length());Field f=Language.Message.class.getDeclaredField("resource");f.setAccessible(true);
  for(Iterator<String>i=spec.keys();i.hasNext();){String raw=i.next();Object result=PoolsMessages.message(raw);assertTrue(raw,result instanceof Language.Message);String id="l10n_"+spec.getString(raw).replaceAll("([a-z0-9])([A-Z])","$1_$2").replace('.','_').toLowerCase(Locale.ROOT);assertEquals(raw,R.string.class.getField(id).getInt(null),f.get(result));}
 }
 @Test public void unknownIdentifiersAndDiagnosticsRemainLiteral(){
  for(String value:new String[]{"0x"+"ab".repeat(64),"Token 中文 Русский Українська $1 {count}","node error 00012.34000","Copied arbitrary user data"})assertEquals(value,PoolsMessages.message(value));assertEquals("",PoolsMessages.message(null));
 }
}
