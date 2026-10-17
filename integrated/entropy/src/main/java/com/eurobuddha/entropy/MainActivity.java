package com.eurobuddha.entropy;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;

import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private final Handler main = new Handler(Looper.getMainLooper());

    private NodeApi node;
    private RollEngine engine;
    private DiceView diceView;
    private LastWordView lastWordView;
    private String selfTestErr = "not run";
    private volatile boolean engineReady = false;
    private boolean demoMode = false;   // session-only, never persisted

    public RollEngine engine() { return engine; }
    public String selfTestError() { return selfTestErr; }
    public boolean engineReady() { return engineReady; }
    public boolean demoMode() { return demoMode; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Seed phrases on screen: never allow screenshots or task-switcher previews.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.pm_entropy_activity_main);

        // edge-to-edge: keep content clear of the status/navigation bars (casino pattern)
        android.view.View root = findViewById(R.id.pm_entropy_root);
        Language.apply(root);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        Theme.load(this);
        Sfx.init(this);
        TextView btnSound = findViewById(R.id.pm_entropy_btnSound);
        Language.setText(btnSound, Theme.sound() ? "🔊" : "🔇");
        btnSound.setOnClickListener(v -> {
            Theme.setSound(this, !Theme.sound());
            Language.setText(btnSound, Theme.sound() ? "🔊" : "🔇");
            if (Theme.sound()) Sfx.click();
        });

        // Demo mode: temporarily lifts FLAG_SECURE so the screen can be mirrored/captured
        // for presentations. Deliberately NOT persisted — every launch starts protected —
        // and a loud banner stays up the whole time it is on.
        TextView btnDemo = findViewById(R.id.pm_entropy_btnDemo);
        TextView demoBanner = findViewById(R.id.pm_entropy_demoBanner);
        btnDemo.setOnClickListener(v -> {
            demoMode = !demoMode;
            if (demoMode) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                // the screen is now capturable — re-blur anything sensitive already on show
                if (diceView != null) diceView.hideWords();
                if (lastWordView != null) lastWordView.hideAll();
            } else {
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
            }
            demoBanner.setVisibility(demoMode ? android.view.View.VISIBLE : android.view.View.GONE);
            btnDemo.setAlpha(demoMode ? 1f : 0.55f);
            Language.setText(btnDemo, demoMode ? "🔓" : "🎥");
        });
        try {
            Bip39.init(this);
            selfTestErr = Bip39.selfTest();
        } catch (Exception e) {
            selfTestErr = "init failed: " + e.getMessage();
        }

        // node IPC to the separately installed Minima Core app
        node = new NodeApi(this, enabled -> {
            // the banner is repurposed for a self-test failure — never let pairing hide that
            if (selfTestErr == null)
                findViewById(R.id.pm_entropy_pairingBanner).setVisibility(enabled ? android.view.View.GONE : android.view.View.VISIBLE);
            if (enabled) verifyNode();
        });
        engine = new RollEngine(node);

        diceView = new DiceView(this);
        lastWordView = new LastWordView(this);

        TabLayout tabs = findViewById(R.id.pm_entropy_tabs);
        ViewPager pager = findViewById(R.id.pm_entropy_pager);
        MainPager adapter = new MainPager(
                new BaseView[]{diceView, lastWordView},
                new CharSequence[]{Language.msg(l10n_entropy_dice_tab), Language.msg(l10n_entropy_word_tab)});
        pager.setAdapter(adapter);
        pager.setOffscreenPageLimit(2);
        tabs.setupWithViewPager(pager);
        // secret hygiene on tab switch: re-blur whatever the leaving tab had on show
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override public void onPageSelected(int position) {
                diceView.hideWords();
                lastWordView.hideAll();
            }
        });

        if (selfTestErr != null) {
            TextView banner = findViewById(R.id.pm_entropy_pairingBanner);
            Language.setText(banner, Language.msg(l10n_entropy_self_banner, EntropyMessages.message(selfTestErr)));
            banner.setVisibility(android.view.View.VISIBLE);
            diceView.setSourceLine(Language.msg(l10n_entropy_self_failed), false);
        } else {
            diceView.setSourceLine(Language.msg(l10n_entropy_connecting), true);
        }

        // never leave rolls gated forever if the node app is absent
        main.postDelayed(() -> {
            if (!engineReady) {
                engineReady = true;
                engine.setNodeOk(false);
                if (selfTestErr == null)
                    diceView.setSourceLine(Language.msg(l10n_entropy_unavailable), false);
            }
        }, 5000);
    }

    /** Prove the node answers `random` and `hash` with well-formed values before any roll uses it. */
    private void verifyNode() {
        node.cmd("random", new NodeApi.Cb() {
            @Override public void onResult(JSONObject r) {
                String a = r != null && r.optJSONObject("response") != null
                        ? r.optJSONObject("response").optString("random", "") : "";
                if (!a.startsWith("0x")) { failVerify(); return; }
                node.cmd("hash data:" + a, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject h) {
                        String hash = h != null && h.optJSONObject("response") != null
                                ? h.optJSONObject("response").optString("hash", "") : "";
                        boolean ok = hash.startsWith("0x") && hash.length() >= 10;
                        engine.setNodeOk(ok);
                        engineReady = true;
                        if (selfTestErr == null)
                            diceView.setSourceLine(ok
                                    ? Language.msg(l10n_entropy_verified)
                                    : Language.msg(l10n_entropy_malformed), ok);
                    }
                    @Override public void onError(String m) { failVerify(); }
                });
            }
            @Override public void onError(String m) { failVerify(); }
        });
    }

    private void refreshLanguage() {
        TabLayout tabs = findViewById(R.id.pm_entropy_tabs);
        if (tabs == null) return;
        CharSequence[] titles = {Language.msg(l10n_entropy_dice_tab), Language.msg(l10n_entropy_word_tab)};
        for (int i=0;i<titles.length;i++) if (tabs.getTabAt(i) != null) tabs.getTabAt(i).setText(titles[i]);
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        refreshLanguage();
    }
    @Override protected void onResume() {
        super.onResume();
        refreshLanguage();
    }

    private void failVerify() {
        engine.setNodeOk(false);
        engineReady = true;
        if (selfTestErr == null)
            diceView.setSourceLine(Language.msg(l10n_entropy_commands_failed), false);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);   // pending engine-ready guard
        // wipe secrets from the UI before teardown
        if (diceView != null) diceView.wipe();
        if (lastWordView != null) lastWordView.clearAll();
        if (node != null) node.onDestroy();
        Sfx.release();
        super.onDestroy();
    }
}
