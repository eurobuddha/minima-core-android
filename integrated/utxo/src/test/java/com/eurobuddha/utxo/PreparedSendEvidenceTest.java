package com.eurobuddha.utxo;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.eurobuddha.utxo.TransactionEvidenceTest.*;
import static com.eurobuddha.utxo.UncertainWriteTest.object;

public class PreparedSendEvidenceTest {
    static final Set<String> OWN=new HashSet<>(Arrays.asList(CHANGE,id('d')));
    static JSONObject quote(String token,String burn,boolean fullBurn)throws Exception {
        JSONObject mainIntent=TransactionEvidence.intent(Collections.singletonList(coin(token,'1',"10")),outputs("3",Util.isMinima(token)?new java.math.BigDecimal("7").subtract(new java.math.BigDecimal(burn)).toPlainString():"7"),token,Util.isMinima(token)?burn:"0");
        JSONObject reply=receipt(mainIntent);txn(reply).put("transactionid",id('a'));
        txn(reply).put("state",new JSONArray()).put("linkhash","0x00");
        for(int i=0;i<txn(reply).getJSONArray("outputs").length();i++)txn(reply).getJSONArray("outputs").getJSONObject(i).put("storestate",i==0);
        if(!Util.isMinima(token)&&new java.math.BigDecimal(burn).signum()>0) {
            JSONObject burnIn=object("coinid",id('2'),"tokenid","0x00","amount",fullBurn?burn:"2","address",id('d'));
            JSONArray out=new JSONArray();if(!fullBurn)out.put(object("coinid",id('5'),"tokenid","0x00","amount",new java.math.BigDecimal("2").subtract(new java.math.BigDecimal(burn)).toPlainString(),"address",id('d')));
            reply.getJSONObject("response").getJSONObject("txpow").getJSONObject("body").put("burntxn",object("transactionid",id('b'),"linkhash",id('a'),"inputs",new JSONArray().put(burnIn),"outputs",out));
        }
        return reply;
    }
    static JSONObject planned(JSONObject quote,String token,String burn){return PreparedSendEvidence.quick(quote,DEST,"3",token,burn,1,OWN);}
    @Test public void nativeMinimaAndTokenQuotesFreezeBothTransactionsBeforeAnySignature()throws Exception {
        for(String token:new String[]{"0x00",TOKEN})for(String burn:new String[]{"0","0.25"})for(boolean full:new boolean[]{false,true}) {
            JSONObject quote=quote(token,burn,full),intent=planned(quote,token,burn);
            assertEquals(id('a'),intent.getString("transactionid"));assertEquals(!Util.isMinima(token)&&!"0".equals(burn),intent.has("companion"));
            assertEquals(intent.toString(),TransactionEvidence.storedIntent(intent.toString()).toString());assertEquals(2,TransactionEvidence.receipt(quote,intent).size());
            JSONObject publicReceipt=TransactionEvidence.publicReceipt(quote,intent);TransactionEvidence.storedReceipt(publicReceipt,intent);
            assertEquals(intent.has("companion")?2:1,TransactionEvidence.inputIds(intent).size());
        }
    }
    @Test public void burnMismatchMissingCompanionAndUnrequestedBurnAreRejected()throws Exception {
        JSONObject quote=quote(TOKEN,"0.25",false),intent=planned(quote,TOKEN,"0.25");
        for(String field:new String[]{"inputs","outputs","linkhash","transactionid"}) {
            JSONObject bad=copy(quote);bad.getJSONObject("response").getJSONObject("txpow").getJSONObject("body").getJSONObject("burntxn").remove(field);
            assertThrows(RuntimeException.class,()->TransactionEvidence.receipt(bad,intent));
        }
        assertThrows(RuntimeException.class,()->planned(quote,TOKEN,"0"));
        assertThrows(RuntimeException.class,()->planned(quote,TOKEN,"0.5"));
        JSONObject noBurn=quote(TOKEN,"0",false);assertThrows(RuntimeException.class,()->TransactionEvidence.receipt(noBurn,intent));
    }
    @Test public void mainAndBurnCannotReserveOrReturnTheSameCoin()throws Exception {
        JSONObject quote=quote(TOKEN,"0.25",false);
        for(boolean input:new boolean[]{true,false}) {
            JSONObject bad=copy(quote),part=bad.getJSONObject("response").getJSONObject("txpow").getJSONObject("body").getJSONObject("burntxn");
            part.getJSONArray(input?"inputs":"outputs").getJSONObject(0).put("coinid",input?id('1'):id('3'));
            assertThrows(RuntimeException.class,()->planned(bad,TOKEN,"0.25"));
        }
    }
    @Test public void recipientsSplitsAmountsAndChangeOwnershipMustMatchTheRequest()throws Exception {
        JSONObject quote=quote("0x00","0",false);
        assertThrows(RuntimeException.class,()->PreparedSendEvidence.quick(quote,id('e'),"3","0x00",null,1,OWN));
        assertThrows(RuntimeException.class,()->PreparedSendEvidence.quick(quote,DEST,"4","0x00",null,1,OWN));
        assertThrows(RuntimeException.class,()->PreparedSendEvidence.quick(quote,DEST,"3","0x00",null,2,OWN));
        assertThrows(RuntimeException.class,()->PreparedSendEvidence.quick(quote,DEST,"3","0x00",null,1,Collections.emptySet()));
        JSONObject changed=copy(quote);txn(changed).getJSONArray("outputs").getJSONObject(1).put("address",id('e'));assertThrows(RuntimeException.class,()->planned(changed,"0x00","0"));
    }
    @Test public void aValidEqualSplitPreservesEveryCoinAndRejectsUnequalSplits()throws Exception {
        JSONObject quote=quote("0x00","0",false);JSONArray out=txn(quote).getJSONArray("outputs");JSONObject change=out.getJSONObject(1);out.getJSONObject(0).put("amount","1.5");out.put(1,copy(out.getJSONObject(0)).put("coinid",id('6')));out.put(change);
        JSONObject intent=PreparedSendEvidence.quick(quote,DEST,"3","0x00",null,2,OWN);assertEquals(3,intent.getJSONArray("outputs").length());
        out.getJSONObject(0).put("amount","1");out.getJSONObject(1).put("amount","2");assertThrows(RuntimeException.class,()->PreparedSendEvidence.quick(quote,DEST,"3","0x00",null,2,OWN));
    }
    @Test public void changedTransactionIdsCannotPassEvenWhenAmountsStillMatch()throws Exception {
        JSONObject quote=quote(TOKEN,"0.25",false),intent=planned(quote,TOKEN,"0.25");
        JSONObject mainChanged=copy(quote);txn(mainChanged).put("transactionid",id('9'));assertThrows(RuntimeException.class,()->TransactionEvidence.receipt(mainChanged,intent));
        JSONObject burnChanged=copy(quote);burnChanged.getJSONObject("response").getJSONObject("txpow").getJSONObject("body").getJSONObject("burntxn").put("transactionid",id('9'));assertThrows(RuntimeException.class,()->TransactionEvidence.receipt(burnChanged,intent));
    }
    @Test public void storedCompanionReceiptsMustRemainCompleteAndLinked()throws Exception {
        JSONObject quote=quote(TOKEN,"0.25",false),intent=planned(quote,TOKEN,"0.25"),saved=TransactionEvidence.publicReceipt(quote,intent);
        saved.remove("companion");assertThrows(RuntimeException.class,()->TransactionEvidence.storedReceipt(saved,intent));
        JSONObject wrong=TransactionEvidence.publicReceipt(quote,intent);wrong.getJSONObject("companion").put("txpowid",id('9'));assertThrows(RuntimeException.class,()->TransactionEvidence.storedReceipt(wrong,intent));
        JSONObject duplicated=copy(intent);duplicated.getJSONObject("companion").getJSONArray("inputs").getJSONObject(0).put("coinid",id('1'));assertThrows(RuntimeException.class,()->TransactionEvidence.storedIntent(duplicated.toString()));
        JSONObject nested=copy(intent);nested.getJSONObject("companion").put("companion",copy(intent.getJSONObject("companion")));assertThrows(RuntimeException.class,()->TransactionEvidence.storedIntent(nested.toString()));
    }
    @Test public void onlyExactDefaultWalletScriptsEstablishChangeOwnership() {
        JSONObject script=object("status",true,"response",object("address",CHANGE,"default",true,"simple",true,"publickey",id('e')));
        assertTrue(PreparedSendEvidence.defaultAddress(script,CHANGE));assertFalse(PreparedSendEvidence.defaultAddress(script,DEST));
        for(String field:new String[]{"default","simple","publickey","address"}){JSONObject bad=copy(script);bad.optJSONObject("response").remove(field);assertFalse(PreparedSendEvidence.defaultAddress(bad,CHANGE));}
        assertFalse(PreparedSendEvidence.defaultAddress(object("status",true,"response",object("relevant",true,"simple",true,"address",CHANGE)),CHANGE));
    }
    @Test public void nativeConsolidationKeepsItsTwoOutputsAndRejectsExternalDestinations()throws Exception {
        JSONObject quote=quote("0x00","0",false);txn(quote).getJSONArray("outputs").getJSONObject(0).put("address",CHANGE);
        JSONObject intent=PreparedSendEvidence.consolidation(quote,"0x00",OWN);assertEquals(2,intent.getJSONArray("outputs").length());
        assertThrows(RuntimeException.class,()->PreparedSendEvidence.consolidation(quote,"0x00",Collections.emptySet()));assertThrows(RuntimeException.class,()->PreparedSendEvidence.consolidation(quote,TOKEN,OWN));
    }
}
