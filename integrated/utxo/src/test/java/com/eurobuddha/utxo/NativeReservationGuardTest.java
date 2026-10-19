package com.eurobuddha.utxo;
import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
/** NFT NativeReservationGuardTest adapted to UTXO's consolidation entry point. */
public class NativeReservationGuardTest {
    @Test public void unresolvedOrUnreadableAttemptsStopNativeSelectionBeforeAnyCommand()throws Exception {
        for(boolean unreadable:new boolean[]{false,true}) {
            MainActivity act=mock(MainActivity.class);HistoryDb history=mock(HistoryDb.class);NodeApi node=mock(NodeApi.class);when(act.history()).thenReturn(history);when(act.node()).thenReturn(node);
            if(unreadable)when(history.hasUnresolvedAttempt("0x00")).thenThrow(new IllegalStateException("public damaged fixture"));else when(history.hasUnresolvedAttempt("0x00")).thenReturn(true);
            WalletTools.Status status=mock(WalletTools.Status.class);WalletTools tools=new WalletTools(act,status);Method merge=WalletTools.class.getDeclaredMethod("autoConsolidate",String.class,WalletTools.Status.class);merge.setAccessible(true);merge.invoke(tools,"0x00",status);
            verify(status).show(any(CharSequence.class),eq(false));verifyNoInteractions(node);verify(history,never()).insertPosting(any());
        }
    }
    @Test public void nativeSelectionAlsoChecksMinimaReservedForACompanionBurn() {
        MainActivity act=mock(MainActivity.class);HistoryDb history=mock(HistoryDb.class);when(act.history()).thenReturn(history);when(history.hasUnresolvedAttempt("0x00")).thenReturn(true);
        assertTrue(TxnUtil.canUseNativeFunds(act,TransactionEvidenceTest.TOKEN,null));assertTrue(TxnUtil.canUseNativeFunds(act,TransactionEvidenceTest.TOKEN,"0"));assertFalse(TxnUtil.canUseNativeFunds(act,TransactionEvidenceTest.TOKEN,"0.001"));assertFalse(TxnUtil.canUseNativeFunds(act,TransactionEvidenceTest.TOKEN,"bad"));
    }
}
