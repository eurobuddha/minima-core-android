package com.eurobuddha.salon;

import java.util.*;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Exercise the existing proof protocol with fake node replies; never sign or contact a node. */
public class NftProofTest {
    private static final String TOKEN="0x"+"ab".repeat(32),COIN="0x"+"cd".repeat(32),ADDR="0x"+"ef".repeat(32),KEY="0x"+"12".repeat(32),BIND=NftProof.hexOf("0x"+"34".repeat(32));
    private NodeApi node;private final List<String> commands=new ArrayList<>();private final List<NodeApi.Cb> pending=new ArrayList<>();
    @Before public void setup(){node=mock(NodeApi.class);doAnswer(c->{commands.add(c.getArgument(0));pending.add(c.getArgument(1));return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));}
    private JSONObject ok(Object response)throws Exception{return new JSONObject().put("status",true).put("response",response);}
    private JSONObject coin()throws Exception{return new JSONObject().put("coinid",COIN).put("tokenid",TOKEN).put("address",ADDR).put("spent",false).put("amount","1");}
    private JSONObject item()throws Exception{return new JSONObject().put("tokenid",TOKEN).put("coinid",COIN).put("coinproof","0xaabb").put("script","RETURN SIGNEDBY("+KEY+")").put("publickey",KEY).put("sig","0xccdd");}
    private JSONObject checked(JSONObject c)throws Exception{return ok(new JSONObject().put("coin",c).put("valid",true));}
    private JSONObject script()throws Exception{return ok(new JSONObject().put("parseok",true).put("clean",new JSONObject().put("script","RETURN SIGNEDBY("+KEY+")").put("address",ADDR)));}
    private void reply(JSONObject response){pending.remove(0).onResult(response);}
    private void resetCalls(){commands.clear();pending.clear();}
    @Test public void untrustedProofFieldsNeverReachCommands()throws Exception{
        for(String field:new String[]{"tokenid","coinid","coinproof","publickey","sig"})for(Object value:new Object[]{"0xaa; status","0xaa burn:100","0xaa\nstatus","0xa","0x",JSONObject.NULL,123}){
            resetCalls();NftProof.Verify cb=mock(NftProof.Verify.class);NftProof.verify(node,item().put(field,value),BIND,cb);assertTrue(field+"="+value,commands.isEmpty());verify(cb).result(eq(false),anyString(),eq(""));
        }
        for(String binding:new String[]{"0xaa;status","0x",NftProof.hexOf("not an identity")}){NftProof.verify(node,item(),binding,mock(NftProof.Verify.class));assertTrue(commands.isEmpty());}
        NftProof.verify(node,null,BIND,mock(NftProof.Verify.class));assertTrue(commands.isEmpty());
    }
    @Test public void scriptMustEstablishPlainWalletOwnership()throws Exception{
        for(String script:new String[]{"LET x="+KEY+" RETURN TRUE","RETURN TRUE /* "+KEY+" */","RETURN SIGNEDBY("+KEY+") OR TRUE","RETURN SIGNEDBY("+KEY+")\";status;\"","RETURN SIGNEDBY(0xab)","RETURN\nSIGNEDBY("+KEY+")"}){
            NftProof.Verify cb=mock(NftProof.Verify.class);NftProof.verify(node,item().put("script",script),BIND,cb);verify(cb).result(false,"key doesn't control the coin","");assertTrue(commands.isEmpty());
        }
    }
    @Test public void validProofBindsCoinAddressAndProfileBeforeShowingSuccess()throws Exception{
        JSONObject source=item().put("script"," return  signedby ( "+KEY+" ) ");String before=source.toString();NftProof.Verify cb=mock(NftProof.Verify.class);NftProof.verify(node,source,BIND,cb);
        assertEquals("coincheck data:0xaabb",commands.get(0));reply(checked(coin()));assertEquals("runscript script:\"RETURN SIGNEDBY("+KEY+")\"",commands.get(1));reply(script());assertEquals("verify data:"+BIND+" publickey:"+KEY+" signature:0xccdd",commands.get(2));reply(ok("Signature valid"));verify(cb).result(true,"verified holding","");assertEquals(before,source.toString());assertEquals(3,commands.size());
    }
    @Test public void failedOrMalformedCoinRepliesCannotProceed()throws Exception{
        List<JSONObject> replies=new ArrayList<>();replies.add(null);replies.add(new JSONObject());replies.add(checked(coin()).put("status",false));replies.add(checked(coin()).put("status","true"));
        for(String field:new String[]{"coinid","tokenid","address","spent","amount"}){JSONObject c=coin();c.remove(field);replies.add(checked(c));}
        replies.add(checked(coin().put("coinid","0xaaaa")));replies.add(checked(coin().put("tokenid","0xaaaa")));replies.add(checked(coin().put("spent",true)));replies.add(checked(coin().put("spent","false")));replies.add(checked(coin().put("amount","0")));replies.add(checked(coin().put("amount","NaN")));replies.add(ok(new JSONObject().put("coin",coin()).put("valid","true")));replies.add(ok(new JSONObject().put("coin",coin()).put("valid",false)));
        for(JSONObject response:replies){resetCalls();NftProof.Verify cb=mock(NftProof.Verify.class);NftProof.verify(node,item(),BIND,cb);reply(response);assertEquals(String.valueOf(response),1,commands.size());verify(cb).result(eq(false),anyString(),eq(""));}
    }
    @Test public void scriptAndSignatureRepliesMustBeCompleteAndSuccessful()throws Exception{
        for(JSONObject response:Arrays.asList(null,new JSONObject(),script().put("status",false),ok(new JSONObject().put("parseok",false)),ok(new JSONObject().put("parseok",true).put("clean",new JSONObject().put("script","RETURN TRUE").put("address",ADDR))))){
            resetCalls();NftProof.Verify cb=mock(NftProof.Verify.class);NftProof.verify(node,item(),BIND,cb);reply(checked(coin()));reply(response);assertEquals(2,commands.size());verify(cb).result(eq(false),anyString(),eq(""));
        }
        for(JSONObject response:Arrays.asList(null,new JSONObject(),ok("Signature valid").put("status",false),ok("Signature valid").put("status","true"),ok("anything"),ok(false))){
            resetCalls();NftProof.Verify cb=mock(NftProof.Verify.class);NftProof.verify(node,item(),BIND,cb);reply(checked(coin()));reply(script());reply(response);verify(cb).result(false,"signature not valid for this profile","");
        }
    }
    @Test public void liveHoldingRequiresSuccessfulMatchingPositiveUnspentCoin()throws Exception{
        for(JSONObject response:Arrays.asList(null,new JSONObject(),ok(new JSONArray()),ok(new JSONArray().put(JSONObject.NULL)),ok(new JSONArray().put(coin())).put("status",false),ok(new JSONArray().put(coin().put("tokenid","0xaaaa"))),ok(new JSONArray().put(coin().put("spent",true))),ok(new JSONArray().put(coin().put("amount","0"))))){
            resetCalls();NftProof.Held cb=mock(NftProof.Held.class);NftProof.holds(node,TOKEN,cb);reply(response);verify(cb).result(false,null);
        }
        resetCalls();NftProof.Held cb=mock(NftProof.Held.class);JSONObject c=coin();NftProof.holds(node,TOKEN,cb);assertEquals("coins relevant:true sendable:true tokenid:"+TOKEN,commands.get(0));reply(ok(new JSONArray().put(c)));verify(cb).result(true,c);resetCalls();NftProof.holds(node,TOKEN+";status",cb);assertTrue(commands.isEmpty());
    }
    @Test public void generationKeepsProtocolAndSignsOnlyValidatedWalletCoin()throws Exception{
        NftProof.Gen cb=mock(NftProof.Gen.class);NftProof.generate(node,TOKEN,BIND,cb);reply(ok(new JSONArray().put(coin())));assertEquals("coinexport coinid:"+COIN,commands.get(1));reply(ok(new JSONObject().put("data","0xaabb")));assertEquals("scripts address:"+ADDR,commands.get(2));reply(ok(new JSONObject().put("script","RETURN SIGNEDBY("+KEY+")").put("publickey",KEY).put("address",ADDR).put("simple",true)));assertEquals("sign publickey:"+KEY+" data:"+BIND,commands.get(3));reply(ok("0xccdd"));org.mockito.ArgumentCaptor<JSONObject> result=org.mockito.ArgumentCaptor.forClass(JSONObject.class);verify(cb).ok(result.capture());assertEquals(item().toString(),result.getValue().toString());verify(cb,never()).fail(anyString());
    }
    @Test public void generationStopsBeforeSigningWhenInputsOrRepliesAreInvalid()throws Exception{
        NftProof.Gen cb=mock(NftProof.Gen.class);NftProof.generate(node,TOKEN+";status",BIND,cb);NftProof.generate(node,TOKEN,"0xaabb",cb);assertTrue(commands.isEmpty());
        for(JSONObject response:Arrays.asList(null,new JSONObject(),ok(new JSONArray().put(JSONObject.NULL)),ok(new JSONArray().put(coin())).put("status",false),ok(new JSONArray().put(coin().put("coinid",COIN+";status"))))){resetCalls();cb=mock(NftProof.Gen.class);NftProof.generate(node,TOKEN,BIND,cb);reply(response);assertEquals(1,commands.size());verify(cb).fail(anyString());}
        for(JSONObject response:Arrays.asList(null,ok(new JSONObject().put("data","0xaa;status")),ok(new JSONObject().put("data","0xaabb")).put("status",false))){resetCalls();cb=mock(NftProof.Gen.class);NftProof.generate(node,TOKEN,BIND,cb);reply(ok(new JSONArray().put(coin())));reply(response);assertEquals(2,commands.size());verify(cb).fail(anyString());}
        for(JSONObject sr:Arrays.asList(new JSONObject(),new JSONObject().put("script","LET x="+KEY+" RETURN TRUE").put("publickey",KEY).put("address",ADDR).put("simple",true),new JSONObject().put("script","RETURN SIGNEDBY("+KEY+")").put("publickey",KEY).put("address","0xaa").put("simple",true))){resetCalls();cb=mock(NftProof.Gen.class);NftProof.generate(node,TOKEN,BIND,cb);reply(ok(new JSONArray().put(coin())));reply(ok(new JSONObject().put("data","0xaabb")));reply(ok(sr));assertEquals(3,commands.size());verify(cb).fail(anyString());}
    }
    @Test public void signatureGenerationFailureCannotReturnAProof()throws Exception{
        for(JSONObject response:Arrays.asList(null,new JSONObject(),ok("0xaabb").put("status",false),ok("0xaabb").put("status","true"),ok("0xaabb;status"),ok("0xa"),ok(123))){
            resetCalls();NftProof.Gen cb=mock(NftProof.Gen.class);NftProof.generate(node,TOKEN,BIND,cb);reply(ok(new JSONArray().put(coin())));reply(ok(new JSONObject().put("data","0xaabb")));reply(ok(new JSONObject().put("script","RETURN SIGNEDBY("+KEY+")").put("publickey",KEY).put("address",ADDR).put("simple",true)));reply(response);verify(cb,never()).ok(any(JSONObject.class));verify(cb).fail(anyString());assertEquals(4,commands.size());
        }
    }
    @Test public void transportErrorsStopTheProofAtEveryStage()throws Exception{
        for(int stage=0;stage<3;stage++){
            resetCalls();NftProof.Verify cb=mock(NftProof.Verify.class);NftProof.verify(node,item(),BIND,cb);if(stage>0)reply(checked(coin()));if(stage>1)reply(script());pending.remove(0).onError("unavailable");verify(cb).result(eq(false),anyString(),eq(""));assertEquals(stage+1,commands.size());
        }
        for(int stage=0;stage<4;stage++){
            resetCalls();NftProof.Gen cb=mock(NftProof.Gen.class);NftProof.generate(node,TOKEN,BIND,cb);if(stage>0)reply(ok(new JSONArray().put(coin())));if(stage>1)reply(ok(new JSONObject().put("data","0xaabb")));if(stage>2)reply(ok(new JSONObject().put("script","RETURN SIGNEDBY("+KEY+")").put("publickey",KEY).put("address",ADDR).put("simple",true)));pending.remove(0).onError("unavailable");verify(cb).fail("unavailable");verify(cb,never()).ok(any(JSONObject.class));assertEquals(stage+1,commands.size());
        }
    }
    @Test public void stateArtworkAndEditionUrlsRemainLiteral()throws Exception{
        JSONObject c=coin().put("state",new JSONArray().put(new JSONObject().put("port",0).put("data","\"07\"")));assertEquals("https://example.invalid/art/07.svg",NftProof.editionImageUrl(c,"https://example.invalid/art/",".svg"));assertEquals("07",NftProof.state(c,0));assertEquals("",NftProof.stateImageUri(c));
    }
}
