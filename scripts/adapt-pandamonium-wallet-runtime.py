"""Wallet presentation cleanup and unavailable-node handling; signing/counter rules stay intact."""
from pathlib import Path

def adapt(root):
    folder=root/'wallet/src/main/java/com/eurobuddha/wallet'
    def change(name,old,new,n=1):
        p=folder/name;s=p.read_text();assert s.count(old)==n,('Wallet runtime drift',name,old,s.count(old));p.write_text(s.replace(old,new))
    for p in folder.glob('*.java'):
        if p.name in ('WalletDialog.java','WalletDialogs.java'):continue
        s=p.read_text();p.write_text(s.replace('new LanguageDialog(', 'new WalletDialog('))
    main='MainActivity.java'
    change(main,'    private java.util.function.BooleanSupplier mUiSession = () -> false;',"""    private final WalletDialogs mDialogs = new WalletDialogs();
    void trackWalletDialog(android.app.Dialog dialog, boolean sensitive) { mDialogs.track(dialog,sensitive); }
    private volatile java.util.function.BooleanSupplier mUiSession = () -> false;""")
    change(main,'    private void stopWalletConnection() {\n        mUiSession = () -> false;',
        '    private void stopWalletConnection() {\n        mUiSession = () -> false;\n        mDialogs.dismissAll();')
    change(main,'        if (mSession != null && mSession.work().busy()) return;',"""        if (mSession != null && mSession.work().busy()) return;
        if (mVault != null && !mVault.isOpen() && (mWallet != null || mKeyUses != null)) showUnlock();""")
    change(main,'        mForeground = false;\n        mTickHandler.removeCallbacks(mBlockTick);',
        '        mForeground = false;\n        mDialogs.dismissSensitive();\n        mTickHandler.removeCallbacks(mBlockTick);')
    # The host screenshot policy must preserve the wallet's current secret-bearing screen.
    change(main, '    private final WalletDialogs mDialogs = new WalletDialogs();',
        '    private boolean mSensitiveScreen;\n    public boolean isSensitiveScreen() { return mSensitiveScreen; }\n    private final WalletDialogs mDialogs = new WalletDialogs();')
    # Seed and seed-entry screens reuse ETH Wallet / Parlons FLAG_SECURE protection.
    for signature in ['private void onboardGenerate() {','private void onboardVerifySeed(final String phrase, final String passphrase) {','private void onboardImport(boolean useNodeSeed) {','private void showUnlock() {']:
        change(main,signature,signature+'\n        mSensitiveScreen = true;\n        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);')
    for signature in ['private void showOnboarding() {','private void buildUi() {']:
        change(main,signature,signature+'\n        mSensitiveScreen = false;\n        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);')
    change(main,'seed.setTextIsSelectable(true);','seed.setTextIsSelectable(false);')
    change(main,'tv.setTextIsSelectable(true);','tv.setTextIsSelectable(false);')
    change(main,'new WalletDialog(this)\n                    .setTitle(Language.msg(l10n_wallet_your_seed))',
        'new WalletDialog(this).sensitive()\n                    .setTitle(Language.msg(l10n_wallet_your_seed))')
    # Keep construction failures out of the main-thread uncaught exception handler.
    change(main,'    private void startMain() {\n        stopWalletConnection();',"""    private void startMain() {
        try { prepareMain(); }
        catch (RuntimeException unavailable) {
            stopWalletConnection();
            mSession.lock();
            showUnlock();
            toast(Language.msg(l10n_wallet_prepare_unavailable));
        }
    }
    private void prepareMain() {
        stopWalletConnection();""")
    change(main,'        if (mStatusBanner == null) return;', '        if (mStatusBanner == null || mBook == null) return;')
    change(main,'                book.deriveMissing(current);\n                runOnUiThread(() -> { if (current.getAsBoolean() && book == mBook) onAddressBookReady(); });',"""                try {
                    book.deriveMissing(current);
                    runOnUiThread(() -> { if (current.getAsBoolean() && book == mBook) onAddressBookReady(); });
                } catch (RuntimeException unavailable) {
                    runOnUiThread(() -> { if (current.getAsBoolean()) toast(Language.msg(l10n_wallet_prepare_unavailable)); });
                }""")
    change(main,'        CharSequence full = Language.join(zReview, "\\n", Language.msg(l10n_wallet_key_usage, zKeyIndex, mKeyUses.currentUses(zKeyIndex), Util.WOTS_MAX_USES)',"""        final int uses;
        try { uses = mKeyUses.currentUses(zKeyIndex); }
        catch (RuntimeException unavailable) { toast(Language.msg(l10n_wallet_counter_unavailable)); return; }
        CharSequence full = Language.join(zReview, "\\n", Language.msg(l10n_wallet_key_usage, zKeyIndex, uses, Util.WOTS_MAX_USES)""")
    settings='SettingsView.java'
    change(settings,'        int used = act.keyUses().currentUses(0);\n        container.addView(kv(Language.msg(l10n_wallet_signatures_used), used + " / " + Util.WOTS_MAX_USES));',"""        CharSequence usage;
        try { usage = act.keyUses().currentUses(0) + " / " + Util.WOTS_MAX_USES; }
        catch (RuntimeException unavailable) { usage = Language.msg(l10n_wallet_counter_unavailable); }
        container.addView(kv(Language.msg(l10n_wallet_signatures_used), usage));""")
    change(settings,'        final int current = act.keyUses().currentUses(0);',"""        final int current;
        try { current = act.keyUses().currentUses(0); }
        catch (RuntimeException unavailable) {
            Toast.makeText(act, Language.msg(l10n_wallet_counter_unavailable), Toast.LENGTH_LONG).show();
            return;
        }""")
    change(settings,'tv.setTextIsSelectable(true);','tv.setTextIsSelectable(false);')
    change(settings,'new WalletDialog(act)\n                .setTitle(Language.msg(l10n_wallet_your_seed))',
        'new WalletDialog(act).sensitive()\n                .setTitle(Language.msg(l10n_wallet_your_seed))')
    change(settings,"        // selectable for the user's own on-screen reading only.", '        // displayed without selection so the platform selection menu cannot copy it.')
    change('NftView.java','        final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);',
        '        final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);\n        act.trackWalletDialog(d, false);',2)
    # Correct the previously noted inherited label using the existing script classifier.
    change('NftView.java','g.meta.stateNft ? Language.msg(l10n_nft_locked_edition) : "NFT"',
        'Nft.isLockedScript(g.meta.script) ? Language.msg(l10n_nft_locked_edition) : g.meta.stateNft ? "StateNFT" : "NFT"')

    change(main,'&& !mSession.work().busy() && current.getAsBoolean();',
        '&& !mSession.work().busy() && current == mUiSession && current.getAsBoolean();')
    # Reuse SettingsView's captured session supplier for every NFT operation/callback.
    change('NftView.java','    private final LinearLayout grid;',
        '    private final java.util.function.BooleanSupplier session;\n    private final LinearLayout grid;')
    change('NftView.java','        super(a, buildRoot(a));',
        '        super(a, buildRoot(a));\n        session = a.sessionAuthorization();')
    import re
    p=folder/'NftView.java';s=p.read_text()
    for method in ['refresh','promptSendItem','promptSendCollection','reviewSendCollection','runCollectionSend','sendNext','publishOne','finishCollectionSend','trackConfirmations','offerRetry','showReport']:
        pattern=r'((?:private|public) void '+method+r'\(.*?\) \{)'
        s,n=re.subn(pattern,lambda m:m[1]+'\n        if (!session.getAsBoolean()) return;',s,count=1,flags=re.S)
        assert n==1,('NFT session entry drift',method)
    for method in ['onResolved','onResult','onError']:
        pattern=r'(?m)^([ \t]*)(@Override )?(public void '+method+r'\([^)]*\) \{)'
        s,n=re.subn(pattern,lambda m:m[0]+'\n'+m[1]+'    if (!session.getAsBoolean()) return;',s)
        assert n>0,('NFT callback drift',method)
    s=s.replace('if (stopped[0] || act.isDestroyed()) return;',
        'if (stopped[0] || act.isDestroyed() || !session.getAsBoolean()) return;')
    p.write_text(s)
