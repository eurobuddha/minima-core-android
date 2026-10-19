package com.eurobuddha.mail;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import androidx.appcompat.widget.PopupMenu;
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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.goterl.lazysodium.LazySodium;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.mail.integratedcomms.Avatars;
import com.eurobuddha.mail.integratedcomms.BackupCrypto;
import com.eurobuddha.mail.integratedcomms.CommsDb;
import com.eurobuddha.mail.integratedcomms.CommsIdentity;
import com.eurobuddha.mail.integratedcomms.CommsScanner;
import com.eurobuddha.mail.integratedcomms.CommsTransport;
import com.eurobuddha.mail.integratedcomms.CryptoProvider;
import com.eurobuddha.mail.integratedcomms.Hex;
import com.eurobuddha.mail.integratedcomms.Images;
import com.eurobuddha.mail.integratedcomms.LocalEcCryptoProvider;
import com.eurobuddha.mail.integratedcomms.MailMessage;
import com.eurobuddha.mail.integratedcomms.MailText;
import com.eurobuddha.mail.integratedcomms.NodeApi;
import com.eurobuddha.mail.integratedcomms.QrUtil;
import com.eurobuddha.mail.integratedcomms.Sodium;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Minima Mail — on-chain email. Drawer + folders + subjects; bubbles inside a conversation. */
public class MainActivity extends AppCompatActivity {

    private final java.util.Set<AlertDialog> languageDialogs = new java.util.HashSet<>();
    private final java.util.Map<PopupMenu, java.util.Map<android.view.MenuItem, CharSequence>> languageMenus = new java.util.LinkedHashMap<>();
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
    private void showLanguageMenu(PopupMenu menu) {
        java.util.Map<android.view.MenuItem, CharSequence> labels = new java.util.LinkedHashMap<>();
        for (int i=0;i<menu.getMenu().size();i++) {
            android.view.MenuItem item = menu.getMenu().getItem(i);
            labels.put(item,item.getTitle());
        }
        languageMenus.put(menu,labels);
        menu.setOnDismissListener(m -> languageMenus.remove(m));
        menu.show();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        updateFreshness();
        ensureChannel();
        for (PopupMenu menu : new java.util.ArrayList<>(languageMenus.keySet())) {
            java.util.Map<android.view.MenuItem, CharSequence> labels = languageMenus.get(menu);
            menu.setOnDismissListener(null);
            menu.dismiss();
            for (java.util.Map.Entry<android.view.MenuItem, CharSequence> entry : labels.entrySet())
                entry.getKey().setTitle(entry.getValue().toString());
            menu.setOnDismissListener(m -> languageMenus.remove(m));
            menu.show();
        }
    }

    private static final String CH = "mail";

    private enum Folder { INBOX, OUTBOX, SENT, ARCHIVE, CONTACTS, IDENTITY, SETTINGS }

    private LazySodium ls;
    private NodeApi node;
    private CommsDb db;
    private CryptoProvider crypto;
    private CommsIdentity identity;
    private IdentityStore identityStore;
    private boolean identityBusy;
    private AlertDialog identityPrompt;
    private CommsScanner scanner;
    private String myId, myName = "", myPayaddr = "";
    private boolean paired = false;
    private boolean modalOpen = false;   // suppress background screen rebuilds while a dialog is open
    private int chainBlock = 0;
    private final java.util.HashMap<String, Runnable> pendingPay = new java.util.HashMap<>();
    private final java.util.HashSet<String> payaddrAsked = new java.util.HashSet<>();
    private java.util.Map<String, String> contactNames;   // cached publickey→name (avoids a DB query per list row)
    private final android.util.LruCache<String, Bitmap> imgCache = new android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private EditText openPayAddrField;   // the address field of an open Send-funds sheet (for live fill)
    private String openPayContact;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private DirectNodeEvents.Listener notifyReceiver;

    private DrawerLayout drawer;
    private LinearLayout navPanel, contentCol, pairingBanner;
    private FrameLayout container;
    private Folder folder = Folder.INBOX;
    private final ArrayDeque<View> stack = new ArrayDeque<>();
    private int insetTop = 0, insetBottom = 0;
    private RecyclerView currentMessages;   // for scroll-to-bottom on keyboard
    private MessagesAdapter convAdapter;    // the open conversation's adapter, for in-place message updates
    private String convHashref;             // the open conversation's thread key
    private TextView freshText;             // the inbox freshness line ("Checked block N · Xm ago")
    private SwipeRefreshLayout inboxSwipe;

    private ActivityResultLauncher<String> exportLauncher;
    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<ScanOptions> scanLauncher;
    private ActivityResultLauncher<String> imagePicker;
    private String imagePickContact, imagePickSubject = "";
    private EditText pendingScanTarget;
    private boolean pendingScanIsAddress;   // true → scan to fill a Minima address field (not a Mail key)

    // ---- lifecycle ----

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ls = Sodium.get();
        db = new CommsDb(this);
        Design.load(db.getMeta("theme", "light"));
        // Reuse PandaPools: native dialogs follow the selected app palette, not the system mode.
        getDelegate().setLocalNightMode(!Design.LIGHT
                ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        myName = db.getMeta("myname", "");
        myPayaddr = db.getMeta("mypayaddr", "");

        getWindow().setBackgroundDrawable(new ColorDrawable(Design.BG));

        drawer = new DrawerLayout(this);
        contentCol = new LinearLayout(this);
        contentCol.setOrientation(LinearLayout.VERTICAL);
        contentCol.setBackgroundColor(Design.BG);
        pairingBanner = buildPairingBanner();
        pairingBanner.setVisibility(View.GONE);
        container = new FrameLayout(this);
        contentCol.addView(pairingBanner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        contentCol.addView(container, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        drawer.addView(contentCol, new DrawerLayout.LayoutParams(DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT));

        navPanel = new LinearLayout(this);
        navPanel.setOrientation(LinearLayout.VERTICAL);
        navPanel.setBackgroundColor(Design.SURFACE);
        DrawerLayout.LayoutParams nlp = new DrawerLayout.LayoutParams(dp(288), DrawerLayout.LayoutParams.MATCH_PARENT, Gravity.START);
        drawer.addView(navPanel, nlp);
        drawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerStateChanged(int newState) {
                if (newState == DrawerLayout.STATE_DRAGGING || newState == DrawerLayout.STATE_SETTLING) rebuildNav();
            }
        });
        rebuildNav();

        setContentView(drawer);
        applyInsets();
        ensureChannel();
        requestNotifPermission();

        exportLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                uri -> { if (uri != null) promptBackupPass(uri, true); });
        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) promptBackupPass(uri, false); });
        scanLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() != null && pendingScanTarget != null) {
                String s = result.getContents().trim();
                if (pendingScanIsAddress) {
                    int bar = s.indexOf('|');                       // a "key|addr" QR → take the address half
                    Language.setText(pendingScanTarget, bar >= 0 ? s.substring(bar + 1).trim() : s);
                } else {
                    Language.setText(pendingScanTarget, acceptKeyShare(s));   // a Mail-key QR → take the key (store the addr)
                }
            }
        });
        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null && imagePickContact != null) sendImage(imagePickContact, imagePickSubject, uri);
        });

        showFolder(Folder.INBOX);

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

        // Freshness display starts with the visible Activity.
    }

    private boolean freshnessVisible;
    private final Runnable freshTick = new Runnable() {
        @Override public void run() {
            if (!freshnessVisible) return;
            updateFreshness();
            ui.postDelayed(this, 30000);
        }
    };

    @Override protected void onDestroy() {
        freshnessVisible = false;
        for (AlertDialog value : new java.util.ArrayList<>(languageDialogs)) value.dismiss();
        languageDialogs.clear();
        for (PopupMenu value : new java.util.ArrayList<>(languageMenus.keySet())) value.dismiss();
        languageMenus.clear();
        super.onDestroy();
        if (identityPrompt != null) identityPrompt.dismiss();
        ui.removeCallbacks(freshTick);
        if (notifyReceiver != null) try { DirectNodeEvents.unsubscribe(notifyReceiver); } catch (Exception ignored) {}
        if (node != null) node.onDestroy();
        io.shutdownNow();
    }

    @Override public void onBackPressed() {
        if (drawer.isDrawerOpen(androidx.core.view.GravityCompat.START)) { drawer.closeDrawer(androidx.core.view.GravityCompat.START); return; }
        if (stack.size() > 1) pop();
        else if (folder != Folder.INBOX) showFolder(Folder.INBOX);
        else super.onBackPressed();
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(drawer, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            insetTop = bars.top;
            insetBottom = Math.max(bars.bottom, ime.bottom);
            contentCol.setPadding(0, insetTop, 0, insetBottom);
            navPanel.setPadding(0, insetTop, 0, bars.bottom);
            if (ime.bottom > 0 && currentMessages != null && currentMessages.getAdapter() != null) {
                currentMessages.scrollToPosition(Math.max(0, currentMessages.getAdapter().getItemCount() - 1));
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(drawer);
        new WindowInsetsControllerCompat(getWindow(), drawer).setAppearanceLightStatusBars(Design.LIGHT);
    }

    // ---- navigation ----

    private void push(View v) { stack.push(v); showTop(); }

    private void pop() {
        if (stack.size() > 1) {
            stack.pop();
            if (stack.size() == 1) refreshTop(buildFolder(folder));   // root re-reads the DB (unread dots etc.)
            else showTop();
        }
    }

    private void showFolder(Folder f) {
        folder = f;
        stack.clear();
        stack.push(buildFolder(f));
        showTop();
    }

    private View buildFolder(Folder f) {
        switch (f) {
            case OUTBOX:   return buildStatusList(Language.msg(l10n_mail_outbox), true);
            case SENT:     return buildStatusList(Language.msg(l10n_mail_sent), false);
            case ARCHIVE:  return buildThreadList(true);
            case CONTACTS: return buildContacts();
            case IDENTITY: return buildIdentity();
            case SETTINGS: return buildSettings();
            default:       return buildThreadList(false);
        }
    }

    private void showTop() {
        currentMessages = null;
        container.removeAllViews();
        container.addView(stack.peek());
    }
    private void refreshTop(View rebuilt) { stack.pop(); stack.push(rebuilt); showTop(); }

    /** Rebuild the visible root folder (called after data changes, never over a pushed screen/dialog). */
    private void refreshFolderIfVisible() {
        if (!modalOpen && stack.size() == 1) refreshTop(buildFolder(folder));
    }

    // ---- drawer ----

    private void rebuildNav() {
        navPanel.removeAllViews();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(18), dp(20), dp(18), dp(14));
        TextView brand = new TextView(this);
        android.text.SpannableString bs = new android.text.SpannableString("MINIMA MAIL");
        bs.setSpan(new android.text.style.ForegroundColorSpan(Design.ACCENT), 7, 11, 0);
        Language.setText(brand, bs);
        brand.setTextColor(Design.TEXT); brand.setTextSize(16f); brand.setTypeface(Typeface.DEFAULT_BOLD);
        TextView me = new TextView(this);
        Language.setText(me, myId == null ? Language.msg(l10n_mail_connecting) : Language.msg(l10n_mail_you_key, shortKey(myId)));
        me.setTextColor(Design.DIM); me.setTextSize(11f); me.setTypeface(Typeface.MONOSPACE);
        me.setPadding(0, dp(3), 0, 0);
        // NB: no click handler on the header — it sits under the hamburger's screen position, so a
        // double-tap on the hamburger would silently navigate. Language.msg(l10n_mail_your_key) below covers the shortcut.
        head.addView(brand); head.addView(me);
        navPanel.addView(head);
        navPanel.addView(hairline());

        int unread = db.unreadCount();
        int pending = db.outboxCount();
        navPanel.addView(navItem(R.drawable.pm_mail_ic_inbox, Language.msg(l10n_mail_inbox), unread, Folder.INBOX));
        navPanel.addView(navItem(R.drawable.pm_mail_ic_outbox, Language.msg(l10n_mail_outbox), pending, Folder.OUTBOX));
        navPanel.addView(navItem(R.drawable.pm_mail_ic_send, Language.msg(l10n_mail_sent), 0, Folder.SENT));
        navPanel.addView(navItem(R.drawable.pm_mail_ic_archive, Language.msg(l10n_mail_archive), 0, Folder.ARCHIVE));
        View sep = hairline();
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        slp.setMargins(dp(18), dp(8), dp(18), dp(8));
        navPanel.addView(sep, slp);
        navPanel.addView(navItem(R.drawable.pm_mail_ic_contacts, Language.msg(l10n_mail_contacts), 0, Folder.CONTACTS));
        navPanel.addView(navItem(R.drawable.pm_mail_ic_key, Language.msg(l10n_mail_your_key), 0, Folder.IDENTITY));
        navPanel.addView(navItem(R.drawable.pm_mail_ic_settings, Language.msg(l10n_mail_settings), 0, Folder.SETTINGS));

        View spacer = new View(this);
        navPanel.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView ver = new TextView(this);
        Language.setText(ver, "Minima Mail v" + BuildConfig.VERSION_NAME);
        ver.setTextColor(Design.DIM2); ver.setTextSize(10.5f);
        ver.setPadding(dp(18), dp(6), dp(18), dp(10));
        navPanel.addView(ver);
    }

    private View navItem(int iconRes, CharSequence label, int badgeCount, final Folder target) {
        boolean sel = folder == target && stack.size() == 1;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(11), dp(14), dp(11));
        int tint = sel ? Design.ACCENT : Design.DIM;
        row.addView(icon(iconRes, tint, 20));
        TextView t = new TextView(this);
        Language.setText(t, label);
        t.setTextColor(sel ? Design.ACCENT_TEXT : Design.TEXT);
        t.setTextSize(14.5f);
        if (sel) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(14), 0, 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);
        if (badgeCount > 0) {
            TextView b = new TextView(this);
            Language.setText(b, String.valueOf(badgeCount));
            b.setTextColor(Design.ON_ACCENT); b.setTextSize(10.5f); b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setBackground(Design.roundBg(this, Design.ACCENT, 10));
            b.setPadding(dp(7), dp(1), dp(7), dp(1));
            row.addView(b);
        }
        if (sel) {
            GradientDrawable d = new GradientDrawable();
            d.setColor((Design.ACCENT & 0x00FFFFFF) | 0x22000000);
            float r = dp(22);
            d.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
            row.setBackground(d);
            ViewGroup.MarginLayoutParams mlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ((ViewGroup.MarginLayoutParams) mlp).rightMargin = dp(12);
            row.setLayoutParams(mlp);
        }
        row.setOnClickListener(v -> { drawer.closeDrawer(androidx.core.view.GravityCompat.START); showFolder(target); });
        return row;
    }

    private View hairline() {
        View v = new View(this);
        v.setBackgroundColor(Design.HAIRLINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return v;
    }

    // ---- pairing + identity ----

    private void onPaired(boolean enabled) {
        paired = enabled;
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) {
            if (chainBlock == 0) fetchBlock();
            fetchMyPayaddr();
            if (crypto == null) setupIdentity(); else requestScan();
        }
    }

    /** Cache my Minima receiving address (piggybacked on outgoing messages so contacts can pay me). */
    private void fetchMyPayaddr() {
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) {
                    String a = r.optString("miniaddress", r.optString("address", ""));   // prefer Mx
                    if (!a.isEmpty()) { myPayaddr = a; db.setMeta("mypayaddr", a); }
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
                updateFreshness();
                if (chainBlock > 0) io.execute(() -> { db.markConfirmed(chainBlock); ui.post(MainActivity.this::refreshFolderIfVisible); });
            }
            @Override public void onError(String m) {}
        });
    }

    private void setupIdentity() {
        if (identityBusy || identity != null || isFinishing() || isDestroyed()) return;
        identityBusy = true;
        io.execute(() -> {
            try {
                if (identityStore == null) identityStore = new IdentityStore(this);
                CommsIdentity saved = identityStore.load();
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (saved != null) {
                        identityBusy = false;
                        adoptIdentity(saved); refreshFolderIfVisible(); requestScan();
                    } else requestNodeIdentity();
                });
            } catch (Exception unreadable) {
                ui.post(() -> {
                    identityBusy = false;
                    if (!isFinishing() && !isDestroyed())
                        toast(Language.msg(l10n_mail_identity_open_failed));
                });
            }
        });
    }

    private void requestNodeIdentity() {
        node.cmd("vault action:seed", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (isFinishing() || isDestroyed()) return;
                JSONObject r = j.optJSONObject("response");
                String ikm = r == null || !j.optBoolean("status", false) || r.optBoolean("locked", false)
                        ? "" : r.optString("seed", r.optString("phrase", ""));
                if (ikm.isEmpty()) askForSeed(); else deriveIdentity(ikm);
            }
            @Override public void onError(String m) {
                if (isFinishing() || isDestroyed()) return;
                if (NodeApi.ERR_NOT_ENABLED.equals(m)) { identityBusy = false; pairingBanner.setVisibility(View.VISIBLE); return; }
                askForSeed();
            }
        });
    }

    private void deriveIdentity(final String ikm) {
        io.execute(() -> {
            try {
                byte[] seed = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(StandardCharsets.UTF_8);
                CommsIdentity id = CommsIdentity.fromSeed(ls, seed);
                java.util.Arrays.fill(seed, (byte)0);
                if (identityStore == null) identityStore = new IdentityStore(this);
                identityStore.save(id);
                ui.post(() -> { identityBusy = false; if (isFinishing() || isDestroyed()) return; adoptIdentity(id); refreshFolderIfVisible(); requestScan(); });
            } catch (Exception e) { ui.post(() -> { identityBusy = false; if (!isFinishing() && !isDestroyed()) toast(Language.msg(l10n_mail_identity_save_failed)); }); }
        });
    }

    private void adoptIdentity(CommsIdentity id) {
        identity = id;
        crypto = new LocalEcCryptoProvider(ls, id);
        myId = id.publicId();
        scanner = new CommsScanner(node, crypto, db, myId, new CommsScanner.Listener() {
            @Override public void onDone(boolean ok, int newCount) { onScanDone(ok, newCount); }
            @Override public void onContactPayaddrUpdated(String publicId) {
                ui.post(() -> {
                    if (openPayAddrField != null && publicId.equals(openPayContact)) {
                        String a = db.contactPayaddr(publicId);
                        if (a != null && openPayAddrField.getText().toString().trim().isEmpty()) Language.setText(openPayAddrField, a);
                    }
                    Runnable r = pendingPay.remove(publicId); if (r != null) r.run();
                });
            }
        });
        fetchMyPayaddr();   // cache my receiving address NOW, so every message I send carries it
    }

    /** Fire a one-time address request to a contact we don't have an address for yet (so it's ready by pay time). */
    private void proactivePayaddr(String otherKey) {
        if (crypto == null || otherKey == null) return;
        if (db.contactPayaddr(otherKey) != null) return;
        if (!payaddrAsked.add(otherKey)) return;   // at most once per contact per session (avoid coin spam)
        sendPayaddrReq(otherKey);
    }

    /** What I share as my Language.msg(l10n_mail_mail_key): encryption key + my Mx receiving address, so contacts can pay me. */
    private String keyShare() {
        return (myPayaddr == null || myPayaddr.isEmpty()) ? myId : myId + "|" + myPayaddr;
    }

    /** Accept a shared key that may be "mailkey|payaddr": store the address if present, return the mail key. */
    private String acceptKeyShare(String shared) {
        if (shared == null) return "";
        shared = shared.trim();
        int bar = shared.indexOf('|');
        if (bar > 0) {
            String key = shared.substring(0, bar).trim();
            String addr = shared.substring(bar + 1).trim();
            if (CommsIdentity.isValidPublicId(key) && looksLikeMinimaAddress(addr)) db.setContactPayaddr(key, addr);
            return key;
        }
        return shared;
    }

    private void askForSeed() {
        if (isFinishing() || isDestroyed() || (identityPrompt != null && identityPrompt.isShowing())) return;
        final EditText in = input(Language.msg(l10n_mail_seed_hint));
        in.setMinLines(3);
        identityPrompt = languageDialog()
                .setTitle(Language.msg(l10n_mail_create_identity))
                .setMessage(Language.msg(l10n_mail_identity_help))
                .setView(in)
                .setPositiveButton(Language.msg(l10n_mail_create), (d, w) -> { String s = in.getText().toString().trim(); if (!s.isEmpty()) { identityBusy = true; deriveIdentity(s); } else identityBusy = false; })
                .setNegativeButton(Language.msg(l10n_mail_restore_instead), (d, w) -> { identityBusy = false; importLauncher.launch(new String[]{"application/json", "*/*"}); })
                .create();
        identityPrompt.setOnCancelListener(d -> identityBusy = false);
        identityPrompt.show();
        identityPrompt.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }

    // ---- scanning ----

    private void requestScan() { if (crypto != null && scanner != null) scanner.scan(chainBlock); }

    /** Reload the open conversation's messages WITHOUT rebuilding the screen (so an open dialog is untouched). */
    private void reloadConversation(final String hashref) {
        if (convAdapter == null || convHashref == null || !convHashref.equals(hashref)) return;
        final MessagesAdapter a = convAdapter;
        final RecyclerView rv = currentMessages;
        io.execute(() -> {
            final List<MailMessage> msgs = db.thread(hashref);
            ui.post(() -> {
                if (convAdapter != a) return;   // user navigated away in the meantime
                a.setData(msgs);
                if (rv != null && !msgs.isEmpty()) rv.scrollToPosition(msgs.size() - 1);
            });
        });
    }

    private void onScanDone(boolean ok, int newCount) {
        ui.post(() -> {
            if (inboxSwipe != null) inboxSwipe.setRefreshing(false);
            updateFreshness();
            if (newCount > 0) {
                notifyNew(newCount);
                if (convHashref != null && stack.size() > 1) reloadConversation(convHashref);   // in-place, dialog-safe
                else refreshFolderIfVisible();
            }
        });
    }

    // ---- freshness ("checked block N · Xm ago") ----

    private View buildFreshness() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Design.SURFACE);
        bar.setPadding(dp(14), dp(3), dp(14), dp(7));
        View dot = new View(this);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL);
        g.setColor(paired ? Design.GREEN : Design.DIM2);
        dot.setBackground(g);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dlp.rightMargin = dp(6);
        bar.addView(dot, dlp);
        freshText = new TextView(this);
        freshText.setTextColor(Design.DIM); freshText.setTextSize(10.5f);
        freshText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(freshText);
        LinearLayout check = new LinearLayout(this);
        check.setOrientation(LinearLayout.HORIZONTAL);
        check.setGravity(Gravity.CENTER_VERTICAL);
        check.addView(icon(R.drawable.pm_mail_ic_refresh, Design.ACCENT, 11));
        TextView ct = new TextView(this);
        Language.setText(ct, Language.msg(l10n_mail_check_now)); ct.setTextColor(Design.ACCENT_TEXT); ct.setTextSize(10.5f); ct.setTypeface(Typeface.DEFAULT_BOLD);
        ct.setPadding(dp(4), 0, 0, 0);
        check.addView(ct);
        check.setPadding(dp(8), dp(2), 0, dp(2));
        check.setOnClickListener(v -> { fetchBlock(); requestScan(); toast(Language.msg(l10n_mail_checking_chain)); });
        bar.addView(check);
        updateFreshness();
        return bar;
    }

    private void updateFreshness() {
        if (freshText == null) return;
        if (crypto == null) { Language.setText(freshText, paired ? Language.msg(l10n_mail_connecting) : Language.msg(l10n_mail_waiting)); return; }
        long last = scanner == null ? 0 : scanner.lastScanEnd();
        CharSequence blk = chainBlock > 0 ? Language.msg(l10n_mail_block, chainBlock) : Language.msg(l10n_mail_chain);
        if (last <= 0) { Language.setText(freshText, Language.msg(l10n_mail_checking, blk)); return; }
        long m = (System.currentTimeMillis() - last) / 60000;
        CharSequence ago = Language.since(last);
        Language.setText(freshText, Language.msg(l10n_mail_checked, blk, ago));
    }

    // ---- INBOX / ARCHIVE (thread lists, email rows) ----

    private View buildThreadList(final boolean archivedView) {
        FrameLayout screen = new FrameLayout(this);
        LinearLayout col = column();

        LinearLayout head = header(archivedView ? Language.msg(l10n_mail_archive) : Language.msg(l10n_mail_inbox), false);
        col.addView(head);
        if (!archivedView) col.addView(buildFreshness());

        if (crypto == null && !archivedView) {
            TextView s = new TextView(this);
            Language.setText(s, paired ? Language.msg(l10n_mail_connecting) : Language.msg(l10n_mail_node_retry));
            s.setTextColor(Design.DIM);
            s.setTextSize(13f);
            s.setPadding(dp(16), dp(8), dp(16), dp(8));
            col.addView(s);
        }

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        final ChatListAdapter adapter = new ChatListAdapter(new ArrayList<>(), archivedView);
        rv.setAdapter(adapter);
        attachSwipe(rv, adapter);
        rv.setClipToPadding(false);
        rv.setPadding(0, 0, 0, dp(88));

        final TextView empty = new TextView(this);
        Language.setText(empty, archivedView ? Language.msg(l10n_mail_no_archived) : Language.msg(l10n_mail_no_mail));
        empty.setTextColor(Design.DIM2); empty.setGravity(Gravity.CENTER); empty.setTextSize(13f);
        empty.setPadding(dp(24), dp(64), dp(24), 0); empty.setVisibility(View.GONE);

        io.execute(() -> {                                  // load off the UI thread
            java.util.Set<String> archived = db.archivedSet();
            List<MailMessage> threads = new ArrayList<>();
            for (MailMessage t : db.threads()) if (archived.contains(t.hashref) == archivedView) threads.add(t);
            ui.post(() -> {
                adapter.data.clear(); adapter.data.addAll(threads); adapter.notifyDataSetChanged();
                empty.setVisibility(threads.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });

        if (archivedView) {
            col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            col.addView(empty);
            screen.addView(col);
            return screen;
        }

        inboxSwipe = new SwipeRefreshLayout(this);
        inboxSwipe.setColorSchemeColors(Design.ACCENT);
        inboxSwipe.setProgressBackgroundColorSchemeColor(Design.SURFACE);
        LinearLayout inner = column();
        inner.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        inboxSwipe.addView(inner);
        inboxSwipe.setOnRefreshListener(() -> {
            fetchBlock(); requestScan();
            ui.postDelayed(() -> { if (inboxSwipe != null) inboxSwipe.setRefreshing(false); }, 4000);
        });
        FrameLayout listWrap = new FrameLayout(this);
        listWrap.addView(inboxSwipe);
        listWrap.addView(empty);
        col.addView(listWrap, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        screen.addView(col);

        // FAB → compose
        FrameLayout fab = new FrameLayout(this);
        GradientDrawable fb = Design.roundBg(this, Design.ACCENT, 16);
        fab.setBackground(fb);
        fab.setElevation(dp(6));
        ImageView pen = icon(R.drawable.pm_mail_ic_pen, Design.ON_ACCENT, 24);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER);
        fab.addView(pen, plp);
        Language.description(fab, Language.msg(l10n_mail_new_message));
        fab.setOnClickListener(v -> push(buildCompose(null, null)));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(56), dp(56));
        flp.gravity = Gravity.BOTTOM | Gravity.END;
        flp.setMargins(0, 0, dp(20), dp(24));
        screen.addView(fab, flp);
        return screen;
    }

    private class ChatListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<MailMessage> data;
        final boolean archivedView;
        ChatListAdapter(List<MailMessage> d, boolean arch) { data = d; archivedView = arch; }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LinearLayout wrap = new LinearLayout(MainActivity.this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(wrap) {};
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            MailMessage t = data.get(pos);
            String other = myId != null && myId.equals(t.frompublickey) ? t.topublickey : t.frompublickey;
            CharSequence name = nameFor(other);
            boolean unread = t.incoming && !t.read;

            LinearLayout wrap = (LinearLayout) h.itemView;
            wrap.removeAllViews();
            wrap.setBackgroundColor(Design.BG);

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.addView(avatar(other, name, 44));

            LinearLayout mid = new LinearLayout(MainActivity.this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(12), 0, dp(8), 0);
            mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout l1 = new LinearLayout(MainActivity.this);
            l1.setOrientation(LinearLayout.HORIZONTAL);
            TextView nm = new TextView(MainActivity.this);
            Language.setText(nm, name);
            nm.setTextColor(unread ? Design.TEXT : Design.DIM);
            nm.setTextSize(14.5f);
            nm.setTypeface(unread ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            nm.setMaxLines(1);
            nm.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView time = new TextView(MainActivity.this);
            Language.setText(time, rel(t.date)); time.setTextColor(Design.DIM2); time.setTextSize(11f);
            l1.addView(nm); l1.addView(time);

            TextView subj = new TextView(MainActivity.this);
            boolean noSubject = t.subject == null || t.subject.isEmpty();
            Language.setText(subj, noSubject ? Language.msg(l10n_mail_no_subject) : t.subject);
            subj.setTextColor(unread ? Design.TEXT : Design.DIM);
            if (noSubject) { subj.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC)); subj.setTextColor(Design.DIM2); }
            else subj.setTypeface(unread ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            subj.setTextSize(12.5f); subj.setMaxLines(1);

            TextView snip = new TextView(MainActivity.this);
            Language.setText(snip, t.incoming ? previewText(t) : Language.msg(l10n_mail_you_preview, previewText(t)));
            snip.setTextColor(Design.DIM);
            snip.setTextSize(11.5f); snip.setMaxLines(1);

            mid.addView(l1); mid.addView(subj); mid.addView(snip);
            row.addView(mid);

            if (unread) {
                View udot = new View(MainActivity.this);
                GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(Design.ACCENT);
                udot.setBackground(g);
                row.addView(udot, new LinearLayout.LayoutParams(dp(8), dp(8)));
            }
            wrap.addView(row);
            wrap.addView(hairline());

            wrap.setOnClickListener(v -> openConversation(t));
            wrap.setOnLongClickListener(v -> { showThreadActions(t, archivedView); return true; });
        }

        @Override public int getItemCount() { return data.size(); }
    }

    // ---- swipe + long-press (archive / delete) ----

    private void attachSwipe(final RecyclerView rv, final ChatListAdapter adapter) {
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override public boolean onMove(RecyclerView r, RecyclerView.ViewHolder a, RecyclerView.ViewHolder b) { return false; }

            @Override public void onSwiped(RecyclerView.ViewHolder vh, int dir) {
                int pos = vh.getBindingAdapterPosition();
                if (pos < 0 || pos >= adapter.data.size()) return;
                final String hashref = adapter.data.get(pos).hashref;
                if (dir == ItemTouchHelper.LEFT) {                 // archive / unarchive
                    io.execute(() -> db.setArchived(hashref, !adapter.archivedView));
                    adapter.data.remove(pos); adapter.notifyItemRemoved(pos);
                    toast(adapter.archivedView ? Language.msg(l10n_mail_unarchived) : Language.msg(l10n_mail_archived));
                } else {                                            // delete (confirm; restore row if cancelled)
                    languageDialog().setMessage(Language.msg(l10n_mail_delete_thread_question))
                            .setPositiveButton(Language.msg(l10n_mail_delete), (d, w) -> { io.execute(() -> db.deleteThread(hashref)); adapter.data.remove(pos); adapter.notifyItemRemoved(pos); })
                            .setNegativeButton(Language.msg(l10n_mail_cancel), (d, w) -> adapter.notifyItemChanged(pos))
                            .setOnCancelListener(d -> adapter.notifyItemChanged(pos)).show();
                }
            }

            @Override public void onChildDraw(Canvas c, RecyclerView r, RecyclerView.ViewHolder vh, float dX, float dY, int as, boolean active) {
                View item = vh.itemView;
                Paint p = new Paint();
                if (dX > 0) {                                       // swipe right → Delete (red, left)
                    p.setColor(Design.RED);
                    c.drawRect(item.getLeft(), item.getTop(), item.getLeft() + dX, item.getBottom(), p);
                    drawSwipeLabel(c, Language.msg(l10n_mail_delete), item.getLeft() + dp(24), item.getTop(), item.getBottom(), true);
                } else if (dX < 0) {                                // swipe left → Archive (orange, right)
                    p.setColor(Design.ACCENT);
                    c.drawRect(item.getRight() + dX, item.getTop(), item.getRight(), item.getBottom(), p);
                    drawSwipeLabel(c, adapter.archivedView ? Language.msg(l10n_mail_unarchive) : Language.msg(l10n_mail_archive), item.getRight() - dp(24), item.getTop(), item.getBottom(), false);
                }
                super.onChildDraw(c, r, vh, dX, dY, as, active);
            }
        }).attachToRecyclerView(rv);
    }

    private void drawSwipeLabel(Canvas c, CharSequence text, float x, int top, int bottom, boolean leftAlign) {
        Paint tp = new Paint();
        tp.setColor(0xFFFFFFFF); tp.setTextSize(dp(14)); tp.setAntiAlias(true);
        tp.setTextAlign(leftAlign ? Paint.Align.LEFT : Paint.Align.RIGHT);
        float y = top + (bottom - top) / 2f - (tp.descent() + tp.ascent()) / 2f;
        c.drawText(text.toString(), x, y, tp);
    }

    private void showThreadActions(MailMessage t, final boolean archivedView) {
        final String hashref = t.hashref;
        CharSequence[] items = {archivedView ? Language.msg(l10n_mail_unarchive) : Language.msg(l10n_mail_archive), Language.msg(l10n_mail_delete)};
        languageDialog().setItems(items, (d, w) -> {
            if (w == 0) {
                io.execute(() -> db.setArchived(hashref, !archivedView));
                toast(archivedView ? Language.msg(l10n_mail_unarchived) : Language.msg(l10n_mail_archived));
                refreshFolderIfVisible();
            } else {
                languageDialog().setMessage(Language.msg(l10n_mail_delete_thread_question))
                        .setPositiveButton(Language.msg(l10n_mail_delete), (dd, ww) -> { io.execute(() -> db.deleteThread(hashref)); refreshFolderIfVisible(); })
                        .setNegativeButton(Language.msg(l10n_mail_cancel), null).show();
            }
        }).show();
    }

    private void openConversation(MailMessage thread) {
        String other = myId != null && myId.equals(thread.frompublickey) ? thread.topublickey : thread.frompublickey;
        openConversation(other, thread.subject == null ? "" : thread.subject);
    }

    private void openConversation(final String otherKey, final String subject) {
        io.execute(() -> {
            db.markThreadRead(MailText.threadKey(myId, otherKey, subject == null ? "" : subject));
            ui.post(() -> push(buildConversation(otherKey, subject == null ? "" : subject)));
        });
    }

    // ---- OUTBOX / SENT (per-message status lists) ----

    private View buildStatusList(CharSequence title, final boolean outboxView) {
        LinearLayout col = column();
        col.addView(header(title, false));

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));

        final TextView empty = new TextView(this);
        Language.setText(empty, outboxView ? Language.msg(l10n_mail_empty_outbox)
                : Language.msg(l10n_mail_empty_sent));
        empty.setTextColor(Design.DIM2); empty.setGravity(Gravity.CENTER); empty.setTextSize(13f);
        empty.setPadding(dp(24), dp(64), dp(24), 0); empty.setVisibility(View.GONE);

        final List<MailMessage> data = new ArrayList<>();
        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
                LinearLayout wrap = new LinearLayout(MainActivity.this);
                wrap.setOrientation(LinearLayout.VERTICAL);
                wrap.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(wrap) {};
            }

            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                final MailMessage m = data.get(pos);
                LinearLayout wrap = (LinearLayout) h.itemView;
                wrap.removeAllViews();
                LinearLayout cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setPadding(dp(14), dp(10), dp(14), dp(10));

                LinearLayout l1 = new LinearLayout(MainActivity.this);
                TextView to = new TextView(MainActivity.this);
                CharSequence subj = (m.subject == null || m.subject.isEmpty()) ? Language.msg(l10n_mail_no_subject) : m.subject;
                Language.setText(to, Language.msg(l10n_mail_to_subject, nameFor(m.topublickey), subj));
                to.setTextColor(Design.TEXT); to.setTextSize(13f); to.setTypeface(Typeface.DEFAULT_BOLD);
                to.setMaxLines(1);
                to.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView time = new TextView(MainActivity.this);
                Language.setText(time, rel(m.date)); time.setTextColor(Design.DIM2); time.setTextSize(10.5f);
                l1.addView(to); l1.addView(time);
                cell.addView(l1);

                TextView snip = new TextView(MainActivity.this);
                Language.setText(snip, previewText(m));
                snip.setTextColor(Design.DIM); snip.setTextSize(11.5f); snip.setMaxLines(1);
                cell.addView(snip);

                cell.addView(statusLine(m, outboxView));
                wrap.addView(cell);
                wrap.addView(hairline());
                wrap.setOnClickListener(v -> openConversation(m.topublickey, m.subject == null ? "" : m.subject));
            }

            @Override public int getItemCount() { return data.size(); }
        };
        rv.setAdapter(adapter);

        io.execute(() -> {
            List<MailMessage> l = outboxView ? db.outbox() : db.sent();
            ui.post(() -> {
                data.clear(); data.addAll(l); adapter.notifyDataSetChanged();
                empty.setVisibility(l.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });

        FrameLayout listWrap = new FrameLayout(this);
        listWrap.addView(rv);
        listWrap.addView(empty);
        col.addView(listWrap, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    /** The honest lifecycle line: ring "Posting…" → chain "On-chain · blk N" → check Language.msg(l10n_mail_confirmed) / failed+Retry. */
    private View statusLine(final MailMessage m, boolean withRetry) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(4), 0, 0);
        int ic; int tint; CharSequence label; int labelColor;
        if ("posting".equals(m.status)) {
            ic = R.drawable.pm_mail_ic_ring; tint = Design.ACCENT; label = Language.msg(l10n_mail_posting); labelColor = Design.ACCENT_TEXT;
        } else if ("failed".equals(m.status)) {
            ic = R.drawable.pm_mail_ic_close; tint = Design.RED; label = Language.msg(l10n_mail_failed_chain); labelColor = Design.RED;
        } else if ("confirmed".equals(m.status)) {
            ic = R.drawable.pm_mail_ic_check; tint = Design.GREEN;
            label = Language.join(Language.msg(l10n_mail_confirmed), m.sentblock > 0 ? Language.join(" · ", Language.msg(l10n_mail_block, m.sentblock)) : "");
            labelColor = Design.DIM;
        } else {
            ic = R.drawable.pm_mail_ic_chain; tint = Design.GREEN;
            label = Language.msg(l10n_mail_confirming, Language.join(Language.msg(l10n_mail_on_chain), m.sentblock > 0 ? Language.join(" · ", Language.msg(l10n_mail_block, m.sentblock)) : ""));
            labelColor = Design.DIM;
        }
        line.addView(icon(ic, tint, 12));
        TextView t = new TextView(this);
        Language.setText(t, label); t.setTextColor(labelColor); t.setTextSize(11f);
        t.setPadding(dp(6), 0, 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(t);
        if (withRetry && "failed".equals(m.status)) {
            LinearLayout retry = new LinearLayout(this);
            retry.setOrientation(LinearLayout.HORIZONTAL);
            retry.setGravity(Gravity.CENTER_VERTICAL);
            retry.setBackground(Design.outlineBg(this, Design.ACCENT, 11));
            retry.setPadding(dp(9), dp(2), dp(9), dp(2));
            retry.addView(icon(R.drawable.pm_mail_ic_refresh, Design.ACCENT, 11));
            TextView rt = new TextView(this);
            Language.setText(rt, Language.msg(l10n_mail_retry)); rt.setTextColor(Design.ACCENT_TEXT); rt.setTextSize(10.5f); rt.setTypeface(Typeface.DEFAULT_BOLD);
            rt.setPadding(dp(4), 0, 0, 0);
            retry.addView(rt);
            retry.setOnClickListener(v -> retrySend(m.id));
            line.addView(retry);
        }
        return line;
    }

    // ---- CONVERSATION (bubbles kept) ----

    private View buildConversation(final String otherKey, final String subject) {
        final String hashref = MailText.threadKey(myId == null ? "" : myId, otherKey, subject);
        LinearLayout col = column();
        col.setTag(hashref);
        proactivePayaddr(otherKey);   // request their receiving address now, so it's ready when you pay

        // top bar: back + avatar + subject/name
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(Design.SURFACE);
        head.setPadding(dp(6), dp(8), dp(12), dp(8));
        head.addView(iconBtn(R.drawable.pm_mail_ic_back, v -> pop()));
        head.addView(avatar(otherKey, nameFor(otherKey), 34));
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setPadding(dp(10), 0, 0, 0);
        tcol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView nm = new TextView(this);
        Language.setText(nm, subject.isEmpty() ? nameFor(otherKey) : subject);
        nm.setTextColor(Design.TEXT); nm.setTextSize(15f); nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setMaxLines(1);
        TextView sub = new TextView(this);
        Language.setText(sub, subject.isEmpty() ? shortKey(otherKey) : Language.join(nameFor(otherKey), " · ", shortKey(otherKey)));
        sub.setTextColor(Design.DIM); sub.setTextSize(10.5f); sub.setTypeface(Typeface.MONOSPACE);
        sub.setMaxLines(1);
        tcol.addView(nm); tcol.addView(sub);
        head.addView(tcol);
        head.addView(iconBtn(R.drawable.pm_mail_ic_dots, v -> conversationMenu(v, otherKey, hashref)));
        col.addView(head);

        // messages
        final RecyclerView rv = new RecyclerView(this);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rv.setLayoutManager(lm);
        final MessagesAdapter adapter = new MessagesAdapter(new ArrayList<>());
        rv.setAdapter(adapter);
        rv.setPadding(0, dp(8), 0, dp(8));
        rv.setClipToPadding(false);
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        currentMessages = rv;
        convAdapter = adapter; convHashref = hashref;   // enable in-place updates (no screen rebuild)
        io.execute(() -> {                              // load messages off the UI thread
            final List<MailMessage> msgs = db.thread(hashref);
            ui.post(() -> { adapter.setData(msgs); if (!msgs.isEmpty()) rv.scrollToPosition(msgs.size() - 1); });
        });

        // composer
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Design.SURFACE);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        ImageView attach = iconBtn(R.drawable.pm_mail_ic_plus, null);
        attach.setColorFilter(Design.ACCENT);
        attach.setOnClickListener(v -> {
            androidx.appcompat.widget.PopupMenu pm = new androidx.appcompat.widget.PopupMenu(this, attach);
            pm.getMenu().add(0, 1, 0, Language.msg(l10n_mail_photo_gif));
            pm.getMenu().add(0, 2, 1, Language.msg(l10n_mail_send_funds));
            pm.setOnMenuItemClickListener(it -> {
                if (it.getItemId() == 1) { imagePickContact = otherKey; imagePickSubject = subject; imagePicker.launch("image/*"); }
                else showSendFundsSheet(otherKey, subject);
                return true;
            });
            showLanguageMenu(pm);
        });
        bar.addView(attach);
        final EditText box = new EditText(this);
        Language.hint(box, Language.msg(l10n_mail_reply));
        box.setHintTextColor(Design.DIM2);
        box.setTextColor(Design.TEXT);
        box.setTextSize(15f);
        box.setMaxLines(4);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.setBackground(Design.roundBg(this, Design.SURFACE2, 20));
        box.setPadding(dp(16), dp(10), dp(16), dp(10));
        box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(box);

        final FrameLayout send = new FrameLayout(this);
        GradientDrawable sd = new GradientDrawable(); sd.setShape(GradientDrawable.OVAL); sd.setColor(Design.ACCENT);
        send.setBackground(sd);
        send.addView(icon(R.drawable.pm_mail_ic_up, Design.ON_ACCENT, 16), new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(40), dp(40));
        slp.leftMargin = dp(8);
        send.setLayoutParams(slp);
        Language.description(send, Language.msg(l10n_mail_send));
        send.setOnClickListener(v -> {
            String text = box.getText().toString();
            doSend(send, otherKey, subject, text, () -> Language.setText(box, ""));
        });
        bar.addView(send);
        col.addView(bar);
        return col;
    }

    /** A precomputed render row: a message, an optional date header above it, and whether to show a footer. */
    private static class Row {
        final MailMessage m; final CharSequence dateHeader; final boolean footer;
        Row(MailMessage m, CharSequence dateHeader, boolean footer) { this.m = m; this.dateHeader = dateHeader; this.footer = footer; }
    }

    private class MessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        List<Row> rows = new ArrayList<>();
        MessagesAdapter(List<MailMessage> msgs) { setData(msgs); }

        void setData(List<MailMessage> msgs) {
            List<Row> out = new ArrayList<>();
            for (int i = 0; i < msgs.size(); i++) {
                MailMessage m = msgs.get(i);
                CharSequence header = null;
                if (i == 0 || !sameDay(msgs.get(i - 1).date, m.date)) header = dateLabel(m.date);
                boolean last = i == msgs.size() - 1;
                boolean footer = last
                        || msgs.get(i + 1).incoming != m.incoming
                        || msgs.get(i + 1).date - m.date > 5 * 60 * 1000L
                        || !sameDay(m.date, msgs.get(i + 1).date)
                        || (!m.incoming && ("posting".equals(m.status) || "failed".equals(m.status)));
                out.add(new Row(m, header, footer));
            }
            rows = out;
            notifyDataSetChanged();
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LinearLayout v = new LinearLayout(MainActivity.this);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            Row r = rows.get(pos);
            LinearLayout v = (LinearLayout) h.itemView;
            v.removeAllViews();

            if (r.dateHeader != null) {
                TextView chip = new TextView(MainActivity.this);
                Language.setText(chip, r.dateHeader);
                chip.setTextColor(Design.DIM2);
                chip.setTextSize(11f);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(0, dp(10), 0, dp(8));
                v.addView(chip);
            }

            LinearLayout rowWrap = new LinearLayout(MainActivity.this);
            rowWrap.setOrientation(LinearLayout.HORIZONTAL);
            rowWrap.setPadding(dp(12), dp(1), dp(12), dp(1));
            rowWrap.setGravity(r.m.incoming ? Gravity.START : Gravity.END);
            rowWrap.addView("image".equals(r.m.type) ? imageBubble(r.m)
                    : "payment".equals(r.m.type) ? paymentBubble(r.m) : textBubble(r.m));
            v.addView(rowWrap);

            if (r.footer) {
                LinearLayout f = new LinearLayout(MainActivity.this);
                f.setOrientation(LinearLayout.HORIZONTAL);
                f.setGravity(Gravity.CENTER_VERTICAL);
                f.setPadding(dp(18), dp(2), dp(18), dp(8));
                LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                flp.gravity = r.m.incoming ? Gravity.START : Gravity.END;
                f.setLayoutParams(flp);

                TextView time = new TextView(MainActivity.this);
                Language.setText(time, clock(r.m.date));
                time.setTextColor(Design.DIM2);
                time.setTextSize(10f);
                f.addView(time);

                if (!r.m.incoming) {
                    int ic = 0; int tint = 0; CharSequence label = null; int labelColor = Design.DIM2;
                    if ("posting".equals(r.m.status)) { ic = R.drawable.pm_mail_ic_ring; tint = Design.ACCENT; label = Language.msg(l10n_mail_posting); labelColor = Design.ACCENT_TEXT; }
                    else if ("failed".equals(r.m.status)) { ic = R.drawable.pm_mail_ic_close; tint = Design.RED; label = Language.msg(l10n_mail_failed_retry); labelColor = Design.RED; }
                    else if ("confirmed".equals(r.m.status)) { ic = R.drawable.pm_mail_ic_check; tint = Design.GREEN; label = Language.msg(l10n_mail_confirmed); }
                    else if ("sent".equals(r.m.status)) {
                        ic = R.drawable.pm_mail_ic_chain; tint = Design.DIM2;
                        label = Language.join(Language.msg(l10n_mail_on_chain), r.m.sentblock > 0 ? Language.join(" · ", Language.msg(l10n_mail_block, r.m.sentblock)) : "");
                    }
                    if (label != null) {
                        TextView dotSep = new TextView(MainActivity.this);
                        Language.setText(dotSep, " · "); dotSep.setTextColor(Design.DIM2); dotSep.setTextSize(10f);
                        f.addView(dotSep);
                        f.addView(icon(ic, tint, 11));
                        TextView st = new TextView(MainActivity.this);
                        Language.setText(st, label); st.setTextColor(labelColor); st.setTextSize(10f);
                        st.setPadding(dp(3), 0, 0, 0);
                        f.addView(st);
                    }
                }
                v.addView(f);
            }
        }

        @Override public int getItemCount() { return rows.size(); }
    }

    private void conversationMenu(View anchor, String otherKey, String hashref) {
        androidx.appcompat.widget.PopupMenu m = new androidx.appcompat.widget.PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, db.contactName(otherKey) == null ? Language.msg(l10n_mail_add_contacts) : Language.msg(l10n_mail_view_contact_key));
        m.getMenu().add(0, 2, 1, Language.msg(l10n_mail_delete_thread));
        m.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 1) {
                if (db.contactName(otherKey) == null) addContactDialog(otherKey);
                else { copy(otherKey, Language.msg(l10n_mail_mail_key)); toast(Language.msg(l10n_mail_key_copied)); }
            } else {
                languageDialog().setMessage(Language.msg(l10n_mail_delete_device_thread))
                        .setPositiveButton(Language.msg(l10n_mail_delete), (d, w) -> io.execute(() -> { db.deleteThread(hashref); ui.post(this::pop); }))
                        .setNegativeButton(Language.msg(l10n_mail_cancel), null).show();
            }
            return true;
        });
        showLanguageMenu(m);
    }

    // ---- COMPOSE ----

    private View buildCompose(String prefillKey, String prefillSubject) {
        LinearLayout col = column();

        // top bar: close + title + Send
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(Design.SURFACE);
        head.setPadding(dp(6), dp(10), dp(12), dp(10));
        head.addView(iconBtn(R.drawable.pm_mail_ic_close, v -> pop()));
        TextView t = new TextView(this);
        Language.setText(t, Language.msg(l10n_mail_new_message));
        t.setTextColor(Design.TEXT); t.setTextSize(17f); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(6), 0, 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(t);

        final EditText to = new EditText(this);
        final EditText subject = new EditText(this);
        final EditText body = new EditText(this);

        LinearLayout sendBtn = new LinearLayout(this);
        sendBtn.setOrientation(LinearLayout.HORIZONTAL);
        sendBtn.setGravity(Gravity.CENTER_VERTICAL);
        sendBtn.setBackground(Design.roundBg(this, Design.ACCENT, 15));
        sendBtn.setPadding(dp(13), dp(6), dp(13), dp(6));
        sendBtn.addView(icon(R.drawable.pm_mail_ic_send, Design.ON_ACCENT, 13));
        TextView st = new TextView(this);
        Language.setText(st, Language.msg(l10n_mail_send)); st.setTextColor(Design.ON_ACCENT); st.setTextSize(13f); st.setTypeface(Typeface.DEFAULT_BOLD);
        st.setPadding(dp(5), 0, 0, 0);
        sendBtn.addView(st);
        head.addView(sendBtn);
        col.addView(head);

        // To
        LinearLayout toRow = fieldRow(Language.msg(l10n_mail_to));
        Language.hint(to, Language.msg(l10n_mail_to_hint));
        styleFieldInput(to, true);
        toRow.addView(to, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        toRow.addView(fieldChip(R.drawable.pm_mail_ic_scan, Language.msg(l10n_mail_scan), v -> startScan(to)));
        toRow.addView(fieldChip(R.drawable.pm_mail_ic_contacts, null, v -> pickContact(to)));
        if (prefillKey != null) Language.setText(to, prefillKey);
        col.addView(toRow);
        col.addView(hairline());

        // Subject
        LinearLayout subjRow = fieldRow(Language.msg(l10n_mail_subject));
        Language.hint(subject, Language.msg(l10n_mail_subject_hint));
        styleFieldInput(subject, false);
        subject.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (prefillSubject != null) Language.setText(subject, prefillSubject);
        subjRow.addView(subject, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(subjRow);
        col.addView(hairline());

        // Body
        Language.hint(body, Language.msg(l10n_mail_write_hint));
        body.setHintTextColor(Design.DIM2);
        body.setTextColor(Design.TEXT);
        body.setTextSize(14.5f);
        body.setGravity(Gravity.TOP);
        body.setBackground(null);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        body.setPadding(dp(16), dp(12), dp(16), dp(12));
        col.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // attach row
        col.addView(hairline());
        LinearLayout attachRow = new LinearLayout(this);
        attachRow.setOrientation(LinearLayout.HORIZONTAL);
        attachRow.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout photo = attachChip(R.drawable.pm_mail_ic_photo, Language.msg(l10n_mail_photo));
        photo.setOnClickListener(v -> {
            String k = acceptKeyShare(to.getText().toString());
            if (!CommsIdentity.isValidPublicId(k)) { toast(Language.msg(l10n_mail_valid_key_first)); return; }
            imagePickContact = k; imagePickSubject = subject.getText().toString().trim();
            imagePicker.launch("image/*");
        });
        LinearLayout funds = attachChip(R.drawable.pm_mail_ic_coin, Language.msg(l10n_mail_send_funds));
        funds.setOnClickListener(v -> {
            String k = acceptKeyShare(to.getText().toString());
            if (!CommsIdentity.isValidPublicId(k)) { toast(Language.msg(l10n_mail_valid_key_first)); return; }
            showSendFundsSheet(k, subject.getText().toString().trim());
        });
        attachRow.addView(photo);
        View gap = new View(this);
        attachRow.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
        attachRow.addView(funds);
        col.addView(attachRow);

        // delivery hint
        col.addView(hairline());
        LinearLayout hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.HORIZONTAL);
        hint.setBackgroundColor(Design.SURFACE);
        hint.setPadding(dp(14), dp(9), dp(14), dp(11));
        hint.addView(icon(R.drawable.pm_mail_ic_chain, Design.ACCENT, 13));
        TextView ht = new TextView(this);
        Language.setText(ht, Language.msg(l10n_mail_delivery_hint));
        ht.setTextColor(Design.DIM); ht.setTextSize(10.5f);
        ht.setPadding(dp(7), 0, 0, 0);
        hint.addView(ht, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(hint);

        sendBtn.setOnClickListener(v -> {
            String k = acceptKeyShare(to.getText().toString());
            String subj = subject.getText().toString().trim();
            String text = body.getText().toString();
            if (!CommsIdentity.isValidPublicId(k)) { toast(Language.msg(l10n_mail_valid_key)); return; }
            if (text.trim().isEmpty()) { toast(Language.msg(l10n_mail_write_first)); return; }
            doSend(sendBtn, k, subj, text, () -> { pop(); openConversation(k, subj); });
        });

        return col;
    }

    private LinearLayout fieldRow(CharSequence label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(4), dp(12), dp(4));
        TextView k = new TextView(this);
        Language.setText(k, label); k.setTextColor(Design.DIM); k.setTextSize(12f);
        k.setWidth(dp(58));
        row.addView(k);
        return row;
    }

    private void styleFieldInput(EditText e, boolean mono) {
        e.setHintTextColor(Design.DIM2);
        e.setTextColor(Design.TEXT);
        e.setTextSize(mono ? 12.5f : 14f);
        if (mono) e.setTypeface(Typeface.MONOSPACE);
        e.setBackground(null);
        e.setSingleLine(true);
        e.setPadding(0, dp(10), dp(8), dp(10));
    }

    private LinearLayout fieldChip(int iconRes, CharSequence label, View.OnClickListener click) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(Design.outlineBg(this, Design.ACCENT, 11));
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        chip.addView(icon(iconRes, Design.ACCENT, 12));
        if (label != null) {
            TextView t = new TextView(this);
            Language.setText(t, label); t.setTextColor(Design.ACCENT_TEXT); t.setTextSize(10.5f); t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(dp(4), 0, 0, 0);
            chip.addView(t);
        }
        chip.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        chip.setLayoutParams(lp);
        return chip;
    }

    private LinearLayout attachChip(int iconRes, CharSequence label) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(Design.roundBg(this, Design.SURFACE2, 14));
        chip.setPadding(dp(11), dp(6), dp(11), dp(6));
        chip.addView(icon(iconRes, Design.ACCENT, 13));
        TextView t = new TextView(this);
        Language.setText(t, label); t.setTextColor(Design.ACCENT_TEXT); t.setTextSize(11.5f); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(5), 0, 0, 0);
        chip.addView(t);
        return chip;
    }

    // ---- CONTACTS ----

    private View buildContacts() {
        LinearLayout col = column();
        LinearLayout head = header(Language.msg(l10n_mail_contacts), false);
        head.addView(iconBtn(R.drawable.pm_mail_ic_plus, v -> addContactDialog(null)));
        col.addView(head);
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        final List<String[]> cs = db.contacts();
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(10), dp(14), dp(10));
                row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(row) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                String[] c = cs.get(pos);
                final long id = Long.parseLong(c[0]); final String name = c[1], key = c[2];
                LinearLayout row = (LinearLayout) h.itemView; row.removeAllViews();
                row.addView(avatar(key, name, 44));
                LinearLayout mid = new LinearLayout(MainActivity.this); mid.setOrientation(LinearLayout.VERTICAL);
                mid.setPadding(dp(12), 0, 0, 0);
                mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView nm = new TextView(MainActivity.this); Language.setText(nm, name); nm.setTextColor(Design.TEXT); nm.setTextSize(15f); nm.setTypeface(Typeface.DEFAULT_BOLD);
                TextView kv = new TextView(MainActivity.this); Language.setText(kv, shortKey(key)); kv.setTextColor(Design.DIM); kv.setTextSize(12f); kv.setTypeface(Typeface.MONOSPACE);
                mid.addView(nm); mid.addView(kv); row.addView(mid);
                row.setOnClickListener(v -> push(buildCompose(key, null)));
                row.setOnLongClickListener(v -> {
                    languageDialog().setMessage(Language.msg(l10n_mail_delete_contact, name))
                            .setPositiveButton(Language.msg(l10n_mail_delete), (d, w) -> io.execute(() -> { db.deleteContact(id); ui.post(() -> { contactNames = null; refreshFolderIfVisible(); }); }))
                            .setNegativeButton(Language.msg(l10n_mail_cancel), null).show();
                    return true;
                });
            }
            @Override public int getItemCount() { return cs.size(); }
        });
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    // ---- YOUR KEY / IDENTITY ----

    private View buildIdentity() {
        LinearLayout col = column();
        col.addView(header(Language.msg(l10n_mail_your_key), false));
        LinearLayout form = pad(column());
        form.setGravity(Gravity.CENTER_HORIZONTAL);

        form.addView(avatar(myId, myName, 72));

        if (myId != null) {
            Bitmap qr = QrUtil.qr(keyShare(), dp(200));
            if (qr != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(qr);
                int s = dp(200);
                LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(s, s);
                qlp.topMargin = dp(16);
                iv.setLayoutParams(qlp);
                iv.setPadding(dp(8), dp(8), dp(8), dp(8));
                iv.setBackgroundColor(0xFFFFFFFF);
                form.addView(iv);
            }
        }
        TextView keyLabel = new TextView(this);
        Language.setText(keyLabel, Language.msg(l10n_mail_share_key));
        keyLabel.setTextColor(Design.DIM); keyLabel.setTextSize(12f); keyLabel.setPadding(0, dp(14), 0, dp(6));
        form.addView(keyLabel);
        TextView key = new TextView(this);
        Language.setText(key, myId == null ? Language.msg(l10n_mail_connecting) : shortKey(myId));
        key.setTextColor(Design.TEXT); key.setTextSize(13f); key.setTypeface(Typeface.MONOSPACE);
        form.addView(key);
        TextView copy = accentButton(Language.msg(l10n_mail_copy_key));
        copy.setOnClickListener(v -> { if (myId != null) { copy(keyShare(), Language.msg(l10n_mail_key_address)); toast(Language.msg(l10n_mail_copied)); } });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        form.addView(copy, clp);

        if (myPayaddr != null && !myPayaddr.isEmpty()) {
            TextView ral = new TextView(this);
            Language.setText(ral, Language.msg(l10n_mail_receiving_help));
            ral.setTextColor(Design.DIM); ral.setTextSize(12f); ral.setPadding(0, dp(16), 0, dp(4));
            form.addView(ral);
            TextView ra = new TextView(this);
            Language.setText(ra, shortKey(myPayaddr));
            ra.setTextColor(Design.TEXT); ra.setTextSize(12f); ra.setTypeface(Typeface.MONOSPACE);
            ra.setOnClickListener(v -> { copy(myPayaddr, Language.msg(l10n_mail_receiving_address)); toast(Language.msg(l10n_mail_address_copied)); });
            form.addView(ra);
        }

        TextView backup = textButton(Language.msg(l10n_mail_backup_identity));
        backup.setOnClickListener(v -> { if (identity == null) toast(Language.msg(l10n_mail_connect_first)); else exportLauncher.launch("minima-mail-backup.json"); });
        form.addView(backup);
        TextView restore = textButton(Language.msg(l10n_mail_restore_backup));
        restore.setOnClickListener(v -> importLauncher.launch(new String[]{"application/json", "*/*"}));
        form.addView(restore);

        ScrollView sv = new ScrollView(this); sv.addView(form);
        col.addView(sv);
        return col;
    }

    // ---- SETTINGS ----

    private View buildSettings() {
        LinearLayout col = column();
        col.addView(header(Language.msg(l10n_mail_settings), false));
        LinearLayout list = column();

        list.addView(settingsGroup(Language.msg(l10n_mail_appearance)));
        LinearLayout themeRow = settingsRow(Language.msg(l10n_mail_theme), Language.msg(l10n_mail_theme_hint));
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(Design.roundBg(this, Design.SURFACE2, 15));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.addView(segOption(Language.msg(l10n_mail_paper), Design.LIGHT, () -> setTheme2("light")));
        seg.addView(segOption(Language.msg(l10n_mail_dark), !Design.LIGHT, () -> setTheme2("dark")));
        themeRow.addView(seg);
        list.addView(themeRow);
        list.addView(hairline());

        list.addView(settingsGroup(Language.msg(l10n_mail_identity)));
        LinearLayout nameRow = settingsRow(Language.msg(l10n_mail_display_name), Language.msg(l10n_mail_name_help));
        TextView nv = new TextView(this);
        Language.setText(nv, myName.isEmpty() ? Language.msg(l10n_mail_not_set) : myName);
        nv.setTextColor(Design.DIM); nv.setTextSize(12.5f);
        nameRow.addView(nv);
        nameRow.addView(chev());
        nameRow.setOnClickListener(v -> {
            final EditText in = input(Language.msg(l10n_mail_name_hint));
            Language.setText(in, myName);
            languageDialog().setTitle(Language.msg(l10n_mail_display_name)).setView(in)
                    .setPositiveButton(Language.msg(l10n_mail_save), (d, w) -> {
                        myName = in.getText().toString().trim();
                        io.execute(() -> db.setMeta("myname", myName));
                        refreshFolderIfVisible();
                    })
                    .setNegativeButton(Language.msg(l10n_mail_cancel), null).show();
        });
        list.addView(nameRow);
        list.addView(hairline());
        LinearLayout keyRow = settingsRow(Language.msg(l10n_mail_key_backup), Language.msg(l10n_mail_key_backup_hint));
        keyRow.addView(chev());
        keyRow.setOnClickListener(v -> showFolder(Folder.IDENTITY));
        list.addView(keyRow);
        list.addView(hairline());

        list.addView(settingsGroup(Language.msg(l10n_mail_about)));
        LinearLayout helpRow = settingsRow(Language.msg(l10n_mail_how_delivery), Language.msg(l10n_mail_how_delivery_hint));
        helpRow.addView(chev());
        helpRow.setOnClickListener(v -> push(buildHelp()));
        list.addView(helpRow);
        list.addView(hairline());
        LinearLayout verRow = settingsRow(Language.msg(l10n_mail_version), "Minima Mail v" + BuildConfig.VERSION_NAME);
        list.addView(verRow);

        ScrollView sv = new ScrollView(this); sv.addView(list);
        col.addView(sv);
        return col;
    }

    private void setTheme2(String theme) {
        if (theme.equals(db.getMeta("theme", "light"))) return;
        db.setMeta("theme", theme);
        Design.load(theme);
        recreate();
    }

    private TextView settingsGroup(CharSequence s) {
        TextView t = new TextView(this);
        Language.setText(t, Language.upper(s));
        t.setTextColor(Design.DIM2); t.setTextSize(10.5f); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(16), dp(18), dp(16), dp(6));
        return t;
    }

    private LinearLayout settingsRow(CharSequence title, CharSequence detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(16), dp(11));
        LinearLayout grow = new LinearLayout(this);
        grow.setOrientation(LinearLayout.VERTICAL);
        grow.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(this);
        Language.setText(t, title); t.setTextColor(Design.TEXT); t.setTextSize(14f);
        grow.addView(t);
        if (detail != null) {
            TextView d = new TextView(this);
            Language.setText(d, detail); d.setTextColor(Design.DIM); d.setTextSize(11f);
            d.setPadding(0, dp(1), 0, 0);
            grow.addView(d);
        }
        row.addView(grow);
        return row;
    }

    private ImageView chev() {
        ImageView iv = icon(R.drawable.pm_mail_ic_chev, Design.DIM2, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(14), dp(14));
        lp.leftMargin = dp(8);
        iv.setLayoutParams(lp);
        return iv;
    }

    private TextView segOption(CharSequence label, boolean on, final Runnable click) {
        TextView t = new TextView(this);
        Language.setText(t, label);
        t.setTextSize(11.5f);
        t.setPadding(dp(11), dp(4), dp(11), dp(4));
        if (on) {
            t.setTextColor(Design.ON_ACCENT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setBackground(Design.roundBg(this, Design.ACCENT, 12));
        } else {
            t.setTextColor(Design.DIM);
        }
        t.setOnClickListener(v -> click.run());
        return t;
    }

    private View buildHelp() {
        LinearLayout col = column();
        col.addView(header(Language.msg(l10n_mail_how_delivery), true));
        ScrollView sv = new ScrollView(this);
        TextView t = new TextView(this);
        t.setPadding(dp(16), dp(8), dp(16), dp(24));
        t.setTextColor(Design.DIM); t.setTextSize(13f); t.setLineSpacing(dp(4), 1f);
        Language.setText(t, Language.msg(l10n_mail_help));
        sv.addView(t);
        col.addView(sv);
        return col;
    }

    // ---- send (honest lifecycle: posting → sent → confirmed / failed) ----

    private void doSend(final View btn, String toKey, String subject, String message, Runnable onOk) {
        if (crypto == null) { toast(Language.msg(l10n_mail_still_connecting)); return; }
        if (!CommsIdentity.isValidPublicId(toKey)) { toast(Language.msg(l10n_mail_valid_key)); return; }
        if (message == null || message.trim().isEmpty()) return;

        btn.setEnabled(false);
        final MailMessage m = new MailMessage();
        m.frompublickey = myId; m.fromname = myName; m.topublickey = toKey;
        m.subject = subject == null ? "" : subject; m.message = message;
        m.randomid = MailText.randomId(); m.date = System.currentTimeMillis();
        m.incoming = false; m.read = true;
        m.payaddr = myPayaddr;
        m.status = "posting"; m.sentblock = chainBlock;
        m.hashref = MailText.threadKey(myId, toKey, m.subject);

        io.execute(() -> {                                   // appears in the thread + Outbox immediately
            m.id = db.insert(m);
            ui.post(() -> {
                btn.setEnabled(true);
                if (onOk != null) onOk.run();
                reloadConversation(m.hashref);
                postMessage(m);
            });
        });
    }

    /** Post an already-inserted message to the chain and keep its status honest. */
    private void postMessage(final MailMessage m) {
        CommsTransport.send(node, crypto, m, new CommsTransport.SendCb() {
            @Override public void onSent() {
                io.execute(() -> {
                    db.setStatus(m.id, "sent");
                    db.setSentBlock(m.id, chainBlock);
                    ui.post(() -> { reloadConversation(m.hashref); refreshFolderIfVisible(); });
                });
            }
            @Override public void onFailed(String err) {
                io.execute(() -> {
                    db.setStatus(m.id, "failed");
                    ui.post(() -> {
                        toast(Language.msg(l10n_mail_send_error, MailMessages.message(err)));
                        reloadConversation(m.hashref); refreshFolderIfVisible();
                    });
                });
            }
        });
    }

    private void retrySend(final long id) {
        if (crypto == null) { toast(Language.msg(l10n_mail_still_connecting)); return; }
        io.execute(() -> {
            final MailMessage m = db.message(id);
            if (m == null) return;
            db.setStatus(id, "posting");
            ui.post(() -> { refreshFolderIfVisible(); postMessage(m); });
        });
    }

    // ---- bubbles ----

    private View textBubble(MailMessage m) {
        TextView b = new TextView(this);
        Language.setText(b, m.message);
        b.setTextSize(15f);
        b.setPadding(dp(14), dp(9), dp(14), dp(9));
        b.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.75));
        b.setTextColor(m.incoming ? Design.TEXT : Design.ON_ACCENT);
        b.setBackground(m.incoming && Design.LIGHT
                ? Design.cardBg(this, Design.SURFACE2, 18)
                : Design.roundBg(this, m.incoming ? Design.SURFACE2 : Design.ACCENT, 18));
        return b;
    }

    private View paymentBubble(MailMessage m) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(Design.roundBg(this, m.incoming ? Design.SURFACE2 : Design.ACCENT, 18));
        int fg = m.incoming ? Design.TEXT : Design.ON_ACCENT;
        int sub = m.incoming ? Design.DIM : (Design.ON_ACCENT & 0x00FFFFFF) | 0xCC000000;
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.addView(icon(R.drawable.pm_mail_ic_coin, sub, 12));
        TextView head = new TextView(this);
        Language.setText(head, m.incoming ? Language.msg(l10n_mail_sent_you, nameFor(m.frompublickey)) : Language.msg(l10n_mail_you_sent));
        head.setTextColor(sub); head.setTextSize(11f);
        head.setPadding(dp(5), 0, 0, 0);
        headRow.addView(head);
        TextView amt = new TextView(this);
        Language.setText(amt, m.amount + "  " + (m.tokenname == null || m.tokenname.isEmpty() ? "MINIMA" : m.tokenname));
        amt.setTextColor(fg); amt.setTextSize(20f); amt.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(headRow); card.addView(amt);
        if (m.message != null && !m.message.isEmpty()) {
            TextView memo = new TextView(this);
            Language.setText(memo, m.message); memo.setTextColor(fg); memo.setTextSize(13f); memo.setPadding(0, dp(4), 0, 0);
            card.addView(memo);
        }
        return card;
    }

    // ---- send funds ----

    private void showSendFundsSheet(final String otherKey, final String subject) {
        if (crypto == null) { toast(Language.msg(l10n_mail_connect_first)); return; }
        final String known = db.contactPayaddr(otherKey);
        if (known == null) sendPayaddrReq(otherKey);   // try to auto-fetch their address in the background
        loadTokens(tokens -> {
            if (tokens.isEmpty()) { toast(Language.msg(l10n_mail_no_funds)); return; }
            LinearLayout box = pad(column());
            final String[] sel = {tokens.get(0)[0], tokens.get(0)[1], tokens.get(0)[2]};   // tokenid, name, balance
            box.addView(label(Language.msg(l10n_mail_token)));
            final TextView tokenBtn = accentOutlineButton(Language.msg(l10n_mail_token_balance, sel[1], sel[2]));
            tokenBtn.setOnClickListener(v -> {
                String[] labels = new String[tokens.size()];
                for (int i = 0; i < tokens.size(); i++) labels[i] = tokens.get(i)[1] + " — " + tokens.get(i)[2];
                languageDialog().setTitle(Language.msg(l10n_mail_choose_token)).setItems(labels, (d, w) -> {
                    sel[0] = tokens.get(w)[0]; sel[1] = tokens.get(w)[1]; sel[2] = tokens.get(w)[2];
                    Language.setText(tokenBtn, Language.msg(l10n_mail_token_balance, sel[1], sel[2]));
                }).show();
            });
            box.addView(tokenBtn);
            box.addView(label(Language.msg(l10n_mail_amount)));
            final EditText amt = input("0.0");
            amt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            box.addView(amt);
            box.addView(label(Language.msg(l10n_mail_their_address)));
            final EditText addr = input(Language.msg(l10n_mail_address_hint));
            if (known != null) Language.setText(addr, known);
            box.addView(addr);
            openPayAddrField = addr; openPayContact = otherKey;   // live-fill when the handshake returns
            TextView hint = new TextView(this);
            Language.setText(hint, known != null ? Language.msg(l10n_mail_autofilled)
                    : Language.msg(l10n_mail_autofill_hint));
            hint.setTextColor(Design.DIM2); hint.setTextSize(11f); hint.setPadding(0, dp(4), 0, 0);
            box.addView(hint);
            TextView scanAddr = textButton(Language.msg(l10n_mail_scan_address));
            scanAddr.setOnClickListener(v -> startAddrScan(addr));
            box.addView(scanAddr);
            if (myPayaddr.isEmpty()) fetchMyPayaddr();
            TextView mine = new TextView(this);
            Language.setText(mine, Language.msg(l10n_mail_you_receive, myPayaddr.isEmpty() ? Language.msg(l10n_mail_getting_address) : shortKey(myPayaddr)));
            mine.setTextColor(Design.DIM2); mine.setTextSize(10f); mine.setPadding(0, dp(8), 0, 0);
            box.addView(mine);
            box.addView(label(Language.msg(l10n_mail_note)));
            final EditText memo = input(Language.msg(l10n_mail_note_hint));
            box.addView(memo);
            ScrollView sv = new ScrollView(this); sv.addView(box);
            AlertDialog dlg = languageDialog().setTitle(Language.msg(l10n_mail_send_to, nameFor(otherKey))).setView(sv)
                    .setPositiveButton(Language.msg(l10n_mail_review), (d, w) ->
                            trySendFunds(otherKey, subject, sel[0], sel[1], sel[2], amt.getText().toString().trim(), addr.getText().toString().trim(), memo.getText().toString()))
                    .setNegativeButton(Language.msg(l10n_mail_cancel), null).create();
            dlg.setOnDismissListener(d -> { modalOpen = false; openPayAddrField = null; openPayContact = null; });
            modalOpen = true;
            dlg.show();
        });
    }

    private void trySendFunds(String otherKey, String subject, String tokenid, String tokenname, String balanceStr, String amountStr, String addr, String memo) {
        if (amountStr.isEmpty()) { toast(Language.msg(l10n_mail_enter_amount)); return; }
        java.math.BigDecimal amount, balance;
        try { amount = new java.math.BigDecimal(amountStr); balance = new java.math.BigDecimal(balanceStr); }
        catch (Exception e) { toast(Language.msg(l10n_mail_invalid_amount)); return; }
        if (amount.signum() <= 0) { toast(Language.msg(l10n_mail_enter_amount)); return; }
        if (amount.compareTo(balance) > 0) { toast(Language.msg(l10n_mail_insufficient)); return; }
        if (!looksLikeMinimaAddress(addr)) {
            if (db.contactPayaddr(otherKey) == null) sendPayaddrReq(otherKey);
            toast(Language.msg(l10n_mail_enter_address, nameFor(otherKey)));
            return;
        }
        final String payaddr = addr.trim();
        db.setContactPayaddr(otherKey, payaddr);   // remember it for next time
        languageDialog()
                .setTitle(Language.msg(l10n_mail_confirm_send, amountStr, tokenname))
                .setMessage(Language.msg(l10n_mail_confirm_funds, nameFor(otherKey), payaddr))
                .setPositiveButton(Language.msg(l10n_mail_send), (d, w) -> doPay(otherKey, subject, payaddr, tokenid, tokenname, amountStr, memo))
                .setNegativeButton(Language.msg(l10n_mail_cancel), null).show();
    }

    /** A real Minima receiving address: Mx… (strict alphanumeric charset, total length 40–80) or 0x +
     *  exactly 64 hex. CRUCIALLY rejects a Mail key (0x + 130 hex) so an identity key can never be a
     *  pay address, AND enforces the Mx charset — a whitespace-tolerant length-only check let a crafted
     *  "Mx… address:0x&lt;attacker&gt;" pass and, once interpolated into `send … address:&lt;x&gt; …`, the node's
     *  space-tokenised last-wins parser redirected the funds. (Mirrors the desktop module's validator.) */
    private static boolean looksLikeMinimaAddress(String a) {
        if (a == null) return false;
        a = a.trim();
        if (a.matches("^Mx[0-9A-Za-z]+$")) return a.length() >= 40 && a.length() <= 80;
        if (a.startsWith("0x")) {
            String h = a.substring(2);
            return h.length() == 64 && h.matches("[0-9A-Fa-f]+");
        }
        return false;
    }

    private void doPay(final String otherKey, final String subject, final String payaddr, final String tokenid, final String tokenname, final String amountStr, final String memo) {
        final AlertDialog progress = progressDialog(Language.msg(l10n_mail_sending, amountStr, tokenname),
                Language.msg(l10n_mail_posting_funds));
        progress.show();
        // Defense-in-depth beyond the charset validator: have the node parse/verify the address before any
        // funds move (same gate as the desktop module). Blocks a charset-valid-but-not-real address.
        node.cmd("checkaddress address:" + payaddr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject chk) {
                if (!chk.optBoolean("status", false)) {
                    safeDismiss(progress);
                    payResult(false, Language.msg(l10n_mail_validate_failed), null);
                    return;
                }
                doPayChecked(otherKey, subject, payaddr, tokenid, tokenname, amountStr, memo, progress);
            }
            @Override public void onError(String m) {
                safeDismiss(progress);
                payResult(false, Language.join(Language.msg(l10n_mail_validate_failed), "\n", MailMessages.message(m)), null);
            }
        });
    }

    private void doPayChecked(final String otherKey, final String subject, String payaddr, final String tokenid, final String tokenname, final String amountStr, final String memo, final AlertDialog progress) {
        // Pinned to a signable coin (SendPin) so shared-node beacon dust can't fail the send after signing starts.
        com.eurobuddha.mail.integratedcomms.SendPin.pin(node,
                "send amount:" + amountStr + " address:" + payaddr + " tokenid:" + tokenid,
                pinned -> node.cmd(pinned, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                boolean ok = j.optBoolean("status", false) || j.optBoolean("pending", false);
                if (!ok) { safeDismiss(progress); payResult(false, Language.msg(l10n_mail_payment_rejected, j.optString("error", "")), null); return; }
                JSONObject r = j.optJSONObject("response");
                final String txid = r != null ? r.optString("txpowid", "") : "";
                // The funds have left — record it locally (shows the bubble) and confirm, then notify the recipient.
                final MailMessage m = paymentMsg(otherKey, subject, tokenid, tokenname, amountStr, memo, txid);
                io.execute(() -> {
                    m.id = db.insert(m);
                    ui.post(() -> {
                        safeDismiss(progress);
                        reloadConversation(m.hashref);
                        payResult(true, Language.msg(l10n_mail_paid, amountStr, tokenname, nameFor(otherKey)), txid);
                    });
                });
                CommsTransport.send(node, crypto, m, new CommsTransport.SendCb() {   // best-effort chat receipt
                    @Override public void onSent() {}
                    @Override public void onFailed(String e) {}
                });
            }
            @Override public void onError(String m) { safeDismiss(progress); payResult(false, Language.msg(l10n_mail_payment_error, MailMessages.message(m)), null); }
        }));
    }

    private MailMessage paymentMsg(String otherKey, String subject, String tokenid, String tokenname, String amountStr, String memo, String txid) {
        MailMessage m = new MailMessage();
        m.type = "payment";
        m.frompublickey = myId; m.fromname = myName; m.topublickey = otherKey;
        m.subject = subject == null ? "" : subject;
        m.message = memo == null ? "" : memo;
        m.amount = amountStr; m.tokenid = tokenid; m.tokenname = tokenname; m.txpowid = txid;
        m.payaddr = myPayaddr;
        m.randomid = MailText.randomId(); m.date = System.currentTimeMillis();
        m.incoming = false; m.read = true; m.status = "sent"; m.sentblock = chainBlock;
        m.hashref = MailText.threadKey(myId, otherKey, m.subject);
        return m;
    }

    private void payResult(boolean ok, CharSequence message, String txid) {
        AlertDialog.Builder b = languageDialog()
                .setTitle(ok ? Language.msg(l10n_mail_payment_sent) : Language.msg(l10n_mail_payment_failed))
                .setMessage(Language.join(message, ok && txid != null && !txid.isEmpty() ? Language.join("\n\n", Language.msg(l10n_mail_tx, shortKey(txid))) : ""))
                .setPositiveButton(Language.msg(l10n_mail_ok), null);
        if (ok && txid != null && !txid.isEmpty()) b.setNeutralButton(Language.msg(l10n_mail_copy_tx), (d, w) -> { copy(txid, Language.msg(l10n_mail_tx_id)); toast(Language.msg(l10n_mail_copied)); });
        b.show();
    }

    private void safeDismiss(AlertDialog d) { try { if (d != null && d.isShowing()) d.dismiss(); } catch (Exception ignored) {} }

    private AlertDialog progressDialog(CharSequence title, CharSequence msg) {
        return languageDialog().setTitle(title).setMessage(msg).setCancelable(false).create();
    }

    private byte[] readBytes(Uri uri) {
        try (java.io.InputStream is = getContentResolver().openInputStream(uri);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) { return null; }
    }

    // ---- images ----

    private void sendImage(final String otherKey, final String subject, final Uri uri) {
        if (crypto == null) { toast(Language.msg(l10n_mail_connect_first)); return; }
        final AlertDialog progress = progressDialog(Language.msg(l10n_mail_sending_image), Language.msg(l10n_mail_compressing));
        progress.show();
        io.execute(() -> {
            try {
                byte[] raw = readBytes(uri);
                MailMessage m = (raw != null && raw.length <= 16000)
                        ? imageMsg(otherKey, subject, raw)                                              // keep original (GIF animated, PNG alpha)
                        : imageMsg(otherKey, subject, Images.compressToFit(MainActivity.this, uri, 15000)); // else shrink to JPEG
                if (m == null) { ui.post(() -> { safeDismiss(progress); toast(Language.msg(l10n_mail_image_unreadable)); }); return; }
                String blob = crypto.seal(otherKey, m.toWire());            // seal ONCE, then size-check + send
                if (blob.length() / 2 > 47000) {                            // too big once sealed → compress harder
                    MailMessage m2 = imageMsg(otherKey, subject, Images.compressToFit(MainActivity.this, uri, 9000));
                    if (m2 != null) { m = m2; blob = crypto.seal(otherKey, m.toWire()); }
                }
                if (blob.length() / 2 > 49000) { ui.post(() -> { safeDismiss(progress); toast(Language.msg(l10n_mail_image_large)); }); return; }
                final MailMessage msg = m;
                msg.id = db.insert(msg);                                     // in the thread + Outbox as "posting"
                final String sealed = blob;
                ui.post(() -> { safeDismiss(progress); reloadConversation(msg.hashref); refreshFolderIfVisible(); });
                CommsTransport.sendBlob(node, sealed, new CommsTransport.SendCb() {
                    @Override public void onSent() { io.execute(() -> {
                        db.setStatus(msg.id, "sent"); db.setSentBlock(msg.id, chainBlock);
                        ui.post(() -> { reloadConversation(msg.hashref); refreshFolderIfVisible(); });
                    }); }
                    @Override public void onFailed(String e) { io.execute(() -> {
                        db.setStatus(msg.id, "failed");
                        ui.post(() -> { toast(Language.msg(l10n_mail_image_error, MailMessages.message(e))); reloadConversation(msg.hashref); refreshFolderIfVisible(); });
                    }); }
                });
            } catch (Throwable t) { ui.post(() -> { safeDismiss(progress); toast(Language.msg(l10n_mail_image_failed)); }); }
        });
    }

    private MailMessage imageMsg(String otherKey, String subject, byte[] jpeg) {
        if (jpeg == null) return null;
        MailMessage m = new MailMessage();
        m.type = "image";
        m.image = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
        m.frompublickey = myId; m.fromname = myName; m.topublickey = otherKey;
        m.subject = subject == null ? "" : subject;
        m.message = ""; m.payaddr = myPayaddr;
        m.randomid = MailText.randomId(); m.date = System.currentTimeMillis();
        m.incoming = false; m.read = true; m.status = "posting"; m.sentblock = chainBlock;
        m.hashref = MailText.threadKey(myId, otherKey, m.subject);
        return m;
    }

    private View imageBubble(final MailMessage m) {
        final ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.62));
        iv.setMaxHeight(dp(300));
        iv.setMinimumWidth(dp(120)); iv.setMinimumHeight(dp(120));
        iv.setBackground(Design.roundBg(this, m.incoming ? Design.SURFACE2 : Design.ACCENT, 14));
        int p = dp(3); iv.setPadding(p, p, p, p);
        iv.setOnClickListener(v -> showFullImage(m.image));

        Bitmap cached = imgCache.get(m.randomid);
        if (cached != null) { iv.setImageBitmap(cached); return iv; }   // hit → no decode

        io.execute(() -> {                                              // decode off the UI thread
            try {
                byte[] bytes = android.util.Base64.decode(m.image, android.util.Base64.NO_WRAP);
                if (isGif(bytes)) {                                     // animated → decode fresh, don't cache
                    Drawable d = decodeImage(m.image);
                    if (d != null) ui.post(() -> {
                        iv.setImageDrawable(d);
                        if (d instanceof android.graphics.drawable.AnimatedImageDrawable) ((android.graphics.drawable.AnimatedImageDrawable) d).start();
                    });
                } else {
                    Bitmap b = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (b != null) { imgCache.put(m.randomid, b); ui.post(() -> iv.setImageBitmap(b)); }
                }
            } catch (Exception ignored) {}
        });
        return iv;
    }

    private static boolean isGif(byte[] b) { return b != null && b.length > 3 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F'; }

    private void showFullImage(String b64) {
        Drawable d = decodeImage(b64);
        if (d == null) return;
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(d);
        if (d instanceof android.graphics.drawable.AnimatedImageDrawable) ((android.graphics.drawable.AnimatedImageDrawable) d).start();
        iv.setAdjustViewBounds(true);
        ScrollView sv = new ScrollView(this); sv.addView(iv);
        languageDialog().setView(sv).setPositiveButton(Language.msg(l10n_mail_close), null).show();
    }

    /** Decode a base64 image to a Drawable — animates GIFs, keeps PNG alpha, falls back to a bitmap. */
    private Drawable decodeImage(String b64) {
        try {
            byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
            try {
                android.graphics.ImageDecoder.Source src = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes));
                return android.graphics.ImageDecoder.decodeDrawable(src);
            } catch (Throwable t) {
                Bitmap b = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                return b != null ? new android.graphics.drawable.BitmapDrawable(getResources(), b) : null;
            }
        } catch (Exception e) { return null; }
    }

    private void sendPayaddrReq(String otherKey) {
        if (crypto == null) return;
        MailMessage r = new MailMessage();
        r.type = "payaddr-req";
        r.frompublickey = myId; r.topublickey = otherKey; r.payaddr = myPayaddr;
        r.message = ""; r.randomid = MailText.randomId(); r.date = System.currentTimeMillis();
        CommsTransport.send(node, crypto, r, new CommsTransport.SendCb() {
            @Override public void onSent() {} @Override public void onFailed(String m) {}
        });
    }

    private void loadTokens(final java.util.function.Consumer<List<String[]>> cb) {
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                List<String[]> out = new ArrayList<>();
                org.json.JSONArray arr = j.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.optJSONObject(i);
                    if (t == null) continue;
                    String tid = t.optString("tokenid", "0x00");
                    String conf = t.optString("confirmed", "0");
                    try { if (new java.math.BigDecimal(conf).signum() <= 0) continue; } catch (Exception e) { continue; }
                    String name;
                    Object tok = t.opt("token");
                    if (tok instanceof JSONObject) name = ((JSONObject) tok).optString("name", shortKey(tid));
                    else name = (tok == null) ? shortKey(tid) : tok.toString();
                    if ("0x00".equals(tid)) name = "Minima";
                    out.add(new String[]{tid, name, conf});
                }
                cb.accept(out);
            }
            @Override public void onError(String m) { cb.accept(new ArrayList<>()); }
        });
    }

    // ---- QR scan ----

    private void startScan(EditText target) { launchScan(target, false); }
    private void startAddrScan(EditText target) { launchScan(target, true); }

    private void launchScan(EditText target, boolean isAddress) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.CAMERA}, 2);
        }
        pendingScanTarget = target;
        pendingScanIsAddress = isAddress;
        ScanOptions o = new ScanOptions();
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        o.setPrompt((isAddress ? Language.msg(l10n_mail_scan_mail_address) : Language.msg(l10n_mail_scan_mail_key)).toString());
        o.setBeepEnabled(false);
        o.setOrientationLocked(false);
        scanLauncher.launch(o);
    }

    // ---- backup ----

    private void promptBackupPass(Uri uri, boolean export) {
        final EditText p = input(Language.msg(l10n_mail_passphrase));
        p.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog secureBackup = languageDialog()
                .setTitle(export ? Language.msg(l10n_mail_encrypt_backup) : Language.msg(l10n_mail_decrypt_backup))
                .setMessage(export ? Language.msg(l10n_mail_backup_help)
                        : Language.msg(l10n_mail_restore_help))
                .setView(p)
                .setPositiveButton(export ? Language.msg(l10n_mail_back_up) : Language.msg(l10n_mail_restore), (d, w) -> {
                    String pass = p.getText().toString();
                    if (pass.length() < 8) { toast(Language.msg(l10n_mail_pass_length)); return; }
                    if (export) doExport(uri, pass); else doImport(uri, pass);
                })
                .setNegativeButton(Language.msg(l10n_mail_cancel), null).create();
        secureBackup.show();
        secureBackup.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void doExport(Uri uri, String pass) {
        io.execute(() -> {
            try {
                JSONObject root = new JSONObject();
                JSONObject id = new JSONObject();
                id.put("boxPk", Hex.to(identity.boxPk)); id.put("boxSk", Hex.to(identity.boxSk));
                id.put("signPk", Hex.to(identity.signPk)); id.put("signSk", Hex.to(identity.signSk));
                root.put("identity", id);
                root.put("name", myName);
                JSONArray cons = new JSONArray();
                for (String[] c : db.contacts()) { JSONObject o = new JSONObject(); o.put("name", c[1]); o.put("key", c[2]); cons.put(o); }
                root.put("contacts", cons);
                JSONArray msgs = new JSONArray();
                for (MailMessage t : db.threads())
                    for (MailMessage m : db.thread(t.hashref)) {
                        JSONObject o = new JSONObject();
                        o.put("hashref", m.hashref); o.put("fromname", m.fromname); o.put("from", m.frompublickey);
                        o.put("to", m.topublickey); o.put("subject", m.subject == null ? "" : m.subject);
                        o.put("message", m.message); o.put("randomid", m.randomid);
                        o.put("incoming", m.incoming); o.put("read", m.read); o.put("date", m.date); o.put("status", m.status);
                        o.put("type", m.type == null ? "text" : m.type);
                        if ("payment".equals(m.type)) {
                            o.put("amount", m.amount); o.put("tokenid", m.tokenid);
                            o.put("tokenname", m.tokenname); o.put("txpowid", m.txpowid);
                        }
                        if ("image".equals(m.type)) o.put("image", m.image == null ? "" : m.image);
                        msgs.put(o);
                    }
                root.put("messages", msgs);
                String enc = BackupCrypto.encrypt(pass, root.toString().getBytes(StandardCharsets.UTF_8));
                try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(enc.getBytes(StandardCharsets.UTF_8)); }
                ui.post(() -> toast(Language.msg(l10n_mail_backed_up)));
            } catch (Exception e) { ui.post(() -> toast(Language.msg(l10n_mail_backup_error, MailMessages.error(e)))); }
        });
    }

    private void doImport(Uri uri, String pass) {
        io.execute(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
                    String line; while ((line = r.readLine()) != null) sb.append(line);
                }
                byte[] plain = BackupCrypto.decrypt(pass, sb.toString());
                JSONObject root = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                JSONObject id = root.getJSONObject("identity");
                CommsIdentity restored = IdentityRecord.decode(id.toString());
                if (identityStore == null) identityStore = new IdentityStore(this);
                String restoredName = root.optString("name", "");
                android.database.sqlite.SQLiteDatabase restoreDb = db.getWritableDatabase();
                restoreDb.beginTransaction();
                try {
                db.setMeta("myname", restoredName);
                JSONArray cons = root.optJSONArray("contacts");
                if (cons != null) for (int i = 0; i < cons.length(); i++) { JSONObject o = cons.getJSONObject(i); db.addContact(o.optString("name"), o.optString("key")); }
                JSONArray msgs = root.optJSONArray("messages");
                if (msgs != null) for (int i = 0; i < msgs.length(); i++) {
                    JSONObject o = msgs.getJSONObject(i);
                    MailMessage m = new MailMessage();
                    m.hashref = o.optString("hashref"); m.fromname = o.optString("fromname");
                    m.frompublickey = o.optString("from"); m.topublickey = o.optString("to");
                    m.subject = o.optString("subject", "");
                    m.message = o.optString("message"); m.randomid = o.optString("randomid");
                    m.incoming = o.optBoolean("incoming"); m.read = o.optBoolean("read"); m.date = o.optLong("date"); m.status = o.optString("status", "");
                    m.type = o.optString("type", "text");
                    m.amount = o.optString("amount", ""); m.tokenid = o.optString("tokenid", "");
                    m.tokenname = o.optString("tokenname", ""); m.txpowid = o.optString("txpowid", "");
                    m.image = o.optString("image", "");
                    db.insert(m);
                }
                restoreDb.setTransactionSuccessful();
                } finally { restoreDb.endTransaction(); }
                // A failed import must never replace the identity used on the next launch.
                identityStore.save(restored);
                ui.post(() -> { if (isFinishing() || isDestroyed()) return; myName = restoredName; contactNames = null; adoptIdentity(restored); toast(Language.msg(l10n_mail_restored)); showFolder(Folder.INBOX); requestScan(); });
            } catch (Exception e) { ui.post(() -> toast(Language.msg(l10n_mail_restore_failed))); }
        });
    }

    // ---- dialogs ----

    private void addContactDialog(String prefillKey) {
        LinearLayout box = pad(column());
        final EditText name = input(Language.msg(l10n_mail_name));
        final EditText key = input(Language.msg(l10n_mail_their_key_hint));
        if (prefillKey != null) Language.setText(key, prefillKey);
        LinearLayout scan = new LinearLayout(this);
        scan.setOrientation(LinearLayout.HORIZONTAL);
        scan.setGravity(Gravity.CENTER_VERTICAL);
        scan.setPadding(dp(4), dp(10), dp(8), dp(6));
        scan.addView(icon(R.drawable.pm_mail_ic_scan, Design.ACCENT, 14));
        TextView stext = new TextView(this);
        Language.setText(stext, Language.msg(l10n_mail_scan_qr)); stext.setTextColor(Design.ACCENT_TEXT); stext.setTextSize(13f);
        stext.setPadding(dp(6), 0, 0, 0);
        scan.addView(stext);
        scan.setOnClickListener(v -> startScan(key));
        box.addView(label(Language.msg(l10n_mail_name))); box.addView(name);
        box.addView(label(Language.msg(l10n_mail_mail_key))); box.addView(key); box.addView(scan);
        languageDialog().setTitle(Language.msg(l10n_mail_add_contact)).setView(box)
                .setPositiveButton(Language.msg(l10n_mail_add), (d, w) -> {
                    String n = name.getText().toString().trim(), k = acceptKeyShare(key.getText().toString());
                    if (n.isEmpty() || !CommsIdentity.isValidPublicId(k)) { toast(Language.msg(l10n_mail_contact_invalid)); return; }
                    io.execute(() -> { db.addContact(n, k); ui.post(() -> { contactNames = null; refreshFolderIfVisible(); }); });
                })
                .setNegativeButton(Language.msg(l10n_mail_cancel), null).show();
    }

    private void pickContact(EditText target) {
        List<String[]> cs = db.contacts();
        if (cs.isEmpty()) { toast(Language.msg(l10n_mail_no_contacts)); return; }
        String[] names = new String[cs.size()];
        for (int i = 0; i < cs.size(); i++) names[i] = cs.get(i)[1];
        languageDialog().setTitle(Language.msg(l10n_mail_pick_contact)).setItems(names, (d, which) -> Language.setText(target, cs.get(which)[2])).show();
    }

    // ---- notifications ----

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CH, Language.msg(l10n_mail_new_mail), NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void notifyNew(int count) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
            android.app.Notification n = new androidx.core.app.NotificationCompat.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle("Minima Mail")
                    .setContentText(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_mail_new_messages, count))
                    .setAutoCancel(true).build();
            NotificationManagerCompat.from(this).notify("pandamonium.mail", 42, n);
        } catch (Exception ignored) {}
    }

    // ---- view helpers ----

    private LinearLayout buildPairingBanner() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackgroundColor(Design.SURFACE);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView t1 = new TextView(this); Language.setText(t1, Language.msg(l10n_mail_node_not_ready)); t1.setTextColor(Design.ACCENT_TEXT); t1.setTextSize(15f); t1.setTypeface(Typeface.DEFAULT_BOLD);
        TextView t2 = new TextView(this); Language.setText(t2, Language.msg(l10n_mail_node_retry)); t2.setTextColor(Design.DIM); t2.setTextSize(13f); t2.setPadding(0, dp(4), 0, dp(8));
        TextView open = accentButton(Language.msg(l10n_mail_open_core));
        open.setOnClickListener(v -> { Intent i = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.pandamonium");
        if (i == null) i = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.minimablock");
        if (i == null) i = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.pandamonium"); if (i != null) startActivity(i); else toast(Language.msg(l10n_mail_no_core)); });
        b.addView(t1); b.addView(t2); b.addView(open);
        return b;
    }

    private FrameLayout avatar(String key, CharSequence name, int sizeDp) { return Avatars.view(this, key, name.toString(), sizeDp); }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout pad(LinearLayout l) { int p = dp(16); l.setPadding(p, p, p, p); return l; }

    /** Folder header: hamburger (root folders) or back (pushed screens) + title. */
    private LinearLayout header(CharSequence title, boolean back) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(Design.SURFACE);
        h.setPadding(dp(8), dp(12), dp(8), dp(12));
        if (back) {
            h.addView(iconBtn(R.drawable.pm_mail_ic_back, v -> pop()));
        } else {
            ImageView burger = iconBtn(R.drawable.pm_mail_ic_menu, v -> drawer.openDrawer(androidx.core.view.GravityCompat.START));
            h.addView(burger);
        }
        TextView t = new TextView(this);
        Language.setText(t, title); t.setTextColor(Design.TEXT); t.setTextSize(19f); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(8), 0, 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        h.addView(t);
        return h;
    }

    /** A tinted vector icon. */
    private ImageView icon(int res, int tint, int sizeDp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    /** A padded, tappable icon button for headers/bars. */
    private ImageView iconBtn(int res, View.OnClickListener click) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(Design.DIM);
        int p = dp(10);
        iv.setPadding(p, p, p, p);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        int description = res == R.drawable.pm_mail_ic_back ? l10n_mail_back
                : res == R.drawable.pm_mail_ic_menu ? l10n_mail_menu
                : res == R.drawable.pm_mail_ic_dots ? l10n_mail_more
                : res == R.drawable.pm_mail_ic_plus ? l10n_mail_attach : l10n_mail_close;
        Language.description(iv, Language.msg(description));
        if (click != null) iv.setOnClickListener(click);
        return iv;
    }

    private TextView label(CharSequence s) {
        TextView t = new TextView(this);
        Language.setText(t, s); t.setTextColor(Design.DIM); t.setTextSize(12f);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private EditText input(CharSequence hint) {
        EditText e = new EditText(this);
        Language.hint(e, hint); e.setHintTextColor(Design.DIM2); e.setTextColor(Design.TEXT); e.setTextSize(14f);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setBackground(Design.cardBg(this, Design.SURFACE, 10));
        int p = dp(12); e.setPadding(p, p, p, p);
        return e;
    }

    private TextView accentButton(CharSequence s) {
        TextView b = new TextView(this);
        Language.setText(b, s); b.setGravity(Gravity.CENTER);
        b.setTextColor(Design.ON_ACCENT); b.setTextSize(15f); b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(Design.roundBg(this, Design.ACCENT, 12));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        return b;
    }

    private TextView textButton(CharSequence s) {
        TextView b = new TextView(this);
        Language.setText(b, s); b.setTextColor(Design.ACCENT_TEXT); b.setTextSize(14f);
        b.setPadding(dp(8), dp(12), dp(16), dp(12));
        return b;
    }

    private TextView accentOutlineButton(CharSequence s) {
        TextView b = new TextView(this);
        Language.setText(b, s); b.setTextColor(Design.ACCENT_TEXT); b.setTextSize(14f);
        b.setBackground(Design.outlineBg(this, Design.ACCENT, 10));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }

    // ---- formatting ----

    private CharSequence nameFor(String key) {
        if (key == null) return Language.msg(l10n_mail_unknown);
        if (contactNames == null) {
            java.util.HashMap<String, String> m = new java.util.HashMap<>();
            for (String[] c : db.contacts()) m.put(c[2], c[1]);
            contactNames = m;
        }
        String n = contactNames.get(key);
        return n != null ? n : shortKey(key);
    }

    private static String shortKey(String key) {
        if (key == null) return "—";
        String h = key.startsWith("0x") ? key.substring(2) : key;
        if (h.length() <= 14) return key;
        return "0x" + h.substring(0, 8) + "…" + h.substring(h.length() - 6);
    }

    private CharSequence previewText(MailMessage t) {
        if ("image".equals(t.type)) return Language.msg(l10n_mail_photo);
        if ("payment".equals(t.type)) return t.amount + " " + (t.tokenname == null || t.tokenname.isEmpty() ? "MINIMA" : t.tokenname)
                + (t.message == null || t.message.isEmpty() ? "" : " — " + oneLine(t.message));
        return oneLine(t.message);
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 64 ? s.substring(0, 64) + "…" : s;
    }

    private static boolean sameDay(long a, long b) {
        java.util.Calendar ca = java.util.Calendar.getInstance(); ca.setTimeInMillis(a);
        java.util.Calendar cb = java.util.Calendar.getInstance(); cb.setTimeInMillis(b);
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR)
                && ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private static CharSequence clock(long ms) { return Language.stamp(ms, "Hm"); }
    private static CharSequence dateLabel(long ms) {
        long now = System.currentTimeMillis();
        if (sameDay(ms, now)) return Language.msg(l10n_mail_today);
        java.util.Calendar yesterday = java.util.Calendar.getInstance();
        yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (sameDay(ms, yesterday.getTimeInMillis())) return Language.msg(l10n_mail_yesterday);
        return Language.stamp(ms, "yMMMd");
    }
    private static CharSequence rel(long ms) {
        if (ms <= 0) return "";
        long age = System.currentTimeMillis() - ms;
        if (age < 3600000) return Language.since(ms);
        if (age < 86400000) return clock(ms);
        if (age < 7 * 86400000L) return Language.since(ms);
        return Language.stamp(ms, "MMMd");
    }

    private int dp(int v) { return Design.dp(this, v); }

    private void copy(String text, CharSequence label) {
        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(label, text));
    }

    private void toast(CharSequence s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override protected void onStart() {
        super.onStart();
        freshnessVisible = true;
        ui.removeCallbacks(freshTick);
        freshTick.run();
        DirectNodeEvents.subscribe(notifyReceiver, "NEWBLOCK", "NEWBALANCE");
    }
    @Override protected void onStop() {
        freshnessVisible = false;
        ui.removeCallbacks(freshTick);
        DirectNodeEvents.unsubscribe(notifyReceiver);
        super.onStop();
    }
}
