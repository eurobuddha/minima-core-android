package com.eurobuddha.nftwallet;
import android.widget.TextView;
import com.eurobuddha.minimacore.i18n.Language;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.lang.reflect.Method;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
/** Exercise actual entry points without a node, seed or signing request. */
public class NativeReservationGuardTest {
    @Test public void unresolvedOrUnreadableCustomAttemptsStopBothNativeEntryPoints()throws Exception {
        for(boolean unreadable:new boolean[]{false,true}) {
            MainActivity act=mock(MainActivity.class);HistoryDb history=mock(HistoryDb.class);NodeApi node=mock(NodeApi.class);when(act.history()).thenReturn(history);when(act.node()).thenReturn(node);
            if(unreadable)when(history.hasUnresolvedAttempt("0x00")).thenThrow(new IllegalStateException("public damaged fixture"));else when(history.hasUnresolvedAttempt("0x00")).thenReturn(true);
            SendView send=mock(SendView.class,CALLS_REAL_METHODS);TextView label=mock(TextView.class);
            TransactionOutcomeCallbacksTest.set(BaseView.class,send,"act",act);TransactionOutcomeCallbacksTest.set(SendView.class,send,"statusView",label);
            try(MockedStatic<Language> language=mockStatic(Language.class,CALLS_REAL_METHODS)) {
                language.when(()->Language.setText(eq(label),any(CharSequence.class))).thenAnswer(i->null);
                Method quick=SendView.class.getDeclaredMethod("runQuickSend",String.class,String.class,String.class,int.class,CharSequence.class,String.class);quick.setAccessible(true);quick.invoke(send,TransactionEvidenceTest.DEST,"1",null,1,"Minima","0x00");
                WalletTools.Status status=mock(WalletTools.Status.class);WalletTools tools=new WalletTools(act,status);Method merge=WalletTools.class.getDeclaredMethod("autoConsolidate",String.class);merge.setAccessible(true);merge.invoke(tools,"0x00");verify(status).show(any(Language.Message.class),eq(false));
            }
            verifyNoInteractions(node);verify(history,never()).insertPosting(any());
        }
    }
    @Test public void nativeTokenSendAlsoChecksMinimaReservedForItsBurn() {
        MainActivity act=mock(MainActivity.class);HistoryDb history=mock(HistoryDb.class);when(act.history()).thenReturn(history);when(history.hasUnresolvedAttempt("0x00")).thenReturn(true);
        assertTrue(TxnUtil.canUseNativeFunds(act,TransactionEvidenceTest.TOKEN,null));assertTrue(TxnUtil.canUseNativeFunds(act,TransactionEvidenceTest.TOKEN,"0"));assertFalse(TxnUtil.canUseNativeFunds(act,TransactionEvidenceTest.TOKEN,"0.001"));assertFalse(TxnUtil.canUseNativeFunds(act,TransactionEvidenceTest.TOKEN,"bad"));
    }
}
