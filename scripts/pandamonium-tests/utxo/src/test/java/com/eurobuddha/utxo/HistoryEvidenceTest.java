package com.eurobuddha.utxo;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** Complete public economics, adapted from NFT TransactionEvidenceTest. */
public class HistoryEvidenceTest {
    static String id(char c) { return "0x"+String.valueOf(c).repeat(64); }
    static final String DEST=id('a'), CHANGE=id('b'), TOKEN=id('c'), TX=id('f');
    static JSONObject obj(Object... values) {
        JSONObject j=new JSONObject();
        try {for(int i=0;i<values.length;i+=2)j.put((String)values[i],values[i+1]);}
        catch(JSONException e){throw new AssertionError(e);}return j;
    }
    static JSONArray arr(Object... values){JSONArray a=new JSONArray();for(Object v:values)a.put(v);return a;}
    static HistoryRow row(String token) {
        HistoryRow r=new HistoryRow();r.internalid="attempt";r.txnid="uw_local";r.status=HistoryDb.STATUS_UNKNOWN;
        r.tokenid=token;r.amount="3";r.tokenName="Test";r.ts=1;r.burn="0";
        r.inputs=arr(obj("coinid",id('1'),"address",CHANGE,"amount","10")).toString();
        r.outputs=arr(obj("address",DEST,"amount","3"),obj("address",CHANGE,"amount","7")).toString();return r;
    }
    static JSONObject coin(char cid,String addr,String human,String token) {
        return obj("coinid",id(cid),"address",addr,"tokenid",token,"tokenamount",human,
            "amount",token.equals("0x00")?human:new java.math.BigDecimal(human).movePointLeft(9).toPlainString());
    }
    static JSONObject tx(String token) {
        return obj("txpowid",TX,"istransaction",true,"header",obj("block","90","timemilli","1234"),
            "body",obj("txn",obj("inputs",arr(coin('1',CHANGE,"10",token)),"outputs",arr(coin('3',DEST,"3",token),coin('4',CHANGE,"7",token)))));
    }
    static JSONObject txn(JSONObject t){return t.optJSONObject("body").optJSONObject("txn");}
    static JSONObject detail(){return obj("difference",obj("0x00","-3"));}
    static JSONObject inclusion(){return obj("status",true,"response",obj("found",true,"blockid",id('e'),"block","100","tip","103","confirmations","3"));}
    @Test public void completeEconomicMatchSupportsNativeAndScaledTokens() {
        for(String token:new String[]{"0x00",TOKEN})assertTrue(HistoryEvidence.matches(row(token),tx(token)));
    }
    @Test public void everyInputAndOutputMustMatchWithDistinctIds() throws Exception {
        for(int mode=0;mode<10;mode++) {
            JSONObject t=tx("0x00");JSONArray ins=txn(t).getJSONArray("inputs"),outs=txn(t).getJSONArray("outputs");
            if(mode==0)ins.remove(0);if(mode==1)ins.put(ins.getJSONObject(0));if(mode==2)ins.getJSONObject(0).put("coinid",id('9'));
            if(mode==3)ins.getJSONObject(0).put("amount","11");if(mode==4)outs.remove(1);if(mode==5)outs.put(outs.getJSONObject(0));
            if(mode==6)outs.getJSONObject(1).put("address",id('9'));if(mode==7)outs.getJSONObject(1).put("amount","6");
            if(mode==8)outs.getJSONObject(1).put("coinid",id('3'));if(mode==9)outs.getJSONObject(1).put("coinid",id('1'));
            assertFalse("mutation "+mode,HistoryEvidence.matches(row("0x00"),t));
        }
    }
    @Test public void duplicateDestinationsRequireDistinctEqualOutputs() throws Exception {
        HistoryRow r=row("0x00");r.outputs=arr(obj("address",DEST,"amount","5"),obj("address",DEST,"amount","5")).toString();
        JSONObject t=tx("0x00");txn(t).put("outputs",arr(coin('3',DEST,"5","0x00"),coin('4',DEST,"5","0x00")));
        assertTrue(HistoryEvidence.matches(r,t));txn(t).getJSONArray("outputs").getJSONObject(1).put("address",CHANGE);
        assertFalse(HistoryEvidence.matches(r,t));
    }
    @Test public void tokenRawAmountsAndIdentityCannotBeSubstituted() throws Exception {
        for(String key:new String[]{"amount","tokenamount","tokenid"}) {
            JSONObject t=tx(TOKEN);txn(t).getJSONArray("outputs").getJSONObject(1).put(key,key.equals("tokenid")?"0x00":"8");
            assertFalse(HistoryEvidence.matches(row(TOKEN),t));
        }
    }
    @Test public void savedTransactionIdCannotResolveToAnotherTransaction() {
        HistoryRow r=row("0x00");r.txnid=id('9');assertFalse(HistoryEvidence.matches(r,tx("0x00")));
        r.txnid=TX;assertTrue(HistoryEvidence.matches(r,tx("0x00")));
    }
    @Test public void missingLegacyOutputsOrTrailingDataCannotConfirm() {
        for(String bad:new String[]{null,"[]","{}","[null]",row("0x00").outputs+" garbage"}) {
            HistoryRow r=row("0x00");r.outputs=bad;assertFalse(HistoryEvidence.matches(r,tx("0x00")));
        }
    }
    @Test public void explicitZeroBurnAndInputOutputGapAreChecked() throws Exception {
        HistoryRow r=row("0x00");r.burn="0.00";assertTrue(HistoryEvidence.matches(r,tx("0x00")));
        r.burn="1";assertFalse(HistoryEvidence.matches(r,tx("0x00")));
        JSONObject t=tx("0x00");txn(t).getJSONArray("outputs").getJSONObject(1).put("amount","6");
        r.outputs=arr(obj("address",DEST,"amount","3"),obj("address",CHANGE,"amount","6")).toString();
        assertTrue(HistoryEvidence.matches(r,t));
        t.getJSONObject("body").put("burntxn",obj("inputs",arr(coin('8',CHANGE,"1","0x00")),"outputs",arr()));
        assertFalse(HistoryEvidence.matches(r,t));
    }
    @Test public void inclusionRequiresSuccessfulConsistentMatureEvidence() throws Exception {
        assertNotNull(HistoryEvidence.inclusion(inclusion()));
        for(int mode=0;mode<7;mode++) {
            JSONObject j=inclusion(),p=j.getJSONObject("response");
            if(mode==0)j.put("status","true");if(mode==1)p.put("found",false);if(mode==2)p.put("confirmations","2");
            if(mode==3)p.put("tip","200");if(mode==4)p.put("blockid",id('0'));if(mode==5)j.put("pending",true);if(mode==6)p.put("block",100);
            try{HistoryEvidence.inclusion(j);fail("Accepted "+mode);}catch(IllegalArgumentException expected){}
        }
    }
}
