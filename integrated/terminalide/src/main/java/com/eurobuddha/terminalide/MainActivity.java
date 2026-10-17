package com.eurobuddha.terminalide;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import android.content.ActivityNotFoundException;
import com.eurobuddha.minimaapi.direct.DirectNodeEvents;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager.widget.ViewPager;

import com.eurobuddha.terminalide.terminal.SessionExport;
import com.eurobuddha.terminalide.terminal.TerminalView;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONObject;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;

public class MainActivity extends AppCompatActivity {

    private CharSequence mNodeSubtitle = "";
    private final java.util.Set<AlertDialog> languageDialogs = new java.util.HashSet<>();
    public LanguageDialog languageDialog() {
        return new LanguageDialog(this) {
            @Override public AlertDialog create() {
                AlertDialog dialog = super.create();
                languageDialogs.add(dialog);
                dialog.setOnDismissListener(d -> languageDialogs.remove(dialog));
                return dialog;
            }
        };
    }
    private void setNodeSubtitle(CharSequence text) {
        mNodeSubtitle = text;
        mToolbar.setSubtitle(text);
    }
    private void refreshLanguage() {
        if (mToolbar == null) return;
        mToolbar.setSubtitle(mNodeSubtitle);
        Menu menu = mToolbar.getMenu();
        if (menu.findItem(MENU_SELECT) != null) menu.findItem(MENU_SELECT).setTitle((mAdapter != null && mAdapter.getTerminalView().isSelectionMode() ? Language.msg(l10n_terminal_ide_exit_select) : Language.msg(l10n_terminal_ide_select_output)));
        if (menu.findItem(MENU_COPYALL) != null) menu.findItem(MENU_COPYALL).setTitle(Language.msg(l10n_terminal_ide_copy_output));
        if (menu.findItem(MENU_EXPORT) != null) menu.findItem(MENU_EXPORT).setTitle(Language.msg(l10n_terminal_ide_export_session));
        if (menu.findItem(MENU_CLEAR) != null) menu.findItem(MENU_CLEAR).setTitle(Language.msg(l10n_terminal_ide_clear));
        if (menu.findItem(MENU_DOCS) != null) menu.findItem(MENU_DOCS).setTitle(Language.msg(l10n_terminal_ide_docs));
        if (menu.findItem(MENU_ABOUT) != null) menu.findItem(MENU_ABOUT).setTitle(Language.msg(l10n_terminal_ide_about));
        TabLayout tabs = findViewById(R.id.pm_terminalide_tabs);
        CharSequence[] titles = {Language.msg(l10n_tab_terminal), Language.msg(l10n_logs_scripts), Language.msg(l10n_terminal_ide_txn), Language.msg(l10n_menu_logs)};
        for (int i=0;i<titles.length;i++) if (tabs.getTabAt(i) != null) tabs.getTabAt(i).setText(titles[i]);
        com.eurobuddha.terminalide.terminal.TerminalLabel.refreshTree(findViewById(android.R.id.content));
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        refreshLanguage();
    }

    private static final int MENU_CLEAR   = 1;
    private static final int MENU_ABOUT   = 2;
    private static final int MENU_EXPORT  = 3;
    private static final int MENU_DOCS    = 4;
    private static final int MENU_SELECT  = 5;
    private static final int MENU_COPYALL = 6;

    ViewPager mMainPager;
    TerminalAdapter mAdapter;
    NodeApi mNode;

    Toolbar mToolbar;
    TextView mPairBanner;

    /** Snapshot handed to the file picker; consumed by the launcher callback. */
    private String mPendingExport;
    private ActivityResultLauncher<Intent> mSaveLauncher;

    /** Live NEWBLOCK listener so the toolbar block height updates without polling. */
    private final DirectNodeEvents.Listener mNotifyReceiver = new DirectNodeEvents.Listener() {
        @Override
        public void onEvent(JSONObject nodeEvent) {
            try {
                JSONObject json = nodeEvent;
                if ("NEWBLOCK".equals(json.optString("event"))) {
                    String block = json.getJSONObject("data").getJSONObject("txpow")
                            .getJSONObject("header").optString("block", "");
                    if (!block.isEmpty()) setBlock(block);
                }
            } catch (Exception ignored) {}
        }
    };
    private boolean mReceiverRegistered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.pm_terminalide_activity_main);

        // NO EdgeToEdge.enable() — matching the proven sibling apps (utxo/mail/wallet).
        // On Android 14 the window then genuinely resizes for the keyboard (adjustResize);
        // on Android 15 edge-to-edge is enforced and this listener pads for bars + IME.
        View root = findViewById(R.id.pm_terminalide_main);
        Language.apply(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right,
                    Math.max(systemBars.bottom, ime.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);

        mToolbar = findViewById(R.id.pm_terminalide_toolbar);
        mToolbar.setTitle("Terminal IDE");
        setSupportActionBar(mToolbar);

        mPairBanner = findViewById(R.id.pm_terminalide_pair_banner);
        mPairBanner.setOnClickListener(v -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.pandamonium");
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.minimablock");
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.pandamonium");
            if (launch != null) startActivity(launch);
        });

        // Save-to-file goes through the Storage Access Framework: no permissions,
        // and no size limit (unlike a clipboard write or an EXTRA_TEXT share).
        mSaveLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
            String text = mPendingExport;
            mPendingExport = null;
            Uri uri = result.getData() == null ? null : result.getData().getData();
            if (result.getResultCode() != RESULT_OK || uri == null || text == null) return;
            try {
                SessionExport.writeTo(this, uri, text);
                Toast.makeText(this, Language.msg(l10n_terminal_ide_session_saved), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, Language.msg(l10n_terminal_ide_save_failed, com.eurobuddha.terminalide.terminal.TerminalMessages.error(e)), Toast.LENGTH_LONG).show();
            }
        });

        mAdapter = new TerminalAdapter(this);
        mMainPager = findViewById(R.id.pm_terminalide_main_pager);
        mMainPager.setOffscreenPageLimit(3);
        mMainPager.setAdapter(mAdapter);

        TabLayout tabs = findViewById(R.id.pm_terminalide_tabs);
        tabs.setupWithViewPager(mMainPager);
        CharSequence[] titles = {Language.msg(l10n_tab_terminal), Language.msg(l10n_logs_scripts), Language.msg(l10n_terminal_ide_txn), Language.msg(l10n_menu_logs)};
        for (int i = 0; i < titles.length; i++) {
            TabLayout.Tab t = tabs.getTabAt(i);
            if (t != null) t.setText(titles[i]);
        }
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                // Select mode locks out pager swipes — never leave it armed off-tab.
                if (tab.getPosition() != 0 && mAdapter.getTerminalView().isSelectionMode()) {
                    mAdapter.getTerminalView().setSelectionMode(false);
                    invalidateOptionsMenu();
                }
                mAdapter.refreshPagerView(tab.getPosition());
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) { mAdapter.refreshPagerView(tab.getPosition()); }
        });

        // One NodeApi for the whole activity; pairing state drives the banner.
        mNode = new NodeApi(this, enabled -> {
            mPairBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
            if (enabled) fetchBlock();
        });
        mAdapter.getTerminalView().setNodeApi(mNode);
        mAdapter.getTerminalView().setExportAction(this::showExportDialog);
        mAdapter.getTxnView().setNodeApi(mNode);
        mAdapter.getScriptsView().setNodeApi(mNode);

        // Subscribed only while this screen is visible.
        mReceiverRegistered = true;

        fetchBlock();
    }

    private void fetchBlock() {
        mNode.cmd("block", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                if (resp != null) setBlock(resp.optString("block", ""));
            }
            @Override
            public void onError(String message) {
                if (!NodeApi.ERR_NOT_ENABLED.equals(message)) {
                    setNodeSubtitle(Language.msg(l10n_terminal_ide_offline));
                }
            }
        });
    }

    private void setBlock(String block) {
        if (!block.isEmpty()) setNodeSubtitle(Language.msg(l10n_terminal_ide_block, block));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_SELECT, 0, Language.msg(l10n_terminal_ide_select_output));
        menu.add(0, MENU_COPYALL, 1, Language.msg(l10n_terminal_ide_copy_output));
        menu.add(0, MENU_EXPORT, 2, Language.msg(l10n_terminal_ide_export_session));
        menu.add(0, MENU_CLEAR, 3, Language.msg(l10n_terminal_ide_clear));
        menu.add(0, MENU_DOCS, 4, Language.msg(l10n_terminal_ide_docs));
        menu.add(0, MENU_ABOUT, 5, Language.msg(l10n_terminal_ide_about));
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem select = menu.findItem(MENU_SELECT);
        if (select != null && mAdapter != null) {
            select.setTitle(mAdapter.getTerminalView().isSelectionMode()
                    ? Language.msg(l10n_terminal_ide_exit_select) : Language.msg(l10n_terminal_ide_select_output));
        }
        return super.onPrepareOptionsMenu(menu);
    }

    /** Copy / save / share the whole terminal session. */
    private void showExportDialog() {
        final String text = mAdapter.getTerminalView().exportText();
        if (text.trim().isEmpty()) {
            Toast.makeText(this, Language.msg(l10n_terminal_ide_nothing_export), Toast.LENGTH_SHORT).show();
            return;
        }
        final String filename = SessionExport.timestampedName("minima-terminal");
        CharSequence[] actions = {Language.msg(l10n_terminal_ide_save_file), Language.msg(l10n_terminal_ide_share), Language.msg(l10n_terminal_ide_copy_clipboard)};
        languageDialog()
                .setTitle(Language.msg(l10n_terminal_ide_export_title, SessionExport.utf8Len(text) / 1024))
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        mPendingExport = text;
                        try {
                            mSaveLauncher.launch(SessionExport.createDocumentIntent(filename));
                        } catch (ActivityNotFoundException e) {
                            mPendingExport = null;
                            Toast.makeText(this, Language.msg(l10n_terminal_ide_no_picker),
                                    Toast.LENGTH_LONG).show();
                        }
                    } else if (which == 1) {
                        SessionExport.share(this, text, filename, Language.msg(l10n_terminal_ide_share_session));
                    } else {
                        SessionExport.copy(this, text, "Minima terminal session");
                    }
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        TerminalView terminal = mAdapter.getTerminalView();
        if (item.getItemId() == MENU_CLEAR) {
            terminal.clearTerminal();
            return true;
        }
        if (item.getItemId() == MENU_SELECT) {
            mMainPager.setCurrentItem(0);
            terminal.setSelectionMode(!terminal.isSelectionMode());
            invalidateOptionsMenu();
            return true;
        }
        if (item.getItemId() == MENU_COPYALL) {
            terminal.copyAll();
            return true;
        }
        if (item.getItemId() == MENU_EXPORT) {
            showExportDialog();
            return true;
        }
        if (item.getItemId() == MENU_DOCS) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://docs.minima.global")));
            return true;
        }
        if (item.getItemId() == MENU_ABOUT) {
            languageDialog()
                    .setTitle("Terminal IDE")
                    .setMessage(Language.msg(l10n_terminal_ide_about_body))
                    .setPositiveButton(Language.msg(l10n_common_ok), null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLanguage();
        fetchBlock();
    }

    @Override
    protected void onDestroy() {
        for (AlertDialog dialog : new java.util.ArrayList<>(languageDialogs)) dialog.dismiss();
        languageDialogs.clear();
        super.onDestroy();
        if (mReceiverRegistered) DirectNodeEvents.unsubscribe(mNotifyReceiver);
        if (mNode != null) mNode.onDestroy();
        if (mAdapter != null) mAdapter.onDestroy();
    }

    @Override protected void onStart() {
        super.onStart();
        DirectNodeEvents.subscribe(mNotifyReceiver, "NEWBLOCK", "NEWBALANCE");
    }
    @Override protected void onStop() {
        DirectNodeEvents.unsubscribe(mNotifyReceiver);
        super.onStop();
    }
}
