package com.eurobuddha.utxo;

import org.json.*;
import java.math.BigDecimal;
import java.util.*;

/** NFT Wallet TransactionEvidence adapted to UTXO's legacy public history rows. */
final class HistoryEvidence {
    private HistoryEvidence() {}
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Incomplete transaction evidence"); }
    static String text(JSONObject value,String key) {
        Object raw=value==null?null:value.opt(key);
        if(!(raw instanceof String))throw invalid();return (String)raw;
    }
    static String id(JSONObject value,String key) {
        String id=text(value,key);
        if(!id.matches("0x[0-9a-fA-F]{64}")||new java.math.BigInteger(id.substring(2),16).signum()==0)throw invalid();
        return id.toLowerCase(Locale.ROOT);
    }
    static BigDecimal amount(String value) {
        if(value==null||value.length()>128||!value.matches("[0-9]+(?:\\.[0-9]+)?"))throw invalid();
        BigDecimal amount=new BigDecimal(value);if(amount.signum()<=0)throw invalid();return amount;
    }
    static BigDecimal amount(JSONObject coin,String token) {
        return amount(text(coin,"0x00".equals(token)?"amount":"tokenamount"));
    }
    static JSONArray array(String raw) {
        if(raw==null||raw.indexOf(0)>=0)throw invalid();
        try {
            JSONTokener input=new JSONTokener(raw);Object value=input.nextValue();
            while(input.more()) {char c=input.next();if(c!=' '&&c!='\t'&&c!='\r'&&c!='\n')throw invalid();}
            if(!(value instanceof JSONArray))throw invalid();return (JSONArray)value;
        }catch(JSONException e){throw invalid();}
    }
    static boolean addressIs(JSONObject coin,String destination) {
        if(!Util.isValidAddress(destination))return false;
        return destination.startsWith("0x")?destination.equalsIgnoreCase(id(coin,"address"))
                :destination.equals(coin.opt("miniaddress"));
    }
    /** Never infer failure from a partial overlap; require every input/output with multiplicity. */
    static boolean matches(HistoryRow row,JSONObject txpow) {
        try {
            String actualId=id(txpow,"txpowid");
            if(row.txnid!=null&&row.txnid.matches("0x[0-9a-fA-F]{64}")
                    &&!actualId.equalsIgnoreCase(row.txnid))return false;
            if(!Boolean.TRUE.equals(txpow.opt("istransaction")))return false;
            String token=row.tokenid;
            if(token==null||!(token.equals("0x00")||token.matches("0x[0-9a-fA-F]{64}")))return false;
            JSONObject body=txpow.optJSONObject("body"),txn=body==null?null:body.optJSONObject("txn");
            JSONArray ins=txn==null?null:txn.optJSONArray("inputs"),outs=txn==null?null:txn.optJSONArray("outputs");
            JSONArray expectedIn=array(row.inputs),expectedOut=array(row.outputs);
            if(ins==null||outs==null||expectedIn.length()==0||expectedOut.length()==0
                    ||ins.length()!=expectedIn.length()||outs.length()!=expectedOut.length())return false;
            JSONObject burn=body.optJSONObject("burntxn");
            if(burn!=null) {
                JSONArray bi=burn.optJSONArray("inputs"),bo=burn.optJSONArray("outputs");
                if(bi==null||bo==null||bi.length()!=0||bo.length()!=0)return false;
            }
            Map<String,JSONObject> actual=new HashMap<>();Set<String> ids=new HashSet<>();
            BigDecimal totalIn=BigDecimal.ZERO,totalOut=BigDecimal.ZERO,referenceRaw=null,referenceHuman=null;
            for(int i=0;i<ins.length();i++) {
                JSONObject coin=ins.optJSONObject(i);String key=id(coin,"coinid");
                if(!ids.add(key)||!token.equalsIgnoreCase(text(coin,"tokenid")))return false;
                BigDecimal human=amount(coin,token),raw=amount(text(coin,"amount"));
                if(referenceRaw==null){referenceRaw=raw;referenceHuman=human;}
                if(raw.multiply(referenceHuman).compareTo(human.multiply(referenceRaw))!=0)return false;
                totalIn=totalIn.add(human);actual.put(key,coin);
            }
            for(int i=0;i<expectedIn.length();i++) {
                JSONObject expected=expectedIn.optJSONObject(i),coin=actual.remove(id(expected,"coinid"));
                if(coin==null||amount(text(expected,"amount")).compareTo(amount(coin,token))!=0)return false;
            }
            if(!actual.isEmpty())return false;
            for(int i=0;i<outs.length();i++) {
                JSONObject coin=outs.optJSONObject(i);
                if(!ids.add(id(coin,"coinid"))||!token.equalsIgnoreCase(text(coin,"tokenid")))return false;
                id(coin,"address");BigDecimal human=amount(coin,token),raw=amount(text(coin,"amount"));
                if(raw.multiply(referenceHuman).compareTo(human.multiply(referenceRaw))!=0)return false;
                totalOut=totalOut.add(human);
            }
            boolean[] used=new boolean[outs.length()];
            for(int i=0;i<expectedOut.length();i++) {
                JSONObject expected=expectedOut.optJSONObject(i);int match=-1;
                for(int j=0;j<outs.length();j++)if(!used[j]&&addressIs(outs.optJSONObject(j),text(expected,"address"))
                        &&amount(text(expected,"amount")).compareTo(amount(outs.optJSONObject(j),token))==0){match=j;break;}
                if(match<0)return false;used[match]=true;
            }
            String fee=row.burn==null||row.burn.isEmpty()?"0":row.burn;
            BigDecimal expectedBurn=fee.matches("0(?:\\.0+)?")?BigDecimal.ZERO:amount(fee);
            return ("0x00".equals(token)||expectedBurn.signum()==0)&&totalIn.subtract(totalOut).compareTo(expectedBurn)==0;
        }catch(RuntimeException invalid){return false;}
    }
    /** Match the node's configured default confirmation depth (GlobalParams.MINIMA_CONFIRM_DEPTH = 3). */
    static JSONObject inclusion(JSONObject reply) {
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status")) || Boolean.TRUE.equals(reply.opt("pending"))) throw invalid();
        JSONObject value = reply.optJSONObject("response");
        if (value == null || !Boolean.TRUE.equals(value.opt("found"))) throw invalid();
        String blockId = id(value,"blockid");
        if (new java.math.BigInteger(blockId.substring(2),16).signum() == 0) throw invalid();
        java.math.BigInteger block = whole(value,"block"), tip = whole(value,"tip"), depth = whole(value,"confirmations");
        if (block.signum() <= 0 || depth.compareTo(java.math.BigInteger.valueOf(3)) < 0
                || !tip.subtract(block).equals(depth)) throw invalid();
        try { return new JSONObject().put("found",true).put("blockid",blockId)
                .put("block",block.toString()).put("tip",tip.toString()).put("confirmations",depth.toString()); }
        catch (org.json.JSONException e) { throw invalid(); }
    }
    private static java.math.BigInteger whole(JSONObject object,String key) {
        Object raw=object.opt(key);
        if (!(raw instanceof String) || !((String)raw).matches("[0-9]{1,30}")) throw invalid();
        return new java.math.BigInteger((String)raw);
    }
}
