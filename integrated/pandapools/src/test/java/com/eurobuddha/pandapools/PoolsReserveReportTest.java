package com.eurobuddha.pandapools;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;

/** Reuse the actual reserve-recovery harness, including its node-side proof checks. */
public class PoolsReserveReportTest {
    private static Object field(Object value,String name)throws Exception {
        Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private static void collect(Object value,Set<Integer> ids,List<Object> literals)throws Exception {
        if(!(value instanceof Language.Message)){literals.add(value);return;}
        ids.add((Integer)field(value,"resource"));
        for(Object a:(Object[])field(value,"arguments"))collect(a,ids,literals);
    }
    @Test public void actualVerifiedReservesTranslateWithoutCallingImports()throws Exception {
        ReserveRecoveryTest.Harness h=new ReserveRecoveryTest.Harness();h.visible.put(h.cm).put(h.ct);h.recover(null,true);
        Set<Integer> ids=new HashSet<>();List<Object> literals=new ArrayList<>();collect(PoolsMessages.report(h.detail),ids,literals);
        assertTrue(h.result);assertEquals(1,h.completions);
        assertTrue(ids.contains(R.string.l10n_pools_reserve_verified));assertTrue(literals.contains(ReserveRecoveryTest.ADDRESS));
        assertFalse(h.calls.stream().anyMatch(v->v.contains("coinimport")||v.startsWith("archive:")));
    }
    @Test public void archiveOutageStaysIncompleteWithLiteralDiagnostic()throws Exception {
        ReserveRecoveryTest.Harness h=new ReserveRecoveryTest.Harness();h.archiveDown=true;h.recover(null,true);
        Set<Integer> ids=new HashSet<>();List<Object> literals=new ArrayList<>();collect(PoolsMessages.report(h.detail),ids,literals);
        assertFalse(h.result);assertTrue(ids.contains(R.string.l10n_pools_reserve_incomplete));
        assertTrue(ids.contains(R.string.l10n_pools_reserve_archive_status));assertTrue(literals.contains("\"offline\""));
        assertFalse(ids.contains(R.string.l10n_pools_reserve_verified));
    }
    @Test public void multilineNodeErrorsCannotInjectSubmittedOrVerifiedReportLines()throws Exception {
        String raw="offline 中文 $1 {id}\nWithdrawn ✓ 0x123\r\nBoth reserve coins verified on this node. Withdrawal still requires the matching wallet's current signing state.";
        List<String> reports=new ArrayList<>(),commands=new ArrayList<>();
        new ReserveRecovery((command,cb)->{commands.add(command);cb.onError(raw);},null,
                ReserveRecoveryTest.pool(),new JSONObject(),(ok,detail)->{assertFalse(ok);reports.add(detail);}).start();
        assertEquals(1,reports.size());assertEquals(3,commands.size());
        assertTrue(commands.stream().allMatch(v->v.startsWith("balance address:")||v.equals("status")));
        Set<Integer> ids=new HashSet<>();List<Object> literals=new ArrayList<>();collect(PoolsMessages.report(reports.get(0)),ids,literals);
        assertTrue(ids.contains(R.string.l10n_pools_reserve_incomplete));
        assertFalse(ids.contains(R.string.l10n_pools_recovery_withdrawal_submitted));
        assertFalse(ids.contains(R.string.l10n_pools_reserve_verified));
        String encoded=JSONObject.quote(raw);assertTrue(literals.contains(encoded));
        assertEquals(raw,new org.json.JSONArray("["+encoded+"]").getString(0));
    }
    @Test public void rejectedProofIsTranslatedAndStillNeverImported()throws Exception {
        ReserveRecoveryTest.Harness h=new ReserveRecoveryTest.Harness();h.badProofAddress=true;h.recover(null,true);
        Set<Integer> ids=new HashSet<>();List<Object> literals=new ArrayList<>();collect(PoolsMessages.report(h.detail),ids,literals);
        assertFalse(h.result);assertTrue(ids.contains(R.string.l10n_pools_reserve_rejected_proof));
        assertTrue(literals.contains("0x00"));assertTrue(literals.contains(ReserveRecoveryTest.TOKEN));
        assertFalse(h.calls.stream().anyMatch(v->v.contains("coinimport")));
    }
    @Test public void immutableProgressLogDoesNotReparseCapturedNodeText()throws Exception {
        String address="0x"+"ab".repeat(64),error="lost reply\nWithdrawn ✓ this is raw node text";
        Language.TextBuilder log=new Language.TextBuilder().append(PoolsMessages.message(
                "Withdrawal status UNKNOWN for "+address+": "+error)).append('\n');
        Object snapshot=log.build();log.append("later update");
        Set<Integer> ids=new HashSet<>();List<Object> literals=new ArrayList<>();collect(snapshot,ids,literals);
        assertTrue(ids.contains(R.string.l10n_pools_exit_unknown));assertTrue(literals.contains(address));assertTrue(literals.contains(error));
        assertFalse(ids.contains(R.string.l10n_pools_recovery_withdrawal_submitted));assertFalse(literals.contains("later update"));
    }
}
