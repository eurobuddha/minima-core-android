package com.eurobuddha.wallet;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minima.utils.BIP39;
import com.eurobuddha.wallet.comms.NodeApi;
import com.eurobuddha.wallet.comms.NodeLink;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * M3 home — the full self-custodial wallet: onboarding (generate / import seed + wallet passphrase),
 * unlock, and the tabbed UI (Balances / Send / Receive / Settings). The seed + WOTS keyuses now live in
 * the portable, seed-derived-encrypted {@link SeedVault} (M2's device-bound {@code SeedStore} is
 * retired), and Send/Split/Consolidate are wired through {@link TxnFactory} + {@link NodeLink}.
 */
public class MainActivity extends AppCompatActivity {

    // Keep the host drawer attached while setup, unlock and wallet screens replace their content.
    // Reuse the existing tab FrameLayout pattern; all wallet state stays in its original owners.
    private FrameLayout mScreenHost;
    @Override public void setContentView(View view) {
        if (mScreenHost == null) {
            mScreenHost = new FrameLayout(this);
            super.setContentView(mScreenHost);
        }
        mScreenHost.removeAllViews();
        mScreenHost.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        androidx.core.view.ViewCompat.requestApplyInsets(mScreenHost);
    }


    public static final int TAB_BALANCES = 0;
    public static final int TAB_SEND     = 1;
    public static final int TAB_RECEIVE  = 2;
    public static final int TAB_HISTORY  = 3;
    public static final int TAB_NFTS     = 4;
    public static final int TAB_SETTINGS = 5;

    // --- wallet core ---
    private WalletSession mSession;
    private KeyUses mKeyUses;
    private SeedVault mVault;
    private WalletCore mWallet;
    private TxnFactory mFactory;
    private String mAddr0x;
    private String mAddrMx;
    private AddressBook mBook;
    private final Runnable mWorkListener = this::walletWorkFinished;
    private androidx.appcompat.app.AlertDialog mWorkDialog;
    private Runnable mAfterWork;
    private java.util.function.Consumer<Exception> mWorkFailure;
    private volatile Exception mCompletedWorkFailure;
    private boolean mWaitingForWork, mWorkOnlyScreen;

    public void runWalletWork(Runnable operation, Runnable after) {
        runWalletWork(operation, after, null);
    }

    private void runWalletWork(Runnable operation, Runnable after,
                               java.util.function.Consumer<Exception> failed) {
        if (mSession.work().busy() || mWaitingForWork) { toast(Language.msg(l10n_wallet_busy)); return; }
        mAfterWork = after;
        mWorkFailure = failed;
        mCompletedWorkFailure = null;
        mWaitingForWork = true;
        try {
            if (!mSession.work().start(() -> {
                try { operation.run(); }
                catch (RuntimeException failure) { mCompletedWorkFailure = failure; throw failure; }
            })) {
                mWaitingForWork = false; mAfterWork = null; mWorkFailure = null;
                return;
            }
        } catch (RuntimeException rejected) {
            mWaitingForWork = false; mAfterWork = null; mWorkFailure = null;
            if (failed != null) failed.accept(rejected);
            else toast(Language.msg(l10n_wallet_operation_failed));
            return;
        }
        if (mSession.work().busy()) showWalletWork();
        else walletWorkFinished();
    }

    /** Settings work belongs to the open vault; only UI delivery belongs to this Activity. */
    public <T> void runAuthenticatedWalletWork(java.util.concurrent.Callable<T> operation,
                                              java.util.function.Consumer<T> completed,
                                              java.util.function.Consumer<Exception> failed) {
        if (mSession.work().busy()) { toast(Language.msg(l10n_wallet_busy)); return; }
        if (mSession.shouldLockOnForeground(System.currentTimeMillis())) {
            mSession.lock(); showUnlock(); return;
        }
        final java.util.function.BooleanSupplier connection = mUiSession;
        if (!sessionAuthorization().getAsBoolean()) { toast(Language.msg(l10n_wallet_session_changed)); return; }
        final SeedVault vault = mVault;
        final java.util.function.BooleanSupplier authorized = vault.authorization();
        final java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<java.util.function.BooleanSupplier> resultAuthorization = new java.util.concurrent.atomic.AtomicReference<>();
        runWalletWork(() -> {
            try {
                if (!authorized.getAsBoolean()) throw new SeedVault.SigningNotAllowedException("Wallet session changed");
                result.set(operation.call());
                resultAuthorization.set(vault.authorization());
            } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }, () -> {
            java.util.function.BooleanSupplier completedSession = resultAuthorization.getAndSet(null);
            if (connection == mUiSession && vault == mVault && completedSession != null && completedSession.getAsBoolean())
                completed.accept(result.getAndSet(null));
            else result.set(null);
        }, error -> {
            result.set(null);
            resultAuthorization.set(null);
            Throwable cause = error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
            if (connection == mUiSession && failed != null)
                failed.accept(cause instanceof Exception ? (Exception)cause : error);
        });
    }
    /** Called only after review approval. Reservation and snapshot ordering remain unchanged. */
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

    private void showWalletWork() {
        if (mWorkDialog != null) return;
        mWorkDialog = new WalletDialog(this)
                .setTitle(Language.msg(l10n_wallet_working))
                .setMessage(Language.msg(l10n_wallet_working_body))
                .setCancelable(false).create();
        mWorkDialog.show();
    }
    private void walletWorkFinished() {
        if (!mWaitingForWork || !mForeground || isFinishing() || isDestroyed() || mSession.work().busy()) return;
        if (mSession.shouldLockOnForeground(System.currentTimeMillis())) mSession.lock();
        mWaitingForWork = false;
        if (mWorkDialog != null) { mWorkDialog.dismiss(); mWorkDialog = null; }
        Runnable after = mAfterWork; mAfterWork = null;
        java.util.function.Consumer<Exception> failed = mWorkFailure; mWorkFailure = null;
        Exception failure = mWorkOnlyScreen ? mSession.work().failure() : mCompletedWorkFailure;
        mCompletedWorkFailure = null;
        if (failure != null) {
            if (failed != null) failed.accept(failure);
            else toast("Wrong wallet passphrase".equals(failure.getMessage()) ? Language.msg(l10n_wallet_wrong_passphrase) : Language.msg(l10n_wallet_operation_failed));
        }
        if (mVault.exists() && !mVault.isOpen()) showUnlock();
        else if (failure == null && after != null) after.run();
        else if (mWorkOnlyScreen) {
            if (mVault.isOpen()) startMain(); else showOnboarding();
        }
        mWorkOnlyScreen = false;
        if (mUiSession.getAsBoolean() && mBook != null) onAddressBookReady();
        if (mForeground) {
            mTickHandler.removeCallbacks(mBlockTick);
            mTickHandler.postDelayed(mBlockTick, BLOCK_TICK_MS);
        }
    }

    private static java.util.function.BooleanSupplier sScreenshotsAllowed = () -> false;
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
    }
    private final WalletDialogs mDialogs = new WalletDialogs();
    void trackWalletDialog(android.app.Dialog dialog, boolean sensitive) { mDialogs.track(dialog,sensitive); }
    private volatile java.util.function.BooleanSupplier mUiSession = () -> false;

    private void stopWalletConnection() {
        mUiSession = () -> false;
        mDialogs.dismissAll();
        if (mNode != null) mNode.onDestroy();
        mNode = null;
        if (mBook != null) mBook.setDeriveListener(null);
        mBook = null; mWallet = null; mFactory = null; mKeyUses = null;
        mAddr0x = null; mAddrMx = null;
        mPaired = false; mTracked = false; mTrackOk = 0;
        mScanCallback = null; mCreateDocCallback = null; mOpenDocCallback = null;
        mBalances.clear(); mMeta.clear(); mLastCoins = new org.minima.utils.json.JSONArray();
    }

    public java.util.function.BooleanSupplier sessionAuthorization() {
        final java.util.function.BooleanSupplier current = mUiSession;
        return () -> !isFinishing() && !isDestroyed() && !mSession.work().busy() && current == mUiSession && current.getAsBoolean();
    }

    /** Reuse the wallet's existing import/generate forms after explicit reauthentication. */
    public void changeSeed() {
        final EditText pass = password();
        new WalletDialog(this)
                .setTitle(Language.msg(l10n_wallet_change_seed))
                .setMessage(Language.msg(l10n_wallet_change_seed_body))
                .setView(pass)
                .setPositiveButton(Language.msg(l10n_wallet_continue), (d,w) -> {
                    final SeedVault vault = mVault;
                    final String password = pass.getText().toString(); pass.setText("");
                    runAuthenticatedWalletWork(() -> vault.verifyPassphrase(password), valid -> {
                        if (!valid) {
                            toast(Language.msg(l10n_wallet_wrong_passphrase)); return;
                        }
                        final java.util.function.BooleanSupplier authorized = sessionAuthorization();
                        new WalletDialog(this)
                                .setTitle(Language.msg(l10n_wallet_choose_seed))
                                .setItems(new CharSequence[]{Language.msg(l10n_wallet_use_node_seed), Language.msg(l10n_wallet_import_different), Language.msg(l10n_wallet_create_seed)}, (choice,index) -> {
                                    if (!authorized.getAsBoolean()) { toast(Language.msg(l10n_wallet_session_changed)); return; }
                                    stopWalletConnection();
                                    mSession.lock();
                                    if (index == 0) onboardImport(true);
                                    else if (index == 1) onboardImport(false);
                                    else onboardGenerate();
                                }).setNegativeButton(Language.msg(l10n_common_cancel), null).show();
                    }, failure -> toast(Language.msg(l10n_wallet_verify_failed)));
                }).setNegativeButton(Language.msg(l10n_common_cancel), null).show();
    }

    /** Derive/track status banner (visible until all 64 addresses are derived AND registered). */
    private TextView mStatusBanner;

    /** How many base scripts the node has confirmed tracked this session (contiguous from 0). */
    private int mTrackOk = 0;

    // --- transport ---
    private NodeLink mNode;
    private boolean mPaired = false;
    private boolean mTracked = false;

    // --- balances state ---
    private final List<TokenBalance> mBalances = new ArrayList<>();
    private final Map<String, TokenMeta> mMeta = new LinkedHashMap<>();
    private org.minima.utils.json.JSONArray mLastCoins = new org.minima.utils.json.JSONArray();
    private String mBlock = "";

    // --- views ---
    private TextView mBlockView;
    private LinearLayout mPairingBanner;
    private FrameLayout mContent;
    private TextView mTabBalances, mTabSend, mTabReceive, mTabHistory, mTabNfts, mTabSettings;
    private BalancesView mBalancesView;
    private SendView mSendView;
    private ReceiveView mReceiveView;
    private HistoryView mHistoryView;
    private NftView mNftView;
    private SettingsView mSettingsView;
    private int mTab = TAB_BALANCES;

    // --- ActivityResult launchers (must be registered in onCreate) ---
    private ActivityResultLauncher<ScanOptions> mScanLauncher;
    private ActivityResultLauncher<String> mCreateDocLauncher;
    private ActivityResultLauncher<String[]> mOpenDocLauncher;
    private Consumer<String> mScanCallback;
    private Consumer<Uri> mCreateDocCallback;
    private Consumer<Uri> mOpenDocCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Design.load(this);
        // Reuse PandaPools: native dialogs follow the selected app palette, not the system mode.
        getDelegate().setLocalNightMode(Design.isDark()
                ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);

        registerLaunchers();

        // The open-vault session is Application-scoped, so an unlock survives Activity recreate()/
        // background for the chosen auto-lock timeout (see WalletSession).
        mSession = WalletSession.get(this);
        mVault   = mSession.vault();
        mSession.work().observe(mWorkListener);
        if (mSession.work().busy()) {
            mWaitingForWork = true; mWorkOnlyScreen = true;
            setContentView(new FrameLayout(this));
            showWalletWork();
            return;
        }

        if (!mVault.exists()) {
            showOnboarding();
        } else if (!mVault.isOpen() || mSession.shouldLockOnForeground(System.currentTimeMillis())) {
            // Locked, or the auto-lock timeout elapsed while backgrounded → require unlock.
            mSession.lock();
            showUnlock();
        } else {
            mSession.onHandledForeground(true);
            startMain();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mForeground = true;
        if (mSession != null && mSession.work().busy()) return;
        if (mWaitingForWork) walletWorkFinished();
        if (mVault != null && !mVault.isOpen() && (mWallet != null || mKeyUses != null)) showUnlock();
        else if (mVault != null && mVault.isOpen() && mWallet != null && !mUiSession.getAsBoolean()) startMain();
        // Enforce the auto-lock timeout when returning to the foreground. Only acts on a real
        // return-from-background (WalletSession ignores config-change recreate); a foreground recreate
        // keeps the session open even in "Immediately" mode.
        if (mSession != null && mVault != null && mVault.isOpen()) {
            if (mSession.shouldLockOnForeground(System.currentTimeMillis())) {
                mSession.lock();
                showUnlock();
            } else {
                mSession.onHandledForeground(true);
            }
        }
        mForeground = true;
        mTickHandler.removeCallbacks(mBlockTick);
        mTickHandler.postDelayed(mBlockTick, BLOCK_TICK_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mForeground = false;
        mDialogs.dismissSensitive();
        mTickHandler.removeCallbacks(mBlockTick);
    }

    // =============================================================================================
    // Foreground block tick — the node has no push channel, so poll the tip while visible and
    // drive the live surfaces (header, balances, History) when the block actually changes.
    // =============================================================================================

    private static final long BLOCK_TICK_MS = 30_000;
    private final android.os.Handler mTickHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean mForeground = false;
    private final Runnable mBlockTick = new Runnable() {
        @Override public void run() {
            if (isDestroyed() || !mForeground) return;
            if (mPaired && mNode != null) loadBlock();
            mTickHandler.postDelayed(this, BLOCK_TICK_MS);
        }
    };

    /** A genuinely new chain tip: refresh data and tell the visible tab. */
    private void onBlockChanged() {
        if (mPaired && mNode != null) loadEverything();
        BaseView view;
        switch (mTab) {
            case TAB_SEND:     view = mSendView; break;
            case TAB_RECEIVE:  view = mReceiveView; break;
            case TAB_HISTORY:  view = mHistoryView; break;
            case TAB_NFTS:     view = mNftView; break;
            case TAB_SETTINGS: view = mSettingsView; break;
            default:           view = mBalancesView;
        }
        if (view != null) view.onNewBlock();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        // Keep the session alive while the user is actively interacting.
        if (mSession != null && !mSession.work().busy() && mVault != null && mVault.isOpen()) mSession.touch();
    }

    private void registerLaunchers() {
        mScanLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (mScanCallback != null && result != null && result.getContents() != null) {
                mScanCallback.accept(result.getContents());
            }
            mScanCallback = null;
        });
        mCreateDocLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
                    if (mCreateDocCallback != null && uri != null) mCreateDocCallback.accept(uri);
                    mCreateDocCallback = null;
                });
        mOpenDocLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (mOpenDocCallback != null && uri != null) mOpenDocCallback.accept(uri);
                    mOpenDocCallback = null;
                });
    }

    // =============================================================================================
    // Onboarding
    // =============================================================================================

    private void showOnboarding() {
        setSensitiveScreen(false);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Design.bg());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(40), dp(24), dp(24));
        sv.addView(col);
        setContentView(sv);
        applyInsets(sv);

        col.addView(bigTitle("Minima Wallet"));
        col.addView(body(Language.msg(l10n_wallet_setup_intro)));

        col.addView(primary(Language.msg(l10n_wallet_use_node_seed), v -> onboardImport(true)));
        col.addView(secondary(Language.msg(l10n_wallet_import_existing), v -> onboardImport()));
    }

    private void onboardGenerate() {
        setSensitiveScreen(true);
        final String phrase = BIP39.convertWordListToString(BIP39.getNewWordList()).trim();

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Design.bg());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(32), dp(24), dp(24));
        sv.addView(col);
        setContentView(sv);
        applyInsets(sv);

        col.addView(bigTitle(Language.msg(l10n_wallet_backup_seed)));
        col.addView(body(Language.msg(l10n_wallet_backup_seed_body)));

        TextView seed = new TextView(this);
        seed.setText(phrase);
        seed.setTypeface(Typeface.MONOSPACE);
        seed.setTextColor(Design.text());
        seed.setTextSize(15f);
        seed.setBackgroundColor(Design.surface());
        seed.setPadding(dp(14), dp(14), dp(14), dp(14));
        seed.setTextIsSelectable(false);
        col.addView(seed);

        col.addView(label(Language.msg(l10n_wallet_passphrase_label)));
        final EditText p1 = password();
        col.addView(p1);
        col.addView(label(Language.msg(l10n_wallet_confirm_passphrase)));
        final EditText p2 = password();
        col.addView(p2);

        col.addView(primary(Language.msg(l10n_wallet_continue), v -> {
            String a = p1.getText().toString(), b = p2.getText().toString();
            if (a.length() < 8) { toast(Language.msg(l10n_wallet_passphrase_short)); return; }
            if (!a.equals(b)) { toast(Language.msg(l10n_wallet_passphrase_mismatch)); return; }
            // M2: do NOT complete setup yet — require the user to prove they backed up the seed.
            onboardVerifySeed(phrase, a);
        }));
    }

    /**
     * M2: seed-backup confirmation. Before a freshly generated wallet is considered set up (and can be
     * funded), the user must re-enter 3 randomly chosen words from the phrase — proving they actually
     * wrote it down. Only on success do we {@link SeedVault#createNew} and enter the wallet.
     */
    private void onboardVerifySeed(final String phrase, final String passphrase) {
        setSensitiveScreen(true);
        final String[] words = phrase.trim().split("\\s+");

        // Pick 3 distinct random word positions (ascending for a tidy prompt order).
        java.util.List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < words.length; i++) pool.add(i);
        java.util.Collections.shuffle(pool);
        final List<Integer> pick = new ArrayList<>(pool.subList(0, Math.min(3, words.length)));
        java.util.Collections.sort(pick);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Design.bg());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(32), dp(24), dp(24));
        sv.addView(col);
        setContentView(sv);
        applyInsets(sv);

        col.addView(bigTitle(Language.msg(l10n_wallet_confirm_backup)));
        col.addView(body(Language.msg(l10n_wallet_confirm_backup_body)));

        final List<EditText> answers = new ArrayList<>();
        for (int idx : pick) {
            col.addView(label(Language.msg(l10n_wallet_word_number, idx + 1)));
            EditText e = new EditText(this);
            e.setInputType(InputType.TYPE_CLASS_TEXT);
            e.setTextColor(Design.text());
            e.setHintTextColor(Design.dim());
            e.setBackgroundColor(Design.surface());
            e.setPadding(dp(12), dp(12), dp(12), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            e.setLayoutParams(lp);
            col.addView(e);
            answers.add(e);
        }

        col.addView(primary(Language.msg(l10n_wallet_create_wallet), v -> {
            for (int i = 0; i < pick.size(); i++) {
                String expected = words[pick.get(i)];
                String got = answers.get(i).getText().toString().trim();
                if (!got.equalsIgnoreCase(expected)) {
                    toast(Language.msg(l10n_wallet_word_mismatch, pick.get(i) + 1));
                    return;
                }
            }
            final SeedVault vault = mVault;
            final android.content.Context app = getApplicationContext();
            runWalletWork(() -> {
                vault.createNew(phrase, passphrase);
                BiometricUnlock.disable(app);
            }, () -> { mSession.touch(); startMain(); });
        }));

        col.addView(secondary(Language.msg(l10n_wallet_show_seed_again), v -> {
            TextView tv = new TextView(this);
            tv.setText(phrase);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextColor(Design.text());
            tv.setTextSize(15f);
            tv.setTextIsSelectable(false);
            tv.setPadding(dp(16), dp(16), dp(16), dp(16));
            new WalletDialog(this).sensitive()
                    .setTitle(Language.msg(l10n_wallet_your_seed))
                    .setMessage(Language.msg(l10n_wallet_seed_privacy))
                    .setView(tv)
                    .setNegativeButton(Language.msg(l10n_language_close), null)
                    .show();
        }));
    }

    private void onboardImport() { onboardImport(false); }
    private void onboardImport(boolean useNodeSeed) {
        setSensitiveScreen(true);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Design.bg());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(32), dp(24), dp(24));
        sv.addView(col);
        setContentView(sv);
        applyInsets(sv);

        col.addView(bigTitle(useNodeSeed ? Language.msg(l10n_wallet_node_seed_title) : Language.msg(l10n_wallet_import_seed_title)));
        col.addView(body(useNodeSeed
                ? Language.msg(l10n_wallet_node_seed_body)
                : Language.msg(l10n_wallet_import_seed_body)));

        final EditText phrase = new EditText(this);
        phrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        phrase.setMinLines(2);
        phrase.setTextColor(Design.text());
        phrase.setHintTextColor(Design.dim());
        Language.hint(phrase, Language.msg(l10n_wallet_seed_hint));
        phrase.setBackgroundColor(Design.surface());
        phrase.setPadding(dp(12), dp(12), dp(12), dp(12));
        if (!useNodeSeed) col.addView(phrase);

        col.addView(label(Language.msg(l10n_wallet_passphrase_label)));
        final EditText p1 = password();
        col.addView(p1);
        col.addView(label(Language.msg(l10n_wallet_confirm_passphrase)));
        final EditText p2 = password();
        col.addView(p2);

        col.addView(label(Language.msg(l10n_wallet_used_label)));
        final EditText used = new EditText(this);
        used.setInputType(InputType.TYPE_CLASS_NUMBER);
        used.setTextColor(Design.text());
        used.setHintTextColor(Design.dim());
        Language.hint(used, Language.msg(l10n_wallet_used_hint));
        used.setBackgroundColor(Design.surface());
        used.setPadding(dp(12), dp(12), dp(12), dp(12));
        col.addView(used);
        col.addView(body(Language.msg(l10n_wallet_used_body, Util.WOTS_MAX_USES)));

        col.addView(primary(Language.msg(l10n_wallet_import_wallet), v -> {
            String ph = phrase.getText().toString().trim();
            if (!useNodeSeed && ph.length() < 16) { toast(Language.msg(l10n_wallet_seed_short)); return; }
            String a = p1.getText().toString(), b = p2.getText().toString();
            if (a.length() < 8) { toast(Language.msg(l10n_wallet_passphrase_short)); return; }
            if (!a.equals(b)) { toast(Language.msg(l10n_wallet_passphrase_mismatch)); return; }
            String usedStr = used.getText().toString().trim();
            if (usedStr.isEmpty()) {
                toast(Language.msg(l10n_wallet_used_required));
                return;
            }
            final int usedN;
            try {
                usedN = Integer.parseInt(usedStr);
            } catch (NumberFormatException nfe) {
                toast(Language.msg(l10n_wallet_used_integer));
                return;
            }
            if (usedN < 0) { toast(Language.msg(l10n_wallet_used_negative)); return; }
            if (usedN >= Util.WOTS_MAX_USES) {
                toast(Language.msg(l10n_wallet_used_exhausted, Util.WOTS_MAX_USES));
                return;
            }
            confirmImport(ph, a, usedN, useNodeSeed);
        }));
    }

    /**
     * Final confirmation before an import commits its attested key-uses count. Echoes the number back and
     * restates the WOTS golden rule, because under-stating the count reuses a one-time key and can lose
     * funds. On confirm, {@link SeedVault#importSeed(String, String, int)} raises the counter (MAX-safe)
     * and stores the vault trusted.
     */
    private void confirmImport(String zPhrase, String zPassphrase, int zUsed, boolean useNodeSeed) {
        CharSequence msg = zUsed == 0 ? Language.msg(l10n_wallet_confirm_zero) : Language.msg(l10n_wallet_confirm_used, zUsed);
        new WalletDialog(this)
                .setTitle(Language.msg(l10n_wallet_confirm_floor))
                .setMessage(Language.join(useNodeSeed ? Language.join(Language.msg(l10n_wallet_node_seed_confirm), "\n\n") : "", msg, "\n\n", Language.msg(l10n_wallet_floor_scope)))
                .setPositiveButton(Language.msg(l10n_wallet_import), (d, w) -> {
                    final SeedVault vault = mVault;
                    final android.content.Context app = getApplicationContext();
                    runWalletWork(() -> {
                        if (useNodeSeed) vault.importNodeSeed(zPassphrase, zUsed);
                        else vault.importSeed(zPhrase, zPassphrase, zUsed);
                        BiometricUnlock.disable(app);
                    }, () -> { mSession.touch(); startMain(); });
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    // =============================================================================================
    // Unlock
    // =============================================================================================

    private void showUnlock() {
        setSensitiveScreen(true);
        stopWalletConnection();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Design.bg());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(60), dp(24), dp(24));
        sv.addView(col);
        setContentView(sv);
        applyInsets(sv);

        col.addView(bigTitle(Language.msg(l10n_wallet_unlock_title)));
        col.addView(body(Language.msg(l10n_wallet_unlock_body)));

        final EditText p = password();
        col.addView(p);
        col.addView(primary(Language.msg(l10n_lock_unlock), v -> unlockWallet(p.getText().toString())));

        // Biometric unlock is an OPTIONAL convenience — the typed passphrase above always remains the
        // fallback. When enabled AND currently usable, the OS biometric sheet is auto-prompted; no
        // on-screen button is needed (dismissing the sheet just leaves the passphrase field available).
        if (BiometricUnlock.isEnabled(this) && BiometricUnlock.isAvailable(this)) {
            col.postDelayed(this::promptBiometricUnlock, 350);
        }
    }

    /** Kick off a biometric unlock; on success open the vault via the SAME path as a typed passphrase. */
    private void promptBiometricUnlock() {
        if (!mForeground || isFinishing() || isDestroyed() || mSession.work().busy()) return;
        final java.util.function.BooleanSupplier screen = mUiSession;
        BiometricUnlock.unlock(this, () -> mForeground && screen == mUiSession && !mSession.work().busy(), new BiometricUnlock.UnlockCallback() {
            @Override public void onPassphrase(String passphrase) {
                if (!mSession.work().busy()) unlockWallet(passphrase);
            }
            @Override public void onError(String message, boolean permanentlyInvalidated) {
                toast(message);
                if (permanentlyInvalidated) recreate();   // repaint unlock without the biometric button
            }
            @Override public void onCancelled() { /* user backed out — passphrase field stays available */ }
        });
    }

    private void unlockWallet(String passphrase) {
        final SeedVault vault = mVault;
        runWalletWork(() -> {
            if (!vault.open(passphrase)) throw new IllegalArgumentException("Wrong wallet passphrase");
        }, this::onUnlocked);
    }

    /** Shared post-unlock step: mark the session active and enter the wallet. */
    private void onUnlocked() {
        mSession.touch();
        startMain();
    }

    // =============================================================================================
    // Main UI
    // =============================================================================================

    private void startMain() {
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
            PreparedWallet value = new PreparedWallet(app, vault);
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
        buildUi();

        mNode = new NodeLink(this, this::onPaired, sessionAuthorization());

        // Derive any missing base addresses (all 64 on first run — expensive) off the UI thread,
        // then re-track + reload so coins at ALL our addresses show up.
        if (!mBook.isComplete()) {
            final AddressBook book = mBook;
            final java.util.function.BooleanSupplier identity = mUiSession;
            final java.util.function.BooleanSupplier current = () -> !isDestroyed()
                    && identity.getAsBoolean() && book == mBook;
            book.setDeriveListener(n -> runOnUiThread(() -> {
                if (current.getAsBoolean() && book == mBook) updateStatusBanner();
            }));
            new Thread(() -> {
                try {
                    book.deriveMissing(current);
                    runOnUiThread(() -> { if (current.getAsBoolean() && book == mBook) onAddressBookReady(); });
                } catch (RuntimeException unavailable) {
                    runOnUiThread(() -> { if (current.getAsBoolean()) toast(Language.msg(l10n_wallet_prepare_unavailable)); });
                }
            }, "addressbook-derive").start();
        }
        updateStatusBanner();
    }

    /**
     * Repaint the derive/track banner: "deriving n/64" while keys are being built, then
     * "registering n/64" until the node has confirmed every script, then gone.
     */
    private void updateStatusBanner() {
        if (mStatusBanner == null || mBook == null) return;
        int derived = mBook.derivedCount();
        if (derived < AddressBook.SIZE) {
            Language.setText(mStatusBanner, Language.msg(l10n_wallet_deriving, derived, AddressBook.SIZE));
            mStatusBanner.setVisibility(View.VISIBLE);
        } else if (mPaired && mTrackOk < AddressBook.SIZE) {
            Language.setText(mStatusBanner, Language.msg(l10n_wallet_tracking, mTrackOk, AddressBook.SIZE));
            mStatusBanner.setVisibility(View.VISIBLE);
        } else {
            mStatusBanner.setVisibility(View.GONE);
        }
    }

    /** All 64 base addresses are now known: track them all and re-pull coins. */
    private void onAddressBookReady() {
        if (isDestroyed() || mSession.work().busy() || !mUiSession.getAsBoolean()) return;
        updateStatusBanner();
        if (mPaired && mNode != null) {
            trackScripts(0);
            loadEverything();
        }
        refreshCurrent();
    }

    private void buildUi() {
        setSensitiveScreen(false);
        LinearLayout rootv = new LinearLayout(this);
        rootv.setOrientation(LinearLayout.VERTICAL);
        rootv.setBackgroundColor(Design.bg());

        rootv.addView(buildHeader(),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mPairingBanner = buildPairingBanner();
        mPairingBanner.setVisibility(View.GONE);
        rootv.addView(mPairingBanner,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mStatusBanner = new TextView(this);
        mStatusBanner.setBackgroundColor(Design.accentSoft());
        mStatusBanner.setTextColor(Design.accent());
        mStatusBanner.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        mStatusBanner.setTextSize(13f);
        mStatusBanner.setPadding(dp(16), dp(10), dp(16), dp(10));
        mStatusBanner.setVisibility(View.GONE);
        rootv.addView(mStatusBanner,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        rootv.addView(buildTabBar(),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mContent = new FrameLayout(this);
        rootv.addView(mContent, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(rootv);
        applyInsets(rootv);

        mBalancesView = new BalancesView(this);
        mSendView     = new SendView(this);
        mReceiveView  = new ReceiveView(this);
        mHistoryView  = new HistoryView(this);
        mNftView      = new NftView(this);
        mSettingsView = new SettingsView(this);
        goToTab(TAB_BALANCES);
    }

    private View buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(16), dp(14), dp(16), dp(10));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Minima Wallet");
        title.setTextColor(Design.accent());
        title.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        title.setTextSize(17f);
        TextView sub = new TextView(this);
        Language.setText(sub, Language.msg(l10n_wallet_self_custodial));
        sub.setTextColor(Design.dim());
        sub.setTextSize(9f);
        sub.setLetterSpacing(0.15f);
        brand.addView(title);
        brand.addView(sub);
        h.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        mBlockView = new TextView(this);
        mBlockView.setText("#—");
        mBlockView.setTextColor(Design.dim());
        mBlockView.setTextSize(13f);
        mBlockView.setTypeface(Design.typeface());
        h.addView(mBlockView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return h;
    }

    private LinearLayout buildPairingBanner() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackgroundColor(Design.accentSoft());
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        TextView t1 = new TextView(this);
        Language.setText(t1, Language.msg(l10n_wallet_node_unavailable));
        t1.setTextColor(Design.accent());
        t1.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        t1.setTextSize(14f);
        b.addView(t1);
        TextView t2 = new TextView(this);
        Language.setText(t2, Language.msg(l10n_wallet_node_retry));
        t2.setTextColor(Design.dim());
        t2.setTextSize(12f);
        t2.setPadding(0, dp(3), 0, 0);
        b.addView(t2);
        return b;
    }

    private View buildTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Design.bg());
        mTabBalances = tab(Language.msg(l10n_wallet_balances), TAB_BALANCES);
        mTabSend     = tab(Language.msg(l10n_wallet_send), TAB_SEND);
        mTabReceive  = tab(Language.msg(l10n_wallet_receive), TAB_RECEIVE);
        mTabHistory  = tab(Language.msg(l10n_tab_history), TAB_HISTORY);
        mTabNfts     = tab("NFTS", TAB_NFTS);
        mTabSettings = tab(Language.msg(l10n_wallet_settings), TAB_SETTINGS);
        bar.addView(mTabBalances, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(mTabSend, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(mTabReceive, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(mTabHistory, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(mTabNfts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(mTabSettings, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return bar;
    }

    private TextView tab(CharSequence label, int idx) {
        TextView t = new TextView(this);
        Language.setText(t, label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(11f);
        t.setLetterSpacing(0.06f);
        t.setPadding(0, dp(12), 0, dp(12));
        t.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        t.setOnClickListener(v -> goToTab(idx));
        return t;
    }

    public void goToTab(int zTab) {
        mTab = zTab;
        mContent.removeAllViews();
        BaseView view;
        switch (zTab) {
            case TAB_SEND:     view = mSendView; break;
            case TAB_RECEIVE:  view = mReceiveView; break;
            case TAB_HISTORY:  view = mHistoryView; break;
            case TAB_NFTS:     view = mNftView; break;
            case TAB_SETTINGS: view = mSettingsView; break;
            default:           view = mBalancesView;
        }
        mContent.addView(view.getRoot(),
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        view.refresh();
        view.onShown();
        paintTabs();
    }

    private void paintTabs() {
        TextView[] tabs = {mTabBalances, mTabSend, mTabReceive, mTabHistory, mTabNfts, mTabSettings};
        int[] ids = {TAB_BALANCES, TAB_SEND, TAB_RECEIVE, TAB_HISTORY, TAB_NFTS, TAB_SETTINGS};
        for (int i = 0; i < tabs.length; i++) {
            boolean sel = mTab == ids[i];
            tabs[i].setTextColor(sel ? Design.accent() : Design.dim());
            tabs[i].setBackgroundColor(sel ? Design.surface() : Design.bg());
        }
    }

    private void applyInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    // =============================================================================================
    // Pairing + node loads
    // =============================================================================================

    private void onPaired(boolean zEnabled) {
        mPaired = zEnabled;
        if (mPairingBanner != null) mPairingBanner.setVisibility(zEnabled ? View.GONE : View.VISIBLE);
        updateStatusBanner();
        if (!zEnabled) return;

        if (!mTracked) {
            mTracked = true;
            // TRACK every derived RETURN SIGNEDBY(...) script so a regular node indexes coins at
            // ALL our base addresses as relevant (idempotent; forward-only). Tracking only affects
            // FUTURE blocks, so balances load immediately in parallel rather than waiting.
            trackScripts(0);
        }
        loadEverything();
        loadBlock();
    }

    /**
     * Chain {@code newscript trackall:true} over every so-far-derived base script, one IPC call at
     * a time (idempotent; errors skip to the next). {@link #onAddressBookReady} re-runs this once
     * all 64 are derived.
     */
    private void trackScripts(final int zIndex) {
        if (mNode == null || zIndex >= mBook.derivedCount()) { updateStatusBanner(); return; }
        mNode.trackScript(mWallet.getScript(zIndex), new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                mTrackOk = Math.max(mTrackOk, zIndex + 1);
                updateStatusBanner();
                trackScripts(zIndex + 1);
            }
            @Override public void onError(String message)   { trackScripts(zIndex + 1); }
        });
    }

    private void loadEverything() {
        mNode.tokens(new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { parseTokens(json); loadCoins(); }
            @Override public void onError(String message)   { loadCoins(); }
        });
    }

    private void loadCoins() {
        // One whole-list call (hand-off safe) covering ALL 64 base addresses; rebuildBalances
        // filters out coins that are not ours (the node's own wallet is also "relevant").
        mNode.coinsRelevant(new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                rebuildBalances(json);
                refreshCurrent();
            }
            @Override public void onError(String message) {
                if (!NodeApi.ERR_NOT_ENABLED.equals(message)) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void loadBlock() {
        mNode.block(new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) {
                    String b = r.optString("block", r.optString("blocknumber", ""));
                    if (!b.isEmpty()) {
                        boolean changed = !b.equals(mBlock) && !mBlock.isEmpty();
                        mBlock = b;
                        if (mBlockView != null) mBlockView.setText("#" + b);
                        if (changed) onBlockChanged();
                    }
                }
            }
            @Override public void onError(String message) { }
        });
    }

    private void parseTokens(JSONObject json) {
        mMeta.clear();
        JSONArray arr = json.optJSONArray("response");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject tok = arr.optJSONObject(i);
            if (tok == null) continue;
            String tokenid = tok.optString("tokenid", "");
            if (tokenid.isEmpty()) continue;
            mMeta.put(tokenid, TokenMeta.parse(tok, tokenid));
        }
    }

    /** Aggregate coins by tokenid AND retain the raw coins array for coin selection in Send. */
    private void rebuildBalances(JSONObject json) {
        mBalances.clear();

        org.minima.utils.json.JSONArray mcoins = new org.minima.utils.json.JSONArray();
        JSONArray arr = json.optJSONArray("response");
        if (arr != null) {
            org.minima.utils.json.parser.JSONParser parser = new org.minima.utils.json.parser.JSONParser();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) continue;
                // OURS only: the relevant set also holds the paired node's own wallet coins.
                if (mBook.keyIndexFor(c.optString("address", "")) == null) continue;
                try {
                    mcoins.add(parser.parse(c.toString()));
                } catch (Exception ignore) { }
            }
        }
        mLastCoins = mcoins;   // authoritative raw coins for Send/Split/Consolidate selection

        for (CoinAggregator.Agg agg : CoinAggregator.aggregate(mcoins)) {
            TokenBalance tb = new TokenBalance();
            tb.tokenid = agg.tokenid;
            TokenMeta meta = mMeta.get(agg.tokenid);
            if (meta == null) meta = TokenMeta.parse(null, agg.tokenid);
            tb.meta = meta;
            tb.name = meta.name;
            String amt = Util.tidyAmount(agg.displayTotal.toString());
            tb.confirmed   = amt;
            tb.sendable    = amt;
            tb.unconfirmed = "0";
            tb.total       = amt;
            tb.coins       = agg.count;
            mBalances.add(tb);
        }
    }

    private void refreshCurrent() {
        switch (mTab) {
            case TAB_SEND:     if (mSendView != null) mSendView.refresh(); break;
            case TAB_RECEIVE:  if (mReceiveView != null) mReceiveView.refresh(); break;
            case TAB_HISTORY:  if (mHistoryView != null) mHistoryView.refresh(); break;
            case TAB_NFTS:     if (mNftView != null) mNftView.refresh(); break;
            case TAB_SETTINGS: if (mSettingsView != null) mSettingsView.refresh(); break;
            default:           if (mBalancesView != null) mBalancesView.refresh();
        }
    }

    // =============================================================================================
    // Accessors used by the views
    // =============================================================================================

    public WalletCore wallet()   { return mWallet; }
    public SeedVault vault()     { return mVault; }
    public WalletSession session() { return mSession; }
    public TxnFactory factory()  { return mFactory; }
    public KeyUses keyUses()     { return mKeyUses; }
    public NodeLink node()       { return mNode; }
    public boolean isPaired()    { return mPaired; }
    public List<TokenBalance> balances() { return mBalances; }
    public org.minima.utils.json.JSONArray coins() { return mLastCoins; }
    public String defaultAddress() { return mAddrMx; }
    public String address0x()      { return mAddr0x; }
    public AddressBook addressBook() { return mBook; }

    /** The wallet key index owning this coin's 0x address, or null if the coin is not ours. */
    public Integer keyIndexForAddress(String zAddr0x) { return mBook.keyIndexFor(zAddr0x); }

    /** Callbacks for {@link #resolveInputs}. */
    public interface InputsCb {
        void onResolved(java.util.List<TxnFactory.InputCoin> zInputs);
        void onError(String zMessage);
    }

    /**
     * Turn selected coins (bundled coins-JSON objects) into InputCoins SAFELY: native-Minima
     * coins convert directly, but every CUSTOM-TOKEN coin is fetched byte-exact from the node via
     * {@code coinexport} — the coins-JSON round-trip is lossy for object token names, and a
     * reconstructed token whose id mismatches builds a transaction that consensus rejects
     * silently after txnpost. Sequential, async; nothing here signs.
     */
    public void resolveInputs(final java.util.List<org.minima.utils.json.JSONObject> zCoins,
                              final InputsCb zCb) {
        resolveNext(zCoins, 0, new java.util.ArrayList<>(), zCb);
    }

    private void resolveNext(final java.util.List<org.minima.utils.json.JSONObject> zCoins,
                             final int zIdx, final java.util.List<TxnFactory.InputCoin> zOut,
                             final InputsCb zCb) {
        if (zIdx >= zCoins.size()) { zCb.onResolved(zOut); return; }
        org.minima.utils.json.JSONObject coin = zCoins.get(zIdx);
        Object addrObj = coin.get("address");
        final Integer ki = keyIndexForAddress(addrObj == null ? "" : String.valueOf(addrObj));
        if (ki == null) {
            zCb.onError("Coin address is not in our wallet: " + addrObj);
            return;
        }
        String tokenid = String.valueOf(coin.get("tokenid"));
        if (Util.isMinima(tokenid)) {
            try {
                zOut.add(TxnFactory.fromCoinJson(coin, ki));
            } catch (Exception e) {
                zCb.onError("Coin parse failed: " + e.getMessage());
                return;
            }
            resolveNext(zCoins, zIdx + 1, zOut, zCb);
            return;
        }
        final String coinid = String.valueOf(coin.get("coinid"));
        mNode.coinExport(coinid, new NodeApi.Cb() {
            @Override public void onResult(org.json.JSONObject json) {
                try {
                    org.json.JSONObject r = json.optJSONObject("response");
                    String hex = r == null ? "" : r.optString("data", "");
                    if (hex.isEmpty()) throw new IllegalStateException("empty coinexport reply");
                    org.minima.objects.CoinProof cp = org.minima.objects.CoinProof
                            .convertMiniDataVersion(new org.minima.objects.base.MiniData(hex));
                    if (cp == null || cp.getCoin() == null
                            || !cp.getCoin().getCoinID().to0xString().equals(coinid)) {
                        throw new IllegalStateException("coinexport returned a different coin");
                    }
                    zOut.add(TxnFactory.fromExportedCoin(cp.getCoin(), ki));
                } catch (Exception e) {
                    zCb.onError("coinexport failed for " + coinid + ": " + e.getMessage());
                    return;
                }
                resolveNext(zCoins, zIdx + 1, zOut, zCb);
            }
            @Override public void onError(String message) {
                zCb.onError("coinexport failed for " + coinid + ": " + message);
            }
        });
    }
    public String blockLabel()     { return mBlock.isEmpty() ? "—" : "#" + mBlock; }
    public String circulatingSupply() { return ""; }

    /** Re-pull tokens + coins + balances from the node and refresh the active tab. */
    public void reload() {
        if (mPaired && mNode != null) loadEverything();
    }

    /**
     * Shared review → fail-safe gate → build+sign → keyuses snapshot resync → publish flow, with
     * the SAME ordering guarantees as SendView's confirmAndSend: the gate throws BEFORE any
     * counter change; the snapshot resync runs in a finally of its own so a resync failure can
     * never mask the build error or skip publish (the live counter is already durably advanced).
     * Building SIGNS — only call the builder after the user's explicit confirm.
     */
    public void confirmSignAndPublish(CharSequence zReview, int zKeyIndex,
                                      java.util.concurrent.Callable<TxnFactory.BuiltTxn> zBuilder) {
        confirmSignAndPublish(zReview, zKeyIndex, zBuilder, null);
    }

    /** As above; {@code zOnSent} runs once after a successful broadcast (before the data reload). */
    public void confirmSignAndPublish(CharSequence zReview, int zKeyIndex,
                                      java.util.concurrent.Callable<TxnFactory.BuiltTxn> zBuilder,
                                      Runnable zOnSent) {
        final java.util.function.BooleanSupplier current = sessionAuthorization();
        final int uses;
        try { uses = mKeyUses.currentUses(zKeyIndex); }
        catch (RuntimeException unavailable) { toast(Language.msg(l10n_wallet_counter_unavailable)); return; }
        CharSequence full = Language.join(zReview, "\n", Language.msg(l10n_wallet_key_usage, zKeyIndex, uses, Util.WOTS_MAX_USES), "\n\n", Language.msg(l10n_wallet_irreversible));

        new WalletDialog(this)
                .setTitle(Language.msg(l10n_wallet_confirm_transaction))
                .setMessage(full)
                .setPositiveButton(Language.msg(l10n_wallet_sign_broadcast), (d, w) -> {
                    if (!current.getAsBoolean()) { toast(Language.msg(l10n_wallet_review_again)); return; }
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
                                Toast.makeText(MainActivity.this, Language.msg(l10n_wallet_broadcast_failed, WalletMessages.message(message)), Toast.LENGTH_LONG).show();
                            }
                        });
                    }, error -> {
                        if (error instanceof SeedVault.SigningNotAllowedException)
                            Toast.makeText(this, WalletMessages.error(error), Toast.LENGTH_LONG).show();
                        else Toast.makeText(this, Language.msg(l10n_nft_failed, WalletMessages.error(error)), Toast.LENGTH_LONG).show();
                    });
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    // ---- ActivityResult bridges for the views ----

    public void scanQr(Consumer<String> onResult) {
        mScanCallback = onResult;
        ScanOptions opts = new ScanOptions();
        opts.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        opts.setPrompt(Language.text(this, l10n_nft_scan_address));
        opts.setBeepEnabled(false);
        opts.setOrientationLocked(false);
        mScanLauncher.launch(opts);
    }

    public void createBackupFile(String suggestedName, Consumer<Uri> onPicked) {
        mCreateDocCallback = onPicked;
        mCreateDocLauncher.launch(suggestedName);
    }

    public void openBackupFile(Consumer<Uri> onPicked) {
        mOpenDocCallback = onPicked;
        mOpenDocLauncher.launch(new String[]{"*/*"});
    }

    public void writeBytes(Uri uri, byte[] bytes) throws Exception {
        try (OutputStream os = getContentResolver().openOutputStream(uri, "w")) {
            if (os == null) throw new IllegalStateException("Cannot open file for writing");
            os.write(bytes);
            os.flush();
        }
    }

    public byte[] readBytes(Uri uri) throws Exception {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IllegalStateException("Cannot open file for reading");
            return VaultCrypto.readContainer(is);
        }
    }

    // =============================================================================================
    // Small onboarding view builders
    // =============================================================================================

    private TextView bigTitle(CharSequence s) {
        TextView t = new TextView(this);
        Language.setText(t, s);
        t.setTextColor(Design.accent());
        t.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        t.setTextSize(24f);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private TextView body(CharSequence s) {
        TextView t = new TextView(this);
        Language.setText(t, s);
        t.setTextColor(Design.dim());
        t.setTextSize(13f);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private TextView label(CharSequence s) {
        TextView t = new TextView(this);
        Language.setText(t, s);
        t.setTextColor(Design.dim());
        t.setTextSize(11f);
        t.setLetterSpacing(0.1f);
        t.setPadding(0, dp(16), 0, dp(4));
        return t;
    }

    private EditText password() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setTextColor(Design.text());
        e.setHintTextColor(Design.dim());
        e.setBackgroundColor(Design.surface());
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        e.setLayoutParams(lp);
        return e;
    }

    private Button primary(CharSequence label, View.OnClickListener l) {
        Button b = new Button(this);
        Language.setText(b, label);
        b.setAllCaps(false);
        b.setTextColor(Design.onAccent());
        b.setBackgroundColor(Design.accent());
        b.setTextSize(15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private Button secondary(CharSequence label, View.OnClickListener l) {
        Button b = primary(label, l);
        b.setTextColor(Design.accent());
        b.setBackgroundColor(Design.surface());
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.topMargin = dp(10);
        return b;
    }

    private void toast(CharSequence s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSession != null) mSession.work().detach(mWorkListener);
        mAfterWork = null; mWorkFailure = null;
        if (mWorkDialog != null) { mWorkDialog.dismiss(); mWorkDialog = null; }
        stopWalletConnection();
        mTickHandler.removeCallbacks(mBlockTick);
    }
}
