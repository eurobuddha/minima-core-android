package com.eurobuddha.utxo;

import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import org.mockito.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.utxo.TransactionEvidenceTest.*;
import static com.eurobuddha.utxo.UncertainWriteTest.object;

/** Delayed real controller callbacks retain history effects but cannot replace a newer review's status. */
public class ToolStatusOwnershipTest {
    static WalletTools.Status status(ToolReviewTest.Flow f,Object review)throws Exception {Field p=f.type.getDeclaredField("status");p.setAccessible(true);return(WalletTools.Status)p.get(review);}
    static List<Coin> coins(ToolReviewTest.Flow f,Object review)throws Exception {Field p=f.type.getDeclaredField("coins");p.setAccessible(true);return(List<Coin>)p.get(review);}
    static void accept(ToolReviewTest.Flow f,Object review)throws Exception {Method m=f.type.getDeclaredMethod("accept",boolean.class);m.setAccessible(true);assertEquals(true,m.invoke(review,false));}
    static void newerStatus(ToolReviewTest.Flow f)throws Exception {f.selected=Collections.singletonList(coin("0x00",'9',"10"));f.invoke("doSplit",f.review(),"invalid");clearInvocations(f.status);}
    @Test public void oldBuilderOutcomesKeepHistoryButCannotReplaceNewerStatus()throws Exception {
        for(String outcome:new String[]{HistoryDb.STATUS_POSTED,HistoryDb.STATUS_ERROR,HistoryDb.STATUS_UNKNOWN})try(ToolReviewTest.Flow f=new ToolReviewTest.Flow()) {
            f.invoke("doSplit",f.review(),"2");f.addresses();TxnBuilder.Done old=f.result();newerStatus(f);
            if(HistoryDb.STATUS_POSTED.equals(outcome))old.onPosted(id('f'),Collections.emptyList());
            else if(HistoryDb.STATUS_ERROR.equals(outcome))old.onFailed("old failure");else old.onUnknown(TransactionEvidence.UNKNOWN);
            verifyNoInteractions(f.status);verify(f.history).update(anyString(),eq(outcome),isNull(),anyString());verify(f.act,never()).clearSelection();
        }
    }
    @Test public void oldLookupErrorsDoNotReplaceNewerValidationAndDistributionStillReleases()throws Exception {
        for(String operation:new String[]{"doSplit","mergeSelected","doDistribute"})try(ToolReviewTest.Flow f=new ToolReviewTest.Flow()) {
            Object reviewed=f.review();if(operation.equals("doSplit"))f.invoke(operation,reviewed,"2");else if(operation.equals("doDistribute"))f.invoke(operation,reviewed,"2","1");else f.invoke(operation,reviewed);
            newerStatus(f);f.replies.remove(0).response(object("status",false,"error","old failure"));verifyNoInteractions(f.status);assertTrue(f.built.isEmpty());
            if(operation.equals("doDistribute"))verify(f.manager).cancelReserve();
        }
    }
    @Test public void oldChangedSelectionWarningCannotReplaceNewerStatus()throws Exception {
        try(ToolReviewTest.Flow f=new ToolReviewTest.Flow()){f.invoke("doDistribute",f.review(),"2","1");newerStatus(f);f.addresses();verifyNoInteractions(f.status);verify(f.manager).cancelReserve();verify(f.manager,never()).start(any(),any(),any(),any(),any());}
    }
    @Test public void openingThenCancellingAnotherReviewDoesNotStealTheRunningStatus()throws Exception {
        try(ToolReviewTest.Flow f=new ToolReviewTest.Flow()){f.invoke("doSplit",f.review(),"2");f.addresses();f.review();clearInvocations(f.status);f.result().onFailed("old failure");verify(f.status).show(any(),eq(false));}
    }
    @Test public void nativeConsolidationUsesTheSameCapturedStatusOwner()throws Exception {
        try(ToolReviewTest.Flow f=new ToolReviewTest.Flow()) {
            List<TxnBuilder.Done> results=new ArrayList<>();
            try(MockedConstruction<PreparedConsolidation> builders=mockConstruction(PreparedConsolidation.class,(builder,c)->results.add((TxnBuilder.Done)c.arguments().get(3)))) {
                Object review=f.review();accept(f,review);Method start=WalletTools.class.getDeclaredMethod("autoConsolidate",String.class,WalletTools.Status.class);start.setAccessible(true);start.invoke(f.tools,"0x00",status(f,review));assertEquals(1,results.size());
                newerStatus(f);results.get(0).onPosted(id('f'),Collections.emptyList());verifyNoInteractions(f.status);verify(f.history).update(anyString(),eq(HistoryDb.STATUS_POSTED),isNull(),eq(""));verify(f.act).reload();
            }
        }
    }
    @Test public void untrackProgressAndCompletionRetainTheirOriginalStatusOwner()throws Exception {
        try(ToolReviewTest.Flow f=new ToolReviewTest.Flow()) {
            Object review=f.review();accept(f,review);Method next=WalletTools.class.getDeclaredMethod("untrackNext",List.class,int.class,WalletTools.Status.class);next.setAccessible(true);next.invoke(f.tools,coins(f,review),0,status(f,review));
            newerStatus(f);f.addresses();verifyNoInteractions(f.status);assertEquals(Arrays.asList("cointrack enable:false coinid:"+id('1'),"cointrack enable:false coinid:"+id('2')),f.commands);verify(f.act,never()).clearSelection();verify(f.act).reload();
        }
    }
    @Test public void closedHostsSuppressStatusWithoutLosingTheRecordedOutcome()throws Exception {
        try(ToolReviewTest.Flow f=new ToolReviewTest.Flow()){f.invoke("doSplit",f.review(),"2");f.addresses();when(f.act.isDestroyed()).thenReturn(true);clearInvocations(f.status);f.result().onFailed("closed");verifyNoInteractions(f.status);verify(f.history).update(anyString(),eq(HistoryDb.STATUS_ERROR),isNull(),eq("closed"));}
    }
}
