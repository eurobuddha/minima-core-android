package com.eurobuddha.nftwallet;

import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.mockito.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Reuses LocalHistoryViewTest's real controller/widget doubles and HistorySyncTest replies.
 * No network, node, signing or real financial records are involved. */
public class HistoryPagingTest {
    MainActivity act; HistoryDb db; NodeApi node; HistoryView view;
    List<String> commands; NodeApi.Cb reply;
    MockedStatic<Language> lang; MockedStatic<Design> design;
    MockedConstruction<TextView> labels; MockedConstruction<LinearLayout> layouts;
    MockedConstruction<HorizontalScrollView> scroll;
    @Before public void setup()throws Exception {
        act=mock(MainActivity.class);db=mock(HistoryDb.class);node=mock(NodeApi.class);
        Resources res=mock(Resources.class);DisplayMetrics metrics=new DisplayMetrics();metrics.density=1;
        when(act.getResources()).thenReturn(res);when(res.getDisplayMetrics()).thenReturn(metrics);
        when(act.history()).thenReturn(db);when(act.node()).thenReturn(node);
        when(act.chainBlock()).thenReturn(100);when(act.currentTab()).thenReturn(MainActivity.TAB_HISTORY);
        commands=new ArrayList<>();doAnswer(i->{commands.add(i.getArgument(0));reply=i.getArgument(1);return null;}).when(node).cmd(anyString(),any());
        view=mock(HistoryView.class,CALLS_REAL_METHODS);
        field(BaseView.class,"act",act);field(BaseView.class,"root",mock(View.class));field(HistoryView.class,"container",mock(LinearLayout.class));
        field(HistoryView.class,"pageMax",8);field(HistoryView.class,"lastFetchBlock",-1);
        lang=mockStatic(Language.class);design=mockStatic(Design.class);
        labels=mockConstruction(TextView.class);layouts=mockConstruction(LinearLayout.class);scroll=mockConstruction(HorizontalScrollView.class);
    }
    @After public void close(){scroll.close();layouts.close();labels.close();design.close();lang.close();}
    void field(Class<?> c,String n,Object v)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);f.set(view,v);}
    void older()throws Exception{Method m=HistoryView.class.getDeclaredMethod("loadOlder");m.setAccessible(true);m.invoke(view);}
    static JSONObject page(int count,boolean transactions)throws Exception {
        JSONArray tx=new JSONArray(),details=new JSONArray();
        for(int i=0;i<count;i++) {tx.put(new JSONObject().put("txpowid","0x"+String.format("%064x",i+1)).put("istransaction",transactions));details.put(new JSONObject().put("difference",new JSONObject().put("0x00","1")));}
        return new JSONObject().put("status",true).put("response",new JSONObject().put("txpows",tx).put("details",details));
    }
    void lastOffset(int n){assertTrue(commands.toString(),commands.get(commands.size()-1).endsWith("offset:"+n));}
    @Test public void persistedCacheCountIsNeverANodeOffset()throws Exception {
        when(db.nodeTxCount()).thenReturn(70);view.onShown();reply.onResult(page(8,true));older();lastOffset(8);
    }
    @Test public void filteredOnlyPagesStillExposeOlderAndAdvanceRawOffset()throws Exception {
        view.onShown();reply.onResult(page(8,false));lang.verify(()->Language.msg(l10n_utxo_history_older));older();lastOffset(8);
        reply.onResult(page(8,false));older();lastOffset(16);verify(db,never()).upsertNodeTx(any());
    }
    @Test public void newTipRestartsTheCursorRatherThanSkippingNewRows()throws Exception {
        view.onShown();reply.onResult(page(8,false));older();reply.onResult(page(8,false));
        when(act.chainBlock()).thenReturn(101);view.onNewBlock();lastOffset(0);reply.onResult(page(8,false));older();lastOffset(8);
    }
    @Test public void rollbackHeightAlsoRefreshes()throws Exception {
        view.onShown();reply.onResult(page(8,false));when(act.chainBlock()).thenReturn(99);view.onNewBlock();assertEquals(2,commands.size());lastOffset(0);
    }
    @Test public void tipMovingDuringReplyCannotSuppressTheNextRefresh()throws Exception {
        view.onShown();when(act.chainBlock()).thenReturn(101);reply.onResult(page(8,false));view.onNewBlock();assertEquals(2,commands.size());lastOffset(0);
    }
    @Test public void olderRequestAfterTipChangeRestartsFromTheHead()throws Exception {
        view.onShown();reply.onResult(page(8,false));when(act.chainBlock()).thenReturn(101);older();lastOffset(0);
    }
    @Test public void malformedPageIsAtomicAndCanBeRetriedAtTheSameHeight()throws Exception {
        view.onShown();JSONObject bad=page(8,true);bad.getJSONObject("response").getJSONArray("txpows").put(7,new JSONObject());reply.onResult(bad);
        verify(db,never()).upsertNodeTx(any());view.onNewBlock();assertEquals(2,commands.size());lastOffset(0);
    }
    @Test public void missingEconomicDetailsCannotPermanentlyCacheZeroTransactions()throws Exception {
        view.onShown();JSONObject bad=page(8,true);bad.getJSONObject("response").remove("details");reply.onResult(bad);
        verify(db,never()).upsertNodeTx(any());view.onNewBlock();assertEquals(2,commands.size());
    }
    @Test public void missingReplyDoesNotCrashOrMarkTheHeightFetched()throws Exception {
        view.onShown();reply.onResult(null);view.onNewBlock();assertEquals(2,commands.size());
    }
    @Test public void offlineErrorDoesNotChainShrinkingRequests()throws Exception {
        view.onShown();reply.onError("offline");assertEquals(1,commands.size());view.onNewBlock();assertTrue(commands.get(1).contains("max:8"));
    }
    @Test public void oversizeRetriesTheSameRawOffsetWithSmallerPages()throws Exception {
        view.onShown();reply.onResult(page(8,false));older();reply.onError(NodeApi.ERR_TOO_LONG);lastOffset(8);assertTrue(commands.get(2).contains("max:4"));
        reply.onResult(page(4,false));older();lastOffset(12);
    }
    @Test public void failedOlderPageDoesNotAdvanceTheCursor()throws Exception {
        view.onShown();reply.onResult(page(8,false));older();reply.onError("offline");older();lastOffset(8);
    }
    @Test public void stringSuccessCannotBeAcceptedAsAHistoryPage()throws Exception {
        view.onShown();reply.onResult(page(8,true).put("status","true"));verify(db,never()).upsertNodeTx(any());
    }
}
