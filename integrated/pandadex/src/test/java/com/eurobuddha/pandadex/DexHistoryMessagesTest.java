package com.eurobuddha.pandadex;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DexHistoryMessagesTest {
    private static Object field(Object value,String name)throws Exception {
        Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private static Object[] args(Object value)throws Exception {
        assertTrue(value instanceof Language.Message);return (Object[])field(value,"arguments");
    }
    private static void resource(Object value,int expected)throws Exception {
        assertTrue(value instanceof Language.Message);assertEquals(expected,field(value,"resource"));
    }
    private static TradeExport.TradeRow row(String status,String note) {
        return new TradeExport.TradeRow("0x"+"ab".repeat(32),InclusionTimeTest.BLOCK_TIME,100,
                new BigDecimal("0.052000"),new BigDecimal("12.34567890"),true,true,"order",
                "0x"+"cd".repeat(32),"BOOK","source","proceeds",status,note,100);
    }
    @Test public void chainStatusAloneDoesNotTurnObservationTimeIntoBlockTime()throws Exception {
        TradeExport.TradeRow observed=row("CHAIN_VERIFIED","Old proof");
        Object[] a=args(TapeTab.tradeMetadata(observed));
        resource(a[0],R.string.l10n_dex_observed);
        resource(a[2],-1);assertArrayEquals(new Object[]{InclusionTimeTest.BLOCK_TIME,"MMMdHm"},args(a[2]));
        resource(a[4],R.string.l10n_dex_maker);resource(a[6],R.string.l10n_dex_source_book);
        resource(a[8],R.string.l10n_dex_chain_when_checked);
        TradeExport.TradeRow verified=row("CHAIN_VERIFIED","Proof"+ChainEvidence.BLOCK_TIME_NOTE);
        resource(args(TapeTab.tradeMetadata(verified))[0],R.string.l10n_dex_block_time);
        assertEquals("Old proof",observed.verificationNote);assertEquals("CHAIN_VERIFIED",observed.verificationStatus);
    }
    @Test public void excludedAndLegacyStatusesKeepTheirAccountingAndEvidence()throws Exception {
        String[] states={"LOCAL_VERIFIED","CHAIN_VERIFIED","RECHECK_REQUIRED","RECHECK_REQUIRED_MISSING","SUPERSEDED_FILL"};
        int[] ids={R.string.l10n_dex_legacy_not_rechecked,R.string.l10n_dex_chain_when_checked,
                R.string.l10n_dex_recheck_excluded,R.string.l10n_dex_recheck_excluded,R.string.l10n_dex_corrected_excluded};
        List<TradeExport.TradeRow> rows=new ArrayList<>();
        for(int i=0;i<states.length;i++) {
            TradeExport.TradeRow r=row(states[i],"Unchanged evidence");rows.add(r);
            resource(args(TapeTab.tradeMetadata(r))[8],ids[i]);
            assertEquals(i<2,ChainReview.accounted(r.verificationStatus));
            assertEquals(states[i],r.verificationStatus);assertEquals("Unchanged evidence",r.verificationNote);
        }
        Method m=TapeTab.class.getDeclaredMethod("totals",List.class);m.setAccessible(true);Object totals=m.invoke(null,rows);
        assertEquals(0,new BigDecimal("24.69135780").compareTo((BigDecimal)field(totals,"netM")));
        BigDecimal expected=TradeExport.usdtNotional(rows.get(0)).multiply(new BigDecimal("-2"));
        assertEquals(0,expected.compareTo((BigDecimal)field(totals,"netU")));
    }
    @Test public void correctionAmountsAndIdsStayLiteralIncludingUnusualPayloads()throws Exception {
        String literal="0x"+"ef".repeat(64)+" 中文 Русский Українська {time} $1\nCHAIN_VERIFIED";
        JSONObject correction=new TestJson().put("coinid",literal).put("old_txpowid","old").put("new_txpowid","new");
        String stored=correction.toString();Object ids=TapeTab.correctionIds(correction);
        resource(ids,R.string.l10n_dex_correction_ids);assertArrayEquals(new Object[]{literal,"old","new"},args(ids));
        assertEquals(stored,correction.toString());
        JSONObject original=new TestJson().put("size","00012.34000").put("price","0.052000").put("timems",InclusionTimeTest.BLOCK_TIME);
        Object record=TapeTab.earlierRecord(original);resource(record,R.string.l10n_dex_earlier_record);
        assertEquals("00012.34000",args(record)[0]);assertEquals("0.052000",args(record)[1]);
        assertArrayEquals(new Object[]{InclusionTimeTest.BLOCK_TIME,"MMMdHm"},args(args(record)[2]));
        original.put("size","BUY");assertEquals("BUY",args(TapeTab.earlierRecord(original))[0]);
    }
    @Test public void missingCorrectionCoordinatesStayUnknown()throws Exception {
        JSONObject empty=new TestJson();Object[] a=args(TapeTab.earlierRecord(empty));
        resource(a[0],R.string.l10n_dex_history_unknown);resource(a[1],R.string.l10n_dex_history_unknown);
        resource(a[2],R.string.l10n_dex_timestamp_missing);
        empty.put("timems",-1);resource(args(TapeTab.earlierRecord(empty))[2],R.string.l10n_dex_timestamp_missing);
        assertEquals(1,empty.length());
        empty.put("size",JSONObject.NULL).put("price",JSONObject.NULL);
        resource(args(TapeTab.earlierRecord(empty))[0],R.string.l10n_dex_history_unknown);
        resource(args(TapeTab.earlierRecord(empty))[1],R.string.l10n_dex_history_unknown);
    }
    @Test public void readoutKeepsActualCandleValuesAndTakesAnImmutableSnapshot()throws Exception {
        List<Candles.Fill> fills=Arrays.asList(
                new Candles.Fill(3000,new BigDecimal("0.04"),new BigDecimal("2"),true),
                new Candles.Fill(1000,new BigDecimal("0.05"),new BigDecimal("10"),true),
                new Candles.Fill(4000,new BigDecimal("0.06"),BigDecimal.ONE,true),
                new Candles.Fill(2000,new BigDecimal("0.07"),new BigDecimal("5"),true));
        Candles.Candle candle=Candles.aggregate(fills,Candles.M15).get(0);
        Object value=ChartTab.candleReadout(candle);resource(value,R.string.l10n_dex_candle_readout);Object[] a=args(value);
        assertArrayEquals(new Object[]{0L,"MMMdHm"},args(a[0]));
        assertArrayEquals(new Object[]{"0.05","0.07","0.04","0.06","18"},Arrays.copyOfRange(a,1,6));
        assertEquals(4,candle.fills);assertEquals(0,new BigDecimal("18").compareTo(candle.volume));
        candle.close=new BigDecimal("0.99");assertEquals("0.06",args(value)[4]);
        assertEquals("",ChartTab.candleReadout(null));
    }
}
