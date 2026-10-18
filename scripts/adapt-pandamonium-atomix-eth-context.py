"""Retain responder consent through ETH RPC reads and the existing nonce serializer."""
from pathlib import Path

def adapt(root):
    base=root/'atomix/src/main/java/com/eurobuddha/atomix'
    p=base/'eth/EthTx.java';s=p.read_text()
    def change(old,new,count=1):
        nonlocal s
        assert s.count(old)==count,('AtomiX ETH context drift',p.name,old,s.count(old))
        s=s.replace(old,new)
    change('''                              String to, String data, BigInteger value, BigInteger gasLimit) throws Exception {
        NonceState st''','''                              String to, String data, BigInteger value, BigInteger gasLimit) throws Exception {
        return send(rpc, creds, chainId, to, data, value, gasLimit, () -> true);
    }

    /** The existing nonce lock and fee calculation are unchanged; only unstarted writes can be cancelled. */
    public static String send(EthRpc rpc, Credentials creds, long chainId,
                              String to, String data, BigInteger value, BigInteger gasLimit,
                              java.util.function.BooleanSupplier authorized) throws Exception {
        requireAuthorized(authorized);
        NonceState st''')
    change('        synchronized (st) {\n            long pending', '        synchronized (st) {\n            requireAuthorized(authorized);\n            long pending')
    change('            RawTransaction raw = RawTransaction.createTransaction(',
           '            requireAuthorized(authorized);\n            RawTransaction raw = RawTransaction.createTransaction(')
    change('            String txHash = rpc.sendRawTransaction(',
           '            requireAuthorized(authorized);\n            String txHash = rpc.sendRawTransaction(')
    anchor='    /** Forget all cached nonce state'
    change(anchor,'''    /** Matches NodeApi's fail-closed BooleanSupplier contract. No signed transaction is sent on refusal. */
    public static void requireAuthorized(java.util.function.BooleanSupplier authorized) {
        boolean allowed;
        try { allowed = authorized != null && authorized.getAsBoolean(); }
        catch (RuntimeException failure) { allowed = false; }
        if (!allowed) throw new IllegalStateException(com.eurobuddha.atomix.integratedcomms.NodeApi.ERR_COMMAND_CHANGED);
    }

'''+anchor)
    p.write_text(s)

    p=base/'eth/EthHtlc.java';s=p.read_text()
    change('    private final EthNet net;', '    private final EthNet net;\n    private final java.util.function.BooleanSupplier authorized;')
    change('''        this.rpc = rpc; this.creds = creds; this.net = net;
    }''','''        this(rpc, creds, net, () -> true);
    }
    /** Authorization applies only to new approvals/locks; existing claim/refund recovery stays available. */
    public EthHtlc(EthRpc rpc, Credentials creds, EthNet net, java.util.function.BooleanSupplier authorized) {
        this.rpc = rpc; this.creds = creds; this.net = net; this.authorized = authorized;
    }''')
    change('FunctionEncoder.encode(f), null, GAS_APPROVE);','FunctionEncoder.encode(f), null, GAS_APPROVE, authorized);')
    change('FunctionEncoder.encode(f), null, GAS_LOCK);','FunctionEncoder.encode(f), null, GAS_LOCK, authorized);')
    p.write_text(s)

    p=base/'swap/SwapEngine.java';s=p.read_text()
    change('    void checkCanSwapCoin(JSONObject coin, int block) {','''    void checkCanSwapCoin(JSONObject coin, int block) {
        final com.eurobuddha.atomix.TradingContext market = com.eurobuddha.atomix.TradingContext.active();
        final long generation = PublishGate.gen();''')
    change('''        if (!inflight.add("cpEth:" + hash)) return;
        // CR-2''','''        final JSONObject operationCoin;
        try { operationCoin = new JSONObject(coin.toString()); }
        catch (org.json.JSONException invalid) { return; }
        final java.util.function.BooleanSupplier authorized = ethCounterLegGuard(operationCoin, hash, reqTokenAddr, market, generation);
        if (!authorized.getAsBoolean() || !inflight.add("cpEth:" + hash)) return;
        // CR-2''')
    change('submitIo(() -> lockEthCounterLeg(coin, hash, reqTokenAddr),',
           'submitIo(() -> lockEthCounterLeg(operationCoin, hash, reqTokenAddr, market, authorized),')
    change('    private void lockEthCounterLeg(JSONObject coin, String hash, String reqTokenAddr) {',
           (Path(__file__).resolve().parent/'pandamonium-sources/atomix/eth-responder-context.inc').read_text()+'''    private void lockEthCounterLeg(JSONObject coin, String hash, String reqTokenAddr,
                                   com.eurobuddha.atomix.TradingContext market,
                                   java.util.function.BooleanSupplier authorized) {''')
    start=s.index('    private void lockEthCounterLeg(');end=s.index('    // ---- Ethereum side',start)
    part=s[start:end]
    part=part.replace('            if (halted()) { inflight.remove("cpEth:" + hash); return; }   // belt: never commit ETH while mismatched',
                      '            EthTx.requireAuthorized(authorized);')
    part=part.replace('new EthHtlc(rpc, creds, net);','new EthHtlc(rpc, creds, net, authorized);')
    part=part.replace('            // CR-1: record the swap row', '            EthTx.requireAuthorized(authorized);\n            // CR-1: record the swap row')
    part=part.replace('com.eurobuddha.atomix.TradingContext.active().coinLabel','market.coinLabel')
    s=s[:start]+part+s[end:];p.write_text(s)

    # Reuse DirectNodeApi.Request's queued/executing/completed ownership for shutdown rejection callbacks.
    change('    public void shutdown() { io.shutdownNow(); }','''    public void shutdown() {
        for (Runnable task : io.shutdownNow()) if (task instanceof IoTask) ((IoTask) task).cancelQueued();
    }''')
    change('''    private void submitIo(Runnable task, Runnable onReject) {
        if (io.isShutdown()) { if (onReject != null) onReject.run(); return; }
        try { io.execute(task); }
        catch (java.util.concurrent.RejectedExecutionException e) { if (onReject != null) onReject.run(); }
    }''','''    private static final class IoTask implements Runnable {
        final Runnable task, onReject;
        final java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger();
        IoTask(Runnable task, Runnable onReject) { this.task = task; this.onReject = onReject; }
        public void run() {
            if (!state.compareAndSet(0, 1)) return;
            try { task.run(); } finally { state.set(2); }
        }
        void cancelQueued() { if (state.compareAndSet(0, 2) && onReject != null) onReject.run(); }
    }
    private void submitIo(Runnable task, Runnable onReject) {
        IoTask queued = new IoTask(task, onReject);
        if (io.isShutdown()) { queued.cancelQueued(); return; }
        try { io.execute(queued); }
        catch (java.util.concurrent.RejectedExecutionException e) { queued.cancelQueued(); }
    }''')
    p.write_text(s)
