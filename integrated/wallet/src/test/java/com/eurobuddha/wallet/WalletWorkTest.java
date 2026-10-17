package com.eurobuddha.wallet;

import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import org.junit.Test;
import static org.junit.Assert.*;

public class WalletWorkTest {
    @Test public void onlyOneOperationRunsUntilItsMainThreadCompletion() {
        List<Runnable> worker = new ArrayList<>(), main = new ArrayList<>();
        WalletWork work = new WalletWork(worker::add, main::add);
        int[] operations={0}, callbacks={0};
        work.observe(() -> callbacks[0]++);
        assertTrue(work.start(() -> operations[0]++));
        assertTrue(work.busy());
        assertFalse(work.start(() -> fail("duplicate operation")));
        assertEquals(0,operations[0]);
        worker.remove(0).run();
        assertTrue(work.busy());
        assertEquals(0,callbacks[0]);
        main.remove(0).run();
        assertFalse(work.busy());
        assertEquals(1,operations[0]);
        assertEquals(1,callbacks[0]);
        assertNull(work.failure());
    }
    @Test public void recreatedScreenReceivesCompletionWithoutRestartingWork() {
        List<Runnable> worker = new ArrayList<>(), main = new ArrayList<>();
        WalletWork work = new WalletWork(worker::add, main::add);
        Runnable old = () -> fail("callback entered destroyed screen");
        int[] delivered={0};
        work.observe(old);
        work.start(() -> {});
        work.detach(old);
        work.observe(() -> delivered[0]++);
        work.detach(old); // late old teardown must not detach its replacement
        worker.remove(0).run(); main.remove(0).run();
        assertEquals(1,delivered[0]);
        assertFalse(work.busy());
    }
    @Test public void failureIsDeliveredAndDoesNotBlockRetry() {
        List<Runnable> worker = new ArrayList<>(), main = new ArrayList<>();
        WalletWork work = new WalletWork(worker::add, main::add);
        work.start(() -> { throw new IllegalArgumentException("disposable failure"); });
        worker.remove(0).run(); main.remove(0).run();
        assertEquals("disposable failure",work.failure().getMessage());
        assertFalse(work.busy());
        assertTrue(work.start(() -> {}));
        assertNull(work.failure());
        worker.remove(0).run(); main.remove(0).run();
        assertFalse(work.busy());
    }
    @Test public void rejectedWorkDoesNotLeaveTheWalletBusy() {
        WalletWork work = new WalletWork(r -> { throw new RejectedExecutionException("fixture"); }, Runnable::run);
        assertThrows(RejectedExecutionException.class, () -> work.start(() -> {}));
        assertFalse(work.busy());
    }
}
