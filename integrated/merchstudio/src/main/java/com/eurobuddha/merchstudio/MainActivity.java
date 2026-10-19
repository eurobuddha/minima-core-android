package com.eurobuddha.merchstudio;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.goterl.lazysodium.LazySodium;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.merchstudio.integratedcomms.CommsIdentity;
import com.eurobuddha.merchstudio.integratedcomms.CommsTransport;
import com.eurobuddha.merchstudio.integratedcomms.Hex;
import com.eurobuddha.merchstudio.integratedcomms.Images;
import com.eurobuddha.merchstudio.integratedcomms.NodeApi;
import com.eurobuddha.merchstudio.integratedcomms.Sodium;

import java.io.File;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * miniMall Studio — build a shop on your phone and export a portable .shop bundle. Two kinds of
 * product under one roof: PRODUCTS (typed goods — name, photo, price, shipping) and NFTS (an
 * art-first gallery of the NFTs in your wallet, incl. StateNFT collections). NFT delivery is
 * on-chain and automatic: the buyer's wallet address rides in the order and miniMall Inbox sends
 * the NFT on confirmed payment; physical goods ship the old-fashioned way.
 */
public class MainActivity extends AppCompatActivity {

    private LazySodium ls;
    private NodeApi node;
    private boolean paired = false;
    private final java.util.Set<String> walletTokens = new java.util.HashSet<>();
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
    }

    private String vendorPublicId = "", vendorAddress = "";

    private String shopName = "", about = "";
    private String currency = "Minima", tokenid = CommsTransport.MINIMA;
    private final java.util.List<Catalog.Shipping> shipping = new java.util.ArrayList<>();

    /** Hand-typed products (physical / regular goods) — the classic miniMall Studio side. */
    private final java.util.List<TypedProduct> typedProducts = new java.util.ArrayList<>();
    private int homeTab = 0;   // 0 = Products, 1 = NFTs
    private androidx.activity.result.ActivityResultLauncher<String> imagePicker;
    private TypedProduct pickingFor;

    /** One row per listable NFT. Keyed by tokenid for plain NFTs, tokenid#idx for StateNFT pieces.
     *  A Refresh merges fresh data in while PRESERVING the vendor's listings + edits. */
    private final LinkedHashMap<String, NftListing> listings = new LinkedHashMap<>();
    /** StateNFT collections detected in the wallet (script-fingerprinted), keyed by tokenid. */
    private final LinkedHashMap<String, StateCollection> collections = new LinkedHashMap<>();
    /** Memoized `tokens tokenid:` records — token scripts are immutable, fetch once per process. */
    private final java.util.HashMap<String, JSONObject> tokenRecords = new java.util.HashMap<>();
    private final java.util.HashSet<String> fetchingRecords = new java.util.HashSet<>();
    private boolean nftsLoading = false, nftsLoaded = false;
    private boolean exporting = false;
    private boolean walletVerified = false;

    private static final int MAX_PRODUCTS = 40;   // .shop schema cap

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final ArrayDeque<View> stack = new ArrayDeque<>();
    private LinearLayout root;
    private FrameLayout container;

    /** A wallet NFT with the vendor's listing edits layered on top of the token metadata.
     *  Plain NFT: bal set, stateIdx 0. StateNFT piece: stateIdx ≥ 1, coinRaw + snMeta set, bal null.
     *  Complete-collection bundle: bundle=true, snMeta set — sells every piece the vendor holds. */
    private static final class NftListing {
        String tokenid = "";
        TokenBalance bal;
        int stateIdx = 0;                     // ≥1 = a piece of a StateNFT collection
        boolean bundle = false;               // the whole collection as ONE listing
        JSONObject coinRaw;                   // the piece's raw coin (byte-exact state)
        StateNft.Meta snMeta;                 // the piece's collection metadata
        boolean listed = false;
        String name = "", description = "";   // prefilled from token metadata, editable
        String price = "";
        String units = "";                    // editions to offer, default = sendable holding
        String cachedB64 = "";                // resolved shop image (bare base64 JPEG), lazily built
    }

    /** A hand-typed product: photo from the gallery, priced and stocked by the vendor. */
    private static final class TypedProduct {
        String name = "", description = "";
        String price = "";
        int maxUnits = 10;
        long photoRequest;
        String imageB64 = "";                 // bare base64 JPEG, compressed on pick
    }

    /** A StateNFT collection in the wallet: one tokenid, one coin per piece, art in coin state. */
    private static final class StateCollection {
        String tokenid = "";
        StateNft.Meta meta;
        boolean legacy = false;               // pre-SAMESTATE contract — creator can still rewrite state
        final java.util.List<JSONObject> ownedCoins = new java.util.ArrayList<>();  // stamped + replayable
        int unstampedCount = 0;
        boolean coinsLoading = false, coinsLoaded = false, tooLong = false, coinsVerified = false;
    }

    // ---- lifecycle ----

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ls = Sodium.get();

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG);
        container = new FrameLayout(this);
        root.addView(container, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            root.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);

        seedShipping();
        imagePicker = registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
            TypedProduct target = pickingFor; pickingFor = null;
            if (uri != null && target != null && typedProducts.contains(target)) attachPhoto(target, uri);
        });

        showGallery();
        node = new NodeApi(this, this::onPaired);
    }

    private void seedShipping() {
        if (!shipping.isEmpty()) return;
        for (String[] s : new String[][]{{"uk", "UK shipping"}, {"intl", "International"}, {"digital", "Digital / collect"}}) {
            Catalog.Shipping x = new Catalog.Shipping(); x.id = s[0]; x.label = s[1]; x.fee = "0"; shipping.add(x);
        }
    }

    private void attachPhoto(final TypedProduct p, final android.net.Uri uri) {
        if (isFinishing() || isDestroyed() || !typedProducts.contains(p)) return;
        final long request = ++p.photoRequest;
        io.execute(() -> {
            byte[] jpeg = Images.compressToFit(MainActivity.this, uri, 90000);
            final String encoded = jpeg == null ? null : android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
            ui.post(() -> applyPhoto(p, request, encoded));
        });
    }

    /** Same view ownership rule as ImageLoader.loadOver; never replace an unrelated editor. */
    private void applyPhoto(TypedProduct p, long request, String encoded) {
        if (isFinishing() || isDestroyed() || !typedProducts.contains(p) || p.photoRequest != request) return;
        if (encoded == null) { toast(Language.msg(l10n_mail_image_unreadable)); return; }
        p.imageB64 = encoded;
        for (View page : stack) {
            ImageView photo = page.findViewWithTag(p);
            if (photo == null) continue;
            photo.setImageBitmap(decodeB64(encoded));
            TextView caption = page.findViewWithTag("photoHint:" + System.identityHashCode(p));
            if (caption != null) Language.setText(caption, Language.msg(l10n_mall_studio_ui_change_photo));
        }
        refreshIfTag("gallery");
    }

    /** Everything that will land in the exported .shop — typed products plus listed NFTs. */
    private int totalListingCount() { return typedProducts.size() + listedItems().size(); }

    @Override protected void onDestroy() { for (AlertDialog dialog : new java.util.ArrayList<>(languageDialogs)) dialog.dismiss(); languageDialogs.clear(); super.onDestroy(); if (node != null) node.onDestroy(); io.shutdownNow(); }

    @Override public void onBackPressed() {
        if (stack.size() > 1) pop(); else super.onBackPressed();
    }

    // ---- navigation (view stack, as in miniMall) ----

    private void push(View v) { stack.push(v); showTop(); }
    private void pop() { if (stack.size() > 1) { stack.pop(); showTop(); } }
    private void showGallery() { stack.clear(); stack.push(buildGallery()); showTop(); }
    private void showTop() { container.removeAllViews(); container.addView(stack.peek()); }
    private void refreshTop(View rebuilt) { stack.pop(); stack.push(rebuilt); showTop(); }
    private void refreshIfTag(String tag) {
        View top = stack.peek();
        if (top != null && tag.equals(top.getTag())) {
            if ("gallery".equals(tag)) refreshTop(buildGallery());
            else if ("shop".equals(tag)) refreshTop(buildShopDetails());
        }
    }

    // ---- identity (vendor card) ----

    private void onPaired(boolean enabled) {
        paired = enabled;
        if (!enabled) walletVerified = false;
        if (enabled && vendorPublicId.isEmpty()) {
            node.cmd("vault action:seed", new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    JSONObject r = j.optJSONObject("response");
                    String ikm = r == null ? "" : r.optString("seed", r.optString("phrase", ""));
                    if (!ikm.isEmpty()) deriveCard(ikm);
                }
                @Override public void onError(String m) {}
            });
            node.cmd("getaddress", new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    JSONObject r = j.optJSONObject("response");
                    if (r != null) { vendorAddress = r.optString("miniaddress", r.optString("address", "")); refreshIfTag("gallery"); }
                }
                @Override public void onError(String m) {}
            });
        }
        if (enabled) loadNfts();
        refreshIfTag("gallery");
    }

    private void deriveCard(final String ikm) {
        io.execute(() -> {
            try {
                byte[] seed = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(StandardCharsets.UTF_8);
                String pid = CommsIdentity.fromSeed(ls, seed).publicId();
                ui.post(() -> { vendorPublicId = pid; refreshIfTag("gallery"); });
            } catch (Exception e) { ui.post(() -> toast(Language.msg(l10n_mall_shop_identity_error, StudioMessages.error(e)))); }
        });
    }

    private boolean cardReady() {
        return CommsIdentity.isValidPublicId(vendorPublicId) && vendorAddress != null && !vendorAddress.isEmpty();
    }

    // ---- wallet NFTs ----

    private void loadNfts() {
        if (nftsLoading) return;
        nftsLoading = true; walletVerified = false;
        refreshIfTag("gallery");
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                final JSONArray rows;
                try { rows = StudioData.balances(j); }
                catch (IllegalArgumentException invalid) { onError(invalid.getMessage()); return; }
                nftsLoading = false; nftsLoaded = true; walletVerified = true;
                mergeBalances(rows);
                refreshIfTag("gallery");
            }
            @Override public void onError(String m) {
                nftsLoading = false;
                if (!NodeApi.ERR_NOT_ENABLED.equals(m)) toast(Language.msg(l10n_mall_studio_ui_wallet_error, StudioMessages.message(m)));
                refreshIfTag("gallery");
            }
        });
    }

    /** Keep every 0-decimal non-Minima token we can currently send; carry existing edits across.
     *  StateNFT collections (script-fingerprinted via ensureRecord) are promoted OUT of the plain
     *  list — their individual pieces are enumerated per-coin by loadCollectionCoins. */
    private void mergeBalances(JSONArray rows) {
        if (rows == null) return;
        walletTokens.clear();
        for (int i=0;i<rows.length();i++) walletTokens.add(rows.optJSONObject(i).optString("tokenid"));
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        LinkedHashMap<String, NftListing> fresh = new LinkedHashMap<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            TokenBalance t = TokenBalance.from(row);
            if (t.isMinima()) continue;
            String d = t.meta == null ? "" : t.meta.decimals;
            if (!(d.isEmpty() || d.equals("0"))) continue;           // fungible token — not listable here
            if (!Util.isValidHexId(t.tokenid)) continue;
            seen.add(t.tokenid);
            // Fingerprint BEFORE any sendable gate: StateNFT coins are script-locked, so `balance`
            // reports sendable:0 for a whole collection — gating on sendable first would hide every
            // collection from detection (the exact bug this ordering fixes).
            // Fingerprint after the new balance snapshot has been installed.
            if (collections.containsKey(t.tokenid)) continue;         // a collection, not a plain row
            BigInteger held = wholeUnits(t.sendable);
            if (held.signum() <= 0) continue;                        // plain NFTs must be `send`-able
            NftListing l = listings.get(t.tokenid);
            if (l == null) {
                l = new NftListing();
                l.name = t.meta.name == null ? "" : t.meta.name;
                l.description = t.meta.description == null ? "" : t.meta.description;
                l.units = held.toString();
            }
            l.tokenid = t.tokenid;
            l.bal = t;
            fresh.put(t.tokenid, l);
        }
        // Drop collections that vanished from the wallet (with their piece listings); refresh the rest.
        for (java.util.Iterator<Map.Entry<String, StateCollection>> it = collections.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, StateCollection> e = it.next();
            if (!seen.contains(e.getKey())) {
                it.remove();
                listings.keySet().removeIf(k -> k.startsWith(e.getKey() + "#"));
            }
        }
        // Carry surviving piece listings across the rebuild (managed by loadCollectionCoins).
        for (Map.Entry<String, NftListing> e : listings.entrySet()) {
            if (e.getKey().contains("#") && collections.containsKey(e.getValue().tokenid)) fresh.put(e.getKey(), e.getValue());
        }
        listings.clear();
        listings.putAll(fresh);
        for (StateCollection col : new ArrayList<>(collections.values())) loadCollectionCoins(col);
        for (String tokenid : seen) ensureRecord(tokenid);
    }

    /** Fetch (once per process) the token record and promote StateNFT collections. */
    private void ensureRecord(final String tokenid) {
        JSONObject cached = tokenRecords.get(tokenid);
        if (cached != null) { adoptTokenRecord(tokenid, cached); return; }
        if (fetchingRecords.contains(tokenid)) return;
        fetchingRecords.add(tokenid);
        node.cmd("tokens tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                fetchingRecords.remove(tokenid);
                if (isFinishing() || isDestroyed() || !walletTokens.contains(tokenid)
                        || j == null || !Boolean.TRUE.equals(j.opt("status"))) return;
                Object resp = j.opt("response");
                JSONObject tok = null;
                if (resp instanceof JSONObject) tok = (JSONObject) resp;
                else if (resp instanceof JSONArray && ((JSONArray) resp).length() > 0) tok = ((JSONArray) resp).optJSONObject(0);
                if (tok == null) return;
                if (!tokenid.equalsIgnoreCase(tok.optString("tokenid", ""))
                        || !(tok.opt("script") instanceof String)) return;
                tokenRecords.put(tokenid, tok);
                adoptTokenRecord(tokenid, tok);
            }
            @Override public void onError(String m) { fetchingRecords.remove(tokenid); }
        });
    }

    private void adoptTokenRecord(String tokenid, JSONObject tok) {
        if (!walletTokens.contains(tokenid) || collections.containsKey(tokenid)) return;
        String script = tok.optString("script", "");
        if (!StateNft.isStateNftScript(script)) return;
        StateCollection col = new StateCollection();
        col.tokenid = tokenid;
        col.meta = StateNft.parseMeta(tokenid, tok);
        col.legacy = !script.startsWith("LET s=PREVSTATE");
        collections.put(tokenid, col);
        listings.remove(tokenid);             // no longer a plain aggregate row
        loadCollectionCoins(col);
        refreshIfTag("gallery");
    }

    /** Enumerate the collection's coins in this wallet (bounded per-token query); rebuild piece
     *  listings keyed tokenid#idx, PRESERVING listing edits (the coinid churns, the idx doesn't). */
    private void loadCollectionCoins(final StateCollection col) {
        if (col.coinsLoading) return;
        col.coinsLoading = true; col.coinsVerified = false;
        node.cmd("coins relevant:true tokenid:" + col.tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (isFinishing() || isDestroyed() || collections.get(col.tokenid) != col) return;
                final JSONArray arr;
                try { arr = StudioData.collection(j, col.tokenid, col.meta.size); }
                catch (IllegalArgumentException invalid) { onError(invalid.getMessage()); return; }
                col.coinsLoading = false; col.coinsLoaded = true; col.tooLong = false; col.coinsVerified = true;
                col.ownedCoins.clear(); col.unstampedCount = 0;
                java.util.HashSet<String> liveKeys = new java.util.HashSet<>();
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null || !Util.isValidHexId(c.optString("coinid", ""))) continue;
                    if (StateNft.isBuried(c)) continue;                           // graveyard coins still get tracked
                    String idx = StateNft.stamped(c);
                    if (idx == null) { col.unstampedCount++; continue; }          // sentinel — not listable
                    int index = StudioData.pieceIndex(c, col.meta.size);
                    if (index <= 0 || !StateNft.replayableState(c)) continue;
                    col.ownedCoins.add(c);
                    String key = col.tokenid + "#" + index;
                    liveKeys.add(key);
                    NftListing l = listings.get(key);
                    if (l == null) {
                        l = new NftListing();
                        l.name = col.meta.name + " #" + idx;
                        l.description = col.meta.description == null ? "" : col.meta.description;
                        l.units = "1";
                        listings.put(key, l);
                    }
                    String previousArt = artUrlOf(l);
                    l.tokenid = col.tokenid;
                    l.stateIdx = index;
                    l.coinRaw = c;
                    l.snMeta = col.meta;
                    if (!previousArt.equals(artUrlOf(l))) l.cachedB64 = "";
                }
                // A live bundle is a listing, not a coin; retain its edits while any pieces remain.
                if (!col.ownedCoins.isEmpty()) liveKeys.add(col.tokenid + "#ALL");
                // pieces that left the wallet are no longer listable
                listings.keySet().removeIf(k -> k.startsWith(col.tokenid + "#") && !liveKeys.contains(k));
                refreshIfTag("gallery");
                refreshIfCollection(col.tokenid);
            }
            @Override public void onError(String m) {
                if (isFinishing() || isDestroyed() || collections.get(col.tokenid) != col) return;
                col.coinsLoading = false;
                if (NodeApi.ERR_TOO_LONG.equals(m)) col.tooLong = true;
                else toast(Language.msg(l10n_mall_studio_ui_wallet_error, StudioMessages.message(m)));
                refreshIfTag("gallery");
                refreshIfCollection(col.tokenid);
            }
        });
    }

    private void refreshIfCollection(String tokenid) {
        View top = stack.peek();
        if (top != null && ("collection:" + tokenid).equals(top.getTag())) {
            StateCollection col = collections.get(tokenid);
            if (col != null) refreshTop(buildCollectionGrid(col));
        }
    }

    private static BigInteger wholeUnits(String amount) {
        try { return new BigDecimal(amount == null ? "0" : amount.trim()).toBigInteger(); }
        catch (Exception e) { return BigInteger.ZERO; }
    }

    private List<NftListing> listedItems() {
        List<NftListing> out = new ArrayList<>();
        for (NftListing l : listings.values()) if (l.listed) out.add(l);
        return out;
    }

    private String displayName(NftListing l) {
        String n = l.name == null ? "" : l.name.trim();
        if (!n.isEmpty()) return n;
        if (l.bundle) return l.snMeta.name + " — complete collection";
        if (l.stateIdx > 0) return l.snMeta.name + " #" + l.stateIdx;
        return l.bal == null || l.bal.meta.name == null ? "NFT" : l.bal.meta.name;
    }

    private CharSequence editionChip(NftListing l) {
        if (l.bundle) {
            StateCollection col = collections.get(l.tokenid);
            return col == null ? Language.msg(l10n_mall_studio_ui_all_pieces) : Language.msg(l10n_mall_studio_ui_all_pieces_count, col.ownedCoins.size());
        }
        if (l.stateIdx > 0) return "◈ #" + l.stateIdx + " / " + l.snMeta.size;
        BigInteger supply = wholeUnits(l.bal.total);
        return BigInteger.ONE.equals(supply) ? Language.msg(l10n_mall_shop_one_of_one) : Language.msg(l10n_mall_shop_editions, supply.toString());
    }

    /** The piece's art URI (embedded state art > url-mode > collection icon). */
    private String pieceImageUrl(NftListing l) {
        return StateNft.imageUrl(l.snMeta, l.stateIdx, l.coinRaw);
    }

    /** Art source for any listing/collection card; empty string = identicon only. */
    private String artUrlOf(NftListing l) {
        if (l.bundle) { String u = IconResolver.resolve(l.snMeta.icon); return u == null ? "" : u; }
        if (l.stateIdx > 0) { String u = pieceImageUrl(l); return u == null ? "" : u; }
        return l.bal != null && l.bal.hasIcon() ? l.bal.meta.iconUrl : "";
    }

    private String identiconKey(NftListing l) {
        if (l.bundle) return l.tokenid + "#ALL";
        return l.stateIdx > 0 ? l.tokenid + "#" + l.stateIdx : l.tokenid;
    }

    private String currencyLabel() { return "USDT".equals(currency) ? "mxUSDT" : "Minima"; }

    private boolean priced(NftListing l) {
        try { return new BigDecimal(l.price.trim()).signum() > 0; } catch (Exception e) { return false; }
    }

    // ==== SCREEN 1 · Home (Products | NFTs tabs) ====

    private View buildGallery() {
        LinearLayout col = column();
        col.setTag("gallery");
        col.addView(header("miniMall Studio", false));

        TextView status = new TextView(this);
        status.setTextSize(12f); status.setPadding(dp(16), dp(6), dp(16), dp(4));
        if (cardReady()) { Language.setText(status, Language.msg(l10n_mall_studio_ui_ready)); status.setTextColor(Design.IN); }
        else if (!paired) { Language.setText(status, Language.msg(l10n_mall_shop_node_unavailable)); status.setTextColor(Design.ACCENT); }
        else { Language.setText(status, Language.msg(l10n_mail_connecting)); status.setTextColor(Design.DIM); }
        col.addView(status);

        // tabs: hand-typed products | wallet NFTs
        LinearLayout tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(16), dp(6), dp(16), dp(4));
        tabs.addView(seg(Language.join(Language.msg(l10n_mall_studio_ui_products), typedProducts.isEmpty() ? "" : " · " + typedProducts.size()), homeTab == 0,
                () -> { homeTab = 0; refreshTop(buildGallery()); }));
        tabs.addView(seg("NFTs" + (listedItems().isEmpty() ? "" : " · " + listedItems().size()), homeTab == 1,
                () -> { homeTab = 1; refreshTop(buildGallery()); }));
        col.addView(tabs);

        if (homeTab == 0) buildProductsTab(col);
        else buildNftsTab(col);

        int total = totalListingCount();
        TextView cont = button(total == 0 ? Language.msg(l10n_mall_studio_ui_add_to_continue) : Language.msg(l10n_mall_studio_ui_continue_shop, total), total > 0);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.setMargins(dp(16), dp(8), dp(16), dp(14)); cont.setLayoutParams(cl);
        cont.setPadding(dp(16), dp(13), dp(16), dp(13));
        cont.setOnClickListener(v -> { if (totalListingCount() > 0) push(buildShopDetails()); });
        col.addView(cont);
        return col;
    }

    /** Tab 0 — the classic studio: typed products with gallery photos. */
    private void buildProductsTab(LinearLayout col) {
        if (typedProducts.isEmpty()) {
            col.addView(hint(Language.msg(l10n_mall_studio_ui_products_help)));
        }
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(4), dp(16), dp(10));
        sv.addView(list);
        for (final TypedProduct p : typedProducts) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Design.roundBg(this, Design.SURFACE, 12)); row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(8); row.setLayoutParams(rlp);
            ImageView thumb = new ImageView(this); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(Design.roundBg(this, Design.SURFACE2, 8)); thumb.setClipToOutline(true);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(48), dp(48)); tl.rightMargin = dp(10); thumb.setLayoutParams(tl);
            if (!p.imageB64.isEmpty()) { Bitmap b = decodeB64(p.imageB64); if (b != null) thumb.setImageBitmap(b); }
            row.addView(thumb);
            LinearLayout tt = new LinearLayout(this); tt.setOrientation(LinearLayout.VERTICAL);
            tt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView nm = new TextView(this); Language.setText(nm, p.name.isEmpty() ? Language.msg(l10n_mall_studio_ui_unnamed) : p.name);
            nm.setTextColor(Design.TEXT); nm.setTextSize(14.5f); nm.setTypeface(null, Typeface.BOLD);
            tt.addView(nm);
            TextView sub = new TextView(this);
            Language.setText(sub, Language.join(p.price.isEmpty() ? Language.msg(l10n_mall_studio_ui_no_price) : Util.tidyAmount(p.price) + " " + currencyLabel(), " · ×", p.maxUnits));
            sub.setTextColor(p.price.isEmpty() ? Design.RED : Design.DIM); sub.setTextSize(12f);
            tt.addView(sub);
            row.addView(tt);
            row.setOnClickListener(v -> push(buildProductEditor(p)));
            list.addView(row);
        }
        col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView add = button(Language.msg(l10n_mall_studio_ui_add_product), false);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.setMargins(dp(16), 0, dp(16), 0); add.setLayoutParams(al);
        add.setOnClickListener(v -> {
            if (totalListingCount() >= MAX_PRODUCTS) { toast(Language.msg(l10n_mall_studio_ui_cap, MAX_PRODUCTS)); return; }
            TypedProduct p = new TypedProduct();
            typedProducts.add(p);
            push(buildProductEditor(p));
        });
        col.addView(add);
    }

    /** Tab 1 — the NFT gallery (wallet NFTs + StateNFT collections). */
    private void buildNftsTab(LinearLayout col) {
        LinearLayout nh = new LinearLayout(this); nh.setOrientation(LinearLayout.HORIZONTAL); nh.setGravity(Gravity.CENTER_VERTICAL);
        nh.setPadding(dp(16), dp(8), dp(16), dp(4));
        TextView nl = new TextView(this);
        int walletCount = collections.size();
        for (Map.Entry<String, NftListing> e : listings.entrySet()) if (!e.getKey().contains("#")) walletCount++;
        Language.setText(nl, Language.upper(Language.msg(l10n_mall_studio_ui_wallet_count, walletCount)));
        nl.setTextColor(Design.DIM2); nl.setTextSize(11f); nl.setTypeface(null, Typeface.BOLD);
        nl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nh.addView(nl);
        TextView refresh = button(nftsLoading ? "…" : "↻", false);
        refresh.setPadding(dp(12), dp(6), dp(12), dp(6)); refresh.setMinWidth(dp(48)); refresh.setMinHeight(dp(48));
        Language.description(refresh, Language.msg(l10n_filez_refresh));
        refresh.setOnClickListener(v -> { if (paired) loadNfts(); else toast(Language.msg(l10n_mall_shop_connect_first)); });
        nh.addView(refresh);
        col.addView(nh);

        List<Object> cards = new ArrayList<>(collections.values());
        for (Map.Entry<String, NftListing> e : listings.entrySet())
            if (!e.getKey().contains("#")) cards.add(e.getValue());

        if (!paired) {
            col.addView(hint(Language.msg(l10n_mall_shop_node_unavailable)));
            col.addView(spacerWeight());
        } else if (nftsLoading && cards.isEmpty()) {
            col.addView(hint(Language.msg(l10n_mall_studio_ui_reading)));
            col.addView(spacerWeight());
        } else if (cards.isEmpty() && nftsLoaded) {
            col.addView(hint(Language.msg(l10n_mall_studio_ui_no_nfts)));
            col.addView(spacerWeight());
        } else {
            col.addView(hint(Language.msg(l10n_mall_studio_ui_nft_help)));
            RecyclerView rv = new RecyclerView(this);
            rv.setLayoutManager(new GridLayoutManager(this, 2));
            rv.setPadding(dp(10), dp(4), dp(10), dp(10));
            rv.setClipToPadding(false);
            rv.setAdapter(new GalleryAdapter(cards, true));
            col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
    }

    private Bitmap decodeB64(String b64) {
        try { byte[] b = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP); return android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length); }
        catch (Exception e) { return null; }
    }

    // ==== SCREEN 1b · Typed product editor ====

    private View buildProductEditor(final TypedProduct p) {
        LinearLayout col = column();
        col.setTag("product:" + System.identityHashCode(p));
        col.addView(header(Language.msg(l10n_mall_studio_ui_your_product), true));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(10), dp(16), dp(24));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        ImageView photo = new ImageView(this);
        photo.setTag(p);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setBackground(Design.roundBg(this, Design.SURFACE2, 14));
        photo.setClipToOutline(true);
        photo.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));
        if (!p.imageB64.isEmpty()) { Bitmap b = decodeB64(p.imageB64); if (b != null) photo.setImageBitmap(b); }
        photo.setOnClickListener(v -> { pickingFor = p; imagePicker.launch("image/*"); });
        body.addView(photo);
        TextView tap = new TextView(this);
        tap.setTag("photoHint:" + System.identityHashCode(p));
        Language.setText(tap, p.imageB64.isEmpty() ? Language.msg(l10n_mall_studio_ui_add_photo) : Language.msg(l10n_mall_studio_ui_change_photo));
        tap.setTextColor(Design.DIM2); tap.setTextSize(11f); tap.setGravity(Gravity.CENTER); tap.setPadding(0, dp(6), 0, 0);
        body.addView(tap);

        body.addView(sectionLabel(Language.msg(l10n_mall_studio_ui_name)));
        body.addView(field(p.name, Language.msg(l10n_mall_studio_ui_product_name), v -> p.name = v));
        body.addView(sectionLabel(Language.msg(l10n_mall_studio_ui_about)));
        EditText desc = field(p.description, Language.msg(l10n_mall_studio_ui_description), v -> p.description = v);
        desc.setMinLines(2); body.addView(desc);

        body.addView(sectionLabel(Language.msg(l10n_mall_studio_ui_price)));
        LinearLayout pr = new LinearLayout(this); pr.setOrientation(LinearLayout.HORIZONTAL); pr.setGravity(Gravity.CENTER_VERTICAL);
        EditText price = field(p.price, "0", v -> p.price = v);
        price.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        price.setLayoutParams(new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));
        pr.addView(price);
        TextView cur = new TextView(this); Language.setText(cur, "  " + currencyLabel()); cur.setTextColor(Design.DIM); cur.setTextSize(14f);
        pr.addView(cur);
        body.addView(pr);

        body.addView(sectionLabel(Language.msg(l10n_mall_studio_ui_stock)));
        EditText max = field(String.valueOf(p.maxUnits), "10", v -> { try { p.maxUnits = Math.max(1, Integer.parseInt(v.trim())); } catch (Exception e) { p.maxUnits = 10; } });
        max.setInputType(InputType.TYPE_CLASS_NUMBER);
        max.setLayoutParams(new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(max);

        TextView done = button(Language.msg(l10n_chat_done), true);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = dp(20); done.setLayoutParams(dl); done.setPadding(dp(16), dp(13), dp(16), dp(13));
        done.setOnClickListener(v -> {
            if (p.name.trim().isEmpty()) { toast(Language.msg(l10n_mall_studio_ui_need_name)); return; }
            try { if (new BigDecimal(p.price.trim()).signum() <= 0) { toast(Language.msg(l10n_mall_studio_ui_need_price)); return; } }
            catch (Exception e) { toast(Language.msg(l10n_mall_studio_ui_need_price)); return; }
            pop();
            refreshIfTag("gallery");
        });
        body.addView(done);

        TextView remove = button(Language.msg(l10n_mall_studio_ui_remove_product), false);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(8); remove.setLayoutParams(rl);
        remove.setTextColor(Design.RED);
        remove.setOnClickListener(v -> { typedProducts.remove(p); pop(); refreshIfTag("gallery"); });
        body.addView(remove);

        return col;
    }

    /** 2-col art grid over NftListing and StateCollection cards.
     *  galleryMode=true → studio cards (tap to list/edit); false → buyer-style preview. */
    private class GalleryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<Object> data;
        final boolean galleryMode;
        GalleryAdapter(List<? extends Object> d, boolean galleryMode) { this.data = new ArrayList<>(d); this.galleryMode = galleryMode; }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {
            return new RecyclerView.ViewHolder(nftCardShell()) {};
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
            Object item = data.get(pos);
            if (item instanceof StateCollection) bindCollectionCard((LinearLayout) h.itemView, (StateCollection) item);
            else if (item instanceof TypedProduct) bindTypedCard((LinearLayout) h.itemView, (TypedProduct) item);
            else bindNftCard((LinearLayout) h.itemView, (NftListing) item, galleryMode);
        }

        @Override public int getItemCount() { return data.size(); }
    }

    /** Collection card: icon art, name, "◈ N pieces · you hold M", listed count. Tap → piece grid. */
    private void bindCollectionCard(LinearLayout card, final StateCollection col) {
        card.removeAllViews();
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        int listed = 0;
        boolean bundled = false;
        for (NftListing l : listings.values()) {
            if (!l.listed || !col.tokenid.equals(l.tokenid)) continue;
            if (l.bundle) bundled = true;
            else if (l.stateIdx > 0) listed++;
        }
        inner.setBackground(cardBg(listed > 0 || bundled));
        inner.setPadding(dp(6), dp(6), dp(6), dp(10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(dp(6), dp(6), dp(6), dp(6));
        inner.setLayoutParams(ilp);

        ImageView art = squareImage();
        art.setImageBitmap(Identicon.forToken(col.tokenid, dp(160)));
        String icon = IconResolver.resolve(col.meta.icon);
        if (icon != null && !icon.isEmpty()) ImageLoader.loadOver(this, icon, art, null);
        inner.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView nm = new TextView(this);
        Language.setText(nm, col.meta.name); nm.setTextColor(Design.TEXT); nm.setTextSize(14f); nm.setTypeface(null, Typeface.BOLD);
        nm.setSingleLine(true); nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nm.setPadding(dp(4), dp(8), dp(4), 0);
        inner.addView(nm);

        TextView ed = new TextView(this);
        Language.setText(ed, Language.msg(l10n_mall_studio_ui_collection_held, col.meta.size, col.ownedCoins.size()));
        ed.setTextColor(Design.DIM); ed.setTextSize(11.5f); ed.setPadding(dp(4), dp(2), dp(4), 0);
        inner.addView(ed);

        TextView pr = new TextView(this);
        Language.setText(pr, col.coinsLoading && !col.coinsLoaded ? Language.msg(l10n_mall_studio_ui_reading_pieces)
                : (bundled ? Language.msg(l10n_mall_studio_ui_bundle_listed) : (listed > 0 ? Language.msg(l10n_mall_studio_ui_listed, listed) : Language.msg(l10n_mall_studio_ui_view_pieces))));
        pr.setTextColor(listed > 0 || bundled ? Design.ACCENT : Design.DIM2);
        if (listed > 0 || bundled) pr.setTypeface(null, Typeface.BOLD);
        pr.setTextSize(13f); pr.setPadding(dp(4), dp(2), dp(4), 0);
        inner.addView(pr);

        inner.setOnClickListener(v -> push(buildCollectionGrid(col)));
        card.addView(inner);
    }

    /** Card shell: square art on top, text block under. Rebuilt cheaply on bind. */
    private LinearLayout nftCardShell() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(lp);
        return card;
    }

    private void bindNftCard(LinearLayout card, final NftListing l, boolean galleryMode) {
        card.removeAllViews();
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setBackground(cardBg(galleryMode && l.listed));
        inner.setPadding(dp(6), dp(6), dp(6), dp(10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(dp(6), dp(6), dp(6), dp(6));
        inner.setLayoutParams(ilp);

        // square art
        FrameLayout artWrap = new FrameLayout(this);
        ImageView art = squareImage();
        art.setImageBitmap(Identicon.forToken(identiconKey(l), dp(160)));
        String artUrl = artUrlOf(l);
        if (!artUrl.isEmpty()) ImageLoader.loadOver(this, artUrl, art, null);
        artWrap.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (galleryMode && l.listed) {
            TextView tick = new TextView(this);
            Language.setText(tick, "✓"); tick.setTextColor(Design.ON_ACCENT); tick.setTextSize(13f); tick.setTypeface(null, Typeface.BOLD);
            tick.setGravity(Gravity.CENTER); tick.setBackground(Design.roundBg(this, Design.ACCENT, 12));
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.END);
            tl.setMargins(0, dp(6), dp(6), 0); tick.setLayoutParams(tl);
            artWrap.addView(tick);
        }
        inner.addView(artWrap);

        TextView nm = new TextView(this);
        Language.setText(nm, displayName(l)); nm.setTextColor(Design.TEXT); nm.setTextSize(14f); nm.setTypeface(null, Typeface.BOLD);
        nm.setSingleLine(true); nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nm.setPadding(dp(4), dp(8), dp(4), 0);
        inner.addView(nm);

        TextView ed = new TextView(this);
        Language.setText(ed, editionChip(l)); ed.setTextColor(Design.DIM); ed.setTextSize(11.5f);
        ed.setPadding(dp(4), dp(2), dp(4), 0);
        inner.addView(ed);

        TextView pr = new TextView(this);
        if (galleryMode) {
            if (l.listed && priced(l)) { Language.setText(pr, Util.tidyAmount(l.price) + " " + currencyLabel()); pr.setTextColor(Design.ACCENT); pr.setTypeface(null, Typeface.BOLD); }
            else if (l.listed) { Language.setText(pr, Language.msg(l10n_mall_studio_ui_listed_needs_price)); pr.setTextColor(Design.ACCENT); }
            else { Language.setText(pr, Language.msg(l10n_mall_studio_ui_unlisted)); pr.setTextColor(Design.DIM2); }
        } else {
            Language.setText(pr, Util.tidyAmount(l.price) + " " + currencyLabel());
            pr.setTextColor(Design.ACCENT); pr.setTypeface(null, Typeface.BOLD);
        }
        pr.setTextSize(13f); pr.setPadding(dp(4), dp(2), dp(4), 0);
        inner.addView(pr);

        if (galleryMode) {
            inner.setOnClickListener(v -> {
                if (!l.listed && totalListingCount() >= MAX_PRODUCTS) { toast(Language.msg(l10n_mall_studio_ui_cap, MAX_PRODUCTS)); return; }
                if (!l.listed) {
                    // listing an individual piece supersedes a complete-collection listing
                    if (l.stateIdx > 0) { StateCollection c = collections.get(l.tokenid); if (c != null) unlistBundle(c); }
                    l.listed = true;
                }
                push(buildListingEditor(l));
            });
        } else {
            inner.setOnClickListener(v -> showFullArt(l));
        }
        card.addView(inner);
    }

    /** Preview card for a typed product — the buyer-side look with the vendor's photo. */
    private void bindTypedCard(LinearLayout card, final TypedProduct p) {
        card.removeAllViews();
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setBackground(cardBg(false));
        inner.setPadding(dp(6), dp(6), dp(6), dp(10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(dp(6), dp(6), dp(6), dp(6));
        inner.setLayoutParams(ilp);

        ImageView art = squareImage();
        if (!p.imageB64.isEmpty()) { Bitmap b = decodeB64(p.imageB64); if (b != null) art.setImageBitmap(b); }
        inner.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView nm = new TextView(this);
        Language.setText(nm, p.name.isEmpty() ? Language.msg(l10n_mall_studio_ui_product) : p.name);
        nm.setTextColor(Design.TEXT); nm.setTextSize(14f); nm.setTypeface(null, Typeface.BOLD);
        nm.setSingleLine(true); nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nm.setPadding(dp(4), dp(8), dp(4), 0);
        inner.addView(nm);

        TextView ed = new TextView(this);
        Language.setText(ed, Language.msg(l10n_mall_studio_ui_available, p.maxUnits));
        ed.setTextColor(Design.DIM); ed.setTextSize(11.5f); ed.setPadding(dp(4), dp(2), dp(4), 0);
        inner.addView(ed);

        TextView pr = new TextView(this);
        Language.setText(pr, Util.tidyAmount(p.price) + " " + currencyLabel());
        pr.setTextColor(Design.ACCENT); pr.setTextSize(13f); pr.setTypeface(null, Typeface.BOLD);
        pr.setPadding(dp(4), dp(2), dp(4), 0);
        inner.addView(pr);

        card.addView(inner);
    }

    /** Rounded card background; accent stroke when listed. */
    private GradientDrawable cardBg(boolean listed) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Design.SURFACE);
        g.setCornerRadius(dp(12));
        if (listed) g.setStroke(dp(2), Design.ACCENT);
        return g;
    }

    /** ImageView measured square (height = width) — the marketplace-grid look. */
    private ImageView squareImage() {
        ImageView iv = new androidx.appcompat.widget.AppCompatImageView(this) {
            @Override protected void onMeasure(int w, int h) { super.onMeasure(w, w); }
        };
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Design.roundBg(this, Design.SURFACE2, 10));
        iv.setClipToOutline(true);
        return iv;
    }

    // ==== SCREEN 2 · Listing editor ====

    private View buildListingEditor(final NftListing l) {
        final boolean piece = l.stateIdx > 0;
        LinearLayout col = column();
        col.setTag("editor:" + identiconKey(l));
        col.addView(header(Language.msg(l10n_mall_studio_ui_price_listing), true));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(10), dp(16), dp(24));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // full-width art
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Design.roundBg(this, Design.SURFACE2, 14));
        art.setClipToOutline(true);
        art.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        art.setImageBitmap(Identicon.forToken(identiconKey(l), dp(220)));
        String artUrl = artUrlOf(l);
        if (!artUrl.isEmpty()) ImageLoader.loadOver(this, artUrl, art, null);
        art.setOnClickListener(v -> showFullArt(l));
        body.addView(art);

        LinearLayout meta = new LinearLayout(this); meta.setOrientation(LinearLayout.HORIZONTAL); meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, dp(10), 0, 0);
        TextView nm = new TextView(this);
        Language.setText(nm, piece ? l.snMeta.name + " #" + l.stateIdx : l.bal.meta.name);
        nm.setTextColor(Design.TEXT); nm.setTextSize(17f); nm.setTypeface(null, Typeface.BOLD);
        nm.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        meta.addView(nm);
        TextView ed = new TextView(this);
        Language.setText(ed, piece ? Language.msg(l10n_mall_studio_ui_edition, l.stateIdx, l.snMeta.size) : editionChip(l));
        ed.setTextColor(Design.DIM); ed.setTextSize(12.5f);
        meta.addView(ed);
        body.addView(meta);

        // ticker · tokenid · external link
        LinearLayout prov = new LinearLayout(this); prov.setOrientation(LinearLayout.HORIZONTAL); prov.setGravity(Gravity.CENTER_VERTICAL);
        TextView tid = new TextView(this);
        String tkr = !piece && l.bal.meta.ticker != null && !l.bal.meta.ticker.isEmpty() ? l.bal.meta.ticker + " · " : "";
        Language.setText(tid, tkr + Util.shorten(l.tokenid));
        tid.setTextColor(Design.DIM2); tid.setTextSize(11.5f);
        tid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        prov.addView(tid);
        final String ext = piece ? l.snMeta.externalUrl : l.bal.meta.externalUrl;
        if (ext != null && (ext.startsWith("http://") || ext.startsWith("https://"))) {
            TextView link = new TextView(this); Language.setText(link, "↗"); link.setTextColor(Design.ACCENT); link.setTextSize(14f);
            link.setPadding(dp(8), 0, 0, 0); link.setMinWidth(dp(48)); link.setMinHeight(dp(48)); link.setGravity(Gravity.CENTER);
            Language.description(link, Language.msg(l10n_wallet_website));
            link.setOnClickListener(v -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ext))); } catch (Exception ignored) {} });
            prov.addView(link);
        }
        body.addView(prov);

        body.addView(sectionLabel(Language.msg(l10n_mall_studio_ui_name)));
        body.addView(field(l.name, Language.msg(l10n_mall_studio_ui_listing_name), v -> l.name = v));
        body.addView(sectionLabel(Language.msg(l10n_mall_studio_ui_about)));
        EditText desc = field(l.description, Language.msg(l10n_mall_studio_ui_piece_description), v -> l.description = v);
        desc.setMinLines(2); body.addView(desc);

        body.addView(sectionLabel(Language.msg(l10n_mall_studio_ui_price)));
        LinearLayout pr = new LinearLayout(this); pr.setOrientation(LinearLayout.HORIZONTAL); pr.setGravity(Gravity.CENTER_VERTICAL);
        EditText price = field(l.price, "0", v -> l.price = v);
        price.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        price.setLayoutParams(new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));
        pr.addView(price);
        TextView cur = new TextView(this); Language.setText(cur, "  " + currencyLabel()); cur.setTextColor(Design.DIM); cur.setTextSize(14f);
        pr.addView(cur);
        body.addView(pr);

        // editions stepper (only meaningful for multi-edition holdings; a piece is always unique)
        final BigInteger held = piece ? BigInteger.ONE : wholeUnits(l.bal.sendable);
        if (held.compareTo(BigInteger.ONE) > 0) {
            body.addView(sectionLabel(Language.msg(l10n_mall_studio_ui_editions_sale)));
            LinearLayout st = new LinearLayout(this); st.setOrientation(LinearLayout.HORIZONTAL); st.setGravity(Gravity.CENTER_VERTICAL);
            final TextView qty = new TextView(this);
            qty.setTextColor(Design.TEXT); qty.setTextSize(16f); qty.setGravity(Gravity.CENTER); qty.setMinWidth(dp(44));
            final Runnable render = () -> Language.setText(qty, clampUnits(l, held).toString());
            TextView minus = roundBtn("–"); TextView plus = roundBtn("+");
            minus.setOnClickListener(v -> { l.units = clampUnits(l, held).subtract(BigInteger.ONE).max(BigInteger.ONE).toString(); render.run(); });
            plus.setOnClickListener(v -> { l.units = clampUnits(l, held).add(BigInteger.ONE).min(held).toString(); render.run(); });
            render.run();
            st.addView(minus); st.addView(qty); st.addView(plus);
            TextView of = new TextView(this); Language.setText(of, Language.msg(l10n_mall_studio_ui_held, held.toString())); of.setTextColor(Design.DIM); of.setTextSize(13f);
            st.addView(of);
            body.addView(st);
        }

        TextView done = button(Language.msg(l10n_mall_studio_ui_done_listed), true);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = dp(20); done.setLayoutParams(dl); done.setPadding(dp(16), dp(13), dp(16), dp(13));
        done.setOnClickListener(v -> {
            if (!priced(l)) { toast(Language.msg(l10n_mall_studio_ui_need_price)); return; }
            l.listed = true;
            pop();
            refreshIfTag("gallery");
            refreshIfCollection(l.tokenid);
        });
        body.addView(done);

        TextView remove = button(Language.msg(l10n_mall_studio_ui_remove_listing), false);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(8); remove.setLayoutParams(rl);
        remove.setTextColor(Design.RED);
        remove.setOnClickListener(v -> { l.listed = false; pop(); refreshIfTag("gallery"); refreshIfCollection(l.tokenid); });
        body.addView(remove);

        return col;
    }

    private BigInteger clampUnits(NftListing l, BigInteger held) {
        BigInteger u = wholeUnits(l.units);
        if (u.signum() <= 0) u = held;
        return u.min(held).max(BigInteger.ONE);
    }

    private void showFullArt(NftListing l) {
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setImageBitmap(Identicon.forToken(identiconKey(l), dp(320)));
        String artUrl = artUrlOf(l);
        if (!artUrl.isEmpty()) ImageLoader.loadFull(this, artUrl, iv, 0);
        ScrollView sv = new ScrollView(this);
        sv.addView(iv);
        languageDialog().setView(sv).setPositiveButton(Language.msg(l10n_language_close), null).show();
    }

    // ==== SCREEN 2b · Collection piece grid ====

    private List<NftListing> piecesOf(StateCollection col) {
        List<NftListing> out = new ArrayList<>();
        for (Map.Entry<String, NftListing> e : listings.entrySet())
            if (e.getKey().startsWith(col.tokenid + "#") && e.getValue().stateIdx > 0) out.add(e.getValue());
        out.sort((a, b) -> Integer.compare(a.stateIdx, b.stateIdx));
        return out;
    }

    private NftListing bundleOf(StateCollection col) { return listings.get(col.tokenid + "#ALL"); }

    private View buildCollectionGrid(final StateCollection col) {
        LinearLayout root = column();
        root.setTag("collection:" + col.tokenid);
        root.addView(header(col.meta.name, true));

        List<NftListing> pieces = piecesOf(col);
        NftListing bundle = bundleOf(col);
        int listed = 0;
        for (NftListing l : pieces) if (l.listed) listed++;
        boolean bundleListed = bundle != null && bundle.listed;

        // Collection identity row: the standard top-side token icon next to the stats — the pieces
        // below carry their own stamped artwork.
        LinearLayout idRow = new LinearLayout(this); idRow.setOrientation(LinearLayout.HORIZONTAL); idRow.setGravity(Gravity.CENTER_VERTICAL);
        idRow.setPadding(dp(16), dp(8), dp(16), 0);
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setBackground(Design.roundBg(this, Design.SURFACE2, 10)); icon.setClipToOutline(true);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(54), dp(54)); il.rightMargin = dp(10); icon.setLayoutParams(il);
        icon.setImageBitmap(Identicon.forToken(col.tokenid, dp(54)));
        String iconUrl = IconResolver.resolve(col.meta.icon);
        if (iconUrl != null && !iconUrl.isEmpty()) ImageLoader.loadOver(this, iconUrl, icon, null);
        idRow.addView(icon);
        LinearLayout stats = new LinearLayout(this); stats.setOrientation(LinearLayout.VERTICAL);
        TextView sub = new TextView(this);
        Language.setText(sub, Language.msg(l10n_mall_studio_ui_pieces_held, pieces.size(), col.meta.size));
        sub.setTextColor(Design.TEXT); sub.setTextSize(13f);
        stats.addView(sub);
        TextView sub2 = new TextView(this);
        Language.setText(sub2, bundleListed ? Language.msg(l10n_mall_studio_ui_bundle_price, Util.tidyAmount(bundle.price), currencyLabel())
                : Language.msg(l10n_mall_studio_ui_slots, listed, totalListingCount(), MAX_PRODUCTS));
        sub2.setTextColor(bundleListed || listed > 0 ? Design.ACCENT : Design.DIM); sub2.setTextSize(12f);
        stats.addView(sub2);
        idRow.addView(stats, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(idRow);

        if (col.unstampedCount > 0) root.addView(hint(Language.msg(l10n_mall_studio_ui_unstamped, col.unstampedCount)));
        if (col.legacy) root.addView(hint(Language.msg(l10n_mall_studio_ui_legacy)));
        if (col.tooLong) root.addView(hint(Language.msg(l10n_mall_studio_ui_coin_list_large)));

        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(16), dp(8), dp(16), 0);
        TextView priceAll = button(Language.msg(l10n_mall_studio_ui_price_each), false);
        priceAll.setOnClickListener(v -> priceAllDialog(col));
        LinearLayout.LayoutParams palp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        palp.rightMargin = dp(8); priceAll.setLayoutParams(palp);
        actions.addView(priceAll);
        TextView sellAll = button(bundleListed ? Language.msg(l10n_mall_studio_ui_edit_bundle) : Language.msg(l10n_mall_studio_ui_sell_bundle), bundleListed);
        sellAll.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sellAll.setOnClickListener(v -> bundleDialog(col));
        actions.addView(sellAll);
        root.addView(actions);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.setPadding(dp(10), dp(4), dp(10), dp(10));
        rv.setClipToPadding(false);
        rv.setAdapter(new GalleryAdapter(pieces, true));
        root.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        int totalListed = listed + (bundleListed ? 1 : 0);
        TextView back = button(totalListed == 0 ? Language.msg(l10n_mall_studio_ui_nothing_listed) : Language.msg(l10n_mall_studio_ui_done_summary, bundleListed ? Language.msg(l10n_mall_studio_ui_bundle_listed) : Language.msg(l10n_mall_studio_ui_listed, listed)), totalListed > 0);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.setMargins(dp(16), dp(8), dp(16), dp(14)); back.setLayoutParams(bl);
        back.setPadding(dp(16), dp(13), dp(16), dp(13));
        back.setOnClickListener(v -> pop());
        root.addView(back);
        return root;
    }

    /** One price for every listable piece you hold, in edition order, up to the shop cap.
     *  Mutually exclusive with the complete-collection bundle (double-selling guard). */
    private void priceAllDialog(final StateCollection col) {
        final EditText in = new EditText(this);
        Language.hint(in, Language.msg(l10n_mall_studio_ui_price_per_piece, currencyLabel()));
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        // Native price dialogs inherit matching text and hint colours from their theme.
        languageDialog()
                .setTitle(Language.msg(l10n_mall_studio_ui_price_owned))
                .setView(in)
                .setPositiveButton(Language.msg(l10n_mall_studio_ui_list_them), (d, w) -> {
                    String p = in.getText().toString().trim();
                    try { if (new BigDecimal(p).signum() <= 0) { toast(Language.msg(l10n_mall_studio_ui_positive_price)); return; } }
                    catch (Exception e) { toast(Language.msg(l10n_mall_studio_ui_positive_price)); return; }
                    unlistBundle(col);
                    int added = 0; boolean capped = false;
                    for (NftListing l : piecesOf(col)) {
                        if (l.listed) { l.price = p; continue; }
                        if (totalListingCount() >= MAX_PRODUCTS) { capped = true; break; }
                        l.listed = true; l.price = p; added++;
                    }
                    toast(capped ? Language.msg(l10n_mall_studio_ui_listed_capped, added, MAX_PRODUCTS) : Language.msg(l10n_mall_studio_ui_listed_price, added, p, currencyLabel()));
                    refreshIfCollection(col.tokenid);
                    refreshIfTag("gallery");
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private void unlistBundle(StateCollection col) {
        NftListing b = bundleOf(col);
        if (b != null && b.listed) { b.listed = false; toast(Language.msg(l10n_mall_studio_ui_bundle_removed)); }
    }
    private void unlistPieces(StateCollection col) {
        int n = 0;
        for (NftListing l : piecesOf(col)) if (l.listed) { l.listed = false; n++; }
        if (n > 0) toast(Language.msg(l10n_mall_studio_ui_pieces_removed, n));
    }

    /** Sell every piece you hold as ONE product. The Inbox delivers them piece by piece — each is
     *  its own on-chain transaction. Mutually exclusive with per-piece listings. */
    private void bundleDialog(final StateCollection col) {
        if (col.ownedCoins.isEmpty()) { toast(Language.msg(l10n_mall_studio_ui_no_pieces)); return; }
        NftListing existing = bundleOf(col);
        final EditText in = new EditText(this);
        Language.hint(in, Language.msg(l10n_mall_studio_ui_bundle_price_hint, col.ownedCoins.size(), currencyLabel()));
        if (existing != null && !existing.price.isEmpty()) Language.setText(in, existing.price);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        // Native price dialogs inherit matching text and hint colours from their theme.
        AlertDialog.Builder b = languageDialog()
                .setTitle(Language.msg(l10n_mall_studio_ui_sell_complete))
                .setMessage(Language.msg(l10n_mall_studio_ui_bundle_help, col.ownedCoins.size()))
                .setView(in)
                .setPositiveButton(existing != null && existing.listed ? Language.msg(l10n_mall_studio_ui_update_price) : Language.msg(l10n_mall_studio_ui_list_it), (d, w) -> {
                    String p = in.getText().toString().trim();
                    try { if (new BigDecimal(p).signum() <= 0) { toast(Language.msg(l10n_mall_studio_ui_positive_price)); return; } }
                    catch (Exception e) { toast(Language.msg(l10n_mall_studio_ui_positive_price)); return; }
                    unlistPieces(col);
                    NftListing l = listings.get(col.tokenid + "#ALL");
                    if (l == null) {
                        if (totalListingCount() >= MAX_PRODUCTS) { toast(Language.msg(l10n_mall_studio_ui_full, MAX_PRODUCTS)); return; }
                        l = new NftListing();
                        l.name = col.meta.name + " — complete collection";
                        l.description = "Every piece the seller holds, delivered to your wallet one by one.";
                        l.units = "1";
                        listings.put(col.tokenid + "#ALL", l);
                    }
                    String previousArt = artUrlOf(l);
                    l.tokenid = col.tokenid; l.bundle = true; l.snMeta = col.meta;
                    l.listed = true; l.price = p;
                    refreshIfCollection(col.tokenid);
                    refreshIfTag("gallery");
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null);
        if (existing != null && existing.listed) {
            b.setNeutralButton(Language.msg(l10n_mall_studio_ui_unlist), (d, w) -> {
                unlistBundle(col);
                refreshIfCollection(col.tokenid);
                refreshIfTag("gallery");
            });
        }
        b.show();
    }

    // ==== SCREEN 3 · Shop details ====

    private View buildShopDetails() {
        LinearLayout col = column();
        col.setTag("shop");
        col.addView(header(Language.msg(l10n_mall_studio_ui_your_shop), true));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(10), dp(16), dp(24));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        body.addView(sectionLabel(Language.msg(l10n_mall_studio_ui_shop_name)));
        body.addView(field(shopName, Language.msg(l10n_mall_studio_ui_shop_hint), s -> shopName = s));

        body.addView(sectionLabel(Language.msg(l10n_mall_studio_ui_about)));
        EditText ab = field(about, Language.msg(l10n_mall_studio_ui_about_hint), s -> about = s);
        ab.setMinLines(2); body.addView(ab);

        body.addView(sectionLabel(Language.msg(l10n_mall_studio_ui_pays)));
        LinearLayout cur = new LinearLayout(this); cur.setOrientation(LinearLayout.HORIZONTAL);
        cur.addView(seg("Minima", currency.equals("Minima"), () -> { currency = "Minima"; tokenid = CommsTransport.MINIMA; refreshTop(buildShopDetails()); }));
        cur.addView(seg("mxUSDT", currency.equals("USDT"), () -> { currency = "USDT"; tokenid = CommsTransport.USDT; refreshTop(buildShopDetails()); }));
        body.addView(cur);

        // Shipping only matters for typed goods — NFT-only shops deliver on-chain.
        if (!typedProducts.isEmpty()) {
            body.addView(sectionLabel(Language.msg(l10n_mall_studio_ui_shipping)));
            for (final Catalog.Shipping s : shipping) {
                LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
                EditText label = field(s.label, Language.msg(l10n_mall_studio_ui_label), v -> s.label = v);
                label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                EditText fee = field(s.fee, "0", v -> s.fee = v);
                fee.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT);
                fl.leftMargin = dp(8); fee.setLayoutParams(fl);
                row.addView(label); row.addView(fee); body.addView(row);
            }
        }

        int nNft = listedItems().size(), nTyped = typedProducts.size();
        TextView info = new TextView(this);
        CharSequence parts = Language.join(nTyped > 0 ? Language.msg(l10n_mall_studio_ui_product_count, nTyped) : "", nTyped > 0 && nNft > 0 ? " + " : "", nNft > 0 ? Language.msg(l10n_mall_studio_ui_nft_count, nNft) : "");
        Language.setText(info, parts);
        info.setTextColor(Design.DIM); info.setTextSize(12.5f); info.setPadding(0, dp(18), 0, 0);
        body.addView(info);

        TextView preview = button(Language.msg(l10n_mall_studio_ui_preview), true);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = dp(16); preview.setLayoutParams(pl); preview.setPadding(dp(16), dp(13), dp(16), dp(13));
        preview.setOnClickListener(v -> {
            if (shopName.trim().isEmpty()) { toast(Language.msg(l10n_mall_studio_ui_need_shop_name)); return; }
            push(buildPreview());
        });
        body.addView(preview);

        return col;
    }

    // ==== SCREEN 4 · Preview (buyer's view) + export ====

    private View buildPreview() {
        LinearLayout col = column();
        col.setTag("preview");
        col.addView(header(Language.msg(l10n_mall_studio_ui_buyer_preview), true));

        // collection header, exactly the miniMall treatment
        LinearLayout ch = new LinearLayout(this); ch.setOrientation(LinearLayout.VERTICAL);
        ch.setPadding(dp(16), dp(10), dp(16), dp(4));
        TextView sn = new TextView(this); Language.setText(sn, shopName.trim());
        sn.setTextColor(Design.TEXT); sn.setTextSize(19f); sn.setTypeface(null, Typeface.BOLD);
        ch.addView(sn);
        TextView sub = new TextView(this);
        Language.setText(sub, typedProducts.isEmpty() ? Language.msg(l10n_mall_shop_nft_pays, currencyLabel()) : Language.msg(l10n_mall_shop_pays, currencyLabel()));
        sub.setTextColor(Design.ACCENT); sub.setTextSize(11.5f); sub.setTypeface(null, Typeface.BOLD);
        sub.setPadding(0, dp(2), 0, 0);
        ch.addView(sub);
        if (!about.trim().isEmpty()) {
            TextView abt = new TextView(this); Language.setText(abt, about.trim());
            abt.setTextColor(Design.DIM); abt.setTextSize(13f); abt.setPadding(0, dp(4), 0, 0);
            ch.addView(abt);
        }
        col.addView(ch);

        List<Object> previewCards = new ArrayList<>(typedProducts);
        previewCards.addAll(listedItems());
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.setPadding(dp(10), dp(4), dp(10), dp(10));
        rv.setClipToPadding(false);
        rv.setAdapter(new GalleryAdapter(previewCards, false));
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView export = button(exporting ? Language.msg(l10n_mall_studio_ui_preparing) : Language.msg(l10n_mall_studio_ui_export), true);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        el.setMargins(dp(16), dp(8), dp(16), dp(14)); export.setLayoutParams(el);
        export.setPadding(dp(16), dp(13), dp(16), dp(13));
        export.setTag("exportShop");
        export.setOnClickListener(v -> exportShop());
        col.addView(export);

        return col;
    }

    // ---- export (.shop schema v3) ----

    private void exportShop() {
        if (exporting) return;
        if (shopName.trim().isEmpty()) { toast(Language.msg(l10n_mall_studio_ui_need_shop_name)); return; }
        if (!cardReady()) { toast(Language.msg(l10n_mall_studio_ui_need_key)); return; }
        final List<NftListing> sel = listedItems();
        if (sel.isEmpty() && typedProducts.isEmpty()) { toast(Language.msg(l10n_mall_studio_ui_need_listing)); return; }
        for (TypedProduct p : typedProducts) {
            if (p.name.trim().isEmpty()) { toast(Language.msg(l10n_mall_studio_ui_unnamed_error)); return; }
            try { if (new BigDecimal(p.price.trim()).signum() <= 0) { toast(Language.msg(l10n_mall_studio_ui_price_for, p.name.trim())); return; } }
            catch (Exception e) { toast(Language.msg(l10n_mall_studio_ui_price_for, p.name.trim())); return; }
        }
        if (!sel.isEmpty() && (!paired || !walletVerified || nftsLoading)) {
            toast(Language.msg(l10n_mall_studio_ui_refresh_export)); return;
        }
        for (NftListing l : sel) {
            StateCollection collection = collections.get(l.tokenid);
            if (!tokenRecords.containsKey(l.tokenid) || fetchingRecords.contains(l.tokenid)
                    || ((l.bundle || l.stateIdx > 0) && (collection == null
                        || !collection.coinsVerified || collection.coinsLoading))) {
                toast(Language.msg(l10n_mall_studio_ui_refresh_export)); return;
            }
            if (!priced(l)) { toast(Language.msg(l10n_mall_studio_ui_price_for, displayName(l))); return; }
            // A piece must still be in the wallet, stamped and cleanly replayable at export time.
            if (l.stateIdx > 0 && (l.coinRaw == null || StateNft.stamped(l.coinRaw) == null || !StateNft.replayableState(l.coinRaw))) {
                toast(Language.msg(l10n_mall_studio_ui_not_sellable, displayName(l))); return;
            }
            if (l.bundle) {
                StateCollection c = collections.get(l.tokenid);
                if (c == null || c.ownedCoins.isEmpty()) { toast(Language.msg(l10n_mall_studio_ui_no_pieces_left, displayName(l))); return; }
            }
        }
        final ShopExport snapshot;
        try { snapshot = captureExport(sel); }
        catch (ArithmeticException unsupported) { toast(Language.msg(l10n_mall_studio_ui_count_limit)); return; }
        catch (Exception e) { toast(Language.msg(l10n_mall_inbox_export_error, StudioMessages.error(e))); return; }
        exporting = true;
        refreshTop(buildPreview());
        io.execute(() -> {
            try {
                File f = snapshot.write(getCacheDir(), MainActivity::resolveExportImage);
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".merchstudio.fileprovider", f);
                ui.post(() -> {
                    finishExport();
                    if (isFinishing() || isDestroyed()) return;
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/octet-stream");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, snapshot.name + " — miniMall shop");
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(Intent.createChooser(send, Language.msg(l10n_mall_studio_ui_share))); }
                    catch (RuntimeException e) { toast(Language.msg(l10n_mall_inbox_export_error, StudioMessages.error(e))); }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    finishExport();
                    if (!isFinishing() && !isDestroyed()) toast(Language.msg(l10n_mall_inbox_export_error, StudioMessages.error(e)));
                });
            }
        });
    }

    /** Completion updates only the export controls, preserving any current editor and cursor. */
    private void finishExport() {
        exporting = false;
        if (isFinishing() || isDestroyed()) return;
        for (View page : stack) {
            TextView button = page.findViewWithTag("exportShop");
            if (button != null) Language.setText(button, Language.msg(l10n_mall_studio_ui_export));
        }
    }

    /** Called on the UI thread. All prices, shipping, metadata and image sources become literals. */
    private ShopExport captureExport(List<NftListing> sel) throws Exception {
        List<ShopExport.Image> images = new ArrayList<>();
        JSONObject o = new JSONObject();
        o.put("schema", 3);
        if (typedProducts.isEmpty()) o.put("shopType", "nft");   // mixed shops render as product lists
        o.put("shopName", shopName.trim()); o.put("shopId", slug(shopName));
        if (!about.trim().isEmpty()) o.put("about", about.trim());
        o.put("vendorPublicId", vendorPublicId); o.put("vendorAddress", vendorAddress);
        o.put("currency", currency); o.put("tokenid", tokenid);
        JSONArray sh = new JSONArray();
        if (typedProducts.isEmpty()) {
            // NFT-only: single compat digital row (older miniMall builds need one)
            JSONObject so = new JSONObject();
            so.put("id", "digital"); so.put("label", "Digital / on-chain delivery"); so.put("fee", "0");
            sh.put(so);
        } else {
            for (Catalog.Shipping s : shipping) {
                JSONObject so = new JSONObject();
                so.put("id", s.id); so.put("label", s.label);
                so.put("fee", s.fee == null || s.fee.isEmpty() ? "0" : s.fee);
                sh.put(so);
            }
        }
        o.put("shipping", sh);
        JSONArray ps = new JSONArray();
        int i = 0;
        for (TypedProduct p : typedProducts) {
            JSONObject po = new JSONObject();
            po.put("id", "p" + (i++));
            po.put("name", p.name.trim());
            po.put("description", p.description == null ? "" : p.description.trim());
            po.put("mode", "units"); po.put("price", p.price.trim());
            po.put("maxUnits", Math.max(1, p.maxUnits));
            po.put("image", p.imageB64 == null ? "" : p.imageB64);
            ps.put(po);
        }
        for (NftListing l : sel) {

            JSONObject po = new JSONObject();
            po.put("id", "p" + (i++));
            po.put("name", displayName(l));
            po.put("description", l.description == null ? "" : l.description.trim());
            po.put("mode", "units"); po.put("price", l.price.trim());
            po.put("image", l.cachedB64);
            if (l.cachedB64.isEmpty()) images.add(new ShopExport.Image("products", ps.length(), "image",
                    artUrlOf(l), identiconKey(l), 900, 512, 90000));
            po.put("nftTokenId", l.tokenid);
            if (l.bundle) {
                // Complete collection: every piece the seller holds, delivered one by one
                StateCollection c = collections.get(l.tokenid);
                po.put("maxUnits", 1);
                po.put("nftBundle", 1);
                po.put("nftPieces", c == null ? 0 : c.ownedCoins.size());
                po.put("nftCollection", l.snMeta.name);
                po.put("nftSupply", ShopExport.count(BigInteger.valueOf(l.snMeta.size)));
                String bExt = l.snMeta.externalUrl;
                if (bExt != null && (bExt.startsWith("http://") || bExt.startsWith("https://"))) po.put("nftExternalUrl", bExt);
            } else if (l.stateIdx > 0) {
                // StateNFT piece: unique, identified on-chain by state port 0
                po.put("maxUnits", 1);
                po.put("nftStateIdx", l.stateIdx);
                po.put("nftCollection", l.snMeta.name);
                po.put("nftSupply", ShopExport.count(BigInteger.valueOf(l.snMeta.size)));
                String pExt = l.snMeta.externalUrl;
                if (pExt != null && (pExt.startsWith("http://") || pExt.startsWith("https://"))) po.put("nftExternalUrl", pExt);
            } else {
                BigInteger held = wholeUnits(l.bal.sendable);
                po.put("maxUnits", ShopExport.count(clampUnits(l, held)));
                po.put("nftSupply", ShopExport.count(wholeUnits(l.bal.total)));
                String tkr = l.bal.meta.ticker;
                if (tkr != null && !tkr.isEmpty()) po.put("nftTicker", tkr);
                String ext = l.bal.meta.externalUrl;
                if (ext != null && (ext.startsWith("http://") || ext.startsWith("https://"))) po.put("nftExternalUrl", ext);
            }
            ps.put(po);
        }
        o.put("products", ps);

        // Collection directory: name/size/top-side icon per referenced collection, so the
        // buyer app can show the standard token icon alongside the stamped piece art.
        java.util.LinkedHashSet<String> colIds = new java.util.LinkedHashSet<>();
        for (NftListing l : sel) if (l.snMeta != null && (l.stateIdx > 0 || l.bundle)) colIds.add(l.tokenid);
        if (!colIds.isEmpty()) {
            JSONArray cols = new JSONArray();
            for (String tid : colIds) {
                StateCollection c = collections.get(tid);
                if (c == null) continue;
                JSONObject co = new JSONObject();
                co.put("tokenid", tid);
                co.put("name", c.meta.name);
                co.put("size", ShopExport.count(BigInteger.valueOf(c.meta.size)));
                images.add(new ShopExport.Image("nftCollections", cols.length(), "icon",
                        IconResolver.resolve(c.meta.icon), c.tokenid, 320, 256, 24000));
                cols.put(co);
            }
            o.put("nftCollections", cols);
        }


        return new ShopExport(o, slug(shopName), images);
    }

    /** Same image resolution, fallbacks and byte budgets as the original Studio exporter. */
    private static String resolveExportImage(ShopExport.Image image) {
        Bitmap bmp = null;
        if (image.url != null && !image.url.isEmpty()) bmp = ImageLoader.decodeSync(image.url, image.decodeSize);
        if (bmp == null) bmp = Identicon.forToken(image.identity, image.fallbackSize);
        byte[] jpeg = Images.compressToFit(bmp, image.budget);
        return jpeg == null ? "" : android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
    }

    // ---- helpers ----

    private static String slug(String s) {
        String r = (s == null ? "shop" : s).replaceAll("[^a-zA-Z0-9]+", "-").replaceAll("^-+|-+$", "");
        return r.isEmpty() ? "shop" : r;
    }
    private int dp(int v) { return Design.dp(this, v); }
    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return c;
    }
    private LinearLayout header(CharSequence title, boolean back) {
        LinearLayout h = new LinearLayout(this); h.setOrientation(LinearLayout.HORIZONTAL); h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(Design.SURFACE); h.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (back) {
            TextView bk = new TextView(this); Language.setText(bk, "‹"); bk.setTextColor(Design.TEXT); bk.setTextSize(22f);
            bk.setMinWidth(dp(48)); bk.setMinHeight(dp(48)); bk.setGravity(Gravity.CENTER);
            Language.description(bk, Language.msg(l10n_explorer_back));
            bk.setOnClickListener(v -> pop());
            h.addView(bk);
        }
        TextView t = new TextView(this); Language.setText(t, title); t.setTextColor(Design.TEXT); t.setTextSize(18f); t.setTypeface(null, Typeface.BOLD);
        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); return h;
    }
    private TextView sectionLabel(CharSequence s) {
        TextView t = new TextView(this); Language.setText(t, Language.upper(s)); t.setTextColor(Design.DIM2); t.setTextSize(11f);
        t.setTypeface(null, Typeface.BOLD); t.setPadding(0, dp(16), 0, dp(6)); return t;
    }
    private TextView hint(CharSequence s) {
        TextView t = new TextView(this); Language.setText(t, s); t.setTextColor(Design.DIM); t.setTextSize(12.5f);
        t.setPadding(dp(16), 0, dp(16), dp(4)); return t;
    }
    private View spacerWeight() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f));
        return v;
    }
    private EditText field(String value, CharSequence hintText, java.util.function.Consumer<String> onChange) {
        EditText e = new EditText(this); Language.setText(e, value == null ? "" : value); Language.hint(e, hintText); e.setHintTextColor(Design.DIM2);
        e.setTextColor(Design.TEXT); e.setTextSize(14f); e.setBackground(Design.roundBg(this, Design.SURFACE2, 10));
        e.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.topMargin = dp(4); e.setLayoutParams(lp);
        e.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) { onChange.accept(s.toString()); }
        });
        return e;
    }
    private TextView seg(CharSequence text, boolean on, Runnable onClick) {
        TextView b = new TextView(this); Language.setText(b, text); b.setGravity(Gravity.CENTER); b.setTextSize(14f);
        b.setTextColor(on ? Design.ON_ACCENT : Design.DIM); b.setBackground(Design.roundBg(this, on ? Design.ACCENT : Design.SURFACE2, 10));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); lp.rightMargin = dp(8); b.setLayoutParams(lp);
        b.setOnClickListener(v -> onClick.run()); return b;
    }
    private TextView button(CharSequence text, boolean active) {
        TextView b = new TextView(this); Language.setText(b, text); b.setTextSize(14f); b.setGravity(Gravity.CENTER);
        b.setTextColor(active ? Design.ON_ACCENT : Design.TEXT); b.setBackground(Design.roundBg(this, active ? Design.ACCENT : Design.SURFACE2, 10));
        b.setPadding(dp(16), dp(11), dp(16), dp(11)); return b;
    }
    private TextView roundBtn(String s) {
        TextView b = new TextView(this); Language.setText(b, s); b.setGravity(Gravity.CENTER); b.setTextSize(18f);
        b.setTextColor(Design.TEXT); b.setBackground(Design.roundBg(this, Design.SURFACE2, 8));
        Language.description(b, "+".equals(s) ? Language.msg(l10n_mall_shop_plus) : Language.msg(l10n_mall_shop_minus));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.rightMargin = dp(4); lp.leftMargin = dp(4); b.setLayoutParams(lp);
        return b;
    }
    private void toast(CharSequence s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
