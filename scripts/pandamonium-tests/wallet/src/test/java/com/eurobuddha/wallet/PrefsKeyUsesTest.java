package com.eurobuddha.wallet;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real counter code with in-memory Android persistence; no node, seed or signing. */
public class PrefsKeyUsesTest {
    static final class Mirror {
        final Map<String,Object> values = new ConcurrentHashMap<>();
        final SharedPreferences prefs = mock(SharedPreferences.class);
        volatile boolean fail;
        Mirror() {
            when(prefs.getInt(anyString(), anyInt())).thenAnswer(a -> {
                Object value = values.get(a.getArgument(0));
                return value == null ? a.getArgument(1) : (Integer)value;
            });
            when(prefs.getAll()).thenAnswer(a -> new HashMap<>(values));
            when(prefs.edit()).thenAnswer(a -> {
                Map<String,Object> pending = new HashMap<>();
                SharedPreferences.Editor edit = mock(SharedPreferences.Editor.class);
                when(edit.putInt(anyString(), anyInt())).thenAnswer(b -> {
                    pending.put(b.getArgument(0), b.getArgument(1)); return edit;
                });
                when(edit.commit()).thenAnswer(b -> {
                    if (fail) return false;
                    values.putAll(pending); return true;
                });
                return edit;
            });
        }
    }
    static final class Store {
        final Context context = mock(Context.class);
        final Mirror a = new Mirror(), b = new Mirror();
        Store() {
            when(context.getApplicationContext()).thenReturn(context);
            when(context.getSharedPreferences("pandamonium_wallet_keyuses_a", 0)).thenReturn(a.prefs);
            when(context.getSharedPreferences("pandamonium_wallet_keyuses_b", 0)).thenReturn(b.prefs);
        }
        PrefsKeyUses open(String namespace) { return new PrefsKeyUses(context, namespace); }
    }

    @Test public void seedNamespacesSurviveSwitchingAndStaleRestore() {
        Store s = new Store();
        PrefsKeyUses first = s.open("classic_seed_a"), second = s.open("classic_seed_b");
        first.recordExternalUses(0, 137);
        first.recordExternalUses(63, 91);
        assertEquals(137, first.reserveNextUse(0));
        assertEquals(0, second.reserveNextUse(0));
        PrefsKeyUses reopened = s.open("classic_seed_a");
        reopened.recordExternalUses(0, 2);
        assertEquals(138, reopened.currentUses(0));
        assertEquals(91, reopened.currentUses(63));
        assertEquals(2, reopened.snapshotAllUses().size());
        assertEquals(Collections.singletonMap(0, 1), second.snapshotAllUses());
    }

    @Test public void independentInstancesCannotReserveTheSameLeaf() throws Exception {
        Store s = new Store();
        Set<Integer> leaves = ConcurrentHashMap.newKeySet();
        ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> work = new ArrayList<>();
            for (int i=0; i<4; i++) work.add(() -> {
                PrefsKeyUses uses = s.open("classic_seed_a");
                for (int j=0; j<25; j++) assertTrue(leaves.add(uses.reserveNextUse(0)));
                return null;
            });
            for (Future<Void> f : exec.invokeAll(work)) f.get(10, TimeUnit.SECONDS);
            assertEquals(100, leaves.size());
            assertEquals(100, s.open("classic_seed_a").currentUses(0));
        } finally { exec.shutdownNow(); }
    }

    @Test public void eitherFailedMirrorRefusesToReturnALeafAndSkipsItOnRetry() {
        for (boolean first : new boolean[]{true,false}) {
            Store s = new Store();
            PrefsKeyUses uses = s.open("classic_seed_a");
            uses.recordExternalUses(0, 12);
            (first ? s.a : s.b).fail = true;
            assertThrows(IllegalStateException.class, () -> uses.reserveNextUse(0));
            assertEquals(13, s.open("classic_seed_a").currentUses(0));
            s.a.fail = s.b.fail = false;
            assertEquals(13, s.open("classic_seed_a").reserveNextUse(0));
            assertEquals(14, uses.currentUses(0));
        }
    }

    @Test public void mirrorsReconcileMaximumAndNeverLowerAnExternalValue() {
        Store s = new Store();
        s.a.values.put("uses_classic_seed_a_0", 5);
        s.b.values.put("uses_classic_seed_a_0", 9);
        PrefsKeyUses uses = s.open("classic_seed_a");
        assertEquals(9, uses.currentUses(0));
        assertEquals(9, uses.raiseTo(0, 1));
        uses.recordExternalUses(0, 2);
        assertEquals(9, s.a.values.get("uses_classic_seed_a_0"));
        assertEquals(9, s.b.values.get("uses_classic_seed_a_0"));
    }

    @Test public void corruptNegativeOrWrongTypeEvidenceCannotBecomeZero() {
        for (Object bad : new Object[]{-1,"broken",Long.MAX_VALUE}) {
            Store s = new Store();
            s.a.values.put("uses_classic_seed_a_0", bad);
            s.b.values.put("uses_classic_seed_a_0", 37);
            PrefsKeyUses uses = s.open("classic_seed_a");
            assertThrows(RuntimeException.class, () -> uses.currentUses(0));
            assertThrows(RuntimeException.class, () -> uses.reserveNextUse(0));
            assertThrows(RuntimeException.class, uses::snapshotAllUses);
            assertEquals(bad, s.a.values.get("uses_classic_seed_a_0"));
        }
    }

    @Test public void negativeInputsAndExhaustionCannotAdvanceOrWrap() {
        Store s = new Store();
        PrefsKeyUses uses = s.open("classic_seed_a");
        assertThrows(IllegalArgumentException.class, () -> uses.recordExternalUses(0,-1));
        assertThrows(IllegalArgumentException.class, () -> uses.raiseTo(0,-1));
        assertThrows(IllegalArgumentException.class, () -> uses.reserveNextUse(-1));
        uses.recordExternalUses(0,262143);
        assertEquals(262143,uses.reserveNextUse(0));
        assertThrows(IllegalStateException.class, () -> uses.reserveNextUse(0));
        assertEquals(262144,uses.currentUses(0));
        uses.recordExternalUses(0,Integer.MAX_VALUE);
        assertThrows(IllegalStateException.class, () -> uses.reserveNextUse(0));
        assertEquals(Integer.MAX_VALUE,uses.currentUses(0));
    }
}
