    public void counter(OtcDb.Deal d, String amount, String price, SendResult res) {
        if (!sameMarket(d)) { res.err(MARKET_ERROR); return; }
        if (!ready() || !negotiating(d) || !OtcDb.TURN_ME.equals(d.whoseTurn)) { res.err("not your turn"); return; }
        if (!validTerms(amount, price)) { res.err("enter amount + price"); return; }
        OtcDb.Deal next = d.copy();
        next.amount = amount; next.price = price; next.status = OtcDb.ST_COUNTERED; next.whoseTurn = OtcDb.TURN_PEER;
        if (!otcDb.replaceDeal(d, next)) { res.err(CHANGED_ERROR); return; }
        sendMsg(next, OtcMessage.COUNTER, "", res);
    }

    public void accept(OtcDb.Deal d, SendResult res) {
        if (!validDeal(d)) { res.err("enter amount + price"); return; }
        if (!sameMarket(d)) { res.err(MARKET_ERROR); return; }
        if (!ready() || !negotiating(d) || !OtcDb.TURN_ME.equals(d.whoseTurn)) { res.err("not your turn"); return; }
        final OtcDb.Deal agreed = d.copy();
        agreed.status = OtcDb.ST_AGREED;
        agreed.whoseTurn = OtcDb.ROLE_INSTIGATOR.equals(d.role) ? OtcDb.TURN_ME : OtcDb.TURN_PEER;
        if (!otcDb.replaceDeal(d, agreed)) { res.err(CHANGED_ERROR); return; }
        sendMsg(agreed, OtcMessage.ACCEPT, "", new SendResult() {
            @Override public void ok() { if (OtcDb.ROLE_INSTIGATOR.equals(agreed.role)) executeDeal(agreed); res.ok(); }
            @Override public void err(String m) { res.err(m); }
        });
    }

    public void reject(OtcDb.Deal d, SendResult res) {
        if (!sameMarket(d)) { res.err(MARKET_ERROR); return; }
        if (!ready()) { res.err("still connecting"); return; }
        if (isTerminal(d.status) || OtcDb.ST_EXECUTING.equals(d.status)) { res.err(CHANGED_ERROR); return; }
        OtcDb.Deal next = d.copy(); next.status = OtcDb.ST_REJECTED;
        if (!otcDb.replaceDeal(d, next)) { res.err(CHANGED_ERROR); return; }
        sendMsg(next, OtcMessage.REJECT, "", res);
    }

