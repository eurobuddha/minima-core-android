package com.eurobuddha.pandadex;

import android.os.Handler;
import android.os.Looper;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class DexExportMessagesTest {
    private static final String UNSAVED=" Export status could not be saved locally; check the destination after restarting.";
    private static final String PREPARE="Could not prepare the export. Source records are unchanged. ";
    private static Object field(Object value,String name)throws Exception {
        assertTrue(value instanceof Language.Message);Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private static Object[] args(Object value)throws Exception{return (Object[])field(value,"arguments");}
    private static void resource(Object value,int id)throws Exception{assertEquals(id,field(value,"resource"));}
    private static String describe(TradeExport.Report report) {
        // Only the Android UI dispatcher is mocked. Use the real exporter and its decimal formatter.
        try(MockedStatic<Looper> looper=mockStatic(Looper.class);MockedConstruction<Handler> handler=mockConstruction(Handler.class)) {
            return TradeExportWriter.describe(report);
        }
    }
    private static TradeExport.TradeRow row(boolean buy,String size,String price,String status) {
        return new TradeExport.TradeRow("0xaabb",1700000000000L,100,new BigDecimal(price),new BigDecimal(size),buy,true,"raw-order",
                "0xccdd","BOOK","0xaabb","0xeeff",status,"original evidence",100);
    }
    @Test public void actualExportTotalsRetainSignsPrecisionAndExcludedRecords()throws Exception {
        TradeExport.Snapshot snapshot=new TradeExport.Snapshot();
        snapshot.rows.add(row(true,"10.250000000","0.125000000","CHAIN_VERIFIED"));
        snapshot.rows.add(row(false,"2.000000000","0.500000000","CHAIN_VERIFIED"));
        snapshot.rows.add(row(true,"9000","10","RECHECK_REQUIRED"));
        TradeExport.Report report=TradeExport.build(snapshot);String csv=report.tradesCsv,evidence=report.verificationCsv;
        Object display=DexMessages.message(describe(report));resource(display,R.string.l10n_dex_export_summary);
        assertArrayEquals(new Object[]{"3","8.25","-0.28125"},args(display));assertEquals(1,report.totals.excludedRechecks);
        snapshot.rows.clear();assertArrayEquals(new Object[]{"3","8.25","-0.28125"},args(display));
        assertEquals(csv,report.tradesCsv);assertEquals(evidence,report.verificationCsv);assertTrue(evidence.contains("RECHECK_REQUIRED"));
    }
    @Test public void emptyAndSingleRecordDescriptionsUseTheSameCountedSummary()throws Exception {
        TradeExport.Snapshot snapshot=new TradeExport.Snapshot();
        Object zero=DexMessages.message(describe(TradeExport.build(snapshot)));resource(zero,R.string.l10n_dex_export_summary);
        assertArrayEquals(new Object[]{"0","0","0"},args(zero));
        snapshot.rows.add(row(false,"0.000001","0.125","CHAIN_VERIFIED"));
        Object one=DexMessages.message(describe(TradeExport.build(snapshot)));resource(one,R.string.l10n_dex_export_summary);
        assertArrayEquals(new Object[]{"1","-0.000001","0.00000012"},args(one));
    }
    @Test public void completedWriteStillCarriesCloudUploadAndUnsavedStatusWarnings()throws Exception {
        String notice="Export written and closed · 12 personal trade records · net -00012.34000 MINIMA · net 0.000000001 MxUSD. For cloud destinations, check that the provider has finished uploading.";
        Object unsaved=DexMessages.message(notice+UNSAVED);resource(unsaved,R.string.l10n_dex_export_status_unsaved);
        Object written=args(unsaved)[0];resource(written,R.string.l10n_dex_export_written);
        Object totals=args(written)[0];resource(totals,R.string.l10n_dex_export_summary);
        assertArrayEquals(new Object[]{"12","-00012.34000","0.000000001"},args(totals));
    }
    @Test public void failedPreparationRetainsUnknownDiagnosticAsOneLiteral()throws Exception {
        String raw="provider://中文/Русский/Українська 0x123 $1 {records}\nExport cancelled. No file was written by PandaDEX.";
        Object warning=DexMessages.message(PREPARE+raw+UNSAVED);resource(warning,R.string.l10n_dex_export_status_unsaved);
        Object failed=args(warning)[0];resource(failed,R.string.l10n_dex_export_prepare_failed);
        assertArrayEquals(new Object[]{raw},args(failed));assertEquals(raw,DexMessages.message(raw));
        Object known=DexMessages.message(PREPARE+"Could not open the prepared export");
        resource(args(known)[0],R.string.l10n_dex_export_prepared_open_failed);
    }
    @Test public void partialAndCancelledResultsRemainDistinctAndMalformedTotalsStayLiteral()throws Exception {
        resource(DexMessages.message("The export could not be completed. The destination may contain a partial file; check it and export to another destination."),R.string.l10n_dex_export_partial);
        resource(DexMessages.message("Export cancelled. No file was written by PandaDEX."),R.string.l10n_dex_export_cancelled);
        for(String raw:new String[]{"2 personal trade records · net NaN MINIMA · net 0 MxUSD","2 personal trade records · net 1,25 MINIMA · net 0 MxUSD","user note: 2 personal trade records · net 1 MINIMA · net 0 MxUSD"})assertEquals(raw,DexMessages.message(raw));
    }
}
