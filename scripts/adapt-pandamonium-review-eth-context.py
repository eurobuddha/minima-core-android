"""Reuse AtomiX's captured-owner guard at ETH Wallet's signing boundaries."""
from pathlib import Path


def adapt(root):
    folder=root/'ethwallet/src/main/java/com/eurobuddha/ethwallet'
    p=folder/'MainActivity.java';s=p.read_text()
    def change(old,new,count=1):
        nonlocal s
        assert s.count(old)==count,('ETH context source drift',p.name,old,s.count(old))
        s=s.replace(old,new)
    change('    private EthRpc rpc;', '    private volatile EthRpc rpc;\n    private volatile long sendGeneration;')
    change('    private void sendDialog(EthNet.Token startToken) {', '''    /** Same owner/connection capture used by AtomiX's ethCounterLegGuard. */
    private final class SendContext {
        final org.web3j.crypto.Credentials owner = wallet.creds();
        final EthRpc connection = rpc;
        final EthNet network = net;
        final long generation = sendGeneration;
        boolean current() {
            return owner != null && owner == wallet.creds() && connection == rpc && network == net
                    && generation == sendGeneration && !gone() && !io.isShutdown();
        }
    }

    private void sendDialog(EthNet.Token startToken) {''')
    change('''        if (!wallet.ready()) return;
        modalOpen = true;
        final List<EthNet.Token> assets''','''        if (!wallet.ready()) return;
        sendGeneration++;
        final SendContext context = new SendContext();
        modalOpen = true;
        final List<EthNet.Token> assets''')
    change('''                    confirmSend(chosen[0], to.getText().toString().trim(), amt.getText().toString().trim());''','''                    if (!context.current()) { toast(Language.msg(l10n_wallet_review_again)); return; }
                    confirmSend(chosen[0], to.getText().toString().trim(), amt.getText().toString().trim());''')
    change('''            if (chosen[0] != null) {''','''            if (!context.current()) { toast(Language.msg(l10n_wallet_review_again)); return; }
            if (chosen[0] != null) {''')
    start=s.index('    private void sendDialog(');end=s.index('    private void confirmSend(',start)
    part=s[start:end].replace('wallet.ethBalanceWei(rpc)','context.connection.getBalance(context.owner.getAddress())').replace('rpc.gasPrice()','context.connection.gasPrice()').replace('if (chosen[0] == null && typedAmount.equals','if (context.current() && chosen[0] == null && typedAmount.equals')
    s=s[:start]+part+s[end:]
    change('''    private void confirmSend(EthNet.Token tk, String to, String amount) {
        final boolean''','''    private void confirmSend(EthNet.Token tk, String to, String amount) {
        sendGeneration++;
        final SendContext context = new SendContext();
        if (!context.current()) { toast(Language.msg(l10n_wallet_review_again)); return; }
        final boolean''')
    start=s.index('    private void confirmSend(');end=s.index('    // Fee tiers',start)
    part=s[start:end].replace('to.equalsIgnoreCase(wallet.address())','to.equalsIgnoreCase(context.owner.getAddress())')
    part=part.replace('''            try {
                final int decimals''','''            try {
                EthTx.requireAuthorized(context::current);
                final int decimals''')
    part=part.replace('rpc.estimateGas(wallet.address(),','context.connection.estimateGas(context.owner.getAddress(),').replace('rpc.gasPrice()','context.connection.gasPrice()')
    part=part.replace('showConfirm(sym, to, normalised, isEth, txTo, data, value, gasLimitF, baseGp)','showConfirm(sym, to, normalised, isEth, txTo, data, value, gasLimitF, baseGp, context)')
    s=s[:start]+part+s[end:]
    change('''BigInteger gasLimit, BigInteger baseGp) {
        modalOpen''','''BigInteger gasLimit, BigInteger baseGp, SendContext context) {
        if (!context.current()) { toast(Language.msg(l10n_wallet_review_again)); return; }
        modalOpen''')
    change('''        box.addView(kvLine(Language.msg(l10n_eth_wallet_send), amount + " " + sym));''','''        box.addView(kvLine(Language.msg(l10n_eth_wallet_your_address), ""));
        box.addView(fullAddrBlock(context.owner.getAddress()));
        box.addView(kvLine(Language.msg(l10n_eth_wallet_send), amount + " " + sym));''')
    change('''                    final BigInteger gasPrice = baseGp.multiply''','''                    if (!context.current()) { toast(Language.msg(l10n_wallet_review_again)); return; }
                    final BigInteger gasPrice = baseGp.multiply''')
    change('EthTx.send(rpc, wallet.creds(), net.chainId, txTo, data, isEth ? value : BigInteger.ZERO, gasLimit, gasPrice)',
        'EthTx.send(context.connection, context.owner, context.network.chainId, txTo, data, isEth ? value : BigInteger.ZERO, gasLimit, gasPrice, context::current)')
    change('rpc.setUrl(u);','rpc = new EthRpc(u);')
    change('rpc.setUrl(net.defaultRpc);','rpc = new EthRpc(net.defaultRpc);')
    # Freeze refresh reads too: a result from the old account must not paint the new account.
    change('''        if (!wallet.ready()) return;
        io.execute(() -> {
            try {
                BigInteger wei = wallet.ethBalanceWei(rpc);''','''        if (!wallet.ready()) return;
        final SendContext context = new SendContext();
        final List<EthNet.Token> requestedTokens = tokens.tokens();
        io.execute(() -> {
            try {
                if (!context.current()) return;
                BigInteger wei = context.connection.getBalance(context.owner.getAddress());''')
    start=s.index('    private void refresh()');end=s.index('    // ---- render',start)
    part=s[start:end].replace('for (EthNet.Token tk : tokens.tokens())','for (EthNet.Token tk : requestedTokens)').replace('wallet.erc20BalanceRaw(rpc, tk.address)','EthWallet.erc20BalanceRaw(context.connection, tk.address, context.owner.getAddress())')
    part=part.replace('onUi(() -> { ethBal', 'onUi(() -> { if (!context.current()) return; ethBal').replace('onUi(() -> { ethErr','onUi(() -> { if (!context.current()) return; ethErr')
    s=s[:start]+part+s[end:];p.write_text(s)

    p=folder/'eth/EthTx.java';s=p.read_text()
    change('''        if (gasPrice == null || gasPrice.signum() <= 0) gasPrice = FALLBACK_GAS_PRICE;''','''        return send(rpc, creds, chainId, to, data, value, gasLimit, gasPrice, () -> true);
    }

    /** Same fail-closed check as AtomiX EthTx; no signature is broadcast after refusal. */
    public static void requireAuthorized(java.util.function.BooleanSupplier authorized) {
        boolean allowed;
        try { allowed = authorized != null && authorized.getAsBoolean(); }
        catch (RuntimeException failure) { allowed = false; }
        if (!allowed) throw new IllegalStateException("Wallet session changed. Review again.");
    }

    public static String send(EthRpc rpc, Credentials creds, long chainId,
                              String to, String data, BigInteger value, BigInteger gasLimit, BigInteger gasPrice,
                              java.util.function.BooleanSupplier authorized) throws Exception {
        requireAuthorized(authorized);
        if (gasPrice == null || gasPrice.signum() <= 0) gasPrice = FALLBACK_GAS_PRICE;''')
    change('''        RawTransaction raw = RawTransaction.createTransaction(''','''        requireAuthorized(authorized);
        RawTransaction raw = RawTransaction.createTransaction(''')
    change('''        String txHash = rpc.sendRawTransaction(''','''        requireAuthorized(authorized);
        String txHash = rpc.sendRawTransaction(''')
    p.write_text(s)
