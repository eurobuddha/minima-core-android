"""Reuse ETH Wallet's captured-owner send context and reviewed gas price in AtomiX."""
from pathlib import Path


def adapt_app(base, integrated=True):
    p=base/'eth/EthTx.java';s=p.read_text()
    def change(old,new,count=1):
        nonlocal s
        assert s.count(old)==count,(p,old,s.count(old))
        s=s.replace(old,new)
    if 'public static void requireAuthorized' not in s:
        change('    /** Forget all cached nonce state', '''    /** Reused ETH Wallet fail-closed signing guard. */
    public static void requireAuthorized(java.util.function.BooleanSupplier authorized) {
        boolean allowed;
        try { allowed = authorized != null && authorized.getAsBoolean(); }
        catch (RuntimeException failure) { allowed = false; }
        if (!allowed) throw new IllegalStateException("Wallet session changed. Review again.");
    }

    /** Forget all cached nonce state''')
        change('''                              String to, String data, BigInteger value, BigInteger gasLimit) throws Exception {
        NonceState st''','''                              String to, String data, BigInteger value, BigInteger gasLimit) throws Exception {
        return send(rpc, creds, chainId, to, data, value, gasLimit, () -> true);
    }

    public static String send(EthRpc rpc, Credentials creds, long chainId,
                              String to, String data, BigInteger value, BigInteger gasLimit,
                              java.util.function.BooleanSupplier authorized) throws Exception {
        requireAuthorized(authorized);
        NonceState st''')
        change('        synchronized (st) {\n            BigInteger pending', '        synchronized (st) {\n            requireAuthorized(authorized);\n            BigInteger pending')
        change('            EthRpc.requireSendableNonce(alloc);', '            requireAuthorized(authorized);\n            EthRpc.requireSendableNonce(alloc);')
        change('            String txHash = rpc.sendRawTransaction(', '            requireAuthorized(authorized);\n            String txHash = rpc.sendRawTransaction(')
    a=s.index('        NonceState st = NONCE.computeIfAbsent');b=s.index('        BigInteger rawGp',a)
    s=s[:a]+'''        // Automated operations retain the existing fee formula; manual sends pass their reviewed price.
'''+s[b:]
    change('''        final BigInteger gasPrice = EthSend.effectiveGasPriceWei(rawGp, rpc.baseFeePerGasOrZero());
        synchronized (st) {''','''        final BigInteger gasPrice = EthSend.effectiveGasPriceWei(rawGp, rpc.baseFeePerGasOrZero());
        return send(rpc, creds, chainId, to, data, value, gasLimit, gasPrice, authorized);
    }

    /** Same explicit-price boundary used by ETH Wallet. Never recompute a reviewed fee. */
    public static String send(EthRpc rpc, Credentials creds, long chainId,
                              String to, String data, BigInteger value, BigInteger gasLimit, BigInteger gasPrice,
                              java.util.function.BooleanSupplier authorized) throws Exception {
        requireAuthorized(authorized);
        requireFees(gasPrice, gasLimit);
        NonceState st = NONCE.computeIfAbsent(creds.getAddress(), k -> new NonceState());
        synchronized (st) {''')
    marker='    /** Forget all cached nonce state'
    change(marker,'''    // Reuse ETH Wallet's hard fee caps (1500 gwei / 1M gas), checked before signing.
    public static final BigInteger MAX_GAS_PRICE = BigInteger.valueOf(1_500_000_000_000L);
    public static final BigInteger MAX_GAS_LIMIT = BigInteger.valueOf(1_000_000);
    public static void requireFees(BigInteger gasPrice, BigInteger gasLimit) {
        if (gasPrice == null || gasPrice.signum() <= 0 || gasPrice.compareTo(MAX_GAS_PRICE) > 0
                || gasLimit == null || gasLimit.signum() <= 0 || gasLimit.compareTo(MAX_GAS_LIMIT) > 0)
            throw new IllegalArgumentException("Ethereum fee is outside the safe range. Review your RPC endpoint.");
    }

'''+marker)
    p.write_text(s)

    p=base/'MainActivity.java';s=p.read_text()
    change('import com.eurobuddha.atomix.eth.EthSend;', 'import com.eurobuddha.atomix.eth.EthSend;\nimport com.eurobuddha.atomix.eth.EthTx;')
    change('    private EthNet net =', '    private volatile EthNet net =')
    change('    private EthRpc rpc;', '    private volatile EthRpc rpc;\n    private volatile long manualSendGeneration;')
    change('    private void sendDialog() {', '''    /** Reuses ETH Wallet's immutable owner/connection capture through the signing boundary. */
    private final class ManualSendContext {
        final org.web3j.crypto.Credentials owner = wallet.creds();
        final EthRpc connection = rpc;
        final EthNet network = net;
        final EthNet.Token token = network.tokens[0];
        final long generation = ++manualSendGeneration;
        volatile boolean active = true;
        boolean current() {
            return active && owner != null && owner == wallet.creds() && connection == rpc && network == net
                    && token == network.tokens[0] && generation == manualSendGeneration
                    && !isFinishing() && !isDestroyed() && !io.isShutdown();
        }
        void cancel() { active = false; }
    }

    private void sendDialog() {''')
    a=s.index('    private void sendDialog()');b=s.index('    private void exportKeyDialog()',a)
    before=s[:a];part=s[a:b];after=s[b:]
    part=part.replace('android.app.AlertDialog.BUTTON_POSITIVE', 'AlertDialog.BUTTON_POSITIVE')
    part=part.replace('        LinearLayout box = new LinearLayout(this);','''        final ManualSendContext context = new ManualSendContext();
        final java.util.concurrent.atomic.AtomicBoolean reviewing = new java.util.concurrent.atomic.AtomicBoolean();
        final long[] maxRequest = {0};
        LinearLayout box = new LinearLayout(this);''',1)
    x=part.index('        maxB.setOnClickListener');y=part.index('        amtRow.addView',x)
    write='Language.setText(amtE, amount)' if integrated else 'amtE.setText(amount)'
    stale='Language.msg(l10n_wallet_review_again)' if integrated else '"Wallet session changed. Review again."'
    gaserror='Language.msg(l10n_atomix_ui_gas_error)' if integrated else '"Could not read gas price — try again"'
    part=part[:x]+'''        maxB.setOnClickListener(v -> {
            final boolean eth = ethSel[0];
            final String typed = amtE.getText().toString();
            final long request = ++maxRequest[0];
            if (!context.current()) { toast(STALE); return; }
            io.execute(() -> {
                try {
                    EthTx.requireAuthorized(context::current);
                    final String amount;
                    if (eth) {
                        BigInteger gp = EthRpc.hexToBig(context.connection.callStr("eth_gasPrice", new org.json.JSONArray()));
                        BigInteger bf = context.connection.baseFeePerGasOrZero();
                        EthTx.requireFees(EthSend.effectiveGasPriceWei(gp, bf), EthSend.GAS_ETH);
                        BigInteger balance = context.connection.getBalance(context.owner.getAddress());
                        amount = EthWallet.format(EthSend.maxEthSendWei(balance, gp, bf), 18, 8);
                    } else {
                        BigInteger balance = EthWallet.erc20BalanceRaw(context.connection, context.token.address, context.owner.getAddress());
                        amount = EthWallet.format(balance, context.token.decimals, context.token.decimals);
                    }
                    ui.post(() -> {
                        if (context.current() && !reviewing.get() && request == maxRequest[0]
                                && eth == ethSel[0] && typed.equals(amtE.getText().toString())) WRITE;
                    });
                } catch (Exception e) { ui.post(() -> { if (context.current()) toast(GASERROR); }); }
            });
        });
'''.replace('STALE',stale).replace('GASERROR',gaserror).replace('WRITE',write)+part[y:]
    part=part.replace('''        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final boolean eth''','''        d.setOnDismissListener(ignored -> { if (!reviewing.get()) context.cancel(); });
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!context.current()) { toast(STALE); return; }
            d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            final boolean eth'''.replace('STALE',stale))
    # Freeze reads and values during preflight. A stale result never replaces the reviewed owner.
    part=part.replace('''                    BigInteger gp = EthRpc.hexToBig(rpc.callStr("eth_gasPrice", new org.json.JSONArray()));
                    BigInteger bf = rpc.baseFeePerGasOrZero();''','''                    EthTx.requireAuthorized(context::current);
                    BigInteger gp = EthRpc.hexToBig(context.connection.callStr("eth_gasPrice", new org.json.JSONArray()));
                    BigInteger bf = context.connection.baseFeePerGasOrZero();''')
    part=part.replace('''                    final String err = EthSend.checkSend(eth, to, amt, ethWeiRaw, usdtRawBal, net.tokens[0].decimals, gp, bf);
                    if (err != null) { ui.post(() -> toast(err)); return; }''','''                    final BigInteger gasPrice = EthSend.effectiveGasPriceWei(gp, bf);
                    EthTx.requireFees(gasPrice, eth ? EthSend.GAS_ETH : EthSend.GAS_ERC20);
                    BigInteger ethBalance = context.connection.getBalance(context.owner.getAddress());
                    BigInteger tokenBalance = eth ? BigInteger.ZERO : EthWallet.erc20BalanceRaw(
                            context.connection, context.token.address, context.owner.getAddress());
                    final String err = EthSend.checkSend(eth, to, amt, ethBalance, tokenBalance, context.token.decimals, gp, bf);
                    if (err != null) throw new IllegalArgumentException(err);''')
    part=part.replace('''                    ui.post(() -> { d.dismiss(); sendReviewDialog(eth, to, amt, fee); });
                } catch (Exception e) { ui.post(() -> toast(GASERROR)); }'''.replace('GASERROR',gaserror),'''                    final String amount = new BigDecimal(EthSend.parseUnits(amt, eth ? 18 : context.token.decimals),
                            eth ? 18 : context.token.decimals).stripTrailingZeros().toPlainString();
                    ui.post(() -> {
                        if (!d.isShowing()) return;
                        d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        if (!context.current()) { toast(STALE); return; }
                        reviewing.set(true); d.dismiss(); sendReviewDialog(context, eth, to, amount, fee, gasPrice);
                    });
                } catch (Exception e) { ui.post(() -> {
                    if (d.isShowing()) {
                        d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        toast(context.current() ? e.getMessage() : STALE);
                    }
                }); }'''.replace('STALE',stale))
    part=part.replace('''    private void sendReviewDialog(boolean eth, String to, String amt, String feeEth) {
        dialog()''','''    private void sendReviewDialog(ManualSendContext context, boolean eth, String to, String amt, String feeEth, BigInteger gasPrice) {
        if (!context.current()) { toast(STALE); return; }
        final java.util.concurrent.atomic.AtomicBoolean submitted = new java.util.concurrent.atomic.AtomicBoolean();
        dialog()'''.replace('STALE',stale))
    part=part.replace('''(dd, w) -> io.execute(() -> {
                    try {
                        String tx = eth
                                ? EthSend.sendEth(rpc, wallet.creds(), net.chainId, to, EthSend.parseUnits(amt, 18))
                                : EthSend.sendErc20(rpc, wallet.creds(), net.chainId, net.tokens[0].address, to,
                                        EthSend.parseUnits(amt, net.tokens[0].decimals));''','''(dd, w) -> {
                    if (!context.current() || !submitted.compareAndSet(false, true)) { toast(STALE); return; }
                    io.execute(() -> {
                    try {
                        BigInteger amount = EthSend.parseUnits(amt, eth ? 18 : context.token.decimals);
                        String tx = EthTx.send(context.connection, context.owner, context.network.chainId,
                                eth ? to : context.token.address,
                                eth ? "" : EthSend.buildTransferData(to, amount), eth ? amount : BigInteger.ZERO,
                                eth ? EthSend.GAS_ETH : EthSend.GAS_ERC20, gasPrice, context::current);'''.replace('STALE',stale))
    part=part.replace('''                }))
                .setNegativeButton''','''                    } finally { context.cancel(); }
                }); })
                .setOnDismissListener(ignored -> { if (!submitted.get()) context.cancel(); })
                .setNegativeButton''')
    # Move finally onto the existing catch (avoid an extra brace from the original lambda).
    part=part.replace('''                    }
                    } finally { context.cancel(); }''','''                    } finally { context.cancel(); }''')
    assert 'sendReviewDialog(eth,' not in part and 'EthSend.sendEth(rpc,' not in part and 'rpc.callStr' not in part
    # Show the exact sender alongside the existing review body, without altering address text.
    if integrated:
        needle='.setMessage(Language.msg(l10n_atomix_ui_review_body, amt, eth ? "ETH" : "USDT", to, feeEth))'
        assert needle in part
        part=part.replace(needle,'.setMessage(Language.join(Language.msg(l10n_eth_wallet_your_address), "\\n", context.owner.getAddress(), "\\n\\n", Language.msg(l10n_atomix_ui_review_body, amt, eth ? "ETH" : "USDT", to, feeEth)))')
    else:
        part=part.replace('.setMessage("Send  " + amt', '.setMessage("From  " + context.owner.getAddress() + "\\n\\nSend  " + amt')
    part=finish_dialogs(part, integrated)
    s=before+part+after;p.write_text(s)


def adapt(root):
    adapt_app(root/'atomix/src/main/java/com/eurobuddha/atomix')
    p=root/'atomix/build.gradle';s=p.read_text();needle='    buildFeatures { buildConfig true }';assert s.count(needle)==1
    p.write_text(s.replace(needle,needle+"\n    testOptions { unitTests.includeAndroidResources = true; unitTests.all { maxHeapSize = '2g' } }"))


def finish_dialogs(part, integrated):
    stale='Language.msg(l10n_wallet_review_again)' if integrated else '"Wallet session changed. Review again."'
    part=part.replace('ethSel[0] = true; restyle.run();','ethSel[0] = true; maxRequest[0]++; restyle.run();').replace('ethSel[0] = false; restyle.run();','ethSel[0] = false; maxRequest[0]++; restyle.run();')
    part=part.replace('                .show();\n        d.setOnDismissListener(ignored -> { if (!reviewing.get()) context.cancel(); });','                .setOnDismissListener(ignored -> { if (!reviewing.get()) context.cancel(); })\n                .show();')
    part=part.replace('if (!context.current()) { toast('+stale+'); return; }\n                        reviewing.set(true);', 'if (!context.current() || eth != ethSel[0] || !to.equals(toE.getText().toString().trim())\n                                || !amt.equals(amtE.getText().toString().trim())) { toast('+stale+'); return; }\n                        reviewing.set(true);')
    if integrated:
        part=part.replace('toast(context.current() ? e.getMessage() :', 'toast(context.current() ? AtomixMessages.error(e) :')
        part=part.replace('ui.post(() -> toast(Language.msg(l10n_atomix_ui_send_failed, e.getMessage())));', 'final CharSequence failure = context.current() ? AtomixMessages.error(e) : '+stale+';\n                        ui.post(() -> toast(Language.msg(l10n_atomix_ui_send_failed, failure)));')
    part=part.replace('io.execute(() -> {','runManual(context, () -> {')
    # Reuse SwapEngine.submitIo's rejected-executor handling at this UI dispatch boundary.
    helper='    private void runManual(ManualSendContext context, Runnable task) {\n        if (!context.current()) { toast('+stale+'); return; }\n        try { io.execute(task); }\n        catch (java.util.concurrent.RejectedExecutionException stopped) { context.cancel(); toast('+stale+'); }\n    }\n\n'
    return helper+part
