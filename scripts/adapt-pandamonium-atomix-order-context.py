"""Reuse OTC market provenance and PublishGate generations for order-board operations."""
def adapt(root):
 folder=root/'atomix/src/main/java/com/eurobuddha/atomix'
 def patch(name,changes):
  p=folder/name;s=p.read_text()
  for old,new,count in changes:
   assert s.count(old)==count,('AtomiX order context drift',name,old,s.count(old));s=s.replace(old,new)
  p.write_text(s)
 patch('swap/Order.java',[(
 '    // set on receive (not part of the signed payload)',
 '''    // Local creation/scan market. Peer JSON cannot choose this attribution.
    com.eurobuddha.atomix.TradingContext market = com.eurobuddha.atomix.TradingContext.active();
    public com.eurobuddha.atomix.TradingContext market() { return market; }

    // set on receive (not part of the signed payload)''',1),(
 '        Order r = new Order();','        Order r = new Order();\n        r.market = null; // only a verified board scan can attribute a received order',1)])
 patch('swap/SwapOrderBook.java',[(
 '    private SwapOrderBook() {}',
 '''    public static final String MARKET_ERROR = "Market changed; refresh and try again.";
    private static boolean current(Order order, long generation) {
        return order != null && order.market != null && order.market == TradingContext.active()
                && generation == PublishGate.gen();
    }
    private SwapOrderBook() {}''',1),(
 '''    public static void publishFresh(NodeApi node, LazySodium ls, CommsIdentity id, Order base, CommsTransport.SendCb cb) {
        node.cmd("balance tokenid:" + TradingContext.active().tokenId, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {''',
 '''    public static void publishFresh(NodeApi node, LazySodium ls, CommsIdentity id, Order base, CommsTransport.SendCb cb) {
        publishFresh(node, ls, id, base, PublishGate.gen(), cb);
    }
    /** Keep the caller's generation through ladder preparation and the balance read. */
    public static void publishFresh(NodeApi node, LazySodium ls, CommsIdentity id, Order base, long generation, CommsTransport.SendCb cb) {
        if (!current(base, generation)) { cb.onFailed(MARKET_ERROR); return; }
        node.cmd("balance tokenid:" + base.market.tokenId, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!current(base, generation)) { cb.onFailed(MARKET_ERROR); return; }''',1),(
 'publish(node, ls, id, base, cb);','publish(node, ls, id, base, generation, cb);',2),(
 '''    public static void publish(NodeApi node, LazySodium ls, CommsIdentity id, Order order, CommsTransport.SendCb cb) {
        try {''',
 '''    public static void publish(NodeApi node, LazySodium ls, CommsIdentity id, Order order, CommsTransport.SendCb cb) {
        publish(node, ls, id, order, PublishGate.gen(), cb);
    }
    private static void publish(NodeApi node, LazySodium ls, CommsIdentity id, Order order, long generation, CommsTransport.SendCb cb) {
        if (!current(order, generation)) { cb.onFailed(MARKET_ERROR); return; }
        final String destination = order.market.orderBookAddr;
        try {''',1),(
 '''            CommsTransport.postBlob(node, address(), CommsTransport.MESSAGE_AMOUNT, CommsTransport.NATIVE,
                    Hex.to(msg), extra, cb);''',
 '''            if (!current(order, generation)) { cb.onFailed(MARKET_ERROR); return; }
            CommsTransport.postBlob(node, destination, CommsTransport.MESSAGE_AMOUNT, CommsTransport.NATIVE,
                    Hex.to(msg), extra, new CommsTransport.SendCb() {
                        @Override public void onSent(String txpowid) {
                            if (!current(order, generation)) { cb.onFailed(MARKET_ERROR); return; }
                            cb.onSent(txpowid);
                        }
                        @Override public void onFailed(String message) { cb.onFailed(message); }
                    });''',1),(
 '        node.cmd("coinnotify action:add address:" + address(), new NodeApi.Cb() {',
 '        final TradingContext market = TradingContext.active();\n        node.cmd("coinnotify action:add address:" + market.orderBookAddr, new NodeApi.Cb() {',1),(
 'doScan(node, ls, ok, err);','doScan(node, ls, market, ok, err);',2),(
 '''    private static void doScan(NodeApi node, LazySodium ls, Consumer<Map<String, Order>> ok, Consumer<String> err) {
        node.cmd("coins simplestate:true order:desc depth:" + SCAN_DEPTH + " address:" + address(), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {''',
 '''    private static void doScan(NodeApi node, LazySodium ls, TradingContext market, Consumer<Map<String, Order>> ok, Consumer<String> err) {
        if (market != TradingContext.active()) { err.accept(MARKET_ERROR); return; }
        node.cmd("coins simplestate:true order:desc depth:" + SCAN_DEPTH + " address:" + market.orderBookAddr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (market != TradingContext.active()) { err.accept(MARKET_ERROR); return; }''',1),(
 '                    if (o == null) continue;','                    if (o == null) continue;\n                    o.market = market;',1),(
 '                ok.accept(book);','                if (market != TradingContext.active()) { err.accept(MARKET_ERROR); return; }\n                ok.accept(book);',1)])
 for name in ['MainActivity.java','SwapService.java']:
  count=2 if name=='MainActivity.java' else 1
  patch(name,[(
   'SwapOrderBook.publishFresh(node, ls, identity, o, new CommsTransport.SendCb() {',
   'SwapOrderBook.publishFresh(node, ls, identity, o, gen, new CommsTransport.SendCb() {',count)])
 patch('MainActivity.java',[(
 'SwapOrderBook.publishFresh(node, ls, identity, o, cb)', 'SwapOrderBook.publishFresh(node, ls, identity, o, gen, cb)',1),(
 '            TradingContext.setActive(target, prefs);', '            PublishGate.bumpGen(); // invalidate publications started in the leaving market\n            TradingContext.setActive(target, prefs);',1),(
 '            io.execute(() -> { PriceOracle.refreshSync(); ui.post(this::publishOrderNow); });',
 '''            final TradingContext market = TradingContext.active();
            final long generation = PublishGate.gen();
            io.execute(() -> { PriceOracle.refreshSync(); ui.post(() -> {
                if (market != TradingContext.active() || generation != PublishGate.gen()) { orderStatus = SwapOrderBook.MARKET_ERROR; render(); return; }
                publishOrderNow();
            }); });''',1),(
 'SwapTake.send(node, crypto, maker.commsPublicId, identity.publicId(), hash, new CommsTransport.SendCb() {',
 'SwapTake.send(node, crypto, maker.commsPublicId, identity.publicId(), hash, maker.market(), new CommsTransport.SendCb() {',1)])
 patch('swap/SwapTake.java',[(
 '''                            String hash, CommsTransport.SendCb cb) {
        try {''',
 '''                            String hash, CommsTransport.SendCb cb) {
        send(node, crypto, makerPublicId, myPublicId, hash, TradingContext.active(), cb);
    }
    /** A completed lock must notify its original market even if selection has since changed. */
    public static void send(NodeApi node, CryptoProvider crypto, String makerPublicId, String myPublicId,
                            String hash, TradingContext market, CommsTransport.SendCb cb) {
        if (market == null) { cb.onFailed(SwapOrderBook.MARKET_ERROR); return; }
        final String destination = market.takeAddr;
        try {''',1),(
 'CommsTransport.postBlob(node, address(),','CommsTransport.postBlob(node, destination,',1)])
 for callback in ['result','cb']:
  old='        if (market == null || market != com.eurobuddha.atomix.TradingContext.active() || !market.tokenId.equalsIgnoreCase(minima.activeToken())) { '+callback+'.err(OtcController.MARKET_ERROR); return; }'
  patch('swap/SwapEngine.java',[(old,old+'\n        if (!otc && (maker == null || maker.market != market)) { '+callback+'.err(SwapOrderBook.MARKET_ERROR); return; }',1)])
