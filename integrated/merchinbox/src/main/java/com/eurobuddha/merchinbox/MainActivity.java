package com.eurobuddha.merchinbox;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import com.eurobuddha.minimaapi.direct.DirectNodeEvents;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.goterl.lazysodium.LazySodium;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.merchinbox.integratedcomms.CommsIdentity;
import com.eurobuddha.merchinbox.integratedcomms.CommsScanner;
import com.eurobuddha.merchinbox.integratedcomms.CommsTransport;
import com.eurobuddha.merchinbox.integratedcomms.CryptoProvider;
import com.eurobuddha.merchinbox.integratedcomms.Hex;
import com.eurobuddha.merchinbox.integratedcomms.LocalEcCryptoProvider;
import com.eurobuddha.merchinbox.integratedcomms.MailText;
import com.eurobuddha.merchinbox.integratedcomms.MerchDb;
import com.eurobuddha.merchinbox.integratedcomms.MerchMessage;
import com.eurobuddha.merchinbox.integratedcomms.NodeApi;
import com.eurobuddha.merchinbox.integratedcomms.QrUtil;
import com.eurobuddha.merchinbox.integratedcomms.Sodium;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** miniMall Inbox — the vendor's native order manager (Maxima-free, on the comms primitive). */
public class MainActivity extends AppCompatActivity {

    private final java.util.Set<AlertDialog> languageDialogs = new java.util.HashSet<>();
    private LanguageDialog languageDialog() {
        return new LanguageDialog(this) {
            @Override public AlertDialog create() {
                AlertDialog dialog = super.create();
                languageDialogs.add(dialog);
                dialog.getWindow().getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) { languageDialogs.add(dialog); }
                    @Override public void onViewDetachedFromWindow(View v) { languageDialogs.remove(dialog); }
                });
                return dialog;
            }
        };
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        ensureChannel();
    }

    private static final String CH = "merchinbox";

    private LazySodium ls;
    private NodeApi node;
    private MerchDb db;
    private CryptoProvider crypto;
    private CommsIdentity identity;
    private CommsScanner orderScanner, paymentScanner;
    private String myId, vendorAddr = "", shopName = "";
    private boolean paired = false;
    private int chainBlock = 0;
    private String filterStatus = "ALL";
    private String filterShop = "ALL";
    private ActivityResultLauncher<String> csvLauncher;
    private ActivityResultLauncher<String[]> listingLauncher;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<View> stack = new ArrayDeque<>();
    /** NFT sends in flight, keyed ref:tokenid — blocks double-taps while the node builds the txn. */
    private final java.util.HashSet<String> nftSending = new java.util.HashSet<>();
    private DeliveryJournal deliveryJournal;
    private PlainDeliveryJournal plainDeliveryJournal;
    private synchronized PlainDeliveryJournal plainDeliveryJournal() {
        if (plainDeliveryJournal == null) plainDeliveryJournal = new PlainDeliveryJournal(db, deliveryJournal());
        return plainDeliveryJournal;
    }
    private final ReplyDrafts replyDrafts = new ReplyDrafts();
    private synchronized DeliveryJournal deliveryJournal() {
        if (deliveryJournal == null) deliveryJournal = new DeliveryJournal(db);
        return deliveryJournal;
    }
    private String deliveredTx(MerchDb.Order order, String[] line) {
        String key = lineKey(order.ref, line);
        if ("0".equals(line[3]) && "0".equals(line[4])) {
            try {
                JSONObject record = plainDeliveryJournal().load(key, order, line);
                return PlainDeliveryJournal.complete(record) ? record.optJSONObject("receipt").optString("txpowid") : "";
            } catch (RuntimeException unavailable) { return ""; }
        }
        try {
            JSONObject record = deliveryJournal().load(key, order, line);
            return DeliveryJournal.complete(record) ? record.optJSONArray("entries").optJSONObject(0).optString("txpowid") : "";
        } catch (RuntimeException unavailable) { return ""; }
    }

    /** NFT lines auto-attempted this process, keyed ref:tokenid — a failed auto-send is never looped;
     *  the next app open (autoDeliverSweep) gets exactly one more automatic attempt. */
    private final java.util.HashSet<String> autoTried = new java.util.HashSet<>();
    /** Orders whose payment flipped to PAID during the current scan pass (main thread only). */
    private final java.util.HashSet<String> pendingAutoDeliver = new java.util.HashSet<>();
    private boolean autoSweepDone = false;

    private LinearLayout root;
    private FrameLayout container;
    private View pairingBanner;
    private DirectNodeEvents.Listener notifyReceiver;
    private int insetTop = 0, insetBottom = 0;

    // ---- lifecycle ----

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        replyDrafts.restore(b);
        ls = Sodium.get();
        db = new MerchDb(this);
        shopName = loadShopName();

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG);
        pairingBanner = buildPairingBanner();
        pairingBanner.setVisibility(View.GONE);
        container = new FrameLayout(this);
        root.addView(pairingBanner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(container, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        applyInsets();
        ensureChannel();
        requestNotifPermission();

        csvLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"),
                uri -> { if (uri != null) writeCsv(uri); });
        listingLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) importListing(uri); });

        showOrders();
        if (b != null) {
            String orderRef = b.getString("mall.openOrder", "");
            if (!orderRef.isEmpty() && db.order(orderRef) != null) push(buildOrderDetail(orderRef));
        }

        node = new NodeApi(this, this::onPaired);
        notifyReceiver = new DirectNodeEvents.Listener() {
            @Override public void onEvent(JSONObject nodeEvent) {
                try {
                    String event = nodeEvent.optString("event", "");
                    if ("NEWBLOCK".equals(event) || "NEWBALANCE".equals(event)) { fetchBlock(); requestScan(); }
                } catch (Exception ignored) {}
            }
        };
        // Subscribed only while this screen is visible.
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        replyDrafts.save(state);
        View top = stack.peek(); Object tag = top == null ? null : top.getTag();
        if (tag instanceof String && ((String) tag).startsWith("order:")) state.putString("mall.openOrder", ((String) tag).substring(6));
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        replyDrafts.close();
        for (AlertDialog dialog : new java.util.ArrayList<>(languageDialogs)) dialog.dismiss();
        languageDialogs.clear();
        super.onDestroy();
        if (notifyReceiver != null) try { DirectNodeEvents.unsubscribe(notifyReceiver); } catch (Exception ignored) {}
        if (node != null) node.onDestroy();
        if (deliveryJournal != null) deliveryJournal.close();
        if (plainDeliveryJournal != null) plainDeliveryJournal.close();
        io.shutdownNow();
    }

    @Override public void onBackPressed() { if (stack.size() > 1) pop(); else super.onBackPressed(); }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            insetTop = bars.top; insetBottom = Math.max(bars.bottom, ime.bottom);
            root.setPadding(0, insetTop, 0, insetBottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);
    }

    // ---- navigation ----

    private void push(View v) { stack.push(v); showTop(); }
    private void pop() { if (stack.size() > 1) { stack.pop(); showTop(); } }
    private void showOrders() { stack.clear(); stack.push(buildOrders()); showTop(); }
    private void showTop() { container.removeAllViews(); container.addView(stack.peek()); }
    private void refreshTop(View rebuilt) { stack.pop(); stack.push(rebuilt); showTop(); }

    // ---- pairing + identity ----

    private void onPaired(boolean enabled) {
        paired = enabled;
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) {
            if (chainBlock == 0) fetchBlock();
            fetchVendorAddr();
            if (crypto == null) setupIdentity(); else requestScan();
        }
    }

    private void fetchVendorAddr() {
        String saved = db.getMeta("vendoraddr", "");   // lock to the first address (the one in the Vendor Card)
        if (!saved.isEmpty()) {
            vendorAddr = saved;
            if (paymentScanner == null && crypto != null) startPaymentScanner();
            return;
        }
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) {
                    String a = r.optString("miniaddress", r.optString("address", ""));
                    if (!a.isEmpty()) { vendorAddr = a; db.setMeta("vendoraddr", a);
                        if (paymentScanner == null && crypto != null) startPaymentScanner(); }
                }
            }
            @Override public void onError(String m) {}
        });
    }

    private void fetchBlock() {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) try { chainBlock = Integer.parseInt(r.optString("block", "0")); } catch (Exception ignored) {}
            }
            @Override public void onError(String m) {}
        });
    }

    private void setupIdentity() {
        node.cmd("vault action:seed", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                String ikm = r == null ? "" : r.optString("seed", r.optString("phrase", ""));
                if (ikm.isEmpty()) askForSeed(); else deriveIdentity(ikm);
            }
            @Override public void onError(String m) {
                if (NodeApi.ERR_NOT_ENABLED.equals(m)) { pairingBanner.setVisibility(View.VISIBLE); return; }
                askForSeed();
            }
        });
    }

    private void deriveIdentity(final String ikm) {
        io.execute(() -> {
            try {
                byte[] seed = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(StandardCharsets.UTF_8);
                CommsIdentity id = CommsIdentity.fromSeed(ls, seed);
                ui.post(() -> { adoptIdentity(id); refreshVisibleOrderOrList(); requestScan(); });
            } catch (Exception e) { ui.post(() -> toast(Language.msg(l10n_mall_shop_identity_error, InboxMessages.error(e)))); }
        });
    }

    private void adoptIdentity(CommsIdentity id) {
        identity = id;
        crypto = new LocalEcCryptoProvider(ls, id);
        myId = id.publicId();
        orderScanner = new CommsScanner(node, crypto, db, CommsTransport.MINIMERCH_ADDRESS,
                this::routeIncoming, (ok, n) -> onScanDone(n));
        if (!vendorAddr.isEmpty()) startPaymentScanner();
    }

    private void startPaymentScanner() {
        paymentScanner = new CommsScanner(node, crypto, db, vendorAddr,
                this::routePayment, (ok, n) -> {
                    onScanDone(n);
                    // After the first clean pass, auto-deliver anything paid-but-unsent (e.g. an
                    // auto-send that failed last session, or a payment that landed while closed).
                    if (ok && !autoSweepDone) { autoSweepDone = true; autoDeliverSweep(); }
                }, false);
    }

    private void askForSeed() {
        final EditText in = input(Language.msg(l10n_mall_shop_seed));
        in.setMinLines(3);
        AlertDialog seedDialog = languageDialog()
                .setTitle(Language.msg(l10n_mall_inbox_setup))
                .setMessage(Language.msg(l10n_mall_inbox_setup_help))
                .setView(in)
                .setPositiveButton(Language.msg(l10n_terminal_editor_create), (d, w) -> { String s = in.getText().toString().trim(); if (!s.isEmpty()) deriveIdentity(s); })
                .create();
        seedDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        seedDialog.show();
    }

    // ---- scanning + routing ----

    private void requestScan() {
        if (crypto == null) return;
        if (orderScanner != null) orderScanner.scan(chainBlock);
        if (paymentScanner != null) paymentScanner.scan(chainBlock);
    }

    /** An opened MINIMERCH message: orders/inquiries land as orders; buyer replies append to the order chat. */
    private boolean routeIncoming(String coinid, com.eurobuddha.merchinbox.integratedcomms.Opened opened, JSONObject coin) {
        MerchMessage m = MerchMessage.fromWire(opened.plaintext);
        if (m == null || !opened.fromPublicId.equals(m.from)) return false;   // sig must match claimed sender
        if (!myId.equals(m.to)) return false;                                  // addressed to me
        if (MerchMessage.ORDER.equals(m.type)) {
            boolean fresh = db.upsertOrder(m, "seller", coinid);
            if (fresh) recheck(db.order(m.ref));   // what is due, from the vendor's own listing
            return fresh;
        }
        if (MerchMessage.INQUIRY.equals(m.type)) {
            boolean rowNew = db.upsertOrder(m, "seller", coinid);   // ensures the inquiry row exists
            boolean chatNew = db.insertChat(m.ref, true, m.message, m.randomid, m.date > 0 ? m.date : System.currentTimeMillis());
            return rowNew || chatNew;
        }
        if (MerchMessage.BUYER_REPLY.equals(m.type)) {
            return db.insertChat(m.ref, true, m.message, m.randomid, m.date > 0 ? m.date : System.currentTimeMillis());
        }
        return false;
    }

    /** A plaintext payment coin at the vendor address: match by the ref stamped in state[1], verify, mark paid. */
    private boolean routePayment(String coinid, com.eurobuddha.merchinbox.integratedcomms.Opened ignored, JSONObject coin) {
        String refHex = CommsScanner.statePort(coin, 1);
        if (refHex == null) return false;
        String ref = hexToText(refHex);
        if (ref.isEmpty()) return false;
        MerchDb.Order o = db.order(ref);
        if (o == null || o.paid) return false;   // PAID locks; any other coin is counted once (recordPayment keeps its id)
        String amount = MerchDb.coinAmount(coin);   // a token coin's tokenamount, never the Minima amount backing it
        String tokenid = coin.optString("tokenid", "0x00");
        String result = db.recordPayment(ref, coinid, amount, tokenid);   // each coin counted once
        // Auto-deliver only on a clean PAID (the token received is the order currency's) — never
        // UNDERPAID/WRONG_TOKEN, and nothing is sent for a mismatched order (MerchDb.mismatch). Queued, drained at scan end.
        if (MerchDb.PAID.equals(result)) pendingAutoDeliver.add(ref);
        return result != null;
    }

    private void onScanDone(int newCount) {
        ui.post(() -> {
            if (!pendingAutoDeliver.isEmpty()) {
                List<String> refs = new ArrayList<>(pendingAutoDeliver);
                pendingAutoDeliver.clear();
                for (String r : refs) maybeAutoDeliver(r);
            }
            if (autoDeliverOn()) autoDeliverSweep();   // each block: lines waiting for confirmations are checked again
            if (newCount <= 0) return;
            notifyNew(newCount);
            refreshVisibleOrderOrList();
        });
    }

    // ---- the vendor's own listing (Listing) ----

    /** Import the .shop file this vendor published; every order of that shop is checked against it again. */
    private void importListing(android.net.Uri uri) {
        final String me = myId;
        io.execute(() -> {
            CharSequence msg;
            try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                if (me == null || me.isEmpty()) throw new IllegalArgumentException(Language.msg(l10n_mall_inbox_listing_need_key).toString());
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) != -1) {
                    if (bos.size() + n > 16 * 1024 * 1024) throw new IllegalArgumentException("That file is too large to be a .shop file.");
                    bos.write(buf, 0, n);
                }
                JSONObject listing = Listing.compact(new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8), me);
                String shopId = listing.getString("shopId");
                db.setMeta("listing:" + shopId, listing.toString());
                int checked = 0;
                for (MerchDb.Order o : db.orders()) if ("seller".equals(o.role) && shopId.equals(o.shopId)) { recheck(o); checked++; }
                msg = Language.msg(l10n_mall_inbox_listing_in, listing.optString("shopName", shopId), checked);
            } catch (IllegalArgumentException e) { msg = InboxMessages.message(e.getMessage()); }
            catch (Exception e) { msg = Language.msg(l10n_mall_shop_read_failed); }
            final CharSequence say = msg;
            ui.post(() -> { languageDialog().setTitle(Language.msg(l10n_mall_inbox_listing_title)).setMessage(say)
                    .setPositiveButton(Language.msg(l10n_common_ok), null).show(); refreshVisibleOrderOrList(); });
        });
    }

    /** Recompute what an order is due from the vendor's own listing for its shop, if one was imported. */
    private void recheck(MerchDb.Order o) {
        if (o == null || !"seller".equals(o.role)) return;
        JSONObject listing = listingFor(o.shopId);
        if (listing == null) return;
        db.setMeta("listingproblem:" + o.ref, "this order has not been checked yet");   // fail closed until the check ends (M1)
        try {
            Listing.Check c = Listing.check(listing, o);
            List<String> kept = new ArrayList<>();
            for (String p : c.problems) { if (kept.size() == 20) { kept.add("… and " + (c.problems.size() - 20) + " more"); break; } kept.add(Listing.cap(p, 400)); }
            db.setMeta("due:" + o.ref, c.due);
            db.setMeta("listingproblem:" + o.ref, String.join("\n", kept));
            db.reevaluate(o.ref);   // PAID follows the recomputed due (review L4)
        } catch (Throwable e) {
            db.setMeta("listingproblem:" + o.ref, "this order could not be checked against your listing; nothing is sent for it");
        }
    }

    // ---- automatic NFT delivery ----

    /** Automatic delivery: OFF unless the vendor switches it on ("autosend"; the old "autodeliver" setting, ON by
     *  default before, is never read). Even ON, an order is sent only when every DeliveryRules check passes. */
    private boolean autoDeliverOn() { return "1".equals(db.getMeta("autosend", "0")); }

    /** Auto-send every unsent NFT line of a cleanly-paid order. Idempotent: nftsent: meta skips
     *  delivered lines, autoTried caps automatic attempts at one per line per session. */
    private void maybeAutoDeliver(String ref) {
        if (!autoDeliverOn()) return;
        MerchDb.Order o = db.order(ref);
        if (!db.deliverable(o)) return;
        if (!MerchDb.PAID.equals(o.status) && !MerchDb.CONFIRMED.equals(o.status)) return;
        if (o.payaddr == null || o.payaddr.isEmpty()) return;   // undeliverable — order screen explains
        for (String[] line : nftLines(o)) {
            String key = lineKey(ref, line);
            if (!deliveredTx(o, line).isEmpty()) continue;
            if (autoTried.contains(key) || !DeliveryLedger.state(db.getReadableDatabase(), ledgerKey(o, line)).isEmpty()) continue;   // one automatic send per line
            sendNft(o, line, true);   // runs every DeliveryRules check; a line that fails waits (checked again at the next block)
        }
    }

    /** Once per process: re-attempt paid-but-unsent NFT orders (failed auto-send last session,
     *  or payment that confirmed while the app was closed). */
    private void autoDeliverSweep() {
        if (!autoDeliverOn()) return;
        io.execute(() -> {
            List<String> refs = new ArrayList<>();
            for (MerchDb.Order o : db.orders())
                if (o.paid && (MerchDb.PAID.equals(o.status) || MerchDb.CONFIRMED.equals(o.status))
                        && !nftLines(o).isEmpty()) refs.add(o.ref);
            if (!refs.isEmpty()) ui.post(() -> { for (String r : refs) maybeAutoDeliver(r); });
        });
    }

    // ---- orders list ----

    private View buildOrders() {
        LinearLayout col = column();
        LinearLayout head = header(shopName.isEmpty() ? Language.msg(l10n_mall_inbox_orders) : shopName, false);
        head.addView(iconBtn("⇪", () -> listingLauncher.launch(new String[]{"*/*"})));   // import your own .shop (Listing)
        head.addView(iconBtn("⤓", this::exportCsv));          // export orders CSV
        head.addView(iconBtn("≡", this::showIdentityCard));   // ≡ menu → identity card
        col.addView(head);

        TextView ver = new TextView(this);
        Language.setText(ver, "miniMall Inbox v" + BuildConfig.VERSION_NAME);
        ver.setTextColor(Design.DIM2); ver.setTextSize(10f);
        ver.setPadding(dp(16), dp(2), dp(16), dp(4));
        col.addView(ver);

        TextView s = new TextView(this);
        s.setPadding(dp(16), dp(4), dp(16), dp(8)); s.setTextSize(12.5f);
        if (crypto == null) {
            Language.setText(s, paired ? Language.msg(l10n_mail_connecting) : Language.msg(l10n_mall_shop_node_unavailable));
            s.setTextColor(Design.DIM);
        } else {
            Language.setText(s, Language.msg(l10n_mall_inbox_ready, myId));
            s.setTextColor(Design.IN);
        }
        col.addView(s);

        // Automatic NFT delivery (default OFF) and the payment confirmations every send needs.
        final boolean auto = autoDeliverOn();
        TextView ad = new TextView(this);
        Language.setText(ad, Language.msg(auto ? l10n_mall_inbox_autosend_on : l10n_mall_inbox_autosend_off));
        ad.setTextColor(auto ? Design.IN : Design.DIM);
        ad.setTextSize(12.5f); ad.setPadding(dp(16), 0, dp(16), dp(4));
        ad.setOnClickListener(v -> {
            if (auto) { io.execute(() -> { db.setMeta("autosend", "0"); ui.post(() -> refreshTop(buildOrders())); }); return; }
            languageDialog().setTitle(Language.msg(l10n_mall_inbox_autosend_title))
                    .setMessage(Language.msg(l10n_mall_inbox_autosend_help, minConf()))
                    .setPositiveButton(Language.msg(l10n_mall_inbox_autosend_turn_on), (d, w) -> io.execute(() -> { db.setMeta("autosend", "1"); ui.post(() -> refreshTop(buildOrders())); }))
                    .setNegativeButton(Language.msg(l10n_common_cancel), null).show();
        });
        col.addView(ad);
        TextView mc = new TextView(this);
        Language.setText(mc, Language.msg(l10n_mall_inbox_minconf_line, minConf()));
        mc.setTextColor(Design.DIM); mc.setTextSize(12.5f); mc.setPadding(dp(16), 0, dp(16), dp(8));
        mc.setOnClickListener(v -> {
            final EditText in = new EditText(this);
            in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); in.setText(String.valueOf(minConf()));
            languageDialog().setTitle(Language.msg(l10n_mall_inbox_minconf_title)).setMessage(Language.msg(l10n_mall_inbox_minconf_help)).setView(in)
                    .setPositiveButton(Language.msg(l10n_mall_inbox_minconf_save), (d, w) -> {
                        int n; try { n = Integer.parseInt(in.getText().toString().trim()); } catch (NumberFormatException e) { n = 0; }
                        if (n < 1) { toast(Language.msg(l10n_mall_inbox_minconf_min)); return; }
                        final int keep = n;
                        io.execute(() -> { db.setMeta("minconf", String.valueOf(keep)); ui.post(() -> refreshTop(buildOrders())); });
                    })
                    .setNegativeButton(Language.msg(l10n_common_cancel), null).show();
        });
        col.addView(mc);

        col.addView(filterBar());
        final HorizontalScrollView shopBar = new HorizontalScrollView(this);
        shopBar.setHorizontalScrollBarEnabled(false);
        col.addView(shopBar);   // populated below only if the vendor runs >1 shop

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        final OrderAdapter adapter = new OrderAdapter(new ArrayList<>());
        rv.setAdapter(adapter);
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        io.execute(() -> {
            List<MerchDb.Order> all = db.orders();
            java.util.LinkedHashSet<String> shops = new java.util.LinkedHashSet<>();
            for (MerchDb.Order o : all) if (!o.shopName.isEmpty()) shops.add(o.shopName);
            List<MerchDb.Order> shown = new ArrayList<>();
            for (MerchDb.Order o : all) {
                if (!"ALL".equals(filterStatus) && !filterStatus.equals(o.status)) continue;
                if (!"ALL".equals(filterShop) && !filterShop.equals(o.shopName)) continue;
                shown.add(o);
            }
            ui.post(() -> {
                adapter.data.clear(); adapter.data.addAll(shown); adapter.notifyDataSetChanged();
                if (shops.size() >= 2) shopBar.addView(shopFilterRow(shops));
            });
        });
        return col;
    }

    private LinearLayout shopFilterRow(java.util.Set<String> shops) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), 0, dp(12), dp(8));
        java.util.List<String> all = new java.util.ArrayList<>(); all.add("ALL"); all.addAll(shops);
        for (final String s : all) {
            boolean on = filterShop.equals(s);
            TextView chip = Design.pill(this, "ALL".equals(s) ? Language.msg(l10n_mall_inbox_all_shops) : s, on ? Design.ACCENT : Design.SURFACE2, on ? Design.ON_ACCENT : Design.DIM);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6); chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> { filterShop = s; refreshTop(buildOrders()); });
            row.addView(chip);
        }
        return row;
    }

    private HorizontalScrollView filterBar() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(4), dp(12), dp(8));
        for (final String s : new String[]{"ALL", MerchDb.PENDING, MerchDb.PAID, MerchDb.CONFIRMED, MerchDb.SHIPPED, MerchDb.DELIVERED}) {
            boolean on = filterStatus.equals(s);
            TextView chip = Design.pill(this, InboxMessages.status(s), on ? Design.ACCENT : Design.SURFACE2, on ? Design.ON_ACCENT : Design.DIM);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6); chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> { filterStatus = s; refreshTop(buildOrders()); });
            row.addView(chip);
        }
        hs.addView(row);
        return hs;
    }

    private class OrderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<MerchDb.Order> data;
        OrderAdapter(List<MerchDb.Order> d) { data = d; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(row) {};
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            MerchDb.Order o = data.get(pos);
            LinearLayout row = (LinearLayout) h.itemView;
            row.removeAllViews();
            LinearLayout top = new LinearLayout(MainActivity.this);
            top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(MainActivity.this);
            Language.setText(title, Language.join(o.read ? "" : "●  ", orderTitle(o)));
            title.setTextColor(Design.TEXT); title.setTextSize(15f); title.setTypeface(null, Typeface.BOLD);
            title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(Design.pill(MainActivity.this, InboxMessages.status(o.status), statusColor(o.status), Design.ON_ACCENT));
            row.addView(top);
            TextView sub = new TextView(MainActivity.this);
            String shopPrefix = o.shopName.isEmpty() ? "" : o.shopName + "   ·   ";
            Language.setText(sub, Language.join(shopPrefix, o.amount, " ", dispCurrency(o), "   ·   ", o.counterparty, "   ·   ", timeAgo(o.date)));
            sub.setTextColor(Design.DIM); sub.setTextSize(12f); sub.setPadding(0, dp(3), 0, 0);
            row.addView(sub);
            row.setOnClickListener(v -> openOrder(o.ref));
        }
        @Override public int getItemCount() { return data.size(); }
    }

    // ---- order detail ----

    private void openOrder(String ref) {
        io.execute(() -> { db.markRead(ref); ui.post(() -> push(buildOrderDetail(ref))); });
    }

    private CharSequence orderTitle(MerchDb.Order o) {
        if (MerchDb.INQUIRY.equals(o.status) || (o.ref != null && o.ref.startsWith("INQ-"))) {
            if (o.shopName != null && !o.shopName.isEmpty() && ("Messages with " + o.shopName).equals(o.product))
                return Language.msg(l10n_mall_shop_messages_with_shop, o.shopName);
            if ("General enquiry".equals(o.product)) return Language.msg(l10n_mall_shop_general_enquiry);
        }
        return o.product.isEmpty() ? o.ref : o.product;
    }

    private View buildOrderDetail(String ref) {
        MerchDb.Order o = db.order(ref);
        LinearLayout col = column();
        col.setTag("order:" + ref);
        LinearLayout head = header(o == null ? Language.msg(l10n_mall_shop_order) : orderTitle(o), true);
        col.addView(head);
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(24));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        if (o == null) { body.addView(label(Language.msg(l10n_mall_shop_missing_order))); return col; }

        final boolean inquiry = MerchDb.INQUIRY.equals(o.status);

        // status + payment
        LinearLayout st = new LinearLayout(this); st.setGravity(Gravity.CENTER_VERTICAL);
        st.addView(Design.pill(this, inquiry ? Language.msg(l10n_mall_shop_enquiry) : InboxMessages.status(o.status), statusColor(o.status), Design.ON_ACCENT));
        if (!inquiry) {
            TextView paid = new TextView(this);
            // paid = the currency's own token arrived in full; anything else received is named by its whole token id
            Language.setText(paid, o.paid ? Language.msg(l10n_mall_inbox_paid_amount, o.paidAmount, dispCurrency(o))
                    : o.paidTokenid.isEmpty() ? Language.msg(l10n_mall_inbox_awaiting) : Language.msg(l10n_mall_inbox_received, o.paidAmount, o.paidTokenid));
            paid.setTextColor(o.paid ? Design.IN : Design.DIM); paid.setTextSize(12f);
            st.addView(paid);
        }
        st.setPadding(0, 0, 0, dp(10)); body.addView(st);
        if (db.mismatch(o)) {
            String want = MerchMessage.tokenFor(o.currency);
            body.addView(label(want == null ? Language.msg(l10n_mall_inbox_mismatch_unpayable, o.currency)
                    : Language.msg(l10n_mall_inbox_mismatch, MerchMessage.tokenName(want), want)));
        }

        if (!inquiry) {
            // line items + totals
            body.addView(sectionLabel(Language.msg(l10n_nft_mint_items)));
            body.addView(card(itemsText(o)));
            body.addView(kv(Language.msg(l10n_mall_shop_total), o.amount + " " + dispCurrency(o)));
            String problems = db.listingProblems(o);
            TextView chk = label(!problems.isEmpty() ? Language.msg(l10n_mall_inbox_listing_problems, InboxMessages.lines(problems))
                    : db.checked(o) ? Language.msg(l10n_mall_inbox_listing_checked, db.due(o), dispCurrency(o))
                    : Language.msg(l10n_mall_inbox_listing_unchecked, o.amount, dispCurrency(o)));
            if (!problems.isEmpty() || !db.checked(o)) chk.setTextColor(Design.RED);
            body.addView(chk);
            body.addView(kv(Language.msg(l10n_mall_shop_reference), o.ref));
            if (!o.shopName.isEmpty()) body.addView(kv(Language.msg(l10n_mall_inbox_shop), o.shopName));
            if (!o.coinid.isEmpty()) body.addView(copyRow(Language.msg(l10n_mall_inbox_order_tx), o.coinid, o.coinid));

            // delivery
            body.addView(sectionLabel(Language.msg(l10n_mall_shop_delivery)));
            body.addView(kv(Language.msg(l10n_mall_inbox_method), shippingLabel(o.shipping)));
            if (!o.delivery.isEmpty()) body.addView(copyRow(Language.msg(l10n_history_address), o.delivery, o.delivery));
            if (!o.message.isEmpty()) { body.addView(sectionLabel(Language.msg(l10n_mall_inbox_buyer_note))); body.addView(card(o.message)); }

            // NFT delivery — order lines that carry an NFT tokenid (from an NFT Studio .shop)
            List<String[]> nfts = nftLines(o);
            if (!nfts.isEmpty()) {
                body.addView(sectionLabel(autoDeliverOn() ? Language.msg(l10n_mall_inbox_nft_auto) : Language.msg(l10n_mall_inbox_nft_delivery)));
                for (String[] line : nfts) body.addView(nftSendRow(o, line));
            }
        }
        body.addView(copyRow(Language.msg(l10n_mall_inbox_buyer_key), o.counterparty, o.counterparty));

        // status actions (orders only — an enquiry has no lifecycle)
        if (!inquiry) {
            body.addView(sectionLabel(Language.msg(l10n_mall_inbox_update_status)));
            body.addView(statusButtons(o));
        }

        // reply / conversation
        body.addView(sectionLabel(inquiry ? Language.msg(l10n_mall_shop_conversation) : Language.msg(l10n_mall_inbox_message_buyer)));
        body.addView(replyRow(o));
        return col;
    }

    private View statusButtons(final MerchDb.Order o) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout actionRow = null;
        int actionIndex = 0;
        String[] flow = {MerchDb.PAID, MerchDb.CONFIRMED, MerchDb.SHIPPED, MerchDb.DELIVERED};
        for (final String s : flow) {
            if (actionIndex++ % 2 == 0) { actionRow = new LinearLayout(this); actionRow.setOrientation(LinearLayout.HORIZONTAL); wrap.addView(actionRow); }
            TextView b = button(InboxMessages.status(s), s.equals(o.status));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(6); b.setLayoutParams(lp);
            b.setOnClickListener(v -> advanceStatus(o, s));
            actionRow.addView(b);
        }
        return wrap;
    }

    private void advanceStatus(final MerchDb.Order o, final String status) { advanceStatus(o, status, false); }

    private void advanceStatus(final MerchDb.Order o, final String status, final boolean quiet) {
        io.execute(() -> {
            db.setStatus(o.ref, status);
            // notify the buyer
            MerchMessage m = new MerchMessage();
            m.type = MerchMessage.STATUS_UPDATE; m.ref = o.ref; m.status = status;
            m.from = myId; m.to = o.counterparty; m.randomid = MailText.randomId(); m.date = System.currentTimeMillis();
            CommsTransport.sendMessage(node, crypto, o.counterparty, m.toWire(), new CommsTransport.SendCb() {
                @Override public void onSent(String txid) {}
                @Override public void onFailed(String e) {}
            });
            ui.post(() -> { if (!quiet) toast(Language.msg(l10n_mall_inbox_marked, InboxMessages.status(status))); refreshOrderIfTopmost(o.ref); });
        });
    }

    /** Refresh only the screen the vendor is actually looking at — never yank navigation from
     *  a background event (auto-delivery, scans). Same pattern as onScanDone. */
    private void refreshOrderIfTopmost(String ref) {
        if (isFinishing() || isDestroyed()) return;
        View top = stack.peek();
        Object tag = top == null ? null : top.getTag();
        if (tag instanceof String && tag.equals("order:" + ref)) {
            if (!replyDrafts.defer(ref)) refreshTop(buildOrderDetail(ref));
        }
        else if (stack.size() == 1) refreshTop(buildOrders());
    }

    private void refreshVisibleOrderOrList() {
        if (isFinishing() || isDestroyed()) return;
        View top = stack.peek(); Object tag = top == null ? null : top.getTag();
        if (tag instanceof String && ((String) tag).startsWith("order:")) refreshOrderIfTopmost(((String) tag).substring(6));
        else if (stack.size() == 1) refreshTop(buildOrders());
    }

    // ---- NFT delivery ----

    /** Order lines carrying an NFT tokenid, as {name, tokenid, quantity, stateIdx, bundle}.
     *  stateIdx "0" + bundle "0" = plain NFT (delivered via `send`); stateIdx ≥1 = a StateNFT piece
     *  (state-replay transfer of the exact coin); bundle "1" = the COMPLETE collection (every held
     *  piece, one transfer each). Empty on plain orders. */
    private List<String[]> nftLines(MerchDb.Order o) {
        List<String[]> out = new ArrayList<>();
        if (o == null || o.items == null || o.items.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(o.items);
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i); if (it == null) continue;
                String tokenid = it.optString("nftTokenId", "");
                if (tokenid.isEmpty()) continue;
                int q = Math.max(1, it.optInt("quantity", 1));
                int idx = Math.max(0, it.optInt("nftStateIdx", 0));
                int bundle = it.optInt("nftBundle", 0) > 0 ? 1 : 0;
                out.add(new String[]{it.optString("product", "NFT"), tokenid, String.valueOf(q), String.valueOf(idx), String.valueOf(bundle)});
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Meta/set key for one deliverable line. Plain NFTs keep the historical ref:tokenid shape (so
     *  existing nftsent: metas survive); pieces append #idx; complete-collection bundles #ALL. */
    private static String lineKey(String ref, String[] line) {
        if ("1".equals(line[4])) return ref + ":" + line[1] + "#ALL";
        return ref + ":" + line[1] + ("0".equals(line[3]) ? "" : "#" + line[3]);
    }

    private View nftSendRow(final MerchDb.Order o, final String[] line) {
        final String name = line[0], tokenid = line[1], qty = line[2];
        final boolean piece = !"0".equals(line[3]);
        final boolean bundle = "1".equals(line[4]);
        final String key = lineKey(o.ref, line);
        final CharSequence label = bundle ? Language.msg(l10n_mall_inbox_complete_label, name)
                : piece ? "\"" + name + "\"" : qty + " × " + name;
        String sentTx = deliveredTx(o, line);
        if (!sentTx.isEmpty()) {
            TextView t = new TextView(this);
            Language.setText(t, Language.msg(l10n_mall_inbox_sent_label, label, (("sent".equals(sentTx) || "posted".equals(sentTx)) ? "" : " · " + sentTx)));
            t.setTextColor(Design.IN); t.setTextSize(13f); t.setPadding(0, dp(4), 0, dp(4));
            return t;
        }
        LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL);
        boolean inFlight = nftSending.contains(key);
        boolean resume = false;
        {
            try { resume = (piece || bundle ? deliveryJournal().load(key, o, line) : plainDeliveryJournal().load(key, o, line)) != null
                    || !db.getMeta("nftposted:" + key, "").isEmpty() || !db.getMeta("nftsent:" + key, "").isEmpty(); }
            catch (RuntimeException unavailable) { resume = true; }
        }
        final boolean recovering = resume;
        // Reserved but no journal: the app stopped between the reservation and the transfer. It may not have gone,
        // but nothing more is sent until the vendor has checked the history.
        if (!recovering && !inFlight && DeliveryLedger.RESERVED.equals(DeliveryLedger.state(db.getReadableDatabase(), ledgerKey(o, line)))) {
            wrap.addView(label(Language.msg(l10n_mall_inbox_unfinished_help, bundle ? Language.msg(l10n_mall_inbox_all_pieces) : piece ? "1 (#" + line[3] + ")" : qty, tokenid, o.payaddr)));
            TextView rel = button(Language.msg(l10n_mall_inbox_unfinished_release), true);
            rel.setOnClickListener(v -> languageDialog().setTitle(Language.msg(l10n_mall_inbox_unfinished_title))
                    .setMessage(Language.msg(l10n_mall_inbox_unfinished_confirm, tokenid, o.payaddr))
                    .setPositiveButton(Language.msg(l10n_mall_inbox_unfinished_release), (d, w) -> io.execute(() -> { DeliveryLedger.mark(db.getWritableDatabase(), ledgerKey(o, line), DeliveryLedger.RELEASED); ui.post(() -> refreshOrderIfTopmost(o.ref)); }))
                    .setNegativeButton(Language.msg(l10n_common_cancel), null).show());
            wrap.addView(rel);
            return wrap;
        }
        TextView b = button(inFlight ? Language.msg(l10n_chat_sending) : recovering ? Language.msg(l10n_mall_inbox_resume_delivery) : Language.msg(l10n_mall_inbox_send_buyer, label), !inFlight);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6); b.setLayoutParams(lp);
        final TextView why = new TextView(this);
        Language.setText(why, Language.msg(l10n_mall_inbox_checking)); why.setTextColor(Design.DIM2); why.setTextSize(11f); why.setPadding(0, dp(2), 0, 0);
        b.setOnClickListener(v -> {
            if (nftSending.contains(key)) return;
            checkDelivery(o.ref, line, verdict -> {
                if (!verdict.reasons.isEmpty()) {   // refused: there is no override
                    languageDialog().setTitle(Language.msg(l10n_mall_inbox_not_sent)).setMessage(InboxMessages.reasons(verdict.reasons, false))
                            .setPositiveButton(Language.msg(l10n_common_ok), null).show();
                    return;
                }
                MerchDb.Order now = db.order(o.ref);
                languageDialog()
                        .setTitle(Language.msg(recovering ? l10n_mall_inbox_resume_delivery : l10n_mall_inbox_send_nft))
                        .setMessage(Language.join(recovering ? Language.msg(l10n_mall_inbox_resume_help, now.payaddr) : Language.msg(l10n_mall_inbox_send_whole, label, tokenid,
                                bundle ? Language.msg(l10n_mall_inbox_all_pieces) : piece ? "1 (#" + line[3] + ")" : qty, now.payaddr),
                                "\n\n", Language.msg(l10n_mall_inbox_paid_of_due, verdict.paid.toPlainString(), verdict.due.toPlainString())))
                        .setPositiveButton(Language.msg(recovering ? l10n_mall_inbox_resume_delivery : l10n_mail_send), (d, w) -> sendNft(now, line, false))
                        .setNegativeButton(Language.msg(l10n_common_cancel), null)
                        .show();
            });
        });
        wrap.addView(b);
        wrap.addView(why);
        checkDelivery(o.ref, line, verdict -> Language.setText(why, verdict.reasons.isEmpty() ? Language.msg(l10n_mall_inbox_ready_to_send) : InboxMessages.reasons(verdict.reasons, true)));
        return wrap;
    }

    /** Deliver one order line. StateNFT pieces (stateIdx ≥ 1) transfer the exact coin with its state
     *  replayed; plain NFTs use a verified native unsigned transaction. auto=true is the headless
     *  payment-confirmed path: no toasts, notifications instead, refresh only if visible. */
    private void sendNft(final MerchDb.Order o, final String[] line, final boolean auto) {
        final String key = lineKey(o.ref, line);
        if (!nftSending.add(key)) return;   // one check-and-send per line at a time
        checkDelivery(o.ref, line, v -> {
            if (!v.reasons.isEmpty()) { deliverFailed(o, key, "Not sent:\n• " + String.join("\n• ", v.reasons), auto); return; }
            io.execute(() -> {
                final MerchDb.Order now = db.order(o.ref);   // the fresh row the checks used: its buyer address
                final String lk = ledgerKey(now, line);
                final android.database.sqlite.SQLiteDatabase w = db.getWritableDatabase();
                String why;
                if (!db.getMeta("nftsent:" + key, "").isEmpty()) why = "this line was already sent";
                else if (deliveryStarted(now, line, key)) {   // a journalled delivery resumes: verify, never a second transfer
                    DeliveryLedger.adopt(w, lk, v.product, v.token, v.qty, key);
                    why = null;
                } else why = DeliveryLedger.reserve(w, lk, v.product, v.token, v.qty, v.stock, v.held, key);
                final String refused = why;
                ui.post(() -> {
                    if (refused != null) { deliverFailed(o, key, "Not sent: " + refused, auto); return; }
                    if (auto) { autoTried.add(key); android.util.Log.i("miniMallInbox", "automatic delivery: " + v.qty.toPlainString() + " of token " + v.token
                            + " for order " + now.ref + " to " + now.payaddr); }
                    nftSending.remove(key);   // each transfer path below takes the line again
                    if ("1".equals(line[4])) { collectionDeliver(now, line, auto); return; }
                    if (!"0".equals(line[3])) { stateNftDeliver(now, line, auto); return; }
                    plainDeliver(now, line, key, auto);
                });
            });
        });
    }

    /** The delivery ledger's key for one order line: the order ref and the line's index in the order (review L6). */
    private String ledgerKey(MerchDb.Order o, String[] line) {
        List<String[]> all = nftLines(o);
        for (int i = 0; i < all.size(); i++) if (java.util.Arrays.equals(all.get(i), line)) return o.ref + "|" + i;
        return o.ref + "|" + lineKey(o.ref, line);
    }

    /** Every rule of DeliveryRules, from fresh reads: the chain tip, the token, the vendor's holdings, the
     *  payment coins at the vendor's own address, and (on the io thread) the order, its listing and the ledger. */
    private void checkDelivery(final String ref, final String[] line, final java.util.function.Consumer<DeliveryRules.Verdict> then) {
        final String tokenid = line[1];
        final boolean askToken = DeliveryGuard.basic(tokenid) == null;
        ask("block", b -> ask(askToken ? "tokens tokenid:" + tokenid : null, t -> ask(askToken ? "coins relevant:true tokenid:" + tokenid : null, h ->
                ask(Util.isValidAddress(vendorAddr) ? "coins relevant:true address:" + vendorAddr : null, pay -> io.execute(() -> {
            DeliveryRules.Inputs in = new DeliveryRules.Inputs();
            in.order = db.order(ref);
            in.mismatch = db.mismatch(in.order);
            in.listing = in.order == null ? null : listingFor(in.order.shopId);
            in.line = line;
            String key = lineKey(ref, line);
            android.database.sqlite.SQLiteDatabase w = db.getWritableDatabase();
            in.lineState = !db.getMeta("nftsent:" + key, "").isEmpty() ? DeliveryLedger.SENT : in.order == null ? "" : DeliveryLedger.state(w, ledgerKey(in.order, line));
            in.resuming = in.order != null && deliveryStarted(in.order, line, key);
            in.tokenReply = t; in.holdingsReply = h; in.paymentReply = pay;
            try { in.tip = Long.parseLong(b == null || b.optJSONObject("response") == null ? "" : b.optJSONObject("response").optString("block", "")); } catch (NumberFormatException e) { in.tip = 0; }
            in.minConf = minConf();
            JSONArray coins = pay == null ? null : pay.optJSONArray("response");
            for (int i = 0; coins != null && i < coins.length(); i++) {
                JSONObject c = coins.optJSONObject(i); if (c == null) continue;
                String id = c.optString("coinid", "").toLowerCase(java.util.Locale.ROOT);
                in.counted.put(id, db.getMeta("paycoin:" + id, ""));
            }
            DeliveryRules.Verdict v = DeliveryRules.reasons(in);
            if (!v.product.isEmpty()) { in.delivered = DeliveryLedger.delivered(w, v.product); in.inflight = DeliveryLedger.inflight(w, tokenid); v = DeliveryRules.reasons(in); }
            final DeliveryRules.Verdict out = v;
            ui.post(() -> then.accept(out));
        })))));
    }

    /** One node command; the callback gets the reply, or null on no answer (or when cmd is null). */
    private void ask(String cmd, java.util.function.Consumer<JSONObject> next) {
        if (cmd == null) { next.accept(null); return; }
        node.cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { next.accept(j); }
            @Override public void onError(String m) { next.accept(null); }
        });
    }

    /** Where Studio saves each shop it publishes, in this app's own data (shared by Studio and Inbox here). */
    static final String LISTINGS = "mall_listings";

    /** The vendor's own listing for a shop: the shop Studio last published from this app (read at once, no import
     *  needed), else one imported with ⇪; null when there is none or it is not this vendor's. */
    private JSONObject listingFor(String shopId) {
        if (shopId != null && shopId.matches("[a-zA-Z0-9]+(?:-[a-zA-Z0-9]+)*")) {
            java.io.File f = new java.io.File(new java.io.File(getFilesDir(), LISTINGS), shopId + ".shop");
            if (f.isFile() && f.length() <= 16L * 1024 * 1024) {
                try { return Listing.compact(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8), myId); }
                catch (Throwable notThisVendors) { /* fall through to an import */ }
            }
        }
        String raw = db.getMeta("listing:" + shopId, "");
        if (raw.isEmpty()) return null;
        try { return new JSONObject(raw); } catch (Exception e) { return null; }
    }

    /** Minimum confirmations a payment coin needs before anything is sent: vendor setting, default 3, never below 1. */
    private int minConf() {
        try { return Math.max(1, Integer.parseInt(db.getMeta("minconf", String.valueOf(DeliveryRules.DEFAULT_MIN_CONF)))); }
        catch (NumberFormatException e) { return DeliveryRules.DEFAULT_MIN_CONF; }
    }

    /** True once this line's delivery has a journal row or a posted/sent mark (its coins may be gone). */
    private boolean deliveryStarted(MerchDb.Order o, String[] line, String key) {
        boolean piece = !"0".equals(line[3]), bundle = "1".equals(line[4]);
        try { return (piece || bundle ? deliveryJournal().load(key, o, line) : plainDeliveryJournal().load(key, o, line)) != null
                || !db.getMeta("nftposted:" + key, "").isEmpty() || !db.getMeta("nftsent:" + key, "").isEmpty(); }
        catch (RuntimeException unavailable) { return true; }
    }

    private void plainDeliver(final MerchDb.Order o, final String[] line, final String key, final boolean auto) {
        if (!nftSending.add(key)) return;
        refreshOrderIfTopmost(o.ref);
        enqueueTx(() -> new PlainDeliveryFlow(node, plainDeliveryJournal(), io, ui,
                () -> !isFinishing() && !isDestroyed(), new DeliveryFlow.Listener() {
            public void posted() { txDone(); }
            public void confirmed() { markSentAndMaybeAdvance(o, key, "sent", auto); }
            public void unconfirmed() {
                nftSending.remove(key);
                CharSequence message = Language.msg(l10n_mall_inbox_delivery_unconfirmed);
                if (auto) notifyDelivery(o.ref, false, message); else toast(message);
                refreshOrderIfTopmost(o.ref);
            }
        }).start(o, key, line));
    }

    // ---- StateNFT piece delivery (state-replay transfer, exact receipt and inclusion) ----

    /** Tokenids whose script fingerprints as a StateNFT collection (memoized). */
    private final java.util.HashMap<String, Boolean> stateNftScripts = new java.util.HashMap<>();
    /** Serial txn queue: build/sign/post grinds PoW — never run two at once on a phone. */
    private final ArrayDeque<Runnable> txQueue = new ArrayDeque<>();
    private boolean txBusy = false;

    private boolean isKnownStateCollection(String tokenid) {
        Boolean b = stateNftScripts.get(tokenid);
        if (b == null && Util.isValidHexId(tokenid)) {
            node.cmd("tokens tokenid:" + tokenid, new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    Object resp = j.opt("response");
                    JSONObject tok = resp instanceof JSONObject ? (JSONObject) resp
                            : (resp instanceof JSONArray && ((JSONArray) resp).length() > 0 ? ((JSONArray) resp).optJSONObject(0) : null);
                    if (tok != null) stateNftScripts.put(tokenid, StateNft.isStateNftScript(tok.optString("script", "")));
                }
                @Override public void onError(String m) {}
            });
        }
        return b != null && b;
    }

    private void enqueueTx(Runnable job) {
        txQueue.add(job);
        pumpTxQueue();
    }
    private void pumpTxQueue() {
        if (txBusy) return;
        Runnable job = txQueue.poll();
        if (job == null) return;
        txBusy = true;
        job.run();
    }
    private void txDone() {
        txBusy = false;
        if (isFinishing() || isDestroyed()) { txQueue.clear(); return; }
        pumpTxQueue();
    }

    private void deliverFailed(MerchDb.Order o, String key, String msg, boolean auto) {
        nftSending.remove(key);
        if (auto) notifyDelivery(o.ref, false, msg);
        else languageDialog().setTitle(Language.msg(l10n_mall_inbox_not_sent)).setMessage(Language.msg(l10n_mall_inbox_send_error, InboxMessages.message(msg)))
                .setPositiveButton(Language.msg(l10n_common_ok), null).show();   // whole ids: a dialog, not a toast
        refreshOrderIfTopmost(o.ref);
    }

    /** A write failure after a post has an unknown outcome: keep this line locked in this host. */
    private void deliveryStorageFailed(MerchDb.Order o, String key, boolean auto) {
        if (isFinishing() || isDestroyed()) return;
        // Do not use deliverFailed: that clears nftSending and enables another transfer.
        CharSequence message = Language.msg(l10n_mall_inbox_storage_unconfirmed);
        if (auto) notifyDelivery(o.ref, false, message);
        else toast(message);
    }

    private void markSentAndMaybeAdvance(final MerchDb.Order o, final String key, final String txid, final boolean auto) {
        try {
            io.execute(() -> {
                final MerchDb.Order fresh;
                final boolean advance;
                try {
                    if (!db.setMetaChecked("nftsent:" + key, txid == null || txid.isEmpty() ? "sent" : txid)) {
                        ui.post(() -> deliveryStorageFailed(o, key, auto));
                        return;
                    }
                    try { DeliveryLedger.markJournal(db.getWritableDatabase(), key, DeliveryLedger.SENT); }
                    catch (RuntimeException ledgerUnwritten) { /* stays reserved: still counted, and nftsent: already says sent */ }
                    fresh = db.order(o.ref);
                    boolean all = fresh != null;
                    if (fresh != null) for (String[] l : nftLines(fresh)) {
                        if (deliveredTx(fresh, l).isEmpty()) { all = false; break; }
                    }
                    advance = all && (MerchDb.PAID.equals(fresh.status) || MerchDb.CONFIRMED.equals(fresh.status));
                } catch (RuntimeException unavailable) {
                    ui.post(() -> deliveryStorageFailed(o, key, auto));
                    return;
                }
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    nftSending.remove(key);
                    if (auto) notifyDelivery(o.ref, true, txid);
                    else toast(Language.msg(l10n_mall_inbox_sent));
                    if (advance) advanceStatus(fresh, MerchDb.SHIPPED, auto);
                    else refreshOrderIfTopmost(o.ref);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            deliveryStorageFailed(o, key, auto);
        }
    }

    private void stateNftDeliver(MerchDb.Order o, String[] line, boolean auto) {
        if (!line[3].matches("^[1-9][0-9]*$")) { deliverFailed(o, lineKey(o.ref, line), "bad edition index", auto); return; }
        stateDelivery(o, line, auto);
    }
    private void collectionDeliver(MerchDb.Order o, String[] line, boolean auto) { stateDelivery(o, line, auto); }
    private void stateDelivery(MerchDb.Order o, String[] line, boolean auto) {
        final String key = lineKey(o.ref, line);
        if (!nftSending.add(key)) return;
        refreshOrderIfTopmost(o.ref);
        enqueueTx(() -> new DeliveryFlow(node, deliveryJournal(), io, ui,
                () -> !isFinishing() && !isDestroyed(), new DeliveryFlow.Listener() {
            public void posted() { txDone(); }
            public void confirmed() { markSentAndMaybeAdvance(o, key, "sent", auto); }
            public void unconfirmed() {
                nftSending.remove(key);
                CharSequence message = Language.msg(l10n_mall_inbox_delivery_unconfirmed);
                if (auto) notifyDelivery(o.ref, false, message); else toast(message);
                refreshOrderIfTopmost(o.ref);
            }
        }).start(o, key, line));
    }

    private View replyRow(final MerchDb.Order o) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        for (String[] c : db.chat(o.ref)) {
            boolean incoming = "1".equals(c[0]);
            TextView t = new TextView(this);
            Language.setText(t, incoming ? Language.msg(l10n_mall_inbox_buyer_says, c[1]) : Language.msg(l10n_chat_you_preview, c[1]));
            t.setTextColor(incoming ? Design.TEXT : Design.DIM); t.setTextSize(13f);
            t.setPadding(0, dp(2), 0, dp(2));
            wrap.addView(t);
        }
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setPadding(0, dp(6), 0, 0);
        final EditText box = input(Language.msg(l10n_mall_inbox_reply));
        replyDrafts.bind(o.ref, box, () -> refreshOrderIfTopmost(o.ref));
        box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView send = button(Language.msg(l10n_mail_send), false);
        send.setOnClickListener(v -> {
            if (crypto == null || myId == null) { toast(Language.msg(l10n_mall_shop_connect_first)); return; }
            ReplyDrafts.Submission ticket = replyDrafts.begin(o.ref);
            if (ticket != null) sendReply(o, ticket);
        });
        bar.addView(box); bar.addView(send);
        wrap.addView(bar);
        return wrap;
    }

    private void sendReply(final MerchDb.Order o, final ReplyDrafts.Submission ticket) {
        final String rid = MailText.randomId();
        final String text = ticket.text();
        try {
            io.execute(() -> {
                try {
                    if (!db.insertChat(o.ref, false, text, rid, System.currentTimeMillis())) throw new IllegalStateException("Reply not saved");
                } catch (RuntimeException unavailable) {
                    ui.post(() -> replySaveFailed(ticket));
                    return;
                }
                MerchMessage m = new MerchMessage();
                m.type = MerchMessage.REPLY; m.ref = o.ref; m.message = text;
                m.from = myId; m.to = o.counterparty; m.randomid = rid; m.date = System.currentTimeMillis();
                CommsTransport.sendMessage(node, crypto, o.counterparty, m.toWire(), new CommsTransport.SendCb() {
                    @Override public void onSent(String txid) {}
                    @Override public void onFailed(String e) { ui.post(() -> {
                        if (!isFinishing() && !isDestroyed()) toast(Language.msg(l10n_mall_inbox_reply_error, InboxMessages.message(e)));
                    }); }
                });
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    replyDrafts.saved(ticket);
                    refreshOrderIfTopmost(o.ref);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) { replySaveFailed(ticket); }
    }
    private void replySaveFailed(ReplyDrafts.Submission ticket) {
        replyDrafts.failed(ticket);
        if (!isFinishing() && !isDestroyed()) toast(Language.msg(l10n_mall_inbox_reply_save_failed));
    }

    // ---- CSV export ----

    private void exportCsv() {
        if (db.orders().isEmpty()) { toast(Language.msg(l10n_mall_inbox_empty_export)); return; }
        csvLauncher.launch("orders-" + System.currentTimeMillis() + ".csv");
    }

    private void writeCsv(android.net.Uri uri) {
        io.execute(() -> {
            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                StringBuilder sb = new StringBuilder();
                sb.append("ref,status,product,total,currency,paid,paidAmount,shipping,delivery,buyerKey,date\n");
                for (MerchDb.Order o : db.orders()) {
                    sb.append(csv(o.ref)).append(',').append(csv(o.status)).append(',').append(csv(o.product)).append(',')
                      .append(csv(o.amount)).append(',').append(csv(dispCurrency(o))).append(',').append(o.paid ? "yes" : "no").append(',')
                      .append(csv(o.paidAmount)).append(',').append(csv(o.shipping)).append(',').append(csv(o.delivery)).append(',')
                      .append(csv(o.counterparty)).append(',').append(csv(fmtDate(o.date))).append('\n');
                }
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                ui.post(() -> toast(Language.msg(l10n_mall_inbox_exported, db.orders().size())));
            } catch (Exception e) { ui.post(() -> toast(Language.msg(l10n_mall_inbox_export_error, InboxMessages.error(e)))); }
        });
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
    private static String fmtDate(long ms) {
        if (ms <= 0) return "";
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(ms));
    }

    // ---- identity card (for pasting into the studio) ----

    private void showIdentityCard() {
        if (myId == null) { toast(Language.msg(l10n_mall_shop_connect_first)); return; }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(20), dp(16), dp(20), dp(8));
        TextView t = new TextView(this);
        Language.setText(t, Language.msg(l10n_mall_inbox_vendor_help));
        t.setTextColor(Design.DIM); t.setTextSize(12f); t.setPadding(0, 0, 0, dp(12));
        col.addView(t);
        final String card = vendorCard();
        ImageView qr = new ImageView(this);
        Bitmap bmp = QrUtil.qr(card, dp(220));
        if (bmp != null) { qr.setImageBitmap(bmp); qr.setAdjustViewBounds(true); col.addView(qr); }
        TextView val = new TextView(this);
        Language.setText(val, card); val.setTextColor(Design.DIM2); val.setTextSize(10f);
        val.setPadding(0, dp(10), 0, 0); col.addView(val);
        languageDialog().setTitle(Language.msg(l10n_mall_inbox_vendor_card)).setView(wrapScroll(col))
                .setPositiveButton(Language.msg(l10n_wallet_copy), (d, w) -> copy(card))
                .setNegativeButton(Language.msg(l10n_language_close), null).show();
    }

    /** publicId|receivingAddress — the studio bakes both into the Shop APK. */
    private String vendorCard() {
        return myId + "|" + (vendorAddr == null ? "" : vendorAddr);
    }

    // ---- helpers: formatting ----

    private String dispCurrency(MerchDb.Order o) {
        if (!o.currency.isEmpty()) return MerchMessage.label(o.currency);   // the wire value "USDT" is MxUSD on Minima
        return CommsTransport.USDT.equalsIgnoreCase(o.tokenid) ? "MxUSD" : "Minima";
    }
    private CharSequence shippingLabel(String s) {
        if ("uk".equalsIgnoreCase(s)) return Language.msg(l10n_mall_inbox_uk_shipping);
        if ("intl".equalsIgnoreCase(s)) return Language.msg(l10n_mall_inbox_international);
        if ("digital".equalsIgnoreCase(s)) return Language.msg(l10n_mall_inbox_digital);
        return s.isEmpty() ? "—" : s;
    }
    private CharSequence itemsText(MerchDb.Order o) {
        try {
            JSONArray a = new JSONArray(o.items);
            Language.TextBuilder sb = new Language.TextBuilder();
            boolean any = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i); if (it == null) continue;
                any = true;
                sb.append("• ").append(it.has("product") ? it.optString("product", "") : Language.msg(l10n_mall_inbox_item));
                String size = it.optString("size", ""); if (!size.isEmpty()) sb.append(" (").append(size).append(")");
                sb.append("  x").append(it.optInt("quantity", 1));
                String lt = it.optString("lineTotal", ""); if (!lt.isEmpty()) sb.append("  = ").append(lt);
                if (i < a.length() - 1) sb.append("\n");
            }
            return !any ? (o.product.isEmpty() ? "—" : o.product) : sb.build();
        } catch (Exception e) { return o.product.isEmpty() ? "—" : o.product; }
    }
    private int statusColor(String s) {
        if (MerchDb.DELIVERED.equals(s) || MerchDb.SHIPPED.equals(s)) return Design.IN;
        if (MerchDb.PAID.equals(s) || MerchDb.CONFIRMED.equals(s)) return Design.ACCENT;
        if (MerchDb.UNDERPAID.equals(s) || MerchDb.WRONG_TOKEN.equals(s) || MerchDb.TOKEN_MISMATCH.equals(s)) return Design.RED;
        return Design.DIM2;
    }
    private static CharSequence timeAgo(long ms) { return ms <= 0 ? "" : Language.since(ms); }
    private String loadShopName() {
        try (java.io.InputStream is = getAssets().open("pm_merchinbox_shop.json")) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8)).optString("shopName", "");
        } catch (Exception e) { return ""; }
    }

    private static String hexToText(String hex) {
        try {
            String h = hex.startsWith("0x") ? hex.substring(2) : hex;
            return new String(Hex.from("0x" + h), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    // ---- helpers: views ----

    private int dp(int v) { return Design.dp(this, v); }
    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(Design.BG);
        c.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return c;
    }
    private LinearLayout header(CharSequence title, boolean back) {
        LinearLayout h = new LinearLayout(this); h.setOrientation(LinearLayout.HORIZONTAL); h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(Design.SURFACE); h.setPadding(dp(8), dp(10), dp(8), dp(10));
        if (back) h.addView(iconBtn("‹", this::pop));
        TextView t = new TextView(this); Language.setText(t, title); t.setTextColor(Design.TEXT); t.setTextSize(18f);
        t.setTypeface(null, Typeface.BOLD); t.setPadding(dp(8), 0, 0, 0); t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return h;
    }
    private TextView iconBtn(String glyph, Runnable onClick) {
        TextView b = new TextView(this); Language.setText(b, glyph); b.setTextColor(Design.TEXT); b.setTextSize(22f);
        Language.description(b, "‹".equals(glyph) ? Language.msg(l10n_explorer_back) : "⤓".equals(glyph) ? Language.msg(l10n_mall_inbox_export_orders) : Language.msg(l10n_mall_inbox_vendor_card));
        b.setMinWidth(dp(48)); b.setMinHeight(dp(48));
        b.setGravity(Gravity.CENTER); b.setPadding(dp(10), dp(2), dp(10), dp(2));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }
    private TextView label(CharSequence s) { TextView t = new TextView(this); Language.setText(t, s); t.setTextColor(Design.DIM); t.setTextSize(13f); t.setPadding(0, dp(8), 0, 0); return t; }
    private TextView sectionLabel(CharSequence s) {
        TextView t = new TextView(this); Language.setText(t, Language.upper(s)); t.setTextColor(Design.DIM2); t.setTextSize(11f);
        t.setTypeface(null, Typeface.BOLD); t.setPadding(0, dp(16), 0, dp(6)); return t;
    }
    private TextView card(CharSequence s) {
        TextView t = new TextView(this); Language.setText(t, s); t.setTextColor(Design.TEXT); t.setTextSize(14f);
        t.setBackground(Design.roundBg(this, Design.SURFACE, 12)); t.setPadding(dp(14), dp(12), dp(14), dp(12)); return t;
    }
    private View kv(CharSequence k, CharSequence v) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(5), 0, dp(5));
        TextView kk = new TextView(this); Language.setText(kk, k); kk.setTextColor(Design.DIM); kk.setTextSize(13f);
        kk.setLayoutParams(new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView vv = new TextView(this); Language.setText(vv, v); vv.setTextColor(Design.TEXT); vv.setTextSize(13f);
        vv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(kk); r.addView(vv); return r;
    }
    private View copyRow(CharSequence k, String shown, final String full) {
        View r = kv(k, shown); r.setOnClickListener(v -> copy(full)); return r;
    }
    private TextView button(CharSequence text, boolean active) {
        TextView b = new TextView(this); Language.setText(b, text); b.setTextSize(13f); b.setGravity(Gravity.CENTER);
        b.setTextColor(active ? Design.ON_ACCENT : Design.TEXT);
        b.setBackground(Design.roundBg(this, active ? Design.ACCENT : Design.SURFACE2, 10));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }
    private EditText input(CharSequence hint) {
        EditText e = new EditText(this); Language.hint(e, hint); e.setHintTextColor(Design.DIM2);
        e.setTextColor(Design.TEXT); e.setTextSize(14f); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setBackground(Design.roundBg(this, Design.SURFACE2, 10)); e.setPadding(dp(14), dp(10), dp(14), dp(10));
        return e;
    }
    private ScrollView wrapScroll(View v) { ScrollView s = new ScrollView(this); s.addView(v); return s; }

    private View buildPairingBanner() {
        TextView t = new TextView(this);
        Language.setText(t, Language.msg(l10n_mall_shop_node_unavailable));
        t.setTextColor(Design.ON_ACCENT); t.setBackgroundColor(Design.ACCENT);
        t.setTextSize(13f); t.setPadding(dp(16), dp(10), dp(16), dp(10));
        return t;
    }

    // ---- misc ----

    private void copy(String s) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("merch", s));
        toast(Language.msg(l10n_utxo_copied));
    }
    private void toast(CharSequence s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel channel = nm.getNotificationChannel(CH);
            if (channel == null) channel = new NotificationChannel(CH, Language.msg(l10n_mall_inbox_orders), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setName(Language.msg(l10n_mall_inbox_orders));
            nm.createNotificationChannel(channel);
        }
    }
    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }
    private void notifyNew(int n) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
            androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle(Language.msg(l10n_mall_inbox_new_activity))
                    .setContentText(Language.msg(l10n_mall_inbox_new_updates, n))
                    .setAutoCancel(true);
            NotificationManagerCompat.from(this).notify("pandamonium.mall.inbox", 2, b.build());
        } catch (Exception ignored) {}
    }

    /** Auto-delivery outcome. Same id as notifyNew so the happy path collapses to ONE visible
     *  notification: Language.msg(l10n_mall_inbox_new_activity) is replaced by the delivery result seconds later. */
    private void notifyDelivery(String ref, boolean ok, CharSequence detail) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
            androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle(ok ? Language.msg(l10n_mall_inbox_payment_received) : Language.msg(l10n_mall_inbox_delivery_failed))
                    .setContentText(ok ? Language.msg(l10n_mall_inbox_delivered_notice, ref)
                                       : detail instanceof Language.Message ? detail : Language.msg(l10n_mall_inbox_retry_notice, ref))
                    .setAutoCancel(true);
            NotificationManagerCompat.from(this).notify("pandamonium.mall.inbox", 2, b.build());
        } catch (Exception ignored) {}
    }

    @Override protected void onStart() {
        super.onStart();
        DirectNodeEvents.subscribe(notifyReceiver, "NEWBLOCK", "NEWBALANCE");
    }
    @Override protected void onStop() {
        DirectNodeEvents.unsubscribe(notifyReceiver);
        super.onStop();
    }
}
