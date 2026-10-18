package com.eurobuddha.nftwallet;
import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static com.eurobuddha.nftwallet.TransactionEvidenceTest.*;
import static com.eurobuddha.nftwallet.BurialEvidenceTest.object;
public class OrderPaymentEvidenceTest {
    static final String REF="order 中文 Україна";
    JSONObject quote()throws Exception {JSONObject q=PreparedSendEvidenceTest.quote(TOKEN,"0",false);txn(q).put("state",new JSONArray().put(object("port",1,"data",OrderPaymentEvidence.reference(REF))));return q;}
    JSONObject plan(JSONObject q){return OrderPaymentEvidence.intent(q,DEST,"3",TOKEN,REF,PreparedSendEvidenceTest.OWN);}
    @Test public void exactOrderReferenceRecipientQuantityAndChangeAreFrozen()throws Exception {JSONObject q=quote(),intent=plan(q);OrderPaymentEvidence.binds(intent,DEST,"3",TOKEN);OrderPaymentEvidence.storedReceipt(OrderPaymentEvidence.publicReceipt(q,intent),intent);assertEquals(2,intent.getJSONArray("outputs").length());assertEquals("0x61223b",OrderPaymentEvidence.reference("a\";"));}
    @Test public void mxRecipientRetainsItsExactRequestFormInTheSavedIntent()throws Exception {JSONObject q=quote();txn(q).getJSONArray("outputs").getJSONObject(0).put("miniaddress",MX);JSONObject intent=OrderPaymentEvidence.intent(q,MX,"3",TOKEN,REF,PreparedSendEvidenceTest.OWN);OrderPaymentEvidence.binds(OrderPaymentEvidence.storedIntent(intent.toString()),MX,"3",TOKEN);OrderPaymentEvidence.publicReceipt(q,intent);}
    @Test public void missingWrongExtraOrNonStoredReferenceCannotAuthorizeSigning()throws Exception {
        for(int mode=0;mode<5;mode++){JSONObject q=quote();JSONArray state=txn(q).getJSONArray("state");if(mode==0)state.remove(0);if(mode==1)state.getJSONObject(0).put("data","0x00");if(mode==2)state.getJSONObject(0).put("port",2);if(mode==3)state.put(object("port",2,"data","0x00"));if(mode==4)txn(q).getJSONArray("outputs").getJSONObject(0).put("storestate",false);assertThrows(RuntimeException.class,()->plan(q));}
    }
    @Test public void staleOrderAmountAndUnownedChangeAreRejected()throws Exception {JSONObject q=quote(),intent=plan(q);assertThrows(RuntimeException.class,()->OrderPaymentEvidence.binds(intent,DEST,"4",TOKEN));assertThrows(RuntimeException.class,()->OrderPaymentEvidence.binds(intent,CHANGE,"3",TOKEN));assertThrows(RuntimeException.class,()->OrderPaymentEvidence.intent(q,DEST,"3",TOKEN,REF,Collections.emptySet()));}
    @Test public void malformedAndPartialPostedRepliesRemainUnconfirmed()throws Exception {JSONObject intent=plan(quote());for(JSONObject bad:new JSONObject[]{null,object("status",true),object("pending",true)})assertThrows(RuntimeException.class,()->OrderPaymentEvidence.publicReceipt(bad,intent));JSONObject q=quote();txn(q).getJSONArray("outputs").remove(1);assertThrows(RuntimeException.class,()->OrderPaymentEvidence.publicReceipt(q,intent));}
}
