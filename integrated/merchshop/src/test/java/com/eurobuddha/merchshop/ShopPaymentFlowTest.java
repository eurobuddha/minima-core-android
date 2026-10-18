package com.eurobuddha.merchshop;
import android.os.Handler;
import com.eurobuddha.merchshop.integratedcomms.NodeApi;
import org.json.*;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import static com.eurobuddha.merchshop.PaymentJournal.*;
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class) @org.robolectric.annotation.Config(sdk=33,manifest=org.robolectric.annotation.Config.NONE)
public class ShopPaymentFlowTest extends ShopPaymentFixture {
    NodeApi node;Handler ui;PaymentFlow.Listener listener;List<String> commands;ArrayDeque<Runnable> delayed;
    JSONObject posted,signed,history,chain;boolean active=true,postError;String hold="",preMined="";NodeApi.Cb pending;
    @Before public void flowSetup()throws Exception {
        node=mock(NodeApi.class);ui=mock(Handler.class);listener=mock(PaymentFlow.Listener.class);commands=new ArrayList<>();delayed=new ArrayDeque<>();posted=quote();signed=quote();history=object("status",true,"response",object("txpows",new JSONArray()));chain=included();
        when(ui.post(any(Runnable.class))).thenAnswer(c->{((Runnable)c.getArgument(0)).run();return true;});when(ui.postDelayed(any(Runnable.class),anyLong())).thenAnswer(c->{delayed.add(c.getArgument(0));return true;});
        doAnswer(c->{String command=c.getArgument(0);NodeApi.Cb cb=c.getArgument(1);commands.add(command);
            if(!hold.isEmpty()&&command.startsWith(hold)){pending=cb;return null;}
            if(command.startsWith("sendnosign "))cb.onResult(object("status",true));
            else if(command.startsWith("sendview file:ms_pay_"))cb.onResult(quote());
            else if(command.startsWith("scripts address:"))cb.onResult(object("status",true,"response",object("address",command.substring("scripts address:".length()),"simple",true,"default",true,"publickey",TX)));
            else if(command.startsWith("sendsign ")){assertEquals(PREPARED,plainPhase());cb.onResult(object("status",true,"response",object("txpow","/public/signedtransaction-123.txn")));}
            else if(command.startsWith("sendview file:signedtransaction-"))cb.onResult(signed);
            else if(command.startsWith("sendpost ")){assertEquals(POSTING,plainPhase());if(postError)cb.onError("timeout");else cb.onResult(posted);}
            else if(command.startsWith("txpow onchain:"))cb.onResult(command.equals("txpow onchain:"+preMined)?object("status",true,"response",object("found",false)):chain);
            else if(command.startsWith("history "))cb.onResult(history);
            else fail(command);return null;
        }).when(node).cmd(anyString(),any(NodeApi.Cb.class));
    }
    void start(){new PaymentFlow(node,plain,Runnable::run,ui,()->active,listener).start(order);}
    long count(String prefix){return commands.stream().filter(c->c.startsWith(prefix)).count();}
    @Test public void exactNativeSendPersistsBeforeSigningAndBeforePostingAndWaitsForDepth(){start();assertEquals(1,count("sendsign "));assertEquals(1,count("sendpost "));assertEquals(CONFIRMED,plainPhase());verify(listener).confirmed();assertEquals(0,count("send "));}
    @Test public void pendingBareAcknowledgementNeverCompletesAndRestartDoesNotSendAgain()throws Exception {
        posted=object("status",true,"pending",true);start();assertEquals(UNKNOWN,plainPhase());verify(listener,never()).confirmed();
        plain.close();plain=new PaymentJournal(db);commands.clear();listener=mock(PaymentFlow.Listener.class);history=object("status",true,"response",object("txpows",new JSONArray().put(quote().getJSONObject("response"))));start();
        assertEquals(0,count("sendsign "));assertEquals(0,count("sendpost "));verify(listener).confirmed();
    }
    @Test public void minedHeaderCanDifferFromReturnedPreMiningId()throws Exception {
        preMined=TX;JSONObject mined=quote().getJSONObject("response");mined.put("txpowid",MINED);history=object("status",true,"response",object("txpows",new JSONArray().put(mined)));start();
        assertEquals(CONFIRMED,plainPhase());assertEquals(MINED,plain.load(KEY,order).getJSONObject("receipt").getString("txpowid"));assertEquals(1,count("sendpost "));verify(listener).confirmed();
    }
    @Test public void failedIntentAndPostingWritesNeverBroadcast() {
        db.getWritableDatabase().execSQL("CREATE TRIGGER deny_insert BEFORE INSERT ON shop_payment BEGIN SELECT RAISE(ABORT,'fixture'); END");start();assertEquals(0,count("sendsign "));assertEquals(0,count("sendpost "));db.getWritableDatabase().execSQL("DROP TRIGGER deny_insert");commands.clear();listener=mock(PaymentFlow.Listener.class);
        db.getWritableDatabase().execSQL("CREATE TRIGGER deny_update BEFORE UPDATE ON shop_payment BEGIN SELECT RAISE(ABORT,'fixture'); END");start();assertEquals(1,count("sendsign "));assertEquals(0,count("sendpost "));assertEquals(PREPARED,plainPhase());
    }
    @Test public void changedSignedReferenceStopsBeforePost()throws Exception {txn(signed).getJSONArray("state").getJSONObject(0).put("data","0x00");start();assertEquals(0,count("sendpost "));assertEquals(NOT_SUBMITTED,plainPhase());verify(listener,never()).confirmed();}
    @Test public void lostPostCallbackAndClosedHostStayReservedUntilReadOnlyRecovery()throws Exception {
        hold="sendpost ";start();assertEquals(POSTING,plainPhase());assertNotNull(pending);active=false;plain.close();pending.onResult(quote());assertEquals(POSTING,plainPhase());
        active=true;hold="";plain=new PaymentJournal(db);commands.clear();listener=mock(PaymentFlow.Listener.class);history=object("status",true,"response",object("txpows",new JSONArray().put(quote().getJSONObject("response"))));start();assertEquals(0,count("sendnosign "));verify(listener).confirmed();
    }
    @Test public void postErrorKeepsReservationAndHistoryScanIsBounded() {
        postError=true;history=object("status",true,"response",object("txpows",new JSONArray().put(object()).put(object()).put(object()).put(object())));start();assertEquals(UNKNOWN,plainPhase());assertEquals(4,count("history "));
        for(int i=0;i<20;i++){assertEquals(1,delayed.size());delayed.remove().run();}assertEquals(80,count("history "));assertEquals(1,count("sendpost "));verify(listener).unconfirmed();verify(listener,never()).confirmed();
    }
    @Test public void shallowChainProofDoesNotCompleteOrReleaseThePostedAttempt()throws Exception {chain.getJSONObject("response").put("tip","101").put("confirmations","1");start();assertEquals(POSTED,plainPhase());assertEquals(1,delayed.size());verify(listener,never()).confirmed();assertThrows(RuntimeException.class,this::preparePlain);}
    @Test public void minimaPaymentUsesTheSameExactEvidenceAndConfirmationPath()throws Exception {
        db.getWritableDatabase().execSQL("UPDATE orders SET tokenid='0x00'");order=plain.paymentOrder(KEY);plain.orderSent(KEY);posted=quote();signed=quote();start();assertEquals(CONFIRMED,plainPhase());verify(listener).confirmed();
    }
    @Test public void aLegacyUncertainPaymentNeverStartsASecondNativeSend(){db.setMeta("paymentready:"+KEY,"");start();assertEquals(0,count("sendnosign "));assertEquals(0,count("sendpost "));verify(listener).unconfirmed();}

    @Test public void aChangedPaidStatusBeforePostCannotSendAgain()throws Exception {
        hold="sendview file:signedtransaction-";start();db.getWritableDatabase().execSQL("UPDATE orders SET paid=1");pending.onResult(signed);
        assertEquals(0,count("sendpost "));assertEquals(NOT_SUBMITTED,plainPhase());verify(listener).unconfirmed();
    }
    @Test public void completedPaymentDiscardsOnlyItsVerifiedFilesAndUnknownRetainsThem()throws Exception {
        start();verify(node).discardPreparedFile(matches("ms_pay_[0-9a-f]{32}\\.txn"));verify(node).discardPreparedFile("signedtransaction-123.txn");
    }
    @Test public void uncertainPostKeepsItsFilesForRecovery()throws Exception {
        postError=true;start();verify(node,never()).discardPreparedFile(anyString());
    }
    @Test public void anAlreadyPaidOrderCannotStartAnotherPreparation(){db.getWritableDatabase().execSQL("UPDATE orders SET paid=1");order=plain.paymentOrder(KEY);start();assertEquals(0,count("sendnosign "));assertEquals(0,count("sendpost "));verify(listener).unconfirmed();}
}
