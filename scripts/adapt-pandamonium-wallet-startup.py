"""Use the existing wallet worker for first-key preparation and the host screenshot policy."""
from pathlib import Path

def adapt(root):
    p=root/'wallet/src/main/java/com/eurobuddha/wallet/MainActivity.java'
    s=p.read_text()
    old='''    private boolean mSensitiveScreen;
    public boolean isSensitiveScreen() { return mSensitiveScreen; }'''
    new='''    private static java.util.function.BooleanSupplier sScreenshotsAllowed = () -> false;
    public static void installScreenshotPolicy(java.util.function.BooleanSupplier allowed) {
        sScreenshotsAllowed = java.util.Objects.requireNonNull(allowed);
    }
    private boolean mSensitiveScreen;
    public boolean isSensitiveScreen() { return mSensitiveScreen; }
    private void setSensitiveScreen(boolean sensitive) {
        mSensitiveScreen = sensitive;
        boolean allowed = false;
        try { allowed = sScreenshotsAllowed.getAsBoolean(); }
        catch (RuntimeException unavailable) { /* An unreadable preference cannot expose a screen. */ }
        if (sensitive || !allowed) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }'''
    assert s.count(old)==1;s=s.replace(old,new)
    for sensitive,action,count in [('true','addFlags',4),('false','clearFlags',2)]:
        old=f'        mSensitiveScreen = {sensitive};\n        getWindow().{action}(android.view.WindowManager.LayoutParams.FLAG_SECURE);'
        assert s.count(old)==count;s=s.replace(old,f'        setSensitiveScreen({sensitive});')
    old='''    private void startMain() {
        try { prepareMain(); }
        catch (RuntimeException unavailable) {
            stopWalletConnection();
            mSession.lock();
            showUnlock();
            toast(Language.msg(l10n_wallet_prepare_unavailable));
        }
    }
    private void prepareMain() {
        stopWalletConnection();
        mUiSession = mVault.authorization();
        mKeyUses = mSession.keyUses();
        mWallet  = new WalletCore(mVault.phrase(), mKeyUses);
        mBook    = new AddressBook(this, mWallet);   // loads the cached 64-address set if present
        mFactory = new TxnFactory(mWallet);
        mAddr0x  = mWallet.getReceiveAddress().getAddressData().to0xString();
        mAddrMx  = mWallet.getReceiveAddress().getMinimaAddress();

        buildUi();'''
    new='''    private void startMain() {
        if (mSession.work().busy() || mWaitingForWork) return;
        try { prepareMain(); }
        catch (RuntimeException unavailable) { preparationFailed(); }
    }
    private void preparationFailed() {
        stopWalletConnection();
        mSession.lock();
        showUnlock();
        toast(Language.msg(l10n_wallet_prepare_unavailable));
    }
    /** Only public addresses and the existing generation-bound wallet handles cross back to UI. */
    private static final class PreparedWallet {
        final KeyUses keyUses;
        final WalletCore wallet;
        final AddressBook book;
        final TxnFactory factory;
        final String address0x, addressMx;
        PreparedWallet(android.content.Context app, SeedVault vault) {
            keyUses = vault.keyUses();
            wallet = new WalletCore(vault.phrase(), keyUses);
            book = new AddressBook(app, wallet);
            factory = new TxnFactory(wallet);
            org.minima.objects.Address address = wallet.getReceiveAddress();
            address0x = address.getAddressData().to0xString();
            addressMx = address.getMinimaAddress();
        }
    }
    private void prepareMain() {
        stopWalletConnection();
        // A warm open session may enter here before any Activity content exists.
        if (mScreenHost == null) setContentView(new FrameLayout(this));
        final SeedVault vault = mVault;
        final java.util.function.BooleanSupplier authorized = vault.authorization();
        final android.content.Context app = getApplicationContext();
        final java.util.concurrent.atomic.AtomicReference<PreparedWallet> prepared = new java.util.concurrent.atomic.AtomicReference<>();
        runWalletWork(() -> {
            if (!authorized.getAsBoolean()) throw new SeedVault.SigningNotAllowedException("Wallet session changed");
            long started = System.nanoTime();
            PreparedWallet value = new PreparedWallet(app, vault);
            android.util.Log.i("WalletTiming", "prepare_ms=" + (System.nanoTime()-started)/1_000_000);
            if (!authorized.getAsBoolean()) throw new SeedVault.SigningNotAllowedException("Wallet session changed");
            prepared.set(value);
        }, () -> {
            PreparedWallet value = prepared.getAndSet(null);
            if (isFinishing() || isDestroyed()) return;
            if (vault != mVault || !authorized.getAsBoolean() || value == null) {
                preparationFailed(); return;
            }
            try { installMain(value, authorized); }
            catch (RuntimeException unavailable) { preparationFailed(); }
        }, failure -> {
            prepared.set(null);
            preparationFailed();
        });
    }
    private void installMain(PreparedWallet prepared, java.util.function.BooleanSupplier authorized) {
        mUiSession = authorized;
        mKeyUses = prepared.keyUses;
        mWallet = prepared.wallet;
        mBook = prepared.book;
        mFactory = prepared.factory;
        mAddr0x = prepared.address0x;
        mAddrMx = prepared.addressMx;
        buildUi();'''
    assert s.count(old)==1;s=s.replace(old,new)
    p.write_text(s)
