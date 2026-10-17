"""Move approved wallet signing onto the existing session worker."""
from pathlib import Path

def adapt(root):
    base=root/'wallet/src/main/java/com/eurobuddha/wallet'
    p=base/'MainActivity.java';s=p.read_text()
    marker='    private void showWalletWork() {'
    assert s.count(marker)==1
    helper='''    /** Called only after review approval. Reservation and snapshot ordering remain unchanged. */
    void runSigningWork(java.util.concurrent.Callable<TxnFactory.BuiltTxn> builder,
                        java.util.function.Consumer<TxnFactory.BuiltTxn> completed,
                        java.util.function.Consumer<Exception> failed) {
        final SeedVault vault = mVault;
        final java.util.function.BooleanSupplier connection = mUiSession;
        final String reviewAgain = Language.text(this, l10n_wallet_review_again);
        runAuthenticatedWalletWork(() -> {
            if (connection != mUiSession || !connection.getAsBoolean())
                throw new SeedVault.SigningNotAllowedException(reviewAgain);
            vault.assertSigningAllowed();
            try { return builder.call(); }
            finally {
                try { vault.syncKeyUses(); }
                catch (Exception snapshot) {
                    android.util.Log.w("wallet", "keyuses snapshot resync failed (safe to lag)", snapshot);
                }
            }
        }, completed, failed);
    }

'''
    s=s.replace(marker,helper+marker)
    start=s.index('                    try {\n                        if (!current.getAsBoolean())',s.index('public void confirmSignAndPublish(CharSequence zReview, int zKeyIndex,',s.index('public void confirmSignAndPublish(CharSequence zReview, int zKeyIndex,')+1))
    end=s.index('\n                })\n                .setNegativeButton',start)
    s=s[:start]+'''                    if (!current.getAsBoolean()) { toast(Language.msg(l10n_wallet_review_again)); return; }
                    final NodeLink node = mNode;
                    runSigningWork(zBuilder, built -> {
                        if (!current.getAsBoolean()) return;
                        Toast.makeText(this, Language.msg(l10n_wallet_broadcasting), Toast.LENGTH_SHORT).show();
                        node.publish(built, new NodeApi.Cb() {
                            @Override public void onResult(org.json.JSONObject json) {
                                if (!current.getAsBoolean()) return;
                                String txpowid = Util.extractTxpowid(json, built.getID());
                                Toast.makeText(MainActivity.this, Language.msg(l10n_wallet_broadcast_sent, txpowid), Toast.LENGTH_LONG).show();
                                if (zOnSent != null) zOnSent.run();
                                reload();
                            }
                            @Override public void onError(String message) {
                                if (!current.getAsBoolean()) return;
                                Toast.makeText(MainActivity.this, Language.msg(l10n_wallet_broadcast_failed, message), Toast.LENGTH_LONG).show();
                            }
                        });
                    }, error -> {
                        if (error instanceof SeedVault.SigningNotAllowedException)
                            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                        else Toast.makeText(this, Language.msg(l10n_nft_failed, error.getMessage()), Toast.LENGTH_LONG).show();
                    });'''+s[end:]
    p.write_text(s)

    p=base/'SendView.java';s=p.read_text()
    s=s.replace('    private Mode mMode = Mode.SEND;', '    private final java.util.function.BooleanSupplier session;\n    private Mode mMode = Mode.SEND;')
    s=s.replace('        super(a, makeRoot(a));','        super(a, makeRoot(a));\n        session = a.sessionAuthorization();')
    for name in ['reviewSend','reviewSplit','reviewConsolidate']:
        old='    private void '+name+'() {\n        try {'
        assert s.count(old)==1
        s=s.replace(old,old+'\n            if (!session.getAsBoolean()) return;\n            final TxnFactory factory = act.factory();\n            final MiniData tokenId = tokenIdData();')
    for method in ['buildSend','buildSplit','buildConsolidate']:
        assert s.count('act.factory().'+method)==1
        s=s.replace('act.factory().'+method,'factory.'+method)
    assert s.count('tokenIdData(), burn, newId()')==3
    s=s.replace('tokenIdData(), burn, newId()', 'tokenId, burn, newId()')
    start=s.index('                    try {\n                        // Fail-safe gate')
    end=s.index('\n                })\n                .setNegativeButton',start)
    s=s[:start]+'''                    if (!session.getAsBoolean() || !current.getAsBoolean()) {
                        Toast.makeText(act, Language.msg(l10n_wallet_review_again), Toast.LENGTH_LONG).show(); return;
                    }
                    act.runSigningWork(builder, this::publish, error -> {
                        if (error instanceof SeedVault.SigningNotAllowedException)
                            Toast.makeText(act, error.getMessage(), Toast.LENGTH_LONG).show();
                        else err(error);
                    });'''+s[end:]
    s=s.replace('        final java.util.function.BooleanSupplier current = act.sessionAuthorization();\n        CharSequence full', '        if (!session.getAsBoolean()) return;\n        final java.util.function.BooleanSupplier current = act.sessionAuthorization();\n        CharSequence full')
    s=s.replace('    private void publish(TxnFactory.BuiltTxn built) {', '    private void publish(TxnFactory.BuiltTxn built) {\n        if (!session.getAsBoolean()) return;')
    start=s.index('    private void publish(TxnFactory.BuiltTxn built) {');end=s.index('    // ---------------------------------------------------------------------------------------------',start)
    part=s[start:end].replace('public void onResult(org.json.JSONObject json) {','public void onResult(org.json.JSONObject json) {\n                if (!session.getAsBoolean()) return;').replace('public void onError(String message) {','public void onError(String message) {\n                if (!session.getAsBoolean()) return;')
    s=s[:start]+part+s[end:]
    s=s.replace('        if (isMinima()) { zGo.accept(toInputs(zSel)); return; }', '        if (!session.getAsBoolean()) return;\n        if (isMinima()) { zGo.accept(toInputs(zSel)); return; }')
    s=s.replace('public void onResolved(List<TxnFactory.InputCoin> inputs) { zGo.accept(inputs); }', 'public void onResolved(List<TxnFactory.InputCoin> inputs) { if (session.getAsBoolean()) zGo.accept(inputs); }')
    s=s.replace('                Toast.makeText(act, Language.msg(l10n_wallet_prepare_coins_failed, message)', '                if (!session.getAsBoolean()) return;\n                Toast.makeText(act, Language.msg(l10n_wallet_prepare_coins_failed, message)')
    p.write_text(s)

    p=base/'NftView.java';s=p.read_text()
    old='''                    act.confirmSignAndPublish(review, keyIndex,
                            () -> act.factory().buildNftTransfer(in, recipient, Util.newTxnId()),'''
    assert s.count(old)==1
    s=s.replace(old,'''                    final TxnFactory factory = act.factory();
                    act.confirmSignAndPublish(review, keyIndex,
                            () -> factory.buildNftTransfer(in, recipient, Util.newTxnId()),''')
    start=s.index('                final TxnFactory.BuiltTxn built;',s.index('    private void sendNext('))
    end=s.index('\n            }\n            @Override public void onError',start)
    s=s[:start]+'''                if (isPending(coinid)) {
                    finishCollectionSend(g, recipient, coins.size(), i, txpowids, sentCoinIds,
                            Language.msg(l10n_nft_wait_pending), pd); return;
                }
                final TxnFactory factory = act.factory();
                final TxnFactory.InputCoin input = inputs.get(0);
                act.runSigningWork(() -> factory.buildNftTransfer(input, recipient, Util.newTxnId()), built -> {
                    if (!session.getAsBoolean()) return;
                    publishOne(g, coins, recipient, i, txpowids, sentCoinIds, progress, pd, cancelled, coinid, idx, built);
                }, error -> {
                    if (!session.getAsBoolean()) return;
                    finishCollectionSend(g, recipient, coins.size(), i, txpowids, sentCoinIds,
                            Language.msg(l10n_nft_item_failed, idx >= 0 ? "#" + idx : String.valueOf(i + 1), error.getMessage()), pd);
                });'''+s[end:]
    p.write_text(s)
