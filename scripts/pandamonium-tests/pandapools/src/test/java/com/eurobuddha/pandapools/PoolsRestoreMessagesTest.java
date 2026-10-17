package com.eurobuddha.pandapools;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

public class PoolsRestoreMessagesTest {
    private static Object field(Object value,String name)throws Exception {
        assertTrue(value instanceof Language.Message);
        Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private static void matches(String raw,int resource,Object... arguments)throws Exception {
        Object value=PoolsMessages.message(raw);
        assertEquals(resource,field(value,"resource"));assertArrayEquals(arguments,(Object[])field(value,"arguments"));
    }
    private static Pool pool() {
        Pool p=new Pool();p.address="0x"+"ab".repeat(64);p.minimumOwnerUses=590;p.reserveBlock=1000;return p;
    }
    @Test public void actualCounterRefusalsTranslateWithoutChangingEligibility()throws Exception {
        Pool p=pool();int aged=1000+PoolRefresher.REFRESH_BLOCKS+1;
        matches(RestoreExit.refusal(null,p,46,262144,aged),R.string.l10n_pools_exit_regressed,"46","590");
        matches(RestoreExit.refusal(null,p,null,262144,aged),R.string.l10n_pools_exit_unreadable);
        matches(RestoreExit.refusal(null,p,262144,262144,aged),R.string.l10n_pools_exit_exhausted,"262,144");
        matches(RestoreExit.refusal(null,p,268435456,268435456,aged),R.string.l10n_pools_exit_exhausted,"268,435,456");
        assertNull(RestoreExit.refusal(null,p,846,262144,aged));
        p.minimumOwnerUses=-1;
        matches(RestoreExit.refusal(null,p,0,262144,aged),R.string.l10n_pools_exit_no_counter);
    }
    @Test public void actualAgeRefusalsPreserveUnknownAndBoundaryCases()throws Exception {
        Pool p=pool();
        matches(RestoreExit.refusal(null,p,846,262144,1010),R.string.l10n_pools_exit_recent,"10");
        matches(RestoreExit.refusal(null,p,846,262144,1000+PoolRefresher.REFRESH_BLOCKS),
                R.string.l10n_pools_exit_recent,Integer.toString(PoolRefresher.REFRESH_BLOCKS));
        p.reserveBlock=0;
        matches(RestoreExit.refusal(null,p,846,262144,2000),R.string.l10n_pools_exit_unknown_age);
        matches(RestoreExit.refusal(null,null,846,262144,2000),R.string.l10n_pools_exit_no_pool);
    }
    @Test public void nestedSkipReasonKeepsFullPoolAndCounterEvidence()throws Exception {
        Pool p=pool();String refusal=RestoreExit.refusal(null,p,46,262144,2000);
        Object message=PoolsMessages.message("NOT withdrawn — "+p.address+": "+refusal);
        assertEquals(R.string.l10n_pools_exit_skipped,field(message,"resource"));
        Object[] args=(Object[])field(message,"arguments");assertEquals(p.address,args[0]);
        assertEquals(R.string.l10n_pools_exit_regressed,field(args[1],"resource"));
        assertArrayEquals(new Object[]{"46","590"},(Object[])field(args[1],"arguments"));
    }
    @Test public void unknownWithdrawalDoesNotBecomeSuccessAndKeepsRawError()throws Exception {
        String address=pool().address,error="lost reply 中文 Русский Українська $1 {id}\n0x"+"89".repeat(64);
        matches("Withdrawal status UNKNOWN for "+address+": "+error,R.string.l10n_pools_exit_unknown,address,error);
        matches("withdrawn to the owner address; the transfer to your wallet did not complete: "+error,
                R.string.l10n_pools_exit_forward_failed,error);
        matches("withdrawn; moving the funds into your wallet",R.string.l10n_pools_exit_forward_pending);
    }
    @Test public void recipeIdentifiersStayLiteralEvenWhenTheyContainLanguageKeys()throws Exception {
        String id="0x"+"ca".repeat(64)+" 中文 $1 {pool} pool";
        matches("Skipped "+id+" (invalid recipe fields).",R.string.l10n_pools_exit_invalid_recipe,id);
        String unknown="node: NOT withdrawn — "+pool().address+": unknown";
        assertEquals(unknown,PoolsMessages.message(unknown));
    }
}
