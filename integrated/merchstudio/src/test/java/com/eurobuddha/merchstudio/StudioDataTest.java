package com.eurobuddha.merchstudio;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class StudioDataTest {
 static final String TOKEN="0x"+"ab".repeat(32),ADDRESS="0x"+"12".repeat(32);
 static JSONObject obj(Object... pairs){try{JSONObject j=new JSONObject();for(int i=0;i<pairs.length;i+=2)j.put((String)pairs[i],pairs[i+1]);return j;}catch(Exception e){throw new AssertionError(e);}}
 static JSONObject copy(JSONObject j){try{return new JSONObject(j.toString());}catch(Exception e){throw new AssertionError(e);}}
 static JSONObject coin(int id,String idx){return obj("coinid","0x"+String.format("%02x",id).repeat(32),"tokenid",TOKEN,"address",ADDRESS,"tokenamount","1.0000","state",obj("0",idx),"storestate",true,"spent",false);}
 static JSONObject balance(){return obj("tokenid",TOKEN,"token",obj("name","Send 中文 Русский Українська","decimals",0),"confirmed","2","unconfirmed","0","sendable","2","total","100");}
 static JSONObject reply(Object rows){return obj("status",true,"response",rows);}
 static void rejects(Runnable f){assertThrows(IllegalArgumentException.class,f::run);}
 @Test public void missingOrFailedReplyCannotBecomeEmptyWallet(){
  for(JSONObject bad:new JSONObject[]{null,obj(),obj("status",true),obj("status","true","response",new JSONArray()),obj("status",false,"response",new JSONArray()),reply(obj()),reply("[]")}){rejects(()->StudioData.balances(bad));rejects(()->StudioData.collection(bad,TOKEN,100));}
  assertEquals(0,StudioData.balances(reply(new JSONArray())).length());assertEquals(0,StudioData.collection(reply(new JSONArray()),TOKEN,100).length());
 }
 @Test public void entireBalanceListIsValidatedBeforeAnyCallerCanMerge()throws Exception{
  JSONObject good=balance();assertSame(good,StudioData.balances(reply(new JSONArray().put(good))).getJSONObject(0));
  for(Object bad:new Object[]{JSONObject.NULL,"bad",obj(),copy(good).put("tokenid","0xzz"),copy(good).put("sendable","1 send amount:1"),copy(good).put("confirmed",1),copy(good).put("token",false)})rejects(()->StudioData.balances(reply(new JSONArray().put(good).put(bad))));
  for(String key:new String[]{"tokenid","token","confirmed","unconfirmed","sendable","total"}){JSONObject bad=copy(good);bad.remove(key);rejects(()->StudioData.balances(reply(new JSONArray().put(bad))));}
  rejects(()->StudioData.balances(reply(new JSONArray().put(good).put(copy(good)))));
 }
 @Test public void collectionReusesExactIdentityAmountAndDuplicateCoinGates()throws Exception{
  JSONObject good=coin(1,"1");assertEquals(1,StudioData.collection(reply(new JSONArray().put(good)),TOKEN,100).length());
  for(Object bad:new Object[]{JSONObject.NULL,"bad",obj(),copy(good).put("tokenid","0x99"),copy(good).put("tokenamount","0"),copy(good).put("tokenamount",1)})rejects(()->StudioData.collection(reply(new JSONArray().put(good).put(bad)),TOKEN,100));
  for(String key:new String[]{"coinid","tokenid","address","tokenamount"}){JSONObject bad=copy(good);bad.remove(key);rejects(()->StudioData.collection(reply(new JSONArray().put(bad)),TOKEN,100));}
  rejects(()->StudioData.collection(reply(new JSONArray().put(good).put(copy(good))),TOKEN,100));
 }
 @Test public void hostileEditionNumbersCannotOverflowOrAliasTwoListings(){
  for(String idx:new String[]{"0","00","-1","1 burn:9","2147483648","9".repeat(100)})assertEquals(-1,StudioData.pieceIndex(coin(1,idx),100));
  assertEquals(-1,StudioData.pieceIndex(coin(1,"101"),100));assertEquals(7,StudioData.pieceIndex(coin(1,"0007"),100));
  rejects(()->StudioData.collection(reply(new JSONArray().put(coin(1,"7")).put(coin(2,"0007"))),TOKEN,100));
 }
 @Test public void malformedStateCannotSilentlyEraseKnownPieces()throws Exception{
  for(Object state:new Object[]{JSONObject.NULL,"invalid",new JSONArray().put("bad"),new JSONArray().put(obj("port",0,"data","1")).put(obj("port","00","data","2")),obj("0",true),obj("0","1","256","2")}){JSONObject bad=coin(1,"1").put("state",state);rejects(()->StudioData.collection(reply(new JSONArray().put(bad)),TOKEN,100));}
  JSONObject good=coin(1,"1").put("state",new JSONArray().put(obj("port",0,"data","1")));assertEquals(1,StudioData.collection(reply(new JSONArray().put(good)),TOKEN,100).length());
 }
 @Test public void buriedAndUnstampedCoinsDoNotConflictWithListableEdition()throws Exception{
  JSONArray rows=new JSONArray().put(coin(1,"7")).put(coin(2,"7").put("address",StateNft.GRAVEYARD)).put(coin(3,"0"));assertEquals(3,StudioData.collection(reply(rows),TOKEN,100).length());
 }

 @Test public void serializedNftBalanceMetadataResolvesNamesAndLargeIcons() throws Exception {
  String image="data:image/png;base64,"+"AAAA".repeat(32768);
  JSONObject metadata=obj("name","Shop NFT 中文 Українська","url",image,"ticker","NFT","decimals","0");
  for(Object raw:new Object[]{metadata,metadata.toString(),obj("name",metadata),obj("name",metadata).toString(),obj("name",metadata.toString()),obj("name",metadata.toString()).toString()}) {
   JSONObject input=balance().put("token",raw).put("decimals","0");String before=input.toString();
   JSONArray accepted=StudioData.balances(reply(new JSONArray().put(input)));
   TokenBalance token=TokenBalance.from(accepted.getJSONObject(0));
   assertEquals("Shop NFT 中文 Українська",token.name);assertEquals(token.name,Util.tokenName(raw,TOKEN));
   assertEquals(image,token.meta.iconUrl);assertTrue(token.hasIcon());
   assertEquals("NFT",token.meta.ticker);assertEquals("0",token.meta.decimals);
   assertEquals("2",token.sendable);assertEquals("100",token.total);assertEquals(before,input.toString());
  }
 }
 @Test public void serializedShopCollectionsResolveCoversAndPieceArt() throws Exception {
  JSONObject details=obj("name","Collection 中文 Українська","size",12,"mode","url",
    "base","https://example.invalid/nft/","ext",".png","url","https://example.invalid/cover.png");
  for(Object metadata:new Object[]{details,details.toString()}) {
   JSONObject token=obj("name",metadata,"totalamount","12");
   for(Object value:new Object[]{token,token.toString()}) {
    JSONObject envelope=obj("token",value,"script","RETURN TRUE");String before=envelope.toString();
    for(Object record:new Object[]{envelope,envelope.toString(),value}) {
     StateNft.Meta meta=StateNft.parseMeta(TOKEN,record);
     assertEquals("Collection 中文 Українська",meta.name);assertEquals(12,meta.size);
     assertEquals("url",meta.mode);assertTrue(StateNft.isCandidate(meta));
     assertEquals("https://example.invalid/cover.png",meta.icon);
     assertEquals("https://example.invalid/nft/3.png",StateNft.imageUrl(meta,3,null));
     TokenMeta cover=TokenMeta.parse(record,TOKEN);assertEquals(meta.name,cover.name);assertEquals(meta.icon,cover.iconUrl);
    }
    assertEquals(before,envelope.toString());
   }
  }
 }
 @Test public void missingNamesNeverExposeStructuredMetadataAsShopLabels() {
  JSONObject details=obj("url","https://example.invalid/cover.png","size",12);
  for(Object metadata:new Object[]{details,details.toString()}) {
   JSONObject token=obj("name",metadata);
   assertEquals("Collection",StateNft.parseMeta(TOKEN,token).name);
   assertEquals("Token",TokenMeta.parse(token,TOKEN).name);
   assertEquals("Token",Util.tokenName(token,TOKEN));
  }
  for(String name:new String[]{"Token","Send","[1,2]","{unfinished","中文 Русский Українська"})
   assertEquals(name,TokenMeta.parse(name,TOKEN).name);
 }

 @Test public void unstampedAndUnsupportedStateDoNotHideTheEntireCollection() throws Exception {
  for(Object state:new Object[]{new JSONArray(),obj(),obj("1","[AAAA]"),obj("0","\"1\""),obj("0","1","2","creator note")}) {
   JSONObject other=coin(2,"1").put("state",state),good=coin(1,"7");
   JSONArray rows=new JSONArray().put(good).put(other);String before=rows.toString();
   assertSame(rows,StudioData.collection(reply(rows),TOKEN,100));
   assertEquals(before,rows.toString());
   assertEquals(7,StudioData.pieceIndex(good,100));assertTrue(StateNft.replayableState(good));
   assertTrue(StateNft.stamped(other)==null || !StateNft.replayableState(other));
  }
 }
}
