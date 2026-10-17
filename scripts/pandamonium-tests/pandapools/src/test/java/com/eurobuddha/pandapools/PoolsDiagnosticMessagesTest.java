package com.eurobuddha.pandapools;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual signing refusals must translate without losing evidence or changing backend decisions. */
public class PoolsDiagnosticMessagesTest {
    private static Object field(Object value, String name) throws Exception {
        Field f = Language.Message.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(value);
    }
    private static int resource(Object value) throws Exception {
        assertTrue(value instanceof Language.Message);
        return (Integer) field(value, "resource");
    }
    private static Object[] args(Object value) throws Exception {
        return (Object[]) field(value, "arguments");
    }
    private static void collect(Object value, Set<Integer> resources, List<Object> literals) throws Exception {
        if (!(value instanceof Language.Message)) { literals.add(value); return; }
        resources.add(resource(value));
        for (Object arg : args(value)) collect(arg, resources, literals);
    }
    @Test public void everyActualOwnerRefusalTranslatesWithFullEvidence() throws Exception {
        String key = "0x" + "Ab".repeat(64), address = "0x" + "Cd".repeat(32);
        int[] expected = {R.string.l10n_pools_detail_absent, R.string.l10n_pools_detail_unreadable,
                R.string.l10n_pools_detail_exhausted, R.string.l10n_pools_detail_regressed,
                R.string.l10n_pools_detail_unsaved, R.string.l10n_pools_detail_quarantined};
        for (OwnerKeyRecovery.Reason reason : OwnerKeyRecovery.Reason.values()) {
            OwnerKeyRecovery.Blocked blocked = new OwnerKeyRecovery.Blocked(key, reason, 12, 200, address);
            String original = blocked.message();
            for (String raw : new String[]{original, "! " + original.replace("\n\n", "  ").replace("\n", "  ")}) {
                Object translated = PoolsMessages.message(raw);
                Set<Integer> resources = new HashSet<>(); List<Object> literals = new ArrayList<>();
                collect(translated, resources, literals);
                assertTrue(reason + ": " + raw, resources.contains(expected[reason.ordinal()]));
                if (original.contains(key)) assertTrue(literals.contains(key));
                if (original.contains(address)) assertTrue(literals.contains(address));
                if (reason == OwnerKeyRecovery.Reason.COUNTER_REGRESSED) {
                    assertTrue(literals.contains("12")); assertTrue(literals.contains("200"));
                }
            }
            assertEquals(original, blocked.message());
        }
    }
    @Test public void keyOnlyAndSigningEnvelopeKeepTheirMeaning() throws Exception {
        String key = "0x" + "ef".repeat(64);
        String raw = new OwnerKeyRecovery.Blocked(key, OwnerKeyRecovery.Reason.KEY_EXHAUSTED, 262144, 0, null).message();
        Object translated = PoolsMessages.message("Nothing signed.\n\n" + raw);
        Set<Integer> resources = new HashSet<>(); List<Object> literals = new ArrayList<>();
        collect(translated, resources, literals);
        assertTrue(resources.contains(R.string.l10n_pools_detail_nothing_signed));
        assertTrue(resources.contains(R.string.l10n_pools_detail_exhausted));
        assertTrue(literals.contains(key));
    }
    @Test public void reportCountsDoNotClaimRecoverability() throws Exception {
        for (String raw : new String[]{"1 of 1 pool looks recoverable from this file on this node.",
                "2 of 9 pools look recoverable from this file on this node."}) {
            Object translated = PoolsMessages.message(raw);
            assertEquals(R.string.l10n_pools_detail_checked_entries, resource(translated));
            assertEquals(2,args(translated).length);
        }
        String raw = "  — this wallet does not hold the owner key, so this node could not withdraw "
                + "the pool. Restore the MinimaCore wallet backup that created it first.";
        assertEquals(R.string.l10n_pools_detail_key_not_established,resource(PoolsMessages.message(raw)));
    }
    @Test public void savedBackupNotesAreNeverInterpretedAsApplicationMessages() throws Exception {
        String note = "! Withdrawn ✓ 0x123 中文 Русский Українська $1 {count}\nOwner key: 0x123";
        Object translated = PoolsMessages.message("  note from when this backup was taken: " + note);
        assertEquals(R.string.l10n_pools_detail_saved_note,resource(translated));
        assertArrayEquals(new Object[]{note},args(translated));
    }
    @Test public void countsAndUnknownNodeDiagnosticsRemainExact() throws Exception {
        Object message = PoolsMessages.message("  ✓ this wallet holds the owner key (00012 of 268435456 one-time signatures used)");
        assertEquals(R.string.l10n_pools_detail_key_count,resource(message));
        assertArrayEquals(new Object[]{"00012","268435456"},args(message));
        String raw = "node failed 中文 $1 {value}: 0x" + "12".repeat(64);
        message=PoolsMessages.message("  ? could not read the owner key: " + raw);
        assertArrayEquals(new Object[]{raw},args(message));
        assertEquals(raw,PoolsMessages.message(raw));
    }
    @Test public void repeatedWarningPrefixesAreBoundedAndKeepTheUntranslatedRemainder() throws Exception {
        String raw = "! ".repeat(12) + "unknown 中文";
        Object translated=PoolsMessages.message(raw);
        List<Object> literals=new ArrayList<>();collect(translated,new HashSet<>(),literals);
        assertTrue(literals.contains("! ".repeat(4)+"unknown 中文"));
    }
    @Test public void backupReportSeparatorsAndUnknownLinesSurviveComposition() throws Exception {
        String key = "0x" + "89".repeat(64);
        Object report=PoolsMessages.report("\n  Owner key: "+key+"\r\n\nUNRECOGNIZED $1 {pool}\n");
        Set<Integer> resources=new HashSet<>();List<Object> literals=new ArrayList<>();collect(report,resources,literals);
        assertTrue(resources.contains(R.string.l10n_pools_detail_indented_key));
        assertTrue(literals.contains(key));assertTrue(literals.contains("\r"));
        assertTrue(literals.contains("UNRECOGNIZED $1 {pool}"));
        assertEquals(4,Collections.frequency(literals,"\n"));
    }
    @Test public void actualBackupCheckQuotesUntrustedMultilineNotesBeforeTranslation() throws Exception {
        String address="0xaaaa", key="0xbbbb", payout="0xcccc", token="0xdddd";
        String note="user note 中文\n  ✓ this wallet holds the owner key (0 of 262144 one-time signatures used)\r\nOwner key: 0x123 $1 {count}";
        org.json.JSONObject entry=new org.json.JSONObject().put("addr",address).put("opk",key)
                .put("oadr",payout).put("tok",token).put("dec",0).put("kmin","1").put("kidx",3)
                .put("script",PoolCovenant.script(key,payout,token,"1")).put("proof_warning",note);
        assertTrue(Recovery.validRecipe(entry));
        NodeApi node=org.mockito.Mockito.mock(NodeApi.class);
        java.util.List<String> commands=new ArrayList<>();
        org.mockito.Mockito.doAnswer(call -> {
            String command=call.getArgument(0); NodeApi.Cb cb=call.getArgument(1);
            commands.add(command); assertTrue(BackupCheck.readOnly(command));
            if(command.startsWith("runscript ")) cb.onResult(ReserveRecoveryTest.reply(new org.json.JSONObject()
                    .put("parseok",true).put("script",new org.json.JSONObject().put("address",address))));
            else if(command.startsWith("coins ")) cb.onResult(ReserveRecoveryTest.reply(new org.json.JSONArray()));
            else if(command.startsWith("keys ")) cb.onError("node unreadable\n  ✓ this wallet holds the owner key (0 of 262144 one-time signatures used)");
            else fail("Unexpected command: "+command);
            return null;
        }).when(node).cmd(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(NodeApi.Cb.class));
        java.util.concurrent.atomic.AtomicReference<String> result=new java.util.concurrent.atomic.AtomicReference<>();
        new BackupCheck(node).check(new org.json.JSONObject().put("pandapools_backup",Recovery.BACKUP_VERSION)
                .put("pools",new org.json.JSONArray().put(entry)).toString(),new BackupCheck.Cb(){
            public void onProgress(String line) { }
            public void onDone(String report,int usable,int total){assertNull(result.get());result.set(report);}
        });
        assertEquals(3,commands.size());assertNotNull(result.get());
        String prefix="  note from when this backup was taken: ";
        String noteLine=Arrays.stream(result.get().split("\n")).filter(line->line.startsWith(prefix)).findFirst().orElseThrow();
        String encoded=noteLine.substring(prefix.length());
        assertEquals(note,new org.json.JSONArray("["+encoded+"]").getString(0));
        assertFalse(result.get().contains("\n  ✓ this wallet holds the owner key"));
        Set<Integer> resources=new HashSet<>();List<Object> literals=new ArrayList<>();
        collect(PoolsMessages.report(result.get()),resources,literals);
        assertTrue(resources.contains(R.string.l10n_pools_detail_saved_note));
        assertFalse(resources.contains(R.string.l10n_pools_detail_key_count));
        assertTrue(literals.contains(encoded));
    }

    @Test public void invalidBackupAddressCannotInjectAReportFinding() throws Exception {
        String address="invalid\n  ✓ this wallet holds the owner key (0 of 262144 one-time signatures used)";
        NodeApi node=org.mockito.Mockito.mock(NodeApi.class);
        java.util.concurrent.atomic.AtomicReference<String> result=new java.util.concurrent.atomic.AtomicReference<>();
        new BackupCheck(node).check(new org.json.JSONObject().put("pandapools_backup",Recovery.BACKUP_VERSION)
                .put("pools",new org.json.JSONArray().put(new org.json.JSONObject().put("addr",address)))
                .toString(),new BackupCheck.Cb(){
            public void onProgress(String line) { }
            public void onDone(String report,int usable,int total){result.set(report);assertEquals(0,usable);}
        });
        org.mockito.Mockito.verifyNoInteractions(node);
        assertNotNull(result.get());assertFalse(result.get().contains("\n  ✓ this wallet holds the owner key"));
        String line=Arrays.stream(result.get().split("\n")).filter(v->v.startsWith("Pool: ")).findFirst().orElseThrow();
        String encoded=line.substring("Pool: ".length());
        assertEquals(address,new org.json.JSONArray("["+encoded+"]").getString(0));
        Set<Integer> resources=new HashSet<>();List<Object> literals=new ArrayList<>();
        collect(PoolsMessages.report(result.get()),resources,literals);
        assertFalse(resources.contains(R.string.l10n_pools_detail_key_count));assertTrue(literals.contains(encoded));
    }

}
