package com.eurobuddha.nftwallet;
import com.eurobuddha.minimaapi.MinimaAPIListener;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.nftwallet.TransactionEvidenceTest.*;
import static com.eurobuddha.nftwallet.BurialEvidenceTest.object;

/** Same real NodeApi/mock transport fixture as Quick Send; no node or signing runs. */
public class PreparedConsolidationTest {
    static JSONObject txn(JSONObject reply){return TransactionEvidence.txpow(reply).optJSONObject("body").optJSONObject("txn");}
    static JSONObject quote(boolean remainder)throws Exception {
        JSONArray ins=new JSONArray();for(int i=0;i<3;i++)ins.put(object("coinid",id((char)('1'+i)),"tokenid","0x00","address",CHANGE,"amount",i==2?"7":String.valueOf(i+1)));
        JSONArray outs=new JSONArray();String half=remainder?"1.49999999999999999999":"1.5";
        outs.put(object("coinid",id('4'),"tokenid","0x00","address",CHANGE,"amount",half,"storestate",true));
        outs.put(object("coinid",id('5'),"tokenid","0x00","address",CHANGE,"amount",half,"storestate",true));
        if(remainder)outs.put(object("coinid",id('6'),"tokenid","0x00","address",CHANGE,"amount","0.00000000000000000002","storestate",true));
        outs.put(object("coinid",id('7'),"tokenid","0x00","address",DEST,"amount","7","storestate",false));
        return object("status",true,"response",object("txpowid",id('f'),"body",object("txn",object("transactionid",id('a'),"linkhash","0x00","state",new JSONArray(),"inputs",ins,"outputs",outs))));
    }
    static class Flow extends PreparedSendFlowTest.Flow {
        boolean rejectOwnership;JSONObject captured;
        Flow(boolean remainder)throws Exception {
            quote=PreparedConsolidationTest.quote(remainder);
            doAnswer(i->{String command=i.getArgument(0);commands.add(command);
                if(!hold.isEmpty()&&command.startsWith(hold)){late=i.getArgument(1);return null;}
                JSONObject reply=object("status",true);
                if(!reject.isEmpty()&&command.startsWith(reject))reply=rejection;
                else if(command.startsWith("consolidate "))reply=quote;
                else if(command.startsWith("scripts address:"))reply=object("status",true,"response",object("address",command.substring("scripts address:".length()),"default",!rejectOwnership,"simple",true,"publickey",id('e')));
                else if(command.startsWith("txnsign "))reply=postReply==null?quote:postReply;
                ((MinimaAPIListener)i.getArgument(1)).response(reply);return null;
            }).when(clients.constructed().get(0)).Command(anyString(),any());
            when(history.prepareAttempt(anyString(),anyString(),any())).thenAnswer(i->{captured=i.getArgument(2);assertFalse(sent("txncreate "));return true;});
        }
        PreparedConsolidation runPlan(){PreparedConsolidation c=new PreparedConsolidation(act,"0x00","public-consolidation",done);c.run();return c;}
    }
    @Test public void nativeSelectionRemainderAndChangeFlagsAreReservedBeforeBuilding()throws Exception {
        for(boolean remainder:new boolean[]{false,true})try(Flow f=new Flow(remainder)){
            when(f.history.markAttempt(anyString(),eq(HistoryDb.POSTING),any())).thenAnswer(i->{assertFalse(f.sent("txnsign "));return true;});
            PreparedConsolidation c=f.runPlan();c.run();verify(f.done).onPosted(eq(id('f')),argThat(v->v.size()==(remainder?4:3)));
            assertEquals("consolidate tokenid:0x00 burn:0 maxcoins:20 maxsigs:5 dryrun:true",f.commands.get(0));
            assertEquals(1,f.commands.stream().filter(x->x.startsWith("consolidate ")).count());
            assertEquals(3,f.commands.stream().filter(x->x.startsWith("txninput ")).count());
            assertEquals(remainder?4:3,f.commands.stream().filter(x->x.startsWith("txnoutput ")).count());
            assertEquals(1,f.commands.stream().filter(x->x.startsWith("txnoutput ")&&x.endsWith("storestate:false")).count());
            assertEquals(remainder?3:2,f.commands.stream().filter(x->x.startsWith("txnoutput ")&&x.endsWith("storestate:true")).count());
            assertEquals(1,f.commands.stream().filter(x->x.startsWith("txnsign ")).count());
            assertEquals(3,TransactionEvidence.inputIds(f.captured).size());assertFalse(f.captured.has("transactionid"));
            assertEquals(f.captured.toString(),TransactionEvidence.storedIntent(f.captured.toString()).toString());
            JSONObject saved=TransactionEvidence.publicReceipt(f.quote,f.captured);TransactionEvidence.storedReceipt(saved,f.captured);
        }
    }
    @Test public void reservationOrPostingCheckpointFailureNeverSigns()throws Exception {
        for(boolean reserve:new boolean[]{true,false})try(Flow f=new Flow(false)){
            if(reserve)when(f.history.prepareAttempt(anyString(),anyString(),any())).thenReturn(false);
            else when(f.history.markAttempt(anyString(),eq(HistoryDb.POSTING),any())).thenReturn(false);
            f.runPlan();assertFalse(f.sent("txnsign "));if(reserve){assertFalse(f.sent("txncreate "));verify(f.done).onFailed(TransactionEvidence.RECORD_FAILED);}else verify(f.done).onUnknown(TransactionEvidence.UNKNOWN);
        }
    }
    @Test public void nonMinimaPlansCannotReachTheDryRunOrBurnSigning()throws Exception {
        try(Flow f=new Flow(false)){new PreparedConsolidation(f.act,TOKEN,"public",f.done).run();assertTrue(f.commands.isEmpty());verify(f.done).onFailed(TransactionEvidence.INVALID);}
    }
    @Test public void unknownPlanAndOwnershipQueriesNeverSignAndIgnoreLateReplies()throws Exception {
        for(String prefix:new String[]{"consolidate ","scripts address:"})try(Flow f=new Flow(false)){
            f.hold=prefix;f.runPlan();f.timeouts.get(0).run();f.late.response(f.quote);
            verify(f.done).onFailed(TransactionEvidence.NOT_SENT);verify(f.done,never()).onUnknown(anyString());assertFalse(f.sent("txncreate "));
        }
    }
    @Test public void watchedOutputsAndNonemptyStateOrUnexpectedBurnAreRefused()throws Exception {
        for(int mode=0;mode<5;mode++)try(Flow f=new Flow(false)){
            JSONObject txn=txn(f.quote);
            if(mode==0)f.rejectOwnership=true;
            else if(mode==1)txn.getJSONArray("state").put(object("port",0,"data","public"));
            else if(mode==2)txn.put("linkhash",id('e'));
            else if(mode==3)txn.getJSONArray("outputs").getJSONObject(0).put("amount","1.4");
            else txn.getJSONArray("outputs").getJSONObject(0).remove("storestate");
            f.runPlan();verify(f.done).onFailed(TransactionEvidence.INVALID);assertFalse(f.sent("txncreate "));
        }
    }
    @Test public void anUncertainPostRetainsItsReservationAndCannotRepeat()throws Exception {
        try(Flow f=new Flow(false)){
            f.hold="txnsign ";PreparedConsolidation c=f.runPlan();f.timeouts.get(0).run();f.late.response(f.quote);c.run();
            verify(f.done).onUnknown(TransactionEvidence.UNKNOWN);verify(f.done,never()).onFailed(anyString());verify(f.done,never()).onPosted(anyString(),any());
            verify(f.history,never()).markAttempt(anyString(),eq(HistoryDb.NOT_SUBMITTED),any());assertFalse(f.sent("txndelete "));assertEquals(1,f.commands.stream().filter(x->x.startsWith("txnsign ")).count());
        }
    }
    @Test public void changedOutputFlagsOrIncompleteReceiptCannotSettleTheAttempt()throws Exception {
        for(int mode=0;mode<3;mode++)try(Flow f=new Flow(false)){
            f.postReply=mode==2?object("status",true):copy(f.quote);
            if(mode==0)txn(f.postReply).getJSONArray("outputs").getJSONObject(0).put("storestate",false);
            if(mode==1)txn(f.postReply).getJSONArray("outputs").getJSONObject(0).remove("storestate");
            f.runPlan();verify(f.done).onUnknown(TransactionEvidence.UNKNOWN);verify(f.done,never()).onPosted(anyString(),any());
        }
    }
    @Test public void ownershipWaitUsesAFrozenCopyOfTheNativeReply()throws Exception {
        try(Flow f=new Flow(false)){
            f.hold="scripts address:";f.runPlan();JSONObject original=copy(f.quote);txn(f.quote).getJSONArray("outputs").getJSONObject(0).put("amount","999");f.postReply=original;
            String address=f.commands.get(f.commands.size()-1).substring("scripts address:".length());f.hold="";
            f.late.response(object("status",true,"response",object("address",address,"default",true,"simple",true,"publickey",id('e'))));
            verify(f.done).onPosted(anyString(),any());assertEquals("1.5",f.captured.getJSONArray("outputs").getJSONObject(0).getString("amount"));
        }
    }
    @Test public void storedStateFlagsRejectMissingMalformedAndChangedEvidence()throws Exception {
        JSONObject quote=quote(false),intent=PreparedSendEvidence.consolidation(quote,"0x00",new HashSet<>(Arrays.asList(DEST,CHANGE)));
        for(Object flag:new Object[]{"true",false,JSONObject.NULL}){
            JSONObject bad=copy(intent);bad.getJSONArray("outputs").getJSONObject(0).put("storestate",flag);
            if(flag instanceof Boolean){JSONObject saved=TransactionEvidence.publicReceipt(quote,intent);saved.getJSONArray("outputs").getJSONObject(0).put("storestate",flag);assertThrows(RuntimeException.class,()->TransactionEvidence.storedReceipt(saved,intent));}
            else assertThrows(RuntimeException.class,()->TransactionEvidence.storedIntent(bad.toString()));
        }
        JSONObject missing=TransactionEvidence.publicReceipt(quote,intent);missing.getJSONArray("outputs").getJSONObject(0).remove("storestate");assertThrows(RuntimeException.class,()->TransactionEvidence.storedReceipt(missing,intent));
    }
}
