package com.eurobuddha.faucet;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import com.eurobuddha.minimaapi.MinimaAPIListener;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Native Android port of the Faucet MiniDapp.
 *
 * Talks directly to the embedded Minima Core node
 * (com.eurobuddha.minimaapi) for the single node command it needs - "getaddress".
 * The actual coin request is a direct HTTPS GET to the faucet backend.
 */
public class MainActivity extends AppCompatActivity {

    private static final String FAUCET_API = "https://eurobuddha.com/faucet/api/request";
    private static final long   NODE_TIMEOUT_MS = 6000;

    private DirectNodeApi mApi;
    private volatile boolean mDestroyed;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private EditText mAddress;
    private Button   mGetAddrBtn;
    private Button   mRequestBtn;
    private Button   mOpenNodeBtn;
    private TextView mStatus;
    private View     mPairingBanner;

    // The last node action that was blocked by "not enabled" - re-run after the
    // user enables us in Minima Core and returns to this app.
    private Runnable mPendingAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.pm_faucet_activity_main);

        mAddress       = findViewById(R.id.pm_faucet_addressInput);
        mGetAddrBtn    = findViewById(R.id.pm_faucet_getAddrBtn);
        mRequestBtn    = findViewById(R.id.pm_faucet_requestBtn);
        mOpenNodeBtn   = findViewById(R.id.pm_faucet_openNodeBtn);
        mStatus        = findViewById(R.id.pm_faucet_statusBox);
        mPairingBanner = findViewById(R.id.pm_faucet_pairingBanner);

        // Edge-to-edge (targetSdk 35 draws under the system bars): pad the root for the status bar,
        // nav bar and keyboard so content never sits under them.
        final View mainRoot = findViewById(R.id.pm_faucet_main);
        Language.apply(mainRoot);
        // Construct the in-process API for embedded-node readiness.
        // The register reply tells us whether we are enabled yet.
        mApi = new DirectNodeApi(this, new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                final boolean enabled = zResponse != null && zResponse.optBoolean("enabled", false);
                mMain.post(() -> {
                    if (mDestroyed) return;
                    if (enabled) {
                        hidePairingBanner();
                    } else {
                        showPairingBanner(null);
                    }
                });
            }
        });

        mGetAddrBtn.setOnClickListener(v -> getMyAddress());
        mRequestBtn.setOnClickListener(v -> requestMinima());
        mOpenNodeBtn.setOnClickListener(v -> openMinimaCore());


    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from Minima Core after (hopefully) enabling us - retry.
        if (mPendingAction != null && mPairingBanner.getVisibility() == View.VISIBLE) {
            Runnable retry = mPendingAction;
            mPendingAction = null;
            retry.run();
        }
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        mMain.removeCallbacksAndMessages(null);
        super.onDestroy();
        if (mApi != null) {
            mApi.onDestroy();
        }
    }

    // ----- Get My Address (needs the node) -------------------------------------------------

    private void getMyAddress() {
        if (mDestroyed) return;
        mGetAddrBtn.setEnabled(false);
        Language.setText(mGetAddrBtn, Language.msg(l10n_faucet_fetching));
        runNodeCommand("getaddress", new NodeResult() {
            @Override
            public void onResponse(JSONObject json) {
                resetGetAddrButton();
                if (isNotEnabled(json)) {
                    showPairingBanner(MainActivity.this::getMyAddress);
                    return;
                }
                hidePairingBanner();
                JSONObject resp = json.optJSONObject("response");
                String addr = "";
                if (resp != null) {
                    addr = resp.optString("miniaddress", resp.optString("address", ""));
                }
                if (Boolean.TRUE.equals(json.opt("status")) && !json.optBoolean("pending",false)
                        && addr.matches("^(0x|Mx)[0-9A-Za-z]+$")) {
                    mAddress.setText(addr);
                } else {
                    showStatus(Language.msg(l10n_faucet_bad_address), false);
                }
            }

            @Override
            public void onTimeout() {
                resetGetAddrButton();
                showStatus(Language.msg(l10n_faucet_node_timeout), false);
            }
        });
    }

    private void resetGetAddrButton() {
        mGetAddrBtn.setEnabled(true);
        Language.setText(mGetAddrBtn, Language.msg(l10n_faucet_get_address));
    }

    // ----- Request Minima (direct HTTPS to the faucet backend, no node) --------------------

    private void requestMinima() {
        if (mDestroyed) return;
        final String addr = mAddress.getText().toString().trim();
        if (!addr.matches("^(0x|Mx)[0-9A-Za-z]+$")) {
            showStatus(Language.msg(l10n_faucet_enter_address), false);
            return;
        }
        mRequestBtn.setEnabled(false);
        Language.setText(mRequestBtn, Language.msg(l10n_faucet_requesting));

        new Thread(() -> {
            String body = null;
            HttpURLConnection conn = null;
            int code = -1;
            try {
                String url = FAUCET_API + "?address=" + URLEncoder.encode(addr, "UTF-8");
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                code = conn.getResponseCode();
                InputStream in = (code >= 200 && code < 400)
                        ? conn.getInputStream() : conn.getErrorStream();
                body = readAll(in);

            } catch (Exception exc) {
                body = null;
            } finally { if (conn != null) conn.disconnect(); }

            final String result = body;
            final int statusCode = code;
            mMain.post(() -> {
                    if (mDestroyed) return;
                mRequestBtn.setEnabled(true);
                Language.setText(mRequestBtn, Language.msg(l10n_faucet_request));
                if (result == null) {
                    showStatus(Language.msg(l10n_faucet_failed), false);
                    return;
                }
                try {
                    JSONObject data = new JSONObject(result);
                    boolean ok = statusCode >= 200 && statusCode < 300 && Boolean.TRUE.equals(data.opt("status"));
                    String msg = data.optString("message", "");
                    showStatus(msg.isEmpty() ? Language.msg(l10n_faucet_unexpected)
                            : Language.msg(l10n_faucet_response,msg), ok);
                } catch (Exception e) {
                    showStatus(Language.msg(l10n_faucet_failed), false);
                }
            });
        }).start();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return null;
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); InputStream stream = in) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) {
                if (out.size() + count > 65536) throw new java.io.IOException("Faucet response too large");
                out.write(buffer,0,count);
            }
            return out.toString("UTF-8");
        }
    }

    // ----- Node command helper (timeout + main-thread marshalling) -------------------------

    private interface NodeResult {
        void onResponse(JSONObject json);
        void onTimeout();
    }

    private void runNodeCommand(String cmd, NodeResult cb) {
        final boolean[] done = {false};
        final Runnable timeout = () -> {
            if (!mDestroyed && !done[0]) {
                done[0] = true;
                cb.onTimeout();
            }
        };
        mMain.postDelayed(timeout, NODE_TIMEOUT_MS);

        mApi.Command(cmd, new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                // Marshal the direct callback to the UI thread.
                mMain.post(() -> {
                    if (mDestroyed) return;
                    if (done[0]) return;
                    done[0] = true;
                    mMain.removeCallbacks(timeout);
                    cb.onResponse(zResponse);
                });
            }
        });
    }

    /** True when the node replied that this app is not yet enabled in Minima Core. */
    private boolean isNotEnabled(JSONObject json) {
        return json == null || !json.optBoolean("enabled", true);
    }

    // ----- Pairing UX ----------------------------------------------------------------------

    private void showPairingBanner(Runnable retryWhenEnabled) {
        if (retryWhenEnabled != null) {
            mPendingAction = retryWhenEnabled;
        }
        mPairingBanner.setVisibility(View.VISIBLE);
    }

    private void hidePairingBanner() {
        mPairingBanner.setVisibility(View.GONE);
    }

    private void openMinimaCore() {
        startActivity(new Intent().setClassName(getPackageName(),"com.eurobuddha.minimacore.main.MainActivity"));
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
    }

    // ----- Status box ----------------------------------------------------------------------

    private void showStatus(CharSequence msg, boolean success) {
        Language.setText(mStatus, msg);
        mStatus.setTextColor(ContextCompat.getColor(this,
                success ? R.color.pm_faucet_faucet_success : R.color.pm_faucet_faucet_error));
        mStatus.setVisibility(View.VISIBLE);
    }
}
