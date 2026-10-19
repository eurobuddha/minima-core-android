package com.eurobuddha.nftwallet;

import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import org.mockito.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
import static com.eurobuddha.nftwallet.TransactionEvidenceTest.*;

/** Actual view methods with widget/transport doubles; rendering does not mutate financial records. */
public class LocalHistoryViewTest {
    static void field(Class<?> cls,Object obj,String name,Object value)throws Exception {Field f=cls.getDeclaredField(name);f.setAccessible(true);f.set(obj,value);}
    @Test public void localOnlyHistoryRendersAndOpeningDetailsRechecksTheOutcome()throws Exception {
        MainActivity act=mock(MainActivity.class);HistoryDb db=mock(HistoryDb.class);Resources res=mock(Resources.class);DisplayMetrics metrics=new DisplayMetrics();metrics.density=1;
        when(act.history()).thenReturn(db);when(act.getResources()).thenReturn(res);when(res.getDisplayMetrics()).thenReturn(metrics);
        HistoryRow row=new HistoryRow();row.internalid="first";row.amount="7";row.tokenid="0x00";row.tokenName="Literal 中文 Українська";row.recipient="";row.inputs=row.outputs="[]";
        when(db.list(anyInt())).thenReturn(Collections.singletonList(row));when(db.loadNodeTx(anyInt())).thenReturn(Collections.emptyList());
        String owner="a".repeat(32)+":"+"b".repeat(32);
        when(db.transactionAttempts()).thenReturn(Arrays.asList(new HistoryDb.Attempt("first","public_handle",HistoryDb.UNKNOWN,intent("0x00").toString(),"",owner)));
        HistoryView view=mock(HistoryView.class,CALLS_REAL_METHODS);LinearLayout container=mock(LinearLayout.class);
        field(BaseView.class,view,"act",act);field(BaseView.class,view,"root",mock(View.class));field(HistoryView.class,view,"container",container);
        try(MockedStatic<Language> lang=mockStatic(Language.class);MockedStatic<Design> design=mockStatic(Design.class);MockedStatic<Sheet> sheets=mockStatic(Sheet.class);
            MockedConstruction<TextView> labels=mockConstruction(TextView.class);MockedConstruction<LinearLayout> layouts=mockConstruction(LinearLayout.class);MockedConstruction<HorizontalScrollView> scroll=mockConstruction(HorizontalScrollView.class)) {
            Sheet sheet=mock(Sheet.class,RETURNS_SELF);sheets.when(()->Sheet.create(eq(act),any())).thenReturn(sheet);
            Method render=HistoryView.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(view);
            lang.verify(()->Language.msg(l10n_nft_history_local_activity));lang.verify(()->Language.msg(l10n_nft_history_unverified_status));
            LinearLayout local=layouts.constructed().get(0);verify(container).addView(local);
            lang.verify(()->Language.setText(any(TextView.class),eq("7  Literal 中文 Українська")));
            ArgumentCaptor<View.OnClickListener> click=ArgumentCaptor.forClass(View.OnClickListener.class);verify(local).setOnClickListener(click.capture());
            when(db.transactionAttempts()).thenReturn(Arrays.asList(new HistoryDb.Attempt("first","public_handle",HistoryDb.NOT_SUBMITTED,intent("0x00").toString(),"",owner)));
            click.getValue().onClick(local);lang.verify(()->Language.msg(l10n_nft_history_not_submitted));verify(sheet).show();
            verify(db,never()).delete(anyString());verify(db,never()).update(anyString(),anyString(),any(),any());verify(act,never()).node();
        }
    }
    @Test public void everyOutcomeUsesARebindableLanguageMessage()throws Exception {
        Method status=HistoryView.class.getDeclaredMethod("localStatus",LocalHistory.Entry.class);status.setAccessible(true);
        Field resource=Language.Message.class.getDeclaredField("resource");resource.setAccessible(true);
        String owner="a".repeat(32)+":"+"b".repeat(32);
        Object[][] phases={{HistoryDb.PREPARED,l10n_nft_history_preparing},{HistoryDb.POSTING,l10n_utxo_status_posting},{HistoryDb.UNKNOWN,l10n_nft_history_unverified_status},{HistoryDb.POSTED,l10n_utxo_status_posted},{HistoryDb.CONFIRMED,l10n_wallet_history_previously_confirmed},{HistoryDb.NOT_SUBMITTED,l10n_nft_history_not_submitted}};
        for(Object[] pair:phases) {
            String phase=(String)pair[0],saved="";
            if(HistoryDb.POSTED.equals(phase)||HistoryDb.CONFIRMED.equals(phase)) {
                org.json.JSONObject receipt=TransactionEvidence.publicReceipt(receipt(intent("0x00")),intent("0x00"));
                if(HistoryDb.CONFIRMED.equals(phase))receipt.put("inclusion",TransactionEvidence.inclusion(TransactionRecoveryTest.inclusion("3")));
                saved=receipt.toString();
            }
            HistoryDb.Attempt a=new HistoryDb.Attempt("first","public_handle",phase,intent("0x00").toString(),saved,owner);
            Object label=status.invoke(null,new LocalHistory.Entry(new HistoryRow(),a));assertTrue(label instanceof Language.Message);assertEquals(pair[1],resource.get(label));
        }
        assertEquals(l10n_nft_history_unverified_status,resource.get(status.invoke(null,new LocalHistory.Entry(new HistoryRow(),null))));
    }

}
