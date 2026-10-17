package com.eurobuddha.merchstudio;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;
public class ShopExportTest {
 @Rule public TemporaryFolder tmp=new TemporaryFolder();
 static JSONObject shop()throws Exception{return new JSONObject().put("shopName","Public export 中文 Українська").put("products",new JSONArray().put(new JSONObject().put("price","0001.2000").put("maxUnits",3)));}
 static ShopExport.Image image(){return new ShopExport.Image("products",0,"image","https://example.invalid/original","public-id",900,512,90000);}
 @Test public void snapshotOwnsNestedValuesAndImageDescriptors()throws Exception{
  JSONObject source=shop();List<ShopExport.Image> images=new ArrayList<>();images.add(image());ShopExport export=new ShopExport(source,"Public-export",images);
  source.put("shopName","changed");source.getJSONArray("products").getJSONObject(0).put("price","9000");images.clear();
  JSONObject result=export.render(i->{assertEquals("https://example.invalid/original",i.url);return "original image";});
  assertEquals("Public export 中文 Українська",export.name);assertEquals(export.name,result.getString("shopName"));assertEquals("0001.2000",result.getJSONArray("products").getJSONObject(0).getString("price"));assertEquals("original image",result.getJSONArray("products").getJSONObject(0).getString("image"));
  result.getJSONArray("products").getJSONObject(0).put("price","changed result");assertEquals("0001.2000",export.render(i->"").getJSONArray("products").getJSONObject(0).getString("price"));
 }
 @Test public void sameShopNameNeverOverwritesAlreadySharedFile()throws Exception{
  File cache=tmp.newFolder();ShopExport first=new ShopExport(shop(),"Public-export",Collections.emptyList());File a=first.write(cache,i->"");byte[] before=Files.readAllBytes(a.toPath());
  File b=new ShopExport(shop().put("shopName","Second export"),"Public-export",Collections.emptyList()).write(cache,i->"");
  assertNotEquals(a,b);assertEquals("Public-export.shop",a.getName());assertEquals(a.getName(),b.getName());assertArrayEquals(before,Files.readAllBytes(a.toPath()));assertEquals("Second export",new JSONObject(new String(Files.readAllBytes(b.toPath()),java.nio.charset.StandardCharsets.UTF_8)).getString("shopName"));assertArrayEquals(new String[]{"Public-export.shop"},b.getParentFile().list());
 }
 @Test public void imageFailureCannotDamagePreviousExportOrPublishPartialFile()throws Exception{
  File cache=tmp.newFolder();File kept=new ShopExport(shop(),"Public-export",Collections.emptyList()).write(cache,i->"");byte[] before=Files.readAllBytes(kept.toPath());
  ShopExport failing=new ShopExport(shop(),"Public-export",Collections.singletonList(image()));assertThrows(IOException.class,()->failing.write(cache,i->{throw new IOException("unreadable image");}));
  assertArrayEquals(before,Files.readAllBytes(kept.toPath()));assertEquals(1,new File(cache,"shops").list().length);
 }
 @Test public void countContractRejectsOverflowAndNonPositiveValues(){
  assertEquals(1,ShopExport.count(BigInteger.ONE));assertEquals(Integer.MAX_VALUE,ShopExport.count(BigInteger.valueOf(Integer.MAX_VALUE)));
  for(String n:new String[]{"0","-1","2147483648","4294967297","999999999999999999999999999999"})assertThrows(ArithmeticException.class,()->ShopExport.count(new BigInteger(n)));
 }
 @Test public void namesCannotEscapePrivateExportDirectory()throws Exception{
  for(String slug:new String[]{"../outside","/tmp/shop","a/b","a\\b","..","","a.shop"})assertThrows(IllegalArgumentException.class,()->new ShopExport(shop(),slug,Collections.emptyList()));
 }
 @Test public void numericCollectionMetadataCannotWrapIntoValidSupply()throws Exception{
  String token="0x"+"ab".repeat(32);
  for(Object bad:new Object[]{4294967297L,2147483648L,-1,0,1.5,"4294967297","bad",true}){
   JSONObject metadata=new JSONObject().put("size",bad);JSONObject record=new JSONObject().put("name",metadata).put("totalamount","100");
   assertEquals(0,StateNft.parseMeta(token,record).size);
  }
  for(Object good:new Object[]{7,"7","7.000",Integer.MAX_VALUE}){
   JSONObject record=new JSONObject().put("name",new JSONObject().put("size",good));
   assertEquals(new java.math.BigDecimal(good.toString()).intValueExact(),StateNft.parseMeta(token,record).size);
  }
 }
}
