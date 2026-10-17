package com.eurobuddha.merchshop;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import com.eurobuddha.minimaapi.direct.DirectNodeEvents;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
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
import com.eurobuddha.merchshop.integratedcomms.CommsIdentity;
import com.eurobuddha.merchshop.integratedcomms.CommsScanner;
import com.eurobuddha.merchshop.integratedcomms.CommsTransport;
import com.eurobuddha.merchshop.integratedcomms.CryptoProvider;
import com.eurobuddha.merchshop.integratedcomms.Hex;
import com.eurobuddha.merchshop.integratedcomms.LocalEcCryptoProvider;
import com.eurobuddha.merchshop.integratedcomms.MailText;
import com.eurobuddha.merchshop.integratedcomms.MerchDb;
import com.eurobuddha.merchshop.integratedcomms.MerchMessage;
import com.eurobuddha.merchshop.integratedcomms.NodeApi;
import com.eurobuddha.merchshop.integratedcomms.Opened;
import com.eurobuddha.merchshop.integratedcomms.Sodium;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** miniMall Shop — the customer storefront (Maxima-free, on the comms primitive). */
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

    private static final String CH = "merchshop";

    private LazySodium ls;
    private NodeApi node;
    private MerchDb db;
    private CryptoProvider crypto;
    private CommsScanner scanner;
    private String myId, buyerPayaddr = "";
    private boolean paired = false;
    private int chainBlock = 0;
    private ShopStore store;
    private Catalog catalog;   // the currently-open shop (null on the My Shops home)
    private ActivityResultLauncher<String[]> importLauncher;
    private final Map<String, Integer> cart = new LinkedHashMap<>();
    private final android.util.LruCache<String, Bitmap> imgCache = new android.util.LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<View> stack = new ArrayDeque<>();

    private LinearLayout root;
    private FrameLayout container;
    private View pairingBanner;
    private DirectNodeEvents.Listener notifyReceiver;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ls = Sodium.get();
        db = new MerchDb(this);
        store = new ShopStore(this);

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

        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) importShop(uri); });
        showHome();
        handleIncomingShop(getIntent());   // launched by tapping a .shop file?

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

    @Override protected void onDestroy() {
        for (AlertDialog dialog : new java.util.ArrayList<>(languageDialogs)) dialog.dismiss();
        languageDialogs.clear();
        super.onDestroy();
        if (notifyReceiver != null) try { DirectNodeEvents.unsubscribe(notifyReceiver); } catch (Exception ignored) {}
        if (node != null) node.onDestroy();
        io.shutdownNow();
    }

    @Override public void onBackPressed() { if (stack.size() > 1) pop(); else super.onBackPressed(); }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingShop(intent);
    }

    /** Launched (or re-launched) by tapping a shared .shop file → import it. */
    private void handleIncomingShop(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        android.net.Uri uri = intent.getData();
        if (uri != null) importShop(uri);
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            root.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);
    }

    // ---- navigation ----
    private void push(View v) { stack.push(v); showTop(); }
    private void pop() { if (stack.size() > 1) { stack.pop(); showTop(); } }
    private void showHome() { stack.clear(); stack.push(buildHome()); showTop(); }
    private void openShop(String shopId) {
        Catalog c = store.get(shopId);
        if (c == null) { toast(Language.msg(l10n_mall_shop_open_failed)); return; }
        catalog = c; cart.clear();
        push(buildStorefront());
    }
    private void showTop() { container.removeAllViews(); container.addView(stack.peek()); }
    private void refreshTop(View rebuilt) { stack.pop(); stack.push(rebuilt); showTop(); }

    // ---- pairing + identity ----
    private void onPaired(boolean enabled) {
        paired = enabled;
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) {
            if (chainBlock == 0) fetchBlock();
            fetchBuyerAddr();
            if (crypto == null) setupIdentity(); else requestScan();
        }
    }

    private void fetchBuyerAddr() {
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) { String a = r.optString("miniaddress", r.optString("address", "")); if (!a.isEmpty()) buyerPayaddr = a; }
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
                ui.post(() -> { adoptIdentity(id); requestScan(); });
            } catch (Exception e) { ui.post(() -> toast(Language.msg(l10n_mall_shop_identity_error, ShopMessages.error(e)))); }
        });
    }

    private void adoptIdentity(CommsIdentity id) {
        crypto = new LocalEcCryptoProvider(ls, id);
        myId = id.publicId();
        scanner = new CommsScanner(node, crypto, db, CommsTransport.MINIMERCH_ADDRESS,
                this::routeIncoming, (ok, n) -> onScanDone(n));
    }

    private void askForSeed() {
        final EditText in = input(Language.msg(l10n_mall_shop_seed));
        in.setMinLines(3);
        AlertDialog seedDialog = languageDialog()
                .setTitle(Language.msg(l10n_mall_shop_setup))
                .setMessage(Language.msg(l10n_mall_shop_setup_help))
                .setView(in)
                .setPositiveButton(Language.msg(l10n_wallet_continue), (d, w) -> { String s = in.getText().toString().trim(); if (!s.isEmpty()) deriveIdentity(s); })
                .create();
        seedDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        seedDialog.show();
    }

    // ---- scanning ----
    private void requestScan() { if (crypto != null && scanner != null) scanner.scan(chainBlock); }

    /** Vendor → me: order status updates + replies, sealed to my box key, threaded by order ref. */
    private boolean routeIncoming(String coinid, Opened opened, JSONObject coin) {
        MerchMessage m = MerchMessage.fromWire(opened.plaintext);
        if (m == null || !opened.fromPublicId.equals(m.from) || !myId.equals(m.to)) return false;
        if (MerchMessage.STATUS_UPDATE.equals(m.type)) {
            MerchDb.Order o = db.order(m.ref);
            if (o == null) return false;
            db.setStatus(m.ref, m.status);
            // NFT order marked shipped/delivered = the token left the vendor's wallet — say so.
            if ((MerchDb.SHIPPED.equals(m.status) || MerchDb.DELIVERED.equals(m.status)) && !nftLines(o).isEmpty())
                nftDeliveredNotify = true;
            return true;
        }
        if (MerchMessage.REPLY.equals(m.type)) {
            return db.insertChat(m.ref, true, m.message, m.randomid, m.date > 0 ? m.date : System.currentTimeMillis());
        }
        return false;
    }

    private void onScanDone(int newCount) {
        ui.post(() -> {
            if (newCount <= 0) return;
            notifyNew(newCount);
            View top = stack.peek();
            Object tag = top == null ? null : top.getTag();
            if ("orders".equals(tag)) refreshTop(buildMyOrders());
            else if (tag instanceof String && ((String) tag).startsWith("order:")) refreshTop(buildOrderDetail(((String) tag).substring(6)));
        });
    }

    // ---- storefront ----
    // ---- My Shops home ----
    private View buildHome() {
        LinearLayout col = column();
        LinearLayout head = header("miniMall", false);
        head.addView(iconBtn("🧾", this::showMyOrders));   // my orders (across all shops)
        col.addView(head);

        TextView ver = new TextView(this);
        Language.setText(ver, "miniMall Shop v" + BuildConfig.VERSION_NAME);
        ver.setTextColor(Design.DIM2); ver.setTextSize(10f); ver.setPadding(dp(16), dp(2), dp(16), dp(4));
        col.addView(ver);

        if (crypto == null) {
            TextView s = new TextView(this);
            Language.setText(s, paired ? Language.msg(l10n_mail_connecting) : Language.msg(l10n_mall_shop_node_unavailable));
            s.setTextColor(Design.DIM); s.setTextSize(13f); s.setPadding(dp(16), dp(8), dp(16), dp(8));
            col.addView(s);
        }

        final List<Catalog> shops = store.all();
        if (shops.isEmpty()) {
            TextView empty = new TextView(this);
            Language.setText(empty, Language.msg(l10n_mall_shop_empty));
            empty.setTextColor(Design.DIM2); empty.setGravity(Gravity.CENTER); empty.setTextSize(14f);
            empty.setPadding(dp(24), dp(56), dp(24), 0);
            col.addView(empty, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            RecyclerView rv = new RecyclerView(this);
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
                    LinearLayout row = new LinearLayout(MainActivity.this); row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(dp(16), dp(14), dp(16), dp(14));
                    row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    return new RecyclerView.ViewHolder(row) {};
                }
                @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                    final Catalog c = shops.get(pos);
                    LinearLayout row = (LinearLayout) h.itemView; row.removeAllViews();
                    TextView name = new TextView(MainActivity.this);
                    Language.setText(name, c.shopName); name.setTextColor(Design.TEXT); name.setTextSize(16f); name.setTypeface(null, Typeface.BOLD);
                    row.addView(name);
                    TextView sub = new TextView(MainActivity.this);
                    Language.setText(sub, Language.msg(l10n_mall_shop_products, c.products.size(), c.currency));
                    sub.setTextColor(Design.DIM); sub.setTextSize(12f); sub.setPadding(0, dp(3), 0, 0);
                    row.addView(sub);
                    row.setOnClickListener(v -> openShop(shopKey(c)));
                    row.setOnLongClickListener(v -> { confirmRemoveShop(c); return true; });
                }
                @Override public int getItemCount() { return shops.size(); }
            });
            col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        TextView add = button(Language.msg(l10n_mall_shop_add), true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = dp(16); alp.setMargins(m, m, m, m); add.setLayoutParams(alp); add.setPadding(dp(16), dp(14), dp(16), dp(14));
        add.setOnClickListener(v -> importLauncher.launch(new String[]{"application/json", "application/octet-stream", "*/*"}));
        col.addView(add);
        return col;
    }

    private static String shopKey(Catalog c) {
        return (c.shopId == null || c.shopId.isEmpty()) ? ShopStore.slug(c.shopName) : c.shopId;
    }

    private void importShop(android.net.Uri uri) {
        io.execute(() -> {
            try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) != -1) {
                    if (bos.size() + n > 16 * 1024 * 1024) throw new java.io.IOException("Shop file is too large");
                    bos.write(buf, 0, n);
                }
                String json = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                final Catalog c = store.importBundle(json);
                ui.post(() -> {
                    if (c == null) toast(Language.msg(l10n_mall_shop_invalid_file));
                    else { toast(Language.msg(l10n_mall_shop_added, c.shopName)); showHome(); }
                });
            } catch (Exception e) { ui.post(() -> toast(Language.msg(l10n_mall_shop_read_failed))); }
        });
    }

    private void confirmRemoveShop(final Catalog c) {
        languageDialog().setTitle(Language.msg(l10n_mall_shop_remove_title, c.shopName))
                .setMessage(Language.msg(l10n_mall_shop_remove_help))
                .setPositiveButton(Language.msg(l10n_mall_shop_remove), (d, w) -> io.execute(() -> { store.delete(shopKey(c)); ui.post(() -> refreshTop(buildHome())); }))
                .setNegativeButton(Language.msg(l10n_common_cancel), null).show();
    }

    private View buildStorefront() {
        final boolean nftShop = catalog.isNftShop();
        LinearLayout col = column();
        LinearLayout head = header(catalog.shopName, true);    // back → My Shops
        head.addView(iconBtn("✉", this::openGeneralThread));   // message the vendor (pre-sales)
        col.addView(head);

        if (nftShop) {
            // collection header, marketplace-style
            TextView sub = new TextView(this);
            Language.setText(sub, Language.msg(l10n_mall_shop_nft_pays, catalog.currency.toUpperCase(java.util.Locale.ROOT)));
            sub.setTextColor(Design.ACCENT); sub.setTextSize(11f); sub.setTypeface(null, Typeface.BOLD);
            sub.setPadding(dp(16), dp(4), dp(16), 0);
            col.addView(sub);
            if (!catalog.about.trim().isEmpty()) {
                TextView abt = new TextView(this);
                Language.setText(abt, catalog.about.trim());
                abt.setTextColor(Design.DIM); abt.setTextSize(13f); abt.setPadding(dp(16), dp(4), dp(16), dp(4));
                col.addView(abt);
            }
        } else {
            TextView ver = new TextView(this);
            Language.setText(ver, Language.msg(l10n_mall_shop_pays, catalog.currency));
            ver.setTextColor(Design.DIM2); ver.setTextSize(11f); ver.setPadding(dp(16), dp(2), dp(16), dp(6));
            col.addView(ver);
        }

        if (crypto == null) {
            TextView s = new TextView(this);
            Language.setText(s, paired ? Language.msg(l10n_mail_connecting) : Language.msg(l10n_mall_shop_node_unavailable));
            s.setTextColor(Design.DIM); s.setTextSize(13f); s.setPadding(dp(16), dp(8), dp(16), dp(8));
            col.addView(s);
        }

        RecyclerView rv = new RecyclerView(this);
        if (nftShop) {
            final NftGridAdapter ad = new NftGridAdapter();
            androidx.recyclerview.widget.GridLayoutManager glm = new androidx.recyclerview.widget.GridLayoutManager(this, 2);
            glm.setSpanSizeLookup(new androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                @Override public int getSpanSize(int pos) { return ad.rows.get(pos) instanceof ColHeader ? 2 : 1; }
            });
            rv.setLayoutManager(glm);
            rv.setPadding(dp(10), dp(4), dp(10), dp(10));
            rv.setClipToPadding(false);
            rv.setAdapter(ad);
        } else {
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new ProductAdapter());
        }
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        col.addView(buildCartBar());
        return col;
    }

    /** A full-width collection section header in the NFT grid — carries the tokenid so the
     *  standard top-side token icon renders beside the name (the cards carry the stamped art). */
    private static final class ColHeader {
        final String tokenid; final CharSequence label;
        ColHeader(String tokenid, CharSequence label) { this.tokenid = tokenid; this.label = label; }
    }

    /** 2-col art-first grid for NFT shops: square art, name, edition chip, price. Tap → item page.
     *  StateNFT bundles + pieces group under full-width collection headers with the token icon. */
    private class NftGridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<Object> rows = new ArrayList<>();

        NftGridAdapter() {
            // plain products first, then each collection: header + bundle + pieces in edition order
            for (Catalog.Product p : catalog.products) if (!p.isStatePiece() && !p.isBundle()) rows.add(p);
            LinkedHashMap<String, List<Catalog.Product>> cols = new LinkedHashMap<>();
            for (Catalog.Product p : catalog.products) {
                if (!p.isStatePiece() && !p.isBundle()) continue;
                List<Catalog.Product> list = cols.get(p.nftTokenId);
                if (list == null) { list = new ArrayList<>(); cols.put(p.nftTokenId, list); }
                list.add(p);
            }
            for (Map.Entry<String, List<Catalog.Product>> e : cols.entrySet()) {
                List<Catalog.Product> items = e.getValue();
                items.sort((a, b) -> {
                    if (a.isBundle() != b.isBundle()) return a.isBundle() ? -1 : 1;   // bundle first
                    return Integer.compare(a.nftStateIdx, b.nftStateIdx);
                });
                Catalog.Product first = items.get(0);
                CharSequence name = !first.nftCollection.isEmpty() ? first.nftCollection : Language.msg(l10n_nft_collection);
                Catalog.CollectionInfo ci = catalog.nftCollections.get(e.getKey());
                if (ci != null && !ci.name.isEmpty()) name = ci.name;
                int pieceCount = 0;
                for (Catalog.Product p : items) pieceCount += p.isBundle() ? Math.max(1, p.nftPieces) : 1;
                rows.add(new ColHeader(e.getKey(), Language.upper(Language.msg(l10n_mall_shop_collection_pieces, name, pieceCount))));
                rows.addAll(items);
            }
        }

        @Override public int getItemViewType(int pos) { return rows.get(pos) instanceof ColHeader ? 1 : 0; }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            if (type == 1) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(16), dp(10), dp(4));
                row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(row) {};
            }
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(card) {};
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            if (rows.get(pos) instanceof ColHeader) {
                final ColHeader hd = (ColHeader) rows.get(pos);
                LinearLayout row = (LinearLayout) h.itemView; row.removeAllViews();
                Catalog.CollectionInfo ci = catalog.nftCollections.get(hd.tokenid);
                if (ci != null && !ci.icon.isEmpty()) {
                    final ImageView ic = new ImageView(MainActivity.this);
                    ic.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    ic.setBackground(Design.roundBg(MainActivity.this, Design.SURFACE2, 7));
                    ic.setClipToOutline(true);
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(26), dp(26));
                    ilp.rightMargin = dp(8); ic.setLayoutParams(ilp);
                    row.addView(ic);
                    Bitmap cb = imgCache.get("colicon:" + hd.tokenid);
                    if (cb != null) ic.setImageBitmap(cb);
                    else {
                        ic.setTag(hd.tokenid);
                        final String iconB64 = ci.icon;
                        io.execute(() -> {
                            Bitmap b = decodeSampled(iconB64, dp(52));
                            if (b != null) { imgCache.put("colicon:" + hd.tokenid, b); ui.post(() -> { if (hd.tokenid.equals(ic.getTag())) ic.setImageBitmap(b); }); }
                        });
                    }
                }
                TextView t = new TextView(MainActivity.this);
                Language.setText(t, hd.label);
                t.setTextColor(Design.DIM2); t.setTextSize(11f); t.setTypeface(null, Typeface.BOLD);
                row.addView(t);
                return;
            }
            final Catalog.Product p = (Catalog.Product) rows.get(pos);
            LinearLayout card = (LinearLayout) h.itemView; card.removeAllViews();
            LinearLayout inner = new LinearLayout(MainActivity.this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setBackground(Design.roundBg(MainActivity.this, Design.SURFACE, 12));
            inner.setPadding(dp(6), dp(6), dp(6), dp(10));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.setMargins(dp(6), dp(6), dp(6), dp(6));
            inner.setLayoutParams(ilp);

            final ImageView art = new ImageView(MainActivity.this) {
                @Override protected void onMeasure(int w, int hh) { super.onMeasure(w, w); }   // square
            };
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setBackground(Design.roundBg(MainActivity.this, Design.SURFACE2, 10));
            art.setClipToOutline(true);
            inner.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (p.image != null && !p.image.isEmpty()) {
                Bitmap c = imgCache.get(p.id);
                if (c != null) art.setImageBitmap(c);
                else {
                    art.setTag(p.id);
                    io.execute(() -> {
                        Bitmap b = decodeSampled(p.image, dp(220));
                        if (b != null) { imgCache.put(p.id, b); ui.post(() -> { if (p.id.equals(art.getTag())) art.setImageBitmap(b); }); }
                    });
                }
            }

            TextView nm = new TextView(MainActivity.this);
            Language.setText(nm, p.name); nm.setTextColor(Design.TEXT); nm.setTextSize(14f); nm.setTypeface(null, Typeface.BOLD);
            nm.setSingleLine(true); nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nm.setPadding(dp(4), dp(8), dp(4), 0);
            inner.addView(nm);

            TextView ed = new TextView(MainActivity.this);
            Language.setText(ed, editionChip(p)); ed.setTextColor(Design.DIM); ed.setTextSize(11.5f);
            ed.setPadding(dp(4), dp(2), dp(4), 0);
            inner.addView(ed);

            TextView pr = new TextView(MainActivity.this);
            Language.setText(pr, p.price + " " + catalog.currency);
            pr.setTextColor(Design.ACCENT); pr.setTextSize(13f); pr.setTypeface(null, Typeface.BOLD);
            pr.setPadding(dp(4), dp(2), dp(4), 0);
            inner.addView(pr);

            inner.setOnClickListener(v -> push(buildProductDetail(p)));
            card.addView(inner);
        }
        @Override public int getItemCount() { return rows.size(); }
    }

    /** Edition scarcity chip: "◈ complete · N pieces" for a whole collection, "◈ #idx / N" for a
     *  StateNFT piece, Language.msg(l10n_mall_shop_one_of_one) for a unique token, "◈ ×N eds" for the editions on sale. */
    private CharSequence editionChip(Catalog.Product p) {
        if (p.isBundle()) return p.nftPieces > 0 ? Language.msg(l10n_mall_shop_complete_pieces, p.nftPieces) : Language.msg(l10n_mall_shop_complete_collection);
        if (p.isStatePiece()) return p.nftSupply > 0 ? "◈ #" + p.nftStateIdx + " / " + p.nftSupply : "◈ #" + p.nftStateIdx;
        if (p.nftSupply == 1 || (p.nftSupply == 0 && p.maxUnits == 1)) return Language.msg(l10n_mall_shop_one_of_one);
        return Language.msg(l10n_mall_shop_editions, p.maxUnits);
    }

    private LinearLayout cartBarRef;
    private LinearLayout buildCartBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Design.SURFACE); bar.setPadding(dp(16), dp(12), dp(16), dp(12));
        cartBarRef = bar;
        refreshCartBar();
        return bar;
    }

    private void refreshCartBar() {
        if (cartBarRef == null) return;
        cartBarRef.removeAllViews();
        int count = cartCount();
        TextView info = new TextView(this);
        Language.setText(info, count == 0 ? Language.msg(l10n_mall_shop_cart_empty) : Language.msg(l10n_mall_shop_cart_summary, count, cartTotal().toPlainString(), catalog.currency));
        info.setTextColor(Design.TEXT); info.setTextSize(14f);
        cartBarRef.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView checkout = button(Language.msg(l10n_mall_shop_checkout), count > 0);
        checkout.setEnabled(count > 0);
        checkout.setOnClickListener(v -> { if (count > 0) openCheckout(); });
        cartBarRef.addView(checkout);
    }

    private class ProductAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(row) {};
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            Catalog.Product p = catalog.products.get(pos);
            LinearLayout row = (LinearLayout) h.itemView; row.removeAllViews();
            LinearLayout hwrap = new LinearLayout(MainActivity.this); hwrap.setOrientation(LinearLayout.HORIZONTAL);
            if (p.image != null && !p.image.isEmpty()) {
                final ImageView iv = new ImageView(MainActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(Design.roundBg(MainActivity.this, Design.SURFACE2, 10)); iv.setClipToOutline(true);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(66), dp(66)); ip.rightMargin = dp(12);
                iv.setLayoutParams(ip); hwrap.addView(iv);
                Bitmap c = imgCache.get(p.id);
                if (c != null) iv.setImageBitmap(c);
                else io.execute(() -> {                          // decode a small thumbnail OFF the UI thread
                    Bitmap b = decodeSampled(p.image, dp(140));
                    if (b != null) { imgCache.put(p.id, b); ui.post(() -> iv.setImageBitmap(b)); }
                });
            }
            LinearLayout content = new LinearLayout(MainActivity.this); content.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(MainActivity.this);
            Language.setText(name, p.name); name.setTextColor(Design.TEXT); name.setTextSize(16f); name.setTypeface(null, Typeface.BOLD);
            content.addView(name);
            if (!p.description.isEmpty()) {
                TextView d = new TextView(MainActivity.this);
                Language.setText(d, p.description); d.setTextColor(Design.DIM); d.setTextSize(13f); d.setPadding(0, dp(3), 0, 0);
                content.addView(d);
            }
            LinearLayout ctl = new LinearLayout(MainActivity.this); ctl.setOrientation(LinearLayout.HORIZONTAL);
            ctl.setGravity(Gravity.CENTER_VERTICAL); ctl.setPadding(0, dp(8), 0, 0);
            TextView price = new TextView(MainActivity.this);
            Language.setText(price, p.price + " " + catalog.currency); price.setTextColor(Design.ACCENT); price.setTextSize(15f); price.setTypeface(null, Typeface.BOLD);
            ctl.addView(price, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ctl.addView(stepper(p));
            content.addView(ctl);
            hwrap.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(hwrap);
            row.setOnClickListener(v -> push(buildProductDetail(p)));   // tap row → product detail
        }
        @Override public int getItemCount() { return catalog.products.size(); }
    }

    private View stepper(final Catalog.Product p) {
        LinearLayout s = new LinearLayout(this); s.setOrientation(LinearLayout.HORIZONTAL); s.setGravity(Gravity.CENTER_VERTICAL);
        final TextView qty = new TextView(this);
        Runnable render = () -> Language.setText(qty, String.valueOf(cart.getOrDefault(p.id, 0)));
        TextView minus = roundBtn("–"); TextView plus = roundBtn("+");
        qty.setTextColor(Design.TEXT); qty.setTextSize(16f); qty.setGravity(Gravity.CENTER);
        qty.setMinWidth(dp(36));
        minus.setOnClickListener(v -> { int q = cart.getOrDefault(p.id, 0); if (q > 0) { if (q - 1 == 0) cart.remove(p.id); else cart.put(p.id, q - 1); render.run(); refreshCartBar(); } });
        plus.setOnClickListener(v -> { int q = cart.getOrDefault(p.id, 0); if (q < p.maxUnits) { cart.put(p.id, q + 1); render.run(); refreshCartBar(); } else toast(Language.msg(l10n_mall_shop_maximum, p.maxUnits)); });
        render.run();
        s.addView(minus); s.addView(qty); s.addView(plus);
        return s;
    }

    /** Decode a base64 image sampled down to ~reqPx on its longest side (caps memory + decode time). */
    private static Bitmap decodeSampled(String b64, int reqPx) {
        try {
            byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1, dim = Math.max(o.outWidth, o.outHeight);
            while (reqPx > 0 && dim / sample > reqPx) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o2);
        } catch (Exception e) { return null; }
    }

    private View buildProductDetail(final Catalog.Product p) {
        LinearLayout col = column();
        col.addView(header(p.name, true));
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(16), dp(12), dp(16), dp(24));
        sv.addView(body); col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        if (p.image != null && !p.image.isEmpty()) {
            final ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(Design.roundBg(this, Design.SURFACE2, 14)); iv.setClipToOutline(true);
            iv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
            iv.setOnClickListener(v -> showFullImage(p));
            body.addView(iv);
            TextView tap = new TextView(this); Language.setText(tap, Language.msg(l10n_mall_shop_photo_help)); tap.setTextColor(Design.DIM2);
            tap.setTextSize(11f); tap.setGravity(Gravity.CENTER); tap.setPadding(0, dp(6), 0, 0); body.addView(tap);
            io.execute(() -> { Bitmap b = decodeSampled(p.image, 1200); if (b != null) ui.post(() -> iv.setImageBitmap(b)); });
        }

        // price + edition chip
        LinearLayout pr = new LinearLayout(this); pr.setOrientation(LinearLayout.HORIZONTAL); pr.setGravity(Gravity.CENTER_VERTICAL);
        pr.setPadding(0, dp(14), 0, dp(8));
        TextView price = new TextView(this);
        Language.setText(price, p.price + " " + catalog.currency); price.setTextColor(Design.ACCENT); price.setTextSize(20f);
        price.setTypeface(null, Typeface.BOLD);
        pr.addView(price, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (p.isNft()) {
            TextView chip = new TextView(this);
            Language.setText(chip, editionChip(p)); chip.setTextColor(Design.DIM); chip.setTextSize(13f);
            pr.addView(chip);
        }
        body.addView(pr);

        if (p.isNft()) {
            TextView nft = new TextView(this);
            Language.setText(nft, Language.msg(l10n_mall_shop_nft_automatic));
            nft.setTextColor(Design.IN); nft.setTextSize(12.5f); nft.setPadding(0, 0, 0, dp(8));
            body.addView(nft);
        }

        if (!p.description.isEmpty()) {
            TextView d = new TextView(this); Language.setText(d, p.description); d.setTextColor(Design.TEXT); d.setTextSize(15f);
            d.setLineSpacing(dp(3), 1f); body.addView(d);
        }

        // token provenance (NFT items)
        if (p.isNft()) {
            body.addView(sectionLabel(Language.msg(l10n_wallet_token)));
            if (p.isBundle()) {
                body.addView(kv(Language.msg(l10n_mall_shop_contents), p.nftPieces > 0 ? Language.msg(l10n_mall_shop_bundle_contents, p.nftPieces) : Language.msg(l10n_mall_shop_all_pieces)));
                if (!p.nftCollection.isEmpty()) body.addView(kv(Language.msg(l10n_nft_collection), p.nftCollection));
                TextView bd = new TextView(this);
                Language.setText(bd, Language.msg(l10n_mall_shop_pieces_help));
                bd.setTextColor(Design.DIM); bd.setTextSize(12.5f); bd.setPadding(0, dp(4), 0, dp(4)); bd.setLineSpacing(dp(2), 1f);
                body.addView(bd);
            } else if (p.isStatePiece()) {
                body.addView(kv(Language.msg(l10n_mall_shop_edition), p.nftSupply > 0 ? Language.msg(l10n_mall_shop_edition_of, p.nftStateIdx, p.nftSupply) : "#" + p.nftStateIdx));
                if (!p.nftCollection.isEmpty()) body.addView(kv(Language.msg(l10n_nft_collection), p.nftCollection));
            }
            body.addView(copyRow(Language.msg(l10n_utxo_token_id), shortId(p.nftTokenId), p.nftTokenId));
            if (p.nftSupply > 0) body.addView(kv(Language.msg(l10n_utxo_supply), p.nftSupply + (p.nftTicker.isEmpty() ? "" : " · " + p.nftTicker)));
            else if (!p.nftTicker.isEmpty()) body.addView(kv(Language.msg(l10n_utxo_ticker), p.nftTicker));
            final String ext = p.nftExternalUrl;
            if (ext != null && (ext.startsWith("http://") || ext.startsWith("https://"))) {
                LinearLayout lr = new LinearLayout(this); lr.setOrientation(LinearLayout.HORIZONTAL); lr.setPadding(0, dp(5), 0, dp(5));
                TextView kk = new TextView(this); Language.setText(kk, Language.msg(l10n_utxo_website)); kk.setTextColor(Design.DIM); kk.setTextSize(13f);
                kk.setLayoutParams(new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView lv = new TextView(this); Language.setText(lv, ext.replaceFirst("^https?://", "") + " ↗");
                lv.setTextColor(Design.ACCENT); lv.setTextSize(13f);
                lv.setOnClickListener(v -> { try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ext))); } catch (Exception ignored) {} });
                lr.addView(kk); lr.addView(lv);
                body.addView(lr);
            }
        }

        // quantity: hidden for 1-of-1s (there's only one); stepper otherwise
        if (p.maxUnits > 1) {
            body.addView(sectionLabel(Language.msg(l10n_mall_shop_quantity)));
            body.addView(stepper(p));
        }

        TextView checkout = button(p.isNft() ? Language.msg(l10n_mall_shop_buy, p.price, catalog.currency) : Language.msg(l10n_mall_shop_go_checkout), true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(20); checkout.setLayoutParams(alp); checkout.setPadding(dp(16), dp(13), dp(16), dp(13));
        checkout.setOnClickListener(v -> {
            if (cart.getOrDefault(p.id, 0) == 0) {
                if (p.isNft()) { cart.put(p.id, 1); refreshCartBar(); }   // Buy = take one, straight to checkout
                else { toast(Language.msg(l10n_mall_shop_add_first)); return; }
            }
            openCheckout();
        });
        body.addView(checkout);
        return col;
    }

    private void showFullImage(Catalog.Product p) {
        if (p.image == null || p.image.isEmpty()) return;
        final ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        ScrollView sv = new ScrollView(this); sv.addView(iv);
        languageDialog().setView(sv).setPositiveButton(Language.msg(l10n_language_close), null).show();
        io.execute(() -> { Bitmap b = decodeSampled(p.image, 2048); if (b != null) ui.post(() -> iv.setImageBitmap(b)); });
    }

    private int cartCount() { int n = 0; for (int q : cart.values()) n += q; return n; }
    private BigDecimal cartTotal() {
        BigDecimal t = BigDecimal.ZERO;
        for (Catalog.Product p : catalog.products) {
            int q = cart.getOrDefault(p.id, 0); if (q <= 0) continue;
            try { t = t.add(new BigDecimal(p.price).multiply(BigDecimal.valueOf(q))); } catch (Exception ignored) {}
        }
        return t;
    }

    private BigDecimal shippingFee(String shipId) {
        for (Catalog.Shipping s : catalog.shipping) if (s.id.equals(shipId)) {
            try { return new BigDecimal(s.fee == null || s.fee.isEmpty() ? "0" : s.fee); } catch (Exception e) { return BigDecimal.ZERO; }
        }
        return BigDecimal.ZERO;
    }
    private BigDecimal grandTotal(String shipId) { return cartTotal().add(shippingFee(shipId)); }
    private CharSequence feeLabel(Catalog.Shipping sh) {
        try {
            BigDecimal f = new BigDecimal(sh.fee == null || sh.fee.isEmpty() ? "0" : sh.fee);
            return f.signum() > 0 ? "  +" + f.toPlainString() : Language.join("  · ", Language.msg(l10n_mall_shop_free));
        } catch (Exception e) { return ""; }
    }

    // ---- checkout ----
    private String chosenShipping = "";

    /** Any cart line that is an NFT / any that is physical — drives the checkout layout. */
    private boolean cartHasNft() {
        for (Catalog.Product p : catalog.products) if (cart.getOrDefault(p.id, 0) > 0 && p.isNft()) return true;
        return false;
    }
    private boolean cartHasPhysical() {
        for (Catalog.Product p : catalog.products) if (cart.getOrDefault(p.id, 0) > 0 && !p.isNft()) return true;
        return false;
    }

    /** The delivery-method picker only earns its place when there's a real choice with a real fee:
     *  hidden for NFT-only carts, empty shipping lists, and the single free digital compat row. */
    private boolean shippingPickerNeeded(boolean nftOnly) {
        if (nftOnly || catalog.shipping.isEmpty()) return false;
        if (catalog.shipping.size() == 1) {
            Catalog.Shipping s = catalog.shipping.get(0);
            boolean free; try { free = new BigDecimal(s.fee == null || s.fee.isEmpty() ? "0" : s.fee).signum() <= 0; } catch (Exception e) { free = true; }
            if ("digital".equals(s.id) && free) return false;
        }
        return true;
    }

    private void openCheckout() {
        if (crypto == null) { toast(Language.msg(l10n_mall_shop_connect_first)); return; }
        if (!catalog.configured()) { toast(Language.msg(l10n_mall_shop_demo)); return; }
        if (cartCount() == 0) { toast(Language.msg(l10n_mall_shop_empty_error)); return; }
        push(buildCheckout());
        // NFT delivery needs the buyer's wallet address; re-fetch if pairing hadn't landed it yet.
        if (cartHasNft() && buyerPayaddr.isEmpty()) ensurePayaddr();
    }

    /** Full-screen checkout (replaces the old dialog: validation no longer dismisses and eats input).
     *  NFT lines are delivered to the buyer's own wallet address — never typed, always shown. */
    private View buildCheckout() {
        final boolean nft = cartHasNft(), physical = cartHasPhysical();
        final boolean nftOnly = nft && !physical;

        LinearLayout col = column();
        col.setTag("checkout");
        col.addView(header(Language.msg(l10n_mall_shop_checkout), true));

        ScrollView sv = new ScrollView(this);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(16), dp(8), dp(16), dp(24));
        sv.addView(body); col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // order summary
        for (Catalog.Product p : catalog.products) {
            int q = cart.getOrDefault(p.id, 0); if (q <= 0) continue;
            LinearLayout li = new LinearLayout(this); li.setOrientation(LinearLayout.HORIZONTAL); li.setPadding(0, dp(3), 0, dp(3));
            TextView n = new TextView(this);
            Language.setText(n, p.name + " × " + q + (p.isNft() ? "   ◈" : ""));
            n.setTextColor(Design.TEXT); n.setTextSize(14f);
            n.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            li.addView(n);
            TextView a = new TextView(this);
            try { Language.setText(a, new BigDecimal(p.price).multiply(BigDecimal.valueOf(q)).toPlainString() + " " + catalog.currency); } catch (Exception e) { Language.setText(a, ""); }
            a.setTextColor(Design.DIM); a.setTextSize(14f);
            li.addView(a);
            body.addView(li);
        }

        // delivery method (physical shops with real options only)
        chosenShipping = nftOnly ? "digital" : (catalog.shipping.isEmpty() ? "digital" : catalog.shipping.get(0).id);
        final TextView tot = new TextView(this);
        tot.setTextColor(Design.TEXT); tot.setTextSize(14f); tot.setTypeface(null, Typeface.BOLD); tot.setPadding(0, dp(14), 0, 0); tot.setLineSpacing(dp(3), 1f);
        final TextView pay = button("", true);
        final Runnable updateTotal = () -> {
            BigDecimal items = cartTotal(), ship = shippingFee(chosenShipping), grand = items.add(ship);
            Language.setText(tot, Language.join(ship.signum() > 0 ? Language.join(Language.msg(l10n_mall_shop_subtotal, items.toPlainString(), catalog.currency), "\n", Language.msg(l10n_mall_shop_shipping_fee, ship.toPlainString(), catalog.currency), "\n") : "", Language.msg(l10n_mall_shop_grand_total, grand.toPlainString(), catalog.currency)));
            Language.setText(pay, Language.msg(l10n_mall_shop_pay_amount, grand.toPlainString(), catalog.currency));
        };
        if (shippingPickerNeeded(nftOnly)) {
            body.addView(sectionLabel(Language.msg(l10n_mall_shop_delivery_method)));
            final LinearLayout ships = new LinearLayout(this); ships.setOrientation(LinearLayout.VERTICAL);
            final Runnable[] redraw = new Runnable[1];
            redraw[0] = () -> {
                ships.removeAllViews();
                for (final Catalog.Shipping sh : catalog.shipping) {
                    TextView b = button(Language.join(sh.label, feeLabel(sh)), sh.id.equals(chosenShipping));
                    b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    ((LinearLayout.LayoutParams) b.getLayoutParams()).bottomMargin = dp(6);
                    b.setOnClickListener(v -> { chosenShipping = sh.id; redraw[0].run(); updateTotal.run(); });
                    ships.addView(b);
                }
            };
            redraw[0].run();
            body.addView(ships);
        }

        // NFT delivery — the buyer's own wallet, straight from their node
        if (nft) {
            body.addView(sectionLabel(Language.msg(l10n_mall_shop_wallet_delivery)));
            body.addView(buildWalletRow());
            TextView why = new TextView(this);
            Language.setText(why, Language.msg(l10n_mall_shop_wallet_delivery_help));
            why.setTextColor(Design.DIM); why.setTextSize(12.5f); why.setPadding(0, dp(6), 0, 0); why.setLineSpacing(dp(2), 1f);
            body.addView(why);
        }

        // postal address only for physical items
        final EditText addr;
        if (physical) {
            body.addView(sectionLabel(nft ? Language.msg(l10n_mall_shop_physical_address) : Language.msg(l10n_mall_shop_delivery_address)));
            addr = input(nft ? Language.msg(l10n_mall_shop_physical_hint) : Language.msg(l10n_mall_shop_delivery_hint));
            addr.setMinLines(2); body.addView(addr);
        } else addr = null;

        body.addView(sectionLabel(Language.msg(l10n_mall_shop_note)));
        final EditText note = input(Language.msg(l10n_mall_shop_note_hint)); body.addView(note);

        updateTotal.run();
        body.addView(tot);

        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(18); pay.setLayoutParams(plp); pay.setPadding(dp(16), dp(13), dp(16), dp(13));
        pay.setOnClickListener(v -> {
            String a = addr == null ? "" : addr.getText().toString().trim();
            if (physical && a.isEmpty() && (nft || !"digital".equals(chosenShipping))) { toast(Language.msg(l10n_mall_shop_physical_required)); return; }
            if (!physical && !"digital".equals(chosenShipping) && a.isEmpty()) { toast(Language.msg(l10n_mall_shop_address_required)); return; }
            if (nft && buyerPayaddr.isEmpty()) { toast(Language.msg(l10n_mall_shop_waiting_address)); ensurePayaddr(); return; }
            confirmAndPlace(nftOnly ? "digital" : chosenShipping, a, note.getText().toString().trim());
        });
        body.addView(pay);
        return col;
    }

    /** The buyer's own receiving address (delivery destination for NFTs), tap to copy. */
    private View buildWalletRow() {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Design.roundBg(this, Design.SURFACE, 12)); row.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView a = new TextView(this);
        Language.setText(a, buyerPayaddr.isEmpty() ? Language.msg(l10n_mall_shop_reading_address) : "◈ " + shortId(buyerPayaddr));
        a.setTextColor(buyerPayaddr.isEmpty() ? Design.DIM : Design.TEXT); a.setTextSize(14f); a.setTypeface(null, Typeface.BOLD);
        a.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(a);
        if (!buyerPayaddr.isEmpty()) {
            TextView cp = new TextView(this); Language.setText(cp, "📋"); cp.setTextSize(15f); cp.setPadding(dp(8), 0, 0, 0);
            row.addView(cp);
            row.setOnClickListener(v -> copy(buyerPayaddr));
        } else {
            TextView retry = button(Language.msg(l10n_startup_retry), false);
            retry.setOnClickListener(v -> ensurePayaddr());
            row.addView(retry);
        }
        return row;
    }

    /** Re-fetch the wallet address and refresh the checkout if it's the visible screen. */
    private void ensurePayaddr() {
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) {
                    String a = r.optString("miniaddress", r.optString("address", ""));
                    if (!a.isEmpty()) buyerPayaddr = a;
                }
                refreshCheckoutIfTop();
            }
            @Override public void onError(String m) {
                toast(Language.msg(l10n_mall_shop_address_failed));
                refreshCheckoutIfTop();
            }
        });
    }
    private void refreshCheckoutIfTop() {
        View top = stack.peek();
        if (top != null && "checkout".equals(top.getTag())) refreshTop(buildCheckout());
    }

    private void confirmAndPlace(final String shipId, final String delivery, final String note) {
        final String total = grandTotal(shipId).toPlainString();
        languageDialog()
                .setTitle(Language.msg(l10n_mall_shop_pay_title, total, catalog.currency))
                .setMessage(Language.msg(l10n_mall_shop_pay_help, catalog.shopName, total, catalog.currency))
                .setPositiveButton(Language.msg(l10n_mall_shop_pay), (d, w) -> placeOrder(shipId, delivery, note))
                .setNegativeButton(Language.msg(l10n_explorer_back), null).show();
    }

    private void placeOrder(String shipId, String delivery, String note) {
        final JSONArray items = new JSONArray();
        final StringBuilder summary = new StringBuilder();
        BigDecimal total = BigDecimal.ZERO;
        try {
            for (Catalog.Product p : catalog.products) {
                int q = cart.getOrDefault(p.id, 0); if (q <= 0) continue;
                BigDecimal line = new BigDecimal(p.price).multiply(BigDecimal.valueOf(q));
                total = total.add(line);
                JSONObject it = new JSONObject();
                it.put("product", p.name); it.put("size", ""); it.put("quantity", q);
                it.put("unitPrice", p.price); it.put("lineTotal", line.toPlainString());
                // NFT listings carry the tokenid so the vendor's Inbox can send the NFT on-chain.
                if (p.nftTokenId != null && !p.nftTokenId.isEmpty()) it.put("nftTokenId", p.nftTokenId);
                // StateNFT piece: the edition index identifies the exact coin the Inbox must transfer
                if (p.nftStateIdx > 0) it.put("nftStateIdx", p.nftStateIdx);
                // Complete collection: the Inbox transfers every held piece, one txn each
                if (p.nftBundle > 0) it.put("nftBundle", 1);
                items.put(it);
                if (summary.length() > 0) summary.append(", ");
                summary.append(p.name).append(" x").append(q);
            }
        } catch (Exception e) { toast(Language.msg(l10n_mall_shop_build_error)); return; }
        if (items.length() == 0) { toast(Language.msg(l10n_mall_shop_empty_error)); return; }
        total = total.add(shippingFee(shipId));   // grand total = items + shipping

        final String ref = "ORD-" + MailText.randomId().substring(0, 8).toUpperCase();
        final String totalStr = total.toPlainString();
        final MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = ref; m.randomid = MailText.randomId();
        m.from = myId; m.to = catalog.vendorPublicId; m.date = System.currentTimeMillis();
        m.items = items.toString(); m.product = summary.toString();
        m.amount = totalStr; m.currency = catalog.currency; m.tokenid = catalog.tokenid;
        m.shipping = shipId; m.delivery = delivery; m.message = note; m.buyerPayaddr = buyerPayaddr;
        m.shopId = catalog.shopId; m.shopName = catalog.shopName;

        final AlertDialog progress = languageDialog()
                .setTitle(Language.msg(l10n_mall_shop_placing)).setMessage(Language.msg(l10n_mall_shop_sending_order)).setCancelable(false).create();
        progress.show();

        io.execute(() -> {
            db.upsertOrder(m, "buyer", "");
            db.setMeta("vendoraddr:" + ref, catalog.vendorAddress);   // so Retry payment works without the live catalog
            CommsTransport.sendMessage(node, crypto, catalog.vendorPublicId, m.toWire(), new CommsTransport.SendCb() {
                @Override public void onSent(String txid) {
                    CommsTransport.sendPayment(node, catalog.vendorAddress, totalStr, catalog.tokenid, ref, new CommsTransport.SendCb() {
                        @Override public void onSent(String ptx) {
                            ui.post(() -> { dismiss(progress); cart.clear(); pop(); toast(Language.msg(l10n_mall_shop_placed)); showMyOrders(); });
                        }
                        @Override public void onFailed(String e) {
                            db.setMeta("unpaid:" + ref, "1");   // flag so the order screen offers Retry payment
                            ui.post(() -> { dismiss(progress); cart.clear(); pop(); toast(Language.msg(l10n_mall_shop_unpaid)); showMyOrders(); });
                        }
                    });
                }
                @Override public void onFailed(String e) { ui.post(() -> { dismiss(progress); toast(Language.msg(l10n_mall_shop_order_error, ShopMessages.message(e))); }); }
            });
        });
    }

    private void retryPayment(final MerchDb.Order o) {
        // Resolve the vendor address WITHOUT the live catalog (null when reached from Home → My Orders):
        // per-order meta first, then the stored shop, then the open catalog.
        String vaddr = db.getMeta("vendoraddr:" + o.ref, "");
        if (vaddr.isEmpty() && o.shopId != null && !o.shopId.isEmpty()) {
            Catalog c = store.get(o.shopId);
            if (c != null) vaddr = c.vendorAddress;
        }
        if (vaddr.isEmpty() && catalog != null) vaddr = catalog.vendorAddress;
        if (vaddr.isEmpty()) { toast(Language.msg(l10n_mall_shop_retry_unavailable)); return; }
        final AlertDialog progress = languageDialog()
                .setTitle(Language.msg(l10n_mall_shop_retrying)).setMessage(Language.msg(l10n_mall_shop_sending_payment, o.amount, o.currency)).setCancelable(false).create();
        progress.show();
        CommsTransport.sendPayment(node, vaddr, o.amount, o.tokenid, o.ref, new CommsTransport.SendCb() {
            @Override public void onSent(String txid) {
                io.execute(() -> db.setMeta("unpaid:" + o.ref, ""));
                ui.post(() -> { dismiss(progress); toast(Language.msg(l10n_mall_shop_payment_sent)); refreshTop(buildOrderDetail(o.ref)); });
            }
            @Override public void onFailed(String e) { ui.post(() -> { dismiss(progress); toast(Language.msg(l10n_chat_payment_failed, ShopMessages.message(e))); }); }
        });
    }

    // ---- my orders ----
    private void showMyOrders() { push(buildMyOrders()); }

    private View buildMyOrders() {
        LinearLayout col = column(); col.setTag("orders");
        col.addView(header(Language.msg(l10n_mall_shop_orders), true));
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        final OrderAdapter adapter = new OrderAdapter(new ArrayList<>());
        rv.setAdapter(adapter);
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        io.execute(() -> { List<MerchDb.Order> os = db.orders(); ui.post(() -> { adapter.data.clear(); adapter.data.addAll(os); adapter.notifyDataSetChanged(); }); });
        return col;
    }

    private class OrderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<MerchDb.Order> data;
        OrderAdapter(List<MerchDb.Order> d) { data = d; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LinearLayout row = new LinearLayout(MainActivity.this); row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(row) {};
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            MerchDb.Order o = data.get(pos);
            LinearLayout row = (LinearLayout) h.itemView; row.removeAllViews();
            LinearLayout top = new LinearLayout(MainActivity.this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(MainActivity.this);
            Language.setText(t, orderTitle(o)); t.setTextColor(Design.TEXT); t.setTextSize(15f); t.setTypeface(null, Typeface.BOLD);
            t.setMaxLines(1); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(Design.pill(MainActivity.this, ShopMessages.status(o.status), statusColor(o.status), Design.ON_ACCENT));
            row.addView(top);
            TextView sub = new TextView(MainActivity.this);
            Language.setText(sub, Language.join(o.amount, " ", o.currency, "  ·  ", timeAgo(o.date)));
            sub.setTextColor(Design.DIM); sub.setTextSize(12f); sub.setPadding(0, dp(3), 0, 0);
            row.addView(sub);
            row.setOnClickListener(v -> push(buildOrderDetail(o.ref)));
        }
        @Override public int getItemCount() { return data.size(); }
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
        col.addView(header(o == null ? Language.msg(l10n_mall_shop_order) : (orderTitle(o)), true));
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(16), dp(8), dp(16), dp(24));
        sv.addView(body); col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        if (o == null) { body.addView(card(Language.msg(l10n_mall_shop_missing_order))); return col; }

        final boolean inquiry = MerchDb.INQUIRY.equals(o.status) || o.ref.startsWith("INQ-");
        final List<String[]> nfts = inquiry ? new ArrayList<>() : nftLines(o);
        final boolean badStatus = MerchDb.UNDERPAID.equals(o.status) || MerchDb.WRONG_TOKEN.equals(o.status);

        if (!nfts.isEmpty() && !badStatus) body.addView(buildTimeline(o.status));   // Placed → Paid → Delivered
        else body.addView(Design.pill(this, inquiry ? Language.msg(l10n_mall_shop_enquiry) : ShopMessages.status(o.status), statusColor(o.status), Design.ON_ACCENT));

        if (!inquiry) {
            body.addView(sectionLabel(Language.msg(l10n_mall_shop_order)));
            body.addView(card(orderTitle(o)));
            body.addView(kv(Language.msg(l10n_mall_shop_total), o.amount + " " + o.currency));
            body.addView(kv(Language.msg(l10n_mall_shop_reference), o.ref));
            if (nfts.isEmpty()) {
                body.addView(kv(Language.msg(l10n_mall_shop_delivery), o.shipping));
                if (!o.delivery.isEmpty()) body.addView(kv(Language.msg(l10n_mail_to), o.delivery));
            } else {
                for (String[] line : nfts) {
                    body.addView(copyRow(line[0] + " ×" + line[2], "NFT · " + shortId(line[1]), line[1]));
                }
                if (o.payaddr != null && !o.payaddr.isEmpty())
                    body.addView(copyRow(Language.msg(l10n_mall_shop_delivered_to), shortId(o.payaddr), o.payaddr));
                if (!o.delivery.isEmpty()) body.addView(kv(Language.msg(l10n_mail_to), o.delivery));   // mixed order: physical address
            }
            if ("1".equals(db.getMeta("unpaid:" + o.ref, ""))) {
                TextView retry = button(Language.msg(l10n_mall_shop_retry_payment), true);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(14); retry.setLayoutParams(rlp); retry.setPadding(dp(16), dp(12), dp(16), dp(12));
                retry.setOnClickListener(v -> retryPayment(o));
                body.addView(retry);
            }
        }

        body.addView(sectionLabel(inquiry ? Language.msg(l10n_mall_shop_conversation) : Language.msg(l10n_mall_shop_vendor_messages)));
        List<String[]> chat = db.chat(o.ref);
        if (chat.isEmpty()) { TextView none = new TextView(this); Language.setText(none, Language.msg(l10n_mall_shop_empty_messages)); none.setTextColor(Design.DIM2); none.setTextSize(13f); body.addView(none); }
        for (String[] c : chat) {
            boolean incoming = "1".equals(c[0]);
            TextView t = new TextView(this);
            Language.setText(t, incoming ? Language.msg(l10n_mall_shop_vendor_says, c[1]) : Language.msg(l10n_chat_you_preview, c[1]));
            t.setTextColor(incoming ? Design.TEXT : Design.DIM); t.setTextSize(14f); t.setPadding(0, dp(3), 0, dp(3));
            body.addView(t);
        }

        col.addView(composeBar(o));   // fixed bottom: message the vendor
        return col;
    }

    private View composeBar(final MerchDb.Order o) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Design.SURFACE); bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        final EditText box = input(Language.msg(l10n_mall_shop_message_hint));
        box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView send = button(Language.msg(l10n_mail_send), true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(8); send.setLayoutParams(slp);
        send.setOnClickListener(v -> {
            String t = box.getText().toString().trim();
            if (t.isEmpty()) return;
            Language.setText(box, "");
            sendBuyerReply(o, t);
        });
        bar.addView(box); bar.addView(send);
        return bar;
    }

    /** Open (and lazily create) the general, non-order conversation with the vendor. */
    private void openGeneralThread() {
        if (crypto == null) { toast(Language.msg(l10n_mall_shop_connect_first)); return; }
        if (!catalog.configured()) { toast(Language.msg(l10n_mall_shop_demo_short)); return; }
        final String ref = "INQ-" + myId.substring(2, Math.min(18, myId.length()));   // 16 hex = 64-bit buyer tag
        io.execute(() -> {
            if (db.order(ref) == null) {
                MerchMessage seed = new MerchMessage();
                seed.type = MerchMessage.INQUIRY; seed.ref = ref;
                seed.from = myId; seed.to = catalog.vendorPublicId; seed.date = System.currentTimeMillis();
                seed.product = "Messages with " + catalog.shopName;
                seed.currency = catalog.currency; seed.tokenid = catalog.tokenid; seed.buyerPayaddr = buyerPayaddr;
                seed.shopId = catalog.shopId; seed.shopName = catalog.shopName;
                db.upsertOrder(seed, "buyer", "");   // local-only until the first message is sent
            }
            ui.post(() -> push(buildOrderDetail(ref)));
        });
    }

    private void sendBuyerReply(final MerchDb.Order o, final String text) {
        if (crypto == null) { toast(Language.msg(l10n_mall_shop_connect_first)); return; }
        final boolean inquiry = MerchDb.INQUIRY.equals(o.status) || o.ref.startsWith("INQ-");
        final String rid = MailText.randomId();
        io.execute(() -> {
            db.insertChat(o.ref, false, text, rid, System.currentTimeMillis());
            MerchMessage m = new MerchMessage();
            m.type = inquiry ? MerchMessage.INQUIRY : MerchMessage.BUYER_REPLY;
            m.ref = o.ref; m.message = text;
            m.from = myId; m.to = o.counterparty; m.randomid = rid; m.date = System.currentTimeMillis();
            if (inquiry) { m.product = "General enquiry"; m.buyerPayaddr = buyerPayaddr; m.shopId = catalog.shopId; m.shopName = catalog.shopName; }
            CommsTransport.sendMessage(node, crypto, o.counterparty, m.toWire(), new CommsTransport.SendCb() {
                @Override public void onSent(String txid) {}
                @Override public void onFailed(String e) { ui.post(() -> toast(Language.msg(l10n_chat_send_failed, ShopMessages.message(e)))); }
            });
            ui.post(() -> refreshTop(buildOrderDetail(o.ref)));
        });
    }

    // ---- NFT order helpers ----

    /** Order lines carrying an NFT tokenid, as {name, tokenid, quantity}. Empty on plain orders. */
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
                out.add(new String[]{it.optString("product", "NFT"), tokenid, String.valueOf(q)});
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Placed → Paid → Delivered progress line for NFT orders. SHIPPED means the NFT left the
     *  vendor's wallet — shown to the buyer as Delivered. */
    private View buildTimeline(String status) {
        int stage = 1;
        if (MerchDb.PAID.equals(status) || MerchDb.CONFIRMED.equals(status)) stage = 2;
        else if (MerchDb.SHIPPED.equals(status) || MerchDb.DELIVERED.equals(status)) stage = 3;

        LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(4), 0, dp(6));
        LinearLayout dots = new LinearLayout(this); dots.setOrientation(LinearLayout.HORIZONTAL); dots.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.HORIZONTAL);
        CharSequence[] names = {Language.msg(l10n_mall_shop_timeline_placed),Language.msg(l10n_mall_shop_timeline_paid),Language.msg(l10n_mall_shop_timeline_delivered)};
        for (int i = 1; i <= 3; i++) {
            boolean done = i <= stage;
            TextView dot = new TextView(this);
            Language.setText(dot, done ? "●" : "◌"); dot.setTextSize(15f);
            dot.setTextColor(done ? (i == 3 ? Design.IN : Design.ACCENT) : Design.DIM2);
            dots.addView(dot);
            if (i < 3) {
                View line = new View(this);
                line.setBackgroundColor(i < stage ? Design.ACCENT : Design.SURFACE2);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, dp(2), 1f);
                ll.leftMargin = dp(6); ll.rightMargin = dp(6);
                line.setLayoutParams(ll);
                dots.addView(line);
            }
            TextView lab = new TextView(this);
            Language.setText(lab, names[i - 1]); lab.setTextSize(11.5f);
            lab.setTextColor(done ? Design.TEXT : Design.DIM2);
            lab.setGravity(i == 1 ? Gravity.START : (i == 3 ? Gravity.END : Gravity.CENTER));
            labels.addView(lab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        wrap.addView(dots);
        wrap.addView(labels);
        return wrap;
    }

    private static String shortId(String s) {
        if (s == null || s.length() < 14) return s == null ? "" : s;
        return s.substring(0, 8) + "…" + s.substring(s.length() - 4);
    }
    private void copy(String s) {
        ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(android.content.ClipData.newPlainText("minimall", s));
        toast(Language.msg(l10n_utxo_copied));
    }
    private View copyRow(CharSequence k, String shown, final String full) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(5), 0, dp(5));
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView kk = new TextView(this); Language.setText(kk, k); kk.setTextColor(Design.DIM); kk.setTextSize(13f);
        kk.setLayoutParams(new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView vv = new TextView(this); Language.setText(vv, shown + "  📋"); vv.setTextColor(Design.TEXT); vv.setTextSize(13f);
        vv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(kk); r.addView(vv);
        r.setOnClickListener(v -> copy(full));
        return r;
    }

    // ---- helpers ----
    private int statusColor(String s) {
        if (MerchDb.DELIVERED.equals(s) || MerchDb.SHIPPED.equals(s)) return Design.IN;
        if (MerchDb.PAID.equals(s) || MerchDb.CONFIRMED.equals(s)) return Design.ACCENT;
        if (MerchDb.UNDERPAID.equals(s) || MerchDb.WRONG_TOKEN.equals(s)) return Design.RED;
        return Design.DIM2;
    }
    private static CharSequence timeAgo(long ms) { return ms <= 0 ? "" : Language.since(ms); }

    private int dp(int v) { return Design.dp(this, v); }
    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setBackgroundColor(Design.BG);
        c.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return c;
    }
    private LinearLayout header(CharSequence title, boolean back) {
        LinearLayout h = new LinearLayout(this); h.setOrientation(LinearLayout.HORIZONTAL); h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(Design.SURFACE); h.setPadding(dp(8), dp(10), dp(8), dp(10));
        if (back) h.addView(iconBtn("‹", this::pop));
        TextView t = new TextView(this); Language.setText(t, title); t.setTextColor(Design.TEXT); t.setTextSize(18f); t.setTypeface(null, Typeface.BOLD);
        t.setPadding(dp(8), 0, 0, 0); t.setMaxLines(1); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return h;
    }
    private TextView iconBtn(String glyph, Runnable onClick) {
        TextView b = new TextView(this); Language.setText(b, glyph); b.setTextColor(Design.TEXT); b.setTextSize(20f);
        Language.description(b, "‹".equals(glyph) ? Language.msg(l10n_explorer_back) : "🧾".equals(glyph) ? Language.msg(l10n_mall_shop_orders) : Language.msg(l10n_mall_shop_message_vendor));
        b.setMinWidth(dp(48)); b.setMinHeight(dp(48));
        b.setGravity(Gravity.CENTER); b.setPadding(dp(10), dp(2), dp(10), dp(2)); b.setOnClickListener(v -> onClick.run());
        return b;
    }
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
    private TextView button(CharSequence text, boolean active) {
        TextView b = new TextView(this); Language.setText(b, text); b.setTextSize(13f); b.setGravity(Gravity.CENTER);
        b.setTextColor(active ? Design.ON_ACCENT : Design.TEXT);
        b.setBackground(Design.roundBg(this, active ? Design.ACCENT : Design.SURFACE2, 10));
        b.setPadding(dp(16), dp(10), dp(16), dp(10)); return b;
    }
    private TextView roundBtn(String glyph) {
        TextView b = new TextView(this); Language.setText(b, glyph); b.setTextColor(Design.TEXT); b.setTextSize(18f); b.setGravity(Gravity.CENTER);
        Language.description(b, "+".equals(glyph) ? Language.msg(l10n_mall_shop_plus) : Language.msg(l10n_mall_shop_minus));
        b.setBackground(Design.roundBg(this, Design.SURFACE2, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48)); lp.leftMargin = dp(4); lp.rightMargin = dp(4);
        b.setLayoutParams(lp); return b;
    }
    private EditText input(CharSequence hint) {
        EditText e = new EditText(this); Language.hint(e, hint); e.setHintTextColor(Design.DIM2); e.setTextColor(Design.TEXT); e.setTextSize(14f);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setBackground(Design.roundBg(this, Design.SURFACE2, 10)); e.setPadding(dp(14), dp(10), dp(14), dp(10)); return e;
    }
    private ScrollView wrapScroll(View v) { ScrollView s = new ScrollView(this); s.addView(v); return s; }
    private View buildPairingBanner() {
        TextView t = new TextView(this); Language.setText(t, Language.msg(l10n_mall_shop_node_unavailable));
        t.setTextColor(Design.ON_ACCENT); t.setBackgroundColor(Design.ACCENT); t.setTextSize(13f); t.setPadding(dp(16), dp(10), dp(16), dp(10));
        return t;
    }
    private void dismiss(AlertDialog d) { try { if (d != null && d.isShowing()) d.dismiss(); } catch (Exception ignored) {} }
    private void toast(CharSequence s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel channel = nm.getNotificationChannel(CH);
            if (channel == null) channel = new NotificationChannel(CH, Language.msg(l10n_mall_shop_channel), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setName(Language.msg(l10n_mall_shop_channel));
            nm.createNotificationChannel(channel);
        }
    }
    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }
    private boolean nftDeliveredNotify = false;   // set by routeIncoming when an NFT order goes SHIPPED

    private void notifyNew(int n) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
            boolean nftDelivered = nftDeliveredNotify; nftDeliveredNotify = false;
            androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(nftDelivered ? Language.msg(l10n_mall_shop_nft_notice) : Language.msg(l10n_mall_shop_order_notice))
                    .setContentText(nftDelivered ? Language.msg(l10n_mall_shop_nft_notice_body) : Language.msg(l10n_mall_shop_order_notice_body))
                    .setAutoCancel(true);
            NotificationManagerCompat.from(this).notify("pandamonium.mall.shop", 3, b.build());
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
