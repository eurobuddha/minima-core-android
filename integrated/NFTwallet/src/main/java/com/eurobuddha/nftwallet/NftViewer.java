package com.eurobuddha.nftwallet;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

/**
 * Fullscreen immersive NFT viewer — the Minimal Wallet experience (apks/wallet NftView.openViewer)
 * generalized to the Gallery's item list: pinch-zoom (1x–6x), pan, double-tap, swipe between the
 * items of whatever list you opened it from, single tap hides ALL chrome (bars included) so the
 * artwork owns the screen. Neighbours are prefetched so a flick lands on a decoded image.
 *
 * Chrome is deliberately fixed white-on-translucent-black, independent of the Design theme:
 * art sits on black in every mode.
 */
public final class NftViewer {

    /** What the Gallery provides around the image: favourites, the full info sheet, drill-in. */
    public interface Host {
        boolean isFav(String favKey);
        void toggleFav(String favKey);
        void showDetail(GalleryView.GItem it);
        void openCollection(GalleryView.GItem it);
    }

    private NftViewer() {}

    /** Single-image mode (Balances token icons, coin-detail embedded art): zoom + immersive, no paging. */
    public static void openSingle(MainActivity act, String url, CharSequence title) {
        GalleryView.GItem it = new GalleryView.GItem();
        it.name = title == null ? "" : title.toString();
        it.displayName = title;
        it.imageUrl = url;
        it.tokenid = "";
        it.idx = -1;
        open(act, Collections.singletonList(it), 0, null);
    }

    public static void open(final MainActivity act, final List<GalleryView.GItem> list,
                            int startPos, final Host host) {
        if (list == null || list.isEmpty()) return;
        final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        act.trackLanguageDialog(d);
        final int[] pos = { Math.max(0, Math.min(startPos, list.size() - 1)) };

        FrameLayout frame = new FrameLayout(act);
        frame.setBackgroundColor(Color.BLACK);

        final ZoomImageView zoom = new ZoomImageView(act);
        frame.addView(zoom, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // ---- top bar: back · title · position counter -------------------------------------------
        final LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0x99000000);
        top.setPadding(dp(act, 8), dp(act, 8), dp(act, 8), dp(act, 8));
        TextView back = new TextView(act);
        Language.setText(back, "←");
        back.setTextSize(24f);
            back.setMinHeight(dp(act, 48));
            back.setMinWidth(dp(act, 48));
        back.setTextColor(Color.WHITE);
        back.setPadding(dp(act, 6), 0, dp(act, 16), 0);
        Language.description(back, Language.msg(l10n_nft_gallery_close_viewer));
        back.setOnClickListener(v -> d.dismiss());
        top.addView(back);
        frame.addView(top, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // ---- bottom bar: ♡ · Details · primary action (gallery mode only) ------------------------
        LinearLayout bottomBar = null;
        TextView heart = null, details = null, action = null;
        if (host != null) {
            bottomBar = new LinearLayout(act);
            bottomBar.setOrientation(LinearLayout.HORIZONTAL);
            bottomBar.setGravity(Gravity.CENTER_VERTICAL);
            bottomBar.setBackgroundColor(0x99000000);
            bottomBar.setPadding(dp(act, 14), dp(act, 10), dp(act, 14), dp(act, 10));

            heart = new TextView(act);
            heart.setTextSize(20f);
            heart.setMinHeight(dp(act, 48));
            heart.setMinWidth(dp(act, 48));
            heart.setPadding(dp(act, 4), 0, dp(act, 16), 0);
            Language.description(heart, Language.msg(l10n_nft_gallery_favourite));
            bottomBar.addView(heart);

            details = new TextView(act);
            Language.setText(details, Language.upper(Language.msg(l10n_nft_details)));
            details.setTextColor(Color.WHITE);
            details.setTextSize(12f);
            details.setMinHeight(dp(act, 48));
            details.setMinWidth(dp(act, 48));
            details.setLetterSpacing(0.1f);
            details.setTypeface(Design.typefaceBold(), Typeface.BOLD);
            details.setPadding(dp(act, 8), dp(act, 8), dp(act, 8), dp(act, 8));
            bottomBar.addView(details, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            action = new TextView(act);
            action.setTextSize(12f);
            action.setMinHeight(dp(act, 48));
            action.setMinWidth(dp(act, 48));
            action.setLetterSpacing(0.08f);
            action.setTypeface(Design.typefaceBold(), Typeface.BOLD);
            action.setGravity(Gravity.CENTER);
            action.setPadding(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 8));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(Design.accent());
            pill.setCornerRadius(dp(act, Math.max(2, (int) Design.radiusDp())));
            action.setBackground(pill);
            bottomBar.addView(action, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
        }

        // ---- caption: ALWAYS visible, even with the chrome hidden — the artwork is never an
        // unlabeled image. Name + the key facts; tapping it opens the full info sheet. -----------
        LinearLayout caption = new LinearLayout(act);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setBackgroundColor(0x99000000);
        caption.setPadding(dp(act, 14), dp(act, 8), dp(act, 14), dp(act, 10));
        final TextView capName = new TextView(act);
        capName.setTextColor(Color.WHITE);
        capName.setTextSize(15f);
        capName.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        capName.setMaxLines(1);
        capName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.addView(capName);
        final TextView capInfo = new TextView(act);
        capInfo.setTextColor(0xCCFFFFFF);
        capInfo.setTextSize(10.5f);
        capInfo.setLetterSpacing(0.07f);
        capInfo.setTypeface(Design.typefaceBold());
        capInfo.setPadding(0, dp(act, 2), 0, 0);
        caption.addView(capInfo);
        if (host != null) {
            caption.setOnClickListener(v -> host.showDetail(list.get(pos[0])));
        }

        LinearLayout bottomGroup = new LinearLayout(act);
        bottomGroup.setOrientation(LinearLayout.VERTICAL);
        if (bottomBar != null) bottomGroup.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottomGroup.addView(caption, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.addView(bottomGroup, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            top.setPadding(dp(act, 8) + bars.left, dp(act, 8) + bars.top,
                    dp(act, 8) + bars.right, dp(act, 8));
            bottomGroup.setPadding(bars.left, 0, bars.right, bars.bottom);
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(frame);
        final LinearLayout bottom = bottomBar;
        final TextView heartF = heart, actionF = action;

        // ---- show one item in place: title, counter, heart, action, image ------------------------
        final Runnable show = () -> {
            GalleryView.GItem it = list.get(pos[0]);
            Language.setText(capName, NftText.galleryName(it));
            CharSequence info = infoLine(it, pos[0], list.size());
            Language.setText(capInfo, info);
            capInfo.setVisibility(info.length() == 0 ? View.GONE : View.VISIBLE);

            if (host != null) {
                boolean fav = it.favKey != null && host.isFav(it.favKey);
                Language.setText(heartF, fav ? "♥" : "♡");
                Language.description(heartF, fav ? Language.msg(l10n_nft_gallery_unfavourite) : Language.msg(l10n_nft_gallery_favourite));
                heartF.setTextColor(fav ? 0xFFFF5A6E : Color.WHITE);

                actionF.setAlpha(1f);
                actionF.setEnabled(true);
                actionF.setTextColor(Design.onAccent());
                if (it.collection) {
                    Language.setText(actionF, Language.upper(Language.msg(l10n_nft_gallery_open_collection)));
                    actionF.setOnClickListener(v -> { d.dismiss(); host.openCollection(it); });
                } else if (it.stateNft && it.coin != null) {
                    boolean unstamped = StateNft.stamped(it.coin.raw) == null;
                    if (unstamped) {
                        // Honest state: the creator bypass is still live, so the item can't move.
                        Language.setText(actionF, Language.upper(Language.msg(l10n_nft_gallery_unstamped)));
                        actionF.setEnabled(false);
                        actionF.setAlpha(0.5f);
                        actionF.setOnClickListener(null);
                    } else {
                        Language.setText(actionF, Language.upper(Language.msg(l10n_nft_wallet_transfer)));
                        actionF.setOnClickListener(v ->
                                StateNftActions.transferDialog(act, it.tokenid, NftText.galleryName(it), it.coin.raw));
                    }
                } else if (it.tokenid != null && !it.tokenid.isEmpty()) {
                    Language.setText(actionF, Language.upper(Language.msg(l10n_utxo_send)));
                    actionF.setOnClickListener(v -> { d.dismiss(); act.sendToken(it.tokenid); });
                } else {
                    Language.setText(actionF, "");
                    actionF.setEnabled(false);
                    actionF.setAlpha(0f);
                }
            }

            // Instant frame from the cache (full > tile > thumb), identicon otherwise, then the
            // bounded full resolution replaces it in place.
            Bitmap cached = ImageLoader.cachedBest(it.imageUrl);
            if (cached != null) zoom.setImageBitmap(cached);
            else zoom.setImageBitmap(Identicon.forToken(it.tokenid + "#" + it.idx, dp(act, 320)));
            if (it.imageUrl != null && !it.imageUrl.isEmpty()) {
                ImageLoader.loadFullOver(act, it.imageUrl, zoom, null);
            }
            // Warm the neighbours so the next flick is instant.
            if (pos[0] + 1 < list.size()) ImageLoader.prefetchFull(list.get(pos[0] + 1).imageUrl);
            if (pos[0] - 1 >= 0) ImageLoader.prefetchFull(list.get(pos[0] - 1).imageUrl);
        };

        if (host != null) {
            heartF.setOnClickListener(v -> {
                GalleryView.GItem it = list.get(pos[0]);
                if (it.favKey == null) return;
                host.toggleFav(it.favKey);
                show.run();
            });
            details.setOnClickListener(v -> host.showDetail(list.get(pos[0])));
        }

        // Swipe left/right at 1x = next/previous; zoomed-in drags stay panning. Ends stop.
        zoom.setSwipeListener(next -> {
            int to = pos[0] + (next ? 1 : -1);
            if (to < 0 || to >= list.size()) return;
            pos[0] = to;
            show.run();
        });

        // Swipe up at 1x: straight into the full info sheet, same as tapping the caption.
        if (host != null) {
            zoom.setSwipeUpListener(() -> host.showDetail(list.get(pos[0])));
        }

        // Single tap: fade ALL chrome (and the system bars with it) so the artwork owns the screen.
        final boolean[] chrome = { true };
        zoom.setTapListener(() -> {
            chrome[0] = !chrome[0];
            for (View v : new View[]{ top, bottom }) {
                if (v == null) continue;
                if (chrome[0]) v.setVisibility(View.VISIBLE);
                v.animate().alpha(chrome[0] ? 1f : 0f).setDuration(150)
                        .withEndAction(() -> { if (!chrome[0]) v.setVisibility(View.GONE); }).start();
            }
            setImmersive(d.getWindow(), !chrome[0]);
        });

        Window w = d.getWindow();
        if (w != null) {
            // Let the black canvas run under a camera cutout instead of leaving a grey letterbox.
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }

        show.run();
        d.setContentView(frame);
        d.show();
    }

    /** The always-visible one-line summary under the artwork: position, edition, status. */
    private static CharSequence infoLine(GalleryView.GItem it, int pos, int n) {
        java.util.List<CharSequence> parts = new java.util.ArrayList<>();
        if (n > 1) parts.add((pos + 1) + " / " + n);
        if (it.collection) {
            parts.add(Language.upper(Language.msg(l10n_nft_gallery_collection_held, it.owned)));
        } else if (it.stateNft) {
            if (it.idx > 0 && it.meta != null) parts.add(Language.upper(Language.msg(l10n_nft_gallery_edition, it.idx, it.meta.size)));
            parts.add(Language.upper(Language.msg(l10n_nft_gallery_locked_edition)));
            if (it.coin != null && StateNft.stamped(it.coin.raw) == null) parts.add(Language.upper(Language.msg(l10n_nft_gallery_unstamped)));
        } else if (it.balance != null) {
            parts.add("1".equals(it.balance.total) ? Language.upper(Language.msg(l10n_nft_gallery_one_of_one)) : Language.upper(Language.msg(l10n_nft_gallery_editions, it.balance.total)));
        }
        if (it.tokenid != null && !it.tokenid.isEmpty()
                && Boolean.TRUE.equals(WebValidate.status(it.tokenid))) parts.add(Language.upper(Language.msg(l10n_nft_gallery_validated)));
        Language.TextBuilder text = new Language.TextBuilder();
        for (int i = 0; i < parts.size(); i++) text.append(i == 0 ? "" : "  ·  ").append(parts.get(i));
        return text.build();
    }

    /** Hide/show status + navigation bars with the chrome. */
    private static void setImmersive(Window w, boolean hide) {
        if (w == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            if (hide) {
                c.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(android.view.WindowInsets.Type.systemBars());
            } else {
                c.show(android.view.WindowInsets.Type.systemBars());
            }
        } else {
            View dv = w.getDecorView();
            dv.setSystemUiVisibility(hide
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                      | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                      | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    : View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private static int dp(android.content.Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }
}
