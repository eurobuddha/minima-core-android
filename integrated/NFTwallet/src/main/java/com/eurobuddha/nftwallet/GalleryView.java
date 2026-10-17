package com.eurobuddha.nftwallet;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Gallery tab — the NFT-first view of the wallet: a two-column grid of every NFT you hold.
 *
 *  - Regular NFTs (decimals-0 tokens) are one card per token.
 *  - StateNFT collections collapse to ONE card showing the collection icon and how many of the
 *    edition you hold. Tapping it drills into that collection, where the grid becomes one card per
 *    owned item with its sealed index and its own image (embedded state art beats the URL base).
 *    A 60-item collection used to bury every other NFT you owned under 60 near-identical tiles.
 *  - Collected / Favourites tabs (long-press or ♥ in detail to favourite), search by name,
 *    creator or tokenid, and a deep detail dialog with Send / Transfer / Bury.
 *
 * Favourites are per ITEM, so that tab always lists items directly — grouping there would hide the
 * one thing the user favourited behind a card they'd have to open.
 */
public class GalleryView extends BaseView {

    private static final String PREFS = "nftwallet_gallery";
    private static final String KEY_FAVS = "favs";

    private final LinearLayout header;
    private final RecyclerView grid;
    private final GalleryAdapter adapter = new GalleryAdapter();

    private EditText search;
    private TextView tabCollected, tabFavs, emptyNote;
    private boolean showFavs = false;

    /** tokenid of the collection we've drilled into, or null at the top level. */
    private String openCollection = null;
    private String openCollectionName = "";
    private CharSequence openCollectionDisplayName = "";

    /** tokenid → token record (script + meta) from `tokens tokenid:`; null value = fetch in flight. */
    private final Map<String, JSONObject> tokenRecords = new HashMap<>();
    private final Set<String> fetching = new HashSet<>();

    private final List<GItem> items = new ArrayList<>();

    /** Cover-hero card for the collection we're drilled into (full-span header row), else null. */
    private GItem heroCard;

    public GalleryView(MainActivity a) {
        super(a, R.layout.pm_nftwallet_view_gallery);
        header = find(R.id.pm_nftwallet_galleryHeader);
        grid = find(R.id.pm_nftwallet_galleryGrid);
        GridLayoutManager glm = new GridLayoutManager(a, 2);
        // The collection cover hero is a full-span header row above the item grid.
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                GridLayoutManager g = (GridLayoutManager) grid.getLayoutManager();
                return adapter.hasHeader() && position == 0 && g != null ? g.getSpanCount() : 1;
            }
        });
        grid.setLayoutManager(glm);
        // Adaptive density: ~190dp per column (2 on a phone, 3-4 on a Fold / landscape).
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            int wDp = (int) ((r - l) / a.getResources().getDisplayMetrics().density);
            int cols = Math.max(2, wDp / 190);
            GridLayoutManager g = (GridLayoutManager) grid.getLayoutManager();
            if (g != null && g.getSpanCount() != cols) g.setSpanCount(cols);
        });
        grid.setAdapter(adapter);
        buildHeader();
    }

    private void buildHeader() {
        root.setBackgroundColor(Design.bg());

        LinearLayout tabs = new LinearLayout(act);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        // Either tab returns you to the top level: staying inside a collection while switching to
        // Favourites would show a filtered subset with no visible reason for what's missing.
        tabCollected = tabBtn(Language.msg(l10n_nft_gallery_collected), v -> { showFavs = false; closeCollection(); });
        tabFavs = tabBtn(Language.msg(l10n_nft_gallery_favourites), v -> { showFavs = true; closeCollection(); });
        tabs.addView(tabCollected, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabFavs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(tabs);

        search = new EditText(act);
        Language.hint(search, Language.msg(l10n_nft_gallery_search));
        search.setHintTextColor(Design.dim());
        search.setTextColor(Design.text());
        search.setTextSize(13f);
        search.setBackgroundColor(Design.surface2());
        search.setPadding(dp(10), dp(9), dp(10), dp(9));
        search.setMaxLines(1);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        slp.bottomMargin = dp(8);
        search.setLayoutParams(slp);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { rebuild(); }
        });
        header.addView(search);

        emptyNote = new TextView(act);
        emptyNote.setTextColor(Design.dim());
        emptyNote.setTextSize(13f);
        emptyNote.setGravity(Gravity.CENTER);
        emptyNote.setPadding(0, dp(30), 0, 0);
        emptyNote.setVisibility(View.GONE);
        header.addView(emptyNote);
    }

    private TextView tabBtn(CharSequence label, View.OnClickListener click) {
        TextView t = new TextView(act);
        Language.setText(t, label);
        t.setTextSize(11f);
        t.setLetterSpacing(0.1f);
        t.setTypeface(Design.typefaceBold());
        t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(48));
            t.setMinWidth(dp(48));
        t.setPadding(0, dp(8), 0, dp(8));
        t.setOnClickListener(click);
        return t;
    }

    @Override
    public void refresh() {
        boolean fav = showFavs;
        tabCollected.setTextColor(!fav ? Design.onAccent() : Design.dim());
        tabCollected.setBackgroundColor(!fav ? Design.accent() : Design.surface2());
        tabFavs.setTextColor(fav ? Design.onAccent() : Design.dim());
        tabFavs.setBackgroundColor(fav ? Design.accent() : Design.surface2());
        // Drilled-in state is carried by the cover-hero header row inside the grid (back, title,
        // count and collection actions all live on the banner).
        rebuild();
    }

    // ===================== item assembly =====================

    /** True NFT candidate: any non-Minima token with no fractional decimals (the nftstudio rule). */
    private boolean isNftCandidate(TokenBalance b) {
        if (b.isMinima()) return false;
        String d = b.meta == null ? "" : b.meta.decimals;
        return d.isEmpty() || "0".equals(d.trim());
    }

    private void rebuild() {
        items.clear();
        heroCard = null;
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase();
        Set<String> favs = favs();
        // Group only at the top level of the Collected tab. Favourites are per item, and once
        // you've drilled in the whole point is to see the items.
        final boolean grouped = !showFavs && openCollection == null;

        for (TokenBalance b : act.balances()) {
            if (!isNftCandidate(b)) continue;
            // A drilled-in collection must exclude unrelated regular NFTs too.
            if (openCollection != null && !openCollection.equals(b.tokenid)) continue;
            if (HiddenTokens.isHidden(act, b.tokenid)) continue;
            JSONObject record = tokenRecords.get(b.tokenid);
            if (record == null) { ensureRecord(b.tokenid); }
            String script = record == null ? "" : record.optString("script", "");
            boolean stateNft = !script.isEmpty() && StateNft.isStateNftScript(script);

            if (stateNft) {
                StateNft.Meta meta = StateNft.parseMeta(b.tokenid, record);
                List<GItem> mine = new ArrayList<>();
                for (Coin c : act.coins()) {
                    if (!c.tokenid.equals(b.tokenid)) continue;
                    String idx = StateNft.stamped(c.raw);
                    GItem it = new GItem();
                    it.tokenid = b.tokenid;
                    it.stateNft = true;
                    it.meta = meta;
                    it.coin = c;
                    it.idx = idx == null ? -1 : parseInt(idx);
                    it.name = meta.name + (idx != null ? " #" + idx : " (unstamped)");
                    it.displayName = Language.join(NftText.collection(meta, record), idx != null ? " #" + idx : Language.join(" (", Language.msg(l10n_nft_gallery_unstamped), ")"));
                    it.displaySub = Language.join(Language.msg(l10n_nft_state), " · ", idx != null ? Language.msg(l10n_nft_gallery_edition, idx, meta.size) : Language.msg(l10n_nft_gallery_unstamped));
                    it.creator = b.meta == null ? "" : b.meta.owner;
                    it.sub = "state · " + (idx != null ? "#" + idx + " of " + meta.size : "unstamped");
                    it.imageUrl = StateNft.imageUrl(meta, it.idx <= 0 ? 1 : it.idx, c.raw);
                    it.favKey = b.tokenid + "#" + it.idx;
                    it.webvalidate = meta.webvalidate;
                    it.externalUrl = meta.externalUrl;
                    it.description = meta.description;
                    it.searchBlob = blob(it.name, it.creator, it.tokenid);
                    mine.add(it);
                }
                if (mine.isEmpty()) continue;
                // Sealed index order, not UTXO order — #2 should follow #1 every time.
                java.util.Collections.sort(mine, (x, y) -> Integer.compare(x.idx, y.idx));
                if (openCollection != null && openCollection.equals(b.tokenid)) {
                    heroCard = collectionCard(b, meta, mine);   // the cover banner above the item grid
                }
                if (grouped) items.add(collectionCard(b, meta, mine));
                else items.addAll(mine);
            } else {
                GItem it = new GItem();
                it.tokenid = b.tokenid;
                it.stateNft = false;
                it.balance = b;
                it.idx = -1;
                it.name = b.name;
                it.displayName = NftText.token(b);
                it.creator = b.meta == null ? "" : b.meta.owner;
                it.sub = "nft · " + Format.amount(b.sendable) + "/" + Format.amount(b.total);
                it.imageUrl = b.meta == null ? "" : b.meta.iconUrl;
                it.favKey = b.tokenid;
                it.webvalidate = b.meta == null ? "" : b.meta.webvalidate;
                it.externalUrl = b.meta == null ? "" : b.meta.externalUrl;
                it.description = b.meta == null ? "" : b.meta.description;
                it.searchBlob = blob(it.name, it.creator, it.tokenid);
                items.add(it);
            }
        }

        // filter: favourites tab + search
        List<GItem> filtered = new ArrayList<>();
        for (GItem it : items) {
            if (showFavs && !favs.contains(it.favKey)) continue;
            // searchBlob carries a collection card's ITEM names too, so searching "#7" still finds
            // the collection holding it rather than coming back empty.
            if (!q.isEmpty() && (it.searchBlob == null || !it.searchBlob.contains(q))) continue;
            filtered.add(it);
        }
        items.clear();
        items.addAll(filtered);

        emptyNote.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        Language.setText(emptyNote, showFavs ? Language.msg(l10n_nft_gallery_no_favourites)
                : openCollection != null ? Language.msg(l10n_nft_gallery_no_collection_items)
                : Language.msg(l10n_nft_gallery_no_nfts));
        adapter.notifyDataSetChanged();
    }

    /** Lower-cased haystack for the search box. Nulls are skipped, not rendered as "null". */
    private static String blob(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (p != null) sb.append(p).append(' ');
        return sb.toString().toLowerCase();
    }

    /** The single card that stands in for a whole StateNFT collection at the top level. */
    private GItem collectionCard(TokenBalance b, StateNft.Meta meta, List<GItem> mine) {
        GItem it = new GItem();
        it.collection = true;
        it.stateNft = true;
        it.tokenid = b.tokenid;
        it.meta = meta;
        it.owned = mine.size();
        it.idx = -1;
        it.name = meta.name;
        it.displayName = NftText.collection(meta, tokenRecords.get(b.tokenid));
        it.displaySub = Language.join(Language.msg(l10n_nft_collection), " · ", Language.msg(l10n_nft_gallery_held, mine.size(), meta.size));
        it.creator = b.meta == null ? "" : b.meta.owner;
        it.sub = "collection · " + mine.size() + " of " + meta.size + " held";
        // The token record's own icon is the collection's cover; fall back to the lowest-numbered
        // item you hold, which is what the mint form defaults the icon to anyway.
        String cover = b.meta == null ? "" : b.meta.iconUrl;
        it.imageUrl = cover == null || cover.isEmpty() ? mine.get(0).imageUrl : cover;
        it.favKey = b.tokenid;
        it.webvalidate = meta.webvalidate;
        it.externalUrl = meta.externalUrl;
        it.description = meta.description;
        StringBuilder names = new StringBuilder(blob(it.name, it.creator, it.tokenid));
        for (GItem m : mine) names.append(m.searchBlob);
        it.searchBlob = names.toString();
        return it;
    }

    // ===================== drill in / out =====================

    private void openCollection(GItem card) {
        openCollection = card.tokenid;
        openCollectionName = card.name == null ? "Collection" : card.name;
        openCollectionDisplayName = NftText.galleryName(card);
        refresh();
        grid.scrollToPosition(0);
    }

    private void closeCollection() {
        openCollection = null;
        openCollectionName = "";
        openCollectionDisplayName = "";
        refresh();
        grid.scrollToPosition(0);
    }

    /** True if the tab handled a Back press by stepping out of a collection. */
    public boolean onBackPressed() {
        if (openCollection == null) return false;
        closeCollection();
        return true;
    }

    /** Whole-collection actions, reachable from inside the collection where they make sense. */
    private void showCollectionActions() {
        if (openCollection == null) return;
        final String tokenid = openCollection;
        final String name = openCollectionName;
        String creatorPk = "";
        JSONObject record = tokenRecords.get(tokenid);
        if (record != null) creatorPk = StateNft.creatorPk(record.optString("script", ""));
        final String pk = creatorPk;
        Sheet.create(act, openCollectionDisplayName)
                .subtitle(Language.msg(l10n_nft_gallery_collection_actions))
                .action(Language.msg(l10n_nft_gallery_send_collection), Sheet.Style.PRIMARY,
                        () -> StateNftActions.transferCollectionDialog(act, tokenid, name,
                                () -> act.reload()))
                .action(Language.msg(l10n_nft_gallery_bury_collection), Sheet.Style.DANGER,
                        () -> StateNftActions.buryCollectionDialog(act, tokenid, name, pk,
                                () -> act.reload()))
                .action(Language.msg(l10n_utxo_close), Sheet.Style.SECONDARY, null)
                .show();
    }

    /** Fetch the token record once per tokenid (script decides state-vs-regular; meta feeds images). */
    private void ensureRecord(final String tokenid) {
        if (!Util.isValidHexId(tokenid)) return;   // chain-supplied id — never interpolate unvetted
        if (tokenRecords.containsKey(tokenid) || fetching.contains(tokenid)) return;
        fetching.add(tokenid);
        act.node().cmd("tokens tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                fetching.remove(tokenid);
                Object resp = json.opt("response");
                JSONObject tok = resp instanceof JSONObject ? (JSONObject) resp
                        : (resp instanceof org.json.JSONArray && ((org.json.JSONArray) resp).length() > 0
                            ? ((org.json.JSONArray) resp).optJSONObject(0) : null);
                if (tok != null) {
                    tokenRecords.put(tokenid, tok);
                    rebuild();
                }
            }
            @Override public void onError(String message) { fetching.remove(tokenid); }
        });
    }

    // ===================== favourites =====================

    private Set<String> favs() {
        return new HashSet<>(act.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getStringSet(KEY_FAVS, new HashSet<>()));
    }

    private void toggleFav(String key) {
        Set<String> f = favs();
        if (!f.remove(key)) f.add(key);
        act.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_FAVS, f).apply();
        rebuild();
    }

    // ===================== grid =====================

    /** One gallery card. Package-visible so {@link NftViewer} can page the exact objects on screen. */
    static class GItem {
        String tokenid, name, creator, sub, imageUrl, favKey, webvalidate, externalUrl, description;
        CharSequence displayName, displaySub;
        String searchBlob;         // lower-cased haystack; a collection's includes its item names
        boolean stateNft;
        boolean collection;        // this card stands for a whole collection, not one item
        int owned;                 // collection cards: how many of the edition you hold
        int idx;
        Coin coin;                 // state item's UTXO
        TokenBalance balance;      // regular NFT's balance row
        StateNft.Meta meta;        // state collection meta
    }

    /** Grid tile image whose height always equals its width (donor: Minimal Wallet NftView). */
    private static class SquareImageView extends androidx.appcompat.widget.AppCompatImageView {
        SquareImageView(android.content.Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) { super.onMeasure(w, w); }
    }

    /** What the fullscreen viewer needs back from the gallery. */
    private final NftViewer.Host viewerHost = new NftViewer.Host() {
        @Override public boolean isFav(String key) { return favs().contains(key); }
        @Override public void toggleFav(String key) { GalleryView.this.toggleFav(key); }
        @Override public void showDetail(GItem it) { GalleryView.this.showDetail(it); }
        @Override public void openCollection(GItem it) { GalleryView.this.openCollection(it); }
    };

    private class Holder extends RecyclerView.ViewHolder {
        final ImageView art, badge;
        final TextView name, sub, idxChip, heart;

        Holder(FrameLayout shell, ImageView art, ImageView badge,
               TextView name, TextView sub, TextView idxChip, TextView heart) {
            super(shell);
            this.art = art; this.badge = badge;
            this.name = name; this.sub = sub; this.idxChip = idxChip; this.heart = heart;
        }
    }

    private class HeroHolder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title, count, desc;

        HeroHolder(View root, ImageView cover, TextView title, TextView count, TextView desc) {
            super(root);
            this.cover = cover; this.title = title; this.count = count; this.desc = desc;
        }
    }

    private class GalleryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        static final int TYPE_HERO = 1;

        boolean hasHeader() { return openCollection != null && heroCard != null; }

        @Override public int getItemCount() { return items.size() + (hasHeader() ? 1 : 0); }

        @Override public int getItemViewType(int position) {
            return hasHeader() && position == 0 ? TYPE_HERO : 0;
        }

        /** The drilled-in collection's cover banner: art + scrim + back/title/count/actions. */
        private HeroHolder createHero() {
            LinearLayout wrap = new LinearLayout(act);
            wrap.setOrientation(LinearLayout.VERTICAL);
            RecyclerView.LayoutParams wlp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = dp(4);
            wlp.setMargins(m, m, m, m);
            wrap.setLayoutParams(wlp);

            FrameLayout banner = new FrameLayout(act);
            ImageView cover = new ImageView(act);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            banner.addView(cover, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View scrim = new View(act);
            scrim.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{ 0x66000000, 0x00000000, 0xB3000000 }));
            banner.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView back = new TextView(act);
            Language.setText(back, Language.msg(l10n_nft_gallery_all_nfts));
            back.setTextColor(0xFFFFFFFF);
            back.setMinHeight(dp(48));
            back.setMinWidth(dp(48));
            back.setTextSize(13f);
            back.setTypeface(Design.typefaceBold());
            back.setShadowLayer(4f, 0, 1, 0xAA000000);
            back.setPadding(dp(10), dp(10), dp(14), dp(12));
            back.setOnClickListener(v -> closeCollection());
            banner.addView(back, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START | Gravity.TOP));

            LinearLayout foot = new LinearLayout(act);
            foot.setOrientation(LinearLayout.VERTICAL);
            foot.setPadding(dp(12), 0, dp(12), dp(10));
            TextView title = new TextView(act);
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(19f);
            title.setTypeface(Design.typefaceBold(), Typeface.BOLD);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            foot.addView(title);
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView count = new TextView(act);
            count.setTextColor(0xCCFFFFFF);
            count.setTextSize(10.5f);
            count.setLetterSpacing(0.08f);
            count.setTypeface(Design.typefaceBold());
            row.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView actions = new TextView(act);
            Language.setText(actions, Language.join(Language.upper(Language.msg(l10n_nft_collection)), " ⌄"));
            actions.setTextColor(0xFFFFFFFF);
            actions.setMinHeight(dp(48));
            actions.setMinWidth(dp(48));
            actions.setTextSize(10.5f);
            actions.setLetterSpacing(0.08f);
            actions.setTypeface(Design.typefaceBold());
            actions.setPadding(dp(10), dp(4), 0, dp(4));
            actions.setOnClickListener(v -> showCollectionActions());
            row.addView(actions);
            foot.addView(row);
            banner.addView(foot, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
            wrap.addView(banner, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));

            TextView desc = new TextView(act);
            desc.setTextColor(Design.dim());
            desc.setTextSize(12.5f);
            desc.setBackgroundColor(Design.surface());
            desc.setPadding(dp(10), dp(8), dp(10), dp(8));
            wrap.addView(desc, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            return new HeroHolder(wrap, cover, title, count, desc);
        }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == TYPE_HERO) return createHero();
            FrameLayout shell = new FrameLayout(act);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = dp(4);
            lp.setMargins(m, m, m, m);
            shell.setLayoutParams(lp);
            shell.setBackgroundColor(Design.surface());

            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            shell.addView(col, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            FrameLayout artWrap = new FrameLayout(act);
            ImageView art = new SquareImageView(act);   // height = width: square tiles at any column count
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artWrap.addView(art, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ImageView badge = new ImageView(act);
            int bs = dp(18);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM | Gravity.END);
            blp.setMargins(0, 0, dp(6), dp(6));
            badge.setLayoutParams(blp);
            badge.setImageBitmap(Identicon.checkBadge(bs));
            badge.setVisibility(View.GONE);
            artWrap.addView(badge);
            col.addView(artWrap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout meta = new LinearLayout(act);
            meta.setOrientation(LinearLayout.VERTICAL);
            meta.setPadding(dp(8), dp(6), dp(8), dp(7));
            TextView name = new TextView(act);
            name.setTextColor(Design.text());
            name.setTextSize(12f);
            name.setTypeface(Design.typefaceBold());
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            TextView sub = new TextView(act);
            sub.setTextColor(Design.dim());
            sub.setTextSize(9.5f);
            sub.setLetterSpacing(0.04f);
            meta.addView(name);
            meta.addView(sub);
            col.addView(meta);

            TextView idxChip = new TextView(act);
            idxChip.setTextColor(0xFFFFFFFF);
            idxChip.setBackgroundColor(0x8C000000);
            idxChip.setTextSize(9f);
            idxChip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            idxChip.setPadding(dp(6), dp(3), dp(6), dp(3));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START | Gravity.TOP);
            clp.setMargins(dp(6), dp(6), 0, 0);
            shell.addView(idxChip, clp);

            TextView heart = new TextView(act);
            heart.setTextSize(14f);
            heart.setMinHeight(dp(48));
            heart.setMinWidth(dp(48));
            heart.setShadowLayer(4f, 0, 1, 0xAA000000);
            FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END | Gravity.TOP);
            hlp.setMargins(0, dp(4), dp(8), 0);
            shell.addView(heart, hlp);

            return new Holder(shell, art, badge, name, sub, idxChip, heart);
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeroHolder) {
                HeroHolder hh = (HeroHolder) holder;
                Language.setText(hh.title, NftText.galleryName(heroCard));
                Language.setText(hh.count, Language.upper(Language.msg(l10n_nft_gallery_held, heroCard.owned, heroCard.meta == null ? "?" : String.valueOf(heroCard.meta.size))));
                hh.desc.setVisibility(heroCard.description == null || heroCard.description.isEmpty()
                        ? View.GONE : View.VISIBLE);
                Language.setText(hh.desc, heroCard.description == null ? "" : heroCard.description);
                hh.cover.setImageBitmap(Identicon.forToken(heroCard.tokenid + "#-1", dp(200)));
                if (heroCard.imageUrl != null && !heroCard.imageUrl.isEmpty()) {
                    ImageLoader.loadTile(act, heroCard.imageUrl, hh.cover, null);
                }
                // Tap the cover to start flicking from the first item you hold.
                hh.cover.setOnClickListener(v -> {
                    if (!items.isEmpty()) NftViewer.open(act, new ArrayList<>(items), 0, viewerHost);
                });
                return;
            }
            Holder h = (Holder) holder;
            final GItem it = items.get(position - (hasHeader() ? 1 : 0));
            Language.setText(h.name, NftText.galleryName(it));
            Language.setText(h.sub, Language.upper(it.displaySub != null ? it.displaySub : it.sub == null ? "" : it.sub));

            if (it.collection) {
                // The chip carries the count, so a stack of items reads as a stack at a glance.
                h.idxChip.setVisibility(View.VISIBLE);
                Language.setText(h.idxChip, Language.join("▣ ", Language.upper(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_nft_items, it.owned))));
            } else if (it.stateNft && it.idx > 0 && it.meta != null) {
                h.idxChip.setVisibility(View.VISIBLE);
                Language.setText(h.idxChip, "#" + it.idx + " / " + it.meta.size);
            } else {
                h.idxChip.setVisibility(View.GONE);
            }

            boolean fav = favs().contains(it.favKey);
            Language.setText(h.heart, fav ? "♥" : "♡");
            Language.description(h.heart, fav ? Language.msg(l10n_nft_gallery_unfavourite) : Language.msg(l10n_nft_gallery_favourite));
            h.heart.setTextColor(fav ? 0xFFFF5A6E : 0xFFFFFFFF);
            h.heart.setOnClickListener(v -> toggleFav(it.favKey));

            h.art.setImageBitmap(Identicon.forToken(it.tokenid + "#" + it.idx, dp(150)));
            if (it.imageUrl != null && !it.imageUrl.isEmpty()) {
                ImageLoader.loadTile(act, it.imageUrl, h.art, null);
            }

            // Web-validation badge, same proof as BalancesView: the webvalidate URL hosts the tokenid.
            boolean validated = it.webvalidate != null && !it.webvalidate.isEmpty()
                    && Boolean.TRUE.equals(WebValidate.status(it.tokenid));
            h.badge.setVisibility(validated ? View.VISIBLE : View.GONE);
            if (it.webvalidate != null && !it.webvalidate.isEmpty()) {
                WebValidate.ensure(act, it.tokenid, it.webvalidate, adapter::notifyDataSetChanged);
            }

            ((FrameLayout) h.itemView).setForeground(null);
            // A collection card drills in; anything else lands straight in the fullscreen viewer,
            // paging the list exactly as displayed (viewer-first — Details is one tap away inside).
            h.itemView.setOnClickListener(v -> {
                if (it.collection) { openCollection(it); return; }
                NftViewer.open(act, new ArrayList<>(items), items.indexOf(it), viewerHost);
            });
            h.itemView.setOnLongClickListener(v -> { toggleFav(it.favKey); return true; });
        }
    }

    // ===================== detail =====================

    private void showDetail(final GItem it) {
        final Sheet sheet = Sheet.create(act, NftText.galleryName(it));
        LinearLayout box = sheet.body();

        // hero — never crop the art: fit, size to the image, tap enters the zoom viewer
        final ImageView hero = new ImageView(act);
        hero.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hero.setAdjustViewBounds(true);
        hero.setMaxHeight(dp(340));
        hero.setScaleType(ImageView.ScaleType.FIT_CENTER);
        hero.setImageBitmap(Identicon.forToken(it.tokenid + "#" + it.idx, dp(200)));
        if (it.imageUrl != null && !it.imageUrl.isEmpty()) {
            ImageLoader.loadFullOver(act, it.imageUrl, hero, null);
            hero.setOnClickListener(v -> NftViewer.open(act,
                    java.util.Collections.singletonList(it), 0, viewerHost));
        }
        box.addView(hero);
        TextView hint = new TextView(act);
        Language.setText(hint, Language.msg(l10n_nft_wallet_fullscreen_hint));
        hint.setTextColor(Design.dim());
        hint.setTextSize(10f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(3), 0, dp(6));
        box.addView(hint);

        // chips line
        Language.TextBuilder chips = new Language.TextBuilder();
        if (it.stateNft) {
            chips.append(it.idx > 0 ? Language.msg(l10n_nft_gallery_edition, it.idx, it.meta == null ? "?" : String.valueOf(it.meta.size)) : Language.msg(l10n_nft_gallery_unstamped));
            chips.append("  ·  ").append(Language.upper(Language.msg(l10n_nft_gallery_locked_edition)));
        } else if (it.balance != null) {
            chips.append("1".equals(it.balance.total) ? Language.upper(Language.msg(l10n_nft_gallery_one_of_one)) : Language.upper(Language.msg(l10n_nft_gallery_editions, it.balance.total)));
        }
        if (it.webvalidate != null && !it.webvalidate.isEmpty()
                && Boolean.TRUE.equals(WebValidate.status(it.tokenid))) {
            chips.append("  ·  ").append(Language.upper(Language.msg(l10n_nft_gallery_web_validated)));
        }
        TextView chipLine = new TextView(act);
        Language.setText(chipLine, chips.build());
        chipLine.setTextColor(Design.accentText());
        chipLine.setTextSize(10f);
        chipLine.setLetterSpacing(0.06f);
        chipLine.setTypeface(Design.typefaceBold());
        chipLine.setPadding(0, dp(3), 0, dp(8));
        box.addView(chipLine);

        if (it.description != null && !it.description.isEmpty()) {
            TextView d = new TextView(act);
            Language.setText(d, it.description);
            d.setTextColor(Design.dim());
            d.setTextSize(13f);
            d.setPadding(0, 0, 0, dp(6));
            box.addView(d);
        }

        kvCopy(box, Language.msg(l10n_utxo_token_id), it.tokenid);
        if (it.coin != null) kvCopy(box, Language.msg(l10n_nft_coin_id), it.coin.coinid);
        if (it.creator != null && !it.creator.isEmpty()) kvCopy(box, Language.msg(l10n_nft_gallery_creator), it.creator);
        if (it.stateNft && it.idx > 0) kvCopy(box, Language.msg(l10n_nft_gallery_identity), Language.msg(l10n_nft_gallery_sealed, it.idx));
        if (it.coin != null) {
            if (!it.coin.address.isEmpty()) kvCopy(box, Language.msg(l10n_nft_held_address), it.coin.address);
            if (!it.coin.amount.isEmpty()) kvCopy(box, Language.msg(l10n_history_amount), it.coin.amount);
            if (!it.coin.created.isEmpty()) kvCopy(box, Language.msg(l10n_nft_wallet_created_block), it.coin.created);
        }
        if (it.externalUrl != null && !it.externalUrl.isEmpty()) linkRow(box, Language.msg(l10n_nft_gallery_external_url), it.externalUrl);
        if (it.webvalidate != null && !it.webvalidate.isEmpty()) linkRow(box, Language.msg(l10n_utxo_web_validation), it.webvalidate);
        // Every state port the coin carries, verbatim-copyable (huge embedded art is summarised on
        // screen; the copy still carries the full value).
        if (it.coin != null) {
            for (String[] e : it.coin.state) {
                String val = e[1] == null ? "" : e[1];
                CharSequence shown = "1".equals(e[0]) && val.length() > 120
                        ? Language.msg(l10n_nft_gallery_embedded_artwork, val.length()) : val;
                kvCopy(box, Language.msg(l10n_nft_gallery_state, e[0]), val, shown);
            }
        }

        final boolean fav = favs().contains(it.favKey);
        sheet.action(Language.msg(l10n_utxo_close), Sheet.Style.SECONDARY, null);
        sheet.action(fav ? Language.join("♥ ", Language.msg(l10n_nft_gallery_unfavourite)) : Language.join("♡ ", Language.msg(l10n_nft_gallery_favourite)), Sheet.Style.SECONDARY,
                () -> toggleFav(it.favKey));

        if (it.stateNft && it.coin != null) {
            final String display = it.name;
            final String collection = it.meta == null ? it.name : it.meta.name;
            sheet.action(Language.msg(l10n_nft_wallet_transfer), Sheet.Style.PRIMARY, () ->
                    StateNftActions.transferDialog(act, it.tokenid, display, it.coin.raw));
            final android.app.Dialog dlg = sheet.show();
            // Bury lives behind long-press on Transfer? No — add a dedicated row instead.
            TextView bury = new TextView(act);
            Language.setText(bury, Language.msg(l10n_nft_gallery_bury_item));
            bury.setTextColor(Design.red());
            bury.setTextSize(12f);
            bury.setPadding(0, dp(12), 0, dp(4));
            bury.setOnClickListener(v -> {
                dlg.dismiss();
                StateNftActions.buryDialog(act, it.tokenid, collection, display, it.coin.raw);
            });
            box.addView(bury);

            TextView sendAll = new TextView(act);
            Language.setText(sendAll, Language.msg(l10n_nft_gallery_send_collection));
            sendAll.setTextColor(Design.accentText());
            sendAll.setTextSize(12f);
            sendAll.setPadding(0, dp(8), 0, dp(2));
            sendAll.setOnClickListener(v -> {
                dlg.dismiss();
                StateNftActions.transferCollectionDialog(act, it.tokenid, collection,
                        () -> act.reload());
            });
            box.addView(sendAll);

            TextView buryAll = new TextView(act);
            Language.setText(buryAll, Language.msg(l10n_nft_gallery_bury_collection));
            buryAll.setTextColor(Design.red());
            buryAll.setTextSize(12f);
            buryAll.setPadding(0, dp(2), 0, dp(4));
            final JSONObject rec = tokenRecords.get(it.tokenid);
            final String creatorPk = rec == null ? "" : StateNft.creatorPk(rec.optString("script", ""));
            buryAll.setOnClickListener(v -> {
                dlg.dismiss();
                StateNftActions.buryCollectionDialog(act, it.tokenid, collection, creatorPk,
                        () -> act.reload());
            });
            box.addView(buryAll);
        } else {
            sheet.action(Language.msg(l10n_utxo_send), Sheet.Style.PRIMARY, () -> act.sendToken(it.tokenid));
            sheet.show();
        }

        // fire the web-validation check so the badge is fresh next open
        if (it.webvalidate != null && !it.webvalidate.isEmpty()) {
            WebValidate.ensure(act, it.tokenid, it.webvalidate, () -> {});
        }
    }

    private void kvCopy(LinearLayout box, CharSequence label, CharSequence value) {
        kvCopy(box, label, value, value);
    }

    /** Label/value row: {@code shown} is displayed, tapping copies the FULL {@code value}. */
    private void kvCopy(LinearLayout box, final CharSequence label, final CharSequence value, CharSequence shown) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(4), 0, dp(4));
        TextView l = new TextView(act);
        Language.setText(l, label);
        l.setTextColor(Design.dim());
        l.setTextSize(12f);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(act);
        Language.setText(v, shown);   // full identifiers on screen — tap copies the complete value
        v.setTextColor(Design.text());
        v.setTextSize(12f);
        v.setTypeface(Typeface.MONOSPACE);
        v.setGravity(Gravity.END);
        r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        r.setOnClickListener(x -> CoinDetailDialog.copy(act, label, value));
        box.addView(r);
    }

    private void linkRow(LinearLayout box, CharSequence label, final String url) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(6), 0, dp(6));
        TextView l = new TextView(act);
        Language.setText(l, label);
        l.setTextColor(Design.dim());
        l.setTextSize(12f);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(act);
        boolean web = Util.isWebUrl(url);
        Language.setText(v, web ? Language.msg(l10n_nft_wallet_open_link) : Language.msg(l10n_nft_wallet_blocked_link));
        v.setTextColor(web ? Design.accentText() : Design.red());
        v.setTextSize(12f);
        v.setGravity(Gravity.END);
        r.addView(v);
        r.setOnClickListener(x -> Util.openWebUrl(act, url));
        box.addView(r);
        TextView dest = new TextView(act);
        Language.setText(dest, url);
        dest.setTextColor(Design.dim2());
        dest.setTextSize(10f);
        dest.setTypeface(Typeface.MONOSPACE);
        dest.setMaxLines(2);
        dest.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        box.addView(dest);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }

    private int dp(int v) { return (int) (v * act.getResources().getDisplayMetrics().density); }
}
