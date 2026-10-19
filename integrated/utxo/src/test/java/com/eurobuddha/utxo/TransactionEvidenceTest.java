package com.eurobuddha.utxo;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static com.eurobuddha.utxo.UncertainWriteTest.object;

public class TransactionEvidenceTest {
    static String id(char c) { return "0x" + String.valueOf(c).repeat(64); }
    static final String DEST=id('a'), CHANGE=id('b'), TOKEN=id('c'), MX="Mx"+"Aa".repeat(24);
    static Coin coin(String token, char id, String amount) {
        Coin c=new Coin(); c.coinid=id(id); c.tokenid=token; c.amount=amount;
        c.rawAmount="0x00".equals(token)?amount:"0.00000001"; c.address=DEST; c.confirmed=c.sendable=true; return c;
    }
    static List<TxnBuilder.Out> outputs(String a,String b) { return Arrays.asList(new TxnBuilder.Out(DEST,a),new TxnBuilder.Out(CHANGE,b)); }
    static JSONObject intent(String token) { return TransactionEvidence.intent(Collections.singletonList(coin(token,'1',"10")),outputs("3","7"),token,null); }
    static JSONObject receipt(JSONObject intent) {
        String token=intent.optString("tokenid"); JSONArray ins=copy(intent).optJSONArray("inputs"),out=new JSONArray();
        for(int i=0;i<intent.optJSONArray("outputs").length();i++) {
            JSONObject want=intent.optJSONArray("outputs").optJSONObject(i);
            out.put(object("coinid",id((char)('3'+i)),"address",want.optString("address"),"tokenid",token,
                    "amount","0x00".equals(token)?want.optString("amount"):new java.math.BigDecimal(want.optString("amount")).movePointLeft(9).toPlainString(),"tokenamount",want.optString("amount")));
        }
        return object("status",true,"response",object("txnpost",true,"txpow",object("txpowid",id('f'),"body",object("txn",object("inputs",ins,"outputs",out)))));
    }
    static JSONObject copy(JSONObject j){try{return new JSONObject(j.toString());}catch(Exception e){throw new AssertionError(e);}}
    static JSONObject txn(JSONObject reply){return reply.optJSONObject("response").optJSONObject("txpow").optJSONObject("body").optJSONObject("txn");}
    static void rejects(Runnable action){try{action.run();fail("Expected rejection");}catch(IllegalArgumentException expected){}}
    @Test public void completeMinimaAndTokenReceiptsMatchAllIntendedOutputs() throws Exception {
        for(String token:new String[]{"0x00",TOKEN}) {JSONObject intent=intent(token);List<TxnBuilder.OutCoin> result=TransactionEvidence.receipt(receipt(intent),intent);
            assertEquals(2,result.size());assertEquals("3",result.get(0).amount);assertEquals("7",result.get(1).amount);
            JSONObject direct=receipt(intent);direct.put("response",direct.getJSONObject("response").getJSONObject("txpow"));assertEquals(2,TransactionEvidence.receipt(direct,intent).size());
        }
    }
    @Test public void missingFailedOrBareAcknowledgementsAreNotReceipts() throws Exception {
        JSONObject intent=intent("0x00");
        for(JSONObject bad:new JSONObject[]{null,object(),object("status",true),object("status","true"),object("status",false),object("status",true,"response",object("txnpost",false,"txpow",receipt(intent).getJSONObject("response").getJSONObject("txpow")))})rejects(()->TransactionEvidence.receipt(bad,intent));
        JSONObject missing=receipt(intent);missing.getJSONObject("response").getJSONObject("txpow").remove("txpowid");rejects(()->TransactionEvidence.receipt(missing,intent));
    }
    @Test public void missingExtraDuplicateOrDifferentInputCannotMatch() throws Exception {
        JSONObject intent=intent("0x00");
        for(int mode=0;mode<4;mode++) {JSONObject reply=receipt(intent);JSONArray inputs=txn(reply).getJSONArray("inputs");
            if(mode==0)inputs.remove(0);if(mode==1)inputs.put(copy(inputs.getJSONObject(0)));if(mode==2)inputs.getJSONObject(0).put("coinid",id('9'));if(mode==3)inputs.getJSONObject(0).put("tokenid",TOKEN);
            rejects(()->TransactionEvidence.receipt(reply,intent));
        }
    }
    @Test public void inputHumanAndRawAmountsMustMatch() throws Exception {
        for(String token:new String[]{"0x00",TOKEN})for(String key:new String[]{"amount","tokenamount"}) {
            if("0x00".equals(token)&&key.equals("tokenamount"))continue;
            JSONObject intent=intent(token),reply=receipt(intent);txn(reply).getJSONArray("inputs").getJSONObject(0).put(key,"9");rejects(()->TransactionEvidence.receipt(reply,intent));
        }
    }
    @Test public void everyOutputNeedsTheRightTokenAmountAndDestination() throws Exception {
        for(String token:new String[]{"0x00",TOKEN})for(String key:new String[]{"tokenid","address","amount"}) {
            JSONObject intent=intent(token),reply=receipt(intent),out=txn(reply).getJSONArray("outputs").getJSONObject(1);
            out.put(key.equals("amount")&&!"0x00".equals(token)?"tokenamount":key,key.equals("amount")?"6":id('9'));
            rejects(()->TransactionEvidence.receipt(reply,intent));
        }
    }
    @Test public void outputCountsAndIdsMustBeCompleteAndDistinct() throws Exception {
        JSONObject intent=intent("0x00");
        for(int mode=0;mode<5;mode++) {JSONObject reply=receipt(intent);JSONArray out=txn(reply).getJSONArray("outputs");
            if(mode==0)out.remove(1);if(mode==1)out.put(copy(out.getJSONObject(0)));if(mode==2)out.getJSONObject(1).put("coinid",out.getJSONObject(0).getString("coinid"));if(mode==3)out.getJSONObject(1).put("coinid",id('1'));if(mode==4)out.getJSONObject(1).put("coinid","0x00");
            rejects(()->TransactionEvidence.receipt(reply,intent));
        }
    }
    @Test public void receiptOrderDoesNotChangeTheIntendedOutputOrder() throws Exception {
        JSONObject intent=intent("0x00"),reply=receipt(intent);JSONArray out=txn(reply).getJSONArray("outputs");JSONObject first=out.getJSONObject(0),second=out.getJSONObject(1);out.put(0,second);out.put(1,first);
        List<TxnBuilder.OutCoin> result=TransactionEvidence.receipt(reply,intent);assertEquals(DEST,result.get(0).address);assertEquals(CHANGE,result.get(1).address);
    }
    @Test public void duplicateDestinationsRequireDistinctMatchingCoins() throws Exception {
        List<TxnBuilder.Out> outs=Arrays.asList(new TxnBuilder.Out(DEST,"5"),new TxnBuilder.Out(DEST,"5"));
        JSONObject intent=TransactionEvidence.intent(Collections.singletonList(coin("0x00",'1',"10")),outs,"0x00",null),reply=receipt(intent);
        assertEquals(2,TransactionEvidence.receipt(reply,intent).size());txn(reply).getJSONArray("outputs").getJSONObject(1).put("amount","4");rejects(()->TransactionEvidence.receipt(reply,intent));
    }
    @Test public void hexCaseCanDifferButMxCaseCannot() throws Exception {
        JSONObject intent=intent("0x00"),reply=receipt(intent);txn(reply).getJSONArray("outputs").getJSONObject(0).put("address","0x"+"A".repeat(64));assertEquals(2,TransactionEvidence.receipt(reply,intent).size());
        intent.getJSONArray("outputs").getJSONObject(0).put("address",MX);JSONObject out=txn(reply).getJSONArray("outputs").getJSONObject(0);out.put("miniaddress",MX);assertEquals(2,TransactionEvidence.receipt(reply,intent).size());out.put("miniaddress",MX.toLowerCase(Locale.ROOT));rejects(()->TransactionEvidence.receipt(reply,intent));
    }
    @Test public void mutableSelectionCannotChangeTheFrozenIntent() {
        Coin coin=coin("0x00",'1',"10");List<Coin> inputs=new ArrayList<>(Collections.singletonList(coin));List<TxnBuilder.Out> outputs=new ArrayList<>(outputs("3","7"));
        JSONObject intent=TransactionEvidence.intent(inputs,outputs,"0x00",null);String before=intent.toString();coin.coinid=id('9');coin.amount="99";inputs.clear();outputs.clear();assertEquals(before,intent.toString());assertEquals(2,TransactionEvidence.receipt(receipt(intent),intent).size());
    }
    @Test public void malformedUnspendableAndMixedTokenSelectionsCannotBuild() {
        for(int mode=0;mode<7;mode++) {Coin c=coin("0x00",'1',"10");if(mode==0)c.coinid="0x00";if(mode==1)c.sendable=false;if(mode==2)c.confirmed=false;if(mode==3)c.tokenid=TOKEN;if(mode==4)c.amount="10;send";if(mode==5)c.rawAmount="9";if(mode==6)c.coinid="0x11 txndelete:true";
            rejects(()->TransactionEvidence.intent(Collections.singletonList(c),outputs("3","7"),"0x00",null));
        }
        Coin c=coin("0x00",'1',"10");rejects(()->TransactionEvidence.intent(Arrays.asList(c,c),outputs("3","17"),"0x00",null));
    }
    @Test public void tokenDisplayAmountsCannotHideAWrongRawValue() throws Exception {
        JSONObject intent=intent(TOKEN),reply=receipt(intent);txn(reply).getJSONArray("outputs").getJSONObject(0).put("amount","0.000000002");rejects(()->TransactionEvidence.receipt(reply,intent));
        Coin first=coin(TOKEN,'1',"10"),second=coin(TOKEN,'2',"10");second.rawAmount="0.00000002";
        rejects(()->TransactionEvidence.intent(Arrays.asList(first,second),outputs("10","10"),TOKEN,null));
        first.rawAmount="0";rejects(()->TransactionEvidence.intent(Collections.singletonList(first),outputs("3","7"),TOKEN,null));
    }
    @Test public void imbalanceAndCommandInjectionAreRejectedBeforeSigning() {
        Coin c=coin("0x00",'1',"10");List<Coin> coins=Collections.singletonList(c);
        rejects(()->TransactionEvidence.intent(coins,outputs("3","6"),"0x00",null));
        rejects(()->TransactionEvidence.intent(coins,outputs("3","8"),"0x00",null));
        rejects(()->TransactionEvidence.intent(coins,Collections.singletonList(new TxnBuilder.Out(DEST+";send","10")),"0x00",null));
        rejects(()->TransactionEvidence.intent(coins,outputs("0","10"),"0x00",null));
        assertNotNull(TransactionEvidence.intent(coins,outputs("3","6.75"),"0x00","0.25"));
    }
}
