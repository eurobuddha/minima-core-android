package com.eurobuddha.pandadex;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DexOrderMessagesTest {
    private static Object field(Object value,String name)throws Exception {
        assertTrue(value instanceof Language.Message);
        Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private static Object[] args(Object value)throws Exception {return (Object[])field(value,"arguments");}
    private static void resource(Object value,int id)throws Exception {assertEquals(id,field(value,"resource"));}
    private static void matches(String raw,int id,Object... values)throws Exception {
        Object message=DexMessages.message(raw);resource(message,id);assertArrayEquals(values,args(message));
    }
    private static OwnerReceipt.Entry entry(String state,int depth,long checkBlock,String checkId,long blockTime,long checkedAt) {
        return new OwnerReceipt.Entry("CANCELLED","0x"+"ab".repeat(32),100,"0xbb",blockTime,50,state,depth,checkedAt,checkBlock,checkId);
    }
    @Test public void ownerStatusRequiresMatchingInclusionAndRetainsReturnedDepth()throws Exception {
        OwnerReceipt.Entry current=entry(ChainReview.CURRENT,8,100,"0xbb",0,60);
        matches(current.status(),R.string.l10n_dex_owner_depth,"8");assertTrue(current.current());
        OwnerReceipt.Entry[] uncertain={entry(ChainReview.MISSING,-1,100,"0xbb",0,60),
                entry(ChainReview.CURRENT,9,101,"0xbb",0,60),entry(ChainReview.CURRENT,9,100,"0xcc",0,60),
                entry(ChainReview.CURRENT,-1,100,"0xbb",0,60),entry(null,0,100,"0xbb",0,0)};
        int[] ids={R.string.l10n_dex_owner_missing,R.string.l10n_dex_owner_moved,R.string.l10n_dex_owner_moved,
                R.string.l10n_dex_owner_moved,R.string.l10n_dex_owner_pending};
        for(int i=0;i<uncertain.length;i++) {
            OwnerReceipt.Entry r=uncertain[i];String raw=r.status();String tx=r.txpowid;
            resource(DexMessages.message(raw),ids[i]);assertFalse(r.current());assertEquals(raw,r.status());assertEquals(tx,r.txpowid);
        }
        String[] outcomes={"CREATED","CANCELLED","EDITED","RENEWED","CANCELLED_INSTEAD","FILLED_INSTEAD","UNKNOWN"};
        int[] titles={R.string.l10n_dex_order_created,R.string.l10n_dex_order_cancelled,R.string.l10n_dex_price_updated,
                R.string.l10n_dex_order_renewed,R.string.l10n_dex_refunded_instead,R.string.l10n_dex_paid_instead,R.string.l10n_dex_owner_saved};
        for(int i=0;i<outcomes.length;i++)resource(DexMessages.message(OwnerReceipt.title(outcomes[i])),titles[i]);
    }
    @Test public void ownerTimesDoNotInventInclusionTimeOrARepeatCheck()throws Exception {
        OwnerReceipt.Entry observed=entry(ChainReview.CURRENT,8,100,"0xbb",0,0);
        Object[] time=args(OwnerHistoryView.operationTime(observed));resource(time[0],R.string.l10n_dex_first_observed);
        assertArrayEquals(new Object[]{50L,"MMMdHm"},args(time[2]));
        Object[] inclusion=args(OwnerHistoryView.operationInclusion(observed));assertArrayEquals(new Object[]{100L},args(inclusion[0]));assertEquals("",inclusion[1]);
        OwnerReceipt.Entry verified=entry(ChainReview.CURRENT,8,100,"0xbb",InclusionTimeTest.BLOCK_TIME,60);
        time=args(OwnerHistoryView.operationTime(verified));resource(time[0],R.string.l10n_dex_block_time);
        assertArrayEquals(new Object[]{InclusionTimeTest.BLOCK_TIME,"MMMdHm"},args(time[2]));
        Object checked=args(OwnerHistoryView.operationInclusion(verified))[1];resource(checked,R.string.l10n_dex_owner_checked);
        assertArrayEquals(new Object[]{60L,"MMMdHm"},args(args(checked)[0]));
        assertEquals(0,observed.blockTimeMs);assertEquals(0,observed.checkedAt);
    }
    @Test public void orderAgePreservesUnknownAndLastCheckedDistinctions()throws Exception {
        JSONObject raw=TransactionHardeningTest.orderCoin();String stored=raw.toString();Order5 order=Order5.from(raw);
        for(long tip:new long[]{0,99})resource(args(OrdersTab.orderMetadata(order,tip,true))[1],R.string.l10n_dex_age_missing);
        Object[] metadata=args(OrdersTab.orderMetadata(order,120,false));resource(metadata[0],R.string.l10n_dex_gtc_label);
        resource(metadata[1],R.string.l10n_dex_age_at_check);assertArrayEquals(new Object[]{"20"},args(metadata[1]));
        assertEquals("1",metadata[2]);assertEquals("1",metadata[3]);
        matches(order.ageLabel(100,true),R.string.l10n_dex_age_blocks,"0");
        assertEquals(stored,raw.toString());assertEquals(100,order.created);
    }
    @Test public void pnlSignsAmountsAndExcludedHistoryRowsStayDistinct()throws Exception {
        matches("P&L vs book mid +0.000001 MxUSD",R.string.l10n_dex_order_pnl_positive,"0.000001");
        matches("P&L vs book mid -0.000001 MxUSD",R.string.l10n_dex_order_pnl,"-0.000001");
        matches("Rows 7 (2 included)   ·   Volume 12345.678901 MINIMA",R.string.l10n_dex_order_trade_volume,"7","2","12345.678901");
        matches("Notional 00012.340000 MxUSD",R.string.l10n_dex_order_notional,"00012.340000");
        for(String status:new String[]{"LOCAL_VERIFIED","CHAIN_VERIFIED",null})assertEquals("",args(OrdersTab.tradeTime(123,status))[1]);
        for(String status:new String[]{"RECHECK_REQUIRED","SUPERSEDED_FILL"}) {
            Object[] time=args(OrdersTab.tradeTime(123,status));assertArrayEquals(new Object[]{123L,"MMMdHm"},args(time[0]));
            resource(args(time[1])[1],status.startsWith("SUPERSEDED_")?R.string.l10n_dex_corrected_short:R.string.l10n_dex_recheck_short);
            assertFalse(ChainReview.accounted(status));
        }
    }
    @Test public void cancellationConsentRetainsSnapshotAmountsAndMakerPause()throws Exception {
        Object[] empty=args(MainActivity.cancelAllMessage(0,"0","0",true,0));resource(empty[0],R.string.l10n_dex_cancel_empty_body);
        resource(empty[1],R.string.l10n_dex_cancel_pause);assertEquals("",args(empty[1])[0]);
        Object[] ordinary=args(MainActivity.cancelAllMessage(1,"00012.34000","0.052000",false,0));
        resource(ordinary[0],R.string.l10n_dex_cancel_all_body);assertArrayEquals(new Object[]{1,"00012.34000","0.052000"},args(ordinary[0]));assertEquals("",ordinary[1]);
        Object[] maker=args(MainActivity.cancelAllMessage(7,"12","34",true,3));Object rungs=args(maker[1])[0];
        resource(rungs,R.string.l10n_dex_cancel_rungs);assertArrayEquals(new Object[]{3},args(rungs));
    }
    @Test public void submittedCancellationAndUnknownFailureDoNotBecomeConfirmed()throws Exception {
        matches("Cancel sent for 1 order — each leaves the book when a block confirms it, and can still be filled until then",R.string.l10n_dex_cancel_batch_accepted,"1");
        matches("Cancel sent for 2 orders — each leaves the book when a block confirms it, and can still be filled until then",R.string.l10n_dex_cancel_batch_accepted,"2");
        matches("Cancel requests accepted for 2; 3 requests failed or have unknown outcomes. Check Orders and retained receipts.",R.string.l10n_dex_cancel_batch_mixed,"2","3");
        String raw="node: 0x"+"cd".repeat(64)+" 中文 Русский Українська {id} $1\nOrder cancelled";
        matches("Cancel failed: "+raw,R.string.l10n_dex_cancel_failure,raw);
        matches("Reprice failed: "+raw,R.string.l10n_dex_reprice_failure,raw);
        matches("Page 2 · 50 operations",R.string.l10n_dex_owner_page,"2","50");
    }
}
