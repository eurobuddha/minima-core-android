package org.minimarex.minimacore.main.views.home;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.minima.database.MinimaDB;
import org.minima.objects.TxPoW;
import org.minima.system.params.GlobalParams;
import org.minima.utils.json.JSONObject;
import org.minimarex.minimacore.R;
import org.minimarex.minimacore.utils.MinimaCMD;
import org.minimarex.minimacore.utils.logger;
import org.minimarex.minimacore.main.BaseView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class HomeView extends BaseView {

    TextView mKeyUses;
    TextView mVersion;
    TextView mMinima;
    TextView mBlock;
    TextView mBlockTime;
    TextView mConnections;
    TextView mPeers;

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean destroyed;
    private final org.minimarex.minimacore.utils.CoalescingRefresh<JSONObject[]> refresh =
            new org.minimarex.minimacore.utils.CoalescingRefresh<>(MinimaCMD.readExecutor(),
                    main::post, HomeView::readDashboard, this::renderDashboard,
                    error -> logger.log("Dashboard refresh failed: " + error));

    private SimpleDateFormat DATEFORMAT = new SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.ENGLISH);

    public HomeView(Activity zActivity){
        super(zActivity, R.layout.view_home);

        mKeyUses    = getMainView().findViewById(R.id.home_keyuses);
        mMinima    = getMainView().findViewById(R.id.home_version);
        mBlock      = getMainView().findViewById(R.id.home_blocks);
        mBlockTime  = getMainView().findViewById(R.id.home_block_time);
        mConnections  = getMainView().findViewById(R.id.home_connections);
        mPeers      = getMainView().findViewById(R.id.home_peers);
        mVersion    = getMainView().findViewById(R.id.home_app_version);

        Button tester = getMainView().findViewById(R.id.button_tester);
        tester.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logger.log("Test Button pressed!");

                Intent intent = new Intent("com.example.snippets.ACTION_UPDATE_DATA");
                intent.putExtra("com.example.snippets.DATA", "SOME DATA!");
                intent.setPackage("org.minimarex.minimacore");
                zActivity.sendBroadcast(intent);
            }
        });

        refreshView();
    }

    private static JSONObject[] readDashboard() {
        return new JSONObject[] { MinimaCMD.execute("keys"), MinimaCMD.execute("peers"), MinimaCMD.execute("network") };
    }

    @Override public void refreshView() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            main.post(this::refreshView); return;
        }
        if (destroyed) return;
        if (!MinimaCMD.checkMinimaStarted()) { refresh.invalidate(); return; }
        refresh.request();
        // Block/version rendering stays cheap; all command work is bounded and coalesced.
        mMinima.setText(GlobalParams.getFullMicroVersion());
        try {
            TxPoW tip = MinimaDB.getDB().getTxPoWTree().getTip().getTxPoW();
            mBlock.setText(tip.getBlockNumber().toString());
            mBlockTime.setText(DATEFORMAT.format(new Date(tip.getTimeMilli().getAsLong())));
        } catch (Exception ignored) { /* Node may be shutting down. */ }
        try { mVersion.setText(getActivity().getPackageManager().getPackageInfo(getActivity().getPackageName(), 0).versionName); }
        catch (Exception ignored) { }
    }

    private void renderDashboard(JSONObject[] results) {
        if (destroyed || getActivity().isFinishing() || getActivity().isDestroyed()) return;
        try {
            JSONObject keys = (JSONObject) results[0].get("response");
            if (keys != null && keys.get("maxuses") != null) mKeyUses.setText(String.valueOf(keys.get("maxuses")));
        } catch (Exception ignored) { }
        try {
            JSONObject peers = (JSONObject) results[1].get("response");
            String list = peers.getString("peerslist");
            String[] items = list == null || list.isEmpty() ? new String[0] : list.split(",");
            mPeers.setText(items.length == 0 ? "No peers" : items[new Random().nextInt(items.length)]);
        } catch (Exception ignored) { }
        try {
            JSONObject network = (JSONObject) results[2].get("response");
            JSONObject details = (JSONObject) network.get("details");
            if (details.get("connected") != null) mConnections.setText(String.valueOf(details.get("connected")));
        } catch (Exception ignored) { }
    }

    @Override public void onActivityDestroy() {
        destroyed = true;
        refresh.close();
        main.removeCallbacksAndMessages(null);
    }
}
