package com.eurobuddha.pandadex;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class DexMakerMessagesTest {
    private static Object field(Object value,String name)throws Exception {
        assertTrue(value instanceof Language.Message);Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private static Object[] args(Object value)throws Exception{return (Object[])field(value,"arguments");}
    private static void resource(Object value,int id)throws Exception{assertEquals(id,field(value,"resource"));}
    @SuppressWarnings("unchecked") private static List<MakerLadder.Slot> want(String... sizes)throws Exception {
        Method m=MakerStatusTest.class.getDeclaredMethod("want",String[].class);m.setAccessible(true);return (List<MakerLadder.Slot>)m.invoke(null,(Object)sizes);
    }
    private static Order5 order(String size)throws Exception {
        Method m=MakerStatusTest.class.getDeclaredMethod("order",String.class,String.class,String.class,String.class);m.setAccessible(true);
        return (Order5)m.invoke(null,"0xC1","0xORD","0.049900",size);
    }
    private static Map<String,MakerConfig.SlotRec> slots(long lastAction) {
        Map<String,MakerConfig.SlotRec> map=new LinkedHashMap<>();map.put("B1",new MakerConfig.SlotRec("0xORD",new BigDecimal("100"),100,lastAction,new BigDecimal("4.99"),DexContract.USDT_ID));return map;
    }
    @After public void resetFeed(){MarketPrice.testSnapshot(0,-1,()->0);}
    @Test public void realBookChecksRetainCurrentStaleMissingAndRequestedStates()throws Exception {
        List<MakerLadder.Slot> desired=want("100");Map<String,Order5> book=new HashMap<>();book.put("0xORD",order("100"));
        Object seen=DexMessages.message(MakerStatus.lines(desired,slots(0),Collections.emptyMap(),book,103,true).get(0).text);
        resource(seen,R.string.l10n_dex_maker_seen);assertArrayEquals(new Object[]{"0.049900","100"},args(seen));
        Object stale=DexMessages.message(MakerStatus.lines(desired,slots(0),Collections.emptyMap(),book,103,false).get(0).text);
        resource(stale,R.string.l10n_dex_maker_last_seen);assertArrayEquals(args(seen),args(stale));
        MakerStatus.Line missing=MakerStatus.lines(desired,slots(0),Collections.emptyMap(),Collections.emptyMap(),102,true).get(0);
        resource(DexMessages.message(missing.text),R.string.l10n_dex_maker_absent);assertEquals(MakerStatus.WAIT,missing.tone);
        MakerStatus.Line review=MakerStatus.lines(desired,slots(0),Collections.emptyMap(),Collections.emptyMap(),999,true).get(0);
        resource(DexMessages.message(review.text),R.string.l10n_dex_maker_absent_review);assertEquals(MakerStatus.WAIT,review.tone);
        resource(DexMessages.message(MakerStatus.lines(desired,slots(104),Collections.emptyMap(),book,105,true).get(0).text),R.string.l10n_dex_maker_reprice_pending);
    }
    @Test public void reducedAndUnknownFundingRemainSeparateFromHealthyOrders()throws Exception {
        Map<String,Order5> book=new HashMap<>();book.put("0xORD",order("40"));
        Object partial=DexMessages.message(MakerStatus.lines(want("100"),slots(0),Collections.emptyMap(),book,103,true).get(0).text);
        resource(partial,R.string.l10n_dex_maker_less_mxusd);assertArrayEquals(new Object[]{"1.996","4.99"},args(partial));
        Map<String,MakerConfig.SlotRec> legacy=slots(0);legacy.put("B1",new MakerConfig.SlotRec("0xORD",new BigDecimal("100"),100,0));
        resource(DexMessages.message(MakerStatus.lines(want("100"),legacy,Collections.emptyMap(),book,103,true).get(0).text),R.string.l10n_dex_maker_funding_unknown);
        book.put("0xORD",order("200"));
        resource(DexMessages.message(MakerStatus.lines(want("100"),slots(0),Collections.emptyMap(),book,103,true).get(0).text),R.string.l10n_dex_maker_funding_differs);
    }
    @Test public void retainedWithdrawalsNeverTreatAbsenceAsCancellationProof()throws Exception {
        for(int count:new int[]{1,2}) {
            Map<String,Long> tomb=new LinkedHashMap<>();tomb.put("0xORD",100L);if(count==2)tomb.put("0xOTHER",100L);
            Map<String,Order5> book=new HashMap<>();book.put("0xORD",order("100"));
            Object current=DexMessages.message(MakerStatus.lines(Collections.emptyList(),Collections.emptyMap(),tomb,book,999,true).get(0).text);
            resource(current,R.string.l10n_dex_maker_cancel_seen);assertArrayEquals(new Object[]{""+count,"1"},args(current));
            Object stale=DexMessages.message(MakerStatus.lines(Collections.emptyList(),Collections.emptyMap(),tomb,book,999,false).get(0).text);
            resource(stale,R.string.l10n_dex_maker_cancel_stale);assertArrayEquals(args(current),args(stale));
            Object absent=DexMessages.message(MakerStatus.lines(Collections.emptyList(),Collections.emptyMap(),tomb,Collections.emptyMap(),999,true).get(0).text);
            resource(absent,R.string.l10n_dex_maker_cancel_absent);assertArrayEquals(new Object[]{""+count},args(absent));assertEquals(count,tomb.size());
        }
    }
    @Test public void statusDisplayRetainsToneRawIdsDiagnosticsAndSnapshot()throws Exception {
        String id="B1 中文 $1 {rung}",raw="node detail\nMAKER ENABLED\n0x123";
        List<MakerStatus.Line> lines=new ArrayList<>();lines.add(new MakerStatus.Line(id,raw,MakerStatus.FAIL));
        lines.add(new MakerStatus.Line("B2","waiting to post — next cycle",MakerStatus.WAIT));
        Object display=MakerTab.statusLines(lines);lines.clear();Object[] a=args(display);
        assertEquals("✗ ",a[0]);assertEquals(id,a[1]);assertEquals("  ",a[2]);assertEquals(raw,a[3]);
        assertEquals('\n',a[4]);assertEquals("⏳ ",a[5]);assertEquals("B2",a[6]);resource(a[8],R.string.l10n_dex_maker_waiting_post);
        String order="not-a-hex-order 中文 $1\nMAKER ENABLED";
        Object intent=DexMessages.message("Interrupted create — review or withdraw this intent before publishing. Order "+order);
        assertArrayEquals(new Object[]{order},args(intent));
    }
    @Test public void realFeedAgesAndQuarantineKeepTheirSafetyMeaning()throws Exception {
        AtomicLong now=new AtomicLong(2000);MarketPrice.testSnapshot(0.05,1000,now::get);
        Object fresh=DexMessages.message(MarketPrice.stateLabel());resource(fresh,R.string.l10n_dex_maker_feed_age);
        resource(args(fresh)[0],R.string.l10n_pools_seconds_ago);assertArrayEquals(new Object[]{"1"},args(args(fresh)[0]));assertTrue(MarketPrice.fresh());
        now.set(7*60000+1000);
        Object wider=DexMessages.message(MarketPrice.stateLabel());resource(wider,R.string.l10n_dex_maker_feed_wider);
        resource(args(wider)[0],R.string.l10n_dex_minutes_ago);assertArrayEquals(new Object[]{"7"},args(args(wider)[0]));assertFalse(MarketPrice.mustWithdraw());
        now.set(MarketPrice.WITHDRAW_MS+1000);resource(DexMessages.message(MarketPrice.stateLabel()),R.string.l10n_dex_maker_feed_stale);assertTrue(MarketPrice.mustWithdraw());
        MarketPrice.testSnapshot(0.05,now.get(),now::get);assertFalse(MarketPrice.acceptMid(0.20));
        resource(DexMessages.message(MarketPrice.stateLabel()),R.string.l10n_dex_maker_feed_quarantined);assertFalse(MarketPrice.fresh());assertTrue(MarketPrice.mustWithdraw());
    }
    @Test public void previewKeepsBothSidesAndExactPricesRatherThanReformattingDrafts()throws Exception {
        Object display=DexMessages.message("BIDS  2 lvl · best 0.001234 · 00012.34000 M\nASKS  none");resource(display,R.string.l10n_dex_maker_preview);
        Object bid=args(display)[0];resource(bid,R.string.l10n_dex_maker_preview_side);assertArrayEquals(new Object[]{"2","0.001234","00012.34000"},args(bid));
        resource(args(display)[1],R.string.l10n_atomix_market_none);
        Object crossed=DexMessages.message("⚠ Crossed — best bid 0.050000 ≥ best ask 0.040000: you'd sell cheaper than you buy");
        resource(crossed,R.string.l10n_dex_maker_crossed);assertArrayEquals(new Object[]{"0.050000","0.040000"},args(crossed));
    }
}
