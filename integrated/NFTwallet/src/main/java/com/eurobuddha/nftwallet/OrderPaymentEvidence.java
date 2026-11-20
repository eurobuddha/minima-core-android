package com.eurobuddha.nftwallet;

import org.json.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Public order-payment boundary over the wallet's existing native-send evidence checks. */
public final class OrderPaymentEvidence {
    private OrderPaymentEvidence() {}
    public static String reference(String ref) {
        if(ref==null||ref.isEmpty())throw TransactionEvidence.invalid();
        StringBuilder hex=new StringBuilder("0x");
        for(byte b:ref.getBytes(StandardCharsets.UTF_8))hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return hex.toString();
    }
    public static void request(String address,String amount,String token,String ref) {
        PreparedSendEvidence.request(address,amount,token,"0",1);reference(ref);
    }
    public static Set<String> changeAddresses(JSONObject reply){return PreparedSendEvidence.ownedOutputs(reply,1,false);}
    public static boolean defaultAddress(JSONObject reply,String address){return PreparedSendEvidence.defaultAddress(reply,address);}
    public static JSONObject intent(JSONObject reply,String address,String amount,String token,String ref,Set<String> owned) {
        request(address,amount,token,ref);
        referenceStamp(reply,ref);
        JSONObject txn=TransactionEvidence.txpow(reply).optJSONObject("body").optJSONObject("txn");
        JSONObject first=txn.optJSONArray("outputs").optJSONObject(0);
        if(first==null||!Boolean.TRUE.equals(first.opt("storestate")))throw TransactionEvidence.invalid();
        JSONObject intent=PreparedSendEvidence.quick(reply,address,amount,token,"0",1,owned);
        try{intent.getJSONArray("outputs").getJSONObject(0).put("address",address);}
        catch(JSONException invalid){throw TransactionEvidence.invalid();}
        TransactionEvidence.receipt(reply,intent);
        return TransactionEvidence.storedIntent(intent.toString());
    }
    /** The order ref in state port 1 and, for a miniMall order with a secret, its commitment (0x + 64 hex) in port 2
     *  (Pandamonium 2.2.69, security review H1); nothing else. */
    public static void referenceStamp(JSONObject reply,String ref) {
        JSONObject txn=TransactionEvidence.txpow(reply).optJSONObject("body").optJSONObject("txn");
        JSONArray state=txn==null?null:txn.optJSONArray("state");
        if(state==null||state.length()<1||state.length()>2)throw TransactionEvidence.invalid();
        boolean refSeen=false;
        for(int i=0;i<state.length();i++) {
            JSONObject e=state.optJSONObject(i);
            if(e==null||!(e.opt("data") instanceof String))throw TransactionEvidence.invalid();
            String port=String.valueOf(e.opt("port"));
            if("1".equals(port)&&!refSeen&&reference(ref).equalsIgnoreCase(e.optString("data")))refSeen=true;
            else if(!"2".equals(port)||state.length()!=2||!e.optString("data").matches("0x[0-9a-fA-F]{64}"))throw TransactionEvidence.invalid();
        }
        if(!refSeen)throw TransactionEvidence.invalid();
    }
    public static JSONObject storedIntent(String raw){return TransactionEvidence.storedIntent(raw);}
    public static void binds(JSONObject intent,String address,String amount,String token) {
        if(!BurialEvidence.same(token,intent.optString("tokenid"))||intent.has("companion")
                ||new BigDecimal(intent.optString("burn")).signum()!=0)throw TransactionEvidence.invalid();
        TransactionEvidence.nonzeroId(intent,"transactionid");
        JSONArray outputs=intent.optJSONArray("outputs");
        if(outputs==null||outputs.length()<1||outputs.length()>2)throw TransactionEvidence.invalid();
        JSONObject first=outputs.optJSONObject(0);
        if(!address.equals(first.optString("address"))||new BigDecimal(amount).compareTo(new BigDecimal(first.optString("amount")))!=0
                ||!Boolean.TRUE.equals(first.opt("storestate")))throw TransactionEvidence.invalid();
    }
    public static Set<String> inputIds(JSONObject intent){return TransactionEvidence.inputIds(intent);}
    public static JSONObject publicReceipt(JSONObject reply,JSONObject intent){return TransactionEvidence.publicReceipt(reply,intent);}
    public static void storedReceipt(JSONObject receipt,JSONObject intent){TransactionEvidence.storedReceipt(receipt,intent);}
    public static JSONObject inclusion(JSONObject reply){return TransactionEvidence.inclusion(reply);}
    public static String txpowid(JSONObject reply){return TransactionEvidence.txpow(reply).optString("txpowid");}
}
