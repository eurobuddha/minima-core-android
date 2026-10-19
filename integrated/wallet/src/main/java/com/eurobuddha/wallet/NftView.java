package com.eurobuddha.wallet;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

import org.minima.utils.json.JSONObject;

import com.eurobuddha.wallet.comms.NodeApi;

/**
 * NFTs tab — the artwork leads, the interface recedes.
 *
 * <pre>
 *   Gallery      adaptive artwork grid of collections (alphabetical)
 *   Collection   full-screen browser: cover banner, description, item grid,
 *                per-item SEND, Send collection
 *   Viewer       full-screen: pinch-zoom full resolution, swipe left/right
 *                between items, single-tap hides/shows the chrome
 *   Details      every field, full identifiers, copyable
 * </pre>
 *
 * Send safety (every path):
 * <ul>
 *   <li><b>Double-send guard</b> — a coin broadcast this session is PENDING until the chain
 *       confirms the spend (it drops out of the coins list). Pending items show a SENDING chip
 *       and refuse further sends; a stale dialog can never resell a sent coin because dialogs
 *       close on success and every send re-checks the live coin list by coinid.</li>
 *   <li><b>Unstamped locked editions are refused</b> — the creator bypass is still live on such
 *       a coin, so a recipient could have it reclaimed. Collection sends skip them and say so.</li>
 *   <li><b>Self-send notice</b> — sending to one of this wallet's own 64 addresses is flagged in
 *       the review (allowed, but never silent).</li>
 *   <li>A StateNFT's sealed state must equal the transaction state, so a collection send is N
 *       separate locally-signed transactions, sequential, with progress, stop, and a full
 *       per-txpowid report.</li>
 * </ul>
 * Display and SEND only — minting stays in NFT wallet.
 */
public class NftView extends BaseView {

    /** Broadcasts remain pending until fresh evidence or explicit retry review.
     * Elapsed time only enables a review; it is never evidence that a transaction failed. */
    private static final java.util.Map<String, Long> PENDING = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long RETRY_WAIT_MS = 10 * 60_000;

    static java.util.Set<String> pendingCoinIds(){
        java.util.Set<String> result=new java.util.HashSet<>();
        for(String id:PENDING.keySet())result.add(id.toLowerCase(java.util.Locale.ROOT));
        return result;
    }
    static long markPending(String id){
        return PENDING.compute(id,(key,previous)->Math.max(System.currentTimeMillis(),previous==null?0:previous+1));
    }
    static void clearSweepPending(String id,long stamp){PENDING.remove(id,stamp);}
    private static boolean isPending(String coinid) { return PENDING.containsKey(coinid); }
    private static boolean retryReady(String coinid) {
        Long time = PENDING.get(coinid);
        return time != null && System.currentTimeMillis() - time >= RETRY_WAIT_MS;
    }

    private final java.util.function.BooleanSupplier session;
    private final LinearLayout grid;
    private final TextView status;

    /** The open browser/viewer dialogs — closed after a successful send so they can't go stale. */
    private Dialog mBrowser, mViewer;

    public NftView(MainActivity a) {
        super(a, buildRoot(a));
        session = a.sessionAuthorization();
        LinearLayout content = (LinearLayout) ((ScrollView) root).getChildAt(0);
        status = (TextView) content.getChildAt(0);
        grid   = (LinearLayout) content.getChildAt(1);
        refresh();
    }

    private static View buildRoot(MainActivity a) {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(a, 12), dp(a, 12), dp(a, 12), dp(a, 16));
        content.setBackgroundColor(Design.bg());
        TextView status = new TextView(a);
        status.setTextColor(Design.dim());
        status.setTextSize(12f);
        status.setPadding(dp(a, 4), 0, dp(a, 4), dp(a, 4));
        content.addView(status);
        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        content.addView(grid);
        sv.addView(content);
        return sv;
    }

    private static int dp(MainActivity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private int dp(int v) { return dp(act, v); }

    /** Columns from the real screen width — 2 on a phone, more on the Fold's inner display. */
    private int columns() {
        float wdp = act.getResources().getDisplayMetrics().widthPixels
                / act.getResources().getDisplayMetrics().density;
        return Math.max(2, (int) (wdp / 190));
    }

    /** Square grid cell image: height always equals measured width. */
    private static class SquareImageView extends androidx.appcompat.widget.AppCompatImageView {
        SquareImageView(MainActivity a) { super(a); }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
            setMeasuredDimension(getMeasuredWidth(), getMeasuredWidth());
        }
    }

    /** One owned NFT token: its meta and our coins of it, sorted by sealed index. */
    private static class Group {
        String tokenid;
        Nft.Meta meta;
        final List<JSONObject> coins = new ArrayList<>();
    }

    /** Lay {@code cells} out in {@code cols} equal columns, padding the last row. */
    private void addGrid(LinearLayout parent, List<View> cells, int cols) {
        for (int i = 0; i < cells.size(); i += cols) {
            LinearLayout rowv = new LinearLayout(act);
            rowv.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols; c++) {
                View cell = (i + c < cells.size()) ? cells.get(i + c) : new View(act);
                rowv.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            parent.addView(rowv);
        }
    }

    // =============================================================================================
    // Gallery (tab content)
    // =============================================================================================

    @Override
    public void refresh() {
        if (!session.getAsBoolean()) return;
        grid.removeAllViews();

        org.minima.utils.json.JSONArray all = act.coins();
        LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
        if (all != null) {
            for (Object o : all) {
                JSONObject c = (JSONObject) o;
                String tokenid = Nft.str(c, "tokenid");
                if (Util.isMinima(tokenid)) continue;
                Object tok = c.get("token");
                Nft.Meta meta = Nft.meta(tok instanceof JSONObject ? (JSONObject) tok : null);
                if (!Nft.isNftMeta(meta)) continue;
                Group g = groups.get(tokenid);
                if (g == null) { g = new Group(); g.tokenid = tokenid; g.meta = meta; groups.put(tokenid, g); }
                g.coins.add(c);
            }
        }
        // A cached gallery refresh cannot prove departure or clear pending sends.

        List<Group> list = new ArrayList<>(groups.values());
        for (Group g : list) {
            java.util.Collections.sort(g.coins, (a, b) -> Nft.itemIndex(a) - Nft.itemIndex(b));
        }
        java.util.Collections.sort(list, (a, b) -> a.meta.name.compareToIgnoreCase(b.meta.name));

        if (list.isEmpty()) {
            Language.setText(status, act.isPaired()
                    ? Language.msg(l10n_nft_no_items)
                    : Language.msg(l10n_nft_node_unavailable));
            return;
        }
        int confirming = 0;
        for (Group g : list) for (JSONObject c : g.coins) if (isPending(Nft.str(c, "coinid"))) confirming++;
        Language.setText(status, Language.join(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_nft_collections, list.size()), " · ", Language.msg(l10n_nft_tap_artwork), confirming > 0 ? Language.join(" · ", Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_nft_confirming_items, confirming)) : ""));

        List<View> cells = new ArrayList<>();
        for (Group g : list) cells.add(galleryTile(g));
        addGrid(grid, cells, columns());
    }

    private View galleryTile(final Group g) {
        LinearLayout cell = new LinearLayout(act);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(4), dp(4), dp(4), dp(10));

        SquareImageView art = new SquareImageView(act);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(Design.surface());
        Language.description(art, Language.join(g.meta.name, ", ", Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_nft_items, g.coins.size())));
        art.setImageBitmap(Identicon.forToken(g.tokenid, dp(160)));
        loadArt(g, g.coins.get(0), art, false);
        cell.addView(art, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(act);
        name.setText(g.meta.name);
        name.setTextColor(Design.text());
        name.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        name.setTextSize(14f);
        name.setMaxLines(1);
        name.setPadding(dp(2), dp(6), dp(2), 0);
        cell.addView(name);

        TextView sub = new TextView(act);
        Language.setText(sub, Language.join(g.meta.stateNft ? "StateNFT · " : "NFT · ", Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_nft_items, g.coins.size())));
        sub.setTextColor(Design.dim());
        sub.setTextSize(11f);
        sub.setPadding(dp(2), 0, dp(2), 0);
        cell.addView(sub);

        cell.setOnClickListener(v -> openCollection(g));
        return cell;
    }

    private void loadArt(Group g, JSONObject coin, ImageView iv, boolean full) {
        String url = Nft.imageUrl(g.meta, Nft.itemIndex(coin), coin);
        if (url == null || url.isEmpty()) return;
        if (full) ImageLoader.loadFullOver(act, url, iv, null);
        else      ImageLoader.loadTile(act, url, iv, null);
    }

    // =============================================================================================
    // Collection browser (full screen)
    // =============================================================================================

    private void openCollection(final Group g) {
        final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        act.trackWalletDialog(d, false);
        mBrowser = d;
        d.setOnDismissListener(x -> { if (mBrowser == d) mBrowser = null; });

        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Design.bg());
        LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);

        // Cover banner — the collection's own artwork carries the header; title sits on a scrim.
        FrameLayout banner = new FrameLayout(act);
        ImageView cover = new ImageView(act);
        cover.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(210)));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(Design.surface());
        cover.setImageBitmap(Identicon.forToken(g.tokenid, dp(210)));
        loadArt(g, g.coins.get(0), cover, false);
        banner.addView(cover);
        View scrim = new View(act);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x00000000, 0xB3000000}));
        banner.addView(scrim, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(210)));
        TextView back = new TextView(act);
        Language.setText(back, "←");
        back.setTextSize(26f);
        back.setTextColor(Color.WHITE);
        back.setPadding(dp(14), dp(8), dp(16), dp(12));
        Language.description(back, Language.msg(l10n_nft_back_gallery));
        back.setOnClickListener(v -> d.dismiss());
        banner.addView(back, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        LinearLayout titleCol = new LinearLayout(act);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(dp(16), 0, dp(16), dp(12));
        TextView title = new TextView(act);
        Language.setText(title, g.meta.name);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        title.setTextSize(22f);
        titleCol.addView(title);
        TextView count = new TextView(act);
        Language.setText(count, Language.join(g.meta.stateNft ? "StateNFT · " : "NFT · ", Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_nft_items, g.coins.size())));
        count.setTextColor(0xE6FFFFFF);
        count.setTextSize(12f);
        titleCol.addView(count);
        banner.addView(titleCol, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        content.addView(banner);

        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(16));

        if (!g.meta.description.isEmpty()) {
            TextView desc = new TextView(act);
            desc.setText(g.meta.description);
            desc.setTextColor(Design.text());
            desc.setTextSize(13f);
            desc.setPadding(dp(4), 0, dp(4), dp(10));
            body.addView(desc);
        }

        Button sendAll = new Button(act);
        Language.setText(sendAll, Language.msg(l10n_nft_send_collection, g.coins.size()));
        sendAll.setTextColor(Design.onAccent());
        sendAll.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
        sendAll.setOnClickListener(v -> promptSendCollection(g));
        body.addView(sendAll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        List<View> cells = new ArrayList<>();
        for (JSONObject coin : g.coins) cells.add(itemTile(g, coin));
        LinearLayout itemsGrid = new LinearLayout(act);
        itemsGrid.setOrientation(LinearLayout.VERTICAL);
        itemsGrid.setPadding(0, dp(8), 0, 0);
        addGrid(itemsGrid, cells, columns());
        body.addView(itemsGrid);

        content.addView(body);
        sv.addView(content);
        d.setContentView(sv);
        d.show();
    }

    private View itemTile(final Group g, final JSONObject coin) {
        final String coinid = Nft.str(coin, "coinid");
        final boolean pending = isPending(coinid);
        final boolean retry = retryReady(coinid);
        final int idx = Nft.itemIndex(coin);
        final int ordinal = g.coins.indexOf(coin) + 1;
        final CharSequence itemName = idx >= 0 ? "#" + idx : Language.msg(l10n_nft_item_number, ordinal);

        LinearLayout cell = new LinearLayout(act);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(4), dp(4), dp(4), dp(10));

        FrameLayout artFrame = new FrameLayout(act);
        SquareImageView art = new SquareImageView(act);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(Design.surface());
        Language.description(art, Language.join(g.meta.name, " ", itemName));
        art.setImageBitmap(Identicon.forToken(g.tokenid + idx, dp(160)));
        loadArt(g, coin, art, false);
        artFrame.addView(art, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        if (pending) {
            TextView chip = new TextView(act);
            Language.setText(chip, Language.msg(l10n_nft_confirming));
            chip.setTextSize(10f);
            chip.setLetterSpacing(0.08f);
            chip.setTypeface(Design.typefaceBold(), Typeface.BOLD);
            chip.setTextColor(Color.WHITE);
            chip.setBackgroundColor(0xB3000000);
            chip.setPadding(dp(8), dp(4), dp(8), dp(4));
            artFrame.addView(chip, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END));
        }
        cell.addView(artFrame);

        LinearLayout caption = new LinearLayout(act);
        caption.setOrientation(LinearLayout.HORIZONTAL);
        caption.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(act);
        Language.setText(label, itemName);
        label.setTextColor(Design.text());
        label.setTextSize(13f);
        label.setPadding(dp(2), dp(4), dp(2), 0);
        caption.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView sendOne = new TextView(act);
        Language.setText(sendOne, retry ? Language.msg(l10n_nft_review) : pending ? Language.msg(l10n_nft_confirming) : Language.msg(l10n_wallet_send));
        sendOne.setTextSize(12f);
        sendOne.setLetterSpacing(0.06f);
        sendOne.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        sendOne.setTextColor(pending && !retry ? Design.dim() : Design.accent());
        sendOne.setMinHeight(dp(40));
        sendOne.setGravity(Gravity.CENTER_VERTICAL);
        sendOne.setPadding(dp(12), 0, dp(4), 0);
        Language.description(sendOne, Language.msg(l10n_nft_send_named, Language.join(g.meta.name, " ", itemName)));
        if (!pending || retry) sendOne.setOnClickListener(v -> promptSendItem(g, coin));
        caption.addView(sendOne);
        cell.addView(caption);

        cell.setOnClickListener(v -> openViewer(g, g.coins.indexOf(coin)));
        return cell;
    }

    // =============================================================================================
    // Item viewer (full screen, pinch-zoom, swipe, tap-to-immerse)
    // =============================================================================================

    private void openViewer(final Group g, int zStartPos) {
        final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        act.trackWalletDialog(d, false);
        mViewer = d;
        d.setOnDismissListener(x -> { if (mViewer == d) mViewer = null; });
        final int[] pos = { Math.max(0, zStartPos) };

        FrameLayout frame = new FrameLayout(act);
        frame.setBackgroundColor(Color.BLACK);

        final ZoomImageView zoom = new ZoomImageView(act);
        frame.addView(zoom, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Top bar: back · title · position counter.
        final LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0x99000000);
        top.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView back = new TextView(act);
        Language.setText(back, "←");
        back.setTextSize(24f);
        back.setTextColor(Color.WHITE);
        back.setPadding(dp(4), 0, dp(14), 0);
        Language.description(back, Language.msg(l10n_nft_back_collection));
        back.setOnClickListener(v -> d.dismiss());
        top.addView(back);
        final TextView title = new TextView(act);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        title.setTextSize(16f);
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView counter = new TextView(act);
        counter.setTextColor(Color.WHITE);
        counter.setTextSize(13f);
        counter.setPadding(dp(8), 0, dp(8), 0);
        top.addView(counter);
        frame.addView(top, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // Bottom bar: Details · Send — always the CURRENT item.
        final LinearLayout bottom = new LinearLayout(act);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setBackgroundColor(0x99000000);
        bottom.setPadding(dp(12), dp(6), dp(12), dp(6));
        Button details = new Button(act);
        Language.setText(details, Language.msg(l10n_nft_details));
        details.setTextColor(Color.WHITE);
        details.setBackgroundColor(Color.TRANSPARENT);
        details.setOnClickListener(v -> showDetails(g, g.coins.get(pos[0])));
        bottom.addView(details, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final Button send = new Button(act);
        send.setTextColor(Design.onAccent());
        send.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
        send.setOnClickListener(v -> promptSendItem(g, g.coins.get(pos[0])));
        bottom.addView(send, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        frame.addView(bottom, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Show one item in place — image, title, counter, send state.
        final Runnable show = () -> {
            JSONObject coin = g.coins.get(pos[0]);
            int idx = Nft.itemIndex(coin);
            boolean pending = isPending(Nft.str(coin, "coinid"));
            boolean retry = retryReady(Nft.str(coin, "coinid"));
            Language.setText(title, g.meta.name + (idx >= 0 ? "  #" + idx : ""));
            Language.setText(counter, (pos[0] + 1) + " / " + g.coins.size());
            Language.setText(send, retry ? Language.msg(l10n_nft_review) : pending ? Language.msg(l10n_nft_confirming) : Language.msg(l10n_wallet_send));
            send.setEnabled(!pending || retry);
            send.setAlpha(pending && !retry ? 0.5f : 1f);
            zoom.setImageBitmap(Identicon.forToken(g.tokenid + idx, dp(320)));
            loadArt(g, coin, zoom, true);   // full resolution (bounded 1600px)
        };

        // Swipe left/right at 1x = next/previous; zoomed-in drags stay panning. Ends stop.
        zoom.setSwipeListener(next -> {
            int to = pos[0] + (next ? 1 : -1);
            if (to < 0 || to >= g.coins.size()) return;
            pos[0] = to;
            show.run();
        });

        // Single tap: hide/show the chrome so the artwork owns the screen.
        zoom.setTapListener(() -> {
            int vis = top.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
            top.setVisibility(vis);
            bottom.setVisibility(vis);
        });

        show.run();
        d.setContentView(frame);
        d.show();
    }

    // =============================================================================================
    // Details — every field, full identifiers, copyable
    // =============================================================================================

    private void showDetails(final Group g, final JSONObject coin) {
        int idx = Nft.itemIndex(coin);
        String addr = Nft.str(coin, "address");
        Integer ki = act.keyIndexForAddress(addr);

        Language.TextBuilder sb = new Language.TextBuilder();
        sb.append(g.meta.name).append(idx >= 0 ? "  #" + idx : "").append("\n");
        sb.append(Nft.isLockedScript(g.meta.script) ? Language.msg(l10n_nft_locked_edition) : g.meta.stateNft ? "StateNFT" : "NFT").append("\n");
        if (!g.meta.mode.isEmpty()) sb.append(Language.join(Language.msg(l10n_nft_artwork_mode), ": ")).append(g.meta.mode).append("\n");
        if (isPending(Nft.str(coin, "coinid"))) {
            sb.append(Language.join(Language.msg(l10n_nft_pending_detail), "\n"));
        }
        if (Nft.isUnstampedLocked(g.meta, coin)) {
            sb.append(Language.join(Language.msg(l10n_nft_unstamped_detail), "\n"));
        }
        if (!g.meta.description.isEmpty()) sb.append("\n").append(g.meta.description).append("\n");
        sb.append(Language.join("\n", Language.msg(l10n_history_token), ":\n")).append(g.tokenid).append("\n");
        sb.append(Language.join("\n", Language.msg(l10n_nft_coin_id), ":\n")).append(Nft.str(coin, "coinid")).append("\n");
        sb.append("\n").append(Language.msg(l10n_nft_held_address)).append(ki != null ? Language.join(" (", Language.msg(l10n_nft_key_index, ki), ")") : "").append(":\n")
          .append(addr).append("\n");
        sb.append(Language.join("\n", Language.msg(l10n_history_amount), ": ")).append(Nft.str(coin, "tokenamount").isEmpty()
                ? Nft.str(coin, "amount") : Nft.str(coin, "tokenamount")).append("\n");
        sb.append(Language.join(Language.msg(l10n_nft_created_block), ": ")).append(Nft.str(coin, "created")).append("\n");

        List<String[]> state = Nft.rawStateEntries(coin);
        if (!state.isEmpty()) {
            sb.append(Language.join("\n", Language.msg(l10n_nft_state), ":\n"));
            for (String[] e : state) {
                String v = e[1];
                if ("1".equals(e[0]) && v.length() > 120) {
                    sb.append("  ").append(Language.msg(l10n_nft_embedded_artwork, v.length())).append("\n");
                } else {
                    sb.append("  ").append(Language.msg(l10n_nft_state_port, e[0])).append(": ").append(v).append("\n");
                }
            }
        }

        TextView msg = new TextView(act);
        Language.setText(msg, sb.build());
        msg.setTextIsSelectable(true);
        msg.setTextColor(Design.text());
        msg.setTypeface(Typeface.MONOSPACE);
        msg.setTextSize(12f);
        msg.setPadding(dp(20), dp(12), dp(20), dp(8));
        ScrollView wrap = new ScrollView(act);
        wrap.addView(msg);

        new WalletDialog(act)
                .setTitle(Language.msg(l10n_nft_details))
                .setView(wrap)
                .setPositiveButton(Language.msg(l10n_nft_copy_token_id), (dd, w) -> copy("tokenid", g.tokenid))
                .setNeutralButton(Language.msg(l10n_nft_copy_coin_id), (dd, w) -> copy("coinid", Nft.str(coin, "coinid")))
                .setNegativeButton(Language.msg(l10n_language_close), null)
                .show();
    }

    private void copy(String zLabel, String zValue) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText(zLabel, zValue));
        Toast.makeText(act, Language.msg(l10n_history_copied), Toast.LENGTH_SHORT).show();
    }

    // =============================================================================================
    // Send one item
    // =============================================================================================

    private void promptSendItem(final Group g, final JSONObject coin) {
        if (!session.getAsBoolean()) return;
        final int idx = Nft.itemIndex(coin);
        if (retryReady(Nft.str(coin, "coinid"))) {
            promptRecipient(Language.msg(l10n_nft_review), recipient -> requestRetry(g,
                    java.util.Collections.singletonList(Nft.str(coin, "coinid")), recipient));
            return;
        }
        String guard = sendGuard(g, coin);
        if (guard != null) { Toast.makeText(act, guard, Toast.LENGTH_LONG).show(); return; }
        promptRecipient(Language.msg(l10n_nft_send_named, g.meta.name + (idx >= 0 ? " #" + idx : "")),
                recipient -> reviewSendItem(g, idx, coin, recipient));
    }

    /** The reason this coin cannot be sent right now, or null when it can. */
    private String sendGuard(Group g, JSONObject coin) {
        String coinid = Nft.str(coin, "coinid");
        if (isPending(coinid)) {
            return Language.text(act, l10n_nft_wait_pending);
        }
        if (Nft.isUnstampedLocked(g.meta, coin)) {
            return Language.text(act, l10n_nft_stamp_first);
        }
        if (!coinStillOurs(coinid)) {
            return Language.text(act, l10n_nft_coin_gone);
        }
        return null;
    }

    /** Re-check the LIVE coin list by coinid — a dialog must never sell a coin we no longer hold. */
    private boolean coinStillOurs(String zCoinId) {
        org.minima.utils.json.JSONArray all = act.coins();
        if (all == null) return false;
        for (Object o : all) {
            if (Nft.str((JSONObject) o, "coinid").equals(zCoinId)) return true;
        }
        return false;
    }

    private interface RecipientCb { void onRecipient(String zAddr); }

    private void promptRecipient(CharSequence zTitle, final RecipientCb zCb) {
        final EditText addr = new EditText(act);
        Language.hint(addr, Language.msg(l10n_wallet_recipient));
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addr.setTextColor(Design.text());
        LinearLayout wrap = new LinearLayout(act);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(addr, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new WalletDialog(act)
                .setTitle(zTitle)
                .setView(wrap)
                .setNeutralButton(Language.msg(l10n_wallet_scan_qr), (d, w) -> act.scanQr(s -> zCb.onRecipient(s == null ? "" : s.trim())))
                .setPositiveButton(Language.msg(l10n_nft_review), (d, w) -> zCb.onRecipient(addr.getText().toString().trim()))
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    /** "" when not ours, else a review-dialog notice naming the receiving key. */
    private CharSequence selfSendNotice(String zRecipient) {
        try {
            String r0x = TxnFactory.parseAddress(zRecipient).to0xString();
            Integer ki = act.keyIndexForAddress(r0x);
            if (ki != null) return Language.join("\n", Language.msg(l10n_nft_self_send, ki));
        } catch (Exception ignore) { }
        return "";
    }

    private void reviewSendItem(final Group g, final int idx, final JSONObject coin, final String recipient) {
        try {
            if (!Util.isValidAddress(recipient)) throw new IllegalArgumentException(Language.text(act, l10n_wallet_invalid_recipient));
            String guard = sendGuard(g, coin);
            if (guard != null) throw new IllegalStateException(guard);
            Integer ki = act.keyIndexForAddress(Nft.str(coin, "address"));
            if (ki == null) throw new IllegalStateException(Language.text(act, l10n_wallet_foreign_coin, Nft.str(coin, "address")));
            final int keyIndex = ki;
            final String coinid = Nft.str(coin, "coinid");

            //Resolve the input BYTE-EXACT from the node (coinexport) before anything signs — the
            //coins-JSON token descriptor is lossy and consensus would reject the txn silently.
            List<org.minima.utils.json.JSONObject> one = new ArrayList<>();
            one.add(coin);
            act.resolveInputs(one, new MainActivity.InputsCb() {
                @Override public void onResolved(List<TxnFactory.InputCoin> inputs) {
                    if (!session.getAsBoolean()) return;
                    final TxnFactory.InputCoin in = inputs.get(0);
                    CharSequence review = Language.join("NFT: ", g.meta.name, idx >= 0 ? " #" + idx : "", "\n",
                            g.meta.stateNft ? Language.msg(l10n_nft_state_replayed) : Language.msg(l10n_nft_sent_whole), "\n",
                            Language.msg(l10n_history_to), ": ", recipient, "\ncoinid: ", coinid, "\n", selfSendNotice(recipient));
                    final TxnFactory factory = act.factory();
                    act.confirmSignAndPublish(review, keyIndex,
                            () -> factory.buildNftTransfer(in, recipient, Util.newTxnId()),
                            () -> {
                                PENDING.put(coinid, System.currentTimeMillis());
                                // The open dialogs now describe coins that changed — close rather than go stale.
                                if (mViewer != null) mViewer.dismiss();
                                if (mBrowser != null) mBrowser.dismiss();
                                refresh();
                            });
                }
                @Override public void onError(String message) {
                    if (!session.getAsBoolean()) return;
                    Toast.makeText(act, Language.msg(l10n_nft_prepare_failed, WalletMessages.message(message)), Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(act, WalletMessages.error(e), Toast.LENGTH_LONG).show();
        }
    }

    // =============================================================================================
    // Send the whole collection — N sequential transactions with progress
    // =============================================================================================

    private void promptSendCollection(final Group g) {
        if (!session.getAsBoolean()) return;
        final List<JSONObject> sendable = new ArrayList<>();
        int pending = 0, unstamped = 0;
        boolean needsRetryReview = false;
        for (JSONObject c : g.coins) {
            if (isPending(Nft.str(c, "coinid"))) {
                if (!retryReady(Nft.str(c, "coinid"))) { pending++; continue; }
                needsRetryReview = true;
            }
            if (Nft.isUnstampedLocked(g.meta, c)) { unstamped++; continue; }
            sendable.add(c);
        }
        if (sendable.isEmpty()) {
            Toast.makeText(act, Language.msg(l10n_nft_none_sendable, pending, unstamped), Toast.LENGTH_LONG).show();
            return;
        }
        final int fpending = pending, funstamped = unstamped;
        final boolean retry = needsRetryReview;
        promptRecipient(Language.join(Language.msg(l10n_nft_send_all_named, g.meta.name), " (", Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_nft_items, sendable.size()), ")"),
                recipient -> {
                    if (!retry) reviewSendCollection(g, sendable, fpending, funstamped, recipient);
                    else {
                        List<String> ids = new ArrayList<>();
                        for (JSONObject c : sendable) ids.add(Nft.str(c, "coinid"));
                        requestRetry(g, ids, recipient);
                    }
                });
    }

    private void reviewSendCollection(final Group g, final List<JSONObject> coins,
                                      int zSkippedPending, int zSkippedUnstamped, final String recipient) {
        reviewCollection(g, coins, zSkippedPending, zSkippedUnstamped, recipient, false,
                () -> runCollectionSend(g, coins, recipient));
    }

    private void reviewCollection(final Group g, final List<JSONObject> coins,
                                  int zSkippedPending, int zSkippedUnstamped, final String recipient,
                                  boolean retry, Runnable approved) {
        if (!session.getAsBoolean()) return;
        try {
            if (!Util.isValidAddress(recipient)) throw new IllegalArgumentException(Language.text(act, l10n_wallet_invalid_recipient));

            Language.TextBuilder idxs = new Language.TextBuilder();
            int position = 0;
            List<Integer> keys = new ArrayList<>();
            for (JSONObject c : coins) {
                Integer ki = act.keyIndexForAddress(Nft.str(c, "address"));
                if (ki == null) throw new IllegalStateException(Language.text(act, l10n_wallet_foreign_coin, Nft.str(c, "address")));
                int idx = Nft.itemIndex(c);
                idxs.append(position++ == 0 ? "" : ", ").append(idx >= 0 ? "#" + idx : Language.msg(l10n_nft_item));
                if (!keys.contains(ki)) keys.add(ki);
            }
            Language.TextBuilder usage = new Language.TextBuilder();
            for (int ki : keys) {
                usage.append("\n").append(Language.msg(l10n_wallet_key_usage, ki, act.keyUses().currentUses(ki), Util.WOTS_MAX_USES));
            }
            CharSequence skipped = Language.join(
                    zSkippedPending > 0 ? Language.join("\n", Language.msg(l10n_nft_skipping_pending, zSkippedPending)) : "",
                    zSkippedUnstamped > 0 ? Language.join("\n", Language.msg(l10n_nft_skipping_unstamped, zSkippedUnstamped)) : "");
            CharSequence full = Language.join(Language.msg(l10n_nft_collection), ": ", g.meta.name, "\n",
                    Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_nft_items, coins.size()), " (", idxs.build(), ")\n",
                    Language.msg(l10n_history_to), ": ", recipient, "\n", selfSendNotice(recipient), skipped,
                    retry ? Language.join("\n\n", Language.msg(l10n_nft_retry_warning)) : "",
                    "\n\n", Language.msg(l10n_nft_separate_transactions), usage.build(), "\n\n", Language.msg(l10n_wallet_irreversible));

            new WalletDialog(act)
                    .setTitle(Language.msg(l10n_wallet_confirm_transaction))
                    .setMessage(full)
                    .setPositiveButton(Language.msg(l10n_nft_sign_all), (d, w) -> { if (session.getAsBoolean()) approved.run(); })
                    .setNegativeButton(Language.msg(l10n_common_cancel), null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(act, WalletMessages.error(e), Toast.LENGTH_LONG).show();
        }
    }

    /** Sequentially: build (signs, keyuses advanced + snapshot resynced) → publish → next. */
    private void runCollectionSend(final Group g, final List<JSONObject> coins, final String recipient) {
        if (!session.getAsBoolean()) return;
        try {
            act.vault().assertSigningAllowed();
        } catch (SeedVault.SigningNotAllowedException block) {
            Toast.makeText(act, WalletMessages.error(block), Toast.LENGTH_LONG).show();
            return;
        }

        final TextView progress = new TextView(act);
        progress.setTextColor(Design.text());
        progress.setTextSize(14f);
        progress.setPadding(dp(24), dp(20), dp(24), dp(8));
        final boolean[] cancelled = {false};
        final androidx.appcompat.app.AlertDialog pd = new WalletDialog(act)
                .setTitle(Language.msg(l10n_nft_sending_collection))
                .setView(progress)
                .setCancelable(false)
                .setNegativeButton(Language.msg(l10n_nft_stop_after_current), (d, w) -> cancelled[0] = true)
                .create();
        pd.show();
        //A Dozing phone drops its peers and mined transactions evaporate before block
        //inclusion (proven live: 17 of 19 vanished). Hold the screen through the batch.
        if (pd.getWindow() != null) {
            pd.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        sendNext(g, coins, recipient, 0, new ArrayList<>(), new ArrayList<>(), progress, pd, cancelled);
    }

    private void sendNext(final Group g, final List<JSONObject> coins,
                          final String recipient, final int i, final List<String> txpowids,
                          final List<String> sentCoinIds, final TextView progress,
                          final androidx.appcompat.app.AlertDialog pd, final boolean[] cancelled) {
        if (!session.getAsBoolean()) return;
        if (i >= coins.size() || cancelled[0]) {
            finishCollectionSend(g, recipient, coins.size(), i, txpowids, sentCoinIds, null, pd);
            return;
        }
        final JSONObject coin = coins.get(i);
        final int idx = Nft.itemIndex(coin);
        final String coinid = Nft.str(coin, "coinid");
        if (isPending(coinid)) {
            finishCollectionSend(g, recipient, coins.size(), i, txpowids, sentCoinIds,
                    Language.msg(l10n_nft_wait_pending), pd);
            return;
        }
        Language.setText(progress, Language.msg(l10n_nft_sending_progress, idx >= 0 ? "#" + idx : "", i + 1, coins.size()));

        //Resolve this item's input BYTE-EXACT (coinexport) before signing.
        List<org.minima.utils.json.JSONObject> one = new ArrayList<>();
        one.add(coin);
        act.resolveInputs(one, new MainActivity.InputsCb() {
            @Override public void onResolved(List<TxnFactory.InputCoin> inputs) {
                if (!session.getAsBoolean()) return;
                if (isPending(coinid)) {
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
                            Language.msg(l10n_nft_item_failed, idx >= 0 ? "#" + idx : String.valueOf(i + 1), WalletMessages.error(error)), pd);
                });
            }
            @Override public void onError(String message) {
                if (!session.getAsBoolean()) return;
                finishCollectionSend(g, recipient, coins.size(), i, txpowids, sentCoinIds,
                        Language.msg(l10n_nft_item_prepare_failed, idx >= 0 ? "#" + idx : String.valueOf(i + 1), WalletMessages.message(message)), pd);
            }
        });
    }

    private void publishOne(final Group g, final List<JSONObject> coins, final String recipient,
                            final int i, final List<String> txpowids, final List<String> sentCoinIds,
                            final TextView progress, final androidx.appcompat.app.AlertDialog pd,
                            final boolean[] cancelled, final String coinid, final int idx,
                            final TxnFactory.BuiltTxn built) {
        if (!session.getAsBoolean()) return;
        act.node().publish(built, new NodeApi.Cb() {
            @Override public void onResult(org.json.JSONObject json) {
                if (!session.getAsBoolean()) return;
                PENDING.put(coinid, System.currentTimeMillis());
                txpowids.add(Util.extractTxpowid(json, built.getID()));
                sentCoinIds.add(coinid);
                sendNext(g, coins, recipient, i + 1, txpowids, sentCoinIds, progress, pd, cancelled);
            }
            @Override public void onError(String message) {
                if (!session.getAsBoolean()) return;
                finishCollectionSend(g, recipient, coins.size(), i, txpowids, sentCoinIds,
                        Language.msg(l10n_nft_item_broadcast_failed, idx >= 0 ? "#" + idx : String.valueOf(i + 1), WalletMessages.message(message)), pd);
            }
        });
    }

    /** Broadcasting is over (complete, stopped, or failed). Anything broadcast now gets TRACKED
     *  to on-chain confirmation instead of being declared done at "sent". */
    private void finishCollectionSend(Group g, String recipient, int total, int attempted,
                                      List<String> txpowids, List<String> sentCoinIds,
                                      CharSequence error, androidx.appcompat.app.AlertDialog pd) {
        if (!session.getAsBoolean()) return;
        pd.dismiss();
        if (mViewer != null) mViewer.dismiss();
        if (mBrowser != null) mBrowser.dismiss();
        refresh();
        act.reload();

        CharSequence header = Language.join(Language.msg(l10n_nft_broadcast_count, sentCoinIds.size(), total), "\n",
                error != null ? Language.join(Language.msg(l10n_nft_stopped_error, error), "\n")
                : attempted < total ? Language.join(Language.msg(l10n_nft_stopped_by_you), "\n") : "");

        if (sentCoinIds.isEmpty()) {
            showReport(error == null ? Language.msg(l10n_nft_collection_send) : Language.msg(l10n_nft_collection_stopped),
                    header, null, null);
            return;
        }
        trackConfirmations(g, recipient, header, new ArrayList<>(sentCoinIds), txpowids);
    }

    // =============================================================================================
    // Confirmation tracking — "sent" means ON-CHAIN, not "broadcast"
    // =============================================================================================

    private static final long CONFIRM_POLL_MS = 20_000;
    private static final int  CONFIRM_MAX_POLLS = 45;   // 15 minutes

    /**
     * Poll the node until every broadcast coin has LEFT the live coin set (its spend confirmed in
     * a block). A mined transaction can still evaporate before block inclusion (Doze, dropped
     * peers), so completion is only claimed on chain evidence; after 15 minutes the leftovers are
     * offered for a fresh status check and review. Screen held on while tracking.
     */
    private void trackConfirmations(final Group g, final String recipient, final CharSequence zHeader,
                                    final List<String> zRemaining, final List<String> txpowids) {
        if (!session.getAsBoolean()) return;
        final TextView msg = new TextView(act);
        msg.setTextIsSelectable(true);
        msg.setTextColor(Design.text());
        msg.setTextSize(13f);
        msg.setPadding(dp(24), dp(16), dp(24), dp(8));
        ScrollView wrap = new ScrollView(act);
        wrap.addView(msg);

        final boolean[] stopped = {false};
        final androidx.appcompat.app.AlertDialog td = new WalletDialog(act)
                .setTitle(Language.msg(l10n_nft_confirming_on_chain))
                .setView(wrap)
                .setCancelable(false)
                .setNegativeButton(Language.msg(l10n_nft_hide_tracking), (d, w) -> stopped[0] = true)
                .create();
        td.setOnDismissListener(d -> stopped[0] = true);
        td.show();
        if (td.getWindow() != null) {
            td.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        final int totalSent = zRemaining.size();
        final java.util.Map<String,Long> watched = pendingSnapshot(zRemaining);
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final int[] polls = {0};

        final Runnable poll = new Runnable() {
            @Override public void run() {
                if (stopped[0] || act.isDestroyed() || !session.getAsBoolean()) return;
                Language.setText(msg, Language.join(zHeader, "\n", Language.msg(l10n_nft_confirmed_count, (totalSent - zRemaining.size()), totalSent), "…\n\n", Language.msg(l10n_nft_keep_screen_on)));
                act.node().coinsRelevant(new NodeApi.Cb() {
                    @Override public void onResult(org.json.JSONObject json) {
                        if (!session.getAsBoolean()) return;
                        if (stopped[0] || act.isDestroyed() || !session.getAsBoolean()) return;
                        // A missing or malformed list cannot prove a coin has left the chain state.
                        if (json == null || !Boolean.TRUE.equals(json.opt("status"))) {
                            schedule(); return;
                        }
                        org.json.JSONArray arr = json.optJSONArray("response");
                        if (arr == null) { schedule(); return; }
                        java.util.HashSet<String> live = new java.util.HashSet<>();
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject c = arr.optJSONObject(i);
                            String id = c == null ? "" : c.optString("coinid", "");
                            // Same plain-hex check as NFT Wallet's Util.isValidHexId.
                            if (!id.matches("^0x[0-9a-fA-F]{2,64}$")) { schedule(); return; }
                            if (!live.add(id.toLowerCase(java.util.Locale.ROOT))) { schedule(); return; }
                        }
                        for (String id : zRemaining) {
                            if (!live.contains(id.toLowerCase(java.util.Locale.ROOT)) && java.util.Objects.equals(watched.get(id), PENDING.get(id)))
                                PENDING.remove(id);
                        }
                        zRemaining.removeIf(id -> !live.contains(id.toLowerCase(java.util.Locale.ROOT)));
                        int confirmed = totalSent - zRemaining.size();
                        if (zRemaining.isEmpty()) {
                            stopped[0] = true;
                            td.dismiss();
                            act.reload();
                            refresh();
                            Language.TextBuilder done = new Language.TextBuilder();
                            done.append(zHeader).append("\n").append(Language.msg(l10n_nft_all_confirmed, totalSent)).append("\n\ntxpowids:\n");
                            for (String id : txpowids) done.append(id).append("\n");
                            showReport(Language.msg(l10n_nft_collection_sent), done.build(), null, null);
                            return;
                        }
                        Language.setText(msg, Language.join(zHeader, "\n", Language.msg(l10n_nft_confirmed_count, confirmed, totalSent), "…\n\n", Language.msg(l10n_nft_keep_screen_on)));
                        schedule();
                    }
                    @Override public void onError(String message) {
                        if (!session.getAsBoolean()) return;
                        if (stopped[0] || act.isDestroyed() || !session.getAsBoolean()) return;
                        schedule();   // transient node hiccup — keep watching
                    }
                });
            }
            private void schedule() {
                if (++polls[0] >= CONFIRM_MAX_POLLS) {
                    stopped[0] = true;
                    td.dismiss();
                    offerRetry(g, recipient, zHeader, zRemaining, totalSent, txpowids);
                    return;
                }
                h.postDelayed(this, CONFIRM_POLL_MS);
            }
        };
        h.post(poll);
    }

    /** Timeout offers fresh evidence and a new review; it does not free any pending coin. */
    private void offerRetry(final Group g, final String recipient, final CharSequence zHeader,
                            final List<String> zRemaining, int totalSent, List<String> txpowids) {
        if (!session.getAsBoolean()) return;
        CharSequence body = Language.join(zHeader, "\n", Language.msg(l10n_nft_confirmed_count,
                totalSent - zRemaining.size(), totalSent), "\n",
                Language.msg(l10n_nft_unconfirmed_body, zRemaining.size()));
        final List<String> requested = new ArrayList<>(zRemaining);
        showReport(Language.msg(l10n_nft_some_unconfirmed), body,
                Language.msg(l10n_nft_retry_unconfirmed, requested.size()),
                () -> requestRetry(g, requested, recipient));
    }

    private static java.util.Map<String,Long> pendingSnapshot(List<String> ids) {
        java.util.Map<String,Long> snapshot = new LinkedHashMap<>();
        for (String id : ids) snapshot.put(id, PENDING.get(id));
        return snapshot;
    }

    private static boolean pendingUnchanged(java.util.Map<String,Long> snapshot) {
        for (java.util.Map.Entry<String,Long> entry : snapshot.entrySet())
            if (!java.util.Objects.equals(entry.getValue(), PENDING.get(entry.getKey()))) return false;
        return true;
    }

    /** Reuse NodeLink's whole-list read and the NFT confirmation path's strict evidence gate. */
    private void requestRetry(final Group g, List<String> ids, final String recipient) {
        if (!session.getAsBoolean() || ids.isEmpty()) return;
        final List<String> requested = new ArrayList<>(ids);
        final java.util.Map<String,Long> expected = pendingSnapshot(requested);
        act.node().coinsRelevant(new NodeApi.Cb() {
            @Override public void onResult(org.json.JSONObject reply) {
                if (!session.getAsBoolean()) return;
                try {
                    if (!pendingUnchanged(expected)) throw new IllegalStateException();
                    final List<JSONObject> fresh = retryCandidates(g, requested, reply);
                    // Release only absent coins, after the entire reply has been validated.
                    java.util.HashSet<String> present = new java.util.HashSet<>();
                    for (JSONObject c : fresh) present.add(Nft.str(c,"coinid"));
                    for (String id : requested) if (!present.contains(id)) PENDING.remove(id);
                    if (fresh.isEmpty()) {
                        act.reload();
                        Toast.makeText(act, Language.msg(l10n_nft_coins_not_found), Toast.LENGTH_LONG).show();
                        return;
                    }
                    final java.util.Map<String,Long> reviewed = pendingSnapshot(new ArrayList<>(present));
                    reviewCollection(g, fresh, 0, 0, recipient, true, () -> {
                        if (!session.getAsBoolean()) return;
                        if (!pendingUnchanged(reviewed)) { retryUnavailable(); return; }
                        for (String id : reviewed.keySet()) PENDING.remove(id);
                        runCollectionSend(g, fresh, recipient);
                    });
                } catch (Exception unavailable) { retryUnavailable(); }
            }
            @Override public void onError(String message) {
                if (session.getAsBoolean()) retryUnavailable();
            }
        });
    }

    /** Nothing is signed, released or reviewed until all rows pass validation. */
    private List<JSONObject> retryCandidates(Group g, List<String> requested, org.json.JSONObject reply)
            throws Exception {
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))) throw new IllegalStateException();
        org.json.JSONArray rows = reply.optJSONArray("response");
        if (rows == null) throw new IllegalStateException();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        List<JSONObject> fresh = new ArrayList<>();
        org.minima.utils.json.parser.JSONParser parser = new org.minima.utils.json.parser.JSONParser();
        for (int i=0; i<rows.length(); i++) {
            org.json.JSONObject row = rows.optJSONObject(i);
            Object rawId = row == null ? null : row.opt("coinid");
            if (!(rawId instanceof String) || !((String)rawId).matches("^0x[0-9a-fA-F]{2,64}$")
                    || !seen.add(((String)rawId).toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalStateException();
            String id = (String)rawId;
            if (!requested.contains(id)) {
                // Case changes must not be mistaken for a missing requested coin.
                for (String wanted : requested) if (wanted.equalsIgnoreCase(id)) throw new IllegalStateException();
                continue;
            }
            if (!(row.opt("address") instanceof String) || act.keyIndexForAddress(row.getString("address")) == null
                    || !g.tokenid.equalsIgnoreCase(row.optString("tokenid", ""))
                    || row.optJSONObject("token") == null) throw new IllegalStateException();
            JSONObject coin = (JSONObject)parser.parse(row.toString());
            Nft.Meta meta = Nft.meta((JSONObject)coin.get("token"));
            if (!Nft.isNftMeta(meta) || Nft.isUnstampedLocked(meta, coin)) throw new IllegalStateException();
            fresh.add(coin);
        }
        return fresh;
    }

    private void retryUnavailable() {
        Toast.makeText(act, Language.msg(l10n_nft_retry_unavailable), Toast.LENGTH_LONG).show();
    }

    /** One report dialog: selectable monospace body, optional action button. */
    private void showReport(CharSequence zTitle, CharSequence zBody, CharSequence zAction, final Runnable zOnAction) {
        if (!session.getAsBoolean()) return;
        TextView msg = new TextView(act);
        Language.setText(msg, zBody);
        msg.setTextIsSelectable(true);
        msg.setTypeface(Typeface.MONOSPACE);
        msg.setTextColor(Design.text());
        msg.setTextSize(12f);
        msg.setPadding(dp(20), dp(12), dp(20), dp(8));
        ScrollView wrap = new ScrollView(act);
        wrap.addView(msg);
        androidx.appcompat.app.AlertDialog.Builder b = new WalletDialog(act)
                .setTitle(zTitle)
                .setView(wrap)
                .setNegativeButton(Language.msg(l10n_language_close), null);
        if (zAction != null) b.setPositiveButton(zAction, (d, w) -> zOnAction.run());
        b.show();
    }
}
