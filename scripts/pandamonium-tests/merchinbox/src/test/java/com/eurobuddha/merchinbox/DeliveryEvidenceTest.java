package com.eurobuddha.merchinbox;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeliveryEvidenceTest {
    static final String TOKEN="0x"+"ab".repeat(32), ADDRESS="0x"+"12".repeat(32);
    static JSONObject object(Object... pairs){try{JSONObject j=new JSONObject();for(int i=0;i<pairs.length;i+=2)j.put((String)pairs[i],pairs[i+1]);return j;}catch(Exception e){throw new AssertionError(e);}}
    static JSONObject copy(JSONObject j){try{return new JSONObject(j.toString());}catch(Exception e){throw new AssertionError(e);}}
    static JSONObject coin(int n){return object("coinid","0x"+String.format("%02x",n).repeat(32),"tokenid",TOKEN,"address",ADDRESS,"tokenamount","1.0000","state",object("0","7","1","[QUJDRA==]"),"storestate",true,"spent",false);}
    static JSONObject output(JSONObject input){JSONObject out=copy(input);try{out.put("coinid","0x"+"ee".repeat(31)+input.getString("coinid").substring(64));out.put("address",StateNft.GRAVEYARD);}catch(Exception e){throw new AssertionError(e);}return out;}
    static JSONObject posted(JSONObject input){JSONObject out=output(input);try{out.put("state",new JSONArray());}catch(Exception e){throw new AssertionError(e);}return object("status",true,"response",object("body",object("txn",object("inputs",new JSONArray().put(input),"outputs",new JSONArray().put(out),"state",input.opt("state")))));}
    static JSONObject list(JSONArray coins){return object("status",true,"response",coins);}
    static JSONObject txn(JSONObject reply){return reply.optJSONObject("response").optJSONObject("body").optJSONObject("txn");}
    static void rejects(Runnable r){try{r.run();fail("Incomplete evidence accepted");}catch(IllegalArgumentException expected){}}
    @Test public void completeCoinListDoesNotTurnUnknownOrMalformedRowsIntoEmpty()throws Exception{
        assertEquals(0,DeliveryEvidence.coins(list(new JSONArray()),TOKEN).length());
        assertEquals(1,DeliveryEvidence.coins(list(new JSONArray().put(coin(1))),TOKEN).length());
        for(JSONObject bad:new JSONObject[]{null,object("status",true),object("status","true","response",new JSONArray()),object("status",false,"response",new JSONArray()),object("status",true,"response",object()),list(new JSONArray().put(JSONObject.NULL)),list(new JSONArray().put("bad")),list(new JSONArray().put(object())),list(new JSONArray().put(coin(1)).put(coin(1)))})rejects(()->DeliveryEvidence.coins(bad,TOKEN));
        for(String field:new String[]{"coinid","tokenid","address","tokenamount"}){JSONObject c=coin(1);c.remove(field);rejects(()->DeliveryEvidence.coins(list(new JSONArray().put(c)),TOKEN));}
        for(Object amount:new Object[]{"0","-1","1 burn:9",1,JSONObject.NULL}){JSONObject c=coin(1).put("tokenamount",amount);rejects(()->DeliveryEvidence.coins(list(new JSONArray().put(c)),TOKEN));}
        JSONObject c=coin(1).put("tokenid","0x99");rejects(()->DeliveryEvidence.coins(list(new JSONArray().put(c)),TOKEN));
    }
    @Test public void stateFingerprintIsOrderIndependentButRejectsLostOrHostilePorts()throws Exception{
        JSONObject c=coin(1), array=copy(c).put("state",new JSONArray().put(object("port",1,"data","[QUJDRA==]")).put(object("port","0","data","7")));
        assertEquals(DeliveryEvidence.stateHash(c),DeliveryEvidence.stateHash(array));
        for(Object state:new Object[]{JSONObject.NULL,object(),new JSONArray(),new JSONArray().put(JSONObject.NULL),new JSONArray().put(object("port",0,"data","7")).put(object("port","00","data","7")),object("0","7 burn:9"),object("0","7","256","1"),object("0",true)}){JSONObject bad=copy(c).put("state",state);rejects(()->DeliveryEvidence.stateHash(bad));}
        assertNotEquals(DeliveryEvidence.stateHash(c),DeliveryEvidence.stateHash(copy(c).put("state",object("0","8","1","[QUJDRA==]"))));
    }
    @Test public void receiptUsesTransactionStateAndExactIntendedInputOutput()throws Exception{
        JSONObject input=coin(1), intent=DeliveryEvidence.intent(input,TOKEN), reply=posted(input);
        JSONObject receipt=DeliveryEvidence.receipt(reply,intent);
        assertEquals(output(input).getString("coinid"),receipt.getString("output"));
        assertEquals("1",receipt.getString("tokenamount"));
        assertTrue(DeliveryEvidence.confirmed(receipt,output(input)));
        assertTrue(DeliveryEvidence.confirmed(receipt,copy(output(input)).put("tokenamount","1.00000000")));
        assertEquals(receipt.toString(),DeliveryEvidence.receipt(object("status",true,"response",object("txpow",reply.getJSONObject("response"))),intent).toString());
        for(JSONObject bad:new JSONObject[]{null,object("status",true),object("status",false,"response",reply.getJSONObject("response"))})rejects(()->DeliveryEvidence.receipt(bad,intent));
        for(String field:new String[]{"coinid","tokenid","address","tokenamount","storestate"}){
            JSONObject bad=copy(reply);txn(bad).getJSONArray("outputs").getJSONObject(0).remove(field);rejects(()->DeliveryEvidence.receipt(bad,intent));
        }
        for(Object[] mutation:new Object[][]{{"coinid","0x00"},{"coinid",input.getString("coinid")},{"tokenid","0x99"},{"address",ADDRESS},{"tokenamount","2"},{"storestate",false}}){JSONObject bad=copy(reply);txn(bad).getJSONArray("outputs").getJSONObject(0).put((String)mutation[0],mutation[1]);rejects(()->DeliveryEvidence.receipt(bad,intent));}
        JSONObject wrongInput=copy(reply);txn(wrongInput).getJSONArray("inputs").getJSONObject(0).put("coinid",coin(2).getString("coinid"));rejects(()->DeliveryEvidence.receipt(wrongInput,intent));
        JSONObject wrongState=copy(reply);txn(wrongState).put("state",object("0","8"));rejects(()->DeliveryEvidence.receipt(wrongState,intent));
        JSONObject extra=copy(reply);txn(extra).getJSONArray("outputs").put(output(input));rejects(()->DeliveryEvidence.receipt(extra,intent));
    }
    @Test public void chainEvidenceMustMatchDestinationAmountStateAndUnspentOutput()throws Exception{
        JSONObject input=coin(1),receipt=DeliveryEvidence.receipt(posted(input),DeliveryEvidence.intent(input,TOKEN));
        for(Object[] mutation:new Object[][]{{"coinid","0x99"},{"tokenid","0x99"},{"address",ADDRESS},{"tokenamount","2"},{"spent",true},{"spent","false"},{"storestate",false},{"state",object("0","8")}}){JSONObject bad=output(input).put((String)mutation[0],mutation[1]);assertFalse(DeliveryEvidence.confirmed(receipt,bad));}
        assertFalse(DeliveryEvidence.confirmed(receipt,null));
        assertFalse(DeliveryEvidence.confirmed(DeliveryEvidence.intent(input,TOKEN),output(input)));
    }
    @Test public void journalsKeepUnknownPostsPendingAndRejectDuplicateOrCorruptEvidence()throws Exception{
        JSONObject pending=DeliveryEvidence.intent(coin(1),TOKEN), receipt=DeliveryEvidence.receipt(posted(coin(1)),pending);
        DeliveryEvidence.journal(new JSONArray().put(pending),TOKEN);
        DeliveryEvidence.journal(new JSONArray().put(receipt).put(DeliveryEvidence.intent(coin(2),TOKEN)),TOKEN);
        for(JSONArray bad:new JSONArray[]{new JSONArray().put(JSONObject.NULL),new JSONArray().put(object()),new JSONArray().put(pending).put(pending),new JSONArray().put(copy(pending).put("statehash","")),new JSONArray().put(copy(pending).put("output","bad id")),new JSONArray().put(receipt).put(copy(receipt).put("input",coin(2).getString("coinid")))})rejects(()->DeliveryEvidence.journal(bad,TOKEN));
    }
    @Test public void journalComparisonUsesPublicFieldsRatherThanJsonKeyOrEntryOrder()throws Exception{
        JSONObject a=DeliveryEvidence.receipt(posted(coin(1)),DeliveryEvidence.intent(coin(1),TOKEN));
        JSONObject b=DeliveryEvidence.intent(coin(2),TOKEN);
        JSONArray first=new JSONArray().put(a).put(b), reordered=new JSONArray().put(copy(b)).put(copy(a).put("tokenamount","1.000"));
        assertTrue(DeliveryEvidence.sameJournal(first,reordered,TOKEN));
        assertFalse(DeliveryEvidence.sameJournal(first,new JSONArray().put(a),TOKEN));
        assertFalse(DeliveryEvidence.sameJournal(first,new JSONArray().put(a).put(copy(b).put("output","0x99")),TOKEN));
    }
}
