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
            @Override public void ok(String hash) {
                OtcDb.Deal locked = executing.copy(); locked.hash = hash;
                if (!otcDb.replaceDeal(executing, locked)) return;
                sendMsg(locked, OtcMessage.EXECUTE, hash, new SendResult() { public void ok() {} public void err(String m) {} });
                ui.note("OTC executing", "Locked your side — waiting for the counterparty");
                ui.onDealsChanged();
            }
            @Override public void err(String m) {
                OtcDb.Deal retry = executing.copy(); retry.status = OtcDb.ST_AGREED;
                if (!otcDb.replaceDeal(executing, retry)) return;
                ui.onDealsChanged();
                ui.note("OTC execute failed", m);
            }
        });
    }

