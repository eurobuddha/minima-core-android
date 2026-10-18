package com.eurobuddha.nftwallet;

import java.math.BigDecimal;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.nftwallet.TransactionEvidenceTest.*;

/** Reuses the prepared-send transport fixture; no node, keys or funds are used. */
public class QuickSplitTest {
    @Test public void knownRemaindersAndSmallestUnitStayInTheFinalCoin() {
        assertEquals(Arrays.asList("3.33","3.33","3.34"),PreparedSendEvidence.splitAmounts("10",3,2));
        assertEquals(Arrays.asList("3","3","4"),PreparedSendEvidence.splitAmounts("10",3,0));
        assertEquals(Arrays.asList("1.5","1.5"),PreparedSendEvidence.splitAmounts("3",2,44));
        String unit=BigDecimal.ONE.scaleByPowerOfTen(-44).toPlainString();
        assertEquals(Arrays.asList(unit,unit),PreparedSendEvidence.splitAmounts(new BigDecimal(unit).multiply(new BigDecimal(2)).toPlainString(),2,44));
        assertThrows(RuntimeException.class,()->PreparedSendEvidence.splitAmounts(unit,2,44));
    }
    @Test public void everySupportedCountPreservesExactTotalAndPositiveBoundedOutputs() {
        for(int dec:new int[]{0,2,11,44})for(int count=1;count<=20;count++) {
            List<String> values=PreparedSendEvidence.splitAmounts("99",count,dec);assertEquals(count,values.size());
            BigDecimal sum=BigDecimal.ZERO;
            for(String value:values){BigDecimal n=new BigDecimal(value);assertTrue(n.signum()>0);assertTrue(Util.decimalPlaces(n)<=dec);sum=sum.add(n);}
            assertEquals(0,new BigDecimal("99").compareTo(sum));
        }
        for(int count:new int[]{0,21})assertThrows(RuntimeException.class,()->PreparedSendEvidence.splitAmounts("10",count,2));
        for(int dec:new int[]{-1,45,Integer.MAX_VALUE})assertThrows(RuntimeException.class,()->PreparedSendEvidence.splitAmounts("10",3,dec));
        for(String amount:new String[]{"0","-1","1;send","1e9","0.001"})assertThrows(RuntimeException.class,()->PreparedSendEvidence.splitAmounts(amount,3,2));
    }
    static JSONObject quote(String token,String burn,List<String> values)throws Exception {
        JSONObject q=PreparedSendEvidenceTest.quote(token,burn,false);JSONArray old=txn(q).getJSONArray("outputs"),out=new JSONArray();
        for(int i=0;i<values.size();i++) {
            JSONObject c=copy(old.getJSONObject(0));String value=values.get(i);
            c.put("coinid","0x"+String.format(Locale.ROOT,"%064x",100+i));
            c.put("amount",Util.isMinima(token)?value:new BigDecimal(value).movePointLeft(9).toPlainString());
            c.put("tokenamount",value);out.put(c);
        }
        out.put(old.getJSONObject(1));txn(q).put("outputs",out);return q;
    }
    static PreparedSend run(PreparedSendFlowTest.Flow f,String token,String burn,int count,int decimals)throws Exception {
        when(f.act.tokenDecimals(token)).thenReturn(decimals);
        if(f.quote==null)f.quote=quote(token,PreparedSendEvidence.fee(burn),PreparedSendEvidence.splitAmounts("3",count,Util.isMinima(token)?44:decimals));
        PreparedSend send=new PreparedSend(f.act,DEST,"3",burn,count,token,"Literal 中文",f.done);send.run();return send;
    }
    @Test public void minimaAndTokenSplitsPreserveFeeCompanionAndJournalBeforeSigning()throws Exception {
        for(String token:new String[]{"0x00",TOKEN})for(String burn:new String[]{"0","0.25"})try(PreparedSendFlowTest.Flow f=new PreparedSendFlowTest.Flow()) {
            when(f.history.prepareAttempt(anyString(),anyString(),any())).thenAnswer(i->{assertFalse(f.sent("sendsign "));JSONObject plan=i.getArgument(2);assertEquals(8,plan.getJSONArray("outputs").length());assertEquals(!Util.isMinima(token)&&!"0".equals(burn),plan.has("companion"));return true;});
            run(f,token,burn,7,2).run();
            verify(f.done).onPosted(eq(id('f')),anyList());verify(f.history).markAttempt(anyString(),eq(HistoryDb.POSTED),any());
            String command=f.commands.get(0);assertTrue(command.startsWith("sendnosign multi:["));assertTrue(command.contains(" split:1 burn:"+burn+" file:nft_send_"));
            JSONArray multi=new JSONArray(command.substring("sendnosign multi:".length(),command.indexOf(" tokenid:")));
            assertEquals(7,multi.length());BigDecimal total=BigDecimal.ZERO;
            for(int i=0;i<multi.length();i++){String entry=multi.getString(i);assertTrue(entry.startsWith(DEST+":"));total=total.add(new BigDecimal(entry.substring(DEST.length()+1)));}
            assertEquals(0,new BigDecimal("3").compareTo(total));assertEquals(1,f.commands.stream().filter(c->c.startsWith("sendsign ")).count());
            assertEquals(1,f.commands.stream().filter(c->c.startsWith("sendpost ")).count());
        }
    }
    @Test public void lostRemainderAndUnequalRedistributionCannotReachSigning()throws Exception {
        for(List<String> bad:Arrays.asList(Arrays.asList("0.42","0.42","0.42","0.42","0.42","0.42","0.42"),Arrays.asList("0.41","0.43","0.42","0.42","0.42","0.42","0.48")))try(PreparedSendFlowTest.Flow f=new PreparedSendFlowTest.Flow()) {
            f.quote=quote(TOKEN,"0",bad);run(f,TOKEN,"0",7,2);
            verify(f.done).onFailed(TransactionEvidence.INVALID);assertFalse(f.sent("sendsign "));verify(f.history,never()).prepareAttempt(anyString(),anyString(),any());
        }
    }
    @Test public void unknownPrecisionOrTooSmallSplitStopsBeforeCreatingFilesOrHistory() {
        for(int dec:new int[]{-1,0,45})try(PreparedSendFlowTest.Flow f=new PreparedSendFlowTest.Flow()) {
            when(f.act.tokenDecimals(TOKEN)).thenReturn(dec);
            new PreparedSend(f.act,DEST,"3",null,7,TOKEN,"Token",f.done).run();
            verify(f.done).onFailed(TransactionEvidence.INVALID);assertTrue(f.commands.isEmpty());verify(f.history,never()).insertPosting(any());
        }
    }
    @Test public void quoteChecksUseFrozenPrecisionAfterBalanceMetadataChanges()throws Exception {
        try(PreparedSendFlowTest.Flow f=new PreparedSendFlowTest.Flow()) {
            f.hold="sendview file:nft_send_";run(f,TOKEN,"0.25",7,2);
            when(f.act.tokenDecimals(TOKEN)).thenReturn(0);f.late.response(f.quote);
            verify(f.done).onPosted(eq(id('f')),anyList());
            ArgumentCaptor<JSONObject> plan=ArgumentCaptor.forClass(JSONObject.class);verify(f.history).prepareAttempt(anyString(),anyString(),plan.capture());
            assertEquals("0.48",plan.getValue().getJSONArray("outputs").getJSONObject(6).getString("amount"));
        }
    }
    @Test public void modifiedSignedRemainderCannotBroadcastAndTimeoutCannotRepeatIt()throws Exception {
        try(PreparedSendFlowTest.Flow f=new PreparedSendFlowTest.Flow()) {
            f.signedReply=quote(TOKEN,"0",Arrays.asList("0.41","0.43","0.42","0.42","0.42","0.42","0.48"));run(f,TOKEN,"0",7,2);
            verify(f.done).onFailed(TransactionEvidence.NOT_SENT);assertFalse(f.sent("sendpost "));
        }
        try(PreparedSendFlowTest.Flow f=new PreparedSendFlowTest.Flow()) {
            f.hold="sendpost ";PreparedSend send=run(f,TOKEN,"0.25",7,2);f.timeouts.get(0).run();f.late.response(f.quote);send.run();
            verify(f.done).onUnknown(TransactionEvidence.UNKNOWN);verify(f.done,never()).onPosted(anyString(),any());
            verify(f.history,never()).markAttempt(anyString(),eq(HistoryDb.NOT_SUBMITTED),any());assertEquals(1,f.commands.stream().filter(c->c.startsWith("sendpost ")).count());
        }
    }
}
