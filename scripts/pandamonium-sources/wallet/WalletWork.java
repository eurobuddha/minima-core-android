package com.eurobuddha.wallet;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** One session-owned operation; executor/handler delivery follows HistoryScreen's worker pattern. */
public final class WalletWork {
    private final Executor worker, delivery;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile Runnable observer;
    private volatile Exception failure;
    public WalletWork(Executor worker, Executor delivery) {
        this.worker = worker; this.delivery = delivery;
    }
    public boolean busy() { return busy.get(); }
    public Exception failure() { return failure; }
    public void observe(Runnable listener) { observer = listener; }
    public void detach(Runnable listener) { if (observer == listener) observer = null; }
    public boolean start(Runnable operation) {
        if (!busy.compareAndSet(false, true)) return false;
        failure = null;
        try {
            worker.execute(() -> {
                Exception problem = null;
                try { operation.run(); } catch (Exception e) { problem = e; }
                final Exception result = problem;
                delivery.execute(() -> {
                    failure = result;
                    busy.set(false);
                    Runnable listener = observer;
                    if (listener != null) listener.run();
                });
            });
        } catch (RuntimeException rejected) {
            busy.set(false);
            throw rejected;
        }
        return true;
    }
}
