package com.eurobuddha.merchshop;
import android.content.Context;
import com.eurobuddha.merchshop.integratedcomms.*;
import com.eurobuddha.nftwallet.OrderPaymentEvidence;
import org.json.*;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

/** Public quote and real SQLite fixture reused from InboxPlainFixture. No node or keys. */
abstract class ShopPaymentFixture {
    static final String TOKEN="0x"+"ab".repeat(32),ADDRESS="0x"+"12".repeat(32),DEST="0x"+"34".repeat(32);
    static final String KEY="fixture",HANDLE="ms_pay_"+"a".repeat(32),TX="0x"+"ee".repeat(32),MINED="0x"+"dd".repeat(32);
    Context context;MerchDb db;PaymentJournal plain;MerchDb.Order order;
    @Before public void setup(){context=org.robolectric.RuntimeEnvironment.getApplication();context.deleteDatabase("pandamonium_merchshop.db");db=new MerchDb(context);plain=new PaymentJournal(db);createOrder(KEY);order=plain.paymentOrder(KEY);}
    void createOrder(String ref){MerchMessage m=new MerchMessage();m.ref=ref;m.to="public-seller";m.amount="3";m.tokenid=TOKEN;m.currency="fixture token";m.items="[]";assertTrue(plain.saveOrder(m,DEST));plain.orderSent(ref);}
    @After public void close(){plain.close();db.close();context.deleteDatabase("pandamonium_merchshop.db");}
    static JSONObject object(Object... pairs){try{JSONObject j=new JSONObject();for(int i=0;i<pairs.length;i+=2)j.put((String)pairs[i],pairs[i+1]);return j;}catch(Exception e){throw new AssertionError(e);}}
    static JSONObject txn(JSONObject reply){return reply.optJSONObject("response").optJSONObject("body").optJSONObject("txn");}
    JSONObject quote()throws Exception {
        boolean minima="0x00".equals(order.tokenid);
        JSONObject input=object("coinid","0x"+"01".repeat(32),"tokenid",order.tokenid,"address",ADDRESS,"amount",minima?"10":"0.00000001","tokenamount","10","storestate",true,"spent",false);
        JSONArray out=new JSONArray().put(object("coinid","0x"+"33".repeat(32),"address",DEST,"tokenid",order.tokenid,"amount",minima?"3":"0.000000003","tokenamount","3","storestate",true))
            .put(object("coinid","0x"+"44".repeat(32),"address",ADDRESS,"tokenid",order.tokenid,"amount",minima?"7":"0.000000007","tokenamount","7","storestate",false));
        return object("status",true,"response",object("txpowid",TX,"istransaction",true,"body",object("txn",object("transactionid","0x"+"aa".repeat(32),"inputs",new JSONArray().put(input),"outputs",out,
                "state",new JSONArray().put(object("port",1,"data",OrderPaymentEvidence.reference(order.ref)))))));
    }
    JSONObject intent()throws Exception{return OrderPaymentEvidence.intent(quote(),DEST,order.amount,order.tokenid,order.ref,Collections.singleton(ADDRESS.toLowerCase(Locale.ROOT)));}
    JSONObject preparePlain()throws Exception{return plain.prepare(KEY,order,HANDLE,intent());}
    String plainPhase(){return plain.load(KEY,order).optString("phase");}
    void plainPhase(String phase){plain.phase(KEY,order,HANDLE,phase);}
    static JSONObject included(){return object("status",true,"response",object("found",true,"blockid","0x"+"ff".repeat(32),"block","100","tip","103","confirmations","3"));}
}
