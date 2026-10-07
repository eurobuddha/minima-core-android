package com.eurobuddha.vestr;

import com.eurobuddha.minimaapi.direct.DirectNodeEvents;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.ViewPager;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;

import java.util.ArrayList;
import java.util.List;

/**
 * vestr — native token-vesting contract manager. Talks to the local Minima node over the broadcast-Intent
 * IPC (same transport as the utxo wallet). On pairing it deploys the vesting script ({@code newscript})
 * and uses the returned address as the contract address. Three tabs: Creator / Collector / About.
 */
public class MainActivity extends AppCompatActivity {

    static final String NODE_PKG = "com.eurobuddha.pandamonium";
    public static final int TAB_CREATOR = 0, TAB_COLLECTOR = 1, TAB_ABOUT = 2;

    private NodeApi node;
    private ViewPager viewPager;
    private MainPager pager;
    private BaseView[] views;
    private LinearLayout pairingBanner, bottomBar;
    private TextView blockNo;
    private final LinearLayout[] tabs = new LinearLayout[3];
    private DirectNodeEvents.Listener notifyReceiver;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable reloadTask = this::reload;

    // ---- node-derived state ----
    private String scriptAddress = "";
    private int chainBlock = 0;
    private final List<JSONObject> contractCoins = new ArrayList<>();   // raw coins at the script address

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.pm_vestr_activity_main);

        // Edge-to-edge (targetSdk 35): push the header below the status bar and the nav below the nav
        // bar, with the dark header/nav backgrounds filling the system-bar areas.
        final View root = findViewById(R.id.pm_vestr_main);
        final View headerV = findViewById(R.id.pm_vestr_header);
        final View navV = findViewById(R.id.pm_vestr_bottomBar);
        final int headerTop = headerV.getPaddingTop();
        final int navBottom = navV.getPaddingBottom();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            headerV.setPadding(headerV.getPaddingLeft(), headerTop + b.top, headerV.getPaddingRight(), headerV.getPaddingBottom());
            navV.setPadding(navV.getPaddingLeft() + b.left, navV.getPaddingTop(), navV.getPaddingRight() + b.right, navBottom + b.bottom);
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(root);
        new androidx.core.view.WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);

        pairingBanner = findViewById(R.id.pm_vestr_pairingBanner);
        bottomBar = findViewById(R.id.pm_vestr_bottomBar);
        blockNo = findViewById(R.id.pm_vestr_blockNo);
        ((Button) findViewById(R.id.pm_vestr_openNodeBtn)).setOnClickListener(v -> openMinimaCore());

        views = new BaseView[]{ new CreatorView(this), new CollectorView(this), new AboutView(this) };
        pager = new MainPager(views, new String[]{"Creator", "Collector", "About"});
        viewPager = findViewById(R.id.pm_vestr_pager);
        viewPager.setOffscreenPageLimit(3);
        viewPager.setAdapter(pager);
        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override public void onPageSelected(int pos) { setActiveTab(pos); views[pos].onShown(); }
        });
        buildBottomBar();
        setActiveTab(TAB_CREATOR);

        node = new NodeApi(this, enabled -> {
            if (enabled) { setPaired(true); deployScript(); }
            else setPaired(false);
        });

        notifyReceiver = new DirectNodeEvents.Listener() {
            @Override public void onEvent(JSONObject nodeEvent) {
                try {
                    String event = nodeEvent.optString("event", "");
                    if ("NEWBLOCK".equals(event) || "NEWBALANCE".equals(event)) requestReload();
                } catch (Exception ignored) {}
            }
        };
        // Subscribed only while this screen is visible.
    }

    @Override protected void onResume() { super.onResume(); requestReload(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(reloadTask);
        if (node != null) node.onDestroy();
        if (notifyReceiver != null) try { DirectNodeEvents.unsubscribe(notifyReceiver); } catch (Exception ignored) {}
    }

    // ===== bottom nav =====

    private void buildBottomBar() {
        String[] glyphs = {"✎", "❖", "ⓘ"};
        String[] labels = {"Creator", "Collector", "About"};
        for (int i = 0; i < 3; i++) {
            final int pos = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tab.setPadding(0, dp(6), 0, dp(6));
            TextView glyph = new TextView(this);
            glyph.setText(glyphs[i]); glyph.setTextSize(20f); glyph.setGravity(Gravity.CENTER);
            TextView label = new TextView(this);
            label.setText(labels[i]); label.setTextSize(11f); label.setGravity(Gravity.CENTER);
            label.setLetterSpacing(0.05f); label.setPadding(0, dp(2), 0, 0);
            tab.addView(glyph); tab.addView(label);
            tab.setOnClickListener(v -> viewPager.setCurrentItem(pos));
            bottomBar.addView(tab);
            tabs[i] = tab;
        }
    }

    private void setActiveTab(int active) {
        for (int i = 0; i < tabs.length; i++) {
            int color = i == active ? VestrDesign.NAV_ACTIVE : VestrDesign.NAV_INACTIVE;
            ((TextView) tabs[i].getChildAt(0)).setTextColor(color);
            ((TextView) tabs[i].getChildAt(1)).setTextColor(color);
        }
    }

    public void goToTab(int pos) { viewPager.setCurrentItem(pos); }

    // ===== contract script deploy + reload =====

    /** Deploy the vesting script and adopt the returned address (mirrors vestr AppContext). */
    private void deployScript() {
        node.cmd("newscript script:\"" + VestingContract.CLEAN_SCRIPT + "\" trackall:false", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) scriptAddress = r.optString("address", scriptAddress);
                requestReload();
                for (BaseView v : views) v.refresh();
            }
            @Override public void onError(String message) { handleErr(message); }
        });
    }

    public void requestReload() {
        ui.removeCallbacks(reloadTask);
        ui.postDelayed(reloadTask, 400);
    }

    public void reload() {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                setPaired(true);
                JSONObject r = json.optJSONObject("response");
                if (r != null) {
                    String b = r.optString("block", "");
                    try { chainBlock = Integer.parseInt(b); } catch (Exception ignored) {}
                    blockNo.setText("#" + chainBlock);
                }
            }
            @Override public void onError(String message) { handleErr(message); }
        });

        if (scriptAddress.isEmpty()) return;
        node.cmd("coins address:" + scriptAddress + " relevant:true", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                setPaired(true);
                contractCoins.clear();
                JSONArray arr = json.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c != null) contractCoins.add(c);
                }
                for (BaseView v : views) v.refresh();
            }
            @Override public void onError(String message) { handleErr(message); }
        });
    }

    private void handleErr(String message) {
        if (NodeApi.ERR_NOT_ENABLED.equals(message)) setPaired(false);
        else Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ===== pairing UX =====

    private void setPaired(boolean paired) {
        pairingBanner.setVisibility(paired ? View.GONE : View.VISIBLE);
    }

    private void openMinimaCore() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(NODE_PKG);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.minimablock");
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.pandamonium");
        if (launch != null) startActivity(launch);
        else Toast.makeText(this, "Minima Core is not installed.", Toast.LENGTH_LONG).show();
    }

    // ===== accessors for views =====

    public NodeApi node() { return node; }
    public int chainBlock() { return chainBlock; }
    public String scriptAddress() { return scriptAddress; }
    public int currentTab() { return viewPager.getCurrentItem(); }

    /** Parsed vesting contracts (coins at the script address relevant to this wallet). */
    public List<Contract> contracts() {
        List<Contract> out = new ArrayList<>();
        for (JSONObject c : contractCoins) {
            Contract k = Contract.from(c);
            if (!k.uid.isEmpty()) out.add(k);
        }
        return out;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override protected void onStart() {
        super.onStart();
        DirectNodeEvents.subscribe(notifyReceiver, "NEWBLOCK", "NEWBALANCE");
    }
    @Override protected void onStop() {
        DirectNodeEvents.unsubscribe(notifyReceiver);
        super.onStop();
    }
}
