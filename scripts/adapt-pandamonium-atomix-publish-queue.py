"""Reuse PandaDEX's guarded DirectNodeApi dispatch for queued AtomiX publications."""
def adapt(root):
 base=root/'atomix/src/main/java/com/eurobuddha'
 def patch(name,changes):
  p=base/name;s=p.read_text()
  for old,new,count in changes:
   assert s.count(old)==count,('AtomiX publish queue drift',name,old,s.count(old));s=s.replace(old,new)
  p.write_text(s)
 patch('comms/NodeApi.java',[(
 '    public void cmd(String command, Cb cb) {\n        if (Looper.myLooper() != Looper.getMainLooper()) { mMain.post(() -> cmd(command, cb)); return; }',
 '''    public static final String ERR_COMMAND_CHANGED = "Market changed; refresh and try again.";
    // Same fail-closed supplier boundary as PandaDEX CommandSession.authorized.
    static boolean authorized(java.util.function.BooleanSupplier guard) {
        try { return guard != null && guard.getAsBoolean(); }
        catch (RuntimeException failure) { return false; }
    }
    public void cmd(String command, Cb cb) { cmd(command, () -> true, cb); }
    /** The shared worker checks this thread-safe guard again immediately before execution. */
    public void cmd(String command, java.util.function.BooleanSupplier authorized, Cb cb) {
        if (Looper.myLooper() != Looper.getMainLooper()) { mMain.post(() -> cmd(command, authorized, cb)); return; }
        if (!authorized(authorized)) { if (cb != null) cb.onError(ERR_COMMAND_CHANGED); return; }''',1),(
 'try { mApi.Command(command, new MinimaAPIListener() {',
 'try { mApi.Command(command, authorized, new MinimaAPIListener() {',1)])
 patch('comms/CommsTransport.java',[(
 '''                                String blobHex, JSONObject extraState, SendCb cb) {
        try {''',
 '''                                String blobHex, JSONObject extraState, SendCb cb) {
        postBlob(node, address, amount, tokenid, blobHex, extraState, () -> true, cb);
    }
    /** Publication guard is retained through the signing queue and the shared node worker. */
    public static void postBlob(NodeApi node, String address, String amount, String tokenid,
                                String blobHex, JSONObject extraState, java.util.function.BooleanSupplier authorized, SendCb cb) {
        try {''',1),(
 'post(node, "send amount:" + amount + " address:" + address + " tokenid:" + tokenid + " state:" + state, cb);',
 'post(node, "send amount:" + amount + " address:" + address + " tokenid:" + tokenid + " state:" + state, authorized, cb);',1),(
 '''    private static void post(NodeApi node, String cmd, SendCb cb) {
        SignGate.submit(gate -> node.cmd(cmd, new NodeApi.Cb() {''',
 '''    private static void post(NodeApi node, String cmd, java.util.function.BooleanSupplier authorized, SendCb cb) {
        SignGate.submit(gate -> {
            if (!NodeApi.authorized(authorized)) {
                gate.free(); cb.onFailed(NodeApi.ERR_COMMAND_CHANGED); return;
            }
            node.cmd(cmd, authorized, new NodeApi.Cb() {''',1),(
 '''                // status:true = accepted; pending:true = queued via the pending app (node locked)
                if (j.optBoolean("status", false) || j.optBoolean("pending", false)) {''',
 '''                // NodeApi already holds pending/incomplete writes for review; never report them sent.
                if (j == null || !WriteSafety.completeReply(j)) { cb.onFailed(NodeApi.ERR_WRITE_UNCERTAIN); return; }
                if (Boolean.TRUE.equals(j.opt("status"))) {''',1),(
 '        }));','            });\n        });',1)])
 patch('atomix/swap/SwapOrderBook.java',[(
 '                    Hex.to(msg), extra, new CommsTransport.SendCb() {',
 '                    Hex.to(msg), extra, () -> current(order, generation), new CommsTransport.SendCb() {',1)])
 patch('atomix/swap/OtcBook.java',[(
 '''    public static void publish(NodeApi node, LazySodium ls, CommsIdentity id, OtcOffer offer, CommsTransport.SendCb cb) {
        try {''',
 '''    public static void publish(NodeApi node, LazySodium ls, CommsIdentity id, OtcOffer offer, CommsTransport.SendCb cb) {
        final TradingContext market = TradingContext.active();
        final long generation = PublishGate.gen();
        final java.util.function.BooleanSupplier current = () -> market == TradingContext.active() && generation == PublishGate.gen();
        try {''',1),(
 '''            CommsTransport.postBlob(node, address(), CommsTransport.MESSAGE_AMOUNT, CommsTransport.NATIVE,
                    Hex.to(msg), extra, cb);''',
 '''            CommsTransport.postBlob(node, market.otcBoardAddr, CommsTransport.MESSAGE_AMOUNT, CommsTransport.NATIVE,
                    Hex.to(msg), extra, current, new CommsTransport.SendCb() {
                        @Override public void onSent(String txpowid) {
                            if (!current.getAsBoolean()) { cb.onFailed(NodeApi.ERR_COMMAND_CHANGED); return; }
                            cb.onSent(txpowid);
                        }
                        @Override public void onFailed(String message) { cb.onFailed(message); }
                    });''',1)])

 patch('atomix/MainActivity.java',[(
 '            prefs.edit().putString("otc_sell_size", sell > 0 ? trim(sell) : "").putString("otc_buy_size", buy > 0 ? trim(buy) : "")',
 '            PublishGate.bumpGen(); // new OTC terms invalidate queued publications\n            prefs.edit().putString("otc_sell_size", sell > 0 ? trim(sell) : "").putString("otc_buy_size", buy > 0 ? trim(buy) : "")',1),(
 '    private void withdrawOtcOffer() {',
 '    private void withdrawOtcOffer() {\n        PublishGate.bumpGen(); // a queued live offer must not overtake this withdrawal',1),(
 'Language.msg(l10n_atomix_otc_publish_failed, message)',
 'Language.msg(l10n_atomix_otc_publish_failed, OperationMessages.message(message))',1)])
