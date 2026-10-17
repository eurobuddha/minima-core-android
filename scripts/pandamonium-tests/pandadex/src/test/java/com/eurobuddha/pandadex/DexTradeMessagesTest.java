package com.eurobuddha.pandadex;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DexTradeMessagesTest {
    private static Object field(Object value,String name)throws Exception {
        assertTrue(value instanceof Language.Message);
        Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private static void matches(String raw,int resource,Object... args)throws Exception {
        Object value=DexMessages.message(raw);
        assertEquals(resource,field(value,"resource"));assertArrayEquals(args,(Object[])field(value,"arguments"));
    }
    @Test public void actualReceiptPhasesKeepEvidenceAndActionLocks()throws Exception {
        for(String kind:new String[]{Pending.CANCEL,Pending.EDIT}) {
            for(String phase:new String[]{"","PREPARED","POSTING","SUBMITTED","UNKNOWN","NOT_SUBMITTED"}) {
                Pending.Row r=ReceiptPresentationTest.receipt(kind,phase,0);
                String stored=r.json().toString();Set<String> held=Pending.unresolvedOwnerCoins(Collections.singletonList(r));
                Object display=DexMessages.message(r.status(100));
                int expected="PREPARED".equals(phase)?R.string.l10n_dex_receipt_prepared:
                        "NOT_SUBMITTED".equals(phase)?R.string.l10n_dex_receipt_not_submitted:
                        "POSTING".equals(phase)?R.string.l10n_dex_receipt_posting:
                        "SUBMITTED".equals(phase)?R.string.l10n_dex_receipt_submitted:R.string.l10n_dex_receipt_unknown;
                assertEquals(kind+" "+phase,expected,field(display,"resource"));
                if(!"PREPARED".equals(phase)&&!"NOT_SUBMITTED".equals(phase)) {
                    Object[] args=(Object[])field(display,"arguments");
                    assertEquals(R.string.l10n_dex_time_unavailable,field(args[0],"resource"));
                }
                assertEquals(stored,r.json().toString());assertEquals(held,Pending.unresolvedOwnerCoins(Collections.singletonList(r)));
            }
        }
    }
    @Test public void actualReceiptDescriptionsRetainSideAndDisplayedAmounts()throws Exception {
        String[] kinds={Pending.CANCEL,Pending.EDIT,Pending.PLACE};
        int[][] ids={{R.string.l10n_dex_receipt_cancel_buy,R.string.l10n_dex_receipt_cancel_sell},
                {R.string.l10n_dex_receipt_update_buy,R.string.l10n_dex_receipt_update_sell},
                {R.string.l10n_dex_receipt_place_buy,R.string.l10n_dex_receipt_place_sell}};
        for(int k=0;k<kinds.length;k++)for(int side=0;side<2;side++) {
            Pending.Row r=ReceiptPresentationTest.receipt(kinds[k],"UNKNOWN",0);r.buy=side==0;
            r.minima=new BigDecimal("12345.67");r.price=new BigDecimal("0.052000");
            matches(r.description(),ids[k][side],"12345.67","0.052000");
        }
    }
    @Test public void incompleteAndUnreadableReceiptsRemainUnverified()throws Exception {
        Pending.Row legacy=ReceiptPresentationTest.receipt(Pending.PLACE,"SUBMITTED",1);legacy.creation=null;
        matches(legacy.status(100),R.string.l10n_dex_receipt_legacy);
        Pending.Row broken=new Pending.Row();broken.kind="RECOVERY_ERROR";
        matches(broken.description(),R.string.l10n_dex_receipt_problem);
        matches(broken.status(100),R.string.l10n_dex_receipt_unreadable);
        assertTrue(Pending.unresolvedOwnerCoins(Collections.singletonList(broken)).isEmpty());
    }
    @Test public void actualDepthAmountsPreserveDownwardRoundingAndLiquiditySource()throws Exception {
        String mixed=TradeView.amountText(new BigDecimal("12.349999"),new BigDecimal("0.009"));
        matches(mixed,R.string.l10n_dex_book_pool_amount,"12.34","0.00");
        matches(TradeView.amountText(BigDecimal.ZERO,new BigDecimal("12.349999")),R.string.l10n_dex_pool_amount,"12.34");
        assertEquals("12.34",DexMessages.message(TradeView.amountText(new BigDecimal("12.349999"),BigDecimal.ZERO)));
        matches("Total: 00012.34000 MxUSD",R.string.l10n_dex_total_value,"00012.34000");
    }
    @Test public void nestedTimesTranslateWithoutReparsingUnknownNodePayloads()throws Exception {
        Object age=DexMessages.message("last trade · 2 minutes ago");assertEquals(R.string.l10n_dex_last_trade_age,field(age,"resource"));
        Object nested=((Object[])field(age,"arguments"))[0];assertEquals(R.string.l10n_dex_minutes_ago,field(nested,"resource"));
        assertArrayEquals(new Object[]{"2"},(Object[])field(nested,"arguments"));
        matches("3m 07s",R.string.l10n_dex_clock_minutes,"3","07");
        String raw="node says 0x"+"ab".repeat(64)+" 中文 Русский Українська $1 {id}\nNode accepted submission";
        Object unknown=DexMessages.message("Submission outcome unknown · "+raw+". On-chain verification pending; receipt retained.");
        assertEquals(R.string.l10n_dex_receipt_unknown,field(unknown,"resource"));
        assertArrayEquals(new Object[]{raw},(Object[])field(unknown,"arguments"));
    }
}
