package com.eurobuddha.utxo;

import android.widget.LinearLayout;
import android.widget.TextView;
import com.eurobuddha.minimacore.i18n.Language;
import org.json.JSONObject;
import org.junit.*;
import org.mockito.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Synthetic node evidence and the real display callback; never modifies a wallet. */
public class HistoryChainCheckTest {
    MainActivity act;NodeApi node;LinearLayout parent;List<NodeApi.Cb> replies;
    MockedStatic<Language> lang;MockedStatic<Design> design;MockedConstruction<TextView> labels;
    static String id(char c){return "0x"+String.valueOf(c).repeat(64);}
    static JSONObject object(Object... values)throws Exception{JSONObject o=new JSONObject();for(int i=0;i<values.length;i+=2)o.put((String)values[i],values[i+1]);return o;}
    static JSONObject proof(int depth)throws Exception{return object("status",true,"response",object("found",true,"blockid",id('b'),"block","100","tip",Integer.toString(100+depth),"confirmations",Integer.toString(depth)));}
    @Before public void setup(){
        act=mock(MainActivity.class);node=mock(NodeApi.class);parent=mock(LinearLayout.class);replies=new ArrayList<>();
        when(act.node()).thenReturn(node);doAnswer(i->{replies.add(i.getArgument(1));return null;}).when(node).cmd(anyString(),any());
        lang=mockStatic(Language.class);design=mockStatic(Design.class);labels=mockConstruction(TextView.class);
    }
    @After public void close(){labels.close();design.close();lang.close();verify(act,never()).history();}
    @Test public void youngInclusionCanBeDisplayedButCannotSettleAnAttempt()throws Exception {
        for(int depth=0;depth<4;depth++){
            assertEquals(Integer.toString(depth),HistoryChainCheck.result(proof(depth)).proof.getString("confirmations"));
            if(depth<3){try{TransactionEvidence.inclusion(proof(depth));fail("Immature settlement");}catch(IllegalArgumentException expected){}}
            else assertNotNull(TransactionEvidence.inclusion(proof(depth)));
        }
    }
    @Test public void fullConsistentTypedEvidenceIsRequired()throws Exception {
        for(String key:new String[]{"found","blockid","block","tip","confirmations"}){
            JSONObject p=proof(3);p.getJSONObject("response").remove(key);invalid(p);
        }
        for(Object value:new Object[]{3,"-1","3.0",true,"3;send",JSONObject.NULL}){
            JSONObject p=proof(3);p.getJSONObject("response").put("confirmations",value);invalid(p);
        }
        JSONObject p=proof(3);p.getJSONObject("response").put("tip","104");invalid(p);
        p=proof(3);p.getJSONObject("response").put("blockid",id('0'));invalid(p);
        p=proof(3);p.put("status","true");invalid(p);invalid(null);
    }
    void invalid(JSONObject p){try{HistoryChainCheck.result(p);fail("Malformed evidence accepted");}catch(IllegalArgumentException expected){}}
    @Test public void typedAbsenceIsDistinctFromUnavailableOrMalformedEvidence()throws Exception {
        assertTrue(HistoryChainCheck.result(object("status",true,"response",object("found",false))).missing);
        invalid(object("status",false,"response",object("found",false)));
        invalid(object("status",true,"response",object("found","false")));
    }
    @Test public void everyOpeningRequestsFreshEvidenceEvenForAPreviouslyConfirmedId()throws Exception {
        HistoryChainCheck.show(act,parent,id('a'));replies.remove(0).onResult(proof(3));
        lang.verify(()->Language.msg(l10n_explorer_on_chain,"100","3"));
        HistoryChainCheck.show(act,parent,id('a'));replies.remove(0).onResult(object("status",true,"response",object("found",false)));
        verify(node,times(2)).cmd(eq("txpow onchain:"+id('a')),any());
        lang.verify(()->Language.msg(l10n_pools_not_found_node));lang.verify(()->Language.msg(l10n_nft_history_unverified_status));
        lang.verify(()->Language.msg(eq(l10n_pools_last_node_check),any()),times(2));
    }
    @Test public void unavailableOrPartialRepliesNeverDisplayConfirmation()throws Exception {
        for(JSONObject p:new JSONObject[]{null,object("status",true,"response",object("found",true,"confirmations","999"))}){
            HistoryChainCheck.show(act,parent,id('a'));replies.remove(0).onResult(p);
        }
        HistoryChainCheck.show(act,parent,id('a'));replies.remove(0).onError("private transport details");
        lang.verify(()->Language.msg(l10n_nft_history_unverified_status),times(3));
        lang.verify(()->Language.msg(eq(l10n_explorer_on_chain),any(),any()),never());
        lang.verify(()->Language.msg(eq(l10n_pools_last_node_check),any()),never());
    }
    @Test public void malformedOrLocalIdsNeverBecomeCommands(){
        for(String id:new String[]{null,"", "uw_local","0x12;send",id('0')})HistoryChainCheck.show(act,parent,id);
        verifyNoInteractions(node);lang.verify(()->Language.msg(l10n_nft_history_unverified_status),times(5));
    }
    @Test public void duplicateCallbackCannotReplaceItsFirstOutcome()throws Exception {
        HistoryChainCheck.show(act,parent,id('a'));NodeApi.Cb cb=replies.remove(0);cb.onError("offline");cb.onResult(proof(3));
        lang.verify(()->Language.msg(l10n_nft_history_unverified_status));lang.verify(()->Language.msg(eq(l10n_explorer_on_chain),any(),any()),never());
    }
    @Test public void closedHostIgnoresLateResults()throws Exception {
        HistoryChainCheck.show(act,parent,id('a'));when(act.isFinishing()).thenReturn(true);replies.remove(0).onResult(proof(3));
        lang.verify(()->Language.msg(eq(l10n_explorer_on_chain),any(),any()),never());
    }
}
