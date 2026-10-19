package com.eurobuddha.ethwallet;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import com.eurobuddha.ethwallet.integratedcomms.NodeApi;
import com.eurobuddha.ethwallet.integratedcomms.QrUtil;
import com.eurobuddha.ethwallet.eth.EthNet;
import com.eurobuddha.ethwallet.eth.EthRpc;
import com.eurobuddha.ethwallet.eth.EthTx;
import com.eurobuddha.ethwallet.eth.EthWallet;
import com.eurobuddha.ethwallet.eth.TokenStore;
import com.eurobuddha.ethwallet.eth.IconLoader;
import org.web3j.crypto.Keys;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minima ETH Wallet — a standalone ERC20 wallet sharing minimaSwap's address. The key is either
 * derived from the local Minima node (`seedrandom modifier:ethbridge`, the same address as minimaSwap)
 * or imported once and kept in a Keystore-backed encrypted store. Mainnet only; activity is viewed on
 * Etherscan via deep links (keyless).
 */
public class MainActivity extends AppCompatActivity implements NodeApi.PairingListener {

    private final java.util.Set<AlertDialog> languageDialogs = new java.util.HashSet<>();
    private LanguageDialog languageDialog() {
        return new LanguageDialog(this) {
            private android.content.DialogInterface.OnDismissListener dismissed;
            @Override public LanguageDialog setOnDismissListener(android.content.DialogInterface.OnDismissListener action) {
                dismissed = action;
                return this;
            }
            @Override public AlertDialog create() {
                AlertDialog value = super.create();
                languageDialogs.add(value);
                final android.content.DialogInterface.OnDismissListener callback = dismissed;
                value.setOnDismissListener(d -> {
                    languageDialogs.remove(value);
                    if (callback != null) callback.onDismiss(d);
                });
                return value;
            }
        };
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
    }

    private static final String PREFS = "ethwallet";
    /** Pref holding the address this node derived last time — see {@link #deriveNow}. */
    private static final String PIN = "derivedAddr";
    private static final String ETHERSCAN = "https://etherscan.io/";
    /** A well-formed Ethereum address. Checked before ANY value leaves this wallet — see confirmSend. */
    private static final java.util.regex.Pattern ADDR = java.util.regex.Pattern.compile("^0x[0-9a-fA-F]{40}$");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private android.content.SharedPreferences prefs;
    private EthNet net;
    private volatile EthRpc rpc;
    private volatile long sendGeneration;
    private final EthWallet wallet = new EthWallet();
    private TokenStore tokens;
    private KeyVault vault;
    private NodeApi node;

    private LinearLayout root, pairingBanner;
    private ScrollView scroller;
    private boolean modalOpen = false;
    private boolean paired = false;

    private String ethAddr, ethBal = "—";
    private CharSequence ethErr;
    private final Map<String, String> tokenBals = new LinkedHashMap<>();   // normalized contract address -> formatted balance
    private long lastUpdate = 0;

    private EditText pendingRecipient;          // filled by the QR scanner
    private ActivityResultLauncher<ScanOptions> scanLauncher;
    private ActivityResultLauncher<android.content.Intent> authLauncher;
    private Runnable pendingAuthAction;         // run once the device credential is confirmed

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        net = EthNet.MAINNET;
        rpc = new EthRpc(prefs.getString("rpc", net.defaultRpc));
        tokens = new TokenStore(prefs);
        // NB: the KeyVault is NOT built here. Opening the Keystore-backed store can fail (a restored
        // backup leaves a keyset the device can no longer decrypt), and a node-paired wallet never
        // touches it — building it eagerly turned that into a crash loop on every launch.

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG);
        pairingBanner = buildPairingBanner();
        pairingBanner.setVisibility(View.GONE);
        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        root.addView(pairingBanner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        applyInsets();

        scanLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() != null && pendingRecipient != null)
                Language.setText(pendingRecipient, cleanAddr(result.getContents()));
        });
        authLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            Runnable action = pendingAuthAction;
            pendingAuthAction = null;
            if (action == null) return;
            if (r.getResultCode() == RESULT_OK) action.run();
            else toast(Language.msg(l10n_eth_wallet_cancelled));
        });

        render();
        startWallet();
    }

    @Override protected void onResume() {
        super.onResume();
        // Re-check enablement after the user returns from Minima Core -> Apps. Without this the
        // pairing banner tells you to do something that then never takes effect until a restart.
        if (node != null && !wallet.ready()) retryNode();
    }

    @Override protected void onDestroy() {
        for (AlertDialog value : new java.util.ArrayList<>(languageDialogs)) value.dismiss();
        languageDialogs.clear();
        super.onDestroy();
        if (node != null) node.onDestroy();
        io.shutdownNow();
    }

    /** True once this Activity is gone — never touch views or show dialogs past this point. */
    private boolean gone() { return isFinishing() || isDestroyed(); }

    /** Post to the UI thread, dropping the work if the Activity died while the IO task ran. */
    private void onUi(Runnable r) { ui.post(() -> { if (!gone()) r.run(); }); }

    /** The secure store, opened on first use only. May be unavailable — always check {@link KeyVault#available()}. */
    private KeyVault vault() {
        if (vault == null) vault = new KeyVault(this);
        return vault;
    }

    /**
     * Confirm the device credential (PIN/pattern/password/biometric) before a sensitive action.
     * Devices with no secure lockscreen have nothing to check against, so the action runs directly —
     * the caller is still responsible for an explicit warning step.
     */
    private void authThen(CharSequence title, CharSequence detail, Runnable action) {
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        android.content.Intent i = (km == null || !km.isDeviceSecure())
                ? null : km.createConfirmDeviceCredentialIntent(title, detail);
        if (i == null) { action.run(); return; }
        pendingAuthAction = action;
        authLauncher.launch(i);
    }

    // ---- wallet source / startup ----

    private void startWallet() {
        String src = prefs.getString("source", null);
        if ("import".equals(src)) {
            KeyVault v = vault();
            String key = v.available() ? v.loadKey() : null;
            if (key != null) { wallet.importKey(key); ethAddr = wallet.address(); render(); refresh(); return; }
            // Preserve the selected source while storage is unavailable; a later launch can retry.
            ethErr = Language.msg(l10n_eth_wallet_storage_unavailable);
            render();
            return;
        }
        if ("node".equals(src)) { connectNode(); return; }
        sourcePicker();
    }

    /**
     * Ensure the IPC exists and that we end up with a key. The first call constructs {@link NodeApi},
     * whose REGISTER reply drives {@link #onEnabled}; later calls have no register to wait for, so they
     * must derive directly. Missing that second branch left "Switch wallet source -> Pair with node"
     * a silent no-op that stranded the app on Language.msg(l10n_eth_wallet_setting_up) until it was force-quit.
     */
    private void connectNode() {
        if (node == null) { node = new NodeApi(this, this); return; }
        if (paired && !wallet.ready()) deriveNow();
    }

    /** Ask the node for the key. Doubles as the pairing retry: a successful reply proves we're enabled,
     *  and NodeApi routes the not-enabled reply back through {@link #onEnabled}. */
    private void deriveNow() {
        if (node == null) return;
        // The address this node derived last time. Null on the very first derivation.
        final String pinned = prefs.getString(PIN, null);
        wallet.deriveFromNode(node, ui, pinned, new EthWallet.Cb() {
            @Override public void ok(String address) {
                if (gone()) return;
                paired = true;
                pairingBanner.setVisibility(View.GONE);
                prefs.edit().putString(PIN, address).apply();   // pin on first success, re-affirm after
                ethAddr = address; ethErr = null; render(); refresh();
            }
            @Override public void err(String msg) {
                if (gone()) return;
                ethErr = NodeApi.ERR_NOT_ENABLED.equals(msg)
                        ? Language.msg(l10n_explorer_node_retry)
                        : EthMessages.message(msg);
                render();
            }
            @Override public void addressChanged(String was, String now) {
                if (gone()) return;
                addressChangedDialog(was, now);
            }
        });
    }

    /**
     * The node derived a different address than last time. The key has NOT been adopted. Either the
     * seed genuinely changed (node reset/restored from a different phrase) — in which case the old
     * funds are stranded and the user needs to know — or something is impersonating the node.
     * Blocking and non-cancelable: silently continuing on a new address is the outcome to prevent.
     */
    private void addressChangedDialog(String was, String now) {
        modalOpen = true;
        LinearLayout box = colBox();
        TextView warn = new TextView(this);
        Language.setText(warn, Language.msg(l10n_eth_wallet_address_warning));
        warn.setTextColor(Design.RED); warn.setTextSize(14f); warn.setPadding(0, 0, 0, dp(10));
        box.addView(warn);
        box.addView(kvLine(Language.msg(l10n_eth_wallet_was), shortAddr(was)));
        box.addView(kvLine(Language.msg(l10n_eth_wallet_now), shortAddr(now)));
        TextView note = new TextView(this);
        Language.setText(note, Language.msg(l10n_eth_wallet_address_warning_body));
        note.setTextColor(Design.DIM); note.setTextSize(12.5f);
        box.addView(note);
        languageDialog().setTitle(Language.msg(l10n_eth_wallet_address_changed))
                .setView(wrapScroll(box))
                .setCancelable(false)
                .setNegativeButton(Language.msg(l10n_eth_wallet_cancel), (d, w) -> { modalOpen = false; ethErr = Language.msg(l10n_eth_wallet_stopped); render(); })
                .setPositiveButton(Language.msg(l10n_eth_wallet_adopt_address), (d, w) -> {
                    modalOpen = false;
                    prefs.edit().remove(PIN).apply();   // drop the pin, then re-derive unpinned and re-pin
                    deriveNow();
                })
                .show();
    }

    /** Re-attempt the node handshake (app resumed, or the pairing banner was tapped). */
    private void retryNode() {
        if (node == null) { connectNode(); return; }
        if (!wallet.ready()) deriveNow();
    }

    @Override public void onEnabled(boolean enabled) {
        if (gone()) return;
        paired = enabled;
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled && !wallet.ready()) deriveNow();
        render();
    }

    private void sourcePicker() {
        modalOpen = true;
        LinearLayout box = colBox();
        TextView t = new TextView(this);
        Language.setText(t, Language.msg(l10n_eth_wallet_source_body));
        t.setTextColor(Design.DIM); t.setTextSize(13f); t.setPadding(0, 0, 0, dp(12));
        box.addView(t);
        languageDialog()
                .setTitle(Language.msg(l10n_eth_wallet_setup))
                .setView(wrapScroll(box))
                .setCancelable(false)
                .setPositiveButton(Language.msg(l10n_eth_wallet_embedded), (d, w) -> { prefs.edit().putString("source", "node").apply(); modalOpen = false; retryNode(); render(); })
                .setNeutralButton(Language.msg(l10n_eth_wallet_import_key), (d, w) -> { modalOpen = false; importDialog(); })
                .show();
    }

    private void importDialog() {
        modalOpen = true;
        LinearLayout box = colBox();
        TextView t = new TextView(this);
        Language.setText(t, Language.msg(l10n_eth_wallet_import_body));
        t.setTextColor(Design.DIM); t.setTextSize(13f); t.setPadding(0, 0, 0, dp(10));
        box.addView(t);
        EditText in = new EditText(this);
        Language.hint(in, "0x…");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        box.addView(in);
        languageDialog()
                .setTitle(Language.msg(l10n_eth_wallet_import_private))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_eth_wallet_import), (d, w) -> {
                    modalOpen = false;
                    String k = in.getText().toString().trim();
                    // A private key is EXACTLY 32 bytes — Credentials.create accepts any hex length and would
                    // silently produce a different (wrong) address, so validate the length/charset first.
                    if (!k.replaceFirst("^0x", "").matches("[0-9a-fA-F]{64}")) { toast(Language.msg(l10n_eth_wallet_key_length)); importDialog(); return; }
                    try {
                        wallet.importKey(k);
                        // Only remember "import" as the source if the key actually reached the secure
                        // store — otherwise the next launch would look for a key that isn't there.
                        if (vault().saveKey(wallet.privateKeyHex())) {
                            prefs.edit().putString("source", "import").apply();
                            toast(Language.msg(l10n_eth_wallet_imported, shortAddr(wallet.address())));
                        } else {
                            toast(Language.msg(l10n_eth_wallet_session_only));
                        }
                        ethAddr = wallet.address(); ethErr = null;
                        render(); refresh();
                    } catch (Exception e) { toast(Language.msg(l10n_eth_wallet_invalid_key)); sourcePicker(); }
                })
                .setNegativeButton(Language.msg(l10n_eth_wallet_back), (d, w) -> { modalOpen = false; sourcePicker(); })
                .setOnCancelListener(d -> { modalOpen = false; sourcePicker(); })
                .show();
    }

    // ---- balances ----

    private void refresh() {
        if (!wallet.ready()) return;
        final SendContext context = new SendContext();
        final List<EthNet.Token> requestedTokens = tokens.tokens();
        io.execute(() -> {
            try {
                if (!context.current()) return;
                BigInteger wei = context.connection.getBalance(context.owner.getAddress());
                String eth = EthWallet.format(wei, 18, 6);
                Map<String, String> bals = new LinkedHashMap<>();
                for (EthNet.Token tk : requestedTokens) {
                    try { bals.put(tokenKey(tk), EthWallet.format(EthWallet.erc20BalanceRaw(context.connection, tk.address, context.owner.getAddress()), tk.decimals, 6)); }
                    catch (Exception e) { bals.put(tokenKey(tk), "—"); }
                }
                onUi(() -> { if (!context.current()) return; ethBal = eth; tokenBals.clear(); tokenBals.putAll(bals); ethErr = null; lastUpdate = System.currentTimeMillis(); render(); });
            } catch (Exception e) {
                onUi(() -> { if (!context.current()) return; ethErr = Language.join("RPC: ", EthMessages.error(e)); render(); });
            }
        });
    }

    // ---- render ----

    private void render() {
        if (modalOpen) return;
        LinearLayout col = colBox();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = new TextView(this);
        Language.setText(brand, "ETH Wallet"); brand.setTextColor(Design.TEXT); brand.setTextSize(22f);
        brand.setTypeface(brand.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(Design.pill(this, Language.msg(l10n_eth_wallet_mainnet), Design.ACCENT, Design.ON_ACCENT));
        col.addView(header);

        TextView sub = new TextView(this);
        Language.setText(sub, Language.join("v", BuildConfig.VERSION_NAME, "  ·  ", wallet.isImported() ? Language.msg(l10n_eth_wallet_imported_key) : Language.msg(l10n_eth_wallet_node_derived), lastUpdate > 0 ? Language.join("  ·  ", Language.msg(l10n_eth_wallet_updated, Language.since(lastUpdate))) : ""));
        sub.setTextColor(Design.DIM2); sub.setTextSize(12f); sub.setPadding(0, dp(2), 0, dp(12));
        col.addView(sub);

        if (!wallet.ready()) {
            TextView w = new TextView(this);
            Language.setText(w, ethErr != null ? Language.join("⚠ ", ethErr) : Language.msg(l10n_eth_wallet_setting_up));
            w.setTextColor(ethErr != null ? Design.RED : Design.DIM); w.setTextSize(13f);
            col.addView(w);
            scroller.removeAllViews(); scroller.addView(col);
            return;
        }

        // address card → receive
        LinearLayout addrCard = card();
        TextView at = new TextView(this); Language.setText(at, Language.msg(l10n_eth_wallet_your_address));
        at.setTextColor(Design.DIM); at.setTextSize(12.5f);
        TextView av = new TextView(this); Language.setText(av, shortAddr(ethAddr));
        av.setTextColor(Design.TEXT); av.setTextSize(16f); av.setPadding(0, dp(3), 0, 0);
        addrCard.addView(at); addrCard.addView(av);
        addrCard.setOnClickListener(v -> receiveDialog());
        col.addView(addrCard);

        // ETH balance — same big/accent card treatment as the tokens, with the ETH icon
        col.addView(assetRow("ETH", TW_ETH, "Ethereum", ethBal + " ETH", () -> sendDialog(null), null));

        if (ethErr != null) {
            TextView err = new TextView(this); Language.setText(err, Language.join("⚠ ", ethErr));
            err.setTextColor(Design.RED); err.setTextSize(12f); err.setPadding(dp(2), dp(6), 0, 0);
            col.addView(err);
        }

        // token cards — identical styling to the ETH card, each with its currency icon
        for (EthNet.Token tk : tokens.tokens()) {
            String bal = tokenBals.containsKey(tokenKey(tk)) ? tokenBals.get(tokenKey(tk)) : "…";
            final EthNet.Token ftk = tk;
            col.addView(assetRow(tk.symbol, tokenIconUrl(tk.address), shortAddr(tk.address), bal + " " + tk.symbol,
                    () -> sendDialog(ftk), () -> tokenMenu(ftk)));
        }

        // actions
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL); actions.setPadding(0, dp(12), 0, 0);
        addPill(actions, Language.msg(l10n_eth_wallet_send_action), () -> sendDialog(null));
        addPill(actions, Language.msg(l10n_eth_wallet_receive_action), this::receiveDialog);
        addPill(actions, Language.msg(l10n_eth_wallet_refresh_action), () -> { toast(Language.msg(l10n_eth_wallet_refreshing)); refresh(); });
        col.addView(actions);

        LinearLayout actions2 = new LinearLayout(this);
        actions2.setOrientation(LinearLayout.HORIZONTAL); actions2.setPadding(0, dp(8), 0, 0);
        addPill(actions2, Language.msg(l10n_eth_wallet_add_token_action), this::addTokenDialog);
        addPill(actions2, "↗  Etherscan", this::viewAddressOnEtherscan);
        addPill(actions2, Language.msg(l10n_eth_wallet_settings_action), this::settingsDialog);
        col.addView(actions2);

        scroller.removeAllViews();
        scroller.addView(col);
    }

    // ---- receive / export ----

    private void receiveDialog() {
        if (ethAddr == null) return;
        modalOpen = true;
        LinearLayout box = colBox();
        Bitmap qr = QrUtil.qr(ethAddr, dp(220));
        if (qr != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(qr);
            iv.setBackgroundColor(0xFFFFFFFF);
            int pad = dp(8); iv.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(236), dp(236));
            ip.gravity = Gravity.CENTER_HORIZONTAL; ip.bottomMargin = dp(12); iv.setLayoutParams(ip);
            box.addView(iv);
        }
        TextView a = new TextView(this);
        Language.setText(a, ethAddr); a.setTextSize(13f); a.setTextIsSelectable(true);
        a.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.addView(a);
        TextView note = new TextView(this);
        Language.setText(note, Language.msg(l10n_eth_wallet_receive_body));
        note.setTextColor(Design.DIM2); note.setTextSize(11.5f); note.setPadding(0, dp(8), 0, 0);
        box.addView(note);
        languageDialog().setTitle(Language.msg(l10n_eth_wallet_receive))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_eth_wallet_copy), (d, w) -> { copy(ethAddr); toast(Language.msg(l10n_eth_wallet_address_copied)); })
                .setNegativeButton(Language.msg(l10n_eth_wallet_close), null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    /**
     * Two-step export: an explicit blocking warning, THEN a device-credential confirmation, and only
     * then the key. Previously Settings -> Language.msg(l10n_eth_wallet_export_key) showed the key immediately, so two taps
     * on an unlocked phone exported a mainnet key (and the README claimed a warning that did not exist).
     */
    private void exportKeyFlow() {
        if (wallet.privateKeyHex() == null) { toast(Language.msg(l10n_eth_wallet_no_key)); return; }
        modalOpen = true;
        languageDialog()
                .setTitle(Language.msg(l10n_eth_wallet_export_key))
                .setMessage(Language.msg(l10n_eth_wallet_export_warning))
                .setPositiveButton(Language.msg(l10n_eth_wallet_show_key), (d, w) -> {
                    modalOpen = false;
                    authThen(Language.msg(l10n_eth_wallet_export_key), Language.msg(l10n_eth_wallet_confirm_identity), this::revealKeyDialog);
                })
                .setNegativeButton(Language.msg(l10n_eth_wallet_cancel), null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    private void revealKeyDialog() {
        String k = wallet.privateKeyHex();
        if (k == null || gone()) return;
        modalOpen = true;
        LinearLayout box = colBox();
        TextView warn = new TextView(this);
        Language.setText(warn, Language.msg(l10n_eth_wallet_private_warning));
        warn.setTextColor(Design.RED); warn.setTextSize(12.5f); warn.setPadding(0, 0, 0, dp(10));
        box.addView(warn);
        TextView key = new TextView(this);
        Language.setText(key, k); key.setTextSize(13f); key.setTextIsSelectable(true);
        key.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.addView(key);
        AlertDialog dlg = languageDialog().setTitle(Language.msg(l10n_eth_wallet_private_key))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_eth_wallet_copy), (d, w) -> { copy(k, true); toast(Language.msg(l10n_eth_wallet_key_copied)); })
                .setNegativeButton(Language.msg(l10n_eth_wallet_close), null)
                .setOnDismissListener(d -> modalOpen = false)
                .create();
        // Keep the key out of screenshots, screen recordings and the recents thumbnail.
        if (dlg.getWindow() != null) {
            dlg.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        dlg.show();
    }

    // ---- send ----

    /** Same owner/connection capture used by AtomiX's ethCounterLegGuard. */
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

    private void sendDialog(EthNet.Token startToken) {
        if (!wallet.ready()) return;
        sendGeneration++;
        final SendContext context = new SendContext();
        modalOpen = true;
        final List<EthNet.Token> assets = new ArrayList<>();
        assets.add(null); // Native ETH is distinct from every ERC20 symbol.
        assets.addAll(tokens.tokens());
        if (startToken != null && !assets.contains(startToken)) {
            modalOpen = false; toast(Language.msg(l10n_eth_wallet_missing_token, startToken.symbol)); return;
        }
        final EthNet.Token[] chosen = { startToken };

        LinearLayout box = colBox();
        final TextView tokenLine = new TextView(this);
        tokenLine.setTextColor(Design.ACCENT); tokenLine.setTextSize(15f);
        tokenLine.setPadding(0, 0, 0, dp(8));
        final Runnable setTok = () -> Language.setText(tokenLine, Language.msg(l10n_eth_wallet_token_balance, tokenLabel(chosen[0]), balOf(chosen[0])));
        setTok.run();
        tokenLine.setOnClickListener(v -> {
            String[] arr = new String[assets.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = tokenLabel(assets.get(i));
            languageDialog().setTitle(Language.msg(l10n_eth_wallet_which_token))
                    .setItems(arr, (d, i) -> { chosen[0] = assets.get(i); setTok.run(); }).show();
        });
        box.addView(tokenLine);

        final EditText to = new EditText(this);
        Language.hint(to, Language.msg(l10n_eth_wallet_recipient));
        to.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        box.addView(to);
        TextView scan = Design.pill(this, Language.msg(l10n_eth_wallet_scan), Design.SURFACE2, Design.TEXT);
        scan.setMinimumHeight(dp(48));
        scan.setOnClickListener(v -> { pendingRecipient = to; scanLauncher.launch(new ScanOptions().setBeepEnabled(false).setOrientationLocked(false).setPrompt(Language.msg(l10n_eth_wallet_scan_prompt).toString())); });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(8); scan.setLayoutParams(sp);
        box.addView(scan);

        final EditText amt = new EditText(this);
        Language.hint(amt, Language.msg(l10n_eth_wallet_amount));
        amt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(8); amt.setLayoutParams(ap);
        box.addView(amt);
        TextView max = Design.pill(this, Language.msg(l10n_eth_wallet_max), Design.SURFACE2, Design.DIM);
        max.setMinimumHeight(dp(48));
        max.setOnClickListener(v -> {
            if (!context.current()) { toast(Language.msg(l10n_wallet_review_again)); return; }
            if (chosen[0] != null) { Language.setText(amt, balOf(chosen[0])); return; }   // ERC20: gas is paid in ETH
            final String typedAmount = amt.getText().toString();
            toast(Language.msg(l10n_eth_wallet_reserving));                                                    // ETH: leave enough for the fee
            io.execute(() -> {
                try {
                    BigInteger wei = context.connection.getBalance(context.owner.getAddress());
                    BigInteger gp = context.connection.gasPrice(); if (gp.signum() <= 0) gp = BigInteger.valueOf(2_000_000_000L);
                    BigInteger fee = BigInteger.valueOf(21000).multiply(gp.multiply(BigInteger.valueOf(17)).divide(BigInteger.TEN)); // reserve for the High tier
                    BigInteger spend = wei.subtract(fee);
                    String out = spend.signum() > 0 ? EthWallet.format(spend, 18, 8) : "0";
                    onUi(() -> { if (context.current() && chosen[0] == null && typedAmount.equals(amt.getText().toString())) Language.setText(amt, out); });
                } catch (Exception e) { onUi(() -> { if (context.current() && chosen[0] == null && typedAmount.equals(amt.getText().toString())) Language.setText(amt, balOf(null)); }); }
            });
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(8); max.setLayoutParams(mp);
        box.addView(max);

        languageDialog().setTitle(Language.msg(l10n_eth_wallet_send))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_eth_wallet_review), (d, w) -> {
                    modalOpen = false;
                    if (!context.current()) { toast(Language.msg(l10n_wallet_review_again)); return; }
                    confirmSend(chosen[0], to.getText().toString().trim(), amt.getText().toString().trim());
                })
                .setNegativeButton(Language.msg(l10n_eth_wallet_cancel), null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    private void confirmSend(EthNet.Token tk, String to, String amount) {
        sendGeneration++;
        final SendContext context = new SendContext();
        if (!context.current()) { toast(Language.msg(l10n_wallet_review_again)); return; }
        final boolean isEth = tk == null;
        final String sym = isEth ? "ETH" : tk.symbol;
        if (!isEth && !tokens.has(tk.address)) { toast(Language.msg(l10n_eth_wallet_missing_token, sym)); return; }
        // MUST be a strict hex check, not just prefix+length. web3j's RLP encoder maps invalid hex
        // characters to -1 without throwing, so "0x" + 40 junk chars silently becomes a VALID signed
        // transaction to a mangled address — funds gone, unrecoverable. The ERC20 path happens to be
        // caught by new Address(), the native ETH path was not.
        if (!ADDR.matcher(to).matches()) { toast(Language.msg(l10n_eth_wallet_bad_recipient)); return; }
        // EIP-55: a mixed-case address carries a checksum, so a typo is detectable. All-lower and
        // all-upper are legacy un-checksummed forms and stay allowed.
        if (!to.equals(to.toLowerCase()) && !to.equals(to.toUpperCase())) {
            String sum;
            try { sum = Keys.toChecksumAddress(to); } catch (Exception e) { sum = null; }
            if (sum != null && !sum.equals(to)) { toast(Language.msg(l10n_eth_wallet_bad_checksum)); return; }
        }
        if (to.equalsIgnoreCase(context.owner.getAddress())) { toast(Language.msg(l10n_eth_wallet_own_address)); return; }
        final BigDecimal amtDec;
        // The decimal keypad emits the LOCALE's separator — a comma across most of Europe, which
        // BigDecimal rejects. Normalise before parsing so decimals are enterable everywhere.
        final String normalised = amount.replace(',', '.').trim();
        try { amtDec = new BigDecimal(normalised); if (amtDec.signum() <= 0) throw new Exception(); }
        catch (Exception e) { toast(Language.msg(l10n_eth_wallet_bad_amount)); return; }

        toast(Language.msg(l10n_eth_wallet_estimating));
        io.execute(() -> {
            try {
                EthTx.requireAuthorized(context::current);
                final int decimals = isEth ? 18 : tk.decimals;
                final BigInteger raw = amtDec.movePointRight(decimals).toBigInteger();
                if (raw.signum() <= 0) { onUi(() -> toast(Language.msg(l10n_eth_wallet_below_unit))); return; }
                final String data = isEth ? null : FunctionEncoder.encode(new Function("transfer",
                        java.util.Arrays.asList(new Address(to), new Uint256(raw)), Collections.emptyList()));
                final String txTo = isEth ? to : tk.address;
                final BigInteger value = isEth ? raw : BigInteger.ZERO;
                BigInteger gasLimit;
                try {
                    gasLimit = context.connection.estimateGas(context.owner.getAddress(), txTo, data, value).multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
                } catch (Exception ge) {
                    // FAIL CLOSED. Only a pure transport failure justifies guessing a gas limit; anything
                    // the node actually evaluated and rejected means the send would fail too, so aborting
                    // beats broadcasting a doomed, gas-burning transaction.
                    String m = ge.getMessage() == null ? "" : ge.getMessage().toLowerCase();
                    boolean rejected = m.contains("revert") || m.contains("insufficient") || m.contains("exceeds")
                            || m.contains("transfer amount") || m.contains("invalid") || m.contains("param")
                            || m.contains("gas required");
                    boolean transport = !rejected && (m.contains("timeout") || m.contains("timed out")
                            || m.contains("connect") || m.contains("resolve") || m.contains("non-json"));
                    if (!transport) { onUi(() -> toast(Language.msg(l10n_eth_wallet_refuse, EthMessages.error(ge)))); return; }
                    gasLimit = BigInteger.valueOf(isEth ? 21000 : 90000);
                }
                BigInteger gp = context.connection.gasPrice(); if (gp.signum() <= 0) gp = BigInteger.valueOf(2_000_000_000L);
                // Bail out here with a readable message rather than letting EthTx throw after the
                // user has already tapped Send. Checked against the top fee tier, since that's the
                // highest the user could select from the confirm screen.
                final BigInteger topGp = gp.multiply(BigInteger.valueOf(FEE_MULT[FEE_MULT.length - 1])).divide(BigInteger.valueOf(100));
                if (topGp.compareTo(EthTx.MAX_GAS_PRICE) > 0) {
                    final String g = EthTx.gwei(gp);
                    onUi(() -> toast(Language.msg(l10n_eth_wallet_high_price, rpcHost(), g))); return;
                }
                if (gasLimit.compareTo(EthTx.MAX_GAS_LIMIT) > 0) {
                    final BigInteger gl = gasLimit;
                    onUi(() -> toast(Language.msg(l10n_eth_wallet_high_gas, rpcHost(), gl))); return;
                }
                final BigInteger gasLimitF = gasLimit, baseGp = gp;
                onUi(() -> showConfirm(sym, to, normalised, isEth, txTo, data, value, gasLimitF, baseGp, context));
            } catch (Exception e) {
                onUi(() -> toast(Language.msg(l10n_eth_wallet_prepare_failed, EthMessages.error(e))));
            }
        });
    }

    // Fee tiers as a percentage of the network base gas price (gwei): slower → faster.
    private static final CharSequence[] FEE_TIERS = {Language.msg(l10n_eth_wallet_low), Language.msg(l10n_eth_wallet_medium), Language.msg(l10n_eth_wallet_high)};
    private static final int[] FEE_MULT = {100, 130, 170};

    private void showConfirm(String sym, String to, String amount, boolean isEth, String txTo, String data, BigInteger value, BigInteger gasLimit, BigInteger baseGp, SendContext context) {
        if (!context.current()) { toast(Language.msg(l10n_wallet_review_again)); return; }
        modalOpen = true;
        final int[] tier = {1};   // default Medium
        LinearLayout box = colBox();
        box.addView(kvLine(Language.msg(l10n_eth_wallet_your_address), ""));
        box.addView(fullAddrBlock(context.owner.getAddress()));
        box.addView(kvLine(Language.msg(l10n_eth_wallet_send), amount + " " + sym));
        if (!isEth) {
            box.addView(kvLine(Language.msg(l10n_eth_wallet_contract_hint), ""));
            box.addView(fullAddrBlock(txTo));
        }
        TextView toLabel = new TextView(this);
        Language.setText(toLabel, Language.msg(l10n_eth_wallet_to)); toLabel.setTextColor(Design.DIM); toLabel.setTextSize(12.5f);
        toLabel.setPadding(0, dp(6), 0, 0);
        box.addView(toLabel);
        box.addView(fullAddrBlock(to));

        TextView feeLabel = new TextView(this);
        Language.setText(feeLabel, Language.msg(l10n_eth_wallet_network_fee)); feeLabel.setTextColor(Design.DIM); feeLabel.setTextSize(12.5f);
        feeLabel.setPadding(0, dp(8), 0, dp(4));
        box.addView(feeLabel);

        LinearLayout tierRow = new LinearLayout(this);
        tierRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] pills = new TextView[FEE_TIERS.length];
        final TextView feeVal = new TextView(this);
        final TextView feeWarn = new TextView(this);
        Runnable paint = () -> {
            for (int i = 0; i < pills.length; i++) {
                boolean on = i == tier[0];
                pills[i].setBackground(Design.roundBg(this, on ? Design.ACCENT : Design.SURFACE2, 14));
                pills[i].setTextColor(on ? Design.ON_ACCENT : Design.DIM);
            }
            BigInteger gp = baseGp.multiply(BigInteger.valueOf(FEE_MULT[tier[0]])).divide(BigInteger.valueOf(100));
            BigInteger feeWei = gasLimit.multiply(gp);
            Language.setText(feeVal, "~" + EthWallet.format(feeWei, 18, 8) + " ETH  ·  " + EthWallet.format(gp, 9, 2) + " gwei");

            // Neither number is ours — both came from an RPC. Flag anything abnormal at the moment
            // of commitment, recomputed per tier so switching Low/Medium/High updates the warning.
            CharSequence w = null;
            if (gp.compareTo(EthTx.WARN_GAS_PRICE) > 0 || gasLimit.compareTo(EthTx.WARN_GAS_LIMIT) > 0) {
                w = Language.msg(l10n_eth_wallet_fee_warning);
            } else if (isEth && value.signum() > 0 && feeWei.multiply(BigInteger.valueOf(4)).compareTo(value) > 0) {
                // ETH only: for an ERC20 the fee is ETH and the amount is tokens, so the ratio is meaningless.
                long pct = feeWei.multiply(BigInteger.valueOf(100)).divide(value).longValue();
                w = Language.msg(l10n_eth_wallet_fee_percent, pct);
            }
            Language.setText(feeWarn, w == null ? "" : w);
            feeWarn.setVisibility(w == null ? View.GONE : View.VISIBLE);
        };
        for (int i = 0; i < FEE_TIERS.length; i++) {
            final int idx = i;
            TextView p = Design.pill(this, FEE_TIERS[i], Design.SURFACE2, Design.DIM);
            p.setMinimumHeight(dp(48));
            p.setOnClickListener(v -> { tier[0] = idx; paint.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = i < FEE_TIERS.length - 1 ? dp(6) : 0;
            p.setGravity(Gravity.CENTER); p.setLayoutParams(lp);
            pills[i] = p; tierRow.addView(p);
        }
        box.addView(tierRow);
        feeVal.setTextSize(13f); feeVal.setPadding(0, dp(6), 0, 0);
        box.addView(feeVal);
        feeWarn.setTextColor(Design.RED); feeWarn.setTextSize(12.5f); feeWarn.setPadding(0, dp(6), 0, 0);
        box.addView(feeWarn);
        paint.run();

        languageDialog().setTitle(Language.msg(l10n_eth_wallet_confirm_send))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_eth_wallet_send), (d, w) -> {
                    modalOpen = false;
                    if (!context.current()) { toast(Language.msg(l10n_wallet_review_again)); return; }
                    final BigInteger gasPrice = baseGp.multiply(BigInteger.valueOf(FEE_MULT[tier[0]])).divide(BigInteger.valueOf(100));
                    toast(Language.msg(l10n_eth_wallet_broadcasting));
                    io.execute(() -> {
                        try {
                            String tx = EthTx.send(context.connection, context.owner, context.network.chainId, txTo, data, isEth ? value : BigInteger.ZERO, gasLimit, gasPrice, context::current);
                            // Persist BEFORE showing it: the tx is already on the network, and losing the
                            // hash to a rotation or a crash would leave the user with no way to find it.
                            prefs.edit().putString("lastTx", tx).apply();
                            onUi(() -> { sentDialog(tx); refresh(); });
                        } catch (Exception e) {
                            if (e instanceof com.eurobuddha.minimaapi.ethereum.EthereumTransactions.Uncertain) {
                                String hash = ((com.eurobuddha.minimaapi.ethereum.EthereumTransactions.Uncertain)e).hash;
                                prefs.edit().putString("lastTx", hash).apply();
                                onUi(() -> toast(Language.join(Language.msg(l10n_nft_transaction_unknown), "\n", hash)));
                            } else onUi(() -> toast(Language.msg(l10n_eth_wallet_send_failed, EthMessages.error(e))));
                        }
                    });
                })
                .setNegativeButton(Language.msg(l10n_eth_wallet_cancel), null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    private void sentDialog(String tx) {
        modalOpen = true;
        LinearLayout box = colBox();
        TextView t = new TextView(this);
        Language.setText(t, Language.msg(l10n_eth_wallet_broadcast, tx)); t.setTextSize(12.5f);
        t.setTypeface(android.graphics.Typeface.MONOSPACE); t.setTextIsSelectable(true);
        box.addView(t);
        languageDialog().setTitle(Language.msg(l10n_eth_wallet_sent))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_eth_wallet_etherscan), (d, w) -> openUrl(net.explorerTx + tx))
                .setNegativeButton(Language.msg(l10n_eth_wallet_close), null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    // ---- tokens ----

    private void addTokenDialog() {
        modalOpen = true;
        LinearLayout box = colBox();
        TextView t = new TextView(this);
        Language.setText(t, Language.msg(l10n_eth_wallet_add_token_body));
        t.setTextColor(Design.DIM); t.setTextSize(13f); t.setPadding(0, 0, 0, dp(10));
        box.addView(t);
        EditText in = new EditText(this);
        Language.hint(in, Language.msg(l10n_eth_wallet_contract_hint));
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        box.addView(in);
        languageDialog().setTitle(Language.msg(l10n_eth_wallet_add_token))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_eth_wallet_add), (d, w) -> {
                    modalOpen = false;
                    String addr = in.getText().toString().trim();
                    if (!ADDR.matcher(addr).matches()) { toast(Language.msg(l10n_eth_wallet_bad_contract)); return; }
                    if (tokens.has(addr)) { toast(Language.msg(l10n_eth_wallet_already_added)); return; }
                    toast(Language.msg(l10n_eth_wallet_reading_token));
                    io.execute(() -> {
                        try {
                            EthNet.Token tk = TokenStore.fetch(rpc, addr);
                            onUi(() -> { tokens.add(tk); toast(Language.msg(l10n_eth_wallet_added, tk.symbol)); render(); refresh(); });
                        } catch (Exception e) { onUi(() -> toast(Language.msg(l10n_eth_wallet_not_erc20, EthMessages.error(e)))); }
                    });
                })
                .setNegativeButton(Language.msg(l10n_eth_wallet_cancel), null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    private void tokenMenu(EthNet.Token tk) {
        modalOpen = true;
        languageDialog().setTitle(tokenLabel(tk))
                .setItems(new CharSequence[]{Language.msg(l10n_eth_wallet_send_token, tk.symbol), Language.msg(l10n_eth_wallet_etherscan), Language.msg(l10n_eth_wallet_remove_token)}, (d, i) -> {
                    modalOpen = false;
                    if (i == 0) sendDialog(tk);
                    else if (i == 1) openUrl(ETHERSCAN + "token/" + tk.address + (ethAddr == null ? "" : "?a=" + ethAddr));
                    else { tokens.remove(tk.address); tokenBals.remove(tokenKey(tk)); render(); }
                })
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    // ---- settings ----

    private void settingsDialog() {
        modalOpen = true;
        languageDialog().setTitle(Language.msg(l10n_eth_wallet_settings))
                .setItems(new CharSequence[]{Language.msg(l10n_eth_wallet_export_key), Language.msg(l10n_eth_wallet_rpc_endpoint), Language.msg(l10n_eth_wallet_add_token), Language.msg(l10n_eth_wallet_view_address), Language.msg(l10n_eth_wallet_last_tx), Language.msg(l10n_eth_wallet_switch_source)}, (d, i) -> {
                    modalOpen = false;
                    switch (i) {
                        case 0: exportKeyFlow(); break;
                        case 1: rpcDialog(); break;
                        case 2: addTokenDialog(); break;
                        case 3: viewAddressOnEtherscan(); break;
                        case 4: viewLastTx(); break;
                        case 5: switchSourceDialog(); break;
                    }
                })
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    private void viewAddressOnEtherscan() {
        if (ethAddr == null) { toast(Language.msg(l10n_eth_wallet_no_address)); return; }
        openUrl(ETHERSCAN + "address/" + ethAddr);
    }

    /** The last broadcast hash, stored the moment the send returned so a crash or rotation
     *  during {@link #sentDialog} can't lose the only reference to a live transaction. */
    private void viewLastTx() {
        String tx = prefs.getString("lastTx", null);
        if (tx == null) { toast(Language.msg(l10n_eth_wallet_no_tx)); return; }
        openUrl(net.explorerTx + tx);
    }

    private void rpcDialog() {
        modalOpen = true;
        LinearLayout box = colBox();
        EditText in = new EditText(this);
        Language.setText(in, rpc.url());
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(in);
        TextView note = new TextView(this);
        Language.setText(note, Language.msg(l10n_eth_wallet_rpc_fallback));
        note.setTextColor(Design.DIM2); note.setTextSize(11.5f); note.setPadding(0, dp(8), 0, 0);
        box.addView(note);
        languageDialog().setTitle(Language.msg(l10n_eth_wallet_rpc_endpoint))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_eth_wallet_save), (d, w) -> { modalOpen = false; String u = in.getText().toString().trim(); if (!u.isEmpty()) { prefs.edit().putString("rpc", u).apply(); rpc = new EthRpc(u); refresh(); } })
                .setNeutralButton(Language.msg(l10n_eth_wallet_default), (d, w) -> { modalOpen = false; prefs.edit().remove("rpc").apply(); rpc = new EthRpc(net.defaultRpc); refresh(); })
                .setNegativeButton(Language.msg(l10n_eth_wallet_cancel), null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    private void switchSourceDialog() {
        modalOpen = true;
        languageDialog().setTitle(Language.msg(l10n_eth_wallet_switch_source))
                .setMessage(Language.msg(l10n_eth_wallet_switch_body))
                .setPositiveButton(Language.msg(l10n_eth_wallet_embedded), (d, w) -> {
                    modalOpen = false;
                    prefs.edit().putString("source", "node").apply();
                    wallet.clear(); ethAddr = null; ethErr = null;
                    retryNode();   // NOT connectNode(): with the IPC already up there is no register to wait for
                    render();
                })
                .setNeutralButton(Language.msg(l10n_eth_wallet_import_key), (d, w) -> { modalOpen = false; wallet.clear(); ethAddr = null; importDialog(); })
                .setNegativeButton(Language.msg(l10n_eth_wallet_cancel), null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    // ---- helpers ----

    private static String tokenKey(EthNet.Token token) { return token.address.toLowerCase(java.util.Locale.ROOT); }
    private static String tokenLabel(EthNet.Token token) { return token == null ? "ETH · Ethereum" : token.symbol + " · " + token.address; }
    private String balOf(EthNet.Token token) { return token == null ? ethBal : tokenBals.getOrDefault(tokenKey(token), "0"); }

    private LinearLayout colBox() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(24));
        return c;
    }
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Design.roundBg(this, Design.SURFACE, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10); c.setLayoutParams(lp);
        return c;
    }

    // ---- asset rows: ETH + every token, identical big/accent styling, with a currency icon ----

    private static final String TW_ETH   = "https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/ethereum/info/logo.png";
    private static final String TW_TOKEN = "https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/ethereum/assets/";

    /** Trust Wallet CDN logo URL for an ERC20 by EIP-55 checksummed address (null if it can't be formed). */
    private String tokenIconUrl(String address) {
        try { return TW_TOKEN + Keys.toChecksumAddress(address) + "/logo.png"; }
        catch (Exception e) { return null; }
    }

    /** A balance card: [icon] title(dim) + big accent value. Used for both ETH and every token. */
    private LinearLayout assetRow(String symForDisc, String iconUrl, String title, String value,
                                  Runnable tap, Runnable longPress) {
        LinearLayout c = card();
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        int sz = dp(32);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(sz, sz);
        ilp.rightMargin = dp(12);
        icon.setLayoutParams(ilp);
        IconLoader.into(this, icon, iconUrl, discBitmap(symForDisc));   // disc now, real logo when it loads
        c.addView(icon);

        LinearLayout colc = new LinearLayout(this);
        colc.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this); Language.setText(t, title); t.setTextColor(Design.DIM); t.setTextSize(12.5f);
        TextView v = new TextView(this); Language.setText(v, value); v.setTextColor(Design.ACCENT); v.setTextSize(20f);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); v.setPadding(0, dp(2), 0, 0);
        colc.addView(t); colc.addView(v);
        c.addView(colc, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (tap != null) c.setOnClickListener(x -> tap.run());
        if (longPress != null) c.setOnLongClickListener(x -> { longPress.run(); return true; });
        return c;
    }

    /** Deterministic lettered disc shown until (or if) a real icon loads. */
    private Bitmap discBitmap(String sym) {
        int sz = dp(32);
        Bitmap bm = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bm);
        String s = (sym == null || sym.isEmpty()) ? "?" : sym;
        int hue = Math.abs(s.hashCode()) % 360;
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(android.graphics.Color.HSVToColor(new float[]{hue, 0.45f, 0.65f}));
        cv.drawCircle(sz / 2f, sz / 2f, sz / 2f, p);
        String t = s.length() >= 2 ? s.substring(0, 2) : s;
        p.setColor(0xFFFFFFFF);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.setTextSize(sz * 0.40f);
        android.graphics.Rect rb = new android.graphics.Rect();
        p.getTextBounds(t, 0, t.length(), rb);
        cv.drawText(t, sz / 2f, sz / 2f - rb.exactCenterY(), p);
        return bm;
    }
    private void addPill(LinearLayout row, CharSequence label, Runnable onClick) {
        TextView p = Design.pill(this, label, Design.SURFACE2, Design.TEXT);
        p.setMinimumHeight(dp(48));
        p.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(8); p.setLayoutParams(lp);
        row.addView(p);
    }
    /**
     * The recipient address, in full, monospace, never truncated.
     *
     * Address-poisoning attacks mint a vanity address matching the first and last few characters of
     * one you have already used, so a truncated "0x123456…abcdef" renders the attacker's address and
     * the real one IDENTICAL. The EIP-55 check in confirmSend does not help — a poisoned address is
     * validly checksummed. The confirm screen is the one place the user commits to a destination, so
     * it shows all 42 characters; the ends stay accented to preserve the quick visual check, but the
     * middle — the only part that differs — is now actually on screen.
     */
    private TextView fullAddrBlock(String addr) {
        TextView t = new TextView(this);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(14f);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(0, dp(2), 0, dp(6));
        if (addr == null) { Language.setText(t, "—"); return t; }
        android.text.SpannableString s = new android.text.SpannableString(addr);
        int head = Math.min(8, addr.length());              // "0x" + 6 hex
        int tail = Math.max(head, addr.length() - 6);
        s.setSpan(new android.text.style.ForegroundColorSpan(Design.ACCENT), 0, head, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new android.text.style.ForegroundColorSpan(Design.ACCENT), tail, addr.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Language.setText(t, s);
        return t;
    }

    private TextView kvLine(CharSequence k, CharSequence v) {
        TextView t = new TextView(this);
        Language.setText(t, Language.join(k, ":  ", v)); t.setTextSize(14f); t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private ScrollView wrapScroll(View v) { ScrollView s = new ScrollView(this); s.addView(v); return s; }

    private LinearLayout buildPairingBanner() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackgroundColor(0xFF3A2A00);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        TextView t = new TextView(this);
        Language.setText(t, Language.msg(l10n_explorer_node_retry));
        t.setTextColor(Design.ACCENT); t.setTextSize(12.5f);
        b.addView(t);
        b.setOnClickListener(v -> { toast(Language.msg(l10n_eth_wallet_retrying)); retryNode(); });
        return b;
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(0, sb.top, 0, sb.bottom);
            return insets;
        });
    }

    private int dp(int v) { return Design.dp(this, v); }
    private void toast(CharSequence s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void copy(String s) { copy(s, false); }

    /** Copy to the clipboard. Sensitive values are flagged so Android 13+ hides them from the paste
     *  preview, and are wiped after a minute so a private key isn't left sitting there. */
    private void copy(String s, boolean sensitive) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        android.content.ClipData clip = android.content.ClipData.newPlainText(sensitive ? Language.text(this, l10n_eth_wallet_private_key) : "eth", s);
        if (sensitive && android.os.Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        if (sensitive) {
            final android.content.Context app = getApplicationContext();   // outlives this Activity
            ui.postDelayed(() -> clearClipboardIfStill(app, s), 60_000L);
        }
    }

    /** Best-effort: Android 10+ only permits clipboard access while focused, so this can no-op. */
    private static void clearClipboardIfStill(android.content.Context ctx, String expected) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) ctx.getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) return;
            android.content.ClipData cur = cm.getPrimaryClip();
            if (cur == null || cur.getItemCount() == 0) return;
            CharSequence t = cur.getItemAt(0).getText();
            if (t != null && expected.contentEquals(t)) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""));
            }
        } catch (Throwable ignore) {}
    }
    private void openUrl(String url) {
        try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (Exception e) { toast(Language.msg(l10n_eth_wallet_no_browser)); }
    }
    /** Host of the RPC that actually answered — named in fee warnings so the user knows who to distrust. */
    private CharSequence rpcHost() {
        try { return new java.net.URL(rpc.url()).getHost(); } catch (Exception e) { return Language.msg(l10n_eth_wallet_rpc); }
    }

    private static String shortAddr(String a) {
        if (a == null) return "—";
        return a.length() > 14 ? a.substring(0, 8) + "…" + a.substring(a.length() - 6) : a;
    }
    private static String cleanAddr(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("ethereum:")) s = s.substring("ethereum:".length());
        int at = s.indexOf('@'); if (at > 0) s = s.substring(0, at);   // EIP-681 chain suffix
        int q = s.indexOf('?'); if (q > 0) s = s.substring(0, q);
        return s.trim();
    }

}
