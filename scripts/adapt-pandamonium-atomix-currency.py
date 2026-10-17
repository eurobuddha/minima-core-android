"""Apply the existing OTC responder currency boundary to persisted UI and initiator paths."""

def adapt(root):
 folder=root/'atomix/src/main/java/com/eurobuddha/atomix'
 def patch(name,changes):
  p=folder/name;s=p.read_text()
  for old,new in changes:
   assert old in s,('AtomiX currency drift',name,old);s=s.replace(old,new)
  p.write_text(s)
 patch('TradingContext.java',[(
 '    public TradingContext other() {',
 '''    /** Resolve the persisted market key without guessing from the current selection. */
    public static TradingContext forKey(String key) {
        for (TradingContext context : values()) if (context.key.equals(key)) return context;
        return null;
    }

    public TradingContext other() {''')])
 patch('swap/OtcDb.java',[(
 '        public long created, updated;',
 '''        public long created, updated;
        public TradingContext market() { return TradingContext.forKey(currency); }
        public boolean inMarket(TradingContext context) { return context != null && market() == context; }
        public boolean inActiveMarket() { return inMarket(TradingContext.active()); }'''),(
 '        if (d.currency == null || d.currency.isEmpty()) d.currency = TradingContext.active().key;',
 '''        if (d.currency == null || d.currency.isEmpty()) {
            Deal existing = getDeal(d.ref);
            // Preserve unknown legacy attribution on updates; never adopt the newly selected market.
            d.currency = existing == null ? TradingContext.active().key : existing.currency;
        }''')])
 error='OTC currency changed or is unknown; switch to the deal\'s market.'
 patch('swap/OtcController.java',[(
 '    private final NodeApi node;',
 '''    private final com.eurobuddha.atomix.TradingContext market = com.eurobuddha.atomix.TradingContext.active();
    public static final String MARKET_ERROR = "'''+error+'''";
    private boolean currentMarket() { return market == com.eurobuddha.atomix.TradingContext.active(); }
    private boolean sameMarket(OtcDb.Deal deal) { return currentMarket() && deal != null && deal.inMarket(market); }
    private final NodeApi node;'''),(
 'public boolean ready() { return crypto != null', 'public boolean ready() { return currentMarket() && crypto != null'),(
 '        if (opened == null || !opened.valid) return false;', '        if (!currentMarket() || opened == null || !opened.valid) return false;'),(
 '        if (!otcDb.addMsg(toRow(m))) return false;',
 '''        if (!sameMarket(otcDb.getDeal(m.ref))) return false;
        if (!otcDb.addMsg(toRow(m))) return false;'''),(
 '        if (otcDb.getDeal(m.ref) != null) return false;                // first PROPOSE only (ref is unique)',
 '        if (!currentMarket() || otcDb.getDeal(m.ref) != null) return false; // first PROPOSE in this market'),(
 '        OtcDb.Deal d = new OtcDb.Deal();',
 '        OtcDb.Deal d = new OtcDb.Deal();\n        d.currency = market.key;'),(
 '        if (d == null || m.from == null || !m.from.equals(d.peerCommsId)) return;',
 '        if (!sameMarket(d) || m.from == null || !m.from.equals(d.peerCommsId)) return;'),(
 'com.eurobuddha.atomix.TradingContext.active().coinLabel', 'market.coinLabel'),(
 '    public void counter(OtcDb.Deal d, String amount, String price, SendResult res) {',
 '    public void counter(OtcDb.Deal d, String amount, String price, SendResult res) {\n        if (!sameMarket(d)) { res.err(MARKET_ERROR); return; }'),(
 '    public void accept(OtcDb.Deal d, SendResult res) {',
 '    public void accept(OtcDb.Deal d, SendResult res) {\n        if (!sameMarket(d)) { res.err(MARKET_ERROR); return; }'),(
 '    public void reject(OtcDb.Deal d, SendResult res) {',
 '    public void reject(OtcDb.Deal d, SendResult res) {\n        if (!sameMarket(d)) { res.err(MARKET_ERROR); return; }'),(
 '    private void sendMsg(OtcDb.Deal d, String type, String hash, SendResult res) {',
 '    private void sendMsg(OtcDb.Deal d, String type, String hash, SendResult res) {\n        if (!sameMarket(d) || !ready()) { res.err(MARKET_ERROR); return; }'),(
 '        m.send(node, crypto, new CommsTransport.SendCb() {',
 '        m.send(node, crypto, market, new CommsTransport.SendCb() {'),(
 '        if (!ready() || !OtcDb.ROLE_INSTIGATOR.equals(d.role)) return;',
 '        if (!sameMarket(d) || !ready() || !OtcDb.ROLE_INSTIGATOR.equals(d.role)) return;'),(
 '        if (cur == null || !OtcDb.ST_AGREED.equals(cur.status)) return;',
 '        if (!sameMarket(cur) || !OtcDb.ST_AGREED.equals(cur.status)) return;'),(
 '    private void resendExecute(OtcDb.Deal d, long now) {\n        if (!ready()) return;',
 '    private void resendExecute(OtcDb.Deal d, long now) {\n        if (!sameMarket(d) || !ready()) return;')])
 patch('swap/OtcMessage.java',[(
 '    public void send(NodeApi node, CryptoProvider crypto, CommsTransport.SendCb cb) {\n        try {',
 '''    public void send(NodeApi node, CryptoProvider crypto, CommsTransport.SendCb cb) {
        send(node, crypto, TradingContext.active(), cb);
    }
    /** Capture the channel before sealing; a selection change cannot reroute this deal's message. */
    public void send(NodeApi node, CryptoProvider crypto, TradingContext market, CommsTransport.SendCb cb) {
        if (market == null) { cb.onFailed(OtcController.MARKET_ERROR); return; }
        final String destination = market.otcChatAddr;
        try {'''),(
 'CommsTransport.postBlob(node, address(),', 'CommsTransport.postBlob(node, destination,')])
 patch('swap/SwapEngine.java',[(
 '    public void executeOtcDeal(OtcDb.Deal deal, StartCb cb) {\n        Order lp =',
 '''    public void executeOtcDeal(OtcDb.Deal deal, StartCb cb) {
        final com.eurobuddha.atomix.TradingContext market = deal == null ? null : deal.market();
        if (market == null || market != com.eurobuddha.atomix.TradingContext.active()
                || !market.tokenId.equalsIgnoreCase(minima.activeToken())) {
            cb.err(OtcController.MARKET_ERROR); return;
        }
        Order lp ='''),(
 '''        // FUND-SAFETY (combine): an AGREED/EXECUTING OTC deal persists across a currency switch. Never respond to
        // (lock the counter-leg for) a deal negotiated in a currency that is not the ACTIVE one — its amount/price
        // are that currency's, so locking the now-active token against them would misprice. This is the single
        // chokepoint for BOTH otcVerifyBuy (ETH-leg-first) and otcVerifySell (Minima-leg-first). (A pre-stamp
        // legacy row has null currency → treated as active, matching old single-currency behaviour.)
        if (d.currency != null && !d.currency.isEmpty()
                && !com.eurobuddha.atomix.TradingContext.active().key.equals(d.currency)) return null;''',
 '''        // Both responder paths need explicit attribution. Unknown legacy deals remain visible,
        // but cannot create a new liability using a guessed market. Existing claim/refund paths are separate.
        if (!d.inActiveMarket() || !d.market().tokenId.equalsIgnoreCase(minima.activeToken())) return null;''')])
 # Capture an initiator's labels and verify the engine still targets that market at async boundaries.
 p=folder/'swap/SwapEngine.java';s=p.read_text()
 start=s.index('    public void startMinimaToErc20(Order maker, String sellMinima, String tokenSymbol, String buyTokenAmount, boolean otc, StartCb result)')
 end=s.index('    /**\n     * Is MY first-leg lock',start);part=s[start:end]
 for cb in ['result','cb']:
  old='        if (!ready()) { '+cb+'.err("Not ready"); return; }'
  new=old+'''\n        final com.eurobuddha.atomix.TradingContext market = com.eurobuddha.atomix.TradingContext.active();
        if (!market.tokenId.equalsIgnoreCase(minima.activeToken())) { '''+cb+'''.err(OtcController.MARKET_ERROR); return; }'''
  assert old in part;part=part.replace(old,new)
 part=part.replace('com.eurobuddha.atomix.TradingContext.active().coinLabel','market.coinLabel')
 old='            @Override public void ok(String secret, String hash) {'
 assert part.count(old)==2
 part=part.replace(old,old+'\n                if (market != com.eurobuddha.atomix.TradingContext.active()) { cb.err(OtcController.MARKET_ERROR); return; }')
 old='                    @Override public void ok(int block) {';assert old in part
 part=part.replace(old,old+'\n                        if (market != com.eurobuddha.atomix.TradingContext.active()) { cb.err(OtcController.MARKET_ERROR); return; }')
 old='                    try {\n                        Credentials creds = wallet.creds();';assert old in part
 part=part.replace(old,'                    try {\n                        if (market != com.eurobuddha.atomix.TradingContext.active()) { ui.post(() -> cb.err(OtcController.MARKET_ERROR)); return; }\n                        Credentials creds = wallet.creds();')
 # Approval can take a while. Recheck before the durable row and the contract submission.
 old='                        final long timelock = ethChainNow() + TIMELOCK_SECS;';assert old in part
 part=part.replace(old,old+'\n                        if (market != com.eurobuddha.atomix.TradingContext.active()) { ui.post(() -> cb.err(OtcController.MARKET_ERROR)); return; }')
 # The OTC caller passes its saved market through dispatch; do not re-read the global selection
 # between the deal check and the initiator call. Ordinary callers capture it at their entry point.
 for name,params,args,callback in [
  ('startMinimaToErc20','Order maker, String sellMinima, String tokenSymbol, String buyTokenAmount','maker, sellMinima, tokenSymbol, buyTokenAmount','result'),
  ('startErc20ToMinima','Order maker, String tokenSymbol, String sellTokenAmount, String buyMinima','maker, tokenSymbol, sellTokenAmount, buyMinima','cb')]:
  signature='    public void '+name+'('+params+', boolean otc, StartCb '+callback+') {'
  replacement=signature+'\n        '+name+'('+args+', otc, com.eurobuddha.atomix.TradingContext.active(), '+callback+');\n    }\n\n    void '+name+'('+params+', boolean otc, com.eurobuddha.atomix.TradingContext market, StartCb '+callback+') {'
  assert signature in part;part=part.replace(signature,replacement)
  old='        final com.eurobuddha.atomix.TradingContext market = com.eurobuddha.atomix.TradingContext.active();\n        if (!market.tokenId.equalsIgnoreCase(minima.activeToken())) { '+callback+'.err(OtcController.MARKET_ERROR); return; }'
  assert old in part;part=part.replace(old,'        if (market == null || market != com.eurobuddha.atomix.TradingContext.active() || !market.tokenId.equalsIgnoreCase(minima.activeToken())) { '+callback+'.err(OtcController.MARKET_ERROR); return; }')
 part=part.replace('startErc20ToMinima(lp, "USDT", usdt, deal.amount, true, cb);','startErc20ToMinima(lp, "USDT", usdt, deal.amount, true, market, cb);')
 part=part.replace('startMinimaToErc20(lp, deal.amount, "USDT", usdt, true, cb);','startMinimaToErc20(lp, deal.amount, "USDT", usdt, true, market, cb);')
 s=s[:start]+part+s[end:];p.write_text(s)
 patch('MainActivity.java',[(
 '    private void renderOtcDeals(LinearLayout col) {',
 '''    private CharSequence otcCurrency(OtcDb.Deal deal) {
        return deal.market() == null ? Language.msg(l10n_atomix_otc_unknown_market) : deal.market().coinLabel;
    }

    private void renderOtcDeals(LinearLayout col) {'''),(
 'Language.msg(l10n_atomix_otc_buy_terms, d.amount, ccy(), d.price) : Language.msg(l10n_atomix_otc_sell_terms, d.amount, ccy(), d.price)',
 'Language.msg(l10n_atomix_otc_buy_terms, d.amount, otcCurrency(d), d.price) : Language.msg(l10n_atomix_otc_sell_terms, d.amount, otcCurrency(d), d.price)'),(
 '            if (otc != null && OtcDb.TURN_ME.equals(d.whoseTurn)',
 '''            if (!d.inActiveMarket()) {
                TextView marketHint = fieldLabel(d.market() == null ? Language.msg(l10n_atomix_otc_unknown_market_help)
                        : Language.msg(l10n_atomix_otc_switch_market, d.market().coinLabel));
                box.addView(marketHint);
            }
            if (d.inActiveMarket() && otc != null && OtcDb.TURN_ME.equals(d.whoseTurn)'''),(
 '    private void otcCounterDialog(final OtcDb.Deal d) {',
 '    private void otcCounterDialog(final OtcDb.Deal d) {\n        if (!d.inActiveMarket()) { toast(Language.msg(l10n_atomix_otc_market_unavailable)); return; }')])
