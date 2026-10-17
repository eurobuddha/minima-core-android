package com.eurobuddha.pandapools;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

public class PoolsReceiptMessagesTest {
    private static ActivityLog.Entry entry(String type,String text,String error) {
        return new ActivityLog.Entry(type,text,"0x"+"ab".repeat(64),123,456,error!=null,error);
    }
    private static Object field(Object value,String name)throws Exception {
        assertTrue(value instanceof Language.Message);
        Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private static void matches(ActivityLog.Entry e,int id,Object... arguments)throws Exception {
        String summary=e.summary,tx=e.txpowid,original=e.originalTxpowid,error=e.failMsg,status=e.statusText(150);
        Object result=PoolsMessages.activitySummary(e);
        assertEquals(id,field(result,"resource"));assertArrayEquals(arguments,(Object[])field(result,"arguments"));
        assertEquals(summary,e.summary);assertEquals(tx,e.txpowid);assertEquals(original,e.originalTxpowid);
        assertEquals(error,e.failMsg);assertEquals(status,e.statusText(150));assertEquals(123,e.submitBlock);assertEquals(456,e.ts);
    }
    @Test public void amountsAndRepeatedTokenNamesStayExactAcrossEveryLiquidityAction()throws Exception {
        for(String token:new String[]{"中文 Русский Українська $1 {token}","0x"+"ca".repeat(64),
                "pool  ·  new size 9 MINIMA + 5 token\nback to wallet","Consolidate Failed coins"}) {
            String amounts="00012.34000 MINIMA + 0.000000000000000001 "+token;
            matches(entry(ActivityLog.CREATE,"Create MINIMA / "+token+" pool  ·  "+amounts,null),R.string.l10n_pools_receipt_create,token,"00012.34000","0.000000000000000001");
            matches(entry(ActivityLog.DEPOSIT,"Add to MINIMA / "+token+"  ·  "+amounts,null),R.string.l10n_pools_receipt_add,token,"00012.34000","0.000000000000000001");
            matches(entry(ActivityLog.MIGRATE,"Migrate MINIMA / "+token+" pool  ·  new size "+amounts,null),R.string.l10n_pools_receipt_migrate,token,"00012.34000","0.000000000000000001");
            matches(entry(ActivityLog.CLOSE,"Withdraw MINIMA / "+token+"  ·  "+amounts+" back to wallet",null),R.string.l10n_pools_receipt_withdraw,token,"00012.34000","0.000000000000000001");
        }
    }
    @Test public void failedAndUncertainAttemptsKeepTheirOriginalStatus()throws Exception {
        String token="Failed pool";
        for(String error:new String[]{"node error",NodeApi.ERR_WRITE_UNCERTAIN}) {
            matches(entry(ActivityLog.CREATE,"Create MINIMA / "+token+" pool",error),R.string.l10n_pools_receipt_create_attempt,token);
            matches(entry(ActivityLog.DEPOSIT,"Add to MINIMA / "+token,error),R.string.l10n_pools_receipt_add_attempt,token);
            matches(entry(ActivityLog.MIGRATE,"Migrate MINIMA / "+token+" pool",error),R.string.l10n_pools_receipt_migrate_attempt,token);
        }
    }
    @Test public void restoredAddressesAndConsolidatedNamesRemainLiteral()throws Exception {
        String address="0x"+"da".repeat(64),name="Consolidate 中文 coins\nVerified";
        matches(entry(ActivityLog.CLOSE,"Automatic withdrawal after restore  ·  "+address,null),R.string.l10n_pools_receipt_restore,address);
        matches(entry(ActivityLog.CONSOLIDATE,"Consolidate "+name+" coins",null),R.string.l10n_pools_consolidate_token,name);
    }
    @Test public void mismatchedUnknownAndSwapSummariesAreUntouched() {
        for(String type:new String[]{ActivityLog.SWAP,"FUTURE_TYPE",ActivityLog.CLOSE,ActivityLog.CREATE,ActivityLog.DEPOSIT,ActivityLog.MIGRATE}) {
            for(String raw:new String[]{"−12.34000 中文  ·  +0.000000001 Українська","Confirmed","unknown receipt"})
                assertEquals(raw,PoolsMessages.activitySummary(entry(type,raw,null)));
        }
        String mismatch="Create MINIMA / AAA pool  ·  1 MINIMA + 2 BBB";
        assertEquals(mismatch,PoolsMessages.activitySummary(entry(ActivityLog.CREATE,mismatch,null)));
        String shortSuccess="Add to MINIMA / token";
        assertEquals(shortSuccess,PoolsMessages.activitySummary(entry(ActivityLog.DEPOSIT,shortSuccess,null)));
        assertEquals(mismatch,PoolsMessages.activitySummary(entry(ActivityLog.CLOSE,mismatch,null)));
    }
    @Test public void missingAmountsStayUnknownAndNeverBecomeZero()throws Exception {
        matches(entry(ActivityLog.CLOSE,"Withdraw MINIMA / T  ·  — MINIMA + — T back to wallet","failure"),
                R.string.l10n_pools_receipt_withdraw,"T","—","—");
    }
}
