package com.eurobuddha.utxo;

import org.json.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Freeze the node's unsigned selection, preserving its split, change and separate burn transaction. */
final class PreparedSendEvidence {
    private PreparedSendEvidence() {}
    static void request(String recipient,String amount,String token,String burn,int split) {
        if(!Util.isValidAddress(recipient)||!Util.isValidAmount(amount)||new BigDecimal(amount).signum()<=0
                ||!Util.isValidHexId(token)||!Util.isValidAmount(fee(burn))||split<1||split>20)throw TransactionEvidence.invalid();
    }
    /** WalletTools.doSplit arithmetic: round down, then retain the remainder in the last coin. */
    static List<String> splitAmounts(String amount,int split,int decimals) {
        if(!Util.isValidAmount(amount)||split<1||split>20||decimals<0||decimals>44)throw TransactionEvidence.invalid();
        BigDecimal total=new BigDecimal(amount);
        if(total.signum()<=0||Util.decimalPlaces(total)>decimals)throw TransactionEvidence.invalid();
        BigDecimal each=total.divide(new BigDecimal(split),decimals,RoundingMode.DOWN);
        if(each.signum()<=0)throw TransactionEvidence.invalid();
        BigDecimal last=total.subtract(each.multiply(new BigDecimal(split-1)));
        List<String> values=new ArrayList<>();
        for(int i=0;i<split;i++)values.add((i==split-1?last:each).stripTrailingZeros().toPlainString());
        return Collections.unmodifiableList(values);
    }
    static JSONObject quickSplit(JSONObject reply,String recipient,String amount,String token,String burn,int split,Set<String> owned,int decimals) {
        return quick(reply,recipient,amount,token,burn,split,owned,splitAmounts(amount,split,decimals));
    }
    static String fee(String burn){return burn==null||burn.isEmpty()?"0":burn;}
    private static JSONObject body(JSONObject reply){JSONObject b=TransactionEvidence.txpow(reply).optJSONObject("body");if(b==null)throw TransactionEvidence.invalid();return b;}
    private static JSONArray outputs(JSONObject txn){JSONArray a=txn==null?null:txn.optJSONArray("outputs");if(a==null)throw TransactionEvidence.invalid();return a;}
    /** Addresses requiring a fresh default-wallet script check before accepting a quote. */
    static Set<String> ownedOutputs(JSONObject reply,int split,boolean consolidate) {
        if(split<1||split>20)throw TransactionEvidence.invalid();
        JSONObject b=body(reply);JSONArray main=outputs(b.optJSONObject("txn"));
        if(main.length()<split||main.length()>split+(consolidate?2:1))throw TransactionEvidence.invalid();
        Set<String> own=new LinkedHashSet<>();
        for(int i=consolidate?0:split;i<main.length();i++)own.add(TransactionFields.id(main.optJSONObject(i),"address").toLowerCase(Locale.ROOT));
        JSONObject burn=b.optJSONObject("burntxn");
        if(burn!=null){JSONArray out=outputs(burn);if(out.length()>1)throw TransactionEvidence.invalid();for(int i=0;i<out.length();i++)own.add(TransactionFields.id(out.optJSONObject(i),"address").toLowerCase(Locale.ROOT));}
        return own;
    }
    static boolean defaultAddress(JSONObject reply,String address) {
        if(reply==null||!Boolean.TRUE.equals(reply.opt("status")))return false;
        JSONObject script=reply.optJSONObject("response");
        try{return script!=null&&Boolean.TRUE.equals(script.opt("default"))&&Boolean.TRUE.equals(script.opt("simple"))
                &&TransactionFields.same(address,TransactionFields.id(script,"address"))
                &&TransactionEvidence.nonzeroId(script,"publickey")!=null;}catch(RuntimeException invalid){return false;}
    }
    static JSONObject quick(JSONObject reply,String recipient,String amount,String token,String burn,int split,Set<String> owned) {
        return quick(reply,recipient,amount,token,burn,split,owned,null);
    }
    private static JSONObject quick(JSONObject reply,String recipient,String amount,String token,String burn,int split,Set<String> owned,List<String> plannedAmounts) {
        request(recipient,amount,token,burn,split);
        JSONObject b=body(reply),main=b.optJSONObject("txn");JSONArray outs=outputs(main);
        Set<String> needed=ownedOutputs(reply,split,false);
        if(owned==null||!owned.containsAll(needed))throw TransactionEvidence.invalid();
        BigDecimal total=BigDecimal.ZERO;String each=null;
        for(int i=0;i<split;i++) {
            JSONObject out=outs.optJSONObject(i);String value=TransactionEvidence.amount(out,token);
            if(!TransactionFields.addressIs(out,recipient))throw TransactionEvidence.invalid();
            if(plannedAmounts==null ? each!=null&&!each.equals(value) : !plannedAmounts.get(i).equals(value))throw TransactionEvidence.invalid();
            each=value;total=total.add(new BigDecimal(value));
        }
        if(total.compareTo(new BigDecimal(amount))!=0)throw TransactionEvidence.invalid();
        JSONObject intent=part(main,token,Util.isMinima(token)?fee(burn):"0",false);
        if(!Util.isMinima(token)&&new BigDecimal(fee(burn)).signum()>0) {
            try{intent.put("companion",part(b.optJSONObject("burntxn"),"0x00",fee(burn),true));}
            catch(JSONException e){throw TransactionEvidence.invalid();}
        }
        TransactionEvidence.receipt(reply,intent);
        return TransactionEvidence.storedIntent(intent.toString());
    }
    /** consolidate's existing native command delegates to send split:2; preserve all its chosen outputs. */
    static JSONObject consolidation(JSONObject reply,String token,Set<String> owned) {
        if(!Util.isMinima(token)||owned==null||!owned.containsAll(ownedOutputs(reply,2,true)))throw TransactionEvidence.invalid();
        JSONObject txn=body(reply).optJSONObject("txn");
        JSONArray state=txn.optJSONArray("state");
        if(state==null||state.length()!=0||new java.math.BigInteger(TransactionFields.id(txn,"linkhash").substring(2),16).signum()!=0)
            throw TransactionEvidence.invalid();
        JSONArray out=outputs(txn);
        for(int i=0;i<out.length();i++)if(!(out.optJSONObject(i).opt("storestate") instanceof Boolean))throw TransactionEvidence.invalid();
        JSONObject intent=part(txn,token,"0",false);
        TransactionEvidence.receipt(reply,intent);return TransactionEvidence.storedIntent(intent.toString());
    }
    private static JSONObject part(JSONObject txn,String token,String burn,boolean burnOnly) {
        JSONArray ins=txn==null?null:txn.optJSONArray("inputs"),outs=outputs(txn);
        if(ins==null||ins.length()==0)throw TransactionEvidence.invalid();
        List<Coin> coins=new ArrayList<>();List<TxnBuilder.Out> destinations=new ArrayList<>();
        for(int i=0;i<ins.length();i++) {
            JSONObject raw=ins.optJSONObject(i);if(raw==null)throw TransactionEvidence.invalid();
            Coin coin=Coin.from(raw);coin.sendable=true;coins.add(coin);
        }
        for(int i=0;i<outs.length();i++) {
            JSONObject raw=outs.optJSONObject(i);
            if(!TransactionFields.same(token,TransactionFields.id(raw,"tokenid")))throw TransactionEvidence.invalid();
            if(raw.has("storestate")&&!(raw.opt("storestate") instanceof Boolean))throw TransactionEvidence.invalid();
            destinations.add(new TxnBuilder.Out(TransactionFields.id(raw,"address"),TransactionEvidence.amount(raw,token),
                    raw.has("storestate")?(Boolean)raw.opt("storestate"):null));
        }
        JSONObject intent=TransactionEvidence.intent(coins,destinations,token,burn,burnOnly);
        try{return intent.put("transactionid",TransactionEvidence.nonzeroId(txn,"transactionid"));}
        catch(JSONException e){throw TransactionEvidence.invalid();}
    }
}
