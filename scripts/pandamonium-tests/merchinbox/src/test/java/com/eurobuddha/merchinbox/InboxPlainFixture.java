package com.eurobuddha.merchinbox;
import com.eurobuddha.nftwallet.OrderPaymentEvidence;
import org.json.*;
import org.junit.*;
import java.util.*;
import static com.eurobuddha.merchinbox.DeliveryEvidenceTest.*;

/** Same real SQLite fixture as stateful delivery; quote economics come from PreparedSendEvidenceTest. */
abstract class InboxPlainFixture extends InboxDeliveryFixture {
    static final String HANDLE="mi_send_"+"a".repeat(32),TX="0x"+"ee".repeat(32),MINED="0x"+"dd".repeat(32);
    PlainDeliveryJournal plain;
    @Before public void plainSetup(){line=new String[]{"Fixture",TOKEN,"3","0","0"};plain=new PlainDeliveryJournal(db,journal);}
    @After public void plainClose(){plain.close();}
    JSONObject quote()throws Exception {
        JSONObject input=coin(1).put("amount","0.00000001").put("tokenamount","10");
        JSONArray out=new JSONArray().put(object("coinid","0x"+"33".repeat(32),"address",DEST,"tokenid",TOKEN,"amount","0.000000003","tokenamount","3","storestate",true))
            .put(object("coinid","0x"+"44".repeat(32),"address",ADDRESS,"tokenid",TOKEN,"amount","0.000000007","tokenamount","7","storestate",false));
        return object("status",true,"response",object("txpowid",TX,"istransaction",true,"body",object("txn",object("transactionid","0x"+"aa".repeat(32),"inputs",new JSONArray().put(input),"outputs",out,
                "state",new JSONArray().put(object("port",1,"data",OrderPaymentEvidence.reference(order.ref)))))));
    }
    JSONObject intent()throws Exception{return OrderPaymentEvidence.intent(quote(),DEST,"3",TOKEN,order.ref,Collections.singleton(ADDRESS.toLowerCase(Locale.ROOT)));}
    JSONObject preparePlain()throws Exception{return plain.prepare(KEY,order,line,HANDLE,intent());}
    String plainPhase(){return plain.load(KEY,order,line).optString("phase");}
    void plainPhase(String phase){plain.phase(KEY,order,line,HANDLE,phase);}
}
