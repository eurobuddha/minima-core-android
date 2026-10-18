package com.eurobuddha.nftwallet;

import android.widget.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import org.mockito.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class TransactionOutcomeCallbacksTest {
    static void set(Class<?> type,Object target,String name,Object value)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    @Test public void toolsKeepUnknownDistinctFromFailureAndDoNotClearAnotherSelection()throws Exception {
        MainActivity act=mock(MainActivity.class);HistoryDb db=mock(HistoryDb.class);when(act.history()).thenReturn(db);when(db.insertPosting(any())).thenReturn(true);
        WalletTools.Status status=mock(WalletTools.Status.class);List<TxnBuilder.Done> callbacks=new ArrayList<>();
        try(MockedConstruction<TxnBuilder> builders=mockConstruction(TxnBuilder.class,(b,c)->{callbacks.add((TxnBuilder.Done)c.arguments().get(4));when(b.record(any())).thenReturn(b);})) {
            WalletTools tools=new WalletTools(act,status);Method run=WalletTools.class.getDeclaredMethod("runBuilder",List.class,List.class,String.class,String.class,CharSequence.class,WalletTools.Status.class);run.setAccessible(true);
            run.invoke(tools,Collections.emptyList(),Collections.emptyList(),"0x00","public-tools","Split",status);callbacks.get(0).onUnknown(TransactionEvidence.UNKNOWN);
            verify(db).update("public-tools",HistoryDb.STATUS_UNKNOWN,null,TransactionEvidence.UNKNOWN);
            verify(db,never()).update(anyString(),eq(HistoryDb.STATUS_ERROR),any(),any());verify(act,never()).clearSelection();verify(act).reload();
            verify(status).show(any(Language.Message.class),eq(false));
        }
    }
    @Test public void sendKeepsTheDraftAndRecordsUnknownWithoutReportingFailure()throws Exception {
        MainActivity act=mock(MainActivity.class);HistoryDb db=mock(HistoryDb.class);when(act.history()).thenReturn(db);when(db.insertPosting(any())).thenReturn(true);
        SendView view=mock(SendView.class,CALLS_REAL_METHODS);Button preview=mock(Button.class);TextView label=mock(TextView.class);
        set(BaseView.class,view,"act",act);set(SendView.class,view,"previewBtn",preview);set(SendView.class,view,"statusView",label);
        String[] draft={"public-recipient","3","public-change","0","1","c","0x00"};doReturn(draft).when(view).fieldValues();when(act.selectedCoins()).thenReturn(Collections.emptyList());
        List<TxnBuilder.Done> callbacks=new ArrayList<>();List<CharSequence> messages=new ArrayList<>();
        try(MockedStatic<Language> language=mockStatic(Language.class,CALLS_REAL_METHODS);
            MockedConstruction<TxnBuilder> builders=mockConstruction(TxnBuilder.class,(b,c)->{callbacks.add((TxnBuilder.Done)c.arguments().get(5));when(b.record(any())).thenReturn(b);when(b.onProgress(any())).thenReturn(b);})) {
            language.when(()->Language.setText(eq(label),any(CharSequence.class))).thenAnswer(i->{messages.add(i.getArgument(1));return null;});
            Method build=SendView.class.getDeclaredMethod("buildSend",List.class,String.class,String.class,String.class,String.class,String.class,String.class,String.class);build.setAccessible(true);
            build.invoke(view,Collections.emptyList(),"public-recipient","3","public-change","7",null,"0x00","Minima");callbacks.get(0).onUnknown(TransactionEvidence.UNKNOWN);
            verify(db).update(anyString(),eq(HistoryDb.STATUS_UNKNOWN),isNull(),eq(TransactionEvidence.UNKNOWN));verify(db,never()).update(anyString(),eq(HistoryDb.STATUS_ERROR),any(),any());
            verify(preview).setEnabled(true);verify(act,never()).clearSelection();verify(act).reload();assertSame(draft,view.fieldValues());
            Field sending=SendView.class.getDeclaredField("sending");sending.setAccessible(true);assertFalse(sending.getBoolean(view));
            assertEquals(com.eurobuddha.minimacore.i18n.R.string.l10n_nft_transaction_unknown,NftMessagesTest.field(messages.get(messages.size()-1),"resource"));
        }
    }
}
