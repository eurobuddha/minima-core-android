package com.eurobuddha.statenft;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.eurobuddha.statenft.BurialEvidenceTest.*;

public class AtelierBurialEvidenceTest {
    static JSONObject strippedPost(JSONObject coin)throws Exception{
        JSONObject r=posted(coin);txn(r).put("state",new JSONArray());txn(r).getJSONArray("outputs").getJSONObject(0).put("storestate",false);return r;
    }
    static JSONObject strippedOutput(JSONObject coin)throws Exception{return output(coin).put("storestate",false).put("state",new JSONArray());}
    @Test public void strippingRequiresExplicitIntentAndExactEmptyState()throws Exception{
        JSONObject c=coin(1),intent=BurialEvidence.burialIntent(c,TOKEN,false),reply=strippedPost(c);
        JSONObject receipt=BurialEvidence.receipt(reply,intent);
        assertFalse(BurialEvidence.preserves(receipt));assertTrue(BurialEvidence.confirmed(receipt,strippedOutput(c)));
        rejects(()->BurialEvidence.receipt(reply,BurialEvidence.intent(c,TOKEN)));
        rejects(()->BurialEvidence.receipt(posted(c),intent));
        assertFalse(BurialEvidence.confirmed(receipt,output(c)));assertFalse(BurialEvidence.confirmed(receipt,strippedOutput(c).put("state",object("0","7"))));
        JSONObject changed=copy(reply);txn(changed).put("state",object("0","7"));rejects(()->BurialEvidence.receipt(changed,intent));
    }
    @Test public void corruptedStateCanOnlyBeBuriedThroughExplicitStripping()throws Exception{
        JSONObject bad=coin(1).put("state",object("0","bad;send"));rejects(()->BurialEvidence.intent(bad,TOKEN));
        JSONObject intent=BurialEvidence.burialIntent(bad,TOKEN,false);assertFalse(BurialEvidence.preserves(intent));
        JSONObject badId=copy(bad).put("coinid","bad;send");rejects(()->BurialEvidence.burialIntent(badId,TOKEN,false));
        JSONObject badAmount=copy(bad).put("tokenamount","1 send");rejects(()->BurialEvidence.burialIntent(badAmount,TOKEN,false));
    }
    @Test public void journalComparisonIncludesPreservationModeAndRejectsCoercion()throws Exception{
        JSONObject c=coin(1),a=BurialEvidence.burialIntent(c,TOKEN,false),b=copy(a).put("preserve",true);
        assertFalse(BurialEvidence.sameJournal(new JSONArray().put(a),new JSONArray().put(b),TOKEN));
        for(Object value:new Object[]{"false",0,JSONObject.NULL}){JSONObject bad=copy(a).put("preserve",value);rejects(()->BurialEvidence.journal(new JSONArray().put(bad),TOKEN));}
        JSONObject legacy=BurialEvidence.intent(c,TOKEN);assertTrue(BurialEvidence.preserves(legacy));assertTrue(BurialEvidence.sameJournal(new JSONArray().put(legacy),new JSONArray().put(copy(legacy).put("preserve",true)),TOKEN));
    }
}
