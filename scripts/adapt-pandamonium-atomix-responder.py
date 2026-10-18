"""Carry the existing market/publication guard through responder coin reads and signing."""
from pathlib import Path

def adapt(root):
    folder=root/'atomix/src/main/java/com/eurobuddha/atomix/swap'
    p=folder/'MinimaHtlc.java';s=p.read_text()
    def change(old,new,count=1):
        nonlocal s
        assert s.count(old)==count,('AtomiX responder adapter drift',p.name,old,s.count(old))
        s=s.replace(old,new)
    signature='''                              int timelockBlock, String otc, PostCb cb) {
        if (!ready())'''
    change(signature,'''                              int timelockBlock, String otc, PostCb cb) {
        lockFromCoins(coinids, totalSelected, amount, requestAmount, reqToken, receiverPubkey,
                ownerEthKey, hashlock, timelockBlock, otc, null, cb);
    }

    /** New responder liabilities retain their authorization through SignGate and the node worker. */
    public void lockFromCoins(List<String> coinids, String totalSelected, String amount, String requestAmount,
                              String reqToken, String receiverPubkey, String ownerEthKey, String hashlock,
                              int timelockBlock, String otc, java.util.function.BooleanSupplier authorized, PostCb cb) {
        final String token = activeToken;
        final java.util.function.BooleanSupplier current = authorized == null ? null
                : () -> token.equals(activeToken) && authorized.getAsBoolean();
        if (!ready())''')
    start=s.index('    /** New responder liabilities');end=s.index('    // Measured MxUSD',start)
    part=s[start:end]
    part=part.replace('tokenid:" + activeToken','tokenid:" + token')
    part=part.replace('runSeq(seq, built -> cmd("txnpost id:" + id + " mine:true txndelete:true",',
                     'runSeq(seq, current, built -> cmd("txnpost id:" + id + " mine:true txndelete:true", current,')
    part=part.replace('cb.err("POSTED:" + e)', 'cb.err(NodeApi.ERR_COMMAND_CHANGED.equals(e) ? e : "POSTED:" + e)')
    s=s[:start]+part+s[end:]
    change('''        node.cmd(command, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {''','''        cmd(command, null, ok, err);
    }
    private void cmd(String command, java.util.function.BooleanSupplier authorized,
                     Consumer<JSONObject> ok, Consumer<String> err) {
        NodeApi.Cb callback = new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {''')
    change('''            @Override public void onError(String m) { err.accept(m); }
        });
    }

    /**
     * Run a command sequence''','''            @Override public void onError(String m) { err.accept(m); }
        };
        if (authorized == null) node.cmd(command, callback);
        else node.cmd(command, authorized, callback);
    }

    /**
     * Run a command sequence''')
    change('''        com.eurobuddha.atomix.integratedcomms.SignGate.submit(gate -> runSeqAt(cmds, 0,
''','''        runSeq(cmds, null, finalOk, err);
    }
    private void runSeq(List<String> cmds, java.util.function.BooleanSupplier authorized,
                        Consumer<JSONObject> finalOk, Consumer<String> err) {
        com.eurobuddha.atomix.integratedcomms.SignGate.submit(gate -> runSeqAt(cmds, 0, authorized,
''')
    change('''    private void runSeqAt(List<String> cmds, int i, Consumer<JSONObject> finalOk, Consumer<String> err) {
        cmd(cmds.get(i), resp -> {''','''    private void runSeqAt(List<String> cmds, int i, java.util.function.BooleanSupplier authorized,
                          Consumer<JSONObject> finalOk, Consumer<String> err) {
        cmd(cmds.get(i), authorized, resp -> {''')
    change('else runSeqAt(cmds, i + 1, finalOk, err);','else runSeqAt(cmds, i + 1, authorized, finalOk, err);')
    p.write_text(s)

    p=folder/'SwapEngine.java';s=p.read_text()
    sources=Path(__file__).resolve().parent/'pandamonium-sources/atomix'
    change('        if (halted()) return;   // node/app key mismatch — take on no new counter-leg liability',
           '        if (io.isShutdown() || halted()) return;   // stopped host/key mismatch: no new liability',2)
    change('    private void releaseCpLeg(String hash) {',(sources/'responder-context.inc').read_text()+'    private void releaseCpLeg(String hash) {')
    change('synchronized (CP_LOCKING) { cpInFlight.remove(hash); cpLockSince.remove(hash); CP_LOCKING.remove(hash); }',
           'synchronized (CP_LOCKING) { cpInFlight.remove(hash); cpLockSince.remove(hash); CP_LOCKING.remove(hash); cpAttempts.remove(hash); }')
    change('            CP_LOCKING.entrySet().removeIf(e -> nowUnix() - e.getValue() > CP_LOCK_TIMEOUT_SECS);',
           '            CP_LOCKING.entrySet().removeIf(e -> nowUnix() - e.getValue() > CP_LOCK_TIMEOUT_SECS);\n            cpAttempts.keySet().retainAll(CP_LOCKING.keySet());')
    change('''            confirmMyLock(h, true, onChain -> { if (onChain) { releaseCpLeg(h); inflight.remove("cpMin:" + h); } });''','''            final CounterLeg attempt;
            synchronized (CP_LOCKING) { attempt = cpAttempts.get(h); }
            confirmMyLock(h, true, onChain -> {
                synchronized (CP_LOCKING) {
                    if (onChain && cpAttempts.get(h) == attempt) { releaseCpLeg(h); inflight.remove("cpMin:" + h); }
                }
            });''')
    change('    private void checkCanCollectEth(EthHtlc eth, EthHtlc.Contract c, int minimaBlock) throws Exception {',
           '    private void checkCanCollectEth(EthHtlc eth, EthHtlc.Contract c, int minimaBlock) throws Exception {\n        final com.eurobuddha.atomix.TradingContext market = com.eurobuddha.atomix.TradingContext.active();\n        final long generation = PublishGate.gen();')
    change('        lockMinimaCounterLeg(c, minimaBlock);','        lockMinimaCounterLeg(c, minimaBlock, market, generation);')
    change('    private void lockMinimaCounterLeg(EthHtlc.Contract c, int minimaBlock) {',
           '    private void lockMinimaCounterLeg(EthHtlc.Contract c, int minimaBlock,\n                                      com.eurobuddha.atomix.TradingContext market, long generation) {')
    start=s.index('    private void lockMinimaCounterLeg(');end=s.index('    // ============================================================ order-match',start)
    part=s[start:end]
    part=part.replace('        final String hash = c.hashlock;', '''        final String hash = c.hashlock;
        final CounterLeg attempt;
        synchronized (CP_LOCKING) {
            if (!CP_LOCKING.containsKey(hash) || !cpInFlight.containsKey(hash) || cpAttempts.containsKey(hash)) return;
            attempt = new CounterLeg(c, market, generation);
            cpAttempts.put(hash, attempt);
        }
        if (!attempt.current()) { attempt.release(); return; }''')
    part=part.replace('releaseCpLeg(hash); inflight.remove("cpMin:" + hash);','attempt.release();')
    part=part.replace('        ui.post(() -> minima.myFreeCoins(coins -> {', '''        if (!ui.post(() -> {
            if (!attempt.current() || !attempt.terms()) { attempt.release(); return; }
            minima.myFreeCoins(coins -> {
            if (!attempt.current() || !attempt.terms()) { attempt.release(); return; }''')
    part=part.replace('            synchronized (CP_LOCKING) {                       // single monitor for all cpInFlight mutations', '''            synchronized (CP_LOCKING) {                       // single monitor for all cpInFlight mutations
                if (!attempt.current()) { attempt.release(); return; }''')
    part=part.replace('com.eurobuddha.atomix.TradingContext.active().coinLabel','attempt.market.coinLabel')
    part=part.replace('            db.upsertSwap(s);', '''            synchronized (CP_LOCKING) {
                if (!attempt.current() || !attempt.terms()) { attempt.release(); return; }
                try { db.upsertSwap(s); }
                catch (RuntimeException storageFailure) {
                    attempt.release(); SwapLog.w("Counter-leg could not be saved before signing"); return;
                }
            }''')
    part=part.replace('myEth(), hash, timelock, "FALSE", new MinimaHtlc.PostCb()',
                      'myEth(), hash, timelock, "FALSE", attempt::authorized, new MinimaHtlc.PostCb()')
    part=part.replace('                    inflight.remove("cpMin:" + hash);\n                    // keep', '''                    synchronized (CP_LOCKING) {
                        if (attempt.owned()) inflight.remove("cpMin:" + hash);
                    }
                    // keep''')
    part=part.replace('''                    inflight.remove("cpMin:" + hash);
                    // Retry ONLY''','''                    synchronized (CP_LOCKING) {
                    if (!attempt.owned()) return;
                    // Retry ONLY''')
    part=part.replace('                    releaseCpLeg(hash);','                    attempt.release();\n                    }')
    part=part.replace('        }));\n    }','        });\n        })) attempt.release();\n    }')
    part=part.replace('        }, e -> {\n            attempt.release();', '        }, e -> {\n            boolean report = attempt.current();\n            attempt.release();\n            if (!report) return;')
    lines=part.splitlines()
    begin=next(i for i,line in enumerate(lines) if line.strip()=='minima.myFreeCoins(coins -> {')+1
    last_line=next(i for i in range(begin,len(lines)) if lines[i].strip()=='})) attempt.release();')
    for i in range(begin,last_line): lines[i]='    '+lines[i] if lines[i] else lines[i]
    part='\n'.join(lines)+'\n'
    s=s[:start]+part+s[end:];p.write_text(s)

    p=folder/'SwapDb.java';s=p.read_text()
    change('        helper.getWritableDatabase().insertWithOnConflict("swaps", null, v, SQLiteDatabase.CONFLICT_REPLACE);',
           '        if (helper.getWritableDatabase().insertWithOnConflict("swaps", null, v, SQLiteDatabase.CONFLICT_REPLACE) < 0)\n            throw new android.database.sqlite.SQLiteException("Swap could not be saved");')
    p.write_text(s)

    # txnpost returns the same top-level TxPoW identity as send (native txnpost.runCommand).
    p=root/'atomix/src/main/java/com/eurobuddha/comms/WriteSafety.java';s=p.read_text()
    change('return !verb.equals("send") || command.matches(', 'return !(verb.equals("send") || verb.equals("txnpost")) || verb.equals("send") && command.matches(')
    change('// Other commands retain their existing result contracts. A definite send rejection',
           '// Other commands retain their existing result contracts. A definite send/post rejection')
    p.write_text(s)
