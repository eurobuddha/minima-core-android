    /** Atomically reserve the exact agreed snapshot before asking either engine to lock leg 1. */
    public void executeDeal(OtcDb.Deal d) {
        if (!sameMarket(d) || !ready() || !OtcDb.ROLE_INSTIGATOR.equals(d.role)) return;
        OtcDb.Deal current = otcDb.getDeal(d.ref);
        if (!sameMarket(current) || !validDeal(current) || !OtcDb.ST_AGREED.equals(current.status)
                || !OtcDb.sameSnapshot(d, current)) return;
        final OtcDb.Deal executing = current.copy(); executing.status = OtcDb.ST_EXECUTING;
        if (!otcDb.replaceDeal(current, executing)) return;
        ui.onDealsChanged();
        engine.executeOtcDeal(executing.copy(), new SwapEngine.StartCb() {
            private OtcDb.Deal prepared;
            private boolean finished;
            /** Reuse PandaDEX's persist-before-submit callback. Called on either engine thread. */
            @Override public synchronized boolean onPrepared(String hash) {
                if (finished || prepared != null || hash == null
                        || !OtcDb.normHash(hash).matches("[0-9a-f]{64}")) return false;
                OtcDb.Deal next = executing.copy(); next.hash = hash;
                if (!otcDb.replaceDeal(executing, next)) return false;
                prepared = next;
                return true;
            }
            @Override public synchronized void ok(String hash) {
                if (finished || prepared == null || !OtcDb.normHash(hash).equals(prepared.hash)
                        || !OtcDb.sameSnapshot(prepared, otcDb.getDeal(prepared.ref))) return;
                finished = true;
                sendMsg(prepared, OtcMessage.EXECUTE, prepared.hash, new SendResult() { public void ok() {} public void err(String m) {} });
                ui.note("OTC executing", "Locked your side — waiting for the counterparty");
                ui.onDealsChanged();
            }
            @Override public synchronized void err(String m) {
                if (finished) return;
                finished = true;
                if (prepared != null) {
                    // The first leg may already be on-chain. Keep its identity through restart;
                    // expireStale/resendExecute and the swap watcher recover this exact attempt.
                    if (!OtcDb.sameSnapshot(prepared, otcDb.getDeal(prepared.ref))) return;
                    ui.onDealsChanged();
                    ui.note("OTC executing", "Submission outcome is unknown. The saved swap will be checked; do not create a replacement deal.");
                    return;
                }
                OtcDb.Deal retry = executing.copy(); retry.status = OtcDb.ST_AGREED;
                if (!otcDb.replaceDeal(executing, retry)) return;
                ui.onDealsChanged();
                ui.note("OTC execute failed", m);
            }
        });
    }

