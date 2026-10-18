package com.eurobuddha.statenft;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Atelier — the Minima NFT creation studio. Katalog design language:
 * paper, ink rules, vermilion stamps, numbered lots, zero rounding.
 */
public class MainActivity extends AppCompatActivity implements ViewerScreen.Host {

    private enum Screen { LAUNCH, GALLERY, COLLECTION, STUDIO, CREATE_COLLECTION, CREATE_NFT, CREATE_TOKEN, CREATE_GENERATIVE, AIRDROP, TRANSFER, BURY, MANAGE, SCAN, ENGINE_LOG, HOSTING, HOSTING_EDIT }

    private static final int PICK_CREATE = 1;
    private static final int PICK_RECOVERY = 2;
    private static final int PICK_NFT = 3;
    private static final int PICK_TOKICON = 5;
    private static final int PICK_COLLICON = 6;
    private static final int PICK_ARTPHOTO = 7;
    private static final int PICK_HOST_ICON = 8;      // upload → an icon-URL field
    private static final int PICK_HOST_EXTERNAL = 9;  // upload → an external/image-URL field
    private static final int PICK_HOST_PLATES = 10;   // upload N → url-mode base+ext
    /** which lane the current hosting upload fills: "collicon"/"collext"/
     *  "collplates"/"nfturl"/"nftext"/"tokicon" */
    private String pendingHostLane = "";
    private Hosting.Profile hostEditProfile;          // profile being edited

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<StateNft.Meta> collections = new ArrayList<>();
    private final ArrayList<StateNft.Meta> scanFound = new ArrayList<>();
    private final ArrayList<JSONObject> nftRows = new ArrayList<>();
    private final ArrayList<JSONObject> tokenRows = new ArrayList<>();

    private NodeApi node;
    private Screen screen = Screen.GALLERY;
    private Runnable backAction;
    private String galleryFilter = "all";
    private String lastGallerySig = "";
    private String lastDetailSig = "";
    private boolean detailPartial = false;
    private int lastTip = 0;
    private final ArrayList<Runnable> departureWatchers = new ArrayList<>();

    /* chrome */
    private FrameLayout rootFrame;
    private LinearLayout appbarBox;
    private ScrollView content;
    private LinearLayout body;
    private LinearLayout navBox;
    private FrameLayout overlay;
    private TextView nodeDot;
    private TextView inlineStatus;
    private int insetTop = 0, insetBottom = 0;

    /* open collection */
    private StateNft.Meta openMeta;
    private long detailRequest;
    private boolean detailLoading;
    private JSONArray openOwned = new JSONArray();
    private JSONArray openAll = new JSONArray();
    private StateNft.Item transferItem;

    /* collection create draft */
    private String createMode = "url";
    private String createName = "", createDesc = "", createSize = "12";
    private boolean createWvAuto = false;   // auto-create web-validation doc post-mint
    private String createBase = "", createExt = ".jpg", createIcon = "", createExternal = "", createWeb = "";
    // last successful plate-upload target — the real, verified base/ext. Kept so a cleared or
    // hand-retyped Base URL can be one-tap restored (and offered as recovery at mint time).
    private String createUploadedBase = "", createUploadedExt = "";
    private String[] createImages = new String[0];
    private int pendingImageIndex = -1;
    private boolean pendingBatchImages = false;
    private int pendingPickContext = PICK_CREATE;
    private long pendingRecoveryId = 0;

    /* nft create draft */
    private String nftName = "", nftDesc = "", nftOwner = "", nftExternal = "", nftWeb = "", nftUrl = "", nftEditions = "1";
    private String nftImage = "";
    private boolean nftEmbed = true;
    private boolean nftSign = true;
    private boolean nftBusy = false;
    private boolean collectionBusy = false;
    private long screenGeneration;
    private final ArrayList<String[]> nftTraits = new ArrayList<>();
    private String traitTypeDraft = "", traitValueDraft = "";

    /* token create draft */
    private String tokName = "", tokTicker = "", tokSupply = "1000000", tokDecimals = "0", tokDesc = "", tokUrl = "";
    private String tokIcon = "";        // embedded wallet icon (b64, ICON_BUDGET)
    private String createIconB64 = ""; // embedded collection icon (b64, ICON_BUDGET)
    /** A plate deserves at least this much image room before the icon may keep
     *  its full weight — ~420px of busy WEBP; plates above it just shrink. */
    private static final int PLATE_MIN_ROOM = 6500;
    private boolean tokBusy = false;
    private final ArrayList<String[]> tokPairs = new ArrayList<>();
    private String pairKeyDraft = "", pairValueDraft = "";

    /* generative studio (artBox packs via ArtStudio bridge) */
    private static final int ART_EMBED_BUDGET = 16000; // b64 chars per SVG — STATE_IMG_BUDGET's
                                                       // transfer-proven envelope (rides twice per
                                                       // transfer under the 64KB TxPoW). The 18
                                                       // generative packs stay tuned+tested at
                                                       // 8192; the photo pack uses the headroom.
    private static org.json.JSONArray ART_STYLE_LIST;  // [{key,label}] from art.js, cached for the process
    private static final java.util.HashMap<String, android.graphics.Bitmap> ART_THUMBS = new java.util.HashMap<>();
    private String artStyle = "mandala";
    private String artSeed = "atelier-genesis";
    private final java.util.HashMap<String, JSONObject> artCfgs = new java.util.HashMap<>();
    private final java.util.HashSet<String> artOpenSlots = new java.util.HashSet<>();
    private JSONArray artItems = new JSONArray();
    private boolean artPhotoLoaded = false;  // mirrors ART_PHOTO_SRC in the bridge WebView
    private final java.util.HashSet<String> artMigrated = new java.util.HashSet<>();
    private final ArrayList<android.graphics.Bitmap> artPreviews = new ArrayList<>();
    private LinearLayout artSheetBox;                  // proof sheet + send button, hidden when stale
    private final Runnable artSaveRunnable = this::artSaveDraft;  // debounced: seed keystrokes
    private boolean artDraftLoaded = false;
    private String genCount = "12";
    private boolean genBusy = false;
    private JSONObject collectionItemTraits = null;

    /* UI refresh only — the ENGINE lives in MintService (foreground service),
     * so mints continue with the screen off. Running a second engine here
     * would race the service's planner. */
    private final Runnable mintLoop = new Runnable() {
        @Override public void run() {
            MintService.kick(MainActivity.this);
            if (screen == Screen.COLLECTION && openMeta != null && activeMint(openMeta) && !detailLoading) {
                loadLocalCollections();
                openMeta = findCollectionByLocalId(openMeta.localId, openMeta);
                refreshDetail();
            }
            if (screen == Screen.ENGINE_LOG) renderEngineLog(backAction);
            main.postDelayed(this, 25000);
        }
    };

    private final Runnable rePair = new Runnable() {
        @Override public void run() {
            if (node != null && !node.isEnabled()) node.reRegister();
            main.postDelayed(this, 12000);
        }
    };

    /* ================= lifecycle & chrome ================= */

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Design.load(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Design.PAPER());

        LinearLayout chrome = vertical();
        appbarBox = vertical();
        appbarBox.setTag("pandamonium.app.toolbar");
        appbarBox.setBackgroundColor(Design.PAPER());
        chrome.addView(appbarBox, new LinearLayout.LayoutParams(-1, -2));

        content = new ScrollView(this);
        content.setFillViewport(true);
        body = vertical();
        body.setPadding(dp(18), dp(14), dp(18), dp(24));
        content.addView(body, new FrameLayout.LayoutParams(-1, -2));
        chrome.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        navBox = vertical();
        navBox.setBackgroundColor(Design.PAPER());
        chrome.addView(navBox, new LinearLayout.LayoutParams(-1, -2));

        rootFrame.addView(chrome, new FrameLayout.LayoutParams(-1, -1));
        overlay = new FrameLayout(this);
        overlay.setVisibility(View.GONE);
        rootFrame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootFrame);

        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(getWindow(), rootFrame);
        wic.setAppearanceLightStatusBars(true);
        wic.setAppearanceLightNavigationBars(true);

        ViewCompat.setOnApplyWindowInsetsListener(rootFrame, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            androidx.core.graphics.Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            insetTop = sys.top;
            insetBottom = sys.bottom;
            appbarBox.setPadding(0, insetTop, 0, 0);
            /* edge-to-edge opts out of adjustResize, so the keyboard must be
             * handled here: pad the whole frame above the IME so focused
             * fields scroll into view, and drop the nav-bar padding while the
             * keyboard covers that bar anyway */
            boolean imeOpen = ime.bottom > 0;
            v.setPadding(0, 0, 0, imeOpen ? ime.bottom : 0);
            navBox.setPadding(0, 0, 0, imeOpen ? 0 : insetBottom);
            overlay.setPadding(0, 0, 0, 0);
            return insets;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (overlay.getVisibility() == View.VISIBLE) { onCloseViewer(); return; }
                if (backAction != null) { backAction.run(); return; }
                finish();
            }
        });

        node = new NodeApi(this, enabled -> {
            refreshNodeDot();
            if (enabled) {
                cleanupPhantomCollections();
                if (screen == Screen.LAUNCH) renderGallery();
                else if (screen == Screen.GALLERY) galleryScan();
            }
        });

        restoreDrafts();
        MintService.kick(this);   // any interrupted mint resumes immediately
        if (LocalStore.load(this).length() == 0) renderLaunch();
        else renderGallery();

        main.postDelayed(() -> { if (node != null && !node.isEnabled()) node.reRegister(); }, 900);
        main.postDelayed(rePair, 12000);
        main.postDelayed(mintLoop, 5000);
    }

    @Override protected void onPause() {
        saveDrafts();
        super.onPause();
    }

    @Override protected void onStart() {
        super.onStart();
        hostingStopped=false;
    }

    @Override protected void onStop() {
        hostingStopped=true;
        cancelHostingCheck();
        super.onStop();
    }

    @Override protected void onDestroy() {
        cancelImageRequests();
        cancelHostingCheck();
        imageIo.shutdownNow();
        verifyGateCancel = true;
        for (Runnable r : departureWatchers) main.removeCallbacks(r);
        departureWatchers.clear();
        main.removeCallbacks(rePair);
        main.removeCallbacks(mintLoop);
        if (node != null) node.onDestroy();
        super.onDestroy();
    }

    /* ---- wizard drafts survive app death ---- */

    private void saveDrafts() {
        // build the JSON trees here (cheap reference puts), serialize + write
        // off the UI thread — the collection draft can be ~300KB of base64
        JSONObject[] drafts = buildDraftJsons();
        new Thread(() -> {
            LocalStore.saveDraft(this, "nft", drafts[0]);
            LocalStore.saveDraft(this, "token", drafts[1]);
            LocalStore.saveDraft(this, "collection", drafts[2]);
        }).start();
    }

    private JSONObject[] buildDraftJsons() {
        JSONObject nft = new JSONObject();
        put(nft, "name", nftName); put(nft, "desc", nftDesc); put(nft, "owner", nftOwner);
        put(nft, "external", nftExternal); put(nft, "web", nftWeb); put(nft, "url", nftUrl);
        put(nft, "editions", nftEditions); put(nft, "image", nftImage);
        put(nft, "embed", nftEmbed); put(nft, "sign", nftSign);
        put(nft, "traits", StateNft.traitsToAttributes(nftTraits));

        JSONObject tok = new JSONObject();
        put(tok, "name", tokName); put(tok, "ticker", tokTicker); put(tok, "supply", tokSupply);
        put(tok, "decimals", tokDecimals); put(tok, "desc", tokDesc); put(tok, "url", tokUrl);
        put(tok, "icon", tokIcon);
        put(tok, "pairs", pairsToJson(tokPairs));

        JSONObject coll = new JSONObject();
        put(coll, "name", createName); put(coll, "desc", createDesc); put(coll, "size", createSize);
        put(coll, "mode", createMode); put(coll, "base", createBase); put(coll, "ext", createExt);
        put(coll, "uploadedBase", createUploadedBase); put(coll, "uploadedExt", createUploadedExt);
        put(coll, "icon", createIcon); put(coll, "iconb64", createIconB64); put(coll, "external", createExternal); put(coll, "web", createWeb);
        JSONArray imgs = new JSONArray();
        for (String s : createImages) imgs.put(s == null ? "" : s);
        put(coll, "images", imgs);
        return new JSONObject[]{ nft, tok, coll };
    }

    private void restoreDrafts() {
        JSONObject nft = LocalStore.loadDraft(this, "nft");
        if (nft != null) {
            nftName = nft.optString("name", ""); nftDesc = nft.optString("desc", "");
            nftOwner = nft.optString("owner", ""); nftExternal = nft.optString("external", "");
            nftWeb = nft.optString("web", ""); nftUrl = nft.optString("url", "");
            nftEditions = nft.optString("editions", "1"); nftImage = nft.optString("image", "");
            nftEmbed = nft.optBoolean("embed", true); nftSign = nft.optBoolean("sign", true);
            nftTraits.clear();
            JSONArray tr = nft.optJSONArray("traits");
            if (tr != null) for (int i = 0; i < tr.length(); i++) {
                JSONObject a = tr.optJSONObject(i);
                if (a != null) nftTraits.add(new String[]{ a.optString("trait_type"), a.optString("value") });
            }
        }
        JSONObject tok = LocalStore.loadDraft(this, "token");
        if (tok != null) {
            tokName = tok.optString("name", ""); tokTicker = tok.optString("ticker", "");
            tokSupply = tok.optString("supply", "1000000"); tokDecimals = tok.optString("decimals", "0");
            tokDesc = tok.optString("desc", ""); tokUrl = tok.optString("url", "");
            tokIcon = tok.optString("icon", "");
            tokPairs.clear();
            JSONArray pr = tok.optJSONArray("pairs");
            if (pr != null) for (int i = 0; i < pr.length(); i++) {
                JSONObject a = pr.optJSONObject(i);
                if (a != null) tokPairs.add(new String[]{ a.optString("k"), a.optString("v") });
            }
        }
        JSONObject coll = LocalStore.loadDraft(this, "collection");
        if (coll != null) {
            createName = coll.optString("name", ""); createDesc = coll.optString("desc", "");
            createSize = coll.optString("size", "12"); createMode = coll.optString("mode", "url");
            createBase = coll.optString("base", ""); createExt = coll.optString("ext", ".jpg");
            createUploadedBase = coll.optString("uploadedBase", ""); createUploadedExt = coll.optString("uploadedExt", "");
            createIcon = coll.optString("icon", ""); createIconB64 = coll.optString("iconb64", ""); createExternal = coll.optString("external", "");
            createWeb = coll.optString("web", "");
            JSONArray imgs = coll.optJSONArray("images");
            if (imgs != null) {
                createImages = new String[imgs.length()];
                for (int i = 0; i < imgs.length(); i++) createImages[i] = imgs.optString(i, "");
            }
        }
    }

    private JSONArray pairsToJson(List<String[]> pairs) {
        JSONArray arr = new JSONArray();
        for (String[] p : pairs) {
            if (p == null || p.length < 2) continue;
            JSONObject o = new JSONObject();
            put(o, "k", p[0]); put(o, "v", p[1]);
            arr.put(o);
        }
        return arr;
    }

    private void setScreen(Screen s, Runnable back) {
        cancelImageRequests();
        cancelHostingCheck();
        screenGeneration++;
        screen = s;
        backAction = back;
        inlineStatus = null;
        for (Runnable r : departureWatchers) main.removeCallbacks(r);
        departureWatchers.clear();
        body.removeAllViews();
        content.scrollTo(0, 0);
    }

    /** App bar: optional back square, tracked title, vermilion node square. */
    private void appbar(CharSequence title, boolean showBack, boolean wordmark) {
        appbarBox.removeAllViews();
        LinearLayout bar = horizontal(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(8), dp(14), dp(8));
        if (showBack) {
            TextView bk = Design.text(this, "‹", 24, Design.PAPER(), Design.sansBold());
            bk.setGravity(Gravity.CENTER);
            bk.setBackground(Design.ripple(Design.rect(Design.INK())));
            bk.setClickable(true);
            bk.setOnClickListener(v -> { if (backAction != null) backAction.run(); });
            bar.addView(bk, new LinearLayout.LayoutParams(dp(44), dp(44)));
            bar.addView(new Space(this), new LinearLayout.LayoutParams(dp(10), 1));
        }
        TextView t;
        if (wordmark) {
            t = Design.display(this, "Atelier", 21);
            t.setLetterSpacing(0.16f);
        } else {
            t = Design.display(this, title, 15);
        }
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView ver = Design.text(this, "№ " + BuildConfig.VERSION_NAME, 9.5f, Design.DIM(), Design.mono());
        ver.setLetterSpacing(0.08f);
        ver.setPadding(dp(6), 0, 0, 0);
        bar.addView(ver);
        nodeDot = Design.text(this, "▪", 16, node != null && node.isEnabled() ? Design.ACCENT() : Design.SOFT(), Design.sansBold());
        nodeDot.setPadding(dp(6), 0, 0, 0);
        nodeDot.setOnClickListener(v -> renderManage());
        bar.addView(nodeDot);
        appbarBox.addView(bar);
        appbarBox.addView(Design.rule(this, 3));
    }

    private void bottomNav(int active) {
        navBox.removeAllViews();
        navBox.addView(Design.rule(this, 3));
        LinearLayout nav = horizontal(Gravity.CENTER);
        CharSequence[] labels = {Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_nft_wallet_gallery), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_studio), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_manage)};
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = Design.text(this, Language.upper(labels[i]), 10.5f,
                    active == i ? Design.PAPER() : Design.DIM(), Design.sansBold());
            t.setLetterSpacing(0.14f);
            t.setGravity(Gravity.CENTER);
            t.setBackground(active == i ? Design.rect(Design.INK()) : null);
            t.setClickable(true);
            t.setOnClickListener(v -> {
                if (idx == 0) renderGallery();
                else if (idx == 1) renderStudio();
                else renderManage();
            });
            nav.addView(t, new LinearLayout.LayoutParams(0, dp(50), 1));
        }
        navBox.addView(nav);
    }

    private void hideNav() {
        navBox.removeAllViews();
    }

    /** One-time healing: rows that were never engine-minted (created != 1)
     *  must prove the StateNFT script fingerprint or leave the collections
     *  store — a regular NFT filed here becomes a phantom collection with a
     *  placeholder cover (the Jazminima bug) AND gets excluded from the
     *  Singles classification. Legitimate adopted collections fingerprint
     *  fine and are untouched. */
    private boolean phantomsChecked = false;

    private void cleanupPhantomCollections() {
        if (phantomsChecked) return;
        phantomsChecked = true;
        JSONArray rows = LocalStore.load(this);
        ArrayList<long[]> candidates = new ArrayList<>();
        ArrayList<String> tokenids = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            String tid = r.optString("tokenid", "");
            if (tid.isEmpty()) continue;                    // mid-create engine rows stay
            if (r.optInt("created", 0) == 1) continue;      // engine-minted rows stay
            candidates.add(new long[]{ r.optLong("id") });
            tokenids.add(tid);
        }
        verifyPhantom(candidates, tokenids, 0, new int[]{0});
    }

    private void verifyPhantom(ArrayList<long[]> ids, ArrayList<String> tids, int i, int[] removed) {
        if (i >= ids.size()) {
            if (removed[0] > 0) {
                loadLocalCollections();
                lastGallerySig = "";
                if (screen == Screen.GALLERY) renderGallery();
                toast(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_atelier_gallery_unfiled, removed[0]));
            }
            return;
        }
        node.cmd("tokens tokenid:" + tids.get(i), new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject t = json == null ? null : json.optJSONObject("response");
                // A failed, missing, mismatched or incomplete lookup proves nothing about this collection.
                if (json == null || !Boolean.TRUE.equals(json.opt("status")) || t == null
                        || !(t.opt("tokenid") instanceof String)
                        || !tids.get(i).equalsIgnoreCase((String) t.opt("tokenid"))
                        || !(t.opt("script") instanceof String) || t.optString("script").trim().isEmpty()) {
                    onError(""); return;
                }
                String script = t.optString("script");
                if (StateNft.creatorPk(script).isEmpty()) {
                    LocalStore.removeById(MainActivity.this, ids.get(i)[0]);
                    removed[0]++;
                }
                verifyPhantom(ids, tids, i + 1, removed);
            }
            @Override public void onError(String message) { phantomsChecked = false; verifyPhantom(ids, tids, i + 1, removed); }
        });
    }

    /** Hand the mint to the foreground service (survives screen-off) and
     *  make sure its notification can actually be seen. */
    private void engageEngine() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                   != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ android.Manifest.permission.POST_NOTIFICATIONS }, 9001);
        }
        MintService.kick(this);
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_engine_engaged));
    }

    private void refreshNodeDot() {
        if (nodeDot != null) nodeDot.setTextColor(node != null && node.isEnabled() ? Design.ACCENT() : Design.SOFT());
    }

    private void setInlineStatus(CharSequence s) {
        if (inlineStatus != null) Language.setText(inlineStatus, s);
    }

    /* ================= LAUNCH ================= */

    private void renderLaunch() {
        setScreen(Screen.LAUNCH, null);
        appbarBox.removeAllViews();
        hideNav();
        body.addView(vspace(46));
        TextView word = Design.display(this, "Atelier", 40);
        word.setLetterSpacing(0.2f);
        body.addView(word);
        body.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_subtitle, BuildConfig.VERSION_NAME)), lpm(0, 6, 0, 18));
        body.addView(Design.rule(this, 3));
        body.addView(vspace(18));
        body.addView(Design.body(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_welcome)), lpm(0, 0, 0, 26));

        LinearLayout card = lotCard();
        card.addView(Design.kicker(this, "Minima Core"));
        boolean on = node != null && node.isEnabled();
        card.addView(Design.text(this, on ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_connected) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_node_not_ready), 14, on ? Design.ACCENT() : Design.DIM(), Design.sansBold()), lpm(0, 6, 0, 4));
        card.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_node_retry)));
        TextView open = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_history_open_core), true);
        open.setOnClickListener(v -> openMinimaCore());
        card.addView(open, lph(50, 0, 16, 0, 8));
        TextView cont = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_enter_studio), false);
        cont.setOnClickListener(v -> renderGallery());
        card.addView(cont, lph(48, 0, 0, 0, 0));
        body.addView(card);
    }

    /* ================= GALLERY ================= */

    private void renderGallery() {
        renderGallery(true);
    }

    /** withScan=false renders from the data already in hand — the scan
     *  callback uses it so a re-render never re-triggers the scan (the
     *  render↔scan loop was THE sluggishness bug). */
    private void renderGallery(boolean withScan) {
        setScreen(Screen.GALLERY, null);
        appbar(null, false, true);
        bottomNav(0);
        loadLocalCollections();

        LinearLayout chips = horizontal(Gravity.CENTER_VERTICAL);
        String[][] fs = {{"all", "All"}, {"coll", "Collections"}, {"nft", "NFTs"}, {"tok", "Tokens"}, {"mine", "Mine"}};
        int filterIndex = 0;
        CharSequence[] filterLabels = {Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_all), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_collections), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_nfts), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_core_wallet_tokens), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_mine)};
        for (String[] f : fs) {
            TextView chipView = Design.chip(this, filterLabels[filterIndex++], galleryFilter.equals(f[0]));
            chipView.setOnClickListener(v -> { galleryFilter = f[0]; renderGallery(); });
            chips.addView(chipView, new LinearLayout.LayoutParams(-2, -2){{ rightMargin = dp(6); }});
        }
        HorizontalScroll hs = new HorizontalScroll(this);
        hs.addView(chips);
        body.addView(hs, lpm(0, 0, 0, 14));

        boolean any = false;
        if (galleryFilter.equals("all") || galleryFilter.equals("coll") || galleryFilter.equals("mine")) {
            List<StateNft.Meta> list = new ArrayList<>();
            for (StateNft.Meta m : collections) {
                // burial means gone: a fresh grave shows for 50 blocks, then
                // the collection leaves the catalogue for good
                if ("BURIED".equals(m.phase)) {
                    boolean fresh = m.buriedAt > 0 && lastTip > 0 && (lastTip - m.buriedAt) <= 50;
                    if (!fresh) continue;
                }
                if (galleryFilter.equals("mine") && !m.created) continue;
                list.add(m);
            }
            if (!list.isEmpty()) {
                any = true;
                body.addView(sectionHead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_collections), String.valueOf(list.size())));
                for (StateNft.Meta m : list) body.addView(collectionCard(m), lpm(0, 0, 0, 14));
            }
        }
        if ((galleryFilter.equals("all") || galleryFilter.equals("nft")) && !nftRows.isEmpty()) {
            any = true;
            body.addView(sectionHead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_single_nfts), String.valueOf(nftRows.size())));
            LinearLayout row = null;
            for (int i = 0; i < nftRows.size(); i++) {
                if (i % 2 == 0) {
                    row = horizontal(Gravity.TOP);
                    body.addView(row, lpm(0, 0, 0, 12));
                }
                View card = nftCard(nftRows.get(i));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1);
                if (i % 2 == 0) clp.rightMargin = dp(6); else clp.leftMargin = dp(6);
                row.addView(card, clp);
                if (i == nftRows.size() - 1 && i % 2 == 0) row.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1){{ leftMargin = dp(6); }});
            }
        }
        if ((galleryFilter.equals("all") || galleryFilter.equals("tok")) && !tokenRows.isEmpty()) {
            any = true;
            body.addView(sectionHead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_core_wallet_tokens), String.valueOf(tokenRows.size())));
            for (JSONObject t : tokenRows) body.addView(tokenRow(t), lpm(0, 0, 0, 8));
        }

        if (!any) {
            LinearLayout empty = lotCard();
            empty.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_empty_catalogue)));
            empty.addView(Design.display(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_no_lots), 18), lpm(0, 4, 0, 6));
            empty.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_first_piece)));
            TextView go = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_open_studio), true);
            go.setOnClickListener(v -> renderStudio());
            empty.addView(go, lph(50, 0, 16, 0, 8));
            TextView scanB = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_scan_wallet), false);
            scanB.setOnClickListener(v -> renderScan());
            empty.addView(scanB, lph(48, 0, 0, 0, 0));
            body.addView(empty);
        }

        inlineStatus = Design.note(this, "");
        body.addView(inlineStatus, lpm(0, 10, 0, 0));
        if (withScan) galleryScan();
    }

    private LinearLayout sectionHead(CharSequence title, CharSequence count) {
        LinearLayout h = horizontal(Gravity.CENTER_VERTICAL);
        h.addView(Design.kicker(this, title), new LinearLayout.LayoutParams(0, -2, 1));
        h.addView(Design.text(this, count, 11, Design.DIM(), Design.mono()));
        LinearLayout box = vertical();
        box.addView(h);
        box.addView(Design.rule(this, 2), new LinearLayout.LayoutParams(-1, dp(2)){{ topMargin = dp(6); bottomMargin = dp(12); }});
        return box;
    }

    private View collectionCard(StateNft.Meta m) {
        LinearLayout card = vertical();
        card.setBackground(Design.blockShadow(this, Design.CARD()));
        card.setClickable(true);
        Design.pressable(card);
        card.setOnClickListener(v -> openCollection(m));

        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setImageBitmap(Identicon.forToken(m.tokenid, 600));
        String lead = collectionLeadImage(m);
        if (lead != null) ImageLoader.loadOver(this, lead, cover);
        card.addView(badgedImage(cover, m.tokenid, m.webvalidate, 26),
                new LinearLayout.LayoutParams(-1, dp(140)));
        card.addView(Design.rule(this, 2));

        LinearLayout inner = vertical();
        inner.setPadding(dp(12), dp(10), dp(12), dp(12));
        inner.addView(Design.lot(this, m.size > 0 ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_lot_range, String.format(Locale.US, "%03d", m.size)) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_lot_pending)));
        LinearLayout titleRow = horizontal(Gravity.CENTER_VERTICAL);
        TextView nm = Design.display(this, m.name, 14);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
        titleRow.addView(statusPill(m));
        inner.addView(titleRow, lpm(0, 3, 0, 4));
        inner.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_collection_summary, m.tokenid.isEmpty() ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_token_pending) : Util.shorten(m.tokenid), Math.max(0, m.minted), Math.max(m.size, 0), Math.max(0, m.owned))));
        inner.addView(Design.meter(this, collectionProgress(m)), new LinearLayout.LayoutParams(-1, dp(5)){{ topMargin = dp(8); }});
        card.addView(inner);
        return card;
    }

    /** Wrap a tile image so a web-validation plaque sits in its bottom-right
     *  corner when the collection's proof doc validates the tokenid — the
     *  over-image badge that distinguishes validated collections at a glance
     *  (NFTwallet parity: BalancesView + Identicon.checkBadge + WebValidate). */
    private FrameLayout badgedImage(ImageView img, String tokenid, String webvalidate, int badgeDp) {
        FrameLayout f = new FrameLayout(this);
        f.addView(img, new FrameLayout.LayoutParams(-1, -1));
        final int bs = dp(badgeDp);
        final Runnable paint = () -> {
            if (f.getChildCount() > 1) f.removeViewAt(1);
            if (Boolean.TRUE.equals(WebValidate.status(tokenid, webvalidate))) {
                ImageView badge = new ImageView(this);
                FrameLayout.LayoutParams blp =
                        new FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM | Gravity.END);
                blp.setMargins(0, 0, dp(7), dp(7));
                badge.setLayoutParams(blp);
                badge.setImageBitmap(Identicon.checkBadge(bs));
                f.addView(badge);
            }
        };
        paint.run();
        if (tokenid != null && !tokenid.isEmpty() && webvalidate != null && !webvalidate.isEmpty())
            WebValidate.ensure(this, f, tokenid, webvalidate, paint);
        return f;
    }

    private TextView statusPill(StateNft.Meta m) {
        String p = m.phase == null ? "DONE" : m.phase;
        if (activeMint(m)) {
            if ("NEEDIMAGES".equals(p)) return Design.pill(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_images), Design.PILL_ERR);
            return Design.pill(this, statusWord(p), Design.PILL_LIVE);
        }
        if ("BURY".equals(p)) return Design.pill(this, Language.msg(l10n_nft_burial_paused_title), Design.PILL_ERR);
        if ("BURIED".equals(p)) return Design.pill(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_buried), Design.PILL_DIM);
        if (m.size > 0 && m.minted >= m.size) return Design.pill(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_sealed), Design.PILL_DONE);
        return Design.pill(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_open), Design.PILL_DIM);
    }

    private CharSequence statusWord(String p) {
        if ("CREATE".equals(p)) return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_creating);
        if ("MOVE".equals(p)) return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_gather);
        if ("SPLIT".equals(p)) return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_splitting);
        if ("STAMP".equals(p)) return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_stamping);
        return p;
    }

    private View nftCard(JSONObject row) {
        String tid = row.optString("tokenid", "");
        StateNft.Meta meta = StateNft.parseMeta(tid, row.opt("token"));
        LinearLayout card = vertical();
        card.setBackground(Design.blockShadow(this, Design.CARD()));
        card.setClickable(true);
        Design.pressable(card);
        card.setOnClickListener(v -> openNftViewer(meta, row));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageBitmap(Identicon.forToken(tid, 400));
        String icon = IconResolver.resolve(meta.icon);
        if (icon != null) ImageLoader.loadOver(this, icon, img);
        card.addView(badgedImage(img, meta.tokenid, meta.webvalidate, 24),
                new LinearLayout.LayoutParams(-1, dp(120)));
        card.addView(Design.rule(this, 2));
        LinearLayout inner = vertical();
        inner.setPadding(dp(10), dp(8), dp(10), dp(10));
        TextView nm = Design.text(this, meta.name, 12, Design.INK(), Design.sansBold());
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        inner.addView(nm);
        inner.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_editions_summary, row.optString("total", "1"), Util.shorten(tid))));
        card.addView(inner);
        return card;
    }

    private View tokenRow(JSONObject row) {
        String tid = row.optString("tokenid", "");
        Object tok = row.opt("token");
        String name = Util.tokenName(tok);
        String ticker = "";
        JSONObject tokenObject = Util.tokenObject(tok);
        if (tokenObject != null) {
            JSONObject n = Util.asObject(tokenObject.opt("name"));
            ticker = n == null ? "" : n.optString("ticker", "");
            if (ticker.isEmpty()) ticker = tokenObject.optString("ticker", "");
        }
        LinearLayout r = horizontal(Gravity.CENTER_VERTICAL);
        r.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1.5f));
        r.setPadding(dp(10), dp(8), dp(10), dp(8));
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setImageBitmap(Identicon.forToken(tid, 120));
        StateNft.Meta meta = StateNft.parseMeta(tid, tok);
        String ic = IconResolver.resolve(meta.icon);
        if (ic != null) ImageLoader.loadOver(this, ic, icon);
        r.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout mid = vertical();
        mid.setPadding(dp(10), 0, 0, 0);
        mid.addView(Design.text(this, name + (ticker.isEmpty() ? "" : " · " + ticker.toUpperCase()), 12, Design.INK(), Design.sansBold()));
        mid.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_supply_summary, row.optString("total", "?"), Util.shorten(tid))));
        r.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
        TextView copy = Design.hash(this, "id");
        copy.setOnClickListener(v -> copyText(tid));
        r.addView(copy);
        return r;
    }

    private void openNftViewer(StateNft.Meta meta, JSONObject balRow) {
        StateNft.Item it = new StateNft.Item();
        it.index = 1;
        it.owned = parseIntSafe(balRow.optString("sendable", "0")) > 0 || !"0".equals(balRow.optString("confirmed", "0"));
        it.imageUrl = IconResolver.resolve(meta.icon);
        List<StateNft.Item> items = new ArrayList<>();
        items.add(it);
        if (meta.size <= 0) meta.size = 1;
        showViewer(meta, items, 0);
    }

    private void galleryScan() {
        if (node == null || !node.isEnabled()) return;
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) lastTip = r.optInt("block", lastTip);
            }
            @Override public void onError(String message) {}
        });
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONArray bal = json.optJSONArray("response");
                if (bal == null) return;
                HashSet<String> known = new HashSet<>();
                for (StateNft.Meta m : collections) known.add(m.tokenid);
                nftRows.clear();
                tokenRows.clear();
                ArrayList<StateNft.Meta> toAdopt = new ArrayList<>();
                for (int i = 0; i < bal.length(); i++) {
                    JSONObject row = bal.optJSONObject(i);
                    if (row == null) continue;
                    String tid = row.optString("tokenid", "");
                    if ("0x00".equals(tid) || known.contains(tid)) continue;
                    Object tok = row.opt("token");
                    JSONObject t = Util.tokenObject(tok);
                    if (t == null) { tokenRows.add(row); continue; }
                    JSONObject metaObj = Util.asObject(t.opt("name"));
                    if (metaObj == null) metaObj = t;
                    StateNft.Meta cand = StateNft.parseMeta(tid, t);
                    if (StateNft.isCandidate(cand)) {
                        // un-adopted StateNFT held in this wallet: adopt it
                        // automatically (script-fingerprint verified below) —
                        // received collections must never hide behind a manual scan
                        toAdopt.add(cand);
                        continue;
                    }
                    boolean isNft = "true".equals(metaObj.optString("nft", ""))
                            || (!metaObj.optString("url", "").isEmpty()
                                && parseIntSafe(row.optString("total", "0")) > 0
                                && parseIntSafe(row.optString("total", "0")) <= 100
                                && metaObj.optString("ticker", "").isEmpty());
                    if (isNft) nftRows.add(row); else tokenRows.add(row);
                }
                StringBuilder sig = new StringBuilder();
                for (JSONObject r : nftRows) sig.append(r.optString("tokenid")).append('|');
                sig.append('/');
                for (JSONObject r : tokenRows) sig.append(r.optString("tokenid")).append('|');
                if (!toAdopt.isEmpty()) autoAdopt(toAdopt, 0, 0);
                if (sig.toString().equals(lastGallerySig)) return;   // nothing new — no rebuild
                lastGallerySig = sig.toString();
                if (screen == Screen.GALLERY) renderGalleryPreserveScroll();
            }
            @Override public void onError(String message) { /* silent; dot shows state */ }
        });
    }

    /** Auto-adopt received StateNFT collections: verify the on-chain script
     *  fingerprint (the definitive test — metadata alone is never enough),
     *  then file them so they appear without a manual Scan Wallet. */
    private void autoAdopt(ArrayList<StateNft.Meta> cands, int i, int adopted) {
        if (i >= cands.size()) {
            if (adopted > 0) {
                loadLocalCollections();
                lastGallerySig = "";
                if (screen == Screen.GALLERY) renderGalleryPreserveScroll();
            }
            return;
        }
        StateNft.Meta cand = cands.get(i);
        node.cmd("tokens tokenid:" + cand.tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject t = json.optJSONObject("response");
                String pk = StateNft.creatorPk(t == null ? "" : t.optString("script", ""));
                if (pk.isEmpty()) { autoAdopt(cands, i + 1, adopted); return; }
                StateNft.Meta m = StateNft.parseMeta(cand.tokenid, t);
                m.creatorPk = pk;
                countOwned(m, () -> {
                    if (LocalStore.findByTokenid(MainActivity.this, m.tokenid) == null) {
                        m.localId = LocalStore.nextId(MainActivity.this);
                        m.phase = "DONE";
                        m.created = false;
                        LocalStore.upsert(MainActivity.this, MintEngine.rowFromMeta(m, new JSONArray()));
                        LocalStore.logEvent(MainActivity.this, "Adopted “" + m.name + "” — found in wallet");
                        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_adopted, m.name));
                    }
                    autoAdopt(cands, i + 1, adopted + 1);
                });
            }
            @Override public void onError(String message) { autoAdopt(cands, i + 1, adopted); }
        });
    }

    private void renderGalleryPreserveScroll() {
        int y = content.getScrollY();
        renderGallery(false);
        content.post(() -> content.scrollTo(0, y));
    }

    /* ================= COLLECTION DETAIL ================= */

    private void openCollection(StateNft.Meta m) {
        if (m == null) { renderGallery(); return; }
        selfHealed = false;
        setScreen(Screen.COLLECTION, this::renderGallery);
        appbar(m.name, true, false);
        bottomNav(0);
        openMeta = m;
        detailPartial = false;
        detailLoading = false;
        openOwned = new JSONArray();
        openAll = new JSONArray();
        lastDetailSig = "";
        body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_reading)), lpm(0, 8, 0, 0));
        refreshDetail();
    }

    private boolean collectionCurrent(StateNft.Meta m, long generation) {
        return !isFinishing() && !isDestroyed() && generation == screenGeneration
                && screen == Screen.COLLECTION && openMeta != null
                && openMeta.localId == m.localId && java.util.Objects.equals(openMeta.tokenid, m.tokenid);
    }

    private boolean detailCurrent(StateNft.Meta m, long generation, long request) {
        return request == detailRequest && openMeta == m && collectionCurrent(m, generation);
    }

    private void refreshDetail() {
        StateNft.Meta m = openMeta;
        final long generation = screenGeneration, request = ++detailRequest;
        if (m == null || !collectionCurrent(m, generation)) return;
        detailLoading = true;
        if (m.tokenid == null || m.tokenid.isEmpty()) {
            openOwned = new JSONArray();
            openAll = new JSONArray();
            detailPartial = false;
            detailLoading = false;
            renderDetail();
            return;
        }
        MintEngine.tokenCoinsBounded(node, m.tokenid, true, (coins, partial) -> {
            if (!detailCurrent(m, generation, request)) return;
            MintEngine.tokenCoinsBounded(node, m.tokenid, false, (all, partial2) -> {
                if (!detailCurrent(m, generation, request)) return;
                applyDetailCoins(m, coins, all.length() == 0 ? coins : all, partial || partial2);
            }, e -> {
                if (!detailCurrent(m, generation, request)) return;
                applyDetailCoins(m, coins, coins, true);
            });
        }, e -> {
            if (!detailCurrent(m, generation, request)) return;
            detailLoading = false;
            detailPartial = true;
            renderDetail();
        });
    }

    /** Commit both reads together so overlapping refreshes cannot mix their coins. */
    private void applyDetailCoins(StateNft.Meta m, JSONArray owned, JSONArray all, boolean partial) {
        detailLoading = false;
        openOwned = owned;
        openAll = all;
        detailPartial = partial;
        m.owned = owned.length();
        m.totalSeen = all.length();
        m.minted = distinctStamped(all);
        if (m.size <= 0) m.size = Math.max(m.minted, Math.max(m.totalSeen, m.owned));
        persistCounts(m);
        renderDetail();
    }

    private void persistCounts(StateNft.Meta m) {
        JSONObject row = LocalStore.findById(this, m.localId);
        if (row == null) row = LocalStore.findByTokenid(this, m.tokenid);
        if (row == null) return;
        put(row, "owned", m.owned);
        put(row, "minted", m.minted);
        LocalStore.upsert(this, row);
    }

    /* A row marked DONE while the chain still shows unsealed lots is the
     * false-DONE of 2026-08-10 (reserved-but-dropped stamps counted as seals):
     * re-point the engine at the chain and let it finish. Once per visit. */
    private boolean selfHealed = false;

    private void maybeSelfHeal(StateNft.Meta m) {
        if (selfHealed || m == null || !m.created || m.size <= 0) return;
        if (!"DONE".equals(m.phase) || m.minted >= m.size) return;
        JSONObject row = LocalStore.findById(this, m.localId);
        if (row == null) row = LocalStore.findByTokenid(this, m.tokenid);
        if (row == null) return;
        selfHealed = true;
        LocalStore.logEvent(this, m.name + ": marked DONE but chain shows " + m.minted
                + "/" + m.size + " sealed — self-recovering");
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_resuming, m.minted, m.size));
        final long generation = screenGeneration;
        MintEngine.recover(this, node, row, lastTip, msg -> runOnUiThread(() -> {
            if (!collectionCurrent(m, generation)) return;
            // a failed recover (chain unreadable) leaves the row on DONE —
            // unlatch so the next refresh retries instead of going silent
            JSONObject after = LocalStore.findById(this, m.localId);
            if (after != null && "DONE".equals(after.optString("phase"))) selfHealed = false;
            engageEngine();
            loadLocalCollections();
            openMeta = findCollectionByLocalId(m.localId, m);
            lastDetailSig = "";
            refreshDetail();
        }));
    }

    private void renderDetail() {
        StateNft.Meta m = openMeta;
        if (m == null || screen != Screen.COLLECTION) return;
        if (!detailPartial) maybeSelfHeal(m);
        // 25s engine ticks re-enter here: skip the full view-tree rebuild
        // (and its mid-scroll stutter) when nothing user-visible changed
        JSONObject dropState = AirdropEngine.job(this, m.localId);
        String sig = m.localId + "|" + m.phase + "|" + m.minted + "|" + m.owned + "|" + m.totalSeen
                + "|" + m.error + "|" + openOwned.length() + "|" + openAll.length() + "|" + detailPartial
                + "|" + (LocalStore.lastHeartbeat(this) / 30000)
                + "|" + (dropState == null ? 0 : dropState.toString().hashCode());
        if (sig.equals(lastDetailSig)) return;
        lastDetailSig = sig;
        body.removeAllViews();

        List<StateNft.Item> items = StateNft.items(m, openOwned, openAll);
        if (m.created && (m.tokenid == null || m.tokenid.isEmpty())) items = localItemsForMeta(m);
        else if (m.created) items = mergeLocalPreviews(m, items);
        final List<StateNft.Item> shown = items;

        /* hero */
        FrameLayout heroBox = new FrameLayout(this);
        heroBox.setBackground(Design.blockShadow(this, Design.CARD()));
        ImageView hero = new ImageView(this);
        hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.setImageBitmap(Identicon.forToken(m.tokenid, 900));
        String heroUrl = !shown.isEmpty() && shown.get(0).imageUrl != null && !shown.get(0).imageUrl.isEmpty()
                ? shown.get(0).imageUrl : IconResolver.resolve(m.icon);
        if (heroUrl != null && !heroUrl.isEmpty()) ImageLoader.loadFull(this, heroUrl, hero);
        FrameLayout.LayoutParams heroLp = new FrameLayout.LayoutParams(-1, dp(170));
        int off = dp(3);
        heroLp.setMargins(0, 0, off, off);
        heroBox.addView(hero, heroLp);
        /* web-validation plaque over the hero */
        {
            final int bs = dp(30);
            final FrameLayout hb = heroBox;
            final Runnable paint = () -> {
                if (Boolean.TRUE.equals(WebValidate.status(m.tokenid, m.webvalidate)) && hb.findViewWithTag("vbadge") == null) {
                    ImageView badge = new ImageView(this);
                    badge.setTag("vbadge");
                    FrameLayout.LayoutParams blp =
                            new FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM | Gravity.END);
                    blp.setMargins(0, 0, dp(11), dp(11));
                    badge.setLayoutParams(blp);
                    badge.setImageBitmap(Identicon.checkBadge(bs));
                    hb.addView(badge);
                }
            };
            paint.run();
            if (!m.tokenid.isEmpty() && !m.webvalidate.isEmpty())
                WebValidate.ensure(this, hb, m.tokenid, m.webvalidate, paint);
        }
        heroBox.setClickable(true);
        heroBox.setOnClickListener(v -> { if (!shown.isEmpty()) showViewer(m, shown, 0); });
        body.addView(heroBox, lpm(0, 4, 0, 12));

        body.addView(Design.lot(this, m.size > 0 ? Language.join(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_lot_range, String.format(Locale.US, "%03d", m.size)), " · ", modeWord(m)) : modeWord(m)));

        LinearLayout meta = lotCard();
        TextView tokHash = Design.hash(this, m.tokenid.isEmpty() ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_token_pending) : Util.shorten(m.tokenid));
        if (!m.tokenid.isEmpty()) tokHash.setOnClickListener(v -> copyText(m.tokenid));
        LinearLayout hr = horizontal(Gravity.CENTER_VERTICAL);
        hr.addView(tokHash);
        hr.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1));
        hr.addView(statusPill(m));
        meta.addView(hr);
        meta.addView(kvRow(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_editions), String.valueOf(m.size)));
        meta.addView(kvRow(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_sealed), m.minted + " / " + m.size));
        meta.addView(kvRow(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_custody), String.valueOf(m.owned)));
        if (m.error != null && !m.error.isEmpty()) {
            meta.addView(Design.text(this, m.error, 11, Design.ACCENT(), Design.sansBold()), lpm(0, 6, 0, 0));
        }
        if (detailPartial) {
            meta.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_partial)), lpm(0, 6, 0, 0));
        }
        body.addView(meta, lpm(0, 8, 0, 12));

        if (activeMint(m)) {
            body.addView(phaseRail(m), lpm(0, 0, 0, 6));
            body.addView(Design.meter(this, collectionProgress(m)), new LinearLayout.LayoutParams(-1, dp(6)){{ bottomMargin = dp(8); }});
            long beat = LocalStore.lastHeartbeat(this);
            long age = beat == 0 ? Long.MAX_VALUE : (System.currentTimeMillis() - beat) / 1000;
            LinearLayout beatRow = horizontal(Gravity.CENTER_VERTICAL);
            beatRow.addView(Design.pill(this, age < 90 ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_engine_live) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_engine_asleep),
                    age < 90 ? Design.PILL_DONE : Design.PILL_ERR));
            beatRow.addView(Design.note(this, age < 90
                    ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_tick, age)
                    : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_no_tick)), new LinearLayout.LayoutParams(0, -2, 1){{ leftMargin = dp(8); }});
            beatRow.setClickable(true);
            Design.pressable(beatRow);
            final StateNft.Meta mF = m;
            beatRow.setOnClickListener(v -> renderEngineLog(() -> openCollection(mF)));
            body.addView(beatRow, lpm(0, 0, 0, 12));
        }
        if ("NEEDIMAGES".equals(m.phase)) body.addView(recoveryPanel(m), lpm(0, 0, 0, 12));

        LinearLayout actions = horizontal(Gravity.CENTER_VERTICAL);
        TextView refresh = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_refresh), false);
        refresh.setOnClickListener(v -> refreshDetail());
        refresh.setMinHeight(dp(48));
        LinearLayout.LayoutParams refreshLp = weight(48, 0, 4); refreshLp.height = -2;
        actions.addView(refresh, refreshLp);
        if (m.created && activeMint(m) && !"NEEDIMAGES".equals(m.phase)) {
            TextView resume = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_resume), true);
            resume.setOnClickListener(v -> {
                engageEngine();
                loadLocalCollections();
                openMeta = findCollectionByLocalId(m.localId, m);
                refreshDetail();
            });
            resume.setMinHeight(dp(48));
            LinearLayout.LayoutParams resumeLp = weight(48, 4, 4); resumeLp.height = -2;
            actions.addView(resume, resumeLp);
            /* Recover: re-derive this mint's phase from the chain (clears
             * local locks and counters, posts nothing) — the manual twin of
             * the stall watchdog. */
            TextView doctor = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_recover), false);
            doctor.setOnClickListener(v -> {
                JSONObject row = LocalStore.findById(this, m.localId);
                if (row == null) row = LocalStore.findByTokenid(this, m.tokenid);
                if (row == null) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_missing_row)); return; }
                toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_reading));
                final long generation = screenGeneration;
                MintEngine.recover(this, node, row, lastTip, msg -> runOnUiThread(() -> {
                    if (!collectionCurrent(m, generation)) return;
                    toast(msg);
                    engageEngine();
                    loadLocalCollections();
                    openMeta = findCollectionByLocalId(m.localId, m);
                    lastDetailSig = "";
                    refreshDetail();
                }));
            });
            doctor.setMinHeight(dp(48));
            LinearLayout.LayoutParams doctorLp = weight(48, 4, 4); doctorLp.height = -2;
            actions.addView(doctor, doctorLp);
        }
        TextView bury = Design.button(this, Language.msg("BURY".equals(m.phase) ? l10n_nft_burial_check_retry : l10n_atelier_gallery_bury), false);
        bury.setOnClickListener(v -> renderBury());
        bury.setMinHeight(dp(48));
        LinearLayout.LayoutParams buryLp = weight(48, 4, 0); buryLp.height = -2;
        actions.addView(bury, buryLp);
        body.addView(actions, lpm(0, 0, 0, 14));

        /* airdrop: progress panel when a job runs, dispatch button when lots are deliverable */
        JSONObject dropJob = AirdropEngine.job(this, m.localId);
        if (dropJob != null) {
            int[] prog = AirdropEngine.progress(dropJob);
            final long dropGeneration = screenGeneration;
            final String dropIdentity = dropJob.optString("jobid");
            final boolean verifiedDrop = AirdropEngine.verifiedJob(dropJob);
            LinearLayout panel = vertical();
            panel.setBackground(Design.ruled(this, Design.CARD(), Design.ACCENT(), 2f));
            panel.setPadding(dp(12), dp(10), dp(12), dp(12));
            panel.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_airdrop)));
            panel.addView(Design.text(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_delivered, prog[0], prog[1]), 13, Design.INK(), Design.sansBold()), lpm(0, 4, 0, 6));
            panel.addView(Design.meter(this, prog[1] == 0 ? 0f : prog[0] / (float) prog[1]), new LinearLayout.LayoutParams(-1, dp(5)){{ bottomMargin = dp(8); }});
            JSONArray es = dropJob.optJSONArray("entries");
            if (es != null) for (int i = 0; i < es.length(); i++) {
                JSONObject e = es.optJSONObject(i);
                if (e == null) continue;
                String st = AirdropEngine.status(verifiedDrop, e);
                CharSequence err = AirdropEngine.errorText(e);
                panel.addView(kvRow(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_lot, String.format(Locale.US, "%03d", e.optInt("idx"))),
                        (err.length() > 0) ? Language.join(dropStatus(st), " — ", err) : Language.join(dropStatus(st), " → ", Util.shorten(e.optString("addr")))));
            }
            panel.addView(Design.note(this, Language.msg(l10n_atelier_delivery_help)), lpm(0, 8, 0, 0));
            boolean checkable = false;
            if (verifiedDrop && es != null) for (int i=0;i<es.length();i++) {
                JSONObject entry=es.optJSONObject(i);
                if (entry.has("receipt") && !"DONE".equals(entry.optString("status"))) checkable=true;
            }
            if (checkable) {
                TextView check = Design.button(this, Language.msg(l10n_atelier_delivery_check), false);
                check.setMinHeight(dp(48));
                check.setOnClickListener(v -> {
                    if (!collectionCurrent(m, dropGeneration)) return;
                    JSONObject current = AirdropEngine.job(this,m.localId);
                    if (current==null || !dropIdentity.equals(current.optString("jobid"))) return;
                    if (!AirdropEngine.checkJob(this,m.localId)) { toast(Language.msg(l10n_atelier_delivery_save_failed)); return; }
                    engageEngine(); refreshDetail();
                });
                panel.addView(check, lpm(0, 8, 0, 0));
            }
            TextView clear = Design.button(this, prog[0] >= prog[1] ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_clear_record) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_abandon), false);
            clear.setOnClickListener(v -> {
                if (!collectionCurrent(m, dropGeneration)) return;
                JSONObject current = AirdropEngine.job(this,m.localId);
                if (current==null || !dropIdentity.equals(current.optString("jobid"))) return;
                if (!AirdropEngine.clearJob(this, m.localId)) { toast(Language.msg(l10n_atelier_delivery_save_failed)); return; }
                refreshDetail();
            });
            clear.setMinHeight(dp(48));
            panel.addView(clear, lpm(0, 8, 0, 0));
            body.addView(panel, lpm(0, 0, 0, 14));
        } else {
            List<StateNft.Item> transferable = new ArrayList<>();
            for (StateNft.Item it : shown) if (it.owned && it.coin != null && StateNft.stamped(it.coin) != null) transferable.add(it);
            if (!transferable.isEmpty()) {
                TextView drop = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_send_lots, transferable.size()), false);
                drop.setOnClickListener(v -> renderAirdrop(m, transferable));
                drop.setMinHeight(dp(48));
                body.addView(drop, lpm(0, 0, 0, 14));
            }
        }

        body.addView(sectionHead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_view_lots), shown.size() + ""));
        if (shown.isEmpty()) {
            body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_empty)));
            return;
        }
        int cols = 3;
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % cols == 0) {
                row = horizontal(Gravity.TOP);
                body.addView(row, lpm(0, 0, 0, 8));
            }
            View tile = itemTile(m, shown, i);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(112), 1);
            if (i % cols != 0) tlp.leftMargin = dp(8);
            row.addView(tile, tlp);
        }
        int rem = shown.size() % cols;
        if (rem != 0) {
            for (int i = 0; i < cols - rem; i++) {
                row.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1){{ leftMargin = dp(8); }});
            }
        }
    }

    private CharSequence modeWord(StateNft.Meta m) {
        return "embed".equalsIgnoreCase(m.mode) ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_embedded) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_hosted);
    }

    private View itemTile(StateNft.Meta m, List<StateNft.Item> items, int index) {
        StateNft.Item it = items.get(index);
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(Design.ruled(this, Design.CARD(), it.owned ? Design.ACCENT() : Design.INK(), 1.5f));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageBitmap(Identicon.forToken(m.tokenid + it.index, 300));
        if (it.imageUrl != null && !it.imageUrl.isEmpty()) ImageLoader.loadOver(this, it.imageUrl, img);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-1, -1);
        int b = dp(2);
        ilp.setMargins(b, b, b, b);
        tile.addView(img, ilp);
        TextView tag = Design.pill(this, String.format(Locale.US, "%02d", it.index),
                it.owned ? Design.PILL_MINE : (it.coin != null ? Design.PILL_DONE : Design.PILL_DIM));
        FrameLayout.LayoutParams tagLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        tagLp.setMargins(dp(5), 0, 0, dp(5));
        tile.addView(tag, tagLp);
        tile.setClickable(true);
        tile.setOnClickListener(v -> showViewer(m, items, index));
        return tile;
    }

    private LinearLayout phaseRail(StateNft.Meta m) {
        CharSequence[] names = {Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_create), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_gather), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_split), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_stamp), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_complete)};
        int current = phaseIndex(m.phase == null ? "" : m.phase);
        LinearLayout rail = horizontal(Gravity.TOP);
        for (int i = 0; i < names.length; i++) {
            LinearLayout nodeBox = vertical();
            nodeBox.setGravity(Gravity.CENTER_HORIZONTAL);
            View sq = new View(this);
            boolean done = current > i || "DONE".equals(m.phase);
            boolean now = current == i && !"DONE".equals(m.phase);
            sq.setBackground(done ? Design.rect(Design.INK())
                    : now ? Design.rect(Design.ACCENT())
                    : Design.ruled(this, Design.PAPER(), Design.INK(), 1.5f));
            nodeBox.addView(sq, new LinearLayout.LayoutParams(dp(12), dp(12)));
            TextView lbl = Design.text(this, Language.upper(names[i]), 7.5f,
                    now ? Design.ACCENT() : done ? Design.INK() : Design.DIM(), Design.sansBold());
            lbl.setLetterSpacing(0.1f);
            nodeBox.addView(lbl, lpm(0, 4, 0, 0));
            rail.addView(nodeBox, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return rail;
    }

    private CharSequence dropStatus(String state) {
        if ("UNKNOWN".equals(state) || "POSTING".equals(state)) return Language.msg(l10n_atelier_delivery_unverified);
        if ("PENDING".equals(state)) return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_pending);
        if ("POSTED".equals(state)) return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_posted);
        if ("DONE".equals(state)) return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_done);
        if ("FAIL".equals(state)) return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_failed);
        return state;
    }

    private int phaseIndex(String phase) {
        if ("CREATE".equals(phase)) return 0;
        if ("MOVE".equals(phase)) return 1;
        if ("SPLIT".equals(phase)) return 2;
        if ("STAMP".equals(phase) || "NEEDIMAGES".equals(phase)) return 3;
        if ("DONE".equals(phase)) return 4;
        return 0;
    }

    /* ================= VIEWER (overlay) ================= */

    private void showViewer(StateNft.Meta m, List<StateNft.Item> items, int index) {
        overlay.removeAllViews();
        View v = ViewerScreen.build(this, this, m, items, index);
        v.setPadding(0, 0, 0, insetBottom);
        overlay.addView(v, new FrameLayout.LayoutParams(-1, -1));
        overlay.setVisibility(View.VISIBLE);
        new WindowInsetsControllerCompat(getWindow(), rootFrame).setAppearanceLightStatusBars(false);
    }

    @Override public void onCloseViewer() {
        overlay.setVisibility(View.GONE);
        overlay.removeAllViews();
        new WindowInsetsControllerCompat(getWindow(), rootFrame).setAppearanceLightStatusBars(true);
    }

    /** The Plate Room — full-screen image editor overlay. */
    private void showEditor(String b64, int budget, ImageEditor.Done done) {
        final long generation = screenGeneration;
        final int context = screen == Screen.COLLECTION ? PICK_RECOVERY : PICK_CREATE;
        final long recovery = openMeta == null ? 0 : openMeta.localId;
        final String target = imageTarget(context,recovery);
        final View[] editor = {null};
        final java.util.function.BooleanSupplier current = () -> !isFinishing() && !isDestroyed()
                && generation == screenGeneration && target.equals(imageTarget(context,recovery))
                && overlay.getChildCount() == 1 && overlay.getChildAt(0) == editor[0];
        overlay.removeAllViews();
        View v = ImageEditor.build(this, b64, budget, () -> { if (current.getAsBoolean()) onCloseViewer(); }, edited -> {
            if (!current.getAsBoolean()) return;
            onCloseViewer(); done.edited(edited); saveDrafts();
        });
        editor[0] = v;
        v.setPadding(0, 0, 0, insetBottom);
        overlay.addView(v, new FrameLayout.LayoutParams(-1, -1));
        overlay.setVisibility(View.VISIBLE);
        new WindowInsetsControllerCompat(getWindow(), rootFrame).setAppearanceLightStatusBars(false);
    }

    @Override public void onTransferItem(StateNft.Item item) {
        onCloseViewer();
        if (openMeta != null && item.coin != null) renderTransfer(item);
    }

    /* ================= TRANSFER ================= */

    private void renderTransfer(StateNft.Item it) {
        StateNft.Meta m = openMeta;
        if (m == null || it.coin == null) return;
        transferItem = it;
        setScreen(Screen.TRANSFER, () -> openCollection(m));
        final long generation = screenGeneration;
        appbar(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_lot, String.format(Locale.US, "%03d", it.index)), true, false);
        hideNav();

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageBitmap(Identicon.forToken(m.tokenid + it.index, 600));
        if (it.imageUrl != null && !it.imageUrl.isEmpty()) ImageLoader.loadFull(this, it.imageUrl, img);
        LinearLayout frame = vertical();
        frame.setBackground(Design.blockShadow(this, Design.CARD()));
        frame.addView(img, new LinearLayout.LayoutParams(-1, dp(200)){{ setMargins(0, 0, dp(3), dp(3)); }});
        body.addView(frame, lpm(0, 4, 0, 14));

        LinearLayout card = lotCard();
        card.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_title)));
        card.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_help)), lpm(0, 4, 0, 8));
        card.addView(kvRow(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_identity), StateNft.state(it.coin, 0) == null ? "—" : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_port, StateNft.state(it.coin, 0))));
        card.addView(kvRow(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_image), StateNft.state(it.coin, 1) == null ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_hosted) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_embedded)));
        body.addView(card, lpm(0, 0, 0, 14));

        body.addView(Design.kicker(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_recipient)));
        EditText to = input(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_send_address_hint));
        to.setMinHeight(dp(50));
        body.addView(to, lpm(0, 6, 0, 8));
        TextView status = Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_ready));
        body.addView(status, lpm(0, 0, 0, 10));
        TextView go = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_submit), true);
        go.setOnClickListener(v -> doTransfer(m, it, to, status, go, generation));
        go.setMinHeight(dp(54));
        body.addView(go, lpm(0, 0, 0, 0));
    }

    private boolean transferScreenActive(long generation) {
        return !isFinishing() && !isDestroyed() && screen == Screen.TRANSFER && generation == screenGeneration;
    }

    private void doTransfer(StateNft.Meta meta, StateNft.Item it, EditText to, TextView msg, TextView go, long generation) {
        if (!transferScreenActive(generation) || !go.isEnabled()) return;
        String addr = to.getText().toString().trim();
        if (!Util.isValidAddress(addr)) {
            Language.setText(msg, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_invalid_address)); return;
        }
        if (!LocalStore.canDepart(this, meta.tokenid)) {
            Language.setText(msg, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_nft_actions_mint_unfinished)); return;
        }
        final JSONObject coin, intent;
        try {
            if (!it.owned || StateNft.stamped(it.coin) == null) throw BurialEvidence.invalid();
            coin = new JSONObject(it.coin.toString());
            intent = BurialEvidence.intent(coin, meta.tokenid);
        } catch (Exception e) {
            Language.setText(msg, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_nft_actions_invalid_departure)); return;
        }
        // A post error can mean an unknown outcome. Never silently enable another submission.
        go.setEnabled(false);
        to.setEnabled(false);
        Language.setText(msg, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_building));
        String txn = "statenft" + java.util.UUID.randomUUID().toString().replace("-", "");
        MintEngine.postTxnWithReceipt(node, txn, StateNft.transferCommands(txn, meta.tokenid, coin, addr), posted -> {
            if (!transferScreenActive(generation)) return;
            final JSONObject receipt;
            try { receipt = BurialEvidence.receipt(posted, intent, addr); }
            catch (RuntimeException e) {
                Language.setText(msg, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_nft_actions_output_unverified)); return;
            }
            Language.setText(msg, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_posted));
            watchDeparture(meta.tokenid, receipt, generation, () -> {
                toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_confirmed, it.index));
                openCollection(meta);
            }, () -> Language.setText(msg, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_unconfirmed)));
        }, e -> {
            if (transferScreenActive(generation)) Language.setText(msg,
                    Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_failed, e));
        });
    }

    /* ================= BURY ================= */

    private void renderBury() {
        StateNft.Meta m = openMeta;
        if (m == null) return;
        setScreen(Screen.BURY, () -> openCollection(m));
        final long generation = screenGeneration;
        appbar(Language.msg(l10n_atelier_burial_title), true, false);
        hideNav();
        body.addView(Design.lot(this, Language.msg(l10n_atelier_burial_final)));
        body.addView(Design.display(this, m.name, 22), lpm(0, 4, 0, 14));
        LinearLayout facts = lotCard();
        facts.addView(kvRow(Language.msg(l10n_atelier_burial_owned), String.valueOf(m.owned)));
        facts.addView(kvRow(Language.msg(l10n_atelier_burial_destination), Util.shorten(StateNft.GRAVEYARD)));
        facts.addView(kvRow(Language.msg(l10n_atelier_burial_contract), Language.msg(l10n_atelier_burial_no_exit)));
        body.addView(facts, lpm(0, 0, 0, 12));
        LinearLayout warn = vertical();
        warn.setBackground(Design.ruled(this, Design.CARD(), Design.ACCENT(), 2f));
        warn.setPadding(dp(12), dp(10), dp(12), dp(12));
        warn.addView(Design.text(this, Language.msg(l10n_atelier_burial_permanent), 13, Design.ACCENT(), Design.sansBold()));
        warn.addView(Design.note(this, Language.msg(l10n_atelier_burial_custody)), lpm(0, 4, 0, 0));
        body.addView(warn, lpm(0, 0, 0, 14));
        body.addView(Design.kicker(this, Language.msg(l10n_atelier_burial_type_name)));
        EditText confirm = input(m.name);
        confirm.setMinHeight(dp(50));
        body.addView(confirm, lpm(0, 6, 0, 8));
        TextView msg = Design.note(this, Language.msg("BURY".equals(m.phase) ? l10n_nft_burial_paused : l10n_atelier_burial_arm));
        body.addView(msg, lpm(0, 0, 0, 10));
        TextView go = Design.button(this, Language.msg("BURY".equals(m.phase) ? l10n_nft_burial_check_retry : l10n_atelier_burial_submit), true);
        go.setOnClickListener(v -> {
            if (!burialScreenActive(generation) || !go.isEnabled()) return;
            if (!m.name.equals(confirm.getText().toString().trim())) {
                Language.setText(msg, Language.msg(l10n_atelier_burial_wrong_name)); return;
            }
            go.setEnabled(false); confirm.setEnabled(false);
            buryOwnedCoins(m, msg, generation);
        });
        go.setMinHeight(dp(54));
        body.addView(go, lpm(0, 0, 0, 0));
    }

    private boolean burialScreenActive(long generation) {
        return !isFinishing() && !isDestroyed() && screen == Screen.BURY && generation == screenGeneration;
    }

    /** NFT Wallet's persistent intent/receipt flow, hosted in Atelier's retained screen. */
    private void buryOwnedCoins(StateNft.Meta m, TextView msg, long generation) {
        try {
            LocalStore.burialJournal(this, m.tokenid);
            if (AirdropEngine.job(this, m.localId) != null) {
                Language.setText(msg, Language.msg(l10n_atelier_burial_airdrop_active)); return;
            }
            if (!MintEngine.pauseForBurial(this, m.tokenid)) {
                Language.setText(msg, Language.msg(l10n_nft_burial_pause_failed)); return;
            }
            m.phase = "BURY";
        } catch (RuntimeException e) {
            Language.setText(msg, Language.msg(l10n_nft_burial_evidence_missing)); return;
        }
        Language.setText(msg, Language.msg(l10n_nft_actions_finding_coins));
        node.cmd("coins relevant:true own:true tokenid:" + m.tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!burialScreenActive(generation)) return;
                final List<JSONObject> coins = new ArrayList<>();
                try {
                    JSONArray arr = BurialEvidence.coins(json, m.tokenid);
                    JSONArray journal = LocalStore.burialJournal(MainActivity.this, m.tokenid);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject coin = arr.getJSONObject(i);
                        if (BurialEvidence.same(StateNft.GRAVEYARD, coin.optString("address"))) throw BurialEvidence.invalid();
                        boolean preserve = StateNft.replayableState(coin);
                        if ((!preserve || StateNft.stamped(coin) == null) && !Util.isValidHexId(m.creatorPk)) throw BurialEvidence.invalid();
                        JSONObject old = burialEntry(journal, coin.optString("coinid"));
                        // Preserve a previously chosen stripped-state fallback on an explicit retry.
                        if (old != null) preserve = BurialEvidence.preserves(old);
                        JSONObject intent = BurialEvidence.burialIntent(coin, m.tokenid, preserve);
                        if (old == null) journal.put(intent);
                        else if (!old.optString("statehash").equals(intent.optString("statehash"))
                                || !old.optString("tokenamount").equals(intent.optString("tokenamount"))) throw BurialEvidence.invalid();
                        coins.add(coin);
                    }
                    if (!LocalStore.saveBurialJournal(MainActivity.this, m.tokenid, journal)) throw BurialEvidence.invalid();
                    if (coins.isEmpty()) {
                        if (journal.length() == 0) Language.setText(msg, Language.msg(l10n_nft_actions_no_coins));
                        else watchBurialCompletion(m, msg, generation);
                        return;
                    }
                } catch (Exception e) {
                    Language.setText(msg, Language.msg(l10n_nft_burial_evidence_missing)); return;
                }
                buryNext(m, coins, 0, msg, generation);
            }
            @Override public void onError(String message) {
                if (burialScreenActive(generation)) Language.setText(msg, Language.msg(l10n_atelier_burial_failed, message));
            }
        });
    }

    private static JSONObject burialEntry(JSONArray journal, String input) {
        for (int i = 0; i < journal.length(); i++) {
            JSONObject entry = journal.optJSONObject(i);
            if (entry != null && BurialEvidence.same(input, entry.optString("input"))) return entry;
        }
        return null;
    }

    private void saveBurialEntry(String tokenid, JSONObject entry) {
        try {
            JSONArray journal = LocalStore.burialJournal(this, tokenid);
            boolean replaced = false;
            for (int j = 0; j < journal.length(); j++) {
                if (BurialEvidence.same(entry.optString("input"), journal.getJSONObject(j).optString("input"))) {
                    journal.put(j, entry); replaced = true; break;
                }
            }
            if (!replaced || !LocalStore.saveBurialJournal(this, tokenid, journal)) throw BurialEvidence.invalid();
        } catch (Exception e) { throw BurialEvidence.invalid(); }
    }

    private void buryNext(StateNft.Meta m, List<JSONObject> coins, int i, TextView msg, long generation) {
        if (!burialScreenActive(generation)) return;
        if (i >= coins.size()) { watchBurialCompletion(m, msg, generation); return; }
        JSONObject coin = coins.get(i);
        final JSONObject intent;
        try {
            intent = burialEntry(LocalStore.burialJournal(this, m.tokenid), coin.optString("coinid"));
            if (intent == null) throw BurialEvidence.invalid();
            if (!intent.optString("output").isEmpty()) { buryNext(m, coins, i + 1, msg, generation); return; }
            if ((!BurialEvidence.preserves(intent) || StateNft.stamped(coin) == null) && !Util.isValidHexId(m.creatorPk)) throw BurialEvidence.invalid();
        } catch (RuntimeException e) {
            Language.setText(msg, Language.msg(l10n_nft_burial_evidence_missing)); return;
        }
        Language.setText(msg, Language.msg(l10n_nft_actions_burying_item, i + 1, coins.size()));
        runBury(m, coins, i, intent, msg, generation);
    }

    private void runBury(StateNft.Meta m, List<JSONObject> coins, int i, JSONObject intent, TextView msg, long generation) {
        if (!burialScreenActive(generation)) return;
        JSONObject coin = coins.get(i);
        boolean preserve = BurialEvidence.preserves(intent);
        String creator = !preserve || StateNft.stamped(coin) == null ? m.creatorPk : "";
        String txn = "bury" + java.util.UUID.randomUUID().toString().replace("-", "");
        MintEngine.postTxnWithReceipt(node, txn, StateNft.buryCommands(txn, m.tokenid, creator, coin, preserve), posted -> {
            try {
                // Persist an accepted receipt even if the user left the screen while posting.
                saveBurialEntry(m.tokenid, BurialEvidence.receipt(posted, intent));
            } catch (RuntimeException e) {
                if (burialScreenActive(generation)) Language.setText(msg, Language.msg(l10n_nft_burial_evidence_missing));
                return;
            }
            buryNext(m, coins, i + 1, msg, generation);
        }, error -> {
            if (!burialScreenActive(generation)) return;
            if (preserve && error != null && error.toLowerCase(Locale.ROOT).contains("size too large") && Util.isValidHexId(m.creatorPk)) {
                try {
                    JSONObject stripped = BurialEvidence.burialIntent(coin, m.tokenid, false);
                    saveBurialEntry(m.tokenid, stripped); // Durable before the fallback signature.
                    runBury(m, coins, i, stripped, msg, generation);
                } catch (RuntimeException e) { Language.setText(msg, Language.msg(l10n_nft_burial_evidence_missing)); }
            } else Language.setText(msg, Language.msg(l10n_atelier_burial_failed, error == null ? "" : error));
        });
    }

    private void watchBurialCompletion(StateNft.Meta m, TextView msg, long generation) {
        final JSONArray journal;
        try {
            journal = LocalStore.burialJournal(this, m.tokenid);
            if (journal.length() == 0) throw BurialEvidence.invalid();
            for (int i = 0; i < journal.length(); i++) BurialEvidence.id(journal.getJSONObject(i), "output");
        } catch (Exception e) {
            Language.setText(msg, Language.msg(l10n_nft_burial_evidence_missing)); return;
        }
        Language.setText(msg, Language.msg(l10n_nft_burial_waiting));
        final int[] tries = {0};
        final Runnable[] poll = new Runnable[1];
        final Runnable retry = () -> {
            if (!burialScreenActive(generation) || !departureWatchers.contains(poll[0])) return;
            if (++tries[0] >= 20) {
                departureWatchers.remove(poll[0]);
                Language.setText(msg, Language.msg(l10n_nft_burial_unconfirmed));
            } else main.postDelayed(poll[0], 20000);
        };
        poll[0] = () -> {
            if (!burialScreenActive(generation) || !departureWatchers.contains(poll[0])) return;
            verifyBurialOutputs(m, journal, 0, generation, () -> {
                node.cmd("coins relevant:true own:true tokenid:" + m.tokenid, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject json) {
                        if (!burialScreenActive(generation)) return;
                        try {
                            if (BurialEvidence.coins(json, m.tokenid).length() != 0) { retry.run(); return; }
                            if (!BurialEvidence.sameJournal(journal, LocalStore.burialJournal(MainActivity.this, m.tokenid), m.tokenid)) {
                                departureWatchers.remove(poll[0]);
                                Language.setText(msg, Language.msg(l10n_nft_burial_evidence_missing)); return;
                            }
                        } catch (RuntimeException e) { retry.run(); return; }
                        node.cmd("block", new NodeApi.Cb() {
                            @Override public void onResult(JSONObject block) {
                                if (!burialScreenActive(generation)) return;
                                JSONObject response = block.optJSONObject("response");
                                Object raw = response == null ? null : response.opt("block");
                                int tip = response == null ? 0 : response.optInt("block", 0);
                                if (!Boolean.TRUE.equals(block.opt("status")) || raw == null || !raw.toString().matches("[0-9]+") || tip <= 0) { retry.run(); return; }
                                try {
                                    if (!LocalStore.completeBurial(MainActivity.this, m.tokenid, journal, tip)) { retry.run(); return; }
                                } catch (RuntimeException e) { retry.run(); return; }
                                departureWatchers.remove(poll[0]);
                                m.phase = "BURIED";
                                MintEngine.clearWalletCoinCache();
                                loadLocalCollections(); lastDetailSig = "";
                                Language.setText(msg, Language.msg(l10n_nft_burial_confirmed));
                            }
                            @Override public void onError(String error) { retry.run(); }
                        });
                    }
                    @Override public void onError(String error) { retry.run(); }
                });
            }, retry);
        };
        departureWatchers.add(poll[0]);
        main.postDelayed(poll[0], 20000);
    }

    private void verifyBurialOutputs(StateNft.Meta m, JSONArray journal, int i, long generation, Runnable confirmed, Runnable retry) {
        if (!burialScreenActive(generation)) return;
        if (i >= journal.length()) { confirmed.run(); return; }
        JSONObject receipt = journal.optJSONObject(i);
        node.cmd("coins coinid:" + receipt.optString("output"), new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!burialScreenActive(generation)) return;
                try {
                    JSONArray coins = BurialEvidence.coins(json, m.tokenid);
                    if (coins.length() != 1 || !BurialEvidence.confirmed(receipt, coins.optJSONObject(0))) { retry.run(); return; }
                } catch (RuntimeException e) { retry.run(); return; }
                verifyBurialOutputs(m, journal, i + 1, generation, confirmed, retry);
            }
            @Override public void onError(String error) { retry.run(); }
        });
    }

    /* ================= STUDIO ================= */

    private void renderStudio() {
        setScreen(Screen.STUDIO, null);
        appbar(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_studio), false, false);
        bottomNav(1);

        body.addView(studioCard("№ 1", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_collection_title),
                Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_collection_help),
                Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_engine), v -> renderCreateCollection()), lpm(0, 0, 0, 14));
        body.addView(studioCard("№ 2", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_single_nft),
                Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_single_help),
                null, v -> renderCreateNft()), lpm(0, 0, 0, 14));
        body.addView(studioCard("№ 3", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_custom_token),
                Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_custom_help),
                null, v -> renderCreateToken()), lpm(0, 0, 0, 14));
        body.addView(studioCard("№ 4", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_generative),
                Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_generative_help),
                "artBox", v -> renderGenerative()), lpm(0, 0, 0, 14));
        body.addView(studioCard("№ 5", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_filtr),
                Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_filtr_help),
                "filtr", v -> launchFiltr()), lpm(0, 0, 0, 14));

        /* drafts shelf */
        boolean hasNftDraft = !nftName.trim().isEmpty() || !nftImage.isEmpty() || !nftTraits.isEmpty();
        boolean hasTokDraft = !tokName.trim().isEmpty() || !tokPairs.isEmpty() || !tokIcon.isEmpty();
        boolean hasCollDraft = !createName.trim().isEmpty() || anyCreateImage();
        if (hasNftDraft || hasTokDraft || hasCollDraft) {
            body.addView(sectionHead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_drafts), ""));
            if (hasCollDraft) body.addView(draftRow(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_nft_collection), createName, v -> renderCreateCollection(), v -> {
                resetCollectionDraft();
                LocalStore.clearDraft(this, "collection");
                renderStudio();
            }), lpm(0, 0, 0, 8));
            if (hasNftDraft) body.addView(draftRow("NFT", nftName, v -> renderCreateNft(), v -> {
                nftName = ""; nftDesc = ""; nftOwner = ""; nftExternal = ""; nftWeb = ""; nftUrl = "";
                nftEditions = "1"; nftImage = ""; nftTraits.clear();
                traitTypeDraft = ""; traitValueDraft = "";
                LocalStore.clearDraft(this, "nft");
                renderStudio();
            }), lpm(0, 0, 0, 8));
            if (hasTokDraft) body.addView(draftRow(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_token), tokName, v -> renderCreateToken(), v -> {
                tokName = ""; tokTicker = ""; tokSupply = "1000000"; tokDecimals = "0"; tokDesc = ""; tokUrl = ""; tokIcon = "";
                tokPairs.clear();
                pairKeyDraft = ""; pairValueDraft = "";
                LocalStore.clearDraft(this, "token");
                renderStudio();
            }), lpm(0, 0, 0, 8));
        }
    }

    private boolean anyCreateImage() {
        for (String s : createImages) if (s != null && !s.isEmpty()) return true;
        return false;
    }

    private View draftRow(CharSequence kind, String name, View.OnClickListener resume, View.OnClickListener discard) {
        LinearLayout row = horizontal(Gravity.CENTER_VERTICAL);
        row.setBackground(Design.dashed(this, Design.CARD(), Design.INK()));
        row.setPadding(dp(10), dp(8), dp(6), dp(8));
        LinearLayout copy = vertical();
        copy.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_draft, kind)));
        copy.addView(Design.text(this, name == null || name.trim().isEmpty() ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_untitled) : name.trim(),
                12, Design.INK(), Design.sansBold()));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView res = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_logs_resume), true);
        res.setOnClickListener(resume);
        res.setMinHeight(dp(48));
        row.addView(res, new LinearLayout.LayoutParams(dp(88), -2));
        TextView disc = Design.text(this, "×", 18, Design.ACCENT(), Design.sansBold());
        disc.setPadding(dp(12), 0, dp(12), 0);
        disc.setClickable(true);
        disc.setOnClickListener(discard);
        row.addView(disc);
        return row;
    }

    private View studioCard(String no, CharSequence title, CharSequence descText, CharSequence pillText, View.OnClickListener click) {
        LinearLayout card = vertical();
        card.setBackground(Design.blockShadow(this, Design.CARD()));
        card.setPadding(dp(14), dp(12), dp(16), dp(15));
        card.setClickable(true);
        Design.pressable(card);
        card.setOnClickListener(click);
        card.addView(Design.lot(this, no));
        LinearLayout titleRow = horizontal(Gravity.CENTER_VERTICAL);
        titleRow.addView(Design.display(this, title, 15), new LinearLayout.LayoutParams(0, -2, 1));
        if (pillText != null) titleRow.addView(Design.pill(this, pillText, Design.PILL_LIVE));
        card.addView(titleRow, lpm(0, 4, 0, 5));
        card.addView(Design.note(this, descText));
        return card;
    }

    /* ---- collection wizard (engine-backed, restyled) ---- */

    private void renderCreateCollection() {
        setScreen(Screen.CREATE_COLLECTION, this::renderStudio);
        appbar(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_new_collection), true, false);
        hideNav();

        EditText name = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_name), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_name_hint), createName);
        name.addTextChangedListener(watch(sv -> createName = sv));
        EditText desc = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_description), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_description_hint), createDesc);
        desc.addTextChangedListener(watch(sv -> createDesc = sv));
        EditText size = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_editions), "12", createSize);
        size.addTextChangedListener(watch(sv -> createSize = sv));

        body.addView(Design.kicker(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_image_source)), lpm(0, 8, 0, 6));
        LinearLayout mode = horizontal(Gravity.CENTER);
        TextView mUrl = Design.chip(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_hosted), "url".equals(createMode));
        TextView mEmb = Design.chip(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_embed), "embed".equals(createMode));
        final EditText[] baseF = new EditText[1], extF = new EditText[1], iconF = new EditText[1], extUrlF = new EditText[1], webF = new EditText[1];
        mUrl.setOnClickListener(v -> { saveCollectionDraft(name, desc, size, baseF[0], extF[0], iconF[0], extUrlF[0], webF[0]); createMode = "url"; renderCreateCollection(); });
        mEmb.setOnClickListener(v -> { saveCollectionDraft(name, desc, size, baseF[0], extF[0], iconF[0], extUrlF[0], webF[0]); createMode = "embed"; renderCreateCollection(); });
        mUrl.setMinHeight(dp(48)); mEmb.setMinHeight(dp(48));
        LinearLayout.LayoutParams urlLp = weight(48, 0, 4); urlLp.height = -2;
        LinearLayout.LayoutParams embedLp = weight(48, 4, 0); embedLp.height = -2;
        mode.addView(mUrl, urlLp);
        mode.addView(mEmb, embedLp);
        body.addView(mode, lpm(0, 0, 0, 10));

        if ("url".equals(createMode)) {
            baseF[0] = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_base), "https://…/collection/", createBase);
            baseF[0].addTextChangedListener(watch(sv -> createBase = sv));
            extF[0] = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_extension), ".jpg", createExt);
            extF[0].addTextChangedListener(watch(sv -> createExt = sv));
            // Safety net: a recorded good upload but a drifted Base URL → one-tap restore.
            if (!createUploadedBase.isEmpty() && !createUploadedBase.equals(createBase)) {
                TextView restore = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_restore_base), false);
                restore.setOnClickListener(v -> { createBase = createUploadedBase; createExt = createUploadedExt; renderCreateCollection(); });
                restore.setMinHeight(dp(48));
                body.addView(restore, lpm(0, 6, 0, 2));
                body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_uploaded_at, createUploadedBase + "1" + createUploadedExt)), lpm(0, 0, 0, 6));
            }
            final EditText sizeField = size;
            body.addView(hostingUploadRow("collplates",
                    Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_upload_plates),
                    () -> { saveCollectionDraft(name, desc, size, baseF[0], extF[0], iconF[0], extUrlF[0], webF[0]);
                            pickHostPlates(clampSize(parseIntSafe(sizeField.getText().toString()))); }),
                    lpm(0, 6, 0, 0));
        } else {
            int n = clampSize(parseIntSafe(size.getText().toString()));
            ensureCreateImages(n);
            TextView refresh = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_update_slots), false);
            refresh.setOnClickListener(v -> {
                saveCollectionDraft(name, desc, size, baseF[0], extF[0], iconF[0], extUrlF[0], webF[0]);
                ensureCreateImages(clampSize(parseIntSafe(size.getText().toString())));
                renderCreateCollection();
            });
            refresh.setMinHeight(dp(48));
            body.addView(refresh, lpm(0, 0, 0, 8));
            TextView pick = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_choose_images), true);
            pick.setOnClickListener(v -> {
                saveCollectionDraft(name, desc, size, baseF[0], extF[0], iconF[0], extUrlF[0], webF[0]);
                pickImages();
            });
            pick.setMinHeight(dp(48));
            body.addView(pick, lpm(0, 0, 0, 10));
            boolean anyPlate = false;
            for (String s : createImages) if (s != null && !s.isEmpty()) { anyPlate = true; break; }
            if (anyPlate) {
                TextView resetAll = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_reset_plates), false);
                resetAll.setOnClickListener(v -> new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                        .setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_reset_plates))
                        .setMessage(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_reset_body))
                        .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_future_reset), (d, w) -> {
                            saveCollectionDraft(name, desc, size, baseF[0], extF[0], iconF[0], extUrlF[0], webF[0]);
                            createImages = new String[createImages.length];
                            renderCreateCollection();
                        })
                        .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_backup_keep), null)
                        .show());
                resetAll.setMinHeight(dp(48));
                body.addView(resetAll, lpm(0, 0, 0, 8));
            }
            body.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_proof_sheet)), lpm(0, 0, 0, 6));
            body.addView(imageSlotGrid(), lpm(0, 0, 0, 8));
        }

        iconF[0] = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_icon_url_optional), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_icon_hint), createIcon);
        iconF[0].addTextChangedListener(watch(sv -> createIcon = sv));
        body.addView(hostingUploadRow("collicon", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_upload_icon_host),
                () -> { saveCollectionDraft(name, desc, size, baseF[0], extF[0], iconF[0], extUrlF[0], webF[0]);
                        pickHostSingle(PICK_HOST_ICON, "collicon"); }), lpm(0, 4, 0, 0));
        body.addView(iconUploadRow(createIconB64, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_icon_help),
                v -> { pendingPickContext = PICK_COLLICON; pendingBatchImages = false; launchPicker(false); },
                v -> { createIconB64 = ""; renderCreateCollection(); }), lpm(0, 4, 0, 8));
        extUrlF[0] = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_external_url), "https://…", createExternal);
        extUrlF[0].addTextChangedListener(watch(sv -> createExternal = sv));
        body.addView(hostingUploadRow("collext", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_upload_full),
                () -> { saveCollectionDraft(name, desc, size, baseF[0], extF[0], iconF[0], extUrlF[0], webF[0]);
                        pickHostSingle(PICK_HOST_EXTERNAL, "collext"); }), lpm(0, 4, 0, 0));
        webF[0] = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_webvalidate), "https://…/validate", createWeb);
        webF[0].addTextChangedListener(watch(sv -> createWeb = sv));
        body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_validation_help)), lpm(0, 4, 0, 0));
        // wv-auto seals the doc's URL on-chain BEFORE the doc exists — impossible on
        // content-addressed Arweave (the URL is unknowable pre-upload), so hide it there.
        Hosting.Profile wvHp = HostingStore.getDefault(this);
        if (wvHp != null && !Hosting.TYPE_ARWEAVE.equals(wvHp.type())) {
            LinearLayout wvRow = horizontal(Gravity.CENTER_VERTICAL);
            android.widget.CheckBox wvChk = new android.widget.CheckBox(this);
            wvChk.setChecked(createWvAuto);
            wvChk.setOnCheckedChangeListener((b, on) -> { createWvAuto = on; if (on) createWeb = ""; renderCreateCollection(); });
            wvRow.addView(wvChk, new LinearLayout.LayoutParams(-2, -2));
            wvRow.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_auto_validation)),
                    new LinearLayout.LayoutParams(0, -2, 1){{ leftMargin = dp(6); }});
            body.addView(wvRow, lpm(0, 6, 0, 0));
        }

        TextView go = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_mint), true);
        go.setOnClickListener(v -> createCollection(name, desc, size, baseF[0], extF[0], iconF[0], extUrlF[0], webF[0]));
        go.setMinHeight(dp(54));
        body.addView(go, lpm(0, 10, 0, 8));
        body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_engine_help)));
    }

    private int clampSize(int n) { return Math.max(2, Math.min(20, n == 0 ? 12 : n)); }

    private void createCollection(EditText name, EditText desc, EditText size, EditText base,
                                  EditText ext, EditText icon, EditText external, EditText web) {
        if (collectionBusy) return;
        saveCollectionDraft(name, desc, size, base, ext, icon, external, web);
        String n = name.getText().toString().trim();
        String d = desc.getText().toString().trim();
        String s = size.getText().toString().trim();
        String bs = base == null ? "" : base.getText().toString().trim();
        String x = ext == null ? ".jpg" : ext.getText().toString().trim();
        String ic = icon.getText().toString().trim();
        String ex = external.getText().toString().trim();
        String w = web.getText().toString().trim();
        if (n.isEmpty() || n.length() > 40) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_name_required)); return; }
        if (d.length() > 200) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_description_range)); return; }
        int count = parseIntSafe(s);
        if (count < 2 || count > 20) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_editions_range)); return; }
        if ("url".equals(createMode) && !bs.startsWith("https://")) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_base_https)); return; }
        if (x.isEmpty()) x = ".jpg";
        if (!validCmdUrl(ic) || !validCmdUrl(ex) || !validCmdUrl(w) || !validCmdUrl(bs)) {
            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_safe_urls));
            return;
        }
        final String extValue = x;
        if ("embed".equals(createMode)) {
            ensureCreateImages(count);
            for (int i = 0; i < count; i++) {
                if (createImages[i] == null || createImages[i].isEmpty()) {
                    toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_missing_image, i + 1));
                    return;
                }
            }
        }
        // Foreign-base guard: in url-mode, if the Base URL wasn't produced by uploading plates
        // for THIS collection (nothing recorded, or it no longer matches), it may point at a
        // DIFFERENT collection's images that merely happen to serve — the Gilbert & George
        // incident, where a live-but-wrong base sailed through verify-before-mint. Confirm intent.
        if ("url".equals(createMode) && !mintDraftSnapshot(2).equals(confirmedCollectionDraft)
                && (createUploadedBase.isEmpty() || !bs.equals(createUploadedBase))) {
            final String confirmationDraft = mintDraftSnapshot(2);
            final long confirmationScreen = screenGeneration;
            new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                    .setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_base_confirm))
                    .setMessage(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_confirm_body, bs + "1" + x))
                    .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_mint_anyway), (dg, wch) -> {
                            if (confirmationScreen != screenGeneration || !confirmationDraft.equals(mintDraftSnapshot(2))) { mintDraftChanged(); return; }
                            confirmedCollectionDraft = confirmationDraft;
                            createCollection(name, desc, size, base, ext, icon, external, web); })
                    .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_cancel), null)
                    .show();
            return;
        }
        // verify-before-mint: every hosted URL the mint will seal must serve
        if (!hostGatePassed) {
            java.util.List<String> urls = new java.util.ArrayList<>();
            if ("url".equals(createMode)) { urls.add(bs + "1" + x); urls.add(bs + count + x); }
            if (!ic.isEmpty() && ic.startsWith("http")) urls.add(ic);
            if (!ex.isEmpty()) urls.add(ex);
            // web-validation URL is NOT gated: the proof doc is usually written
            // AFTER mint (it must contain the tokenid, which only exists then).
            // An absent doc just means "not yet validated", never a failed mint.
            if (!urls.isEmpty()) {
                verifyHostedThen(urls,
                        () -> { hostGatePassed = true;
                                try { createCollection(name, desc, size, base, ext, icon, external, web); }
                                finally { hostGatePassed = false; } },
                        badMsg -> offerUploadedBaseRecovery(bs, extValue, badMsg));
                return;
            }
        }
        hostGatePassed = false;
        confirmedCollectionDraft = null;
        // auto web-validation: derive a path on the default hosting profile and
        // bake its URL into the token now; the engine writes the tokenid there
        // once minting reveals it
        String wvRel = "", effWeb = w;
        Hosting.Profile hp = HostingStore.getDefault(this);
        // arweave: URL unknowable before upload — never auto-seal (checkbox is hidden too)
        boolean wvAuto = createWvAuto && hp != null && !Hosting.TYPE_ARWEAVE.equals(hp.type());
        if (wvAuto) {
            wvRel = "atelier/" + Hosting.slug(n) + "-validate-" + Hosting.ts36() + ".txt";
            effWeb = Hosting.publicUrl(hp, wvRel, false);
        }
        final String bsF = bs, icF = ic, exF = ex, wF = effWeb, nF = n, dF = d;
        final String wvRelF = wvRel; final boolean wvAutoF = wvAuto;
        final int countF = count;
        final String collectionDraft = mintDraftSnapshot(2);
        final long collectionScreen = screenGeneration;
        collectionBusy = true;
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                collectionBusy = false;
                if (collectionScreen != screenGeneration || !collectionDraft.equals(mintDraftSnapshot(2))) { mintDraftChanged(); return; }
                JSONObject r = json == null || !Boolean.TRUE.equals(json.opt("status")) ? null : json.optJSONObject("response");
                if (r == null || r.optString("publickey").isEmpty() || r.optString("address").isEmpty()) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_address_failed)); return; }
                StateNft.Meta m = new StateNft.Meta();
                m.localId = LocalStore.nextId(MainActivity.this);
                m.name = nF;
                m.description = dF;
                m.mode = createMode;
                m.size = countF;
                m.base = "url".equals(createMode) ? bsF : "";
                m.ext = extValue;
                String leadImg = "embed".equals(createMode) && createImages.length > 0 ? createImages[0] : "";
                m.icon = !icF.isEmpty() ? icF
                        : !createIconB64.isEmpty() ? createIconB64
                        : leadImg.isEmpty() ? ""
                        : "image/svg+xml".equals(ImageTools.mimeOf(leadImg))
                            ? (leadImg.length() <= ImageTools.ICON_BUDGET ? leadImg : "")
                            // slot-1 fallback: square-crop so the wallet tile fills edge-to-edge
                            : ImageTools.iconFromBase64(leadImg, ImageTools.ICON_BUDGET);
                m.externalUrl = exF;
                m.webvalidate = wF;
                m.creatorAddr = r.optString("address");
                m.creatorPk = r.optString("publickey");
                m.phase = "CREATE";
                m.creator = true;
                m.created = true;
                JSONArray items = new JSONArray();
                for (int i = 1; i <= countF; i++) {
                    JSONObject it = new JSONObject();
                    put(it, "idx", i);
                    put(it, "image", "embed".equals(createMode) ? createImages[i - 1] : (bsF + i + extValue));
                    items.put(it);
                }
                /* Final exact gate: name/desc/icon/traits are all known here.
                 * Refuse to create a collection whose lots could never leave
                 * the wallet (joint transfer budget - the "Random" lesson). */
                if ("embed".equals(createMode)) {
                    /* ALWAYS SIGNED envelope, FIT-then-refuse: the real name/
                     * desc/URLs land here and can outweigh the handoff's
                     * estimate — re-run the icon ladder (slim -> drop) before
                     * ever refusing. Only traits/text overweight refuses. */
                    JSONObject metaJ = StateNft.tokenMetadata(m);
                    if (collectionItemTraits != null && collectionItemTraits.length() > 0)
                        put(metaJ, "traits", collectionItemTraits);
                    int room = MintEngine.imageBudget(metaJ.toString().length());
                    /* The icon must never starve the plates: an embedded icon
                     * (up to 6000 chars, incl. the silent plate-1 fallback)
                     * inside the record squeezed every plate to ~4K — below
                     * even the deep ladder's comfort. Slim/drop the icon
                     * whenever the plates need more room than it leaves, not
                     * only when the record fails outright (2026-08-10). */
                    int need = 0;
                    for (String im0 : createImages) {
                        if (im0 != null) need = Math.max(need, Math.min(im0.length(), PLATE_MIN_ROOM));
                    }
                    if (room < need && !m.icon.isEmpty() && !m.icon.startsWith("http")) {
                        /* Slim the icon to the room ACTUALLY spare once the
                         * plates get theirs — a hardcoded 2000 sat below the
                         * deep ladder's floor for busy images, so a chosen
                         * icon got dropped and plate 1 showed instead
                         * (2026-08-11). */
                        String iconSave = m.icon;
                        m.icon = "";
                        JSONObject metaNoIcon = StateNft.tokenMetadata(m);
                        if (collectionItemTraits != null && collectionItemTraits.length() > 0)
                            put(metaNoIcon, "traits", collectionItemTraits);
                        // −64: embedding the icon adds its own metadata overhead
                        // ("url":"<artimage>…"); a slim landing flush on the cap
                        // failed the recheck and dropped the icon anyway
                        int iconAllow = MintEngine.imageBudget(metaNoIcon.toString().length()) - need - 64;
                        m.icon = iconSave;
                        // 1200 = the ShrinkLadderTest-guaranteed floor (pure
                        // noise fits it); below that an icon would be mush —
                        // drop it instead
                        String slim = iconAllow >= 1200
                                ? ImageTools.iconFromBase64(m.icon, Math.min(iconAllow, ImageTools.ICON_BUDGET))
                                : "";
                        if (!slim.isEmpty()) {
                            m.icon = slim;
                            metaJ = StateNft.tokenMetadata(m);
                            if (collectionItemTraits != null && collectionItemTraits.length() > 0)
                                put(metaJ, "traits", collectionItemTraits);
                            room = MintEngine.imageBudget(metaJ.toString().length());
                            if (room >= need) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_icon_slimmed));
                        }
                        if (room < need) {
                            m.icon = "";
                            metaJ = StateNft.tokenMetadata(m);
                            if (collectionItemTraits != null && collectionItemTraits.length() > 0)
                                put(metaJ, "traits", collectionItemTraits);
                            room = MintEngine.imageBudget(metaJ.toString().length());
                            if (room >= 0) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_icon_dropped));
                        }
                    }
                    if (room < 0) {
                        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_metadata_too_large, metaJ.toString().length(), MintEngine.META_MAX));
                        return;
                    }
                    for (int ii = 0; ii < createImages.length; ii++) {
                        String im = createImages[ii];
                        if (im == null || im.length() <= room) continue;
                        if ("image/svg+xml".equals(ImageTools.mimeOf(im))) {
                            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_svg_too_large, ii + 1, im.length(), room));
                            return;
                        }
                        String out = ImageTools.recompressBase64(im, room);
                        if (out.isEmpty()) {
                            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_image_too_large, ii + 1, room));
                            return;
                        }
                        createImages[ii] = out;
                        put(items.optJSONObject(ii), "image", out);
                    }
                }
                JSONObject row = MintEngine.rowFromMeta(m, items);
                if (wvAutoF) { put(row, "wvauto", 1); put(row, "wvrel", wvRelF); put(row, "wvdone", 0); }
                if (collectionItemTraits != null && collectionItemTraits.length() > 0
                        && "embed".equals(createMode)) {
                    put(row, "itemtraits", collectionItemTraits);
                }
                LocalStore.upsert(MainActivity.this, row);
                resetCollectionDraft();
                toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_mint_started));
                engageEngine();
                loadLocalCollections();
                openCollection(findCollectionByLocalId(m.localId, m));
            }
            @Override public void onError(String message) { collectionBusy = false; toast(message); }
        });
    }

    /* ---- single NFT (NFTwallet-compatible metadata) ---- */

    private void renderCreateNft() {
        setScreen(Screen.CREATE_NFT, this::renderStudio);
        appbar(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_new_nft), true, false);
        hideNav();

        body.addView(Design.kicker(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_artwork)), lpm(0, 0, 0, 6));
        LinearLayout modeRow = horizontal(Gravity.CENTER);
        TextView mEmb = Design.chip(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_embed), nftEmbed);
        TextView mUrl = Design.chip(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_hosted), !nftEmbed);
        mEmb.setOnClickListener(v -> { nftEmbed = true; renderCreateNft(); });
        mUrl.setOnClickListener(v -> { nftEmbed = false; renderCreateNft(); });
        mEmb.setMinHeight(dp(48)); mUrl.setMinHeight(dp(48));
        LinearLayout.LayoutParams embedLp = weight(48, 0, 4); embedLp.height = -2;
        LinearLayout.LayoutParams urlLp = weight(48, 4, 0); urlLp.height = -2;
        modeRow.addView(mEmb, embedLp);
        modeRow.addView(mUrl, urlLp);
        body.addView(modeRow, lpm(0, 0, 0, 10));

        if (nftEmbed) {
            FrameLayout frame = new FrameLayout(this);
            frame.setBackground(Design.blockShadow(this, Design.CARD()));
            ImageView art = new ImageView(this);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setImageBitmap(Identicon.forToken("nftdraft", 500));
            if (!nftImage.isEmpty()) ImageLoader.loadOver(this, ImageTools.dataUri(nftImage), art);
            FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(-1, dp(190));
            alp.setMargins(0, 0, dp(3), dp(3));
            frame.addView(art, alp);
            frame.setClickable(true);
            frame.setOnClickListener(v -> pickNftImage());
            body.addView(frame, lpm(0, 0, 0, 6));
            body.addView(Design.note(this, nftImage.isEmpty() ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_choose_image) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_replace_image)), lpm(0, 0, 0, 8));
            if (!nftImage.isEmpty()) {
                if ("image/svg+xml".equals(ImageTools.mimeOf(nftImage))) {
                    body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_svg_help)), lpm(0, 0, 0, 10));
                } else {
                    TextView edit = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_edit_image), false);
                    edit.setOnClickListener(v -> showEditor(nftImage, ImageTools.ARTIMAGE_BUDGET, edited -> {
                        nftImage = edited;
                        renderCreateNft();
                    }));
                    body.addView(edit, lph(46, 0, 0, 0, 10));
                }
            }
        } else {
            EditText urlF = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_image_url), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_image_url_hint), nftUrl);
            urlF.addTextChangedListener(watch(sv -> nftUrl = sv));
            body.addView(hostingUploadRow("nfturl", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_upload_artwork),
                    () -> pickHostSingle(PICK_HOST_EXTERNAL, "nfturl")), lpm(0, 4, 0, 0));
        }

        EditText nameF = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_title), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_title_hint), nftName);
        nameF.addTextChangedListener(watch(sv -> nftName = sv));
        EditText descF = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_description), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_optional), nftDesc);
        descF.addTextChangedListener(watch(sv -> nftDesc = sv));
        EditText ownerF = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_owner), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_owner_hint), nftOwner);
        ownerF.addTextChangedListener(watch(sv -> nftOwner = sv));
        EditText edF = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_editions), "1", nftEditions);
        edF.addTextChangedListener(watch(sv -> nftEditions = sv));
        EditText extF = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_external_url), "https://…", nftExternal);
        extF.addTextChangedListener(watch(sv -> nftExternal = sv));
        body.addView(hostingUploadRow("nftext", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_upload_full),
                () -> pickHostSingle(PICK_HOST_EXTERNAL, "nftext")), lpm(0, 4, 0, 0));
        EditText webF = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_webvalidate), "https://…/validate", nftWeb);
        webF.addTextChangedListener(watch(sv -> nftWeb = sv));

        LinearLayout signRow = horizontal(Gravity.CENTER_VERTICAL);
        TextView signChip = Design.chip(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_signed), true);
        signRow.addView(signChip, new LinearLayout.LayoutParams(-2, -2));
        body.addView(signRow, lpm(0, 4, 0, 12));

        /* traits — OpenSea-style attributes */
        body.addView(sectionHead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_traits), nftTraits.size() + ""));
        for (int i = 0; i < nftTraits.size(); i++) {
            final int idx = i;
            String[] t = nftTraits.get(i);
            LinearLayout row = horizontal(Gravity.CENTER_VERTICAL);
            row.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1.5f));
            row.setPadding(dp(10), dp(7), dp(6), dp(7));
            TextView type = Design.text(this, t[0].toUpperCase(), 9.5f, Design.DIM(), Design.sansBold());
            type.setLetterSpacing(0.12f);
            row.addView(type, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(Design.text(this, t[1], 12, Design.INK(), Design.sansBold()), new LinearLayout.LayoutParams(0, -2, 1));
            TextView del = Design.text(this, "×", 16, Design.ACCENT(), Design.sansBold());
            del.setPadding(dp(10), 0, dp(10), 0);
            del.setClickable(true);
            del.setOnClickListener(v -> { nftTraits.remove(idx); renderCreateNft(); });
            row.addView(del);
            body.addView(row, lpm(0, 0, 0, 6));
        }
        LinearLayout addRow = horizontal(Gravity.CENTER_VERTICAL);
        EditText tType = input(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_trait_hint));
        tType.setText(traitTypeDraft);
        tType.addTextChangedListener(watch(sv -> traitTypeDraft = sv));
        EditText tVal = input(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_trait_value_hint));
        tVal.setText(traitValueDraft);
        tVal.addTextChangedListener(watch(sv -> traitValueDraft = sv));
        addRow.addView(tType, weight(46, 0, 3));
        addRow.addView(tVal, weight(46, 3, 3));
        TextView addBtn = Design.button(this, "+", false);
        addBtn.setOnClickListener(v -> {
            if (traitTypeDraft.trim().isEmpty() || traitValueDraft.trim().isEmpty()) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_trait_required)); return; }
            if (nftTraits.size() >= 12) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_trait_limit)); return; }
            nftTraits.add(new String[]{ traitTypeDraft.trim(), traitValueDraft.trim() });
            traitTypeDraft = ""; traitValueDraft = "";
            renderCreateNft();
        });
        addRow.addView(addBtn, new LinearLayout.LayoutParams(dp(46), dp(46)));
        body.addView(addRow, lpm(0, 2, 0, 12));

        /* seal preview: wallet card + metadata record */
        body.addView(sectionHead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_before_seal), ""));
        LinearLayout prev = horizontal(Gravity.CENTER_VERTICAL);
        prev.setBackground(Design.blockShadow(this, Design.CARD()));
        prev.setPadding(dp(10), dp(10), dp(12), dp(12));
        ImageView pIcon = new ImageView(this);
        pIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pIcon.setImageBitmap(Identicon.forToken("nftdraft", 160));
        String pUrl = nftEmbed ? (nftImage.isEmpty() ? null : ImageTools.dataUri(nftImage))
                : IconResolver.resolve(nftUrl.trim());
        if (pUrl != null) ImageLoader.loadOver(this, pUrl, pIcon);
        prev.addView(pIcon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout pCopy = vertical();
        pCopy.setPadding(dp(10), 0, 0, 0);
        pCopy.addView(Design.text(this, nftName.trim().isEmpty() ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_untitled) : nftName.trim(), 13, Design.INK(), Design.sansBold()));
        pCopy.addView(Design.note(this, Language.join(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_editions_preview, nftEditions.trim().isEmpty() ? "1" : nftEditions.trim()), (nftOwner.trim().isEmpty() ? "" : " · " + nftOwner.trim()), nftTraits.isEmpty() ? "" : Language.join(" · ", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_traits_preview, nftTraits.size())))));
        prev.addView(pCopy, new LinearLayout.LayoutParams(0, -2, 1));
        prev.addView(Design.pill(this, "NFT", Design.PILL_DONE));
        body.addView(prev, lpm(0, 0, 0, 8));
        TextView metaDump = Design.text(this, previewNftJson(), 9.5f, Design.DIM(), Design.mono());
        metaDump.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1));
        metaDump.setPadding(dp(10), dp(8), dp(10), dp(8));
        body.addView(metaDump, lpm(0, 0, 0, 12));

        TextView go = Design.button(this, nftBusy ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_minting) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_seal_nft), true);
        go.setEnabled(!nftBusy);
        go.setOnClickListener(v -> mintNft(go));
        body.addView(go, lph(54, 0, 0, 0, 8));
        body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_metadata_help)));
    }

    private String previewNftJson() {
        String url = nftEmbed ? (nftImage.isEmpty() ? "<artimage>…</artimage>" : "<artimage>" + nftImage.length() + " chars</artimage>")
                : nftUrl.trim();
        JSONObject meta = StateNft.nftMetadata(nftName.trim().isEmpty() ? "Untitled" : nftName.trim(),
                nftDesc.trim(), url, nftOwner.trim(), nftExternal.trim(), nftWeb.trim(),
                StateNft.traitsToAttributes(nftTraits));
        try { return meta.toString(2); } catch (Exception e) { return meta.toString(); }
    }

    private void mintNft(TextView go) {
        if (nftBusy) return;
        String n = nftName.trim();
        if (n.isEmpty() || n.length() > 60) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_title_required)); return; }
        int editions = parseIntSafe(nftEditions.trim().isEmpty() ? "1" : nftEditions.trim());
        if (editions < 1 || editions > 1000) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_editions_range)); return; }
        String url;
        if (nftEmbed) {
            if (nftImage.isEmpty()) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_image_required)); return; }
            url = "<artimage>" + nftImage + "</artimage>";
        } else {
            url = nftUrl.trim();
            if (url.isEmpty() || !validCmdUrl(url)) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_image_url_required)); return; }
        }
        if (!validCmdUrl(nftExternal.trim()) || !validCmdUrl(nftWeb.trim())) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_safe_urls)); return; }
        if (!hostGatePassed) {
            java.util.List<String> urls = new java.util.ArrayList<>();
            if (!nftEmbed && nftUrl.trim().startsWith("http")) urls.add(nftUrl.trim());
            if (!nftExternal.trim().isEmpty()) urls.add(nftExternal.trim());
            // nftWeb (validation doc) is NOT gated — written post-mint with the tokenid
            if (!urls.isEmpty()) { verifyHostedThen(urls, () -> { hostGatePassed = true; try { mintNft(go); } finally { hostGatePassed = false; } }); return; }
        }
        hostGatePassed = false;
        nftBusy = true;
        Language.setText(go, Language.upper(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_minting)));
        final org.json.JSONArray attrs = StateNft.traitsToAttributes(nftTraits);
        /* ALWAYS SIGNED: the artwork rides inside the token record with the
         * 8.4KB signature. editions>1 must also split (3 records/txn);
         * editions=1 only sends (2 records/txn). Shrink the art to fit. */
        String urlFit = url;
        if (!nftImage.isEmpty() && url.startsWith("<artimage>")) {
            String probeCmd = StateNft.nftCreateCommand(n, nftDesc.trim(), "<artimage></artimage>",
                    nftOwner.trim(), nftExternal.trim(), nftWeb.trim(), attrs, editions, "");
            int sansLen = probeCmd.length();
            int bound = editions > 1 ? MintEngine.META_MAX
                    : (MintEngine.TRANSFER_PAIR_BUDGET - MintEngine.DEF_WRAPPER - MintEngine.DEF_SIGN_WEIGHT);
            int artBudget = bound - sansLen - 24;
            if (nftImage.length() > artBudget) {
                if ("image/svg+xml".equals(ImageTools.mimeOf(nftImage))) {
                    nftBusy = false;
                    toast(Language.join(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_svg_too_large, nftImage.length(), artBudget), editions > 1 ? Language.join(" ", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_svg_editions, editions)) : ""));
                    if (screen == Screen.CREATE_NFT) renderCreateNft();
                    return;
                }
                String out = ImageTools.recompressBase64(nftImage, Math.max(1500, artBudget));
                if (out.isEmpty() || out.length() > artBudget) {
                    nftBusy = false;
                    toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_art_too_large, artBudget));
                    if (screen == Screen.CREATE_NFT) renderCreateNft();
                    return;
                }
                nftImage = out;
                urlFit = "<artimage>" + out + "</artimage>";
                toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_art_shrunk));
            }
        }
        final String submittedDraft = mintDraftSnapshot(0);
        final String mintCommand = StateNft.nftCreateCommand(n, nftDesc.trim(), urlFit, nftOwner.trim(),
                nftExternal.trim(), nftWeb.trim(), attrs, editions, "");
        NodeApi.Cb fin = new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                nftBusy = false;
                if (!json.optBoolean("status", false)) {
                    toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_mint_failed, json.isNull("error") ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_node_refused) : json.optString("error")));
                    if (screen == Screen.CREATE_NFT) renderCreateNft();
                    return;
                }
                toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_nft_minted));
                if (!submittedDraft.equals(mintDraftSnapshot(0))) {
                    if (screen == Screen.CREATE_NFT) renderCreateNft();
                    return;
                }
                nftName = ""; nftDesc = ""; nftImage = ""; nftUrl = ""; nftExternal = ""; nftWeb = ""; nftEditions = "1";
                nftTraits.clear();
                traitTypeDraft = ""; traitValueDraft = "";
                LocalStore.clearDraft(MainActivity.this, "nft");
                if (screen == Screen.CREATE_NFT) renderGallery();
            }
            @Override public void onError(String message) { nftBusy = false; toast(message); if (screen == Screen.CREATE_NFT) renderCreateNft(); }
        };

        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json == null || !Boolean.TRUE.equals(json.opt("status")) ? null : json.optJSONObject("response");
                String pk = r == null ? "" : r.optString("publickey", "");
                if (pk.isEmpty()) { nftBusy = false; toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_key_failed)); if (screen == Screen.CREATE_NFT) renderCreateNft(); return; }
                node.cmd(mintCommand + " signtoken:" + pk, fin);
            }
            @Override public void onError(String message) { nftBusy = false; toast(message); if (screen == Screen.CREATE_NFT) renderCreateNft(); }
        });
    }

    /* ---- custom token ---- */

    private void renderCreateToken() {
        setScreen(Screen.CREATE_TOKEN, this::renderStudio);
        appbar(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_new_token), true, false);
        hideNav();

        EditText nameF = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_name), "EuroBuddha Coin", tokName);
        nameF.addTextChangedListener(watch(sv -> tokName = sv));
        EditText tickF = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_ticker), "EUROB", tokTicker);
        tickF.addTextChangedListener(watch(sv -> tokTicker = sv));
        EditText supF = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_nft_mint_supply), "1000000", tokSupply);
        supF.addTextChangedListener(watch(sv -> tokSupply = sv));
        EditText decF = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_decimals), "0", tokDecimals);
        decF.addTextChangedListener(watch(sv -> tokDecimals = sv));
        EditText descF = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_description_optional), "", tokDesc);
        descF.addTextChangedListener(watch(sv -> tokDesc = sv));
        EditText urlF = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_icon_url_optional), "https://…/icon.png", tokUrl);
        urlF.addTextChangedListener(watch(sv -> tokUrl = sv));
        body.addView(hostingUploadRow("tokicon", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_upload_icon_host),
                () -> pickHostSingle(PICK_HOST_ICON, "tokicon")), lpm(0, 4, 0, 0));
        body.addView(iconUploadRow(tokIcon, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_icon_help),
                v -> { pendingPickContext = PICK_TOKICON; pendingBatchImages = false; launchPicker(false); },
                v -> { tokIcon = ""; renderCreateToken(); }), lpm(0, 4, 0, 8));

        /* custom metadata pairs */
        body.addView(sectionHead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_custom_fields), tokPairs.size() + ""));
        for (int i = 0; i < tokPairs.size(); i++) {
            final int idx = i;
            String[] p = tokPairs.get(i);
            LinearLayout row = horizontal(Gravity.CENTER_VERTICAL);
            row.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1.5f));
            row.setPadding(dp(10), dp(7), dp(6), dp(7));
            TextView key = Design.text(this, p[0].toUpperCase(), 9.5f, Design.DIM(), Design.sansBold());
            key.setLetterSpacing(0.12f);
            row.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(Design.text(this, p[1], 12, Design.INK(), Design.sansBold()), new LinearLayout.LayoutParams(0, -2, 1));
            TextView del = Design.text(this, "×", 16, Design.ACCENT(), Design.sansBold());
            del.setPadding(dp(10), 0, dp(10), 0);
            del.setClickable(true);
            del.setOnClickListener(v -> { tokPairs.remove(idx); renderCreateToken(); });
            row.addView(del);
            body.addView(row, lpm(0, 0, 0, 6));
        }
        LinearLayout addRow = horizontal(Gravity.CENTER_VERTICAL);
        EditText pKey = input(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_field_hint));
        pKey.setText(pairKeyDraft);
        pKey.addTextChangedListener(watch(sv -> pairKeyDraft = sv));
        EditText pVal = input(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_terminal_txn_value));
        pVal.setText(pairValueDraft);
        pVal.addTextChangedListener(watch(sv -> pairValueDraft = sv));
        addRow.addView(pKey, weight(46, 0, 3));
        addRow.addView(pVal, weight(46, 3, 3));
        TextView addBtn = Design.button(this, "+", false);
        addBtn.setOnClickListener(v -> {
            String k = pairKeyDraft.trim();
            if (k.isEmpty() || pairValueDraft.trim().isEmpty()) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_field_required)); return; }
            if (StateNft.RESERVED_KEYS.contains(k.toLowerCase())) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_reserved, k)); return; }
            if (tokPairs.size() >= 10) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_field_limit)); return; }
            tokPairs.add(new String[]{ k, pairValueDraft.trim() });
            pairKeyDraft = ""; pairValueDraft = "";
            renderCreateToken();
        });
        addRow.addView(addBtn, new LinearLayout.LayoutParams(dp(46), dp(46)));
        body.addView(addRow, lpm(0, 2, 0, 12));

        /* record preview */
        body.addView(sectionHead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_record), ""));
        TextView metaDump = Design.text(this, previewTokenJson(), 9.5f, Design.DIM(), Design.mono());
        metaDump.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1));
        metaDump.setPadding(dp(10), dp(8), dp(10), dp(8));
        body.addView(metaDump, lpm(0, 0, 0, 12));

        LinearLayout warn = vertical();
        warn.setBackground(Design.ruled(this, Design.CARD(), Design.ACCENT(), 2f));
        warn.setPadding(dp(12), dp(10), dp(12), dp(12));
        warn.addView(Design.text(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_permanent_supply), 12, Design.ACCENT(), Design.sansBold()));
        warn.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_supply_help)), lpm(0, 3, 0, 0));
        body.addView(warn, lpm(0, 6, 0, 14));

        TextView go = Design.button(this, tokBusy ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_minting) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_nft_mint_mint_token), true);
        go.setEnabled(!tokBusy);
        go.setOnClickListener(v -> mintToken(go));
        body.addView(go, lph(54, 0, 0, 0, 0));
    }

    private void mintToken(TextView go) {
        if (tokBusy) return;
        String n = tokName.trim();
        if (n.isEmpty() || n.length() > 40) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_name_required)); return; }
        long supply = 0;
        try { supply = Long.parseLong(tokSupply.trim()); } catch (Exception ignored) {}
        if (supply < 1) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_supply_required)); return; }
        int dec = parseIntSafe(tokDecimals.trim());
        if (dec < 0 || dec > 16) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_decimals_range)); return; }
        if (!validCmdUrl(tokUrl.trim())) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_safe_icon_url)); return; }
        if (!hostGatePassed && tokUrl.trim().startsWith("http")) {
            java.util.List<String> urls = new java.util.ArrayList<>();
            urls.add(tokUrl.trim());
            verifyHostedThen(urls, () -> { hostGatePassed = true; try { mintToken(go); } finally { hostGatePassed = false; } });
            return;
        }
        hostGatePassed = false;
        tokBusy = true;
        Language.setText(go, Language.upper(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_minting)));
        String iconValue = !tokUrl.trim().isEmpty() ? tokUrl.trim()
                : (!tokIcon.isEmpty() ? "<artimage>" + tokIcon : "");
        /* ALWAYS SIGNED: gate the record (icon + pairs + signature) against the
         * split cap, auto-slimming the icon rather than refusing. */
        String iconFit = iconValue;
        {
            String probe = StateNft.tokenCreateCommand(n, tokDesc.trim(), tokTicker.trim(), iconFit, supply, dec, tokPairs);
            if (probe.length() + MintEngine.DEF_SIGN_WEIGHT > MintEngine.DEF_SPLIT_MAX
                    && !tokIcon.isEmpty() && iconFit.startsWith("<artimage>")) {
                int room = MintEngine.DEF_SPLIT_MAX - MintEngine.DEF_SIGN_WEIGHT
                        - (probe.length() - tokIcon.length()) - 24;
                String out = ImageTools.recompressBase64(tokIcon, Math.max(1200, room));
                if (!out.isEmpty() && out.length() <= room) {
                    iconFit = "<artimage>" + out + "</artimage>";
                    toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_icon_shrunk));
                } else {
                    tokBusy = false;
                    toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_icon_too_large));
                    if (screen == Screen.CREATE_TOKEN) renderCreateToken();
                    return;
                }
            } else if (probe.length() + MintEngine.DEF_SIGN_WEIGHT > MintEngine.DEF_SPLIT_MAX) {
                tokBusy = false;
                toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_record_too_large));
                if (screen == Screen.CREATE_TOKEN) renderCreateToken();
                return;
            }
        }
        final String submittedDraft = mintDraftSnapshot(1);
        final String mintCommand = StateNft.tokenCreateCommand(n, tokDesc.trim(), tokTicker.trim(), iconFit, supply, dec, tokPairs);
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject ga) {
                JSONObject gr = ga == null || !Boolean.TRUE.equals(ga.opt("status")) ? null : ga.optJSONObject("response");
                String pk = gr == null ? "" : gr.optString("publickey", "");
                if (pk.isEmpty()) { tokBusy = false; toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_key_failed)); if (screen == Screen.CREATE_TOKEN) renderCreateToken(); return; }
        node.cmd(mintCommand
                        + " signtoken:" + pk,
                new NodeApi.Cb() {
                    @Override public void onResult(JSONObject json) {
                        tokBusy = false;
                        if (!json.optBoolean("status", false)) {
                            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_mint_failed, json.isNull("error") ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_node_refused) : json.optString("error")));
                            if (screen == Screen.CREATE_TOKEN) renderCreateToken();
                            return;
                        }
                        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_token_minted));
                        if (!submittedDraft.equals(mintDraftSnapshot(1))) {
                            if (screen == Screen.CREATE_TOKEN) renderCreateToken();
                            return;
                        }
                        tokName = ""; tokTicker = ""; tokDesc = ""; tokUrl = ""; tokIcon = "";
                        tokPairs.clear();
                        pairKeyDraft = ""; pairValueDraft = "";
                        LocalStore.clearDraft(MainActivity.this, "token");
                        if (screen == Screen.CREATE_TOKEN) renderGallery();
                    }
                    @Override public void onError(String message) { tokBusy = false; toast(message); if (screen == Screen.CREATE_TOKEN) renderCreateToken(); }
                });
            }
            @Override public void onError(String message) { tokBusy = false; toast(message); if (screen == Screen.CREATE_TOKEN) renderCreateToken(); }
        });
    }

    private String previewTokenJson() {
        String iconValue = !tokUrl.trim().isEmpty() ? tokUrl.trim()
                : (!tokIcon.isEmpty() ? "<artimage>" + tokIcon.length() + " chars" : "");
        JSONObject meta = StateNft.tokenMeta(tokName.trim().isEmpty() ? "Unnamed" : tokName.trim(),
                tokDesc.trim(), tokTicker.trim(), iconValue, tokPairs);
        try { return meta.toString(2); } catch (Exception e) { return meta.toString(); }
    }

    /* ================= GENERATIVE ================= */

    private String artDraftSnapshot() {
        JSONObject snapshot = new JSONObject();
        put(snapshot, "style", artStyle); put(snapshot, "seed", artSeed);
        put(snapshot, "count", genCount); put(snapshot, "photo", artPhotoLoaded);
        put(snapshot, "config", artCfgs.get(artStyle));
        return snapshot.toString();
    }

    private boolean artRequestCurrent(long generation, String draft) {
        if (isFinishing() || isDestroyed()) return false;
        if (generation == screenGeneration && draft.equals(artDraftSnapshot())) return true;
        if (screen == Screen.CREATE_GENERATIVE) renderGenerative();
        return false;
    }

    private void renderGenerative() {
        setScreen(Screen.CREATE_GENERATIVE, this::renderStudio);
        appbar(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_title), true, false);
        hideNav();
        if (!artDraftLoaded) { artDraftLoaded = true; artLoadDraft(); }

        body.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_intro)));
        body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_deterministic)), lpm(0, 4, 0, 12));

        final TextView warm = Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_warming));
        body.addView(warm, lpm(0, 0, 0, 12));

        final long requestedScreen = screenGeneration;
        final String requestedStyle = artStyle;
        ArtStudio.with(this, studio -> {
            if (isFinishing() || isDestroyed() || requestedScreen != screenGeneration) return;
            if (ART_STYLE_LIST == null) {
                studio.styles(list -> {
                    if (isFinishing() || isDestroyed() || requestedScreen != screenGeneration) return;
                    ART_STYLE_LIST = list;
                    renderGenerative();
                });
                return;
            }
            JSONObject cfg = artCfgs.get(artStyle);
            if (cfg == null) {
                studio.defaultConfig(requestedStyle, c2 -> {
                    if (isFinishing() || isDestroyed() || requestedScreen != screenGeneration) return;
                    artCfgs.put(requestedStyle, c2);
                    artMigrated.add(requestedStyle);
                    renderGenerative();
                });
                return;
            }
            /* saved drafts age across releases — rebuild on the current slot
             * set once per process so new slots (e.g. Render) actually show */
            if (!artMigrated.contains(artStyle)) {
                studio.migrate(cfg, m -> {
                    if (isFinishing() || isDestroyed() || requestedScreen != screenGeneration) return;
                    artMigrated.add(requestedStyle);
                    if (m != null && m.length() > 0) { artCfgs.put(requestedStyle, m); artSaveDraft(); }
                    renderGenerative();
                });
                return;
            }
            body.removeView(warm);
            buildArtStudioUi(studio, cfg);
        });
    }

    private void buildArtStudioUi(ArtStudio studio, JSONObject cfg) {
        /* ---- style picker ---- */
        LinearLayout strip = horizontal(Gravity.TOP);
        for (int i = 0; i < ART_STYLE_LIST.length(); i++) {
            JSONObject st = ART_STYLE_LIST.optJSONObject(i);
            if (st == null) continue;
            final String key = st.optString("key");
            LinearLayout cell = vertical();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, 0, dp(8), 0);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            boolean active = key.equals(artStyle);
            iv.setBackground(Design.ruled(this, Design.CARD(), active ? Design.ACCENT() : Design.INK(),
                    active ? 2.5f : 1.5f));
            iv.setPadding(dp(2), dp(2), dp(2), dp(2));
            android.graphics.Bitmap tb = ART_THUMBS.get(key);
            if (tb != null) iv.setImageBitmap(tb);
            else studio.thumb(key, svg -> new Thread(() -> {
                android.graphics.Bitmap b = ArtStudio.svgBitmap(svg, dp(72));
                runOnUiThread(() -> {
                    if (b != null) ART_THUMBS.put(key, b);
                    iv.setImageBitmap(b);
                });
            }).start());
            iv.setClickable(true);
            iv.setOnClickListener(v -> {
                artStyle = key;
                artItems = new JSONArray();
                artPreviews.clear();
                artOpenSlots.clear();
                artSaveDraft();
                renderGenerative();
            });
            cell.addView(iv, new LinearLayout.LayoutParams(dp(72), dp(72)));
            TextView nm = Design.text(this, ArtLabels.display(st.optString("label")), 8.5f,
                    active ? Design.ACCENT() : Design.INK(), Design.sansBold());
            nm.setSingleLine(false);
            nm.setGravity(Gravity.CENTER);
            cell.addView(nm, new LinearLayout.LayoutParams(dp(76), -2));
            strip.addView(cell);
        }
        HorizontalScroll styleScroll = new HorizontalScroll(this);
        styleScroll.addView(strip);
        body.addView(styleScroll, lpm(0, 0, 0, 14));

        /* ---- photo intake (Photo Cartoon pack only) ---- */
        if ("photo".equals(artStyle)) {
            LinearLayout card = lotCard();
            card.addView(Design.display(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_label_photo_cartoon), 13));
            card.addView(Design.note(this, artPhotoLoaded
                    ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_photo_loaded)
                    : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_photo_help)));
            LinearLayout row = horizontal(Gravity.CENTER_VERTICAL);
            TextView pick = Design.button(this,
                    artPhotoLoaded ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_change_photo) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_choose_photo), true);
            pick.setOnClickListener(v -> {
                pendingPickContext = PICK_ARTPHOTO;
                pendingBatchImages = false;
                launchPicker(false);
            });
            pick.setMinHeight(dp(48));
            LinearLayout.LayoutParams pickLp = weight(48, 0, artPhotoLoaded ? 4 : 0); pickLp.height = -2;
            row.addView(pick, pickLp);
            if (artPhotoLoaded) {
                TextView clear = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_logs_clear), false);
                clear.setOnClickListener(v -> ArtStudio.with(this, s ->
                        s.clearPhoto(() -> {
                            artPhotoLoaded = false;
                            ART_THUMBS.remove("photo");
                            artItems = new JSONArray();
                            artPreviews.clear();
                            if (screen == Screen.CREATE_GENERATIVE) renderGenerative();
                        })));
                clear.setMinHeight(dp(48));
                LinearLayout.LayoutParams clearLp = weight(48, 4, 0); clearLp.height = -2;
                row.addView(clear, clearLp);
            }
            card.addView(row, lpm(0, 8, 0, 0));
            body.addView(card, lpm(0, 0, 0, 14));
        }

        /* ---- seed ---- */
        body.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_seed)));
        LinearLayout seedRow = horizontal(Gravity.CENTER_VERTICAL);
        EditText seed = input("atelier-genesis");
        seed.setText(artSeed);
        seed.addTextChangedListener(watch(sv -> {
            artSeed = sv.trim();
            if (artItems.length() > 0) {
                // the sheet no longer derives from the seed on display — retire it
                artItems = new JSONArray();
                artPreviews.clear();
                if (artSheetBox != null) artSheetBox.setVisibility(android.view.View.GONE);
            }
            body.removeCallbacks(artSaveRunnable);
            body.postDelayed(artSaveRunnable, 400);
        }));
        seedRow.addView(seed, weight(46, 0, 4));
        TextView dice = Design.button(this, "🎲", false);
        Language.description(dice, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_random_seed));
        dice.setOnClickListener(v -> {
            String chars = "abcdefghjkmnpqrstuvwxyz23456789";
            StringBuilder sb = new StringBuilder("atelier-");
            java.util.Random r = new java.util.Random();
            for (int i = 0; i < 10; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
            artSeed = sb.toString();
            artItems = new JSONArray();
            artPreviews.clear();
            artSaveDraft();
            renderGenerative();
        });
        seedRow.addView(dice, new LinearLayout.LayoutParams(dp(56), dp(46)));
        body.addView(seedRow, lpm(0, 6, 0, 14));

        /* ---- trait pool & rarity ---- */
        body.addView(sectionHead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_traits), ""));
        JSONArray slots = cfg.optJSONArray("slots");
        if (slots != null) for (int i = 0; i < slots.length(); i++) {
            final JSONObject slot = slots.optJSONObject(i);
            if (slot == null) continue;
            final String slotKey = slot.optString("key", "s" + i);
            LinearLayout card = lotCard();
            LinearLayout head = horizontal(Gravity.CENTER_VERTICAL);
            LinearLayout copy = vertical();
            copy.addView(Design.display(this, ArtLabels.display(slot.optString("label", slotKey)), 13));
            JSONArray vs = slot.optJSONArray("variants");
            int on = 0, total = vs == null ? 0 : vs.length();
            if (vs != null) for (int k = 0; k < total; k++) {
                JSONObject v2 = vs.optJSONObject(k);
                if (v2 != null && v2.optBoolean("on", true) && v2.optInt("weight", 0) > 0) on++;
            }
            copy.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_enabled, on, total)));
            head.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            boolean open = artOpenSlots.contains(slotKey);
            TextView arrow = Design.text(this, open ? "▴" : "▾", 14, Design.ACCENT(), Design.sansBold());
            head.addView(arrow);
            head.setClickable(true);
            head.setOnClickListener(v -> {
                if (!artOpenSlots.remove(slotKey)) artOpenSlots.add(slotKey);
                renderGenerative();
            });
            card.addView(head);

            if (open && vs != null) {
                int weightTotal = 0;
                for (int k = 0; k < vs.length(); k++) {
                    JSONObject v2 = vs.optJSONObject(k);
                    if (v2 != null && v2.optBoolean("on", true)) weightTotal += v2.optInt("weight", 0);
                }
                final int wt = Math.max(1, weightTotal);
                for (int k = 0; k < vs.length(); k++) {
                    final JSONObject variant = vs.optJSONObject(k);
                    if (variant == null) continue;
                    LinearLayout row = horizontal(Gravity.CENTER_VERTICAL);
                    android.widget.CheckBox cb = new android.widget.CheckBox(this);
                    cb.setChecked(variant.optBoolean("on", true));
                    cb.setOnCheckedChangeListener((b2, checked) -> {
                        put(variant, "on", checked);
                        artItems = new JSONArray();
                        artSaveDraft();
                        renderGenerative();
                    });
                    row.addView(cb);
                    TextView nm = Design.text(this, ArtLabels.display(variant.optString("name")), 11.5f, Design.INK(), Design.sans());
                    nm.setSingleLine(false);
                    row.addView(nm, new LinearLayout.LayoutParams(dp(96), -2));
                    android.widget.SeekBar sb = new android.widget.SeekBar(this);
                    sb.setMax(100);
                    sb.setProgress(Math.min(100, variant.optInt("weight", 0)));
                    final TextView pct = Design.text(this,
                            pctText(variant, wt), 9.5f, Design.DIM(), Design.mono());
                    sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                        @Override public void onProgressChanged(android.widget.SeekBar s2, int p2, boolean fromUser) {
                            if (!fromUser) return;
                            put(variant, "weight", p2);
                            pct.setText(pctText(variant, wt));
                        }
                        @Override public void onStartTrackingTouch(android.widget.SeekBar s2) {}
                        @Override public void onStopTrackingTouch(android.widget.SeekBar s2) {
                            artItems = new JSONArray();
                            artSaveDraft();
                            renderGenerative();
                        }
                    });
                    row.addView(sb, new LinearLayout.LayoutParams(0, -2, 1));
                    pct.setGravity(Gravity.END);
                    row.addView(pct, new LinearLayout.LayoutParams(dp(44), -2));
                    card.addView(row, lpm(0, 2, 0, 0));
                }
            }
            body.addView(card, lpm(0, 0, 0, 8));
        }

        LinearLayout shuffleRow = horizontal(Gravity.CENTER_VERTICAL);
        TextView shufR = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_shuffle_rarity), false);
        shufR.setOnClickListener(v -> { artShuffle(cfg, false); });
        shufR.setMinHeight(dp(48));
        LinearLayout.LayoutParams shufRLp = weight(48, 0, 4); shufRLp.height = -2;
        shuffleRow.addView(shufR, shufRLp);
        TextView shufP = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_shuffle_traits), false);
        shufP.setOnClickListener(v -> { artShuffle(cfg, true); });
        shufP.setMinHeight(dp(48));
        LinearLayout.LayoutParams shufPLp = weight(48, 4, 4); shufPLp.height = -2;
        shuffleRow.addView(shufP, shufPLp);
        TextView reset = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_future_reset), false);
        reset.setOnClickListener(v -> {
            artCfgs.remove(artStyle);
            artItems = new JSONArray();
            artPreviews.clear();
            artSaveDraft();
            renderGenerative();
        });
        reset.setMinHeight(dp(48));
        LinearLayout.LayoutParams resetLp = weight(48, 4, 0); resetLp.height = -2;
        shuffleRow.addView(reset, resetLp);
        body.addView(shuffleRow, lpm(0, 4, 0, 6));

        final TextView capNote = Design.note(this, "");
        body.addView(capNote, lpm(0, 0, 0, 14));
        studio.capacity(cfg, n -> Language.setText(capNote, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_capacity, String.format(Locale.US, "%,d", n))));

        /* ---- proof sheet ---- */
        body.addView(sectionHead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_proof), artItems.length() == 0 ? "" : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_plates, artItems.length())));
        LinearLayout genRow = horizontal(Gravity.CENTER_VERTICAL);
        EditText count = input("12");
        count.setText(genCount);
        count.addTextChangedListener(watch(sv -> genCount = sv));
        genRow.addView(count, new LinearLayout.LayoutParams(dp(70), dp(46)));
        TextView gen = Design.button(this, genBusy ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_drawing) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_generate), true);
        gen.setEnabled(!genBusy);
        gen.setOnClickListener(v -> artGenerate(studio, cfg));
        gen.setMinHeight(dp(48));
        LinearLayout.LayoutParams genLp = weight(48, 8, 0); genLp.height = -2;
        genRow.addView(gen, genLp);
        body.addView(genRow, lpm(0, 0, 0, 10));

        artSheetBox = vertical();
        body.addView(artSheetBox);
        if (artItems.length() > 0) {
            artSheetBox.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_plate_hint)), lpm(0, 0, 0, 8));
            int cols = 3;
            LinearLayout row = null;
            for (int i = 0; i < artItems.length(); i++) {
                final int gi = i;
                final JSONObject item = artItems.optJSONObject(i);
                if (i % cols == 0) {
                    row = horizontal(Gravity.TOP);
                    artSheetBox.addView(row, lpm(0, 0, 0, 8));
                }
                FrameLayout tile = new FrameLayout(this);
                tile.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1.5f));
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                if (i < artPreviews.size() && artPreviews.get(i) != null) iv.setImageBitmap(artPreviews.get(i));
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-1, -1);
                int bb = dp(3);
                ilp.setMargins(bb, bb, bb, bb);
                tile.addView(iv, ilp);
                TextView tag = Design.pill(this, String.format(Locale.US, "%02d", i + 1), Design.PILL_DONE);
                FrameLayout.LayoutParams tagLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
                tagLp.setMargins(dp(5), 0, 0, dp(5));
                tile.addView(tag, tagLp);
                tile.setClickable(true);
                tile.setOnClickListener(v -> showArtItem(item, gi));
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(104), 1);
                if (i % cols != 0) tlp.leftMargin = dp(8);
                row.addView(tile, tlp);
            }
            int rem = artItems.length() % cols;
            if (rem != 0 && row != null) {
                for (int i = 0; i < cols - rem; i++) {
                    row.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1){{ leftMargin = dp(8); }});
                }
            }
            TextView use = Design.button(this, genBusy ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_composing) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_send), true);
            use.setEnabled(!genBusy);
            use.setOnClickListener(v -> sendArtToCollection());
            use.setMinHeight(dp(52));
            artSheetBox.addView(use, lpm(0, 12, 0, 8));
        }
    }

    private String pctText(JSONObject variant, int weightTotal) {
        if (!variant.optBoolean("on", true) || weightTotal <= 0) return "0%";
        return (Math.round(variant.optInt("weight", 0) * 1000f / weightTotal) / 10f) + "%";
    }

    private void artShuffle(JSONObject cfg, boolean pool) {
        java.util.Random r = new java.util.Random();
        JSONArray slots = cfg.optJSONArray("slots");
        if (slots == null) return;
        for (int i = 0; i < slots.length(); i++) {
            JSONArray vs = slots.optJSONObject(i) == null ? null : slots.optJSONObject(i).optJSONArray("variants");
            if (vs == null) continue;
            int on = 0;
            for (int k = 0; k < vs.length(); k++) {
                JSONObject v2 = vs.optJSONObject(k);
                if (v2 == null) continue;
                if (pool) { put(v2, "on", r.nextFloat() > 0.35f); }
                if (v2.optBoolean("on", true)) { put(v2, "weight", 1 + r.nextInt(99)); on++; }
            }
            if (pool) while (on < Math.min(2, vs.length())) {
                JSONObject v2 = vs.optJSONObject(r.nextInt(vs.length()));
                if (v2 != null && !v2.optBoolean("on", true)) { put(v2, "on", true); put(v2, "weight", 1 + r.nextInt(99)); on++; }
            }
        }
        artItems = new JSONArray();
        artPreviews.clear();
        artSaveDraft();
        renderGenerative();
        toast(pool ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_traits_shuffled) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_rarity_shuffled));
    }

    private void artGenerate(ArtStudio studio, JSONObject cfg) {
        if (genBusy) return;
        int n = Math.max(2, Math.min(20, parseIntSafe(genCount)));
        genCount = String.valueOf(n);
        genBusy = true;
        renderGenerative();
        final long requestedScreen = screenGeneration;
        final String requestedDraft = artDraftSnapshot();
        String s = artSeed.isEmpty() ? "atelier" : artSeed;
        studio.generate(s, n, cfg, result -> {
            genBusy = false;
            if (!artRequestCurrent(requestedScreen, requestedDraft)) return;
            String err = result.optString("error", "");
            JSONArray items = result.optJSONArray("items");
            if (items == null) items = new JSONArray();
            if (err.length() != 0 && items.length() < n) {
                toast(ArtLabels.error(err));
            }
            artItems = items;
            artPreviews.clear();
            for (int i = 0; i < items.length(); i++) artPreviews.add(null);
            renderGenerative();
            final JSONArray itemsF = items;
            new Thread(() -> {
                for (int i = 0; i < itemsF.length(); i++) {
                    JSONObject it = itemsF.optJSONObject(i);
                    android.graphics.Bitmap b = it == null ? null
                            : ArtStudio.svgBitmap(it.optString("svg"), dp(160));
                    final int idx = i;
                    runOnUiThread(() -> {
                        if (artItems == itemsF && idx < artPreviews.size()) {
                            artPreviews.set(idx, b);
                            if (screen == Screen.CREATE_GENERATIVE && idx == itemsF.length() - 1) renderGenerative();
                        }
                    });
                }
            }).start();
        });
    }

    private void showArtItem(JSONObject item, int idx) {
        if (item == null) return;
        Language.TextBuilder sb = new Language.TextBuilder();
        JSONArray ts = item.optJSONArray("traits");
        if (ts != null) for (int i = 0; i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t == null) continue;
            sb.append(ArtLabels.display(t.optString("label"))).append(":  ").append(ArtLabels.display(t.optString("value")))
              .append("   (").append(t.optString("pct")).append("%)\n");
        }
        sb.append("\n").append(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_rarity_bytes, item.optString("score"), item.optInt("bytes")));
        new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                .setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_plate, String.format(Locale.US, "%02d", idx + 1)))
                .setMessage(sb.build())
                .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_language_close), null)
                .show();
    }

    private void sendArtToCollection() {
        if (artItems.length() == 0 || genBusy) return;
        if ("photo".equals(artStyle) && !artPhotoLoaded) {
            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_load_photo));
            return;
        }
        genBusy = true;
        renderGenerative();
        final JSONArray itemsF = artItems;
        final String requestedStyle = artStyle, requestedDraft = artDraftSnapshot();
        final String collectionDraft = mintDraftSnapshot(2);
        final long requestedScreen = screenGeneration;
        new Thread(() -> {
            String[] images = new String[itemsF.length()];
            JSONObject traitsMap = new JSONObject();
            CharSequence err = "";
            // the photo pack seals only its rarity axes — freed record bytes
            // pay for full-quality Painted plates under the joint budget
            java.util.HashSet<String> sealed = "photo".equals(requestedStyle)
                    ? new java.util.HashSet<>(java.util.Arrays.asList(
                        "palette", "finish", "render", "mode"))
                    : null;
            for (int i = 0; i < itemsF.length(); i++) {
                JSONObject it = itemsF.optJSONObject(i);
                String svg = it == null ? "" : SvgSanitizer.sanitize(it.optString("svg"));
                String b64 = svg.isEmpty() ? "" : java.util.Base64.getEncoder()
                        .encodeToString(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (b64.isEmpty() || b64.length() > ART_EMBED_BUDGET) {
                    err = Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_plate_budget, i + 1);
                    break;
                }
                images[i] = b64;
                JSONArray attrs = new JSONArray();
                JSONArray ts = it.optJSONArray("traits");
                if (ts != null) for (int k = 0; k < ts.length(); k++) {
                    JSONObject t = ts.optJSONObject(k);
                    if (t == null) continue;
                    if (sealed != null && !sealed.contains(t.optString("slot"))) continue;
                    JSONObject a = new JSONObject();
                    put(a, "trait_type", t.optString("label"));
                    put(a, "value", t.optString("value"));
                    attrs.put(a);
                }
                put(traitsMap, String.valueOf(i + 1), attrs);
            }
            /* Wallet icon parity with the MiniDapp: a lead SVG within
             * ICON_BUDGET embeds as crisp vector text (the mint flow's own
             * fallback), but an oversized one must be rasterized here or the
             * wallet tile falls back to the placeholder. */
            String iconB64 = "";
            if (err.length() == 0 && images.length > 0 && images[0].length() > ImageTools.ICON_BUDGET) {
                JSONObject lead = itemsF.optJSONObject(0);
                android.graphics.Bitmap b = lead == null ? null
                        : ArtStudio.svgBitmap(lead.optString("svg"), 512);
                iconB64 = ImageTools.iconFromBitmap(b, ImageTools.ICON_BUDGET);
            }
            /* ALWAYS SIGNED envelope at the handoff: fit the icon into the
             * room; the binding exact gate runs again at the mint click. */
            if (err.length() == 0) {
                int maxImg = 0;
                for (String im : images) if (im != null && im.length() > maxImg) maxImg = im.length();
                StateNft.Meta probe = new StateNft.Meta();
                probe.name = "collection-name-placeholder-40chars....";
                probe.description = "";
                probe.mode = "embed";
                probe.size = images.length;
                probe.icon = !iconB64.isEmpty() ? iconB64
                        : (images.length > 0 && images[0].length() <= ImageTools.ICON_BUDGET
                            ? images[0] : "");
                JSONObject pm = StateNft.tokenMetadata(probe);
                if (traitsMap.length() > 0) put(pm, "traits", traitsMap);
                String gate = MintEngine.jointGate(pm.toString().length(), maxImg);
                if (!"sign".equals(gate)) {
                    JSONObject lead = itemsF.optJSONObject(0);
                    android.graphics.Bitmap b = lead == null ? null
                            : ArtStudio.svgBitmap(lead.optString("svg"), 512);
                    String slim = ImageTools.iconFromBitmap(b, 2000);
                    probe.icon = slim;
                    JSONObject pm2 = StateNft.tokenMetadata(probe);
                    if (traitsMap.length() > 0) put(pm2, "traits", traitsMap);
                    if (!slim.isEmpty() && "sign".equals(MintEngine.jointGate(pm2.toString().length(), maxImg))) {
                        iconB64 = slim;
                        LocalStore.logEvent(MainActivity.this,
                                "Icon slimmed so every lot signs and travels");
                    } else {
                        probe.icon = "";
                        JSONObject pm3 = StateNft.tokenMetadata(probe);
                        if (traitsMap.length() > 0) put(pm3, "traits", traitsMap);
                        String gate3 = MintEngine.jointGate(pm3.toString().length(), maxImg);
                        if ("sign".equals(gate3)) {
                            iconB64 = "";
                            LocalStore.logEvent(MainActivity.this,
                                    "Icon dropped so every lot signs and travels - wallet shows the identicon");
                        } else {
                            err = Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_signed_budget);
                        }
                    }
                }
            }
            final CharSequence errF = err;
            final String iconF = iconB64;
            runOnUiThread(() -> {
                genBusy = false;
                if (!artRequestCurrent(requestedScreen, requestedDraft)) return;
                if (artItems != itemsF || !collectionDraft.equals(mintDraftSnapshot(2))) { renderGenerative(); return; }
                if (errF.length() != 0) { toast(errF); renderGenerative(); return; }
                createMode = "embed";
                createSize = String.valueOf(images.length);
                createImages = images;
                collectionItemTraits = traitsMap;
                if (!iconF.isEmpty()) createIconB64 = iconF;
                toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_editions_ready, images.length));
                renderCreateCollection();
            });
        }).start();
    }

    private void launchFiltr() {
        if (screen != Screen.STUDIO || isFinishing() || isDestroyed()) return;
        if (pendingImagePick != null || activeImagePick != null) { toast(Language.msg(l10n_atelier_image_busy)); return; }
        if (!artDraftLoaded) { artDraftLoaded = true; artLoadDraft(); }
        ImagePick pick = new ImagePick();
        pick.context = -1; pick.screen = screen; pick.generation = screenGeneration;
        pick.sequence = ++imageSequence; pick.target = imageTarget(-1, 0);
        pick.filtrId = java.util.UUID.randomUUID().toString();
        pendingImagePick = pick;
        try {
            startActivityForResult(new Intent(this, FiltrActivity.class).putExtra(FiltrHandoff.ID, pick.filtrId), FiltrHandoff.REQUEST);
        } catch (RuntimeException unavailable) {
            pendingImagePick = null; toast(Language.msg(l10n_atelier_image_picker_failed));
        }
    }

    private void receiveFiltr(int resultCode, Intent data) {
        ImagePick pick = pendingImagePick;
        if (pick == null || pick.filtrId == null) {
            if (resultCode == RESULT_OK) {
                if (data != null) {
                    String id = data.getStringExtra(FiltrHandoff.ID);
                    if (activeImagePick != null && id != null && id.equals(activeImagePick.filtrId)) return;
                    FiltrHandoff.discard(this,id);
                }
                toast(Language.msg(l10n_atelier_image_reselect));
            }
            return;
        }
        pendingImagePick = null;
        if (resultCode != RESULT_OK || data == null) { FiltrHandoff.discard(this,pick.filtrId); return; }
        String id = data.getStringExtra(FiltrHandoff.ID), mode = data.getStringExtra(FiltrHandoff.MODE);
        if (!pick.filtrId.equals(id) || !imageCurrent(pick)) {
            FiltrHandoff.discard(this,pick.filtrId); toast(Language.msg(l10n_atelier_image_changed)); return;
        }
        activeImagePick = pick;
        executeImageWork(pick, () -> {
            final String image;
            try { image = FiltrHandoff.take(this,pick.filtrId,mode); }
            catch (java.io.IOException failure) {
                runOnUiThread(() -> { if (acceptImageResult(pick)) toast(Language.msg(l10n_atelier_art_filtr_failed)); }); return;
            }
            runOnUiThread(() -> {
                if (!acceptImageResult(pick)) return;
                if (FiltrHandoff.MINT.equals(mode)) {
                    nftImage = image; saveDrafts(); renderCreateNft();
                    toast(Language.msg(l10n_atelier_art_filtr_loaded));
                } else {
                    renderGenerative();
                    ImagePick photo = new ImagePick();
                    photo.context = PICK_ARTPHOTO; photo.screen = screen; photo.generation = screenGeneration;
                    photo.sequence = ++imageSequence; photo.target = imageTarget(PICK_ARTPHOTO,0);
                    activeImagePick = photo;
                    toast(Language.msg(l10n_atelier_art_cartoonizing));
                    executeImageWork(photo, () -> {
                        byte[] bytes = android.util.Base64.decode(image, android.util.Base64.DEFAULT);
                        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);
                        artPhotoFromBitmap(bitmap,photo);
                    });
                }
            });
        });
    }

    private void artPhotoFromBitmap(android.graphics.Bitmap big, ImagePick pick) {
        android.graphics.Bitmap toon = big == null ? null : AiCartoon.cartoonize(this, big);
        final boolean ai = toon != null;
        int[] px = ImageTools.gridPixels(ai ? toon : big, 96);
        final String rgba = px == null ? null : artRgbaJson(px);
        /* AI paintings are flat — richer 10-color trace, and the
         * painting itself rides along for the Painted finish */
        final String paint = ai ? ImageTools.paintB64(toon, 8600) : "";  // fits the PROVEN 19500 pair budget
        runOnUiThread(() -> {
            if (!acceptImageResult(pick)) return;
            if (rgba == null) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_photo_read_failed)); return; }
            ArtStudio.with(this, s -> {
                if (!imageCurrent(pick)) return;
                s.setPhoto(rgba, ai ? 10 : 8, paint, k -> {
                if (!imageCurrent(pick)) return;
                artPhotoLoaded = k > 0;
                ART_THUMBS.remove("photo");
                artItems = new JSONArray();
                artPreviews.clear();
                toast(!artPhotoLoaded ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_photo_process_failed)
                        : ai ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_photo_ready)
                             : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_photo_fallback));
                if (screen == Screen.CREATE_GENERATIVE) renderGenerative();
            }); });
        });
    }

    /** ARGB pixels -> the flat [r,g,b,a,...] JSON array photoQuantize expects. */
    private static String artRgbaJson(int[] px) {
        StringBuilder sb = new StringBuilder(px.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            if (i > 0) sb.append(',');
            sb.append((p >> 16) & 255).append(',').append((p >> 8) & 255)
              .append(',').append(p & 255).append(",255");
        }
        sb.append(']');
        return sb.toString();
    }

    /* ---- studio draft: style + seed + edited configs survive restarts ---- */

    private void artLoadDraft() {
        JSONObject d = LocalStore.loadDraft(this, "artstudio");
        if (d == null) return;
        artStyle = d.optString("style", artStyle);
        artSeed = d.optString("seed", artSeed);
        genCount = d.optString("count", genCount);
        JSONObject cfgs = d.optJSONObject("cfgs");
        if (cfgs != null) {
            java.util.Iterator<String> it = cfgs.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject c2 = cfgs.optJSONObject(k);
                if (c2 != null) artCfgs.put(k, c2);
            }
        }
    }

    private void artSaveDraft() {
        JSONObject d = new JSONObject();
        put(d, "style", artStyle);
        put(d, "seed", artSeed);
        put(d, "count", genCount);
        JSONObject cfgs = new JSONObject();
        for (java.util.Map.Entry<String, JSONObject> e : artCfgs.entrySet()) put(cfgs, e.getKey(), e.getValue());
        put(d, "cfgs", cfgs);
        LocalStore.saveDraft(this, "artstudio", d);
    }

    /* ================= AIRDROP ================= */

    private void renderAirdrop(StateNft.Meta m, List<StateNft.Item> transferable) {
        setScreen(Screen.AIRDROP, () -> openCollection(m));
        final long generation = screenGeneration;
        appbar(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_title), true, false);
        hideNav();

        body.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_dispatch, transferable.size())));
        body.addView(Design.display(this, m.name, 18), lpm(0, 4, 0, 10));

        /* the whole collection to ONE address — a single txn is impossible
         * on-chain (state is per-transaction), so this queues one identity-
         * preserving transfer per lot through the resumable engine */
        LinearLayout wholeCard = lotCard();
        wholeCard.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_whole)));
        wholeCard.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_whole_help, transferable.size())), lpm(0, 4, 0, 8));
        EditText oneAddr = input(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_send_address_hint));
        oneAddr.setMinHeight(dp(48));
        wholeCard.addView(oneAddr, lpm(0, 0, 0, 8));
        TextView sendAll = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_send_all, transferable.size()), true);
        sendAll.setOnClickListener(v -> {
            if (isFinishing() || isDestroyed() || screen != Screen.AIRDROP || generation != screenGeneration) return;
            String a = oneAddr.getText().toString().trim();
            if (!Util.isValidAddress(a)) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_invalid_address)); return; }
            List<String> addrs = new ArrayList<>();
            for (int i = 0; i < transferable.size(); i++) addrs.add(a);
            JSONObject job = AirdropEngine.createJob(m, transferable, addrs);
            if (AirdropEngine.progress(job)[1] == 0) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_nothing)); return; }
            if (AirdropEngine.job(this, m.localId) != null) {
                toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_existing)); return;
            }
            if (!AirdropEngine.saveJob(this, job)) { toast(Language.msg(l10n_atelier_delivery_save_failed)); return; }
            engageEngine();
            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_queued, AirdropEngine.progress(job)[1], Util.shorten(a)));
            openCollection(m);
        });
        sendAll.setMinHeight(dp(50));
        wholeCard.addView(sendAll, lpm(0, 0, 0, 0));
        body.addView(wholeCard, lpm(0, 0, 0, 16));

        body.addView(sectionHead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_list), ""));
        body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_list_help, transferable.isEmpty() ? "—" : String.format(Locale.US, "%03d", transferable.get(0).index))), lpm(0, 0, 0, 10));

        EditText addrs = inputMulti("Mx…\nMx…\n0x…");
        body.addView(addrs, new LinearLayout.LayoutParams(-1, dp(140)){{ bottomMargin = dp(8); }});
        TextView status = Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_paste));
        body.addView(status, lpm(0, 0, 0, 10));

        LinearLayout mapping = vertical();
        body.addView(mapping, lpm(0, 0, 0, 10));

        final List<String> valid = new ArrayList<>();
        final String[] previewed = {null};
        addrs.addTextChangedListener(watch(value -> {
            valid.clear();
            previewed[0] = null;
            mapping.removeAllViews();
            Language.setText(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_preview_first));
        }));
        TextView preview = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_preview), false);
        preview.setOnClickListener(v -> {
            if (isFinishing() || isDestroyed() || screen != Screen.AIRDROP || generation != screenGeneration) return;
            valid.clear();
            mapping.removeAllViews();
            previewed[0] = addrs.getText().toString();
            String[] lines = previewed[0].split("\n");
            int bad = 0;
            for (String line : lines) {
                String a = line.trim().replace(" ", "");
                if (a.isEmpty()) continue;
                if (Util.isValidAddress(a)) valid.add(a);
                else bad++;
            }
            int n = Math.min(valid.size(), transferable.size());
            for (int i = 0; i < n; i++) {
                LinearLayout row = horizontal(Gravity.CENTER_VERTICAL);
                row.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1));
                row.setPadding(dp(10), dp(6), dp(10), dp(6));
                row.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_lot, String.format(Locale.US, "%03d", transferable.get(i).index))),
                        new LinearLayout.LayoutParams(dp(74), -2));
                row.addView(Design.text(this, Util.shorten(valid.get(i)), 11, Design.INK(), Design.mono()),
                        new LinearLayout.LayoutParams(0, -2, 1));
                mapping.addView(row, lpm(0, 0, 0, 5));
            }
            Language.TextBuilder summary = new Language.TextBuilder().append(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_ready, n));
            if (bad > 0) summary.append(" · ").append(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_invalid_lines, bad));
            if (valid.size() > transferable.size()) summary.append(" · ").append(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_extra_recipients, valid.size() - transferable.size()));
            if (transferable.size() > valid.size() && n > 0) summary.append(" · ").append(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_remaining, transferable.size() - valid.size()));
            Language.setText(status, summary.build());
        });
        preview.setMinHeight(dp(48));
        body.addView(preview, lpm(0, 0, 0, 8));

        TextView go = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_begin), true);
        go.setOnClickListener(v -> {
            if (isFinishing() || isDestroyed() || screen != Screen.AIRDROP || generation != screenGeneration) return;
            if (valid.isEmpty() || !addrs.getText().toString().equals(previewed[0])) {
                Language.setText(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_preview_first)); return;
            }
            JSONObject job = AirdropEngine.createJob(m, transferable, valid);
            if (AirdropEngine.progress(job)[1] == 0) { Language.setText(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_nothing)); return; }
            if (AirdropEngine.job(this, m.localId) != null) {
                toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_existing)); return;
            }
            if (!AirdropEngine.saveJob(this, job)) { toast(Language.msg(l10n_atelier_delivery_save_failed)); return; }
            engageEngine();
            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_armed));
            openCollection(m);
        });
        go.setMinHeight(dp(54));
        body.addView(go, lpm(0, 0, 0, 0));
    }

    private EditText inputMulti(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(Design.INK());
        e.setHintTextColor(Design.DIM());
        e.setTextSize(12.5f);
        e.setTypeface(Design.mono());
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setGravity(Gravity.TOP);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1.5f));
        return e;
    }

    /* ================= MANAGE ================= */

    /** The engine's own record — every tick summary, phase change and error,
     *  newest first. Reached from the pane above the mint rail and Manage. */
    private void renderEngineLog(Runnable back) {
        setScreen(Screen.ENGINE_LOG, back != null ? back : this::renderManage);
        appbar(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_engine_log), true, false);
        hideNav();

        JSONArray log = LocalStore.engineLog(this);
        long beat = LocalStore.lastHeartbeat(this);
        body.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_log_intro)));
        body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_log_summary, beat == 0 ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_no_tick) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_last_tick, (System.currentTimeMillis() - beat) / 1000), log.length())), lpm(0, 4, 0, 10));

        LinearLayout actions = horizontal(Gravity.CENTER_VERTICAL);
        TextView copy = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_copy_log), false);
        copy.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US);
            for (int i = log.length() - 1; i >= 0; i--) {
                JSONObject e = log.optJSONObject(i);
                if (e != null) sb.append(f.format(new java.util.Date(e.optLong("t"))))
                        .append("  ").append(e.optString("m")).append('\n');
            }
            copyText(sb.toString());
        });
        actions.addView(copy, weight(44, 0, 4));
        TextView clear = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_logs_clear), false);
        clear.setOnClickListener(v -> { LocalStore.clearEngineLog(this); renderEngineLog(back); });
        actions.addView(clear, weight(44, 4, 0));
        body.addView(actions, lpm(0, 0, 0, 12));

        if (log.length() == 0) {
            body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_log_empty)));
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US);
        for (int i = log.length() - 1; i >= 0; i--) {
            JSONObject e = log.optJSONObject(i);
            if (e == null) continue;
            String msg = e.optString("m");
            LinearLayout rowV = horizontal(Gravity.TOP);
            TextView tt = Design.text(this, fmt.format(new java.util.Date(e.optLong("t"))),
                    9.5f, Design.DIM(), Design.mono());
            tt.setPadding(0, dp(1), dp(8), 0);
            rowV.addView(tt);
            TextView mm = Design.text(this, msg, 10.5f,
                    msg.startsWith("ERROR") ? Design.ACCENT() : Design.INK(), Design.mono());
            rowV.addView(mm, new LinearLayout.LayoutParams(0, -2, 1));
            body.addView(rowV, lpm(0, 0, 0, 6));
        }
    }

    private void renderManage() {
        setScreen(Screen.MANAGE, null);
        appbar(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_manage), false, false);
        bottomNav(2);

        LinearLayout nodeCard = lotCard();
        nodeCard.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_tab_node)));
        boolean on = node != null && node.isEnabled();
        nodeCard.addView(Design.text(this, on ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_connected) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_not_ready),
                14, on ? Design.INK() : Design.ACCENT(), Design.sansBold()), lpm(0, 4, 0, 6));
        nodeCard.addView(kvRow(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_last_response), node == null || node.lastOkMs() == 0 ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_none_yet) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_received)));
        TextView engineLogBtn = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_engine_log), false);
        engineLogBtn.setOnClickListener(v -> renderEngineLog(this::renderManage));
        nodeCard.addView(engineLogBtn, lph(44, 0, 8, 0, 0));
        TextView reconnect = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_reconnect), false);
        reconnect.setOnClickListener(v -> { if (node != null) node.reRegister(); toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_reconnecting)); });
        nodeCard.addView(reconnect, lph(46, 0, 10, 0, 6));
        TextView openCore = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_history_open_core), false);
        openCore.setOnClickListener(v -> openMinimaCore());
        nodeCard.addView(openCore, lph(46, 0, 0, 0, 0));
        body.addView(nodeCard, lpm(0, 0, 0, 14));

        LinearLayout engineCard = lotCard();
        engineCard.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_mint_channel)));
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean exempt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        engineCard.addView(Design.note(this, exempt
                ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_battery_exempt)
                : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_battery_help)), lpm(0, 4, 0, 10));
        if (!exempt) {
            TextView allow = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_allow_background), true);
            allow.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_battery_settings)); }
            });
            engineCard.addView(allow, lph(48, 0, 0, 0, 0));
        }
        body.addView(engineCard, lpm(0, 0, 0, 14));

        LinearLayout walletCard = lotCard();
        walletCard.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_tab_wallet)));
        walletCard.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_adopt_help)), lpm(0, 4, 0, 10));
        TextView scanB = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_scan_wallet), true);
        scanB.setOnClickListener(v -> renderScan());
        walletCard.addView(scanB, lph(48, 0, 0, 0, 0));
        body.addView(walletCard, lpm(0, 0, 0, 14));

        LinearLayout hostCard = lotCard();
        hostCard.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_hosting)));
        hostCard.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_hosting_help)), lpm(0, 4, 0, 8));
        Hosting.Profile def = HostingStore.getDefault(this);
        hostCard.addView(kvRow(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_eth_wallet_default), def == null ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_none_yet) : def.name() + " · " + def.type()));
        TextView hostBtn = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_destinations), true);
        hostBtn.setOnClickListener(v -> renderHosting());
        hostCard.addView(hostBtn, lph(48, 0, 8, 0, 0));
        body.addView(hostCard, lpm(0, 0, 0, 14));

        LinearLayout appCard = lotCard();
        appCard.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_colophon)));
        appCard.addView(kvRow(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_app_heading), "Atelier — StateNFT Studio"));
        appCard.addView(kvRow(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_version), BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"));
        appCard.addView(kvRow(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_apps_package), getPackageName()));
        body.addView(appCard, lpm(0, 0, 0, 14));
    }

    /* ================= HOSTING ================= */

    private void renderHosting() {
        setScreen(Screen.HOSTING, this::renderManage);
        appbar(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_hosting), true, false);

        java.util.List<Hosting.Profile> profiles = HostingStore.list(this);
        if (profiles.isEmpty()) {
            LinearLayout empty = lotCard();
            empty.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_empty)));
            body.addView(empty, lpm(0, 0, 0, 12));
        }
        for (Hosting.Profile p : profiles) {
            LinearLayout card = lotCard();
            LinearLayout head = horizontal(Gravity.CENTER_VERTICAL);
            head.addView(Design.text(this, p.name().isEmpty() ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_unnamed) : p.name(), 15, Design.INK(), Design.sansBold()),
                    new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(Design.pill(this, p.type(), Design.PILL_DIM));
            if (p.isDefault()) head.addView(Design.pill(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_default), Design.PILL_DONE));
            card.addView(head, lpm(0, 0, 0, 8));
            LinearLayout row = horizontal(Gravity.CENTER_VERTICAL);
            TextView test = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_test), false);
            test.setOnClickListener(v -> testProfile(p));
            row.addView(test, weight(48, 0, 4));
            TextView edit = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_edit), false);
            edit.setOnClickListener(v -> renderHostingEdit(p));
            row.addView(edit, weight(48, 4, 4));
            TextView del = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_filez_delete), false);
            del.setOnClickListener(v -> new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                    .setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_delete, p.name()))
                    .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_filez_delete), (d, w) -> { HostingStore.remove(this, p.id()); renderHosting(); })
                    .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_backup_keep), null).show());
            row.addView(del, weight(48, 4, 0));
            card.addView(row, lpm(0, 0, 0, 6));
            if (!p.isDefault()) {
                TextView mkDef = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_make_default), false);
                mkDef.setOnClickListener(v -> { HostingStore.setDefault(this, p.id()); renderHosting(); });
                card.addView(mkDef, lph(42, 0, 0, 0, 0));
            }
            body.addView(card, lpm(0, 0, 0, 12));
        }
        TextView add = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_add), true);
        add.setOnClickListener(v -> renderHostingEdit(null));
        body.addView(add, lph(52, 0, 4, 0, 8));
    }

    private static final String[] HOST_TYPES = { Hosting.TYPE_SFTP, Hosting.TYPE_WEBDAV,
            Hosting.TYPE_KUBO, Hosting.TYPE_PINATA, Hosting.TYPE_GITHUB, Hosting.TYPE_ARWEAVE };
    private static final CharSequence[] HOST_TYPE_LABELS = { "SFTP", "WebDAV", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_ipfs_node), "Pinata", "GitHub", "Arweave" };

    private void renderHostingEdit(Hosting.Profile existing) {
        setScreen(Screen.HOSTING_EDIT, this::renderHosting);
        appbar(existing == null ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_add) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_edit_destination), true, false);
        hostEditProfile = existing != null ? existing
                : Hosting.Profile.fresh(Hosting.TYPE_SFTP);

        EditText nameF = fieldInto(body, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_name), Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_server_hint), hostEditProfile.name());
        nameF.addTextChangedListener(watch(sv -> Hosting.put(hostEditProfile.j, "name", sv)));

        body.addView(Design.kicker(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_type)), lpm(0, 10, 0, 5));
        LinearLayout chips = horizontal(Gravity.CENTER_VERTICAL);
        chips.setPadding(0, 0, 0, dp(4));
        for (int i = 0; i < HOST_TYPES.length; i++) {
            final String t = HOST_TYPES[i];
            TextView chip = Design.chip(this, HOST_TYPE_LABELS[i], t.equals(hostEditProfile.type()));
            chip.setOnClickListener(v -> {
                if (!t.equals(hostEditProfile.type())) {
                    Hosting.put(hostEditProfile.j, "type", t);
                    if (hostEditProfile.j.optJSONObject(t) == null) Hosting.put(hostEditProfile.j, t, new org.json.JSONObject());
                    renderHostingEdit(hostEditProfile);
                }
            });
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.rightMargin = dp(6);
            chips.addView(chip, clp);
        }
        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        body.addView(chipScroll, lpm(0, 0, 0, 4));

        org.json.JSONObject cfg = hostEditProfile.cfg();
        switch (hostEditProfile.type()) {
            case Hosting.TYPE_SFTP:
                hostField(cfg, "host", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_host), "eurobuddha.com", false);
                hostField(cfg, "port", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_terminal_txn_port), "22", false);
                hostField(cfg, "user", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_user), "root", false);
                final org.json.JSONObject sftpCfg = cfg;
                boolean keyAuth = "key".equals(cfg.optString("auth", "password"));
                body.addView(Design.kicker(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_authentication)), lpm(0, 10, 0, 5));
                LinearLayout authRow = horizontal(Gravity.CENTER_VERTICAL);
                TextView pwChip = Design.chip(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_backup_password), !keyAuth);
                TextView keyChip = Design.chip(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_eth_wallet_private_key), keyAuth);
                pwChip.setPadding(dp(12), dp(6), dp(12), dp(6));
                keyChip.setPadding(dp(12), dp(6), dp(12), dp(6));
                pwChip.setOnClickListener(v -> { Hosting.put(sftpCfg, "auth", "password"); renderHostingEdit(hostEditProfile); });
                keyChip.setOnClickListener(v -> { Hosting.put(sftpCfg, "auth", "key"); renderHostingEdit(hostEditProfile); });
                LinearLayout.LayoutParams achlp = new LinearLayout.LayoutParams(-2, -2); achlp.rightMargin = dp(8);
                authRow.addView(pwChip, achlp);
                authRow.addView(keyChip, new LinearLayout.LayoutParams(-2, -2));
                body.addView(authRow, lpm(0, 0, 0, 4));
                if (keyAuth) {
                    hostFieldMulti(cfg, "privateKey", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_private_key));
                    body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_private_key_help)), lpm(0, 6, 0, 0));
                } else {
                    hostField(cfg, "password", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_backup_password), "••••••", true);
                    body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_password_help)), lpm(0, 6, 0, 0));
                }
                hostField(cfg, "remoteRoot", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_remote_folder), "/var/www/html/", false);
                hostField(cfg, "urlPrefix", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_url_prefix), "https://eurobuddha.com/", false);
                body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_remote_help)), lpm(0, 8, 0, 0));
                break;
            case Hosting.TYPE_WEBDAV:
                hostField(cfg, "endpoint", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_webdav), "https://dav.example.com/files/", false);
                hostField(cfg, "user", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_user), "", false);
                hostField(cfg, "password", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_backup_password), "••••••", true);
                hostField(cfg, "urlPrefix", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_url_prefix), "https://example.com/files/", false);
                break;
            case Hosting.TYPE_KUBO:
                hostField(cfg, "apiUrl", "kubo RPC API", "https://api-ipfs.eurobuddha.com", false);
                hostField(cfg, "user", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_api_user), "ipfs", false);
                hostField(cfg, "password", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_api_password), "", true);
                hostField(cfg, "gateway", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_public_gateway), "https://ipfs.eurobuddha.com", false);
                body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_kubo_help)), lpm(0, 8, 0, 0));
                break;
            case Hosting.TYPE_PINATA:
                hostField(cfg, "jwt", "Pinata JWT", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_jwt_hint), true);
                hostField(cfg, "gateway", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_gateway_optional), "https://gateway.pinata.cloud", false);
                body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_pinata_help)), lpm(0, 8, 0, 0));
                break;
            case Hosting.TYPE_GITHUB:
                hostField(cfg, "owner", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_owner), "eurobuddha", false);
                hostField(cfg, "repo", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_repo), "nft-assets", false);
                hostField(cfg, "branch", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_branch), "main", false);
                hostField(cfg, "token", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_pat), "ghp_…", true);
                hostField(cfg, "serve", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_serve), "raw", false);
                hostField(cfg, "pagesPrefix", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_pages_prefix), "https://you.github.io/nft-assets/", false);
                break;
            case Hosting.TYPE_ARWEAVE:
                body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_arweave_help)), lpm(0, 6, 0, 4));
                if (cfg.optString("jwk", "").isEmpty()) {
                    final TextView gen = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_generate_wallet), true);
                    final org.json.JSONObject arCfg = cfg;
                    final Hosting.Profile generatedProfile = hostEditProfile;
                    gen.setOnClickListener(v -> {
                        Language.setText(gen, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_generating)); gen.setEnabled(false); gen.setAlpha(0.5f);
                        new Thread(() -> {   // RSA-4096 keygen takes seconds on-device
                            try {
                                String jwk = Ans104.generateJwkJson();
                                String wrapped = Crypt.encrypt(jwk);
                                main.post(() -> {
                                    Hosting.put(arCfg, "jwk", wrapped);
                                    // persist immediately — a generated key must never exist unsaved;
                                    // default the name so the upsert can't create a blank list entry
                                    if (generatedProfile.name().isEmpty()) Hosting.put(generatedProfile.j, "name", "Arweave");
                                    HostingStore.upsert(this, generatedProfile);
                                    toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_wallet_created));
                                    if (screen == Screen.HOSTING_EDIT && hostEditProfile == generatedProfile) renderHostingEdit(generatedProfile);
                                    else toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_wallet_reopen));
                                });
                            } catch (Crypt.Unavailable unavailable) {
                                main.post(() -> { hostingSecretUnavailable(); gen.setEnabled(true); gen.setAlpha(1f); Language.setText(gen, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_generate_wallet)); });
                            } catch (Exception e) {
                                main.post(() -> { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_generate_failed, e.getClass().getSimpleName()));
                                        if (screen == Screen.HOSTING_EDIT && hostEditProfile == generatedProfile) renderHostingEdit(generatedProfile); });
                            }
                        }).start();
                    });
                    body.addView(gen, lph(48, 0, 4, 0, 4));
                } else {
                    CharSequence addr;
                    try { addr = Ans104.addressFromJwk(Crypt.decrypt(cfg.optString("jwk", ""))); }
                    catch (Crypt.Unavailable unavailable) { addr = Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_unavailable); }
                    catch (Exception e) { addr = Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_unreadable); }
                    final String addrF = addr.toString();
                    body.addView(Design.kicker(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_wallet_address)), lpm(0, 8, 0, 5));
                    TextView addrV = Design.text(this, addr, 12f, Design.INK(), Design.mono());
                    addrV.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1.5f));
                    addrV.setPadding(dp(12), dp(10), dp(12), dp(10));
                    addrV.setClickable(true);
                    addrV.setOnClickListener(v -> { if (addrF.matches("[A-Za-z0-9_-]{43}")) copyText(addrF); });
                    body.addView(addrV, lpm(0, 0, 0, 4));
                    body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_backup_help)), lpm(0, 4, 0, 4));
                }
                if (!cfg.optString("jwk", "").isEmpty()) {
                    TextView backup = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_copy_backup), false);
                    backup.setMinHeight(dp(48)); backup.setPadding(dp(12), dp(8), dp(12), dp(8));
                    backup.setOnClickListener(v -> copyArweaveBackup(cfg));
                    body.addView(backup, lpm(0, 4, 0, 4));
                }
                hostFieldMulti(cfg, "jwk", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_jwk), "{\"kty\":\"RSA\",\"n\":\"…\",\"e\":\"AQAB\",\"d\":\"…\"}");
                hostField(cfg, "endpoint", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_turbo_endpoint), ArweaveUploader.DEFAULT_ENDPOINT, false);
                hostField(cfg, "gateway", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_gateway), "https://arweave.net", false);
                break;
        }

        LinearLayout actions = horizontal(Gravity.CENTER_VERTICAL);
        TextView save = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_save), true);
        save.setOnClickListener(v -> {
            if (!hostingSecretsReady(hostEditProfile)) return;
            if (hostEditProfile.name().isEmpty()) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_name_required)); return; }
            HostingStore.upsert(this, hostEditProfile);
            if (HostingStore.getDefault(this) == null || HostingStore.list(this).size() == 1) {
                HostingStore.setDefault(this, hostEditProfile.id());
            }
            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_terminal_ide_saved));
            renderHosting();
        });
        actions.addView(save, weight(48, 0, 4));
        TextView testB = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_save_test), false);
        testB.setOnClickListener(v -> {
            if (!hostingSecretsReady(hostEditProfile)) return;
            if (hostEditProfile.name().isEmpty()) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_name_required)); return; }
            HostingStore.upsert(this, hostEditProfile);
            testProfile(hostEditProfile);
        });
        actions.addView(testB, weight(48, 4, 0));
        body.addView(actions, lph(52, 0, 12, 0, 8));
    }

    /** A hosting-config field; SECRET fields are wrapped via Crypt on change
     *  and shown as a password input. */
    private final HostingSecrets hostSecrets = new HostingSecrets();

    private void hostingSecretUnavailable() {
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_unavailable).toString());
    }
    private boolean hostingSecretsReady(Hosting.Profile profile) {
        if (hostSecrets.retry(profile)) return true;
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_not_saved).toString());
        return false;
    }
    private void bindHostingSecret(EditText input, JSONObject cfg, String key,
            boolean revealStored, boolean trim, boolean keepBlank) {
        hostSecrets.bind(input, cfg, key);
        try { input.setText(hostSecrets.value(cfg, key, revealStored)); }
        catch (Crypt.Unavailable unavailable) {
            Language.error(input, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_unavailable));
        }
        if (hostSecrets.pending(cfg, key))
            Language.error(input, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_not_saved));
        input.addTextChangedListener(watch(value -> {
            String next = trim ? value.trim() : value;
            if (keepBlank && next.isEmpty()) {
                hostSecrets.discard(cfg, key); Language.error(input, null); return;
            }
            boolean saved = hostSecrets.write(cfg, key, next);
            Language.error(input, saved ? null : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_not_saved));
        }));
    }

    private void hostField(org.json.JSONObject cfg, String key, CharSequence label, CharSequence hint, boolean secret) {
        final boolean isSecret = secret;
        String cur = cfg.optString(key, "");
        if (!isSecret) {
            EditText e = fieldInto(body, label, hint, cur);
            e.addTextChangedListener(watch(sv -> Hosting.put(cfg, key, sv)));
            return;
        }
        // secret: kicker + [ input | eye ] row, so the value can be revealed
        // to confirm the keyboard didn't mangle it (a wrong char reads to the
        // server as "Failed password")
        body.addView(Design.kicker(this, label), lpm(0, 8, 0, 5));
        LinearLayout row = horizontal(Gravity.CENTER_VERTICAL);
        final EditText e = input(!cur.isEmpty() ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_stored) : hint);
        // NO_SUGGESTIONS + password variation: stop autocaps/predictive text
        final int MASKED = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        final int SHOWN = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        e.setInputType(MASKED);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        bindHostingSecret(e, cfg, key, false, false, true);
        row.addView(e, new LinearLayout.LayoutParams(0, dp(48), 1));
        final TextView eye = Design.text(this, "👁", 18, Design.INK(), Design.sans());
        eye.setGravity(Gravity.CENTER);
        eye.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1.5f));
        eye.setClickable(true);
        final boolean[] visible = { false };
        eye.setOnClickListener(v -> {
            visible[0] = !visible[0];
            int cursor = e.getSelectionEnd();
            e.setInputType(visible[0] ? SHOWN : MASKED);
            e.setTypeface(Design.sans());   // setInputType resets the typeface
            if (cursor >= 0 && cursor <= e.length()) e.setSelection(cursor);
            eye.setAlpha(visible[0] ? 1f : 0.5f);
        });
        eye.setAlpha(0.5f);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(48), dp(48));
        elp.leftMargin = dp(6);
        row.addView(eye, elp);
        body.addView(row, lpm(0, 0, 0, 4));
    }

    /** Multiline secret field (SFTP private key). Stored encrypted; the box
     *  stays blank when a key is already stored (type to replace). */
    private void hostFieldMulti(org.json.JSONObject cfg, String key, CharSequence label) {
        hostFieldMulti(cfg, key, label, "-----BEGIN OPENSSH PRIVATE KEY-----\n…");
    }

    private void hostFieldMulti(org.json.JSONObject cfg, String key, CharSequence label, CharSequence emptyHint) {
        String cur = cfg.optString(key, "");
        body.addView(Design.kicker(this, label), lpm(0, 8, 0, 5));
        EditText e = new EditText(this);
        Language.hint(e, cur.isEmpty() ? emptyHint : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_stored_paste));
        e.setTextColor(Design.INK());
        e.setHintTextColor(Design.DIM());
        e.setTextSize(11f);
        e.setTypeface(Design.mono());
        e.setSingleLine(false);
        e.setMinLines(4);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1.5f));
        bindHostingSecret(e, cfg, key, false, true, true);
        body.addView(e, lpm(0, 0, 0, 4));
    }

    /** Salon's frozen saved-destination checks, scoped to Atelier's screen lifetime. */
    private static final class HostingCheck {
        final Hosting.Profile profile, editor;
        final long generation;
        volatile boolean cancelled;
        HostingCheck(Hosting.Profile profile, Hosting.Profile editor, long generation) {
            this.profile=profile; this.editor=editor; this.generation=generation;
        }
    }
    private volatile HostingCheck hostingCheck;
    private boolean hostingStopped;
    private androidx.appcompat.app.AlertDialog hostingTrustDialog, hostingResultDialog;

    private void cancelHostingCheck() {
        HostingCheck op=hostingCheck; hostingCheck=null;
        if(op!=null) op.cancelled=true;
        if(hostingTrustDialog!=null) {
            androidx.appcompat.app.AlertDialog dialog=hostingTrustDialog;hostingTrustDialog=null;dialog.dismiss();
        }
        if(hostingResultDialog!=null) {
            androidx.appcompat.app.AlertDialog dialog=hostingResultDialog;hostingResultDialog=null;dialog.dismiss();
        }
    }
    private boolean hostingStoredCurrent(HostingCheck op) {
        if(op.cancelled || hostingCheck!=op) return false;
        try { return HostingStore.matches(this,op.profile); }
        catch(Exception unavailable) { return false; }
    }
    private boolean hostingCurrent(HostingCheck op) {
        if(hostingStopped || isFinishing() || isDestroyed() || op.generation!=screenGeneration) return false;
        try {
            return HostingStore.profileSnapshot(op.editor).equals(HostingStore.profileSnapshot(op.profile))
                    && hostingStoredCurrent(op);
        } catch(Exception unavailable) { return false; }
    }
    private HostingCheck beginHostingCheck(Hosting.Profile p) {
        cancelHostingCheck();
        if(hostingStopped || isFinishing() || isDestroyed()) return null;
        try {
            HostingCheck op=new HostingCheck(new Hosting.Profile(new JSONObject(p.j.toString())),p,screenGeneration);
            if(!HostingStore.matches(this,op.profile)) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_changed));return null; }
            hostingCheck=op;return op;
        } catch(Exception unavailable) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_storage));return null; }
    }
    private void finishHostingCheck(HostingCheck op,CharSequence message) {
        if(hostingCheck!=op) return;
        boolean current=hostingCurrent(op);cancelHostingCheck();
        if(current) {
            hostingResultDialog=new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                    .setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_test_title, op.profile.name())).setMessage(message).setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_ok),null).show();
            androidx.appcompat.app.AlertDialog dialog=hostingResultDialog;
            dialog.setOnDismissListener(d->{if(hostingResultDialog==dialog)hostingResultDialog=null;});
        }
    }
    private void testProfile(Hosting.Profile p) {
        HostingCheck op=beginHostingCheck(p);
        if(op==null) return;
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_testing_name, op.profile.name()));
        try {
            imageIo.execute(()->{
                if(!hostingStoredCurrent(op)) return;
                CharSequence result;
                try {
                    String nonce="atelier-"+System.currentTimeMillis();
                    String rel="atelier/probe/"+java.util.UUID.randomUUID()+".txt";
                    Hosting.Uploader uploader=Hosting.forProfile(op.profile);
                    String url;
                    try {
                        if(!hostingStoredCurrent(op)) return;
                        url=uploader.putFile(nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8),rel,"text/plain");
                    } finally {
                        if(uploader instanceof AutoCloseable) {
                            try { ((AutoCloseable)uploader).close(); } catch(Exception ignored) { }
                        }
                    }
                    if(!hostingStoredCurrent(op)) return;
                    if(Hosting.TYPE_ARWEAVE.equals(op.profile.type())) {
                        // Turbo's receipt precedes gateway indexing; retain Atelier's existing policy.
                        result=Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_turbo_tested, url);
                    } else {
                        Hosting.verifyUrl(url,op.profile);
                        result=Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_tested, url);
                    }
                } catch(SftpUploader.HostKeyUnverified untrusted) {
                    main.post(()->showHostingTrust(op,untrusted.fingerprint));return;
                } catch(Exception error) {
                    result=Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_failed, error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());
                }
                final CharSequence message=result;
                main.post(()->finishHostingCheck(op,message));
            });
        } catch(java.util.concurrent.RejectedExecutionException unavailable) { finishHostingCheck(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_storage)); }
    }
    /** Image uploads already validate their draft before handing their frozen host here. */
    private void confirmHostKey(Hosting.Profile p,String fp) {
        HostingCheck op=beginHostingCheck(p);
        if(op!=null) showHostingTrust(op,fp);
    }
    private void showHostingTrust(HostingCheck op,String fp) {
        if(!hostingCurrent(op) || !Hosting.TYPE_SFTP.equals(op.profile.type()) || fp==null || fp.isEmpty()
                || !op.profile.cfgStr("hostKeyFp").isEmpty() || hostingTrustDialog!=null) return;
        hostingTrustDialog=new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                .setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_verify_key)).setMessage(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_verify_body, fp))
                .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_trust_pin),(d,w)->{
                    synchronized(HostingStore.class) {
                        if(!hostingCurrent(op)) { if(hostingCheck==op)cancelHostingCheck();return; }
                        // Consume consent before persistence or retry. Never overwrite a changed destination.
                        cancelHostingCheck();
                        boolean saved;
                        try { saved=HostingStore.pinHostKey(this,op.profile,fp); }
                        catch(Exception unavailable) { saved=false; }
                        if(!saved) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_storage));return; }
                        Hosting.put(op.editor.cfg(),"hostKeyFp",fp);
                        testProfile(op.editor);
                    }
                })
                .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_cancel),(d,w)->{if(hostingCheck==op)cancelHostingCheck();}).show();
        hostingTrustDialog.setOnDismissListener(d->{if(hostingCheck==op)cancelHostingCheck();});
    }

    /** Explicit wallet backup; same sensitive-clipboard handling as minimaCore utils/Clip. */
    private void copyArweaveBackup(JSONObject cfg) {
        try {
            String jwk = Crypt.decrypt(cfg.optString("jwk", ""));
            Ans104.parseJwk(jwk); // Never claim a malformed value is a usable wallet backup.
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_core_wallet_clipboard)); return; }
            ClipData clip = ClipData.newPlainText("Arweave JWK", jwk);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.os.PersistableBundle extras = new android.os.PersistableBundle();
                extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            }
            cm.setPrimaryClip(clip);
            if (android.os.Build.VERSION.SDK_INT < 33) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_history_copied));
        } catch (Crypt.Unavailable unavailable) { hostingSecretUnavailable(); }
        catch (Exception invalid) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_unreadable)); }
    }

    private java.util.Map<String, String> mapOf(String... kv) {
        java.util.HashMap<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /* ================= SCAN ================= */

    private void renderScan() {
        setScreen(Screen.SCAN, this::renderManage);
        appbar(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_scan_wallet), true, false);
        hideNav();
        scanFound.clear();

        LinearLayout card = lotCard();
        card.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_survey)));
        inlineStatus = Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_reading));
        card.addView(inlineStatus, lpm(0, 6, 0, 8));
        ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        p.setIndeterminate(true);
        card.addView(p, new LinearLayout.LayoutParams(-1, dp(22)));
        body.addView(card, lpm(0, 0, 0, 14));
        body.addView(sectionHead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_found), ""));
        startScan();
    }

    private void startScan() {
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (screen != Screen.SCAN) return;
                if (json == null || !Boolean.TRUE.equals(json.opt("status"))
                        || !(json.opt("response") instanceof JSONArray)) {
                    setInlineStatus(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_scan_failed)); return;
                }
                scanBalance(json.optJSONArray("response"), 0);
            }
            @Override public void onError(String message) { if (screen == Screen.SCAN) setInlineStatus(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_scan_failed)); }
        });
    }

    private void scanBalance(JSONArray bal, int idx) {
        if (screen != Screen.SCAN) return;
        if (idx >= bal.length()) {
            loadLocalCollections();
            setInlineStatus(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_catalogue_count, scanFound.size()));
            TextView done = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_chat_done), true);
            done.setOnClickListener(v -> renderGallery());
            body.addView(done, lph(50, 0, 10, 0, 0));
            return;
        }
        setInlineStatus(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_manage_checking, idx + 1, bal.length()));
        JSONObject row = bal.optJSONObject(idx);
        if (row == null) { scanBalance(bal, idx + 1); return; }
        String tid = row.optString("tokenid", "");
        StateNft.Meta meta = StateNft.parseMeta(tid, row.opt("token"));
        if (!StateNft.isCandidate(meta)) { scanBalance(bal, idx + 1); return; }
        inspectTokenForScan(meta, () -> scanBalance(bal, idx + 1));
    }

    private void inspectTokenForScan(StateNft.Meta candidate, Runnable done) {
        node.cmd("tokens tokenid:" + candidate.tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject t = json.optJSONObject("response");
                String script = t == null ? "" : t.optString("script", "");
                String pk = StateNft.creatorPk(script);
                if (pk.isEmpty()) { done.run(); return; }
                StateNft.Meta m = StateNft.parseMeta(candidate.tokenid, t);
                m.creatorPk = pk;
                countOwned(m, () -> {
                    JSONObject known = LocalStore.findByTokenid(MainActivity.this, m.tokenid);
                    if (known == null) {
                        m.localId = LocalStore.nextId(MainActivity.this);
                        m.phase = "DONE";
                        m.created = false;
                        LocalStore.upsert(MainActivity.this, MintEngine.rowFromMeta(m, new JSONArray()));
                        scanFound.add(m);
                    } else {
                        scanFound.add(MintEngine.metaFromRow(known));
                    }
                    if (screen == Screen.SCAN) body.addView(collectionCard(m), lpm(0, 0, 0, 12));
                    done.run();
                });
            }
            @Override public void onError(String message) { done.run(); }
        });
    }

    /* ================= shared node helpers ================= */

    private void countOwned(StateNft.Meta m, Runnable done) {
        MintEngine.tokenCoinsBounded(node, m.tokenid, true, (coins, partial) -> {
            m.owned = coins.length();
            m.minted = distinctStamped(coins);
            checkCreator(m, done);
        }, e -> checkCreator(m, done));
    }

    private void checkCreator(StateNft.Meta m, Runnable done) {
        if (m.creatorPk.isEmpty()) { done.run(); return; }
        node.cmd("keys action:list publickey:" + m.creatorPk, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                Object r = json.opt("response");
                JSONArray keys = null;
                if (r instanceof JSONObject) keys = ((JSONObject) r).optJSONArray("keys");
                else if (r instanceof JSONArray) keys = (JSONArray) r;
                if (keys != null) {
                    for (int i = 0; i < keys.length(); i++) {
                        JSONObject k = keys.optJSONObject(i);
                        if (k != null && m.creatorPk.equals(k.optString("publickey"))) m.creator = true;
                    }
                }
                done.run();
            }
            @Override public void onError(String message) { done.run(); }
        });
    }

    /** NFT Wallet's exact-output evidence; an absent input is never proof of delivery. */
    private void watchDeparture(String tokenid, JSONObject receipt, long generation, Runnable ok, Runnable unsure) {
        final String outputid, destination;
        try {
            BurialEvidence.journal(new JSONArray().put(receipt), tokenid);
            outputid = BurialEvidence.id(receipt, "output");
            destination = BurialEvidence.id(receipt, "address");
        } catch (RuntimeException e) { unsure.run(); return; }
        final int[] tries = {0};
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (!transferScreenActive(generation) || !departureWatchers.contains(poll[0])) return;
            node.cmd("coins coinid:" + outputid, new NodeApi.Cb() {
                private boolean active() {
                    return transferScreenActive(generation) && departureWatchers.contains(poll[0]);
                }
                @Override public void onResult(JSONObject json) {
                    if (!active()) return;
                    try {
                        JSONArray coins = BurialEvidence.coins(json, tokenid);
                        if (coins.length() == 1 && BurialEvidence.confirmed(receipt, coins.optJSONObject(0), destination)) {
                            departureWatchers.remove(poll[0]); ok.run(); return;
                        }
                    } catch (RuntimeException e) { /* Incomplete evidence stays unconfirmed. */ }
                    reschedule();
                }
                @Override public void onError(String message) { if (active()) reschedule(); }
                private void reschedule() {
                    if (++tries[0] >= 20) { departureWatchers.remove(poll[0]); unsure.run(); }
                    else main.postDelayed(poll[0], 20000);
                }
            });
        };
        departureWatchers.add(poll[0]);
        main.postDelayed(poll[0], 20000);
    }

    private int distinctStamped(JSONArray coins) {
        if (coins == null) return 0;
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < coins.length(); i++) {
            String s = StateNft.stamped(coins.optJSONObject(i));
            if (s != null && s.matches("^[0-9]+$")) seen.add(s);
        }
        return seen.size();
    }

    /* ================= local store & drafts ================= */

    private void loadLocalCollections() {
        collections.clear();
        JSONArray rows = LocalStore.load(this);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null) collections.add(MintEngine.metaFromRow(row));
        }
    }

    private StateNft.Meta findCollectionByLocalId(long id, StateNft.Meta fallback) {
        loadLocalCollections();
        for (StateNft.Meta m : collections) if (m.localId == id) return m;
        return fallback;
    }

    private String collectionLeadImage(StateNft.Meta m) {
        String icon = IconResolver.resolve(m == null ? "" : m.icon);
        if (icon != null && !icon.isEmpty()) return icon;
        List<StateNft.Item> local = localItemsForMeta(m);
        if (!local.isEmpty()) return local.get(0).imageUrl;
        return null;
    }

    private List<StateNft.Item> localItemsForMeta(StateNft.Meta m) {
        ArrayList<StateNft.Item> out = new ArrayList<>();
        if (m == null) return out;
        JSONObject row = LocalStore.findById(this, m.localId);
        JSONArray items = row == null ? new JSONArray() : MintEngine.localItems(row);
        for (int i = 0; i < items.length(); i++) {
            JSONObject j = items.optJSONObject(i);
            if (j == null) continue;
            StateNft.Item it = new StateNft.Item();
            it.index = j.optInt("idx", i + 1);
            it.owned = false;
            it.imageUrl = "embed".equals(m.mode)
                    ? ImageTools.dataUri(j.optString("image"))
                    : j.optString("image");
            out.add(it);
        }
        return out;
    }

    private List<StateNft.Item> mergeLocalPreviews(StateNft.Meta m, List<StateNft.Item> chainItems) {
        List<StateNft.Item> local = localItemsForMeta(m);
        if (local.isEmpty()) return chainItems;
        java.util.HashMap<Integer, String> preview = new java.util.HashMap<>();
        for (StateNft.Item it : local) preview.put(it.index, it.imageUrl);
        for (StateNft.Item it : chainItems) {
            String localUrl = preview.get(it.index);
            if (localUrl != null && !localUrl.isEmpty()
                    && (it.coin == null || it.imageUrl == null || it.imageUrl.isEmpty()
                    || it.imageUrl.equals(IconResolver.resolve(m.icon)))) {
                it.imageUrl = localUrl;
            }
        }
        return chainItems;
    }

    private void ensureCreateImages(int count) {
        if (createImages.length == count) return;
        String[] next = new String[count];
        for (int i = 0; i < Math.min(createImages.length, next.length); i++) next[i] = createImages[i];
        createImages = next;
    }

    private void saveCollectionDraft(EditText name, EditText desc, EditText size, EditText base,
                                     EditText ext, EditText icon, EditText external, EditText web) {
        if (name != null) createName = name.getText().toString();
        if (desc != null) createDesc = desc.getText().toString();
        if (size != null) createSize = size.getText().toString();
        if (base != null) createBase = base.getText().toString();
        if (ext != null) createExt = ext.getText().toString();
        if (icon != null) createIcon = icon.getText().toString();
        if (external != null) createExternal = external.getText().toString();
        if (web != null) createWeb = web.getText().toString();
    }

    private void resetCollectionDraft() {
        createName = ""; createDesc = ""; createSize = "12"; createWvAuto = false;
        createBase = ""; createExt = ".jpg"; createIcon = ""; createExternal = ""; createWeb = "";
        createUploadedBase = ""; createUploadedExt = "";
        createImages = new String[0];
        createIconB64 = "";
        collectionItemTraits = null;
        LocalStore.clearDraft(this, "collection");
    }

    /* ---- image slot grid (collection embed mode) ---- */

    private LinearLayout imageSlotGrid() {
        LinearLayout grid = vertical();
        int cols = 4;
        for (int i = 0; i < createImages.length; i += cols) {
            LinearLayout row = horizontal(Gravity.CENTER);
            for (int c2 = 0; c2 < cols; c2++) {
                int idx = i + c2;
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(84), 1);
                if (c2 != 0) slp.leftMargin = dp(8);
                if (idx >= createImages.length) row.addView(new Space(this), slp);
                else row.addView(imageSlotCell(idx), slp);
            }
            grid.addView(row, lpm(0, 0, 0, 8));
        }
        return grid;
    }

    private View imageSlotCell(int idx) {
        final long generation = screenGeneration;
        boolean ready = idx < createImages.length && createImages[idx] != null && !createImages[idx].isEmpty();
        FrameLayout cell = new FrameLayout(this);
        cell.setBackground(ready ? Design.ruled(this, Design.CARD(), Design.INK(), 1.5f)
                : Design.dashed(this, Design.CARD(), Design.DIM()));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageBitmap(Identicon.forToken("slot" + idx, 160));
        if (ready) ImageLoader.loadOver(this, ImageTools.dataUri(createImages[idx]), img);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-1, -1);
        int bpx = dp(2);
        ilp.setMargins(bpx, bpx, bpx, bpx);
        cell.addView(img, ilp);
        TextView tag = Design.pill(this, String.format(Locale.US, "%02d %s", idx + 1, ready ? "⟳" : "+"),
                ready ? Design.PILL_DONE : Design.PILL_DIM);
        FrameLayout.LayoutParams tagLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        tagLp.setMargins(dp(4), 0, 0, dp(4));
        cell.addView(tag, tagLp);
        cell.setClickable(true);
        cell.setOnClickListener(v -> {
            if (isFinishing() || isDestroyed() || generation != screenGeneration || screen != Screen.CREATE_COLLECTION || idx >= createImages.length) return;
            if (ready) {
                if ("image/svg+xml".equals(ImageTools.mimeOf(createImages[idx]))) {
                    toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_vector_replace));
                    pickImage(idx);
                } else {
                    // a filled plate offers all three exits — crop-only trapped
                    // an oversized pick with no way to swap it (2026-08-10)
                    new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                            .setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_plate, String.format(Locale.US, "%02d", idx + 1)))
                            .setItems(new CharSequence[]{
                                    Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_edit_plate),
                                    Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_replace_plate),
                                    Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_clear_plate) }, (d, which) -> {
                                if (which == 0) {
                                    showEditor(createImages[idx], ImageTools.STATE_IMG_BUDGET, edited -> {
                                        createImages[idx] = edited;
                                        renderCreateCollection();
                                    });
                                } else if (which == 1) {
                                    pickImage(idx);
                                } else {
                                    createImages[idx] = "";
                                    renderCreateCollection();
                                }
                            })
                            .show();
                }
            } else pickImage(idx);
        });
        return cell;
    }

    /* ---- recovery (NEEDIMAGES) ---- */

    private LinearLayout recoveryPanel(StateNft.Meta m) {
        final long generation = screenGeneration;
        LinearLayout panel = vertical();
        panel.setBackground(Design.ruled(this, Design.CARD(), Design.ACCENT(), 2f));
        panel.setPadding(dp(12), dp(10), dp(12), dp(12));
        panel.addView(Design.text(this, Language.msg(l10n_atelier_image_missing_title), 12, Design.ACCENT(), Design.sansBold()));
        panel.addView(Design.note(this, Language.msg(l10n_atelier_image_missing_help)), lpm(0, 4, 0, 10));
        TextView pick = Design.button(this, Language.msg(l10n_atelier_image_choose_missing), true);
        pick.setOnClickListener(v -> { if (collectionCurrent(m,generation)) pickRecoveryImages(m); });
        pick.setMinHeight(dp(48)); panel.addView(pick, lpm(0, 0, 0, 10));
        panel.addView(recoveryImageGrid(m));
        int missing = missingLocalImages(m);
        panel.addView(Design.note(this, missing == 0 ? Language.msg(l10n_atelier_image_all_present) : Language.msg(l10n_atelier_image_missing_count, missing)), lpm(0, 6, 0, 0));
        if (missing == 0) {
            TextView resume = Design.button(this, Language.msg(l10n_atelier_image_resume), true);
            resume.setOnClickListener(v -> {
                if (!collectionCurrent(m,generation)) return;
                JSONObject row = LocalStore.findById(this, m.localId);
                if (row != null && "NEEDIMAGES".equals(row.optString("phase")) && missingLocalImagesInRow(row) == 0) {
                    put(row, "phase", "MOVE");
                    put(row, "error", "");
                    LocalStore.upsert(this, row);
                    engageEngine();
                    loadLocalCollections();
                    openMeta = findCollectionByLocalId(m.localId, m);
                    openCollection(openMeta);
                }
            });
            resume.setMinHeight(dp(48)); panel.addView(resume, lpm(0, 10, 0, 0));
        }
        return panel;
    }

    private LinearLayout recoveryImageGrid(StateNft.Meta m) {
        LinearLayout grid = vertical();
        // resolve the row ONCE — per-slot lookups scanned the whole store per cell
        JSONObject storeRow = LocalStore.findById(this, m.localId);
        int cols = 4;
        for (int i = 1; i <= Math.max(0, m.size); i += cols) {
            LinearLayout row = horizontal(Gravity.CENTER);
            for (int c2 = 0; c2 < cols; c2++) {
                int idx = i + c2;
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(84), 1);
                if (c2 != 0) slp.leftMargin = dp(8);
                if (idx > m.size) row.addView(new Space(this), slp);
                else row.addView(recoverySlotCell(m, storeRow, idx), slp);
            }
            grid.addView(row, lpm(0, 0, 0, 8));
        }
        return grid;
    }

    private View recoverySlotCell(StateNft.Meta m, JSONObject storeRow, int idx) {
        final long generation = screenGeneration;
        final String expected = storeRow == null ? "" : storeRow.toString();
        String image = storeRow == null ? "" : localImageInRow(storeRow, idx);
        boolean ready = image != null && !image.isEmpty();
        FrameLayout cell = new FrameLayout(this);
        cell.setBackground(ready ? Design.ruled(this, Design.CARD(), Design.INK(), 1.5f)
                : Design.dashed(this, Design.CARD(), Design.ACCENT()));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageBitmap(Identicon.forToken("recover" + idx, 160));
        if (ready) ImageLoader.loadOver(this, ImageTools.dataUri(image), img);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-1, -1);
        int bpx = dp(2);
        ilp.setMargins(bpx, bpx, bpx, bpx);
        cell.addView(img, ilp);
        TextView tag = Design.pill(this, String.format(Locale.US, "%02d", idx),
                ready ? Design.PILL_DONE : Design.PILL_ERR);
        FrameLayout.LayoutParams tagLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        tagLp.setMargins(dp(4), 0, 0, dp(4));
        cell.addView(tag, tagLp);
        cell.setClickable(true);
        cell.setOnClickListener(v -> {
            JSONObject current = LocalStore.findById(this,m.localId);
            if (!collectionCurrent(m,generation) || current == null || !expected.equals(current.toString())
                    || !"NEEDIMAGES".equals(current.optString("phase"))) return;
            if (ready && !"image/svg+xml".equals(ImageTools.mimeOf(image))) {
                showEditor(image, ImageTools.STATE_IMG_BUDGET, edited -> {
                    JSONObject row = LocalStore.findById(this, m.localId);
                    if (row == null) return;
                    setLocalImage(row, idx, edited);
                    LocalStore.upsert(this, row);
                    loadLocalCollections();
                    openMeta = findCollectionByLocalId(m.localId, openMeta);
                    openCollection(openMeta);
                });
            } else pickRecoveryImage(m, idx);
        });
        return cell;
    }

    private int missingLocalImages(StateNft.Meta m) {
        if (m == null || !"embed".equals(m.mode)) return 0;
        JSONObject row = LocalStore.findById(this, m.localId);
        if (row == null) return m.size;
        return missingLocalImagesInRow(row);
    }

    private int applyRecoveryImages(long localId, int startZero, boolean batch, ArrayList<String> images) {
        JSONObject row = LocalStore.findById(this, localId);
        if (row == null) return 0;
        int size = row.optInt("size", 0);
        int added = 0;
        if (batch) {
            int slot = Math.max(1, startZero + 1);
            for (String b64 : images) {
                if (b64 == null || b64.isEmpty()) continue;
                while (slot <= size && !localImageInRow(row, slot).isEmpty()) slot++;
                if (slot > size) break;
                setLocalImage(row, slot, b64);
                added++;
                slot++;
            }
        } else if (!images.isEmpty()) {
            String b64 = images.get(0);
            int idx = Math.max(1, startZero + 1);
            if (idx <= size && b64 != null && !b64.isEmpty()) {
                setLocalImage(row, idx, b64);
                added = 1;
            }
        }
        put(row, "phase", missingLocalImagesInRow(row) == 0 ? "MOVE" : "NEEDIMAGES");
        put(row, "error", missingLocalImagesInRow(row) == 0 ? "" : missingLocalImagesInRow(row) + " images still missing");
        LocalStore.upsert(this, row);
        return added;
    }

    private String localImageInRow(JSONObject row, int idx) {
        JSONArray items = MintEngine.localItems(row);
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && it.optInt("idx") == idx) return it.optString("image", "");
        }
        return "";
    }

    private int missingLocalImagesInRow(JSONObject row) {
        int size = row.optInt("size", 0);
        int missing = 0;
        for (int i = 1; i <= size; i++) if (localImageInRow(row, i).isEmpty()) missing++;
        return missing;
    }

    private void setLocalImage(JSONObject row, int idx, String b64) {
        JSONArray items = MintEngine.localItems(row);
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && it.optInt("idx") == idx) {
                put(it, "image", b64);
                put(row, "items", items);
                return;
            }
        }
        JSONObject it = new JSONObject();
        put(it, "idx", idx);
        put(it, "image", b64);
        items.put(it);
        put(row, "items", items);
    }

    /* ================= image picking ================= */

    private void pickImages() {
        pendingPickContext = PICK_CREATE;
        pendingImageIndex = -1;
        pendingBatchImages = true;
        launchPicker(true);
    }

    private void pickImage(int idx) {
        pendingPickContext = PICK_CREATE;
        pendingImageIndex = idx;
        pendingBatchImages = false;
        launchPicker(false);
    }

    private void pickNftImage() {
        pendingPickContext = PICK_NFT;
        pendingImageIndex = -1;
        pendingBatchImages = false;
        launchPicker(false);
    }

    private void pickRecoveryImages(StateNft.Meta m) {
        pendingPickContext = PICK_RECOVERY;
        pendingRecoveryId = m.localId;
        pendingImageIndex = firstMissingLocalImageIndex(m) - 1;
        pendingBatchImages = true;
        launchPicker(true);
    }

    private void pickRecoveryImage(StateNft.Meta m, int idx) {
        pendingPickContext = PICK_RECOVERY;
        pendingRecoveryId = m.localId;
        pendingImageIndex = idx - 1;
        pendingBatchImages = false;
        launchPicker(false);
    }

    private void launchPicker(boolean multiple) {
        if (pendingImagePick != null || activeImagePick != null) { toast(Language.msg(l10n_atelier_image_busy)); return; }
        if (isFinishing() || isDestroyed()) return;
        ImagePick pick = new ImagePick();
        pick.context = pendingPickContext; pick.batch = pendingBatchImages;
        pick.start = pick.context == PICK_CREATE && pick.batch ? firstEmptyImageSlot() : pendingImageIndex;
        pick.recovery = pendingRecoveryId; pick.lane = pendingHostLane; pick.count = pendingHostPlateCount;
        Screen expected = pick.context == PICK_RECOVERY ? Screen.COLLECTION
                : pick.context == PICK_ARTPHOTO ? Screen.CREATE_GENERATIVE
                : pick.context == PICK_NFT ? Screen.CREATE_NFT
                : pick.context == PICK_TOKICON ? Screen.CREATE_TOKEN : Screen.CREATE_COLLECTION;
        if (pick.context == PICK_HOST_ICON || pick.context == PICK_HOST_EXTERNAL) {
            if ("nfturl".equals(pick.lane) || "nftext".equals(pick.lane)) expected = Screen.CREATE_NFT;
            else if ("tokicon".equals(pick.lane)) expected = Screen.CREATE_TOKEN;
        }
        if (screen != expected) return;
        pick.screen = screen; pick.generation = screenGeneration; pick.sequence = ++imageSequence;
        pick.target = imageTarget(pick.context,pick.recovery);
        pick.slug = Hosting.slug(screen == Screen.CREATE_NFT ? nftName : screen == Screen.CREATE_TOKEN ? tokName : createName);
        if (pick.context >= PICK_HOST_ICON) {
            Hosting.Profile host = HostingStore.getDefault(this);
            if (host == null) { toast(Language.msg(l10n_atelier_image_no_host)); return; }
            try { pick.hostJson = host.j.toString(); pick.host = new Hosting.Profile(new JSONObject(pick.hostJson)); }
            catch (org.json.JSONException invalid) { return; }
        }
        if (!imageCurrent(pick)) { toast(Language.msg(l10n_atelier_image_changed)); return; }
        pendingImagePick = pick;
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType("image/*");
            if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(i, Language.msg(l10n_atelier_image_choose)), 7001);
        } catch (RuntimeException unavailable) {
            pendingImagePick = null; toast(Language.msg(l10n_atelier_image_picker_failed));
        }
    }

    /** Salon media-request pattern, scoped to Atelier's draft and screen generation. */
    private static final class ImagePick {
        int context, start, count; boolean batch; long recovery, generation, sequence;
        Screen screen; String lane, target, slug, hostJson, filtrId;
        Hosting.Profile host; volatile boolean cancelled;
        Runnable cancelConfirmation;
    }
    private ImagePick pendingImagePick, activeImagePick;
    private long imageSequence;
    private final java.util.concurrent.ExecutorService imageIo = java.util.concurrent.Executors.newSingleThreadExecutor();

    private String imageTarget(int context, long recovery) {
        if (context == -1) return new JSONArray().put(mintDraftSnapshot(0)).put(artDraftSnapshot()).toString();
        if (context == PICK_RECOVERY) {
            JSONObject row = LocalStore.findById(this, recovery);
            return row == null ? "" : row.toString();
        }
        if (context == PICK_ARTPHOTO) return artDraftSnapshot();
        return mintDraftSnapshot(mintDraftKind());
    }
    private boolean imageCurrent(ImagePick pick) {
        if (pick == null || pick.cancelled || isFinishing() || isDestroyed()
                || pick.sequence != imageSequence || pick.generation != screenGeneration || pick.screen != screen
                || !pick.target.equals(imageTarget(pick.context,pick.recovery))) return false;
        if (pick.context == PICK_RECOVERY) {
            JSONObject row = LocalStore.findById(this,pick.recovery);
            if (openMeta == null || openMeta.localId != pick.recovery || row == null
                    || !"NEEDIMAGES".equals(row.optString("phase"))) return false;
        }
        if (pick.host != null) {
            Hosting.Profile current = HostingStore.getDefault(this);
            if (current == null || !pick.hostJson.equals(current.j.toString())) return false;
        }
        return true;
    }
    private void cancelImageRequests() {
        for (ImagePick pick : new ImagePick[]{pendingImagePick,activeImagePick}) if (pick != null) {
            pick.cancelled = true;
            if (pick.filtrId != null) FiltrHandoff.discard(this,pick.filtrId);
            if (pick.cancelConfirmation != null) pick.cancelConfirmation.run();
        }
    }
    private boolean acceptImageResult(ImagePick pick) {
        if (activeImagePick != pick) return false;
        activeImagePick = null;
        if (imageCurrent(pick)) return true;
        if (!isFinishing() && !isDestroyed()) toast(Language.msg(l10n_atelier_image_changed));
        return false;
    }
    private void executeImageWork(ImagePick pick, Runnable work) {
        try {
            imageIo.execute(() -> {
                if (pick.cancelled) { FiltrHandoff.discard(this,pick.filtrId); main.post(() -> acceptImageResult(pick)); return; }
                try { work.run(); }
                catch (Exception failure) { main.post(() -> {
                    if (acceptImageResult(pick)) toast(Language.msg(l10n_atelier_image_failed, failure.getClass().getSimpleName()));
                }); }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            FiltrHandoff.discard(this,pick.filtrId);
            if (acceptImageResult(pick)) toast(Language.msg(l10n_atelier_image_failed, stopped.getClass().getSimpleName()));
        }
    }
    /** Worker-to-UI handoff before creating a transport; all draft reads stay on the UI thread. */
    private boolean allowImageUpload(ImagePick pick) {
        // This handoff must never block the UI thread if called by a synchronous executor.
        if (Looper.myLooper() == main.getLooper()) {
            if (activeImagePick == pick && imageCurrent(pick)) return true;
            acceptImageResult(pick); return false;
        }
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean allowed = new java.util.concurrent.atomic.AtomicBoolean();
        main.post(() -> { allowed.set(activeImagePick == pick && imageCurrent(pick)); ready.countDown(); });
        try {
            if (ready.await(30, java.util.concurrent.TimeUnit.SECONDS) && allowed.get() && !pick.cancelled) return true;
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        pick.cancelled = true;
        main.post(() -> acceptImageResult(pick));
        return false;
    }

    private CharSequence imageError(String error) {
        if ("could not open the image".equals(error) || "Could not encode the image as JPEG".equals(error))
            return Language.msg(l10n_atelier_image_process_failed);
        if ("image is over 60MB".equals(error)) return Language.msg(l10n_atelier_image_too_large);
        if ("Empty upload reference".equals(error)) return Language.msg(l10n_atelier_image_empty_reference);
        if ("Upload stopped after navigation".equals(error)) return Language.msg(l10n_atelier_image_changed);
        return error;
    }


    /* ---- hosting upload rows + lanes ---- */

    /** A "Upload to hosting…" button; disabled with guidance when there is no
     *  default destination. */
    private View hostingUploadRow(String lane, CharSequence label, Runnable onTap) {
        boolean ready = HostingStore.getDefault(this) != null;
        TextView b = Design.button(this, ready ? label : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_setup_hosting), false);
        if (ready) {
            b.setOnClickListener(v -> onTap.run());
        } else {
            b.setOnClickListener(v -> renderHosting());
        }
        return b;
    }

    // Only set during the synchronous, draft-checked verification continuation.
    private boolean hostGatePassed = false;
    private String confirmedCollectionDraft;

    /** Reuse persisted draft structure; include pending fields that must survive completion. */
    private String mintDraftSnapshot(int kind) {
        JSONObject draft = buildDraftJsons()[kind];
        if (kind == 0) {
            put(draft, "pendingType", traitTypeDraft); put(draft, "pendingValue", traitValueDraft);
        } else if (kind == 1) {
            put(draft, "pendingKey", pairKeyDraft); put(draft, "pendingValue", pairValueDraft);
        } else {
            put(draft, "wvAuto", createWvAuto);
            put(draft, "itemTraits", collectionItemTraits);
        }
        return draft.toString();
    }

    private int mintDraftKind() {
        return screen == Screen.CREATE_NFT ? 0 : screen == Screen.CREATE_TOKEN ? 1 : 2;
    }

    private void mintDraftChanged() {
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_draft_changed));
    }

    private interface HostFail { void onFail(String msg); }
    private void verifyHostedThen(java.util.List<String> urls, Runnable onOk) {
        verifyHostedThen(urls, onOk, this::toast);
    }
    private volatile boolean verifyGateRunning = false;
    private volatile boolean verifyGateCancel = false;

    /** Same hosted verifier/deadline as the standalone app, bound to this draft and screen. */
    private void verifyHostedThen(java.util.List<String> urls, Runnable onOk, HostFail onFail) {
        if (verifyGateRunning) {
            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_verifying_busy));
            return;
        }
        final int kind = mintDraftKind();
        final String draft = mintDraftSnapshot(kind);
        final long generation = screenGeneration;
        final java.util.List<String> checkedUrls = new java.util.ArrayList<>(urls);
        verifyGateRunning = true;
        verifyGateCancel = false;
        final Hosting.Profile p = HostingStore.getDefault(this);
        final boolean slow = p != null && Hosting.TYPE_ARWEAVE.equals(p.type());
        LocalStore.logEvent(this, "MINT GATE start (" + checkedUrls.size() + " urls, type="
                + (p == null ? "none" : p.type()) + "): " + checkedUrls);
        final TextView msgV = Design.note(this, Language.msg(slow
                ? com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_slow_verification
                : com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_verifying));
        msgV.setPadding(dp(20), dp(14), dp(20), dp(6));
        androidx.appcompat.app.AlertDialog dlg = null;
        try {
            dlg = new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                    .setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_verify_title))
                    .setView(msgV).setCancelable(false)
                    .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_hide), null)
                    .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_stop), null).show();
        } catch (RuntimeException unavailable) {
            verifyGateRunning = false;
            return; // No invisible, uncancellable mint attempt if the activity cannot show the gate.
        }
        final androidx.appcompat.app.AlertDialog dlgF = dlg;
        // VoiceRecordDialog pattern: cancel synchronously in the click handler, before queued completion.
        dlgF.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
            verifyGateCancel = true;
            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_stopping));
            dlgF.dismiss();
        });
        final long deadline = System.currentTimeMillis() + 12L * 60 * 1000;
        final long began = System.currentTimeMillis();
        new Thread(() -> {
            String bad = null;
            for (int i = 0; i < checkedUrls.size(); i++) {
                if (verifyGateCancel) break;
                final String u = checkedUrls.get(i);
                final int idx = i + 1, n = checkedUrls.size();
                try {
                    Hosting.verifyUrl(u, p, slow ? deadline : 0, (url, attempt, elapsedMs) -> {
                        if (verifyGateCancel) return false;
                        long el = (System.currentTimeMillis() - began) / 1000;
                        final CharSequence line = Language.join(
                                Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_progress, idx, n, attempt, el / 60, el % 60),
                                "\n\n", url, slow ? Language.join("\n\n", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_slow_verification)) : "");
                        main.post(() -> { if (!verifyGateCancel) Language.setText(msgV, line); });
                        return true;
                    });
                    LocalStore.logEvent(this, "MINT GATE ok " + idx + "/" + n + " — " + u);
                } catch (Hosting.HostingException e) { bad = e.getMessage(); break; }
                catch (Throwable t) { bad = Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_unreachable, u).toString(); break; }
            }
            final String err = bad;
            final long took = (System.currentTimeMillis() - began) / 1000;
            main.post(() -> {
                verifyGateRunning = false;
                if (dlgF != null) { try { dlgF.dismiss(); } catch (RuntimeException ignored) { } }
                if (isFinishing() || isDestroyed()) return;
                // Stop can land after the final HTTP response but before this main-thread callback.
                if (verifyGateCancel) {
                    toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_stopped));
                    return;
                }
                if (generation != screenGeneration || !draft.equals(mintDraftSnapshot(kind))) {
                    mintDraftChanged(); return;
                }
                if (err != null) {
                    LocalStore.logEvent(this, "MINT GATE FAILED after " + took + "s: " + err);
                    onFail.onFail(err);
                } else {
                    LocalStore.logEvent(this, "MINT GATE passed in " + took + "s");
                    toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_verified));
                    onOk.run();
                }
            });
        }).start();
    }

    /** At mint, the typed Base URL didn't serve. If we hold a recorded good upload that DOES
     *  serve, offer to switch to it rather than just failing. Full URLs shown (never truncated).
     *  NOTE: on Arweave this probe inherits verifyUrl's ~10-min retry budget, so a gate failure
     *  can chain a second slow check — rare double-failure path, accepted deliberately. */
    private void offerUploadedBaseRecovery(String typedBase, String typedExt, String badMsg) {
        if (createUploadedBase.isEmpty() || createUploadedBase.equals(typedBase)) { toast(badMsg); return; }
        final String uploadedBase = createUploadedBase, uploadedExt = createUploadedExt;
        final String recoveryDraft = mintDraftSnapshot(2);
        final long recoveryScreen = screenGeneration;
        final String probe = uploadedBase + "1" + uploadedExt;
        final Hosting.Profile p = HostingStore.getDefault(this);
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_checking_uploaded));
        new Thread(() -> {
            boolean ok;
            try { Hosting.verifyUrl(probe, p); ok = true; } catch (Throwable t) { ok = false; }
            final boolean good = ok;
            main.post(() -> {
                if (isFinishing() || isDestroyed() || recoveryScreen != screenGeneration
                        || !recoveryDraft.equals(mintDraftSnapshot(2))) return;
                if (!good) { toast(badMsg); return; }
                new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                        .setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_wrong_base))
                        .setMessage(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_recovery_body, typedBase + "1" + typedExt, probe))
                        .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_use_uploaded), (dg, wch) -> {
                            if (recoveryScreen != screenGeneration || !recoveryDraft.equals(mintDraftSnapshot(2))) { mintDraftChanged(); return; }
                            createBase = uploadedBase; createExt = uploadedExt;
                            renderCreateCollection();
                            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_base_restored));
                        })
                        .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_keep_typed), null)
                        .show();
            });
        }).start();
    }

    private void pickHostSingle(int context, String lane) {
        pendingPickContext = context;
        pendingHostLane = lane;
        pendingBatchImages = false;
        launchPicker(false);
    }

    private void pickHostPlates(int count) {
        pendingPickContext = PICK_HOST_PLATES;
        pendingHostLane = "collplates";
        pendingHostPlateCount = count;
        pendingBatchImages = true;
        launchPicker(true);
    }

    private int pendingHostPlateCount = 0;

    /** Runs the upload off the main thread, fills the target field on success,
     *  reports honestly on failure. Single-file lanes only. */
    private void hostUploadSingle(int context, String lane, Uri uri) {
        final ImagePick pick = activeImagePick;
        if (!imageCurrent(pick)) { if (pick != null) acceptImageResult(pick); return; }
        final Hosting.Profile p = pick.host;
        if (p == null) { acceptImageResult(pick); toast(Language.msg(l10n_atelier_image_no_host)); return; }
        toast(Language.msg(l10n_atelier_image_uploading, p.name()));
        executeImageWork(pick, () -> {
            String result = null, url = null;
            Hosting.Uploader u = null;
            try {
                byte[] bytes; String mime;
                if (context == PICK_HOST_ICON) {
                    android.graphics.Bitmap sq = ImageTools.squareBitmap(this, uri, 512);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    sq.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 85, bos);
                    bytes = bos.toByteArray();
                    mime = "image/webp";
                } else {
                    bytes = readUri(uri);
                    mime = getContentResolver().getType(uri);
                    if (mime == null) mime = "application/octet-stream";
                }
                if (bytes.length == 0) throw new Hosting.HostingException("could not open the image");
                String ext = extFromMime(mime);
                String coll = pick.slug;
                String rel = Hosting.fillTemplate(p.pathTemplate(), mapOf(
                        "collection", coll, "name", lane, "ext", ext,
                        "ts", Hosting.ts36(), "rand", Hosting.rand4(), "idx", ""));
                if (Hosting.TYPE_ARWEAVE.equals(p.type()) && bytes.length > ArweaveUploader.FREE_TIER_BYTES
                        && !confirmArweaveSpend(pick, bytes.length, 1)) {
                    main.post(() -> { if (acceptImageResult(pick)) toast(Language.msg(l10n_atelier_image_cancelled)); });
                    return;
                }
                if (!allowImageUpload(pick)) return;
                u = Hosting.forProfile(p);
                if (u.exists(rel)) throw new Hosting.HostingException("Remote file already exists: " + rel);
                url = u.putFile(bytes, rel, mime);
                if (url == null || url.isEmpty()) throw new Hosting.HostingException("Empty upload reference");
                // arweave: Turbo's 200 + txid is the receipt; the URL only starts
                // serving after gateway indexing (~5-10 min) — don't block on it.
                if (!Hosting.TYPE_ARWEAVE.equals(p.type())) Hosting.verifyUrl(url, p);
            } catch (SftpUploader.HostKeyUnverified hk) {
                main.post(() -> { if (acceptImageResult(pick)) confirmHostKey(p, hk.fingerprint); });
                return;
            } catch (Hosting.HostingException e) {
                result = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } catch (Throwable t) {
                result = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : " — " + t.getMessage());
            } finally {
                if (u instanceof AutoCloseable) { try { ((AutoCloseable) u).close(); } catch (Exception ignored) { } }
            }
            final String err = result, okUrl = url;
            main.post(() -> {
                if (err != null) {
                    LocalStore.logEvent(this, "HOSTING " + lane + " FAILED: " + err);
                    android.util.Log.e("AtelierHost", lane + " failed: " + err);
                    if (acceptImageResult(pick)) toast(Language.msg(l10n_atelier_image_failed, imageError(err)));
                    return;
                }
                LocalStore.logEvent(this, "HOSTING " + lane + " OK — " + okUrl);
                if (!acceptImageResult(pick)) return;
                fillHostLane(lane, okUrl, null);
                saveDrafts();
                toast(Hosting.TYPE_ARWEAVE.equals(p.type())
                        ? Language.msg(l10n_atelier_image_uploaded_slow) : Language.msg(l10n_atelier_image_uploaded));
            });
        });
    }

    /** N plates → one directory (IPFS) or sequence (sftp/webdav/github) named
     *  1..N + ext; fills base + ext. */
    private void hostUploadPlates(ArrayList<Uri> uris) {
        final ImagePick pick = activeImagePick;
        if (!imageCurrent(pick)) { if (pick != null) acceptImageResult(pick); return; }
        final Hosting.Profile p = pick.host;
        if (p == null) { acceptImageResult(pick); toast(Language.msg(l10n_atelier_image_no_host)); return; }
        if (uris.size() != pick.count) {
            acceptImageResult(pick);
            LocalStore.logEvent(this, "HOSTING plates: picked " + uris.size() + " but need " + pick.count);
            toast(Language.msg(l10n_atelier_image_exact_count, pick.count, uris.size()));
            return;
        }
        LocalStore.logEvent(this, "HOSTING plates start: type=" + p.type()
                + " root=" + p.cfgStr("remoteRoot") + " prefix=" + p.cfgStr("urlPrefix")
                + " dirTpl=" + p.dirTemplate());
        toast(Language.msg(l10n_atelier_image_uploading_plates, uris.size(), p.name()));
        executeImageWork(pick, () -> {
            String result = null, base = null, ext = null;
            Hosting.Uploader u = null;
            try {
                java.util.List<Hosting.Entry> files = new java.util.ArrayList<>();
                String coll = pick.slug;
                String dirRel = Hosting.fillTemplate(p.dirTemplate(), mapOf(
                        "collection", coll, "ts", Hosting.ts36(), "rand", Hosting.rand4()));
                // every plate → display-grade JPEG, so the set is homogeneous
                // (.jpg) by construction and bounded in size
                ext = ".jpg";
                for (int i = 0; i < uris.size(); i++) {
                    byte[] bytes = plateBytes(uris.get(i));
                    files.add(new Hosting.Entry(bytes, dirRel + "/" + (i + 1) + ext, "image/jpeg"));
                }
                if (Hosting.TYPE_ARWEAVE.equals(p.type())) {
                    long total = 0;
                    for (Hosting.Entry e : files) total += e.bytes.length;
                    if (total > ArweaveUploader.FREE_TIER_BYTES && !confirmArweaveSpend(pick, total, files.size())) {
                        main.post(() -> { if (acceptImageResult(pick)) toast(Language.msg(l10n_atelier_image_cancelled)); });
                        return;
                    }
                }
                if (!allowImageUpload(pick)) return;
                u = Hosting.forProfile(p);
                if (u instanceof Hosting.DirUploader) {
                    base = ((Hosting.DirUploader) u).putDirectory(files, null);
                } else {
                    base = Hosting.putSequence(u, p, dirRel, files, (done,total,path) -> { if (pick.cancelled) throw new IllegalStateException("Upload stopped after navigation"); });
                }
                if (base == null || base.isEmpty()) throw new Hosting.HostingException("Empty upload reference");
                // verify plate 1 and plate N — except arweave, where the Turbo
                // receipt is trusted and the pre-mint gate retry-verifies later
                // (fresh txids take ~5-10 min to serve)
                if (!Hosting.TYPE_ARWEAVE.equals(p.type())) {
                    Hosting.verifyUrl(base + "1" + ext, p);
                    Hosting.verifyUrl(base + uris.size() + ext, p);
                }
            } catch (SftpUploader.HostKeyUnverified hk) {
                main.post(() -> { if (acceptImageResult(pick)) confirmHostKey(p, hk.fingerprint); });
                return;
            } catch (Hosting.HostingException e) {
                result = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } catch (Throwable t) {
                result = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : " — " + t.getMessage());
            } finally {
                if (u instanceof AutoCloseable) { try { ((AutoCloseable) u).close(); } catch (Exception ignored) { } }
            }
            final String err = result, okBase = base, okExt = ext;
            main.post(() -> {
                if (err != null) {
                    LocalStore.logEvent(this, "HOSTING plates FAILED: " + err);
                    android.util.Log.e("AtelierHost", "plates failed: " + err);
                    if (acceptImageResult(pick)) toast(Language.msg(l10n_atelier_image_failed, imageError(err)));
                    return;
                }
                LocalStore.logEvent(this, "HOSTING plates OK — base " + okBase);
                if (!acceptImageResult(pick)) return;
                createBase = okBase;
                createExt = okExt;
                createUploadedBase = okBase;
                createUploadedExt = okExt;
                createMode = "url";
                toast(Hosting.TYPE_ARWEAVE.equals(p.type())
                        ? Language.msg(l10n_atelier_image_plates_slow) : Language.msg(l10n_atelier_image_plates_uploaded));
                renderCreateCollection();
                saveDrafts();
            });
        });
    }

    /** The existing paid-upload gate, tied to the selected draft and released on navigation. */
    private boolean confirmArweaveSpend(ImagePick pick, long totalBytes, int fileCount) {
        String estimate = ArweaveUploader.priceEstimate(totalBytes);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean approved = new java.util.concurrent.atomic.AtomicBoolean();
        final androidx.appcompat.app.AlertDialog[] dialog = {null};
        main.post(() -> {
            if (activeImagePick != pick || !imageCurrent(pick)) { latch.countDown(); return; }
            try {
                dialog[0] = new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                        .setTitle(Language.msg(l10n_atelier_image_cost_title))
                        .setMessage(Language.msg(l10n_atelier_image_cost, fileCount,
                                String.format(Locale.US, "%.1f MiB", totalBytes / 1048576.0), totalBytes,
                                estimate.isEmpty() ? Language.msg(l10n_atelier_image_fee_unknown) : estimate))
                        .setPositiveButton(Language.msg(l10n_atelier_image_upload), (d,w) -> {
                            approved.set(activeImagePick == pick && imageCurrent(pick)); latch.countDown();
                        })
                        .setNegativeButton(Language.msg(l10n_common_cancel), (d,w) -> latch.countDown())
                        .setOnCancelListener(d -> latch.countDown()).show();
                dialog[0].setOnDismissListener(d -> latch.countDown());
                pick.cancelConfirmation = () -> { approved.set(false); latch.countDown(); dialog[0].dismiss(); };
            } catch (RuntimeException unavailable) { latch.countDown(); }
        });
        try { latch.await(5, java.util.concurrent.TimeUnit.MINUTES); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        main.post(() -> { pick.cancelConfirmation = null; if (dialog[0] != null) dialog[0].dismiss(); });
        return approved.get() && !pick.cancelled;
    }

    private void fillHostLane(String lane, String url, String ext) {
        switch (lane) {
            case "collicon": createIcon = url; renderCreateCollection(); break;
            case "collext":  createExternal = url; renderCreateCollection(); break;
            case "nfturl":   nftUrl = url; renderCreateNft(); break;
            case "nftext":   nftExternal = url; renderCreateNft(); break;
            case "tokicon":  tokUrl = url; renderCreateToken(); break;
            default: break;
        }
    }

    private String slugForHost() {
        String n = createName != null && !createName.isEmpty() ? createName
                : (nftName != null && !nftName.isEmpty() ? nftName
                : (tokName != null && !tokName.isEmpty() ? tokName : "atelier"));
        return Hosting.slug(n);
    }

    private static final long HOST_HARD_CAP = 60L * 1024 * 1024;

    /** Raw bytes for the full-res single lane (external URL). Generous cap so a
     *  phone photo never trips it; null-safe. */
    private byte[] readUri(Uri uri) throws Exception {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            if (in == null) throw new Exception("could not open the image");
            byte[] buf = new byte[8192];
            int n; long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > HOST_HARD_CAP) throw new Exception("image is over 60MB");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /** A collection plate for hosting: display-grade JPEG, long edge ≤ 2048.
     *  "Processed and sent" per the brief — the on-chain record only points at
     *  the URL, so multi-MB sensor dumps buy nothing and make 20-plate uploads
     *  crawl. Refuses undecodable files rather than labelling raw bytes as JPEG. */
    private byte[] plateBytes(Uri uri) throws Exception {
        android.graphics.Bitmap bmp = ImageTools.boundedBitmap(this, uri, 2048);
        if (bmp != null) {
            java.io.ByteArrayOutputStream jbos = new java.io.ByteArrayOutputStream();
            if (bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, jbos) && jbos.size() > 0) {
                return jbos.toByteArray();
            }
        }
        throw new Exception("Could not encode the image as JPEG");
    }

    private static String extFromMime(String mime) {
        if (mime == null) return ".png";
        switch (mime) {
            case "image/jpeg": return ".jpg";
            case "image/png":  return ".png";
            case "image/webp": return ".webp";
            case "image/gif":  return ".gif";
            case "image/svg+xml": return ".svg";
            default: return ".png";
        }
    }

    private int firstMissingLocalImageIndex(StateNft.Meta m) {
        if (m == null) return 1;
        JSONObject row = LocalStore.findById(this, m.localId);
        if (row == null) return 1;
        for (int i = 1; i <= m.size; i++) if (localImageInRow(row, i).isEmpty()) return i;
        return 1;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FiltrHandoff.REQUEST) { receiveFiltr(resultCode,data); return; }
        if (requestCode != 7001 || (pendingImagePick != null && pendingImagePick.filtrId != null)) return;
        final ImagePick pick = pendingImagePick;
        pendingImagePick = null;
        if (resultCode != RESULT_OK || data == null) return;
        if (pick == null) { toast(Language.msg(l10n_atelier_image_reselect)); return; }
        if (!imageCurrent(pick)) { toast(Language.msg(l10n_atelier_image_changed)); return; }
        ArrayList<Uri> uris = selectedImageUris(data);
        if (uris.isEmpty()) return;
        final int start = pick.start, context = pick.context;
        final boolean batch = pick.batch;
        final long recoveryId = pick.recovery;
        activeImagePick = pick;
        if (context == PICK_ARTPHOTO) {
            final Uri photoUri = uris.get(0);
            toast(Language.msg(l10n_atelier_art_cartoonizing));
            executeImageWork(pick, () -> {
                android.graphics.Bitmap big = ImageTools.squareBitmap(this, photoUri, 512);
                artPhotoFromBitmap(big, pick);
            });
            return;
        }
        if (context == PICK_HOST_ICON || context == PICK_HOST_EXTERNAL) {
            hostUploadSingle(context, pick.lane, uris.get(0));
            return;
        }
        if (context == PICK_HOST_PLATES) {
            hostUploadPlates(uris);
            return;
        }
        int budget = context == PICK_NFT ? ImageTools.ARTIMAGE_BUDGET
                : (context == PICK_TOKICON || context == PICK_COLLICON) ? ImageTools.ICON_BUDGET
                : ImageTools.STATE_IMG_BUDGET;
        executeImageWork(pick, () -> {
            ArrayList<String> out = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    // SVG lane: sanitize + seal as text, skip the raster pipeline
                    if (ImageTools.isSvgUri(this, uri)) out.add(ImageTools.svgBase64FromUri(this, uri, budget));
                    // wallet icons are square-cropped so wallet tiles fill edge-to-edge
                    else if (context == PICK_TOKICON || context == PICK_COLLICON) out.add(ImageTools.iconFromUri(this, uri, budget));
                    else out.add(ImageTools.compressUri(this, uri, budget));
                } catch (Exception ignored) { out.add(""); }
            }
            runOnUiThread(() -> {
                if (!acceptImageResult(pick)) return;
                if (context == PICK_NFT) {
                    String b64 = out.isEmpty() ? "" : out.get(0);
                    if (b64 == null || b64.isEmpty()) { toast(Language.msg(l10n_atelier_image_process_failed)); renderCreateNft(); return; }
                    nftImage = b64;
                    renderCreateNft();
                    // straight into the plate room — a bad crop is sealed forever
                    // (SVG is resolution-independent; crop/tone don't apply)
                    if (!"image/svg+xml".equals(ImageTools.mimeOf(b64))) {
                        showEditor(b64, ImageTools.ARTIMAGE_BUDGET, edited -> {
                            nftImage = edited;
                            renderCreateNft();
                        });
                    }
                } else if (context == PICK_TOKICON || context == PICK_COLLICON) {
                    String b64 = out.isEmpty() ? "" : out.get(0);
                    if (b64 == null || b64.isEmpty()) toast(Language.msg(l10n_atelier_image_icon_failed));
                    else if (context == PICK_TOKICON) tokIcon = b64;
                    else createIconB64 = b64;
                    if (context == PICK_TOKICON) renderCreateToken();
                    else renderCreateCollection();
                } else if (context == PICK_RECOVERY) {
                    int added = applyRecoveryImages(recoveryId, start, batch, out);
                    toast(added == 0 ? Language.msg(l10n_atelier_image_process_failed)
                            : (batch ? Language.msg(l10n_atelier_image_restored, added) : Language.msg(l10n_atelier_image_restored, 1)));
                    loadLocalCollections();
                    openMeta = findCollectionByLocalId(recoveryId, openMeta);
                    if (openMeta != null && !"NEEDIMAGES".equals(openMeta.phase)) {
                        engageEngine();
                    }
                    if (openMeta != null) openCollection(openMeta);
                } else {
                    int added = batch ? applyPickedImages(start, out) : applyReplacementImage(start, out);
                    toast(added == 0 ? Language.msg(l10n_atelier_image_process_failed)
                            : (batch ? Language.msg(l10n_atelier_image_loaded, added) : Language.msg(l10n_atelier_image_plate_loaded, start + 1)));
                    renderCreateCollection();
                }
                saveDrafts();
            });
        });
    }


    private ArrayList<Uri> selectedImageUris(Intent data) {
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    private int firstEmptyImageSlot() {
        for (int i = 0; i < createImages.length; i++) {
            if (createImages[i] == null || createImages[i].isEmpty()) return i;
        }
        return 0;
    }

    private int applyPickedImages(int start, ArrayList<String> images) {
        if (start < 0 || start >= createImages.length) start = firstEmptyImageSlot();
        int slot = start;
        int added = 0;
        for (String b64 : images) {
            if (b64 == null || b64.isEmpty()) continue;
            while (slot < createImages.length && createImages[slot] != null && !createImages[slot].isEmpty()) slot++;
            if (slot >= createImages.length) break;
            createImages[slot++] = b64;
            added++;
        }
        return added;
    }

    private int applyReplacementImage(int slot, ArrayList<String> images) {
        if (slot < 0 || slot >= createImages.length || images.isEmpty()) return 0;
        String b64 = images.get(0);
        if (b64 == null || b64.isEmpty()) return 0;
        createImages[slot] = b64;
        return 1;
    }

    /* ================= widgets & misc ================= */

    private static class HorizontalScroll extends android.widget.HorizontalScrollView {
        HorizontalScroll(Context c) { super(c); setHorizontalScrollBarEnabled(false); }
    }

    private interface StrConsumer { void accept(String s); }

    private android.text.TextWatcher watch(StrConsumer c) {
        return new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable s) { c.accept(s.toString()); }
        };
    }

    /** Icon upload affordance: thumb preview + pick/clear controls. */
    private LinearLayout iconUploadRow(String iconB64, CharSequence caption,
                                       View.OnClickListener pick, View.OnClickListener clear) {
        LinearLayout row = horizontal(Gravity.CENTER_VERTICAL);
        boolean has = iconB64 != null && !iconB64.isEmpty();
        if (has) {
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1.5f));
            ImageLoader.loadOver(this, ImageTools.dataUri(iconB64), thumb);
            row.addView(thumb, new LinearLayout.LayoutParams(dp(40), dp(40)){{ rightMargin = dp(8); }});
        }
        TextView btn = Design.button(this, has ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_replace_icon) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_create_upload_icon), false);
        btn.setOnClickListener(pick);
        btn.setMinHeight(dp(48));
        row.addView(btn, new LinearLayout.LayoutParams(0, -2, 1));
        if (has) {
            TextView del = Design.text(this, "×", 18, Design.ACCENT(), Design.sansBold());
            del.setPadding(dp(12), 0, dp(6), 0);
            del.setClickable(true);
            del.setOnClickListener(clear);
            row.addView(del);
        }
        LinearLayout box = vertical();
        box.addView(row);
        box.addView(Design.note(this, caption), lpm(0, 3, 0, 0));
        return box;
    }

    private EditText fieldInto(LinearLayout parent, CharSequence label, CharSequence hint, String value) {
        parent.addView(Design.kicker(this, label), lpm(0, 8, 0, 5));
        EditText e = input(hint);
        if (value != null && !value.isEmpty()) e.setText(value);
        parent.addView(e, lph(48, 0, 0, 0, 4));
        return e;
    }

    private EditText input(CharSequence hint) {
        EditText e = new EditText(this);
        Language.hint(e, hint);
        e.setTextColor(Design.INK());
        e.setHintTextColor(Design.DIM());
        e.setTextSize(13.5f);
        e.setTypeface(Design.sans());
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1.5f));
        return e;
    }

    private LinearLayout lotCard() {
        LinearLayout card = vertical();
        card.setBackground(Design.blockShadow(this, Design.CARD()));
        card.setPadding(dp(14), dp(12), dp(16), dp(15));
        return card;
    }

    private LinearLayout kvRow(CharSequence k, CharSequence v) {
        LinearLayout row = horizontal(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView key = Design.text(this, Language.upper(k), 9.5f, Design.DIM(), Design.sansBold());
        key.setLetterSpacing(0.14f);
        row.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = Design.text(this, v == null ? "—" : v, 12, Design.INK(), Design.mono());
        val.setGravity(Gravity.END);
        row.addView(val, new LinearLayout.LayoutParams(0, -2, 1.3f));
        return row;
    }

    private float collectionProgress(StateNft.Meta m) {
        if (m == null) return 0f;
        if (m.size > 0 && m.minted > 0) return Math.min(1f, m.minted / (float) m.size);
        String p = m.phase == null ? "" : m.phase;
        if ("CREATE".equals(p)) return 0.1f;
        if ("MOVE".equals(p)) return 0.28f;
        if ("SPLIT".equals(p)) return 0.48f;
        if ("STAMP".equals(p)) return 0.68f;
        if ("DONE".equals(p)) return 1f;
        return 0f;
    }

    private boolean activeMint(StateNft.Meta m) {
        String p = m == null || m.phase == null ? "" : m.phase;
        return "CREATE".equals(p) || "MOVE".equals(p) || "SPLIT".equals(p) || "STAMP".equals(p) || "NEEDIMAGES".equals(p);
    }

    private static boolean validCmdUrl(String u) {
        return Util.validCmdUrl(u);
    }

    private void copyText(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("atelier", s));
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_history_copied));
    }

    private void openMinimaCore() {
        cancelHostingCheck();
        // Same explicit destination and task behavior as Pandamonium's drawer.
        Intent intent=new Intent();
        intent.setClassName(getPackageName(),"com.eurobuddha.minimacore.main.MainActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    private void toast(CharSequence s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    /* layout shorthand */
    private int dp(int v) { return Design.dp(this, v); }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal(int gravity) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(gravity);
        return l;
    }

    private View vspace(int h) {
        Space s = new Space(this);
        s.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return s;
    }

    private LinearLayout.LayoutParams lpm(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout.LayoutParams lph(int hDp, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(hDp));
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout.LayoutParams weight(int hDp, int lm, int rm) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(hDp), 1);
        p.leftMargin = dp(lm);
        p.rightMargin = dp(rm);
        return p;
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignored) {}
    }
}
