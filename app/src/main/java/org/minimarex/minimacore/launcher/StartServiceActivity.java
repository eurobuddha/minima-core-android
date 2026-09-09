package org.minimarex.minimacore.launcher;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import androidx.appcompat.app.AppCompatActivity;
import org.minima.system.Main;
import org.minimarex.minimacore.R;
import org.minimarex.minimacore.main.MainActivity;
import org.minimarex.minimacore.main.SeedSyncActivity;
import org.minimarex.minimacore.service.MinimaService;
import org.minimarex.minimacore.utils.MinimaCMD;
import org.minimarex.minimacore.utils.Peers;

/** Startup observation belongs to this screen; the foreground node owns its own lifetime. */
public class StartServiceActivity extends AppCompatActivity implements ServiceConnection {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MinimaService minima;
    private boolean bound, active, finished, initialized;
    private long deadline;
    private ProgressDialog progress;
    private AlertDialog message;
    private final Runnable poll = this::checkStartup;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SeedSyncActivity.redirectIfPending(this)) return;
        initialized = true;
        progress = new ProgressDialog(this);
        progress.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        progress.setTitle("Minima starting…");
        progress.setMessage("Waiting for the node to finish starting.");
        progress.setIndeterminate(true);
        progress.setCancelable(true);
        progress.setCanceledOnTouchOutside(false);
        progress.setOnCancelListener(dialog -> finish());
        startMinimaService();
        deadline = SystemClock.elapsedRealtime() + waitLimitMillis();
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        if (initialized && !finished && message == null) handler.post(poll);
    }

    @Override protected void onStop() {
        active = false;
        handler.removeCallbacks(poll);
        if (progress != null) progress.dismiss();
        super.onStop();
    }

    protected long waitLimitMillis() { return 120_000; }
    protected enum StartupState { WAITING, READY, FAILED }
    protected StartupState startupState() {
        Main node = Main.getInstance();
        if (minima == null || node == null) return StartupState.WAITING;
        if (node.isStartupError()) return StartupState.FAILED;
        return node.isStartUpComplete() ? StartupState.READY : StartupState.WAITING;
    }

    private void checkStartup() {
        if (!active || finished || isFinishing() || isDestroyed()) return;
        StartupState state = startupState();
        if (state == StartupState.READY) {
            finished = true;
            progress.dismiss();
            MinimaCMD.runMinima("peers action:addpeers peerslist:" + Peers.DEFAULT_MINIMAPEERS);
            openMain();
        } else if (state == StartupState.FAILED) {
            progress.dismiss();
            message = new AlertDialog.Builder(this).setTitle("Startup error")
                    .setMessage("The node reported a startup error. Open Minima to inspect the logs and resync options.")
                    .setIcon(R.drawable.ic_minima)
                    .setPositiveButton("Open Minima", (d, w) -> { finished = true; openMain(); })
                    .setNegativeButton("Close", (d, w) -> finish())
                    .setOnCancelListener(d -> finish()).show();
        } else if (SystemClock.elapsedRealtime() >= deadline) {
            progress.dismiss();
            message = new AlertDialog.Builder(this).setTitle("Startup is taking longer")
                    .setMessage("The node has not reported that it is ready. You can keep waiting or close this screen; the node can continue starting in the background.")
                    .setPositiveButton("Keep waiting", (d, w) -> { message = null; waitForMinimaToStartUp(); })
                    .setNegativeButton("Close", (d, w) -> finish())
                    .setOnCancelListener(d -> finish()).show();
        } else {
            if (!progress.isShowing()) progress.show();
            handler.postDelayed(poll, 500);
        }
    }

    protected void openMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    public void waitForMinimaToStartUp() {
        deadline = SystemClock.elapsedRealtime() + waitLimitMillis();
        handler.removeCallbacks(poll);
        if (active && !finished) handler.post(poll);
    }

    public void startMinimaService() {
        if (bound) return;
        try {
            Intent intent = new Intent(this, MinimaService.class);
            startForegroundService(intent);
            bound = bindService(intent, this, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException e) {
            message = new AlertDialog.Builder(this).setTitle("Could not start Minima")
                    .setMessage("Android could not connect to the node service. Please try again.")
                    .setPositiveButton("Retry", (d, w) -> {
                        message = null; startMinimaService();
                        if (message == null) waitForMinimaToStartUp();
                    })
                    .setNegativeButton("Close", (d, w) -> finish())
                    .setOnCancelListener(d -> finish()).show();
        }
    }

    @Override protected void onDestroy() {
        active = false; finished = true;
        handler.removeCallbacksAndMessages(null);
        if (progress != null) progress.dismiss();
        if (message != null) message.dismiss();
        // A successful bind must be released even when onServiceConnected never arrived.
        if (bound) { bound = false; unbindService(this); }
        minima = null;
        super.onDestroy();
    }

    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        if (!finished) minima = ((MinimaService.MyBinder) binder).getService();
    }
    @Override public void onServiceDisconnected(ComponentName name) { minima = null; }
}
