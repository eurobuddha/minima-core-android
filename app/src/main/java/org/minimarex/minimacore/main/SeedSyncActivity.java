package org.minimarex.minimacore.main;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.widget.NestedScrollView;

import org.minimarex.minimacore.R;
import org.minimarex.minimacore.utils.KeyboardInsets;
import org.minimarex.minimacore.launcher.StartServiceActivity;
import org.minimarex.minimacore.service.MinimaService;
import org.minimarex.minimacore.utils.Clip;
import org.minimarex.minimacore.utils.Peers;

/** Retained command state with a bounded, selectable live tail of real node output. */
public class SeedSyncActivity extends AppCompatActivity {
    public static final String PREF_PENDING = "node_resync_pending";

    /** Reopening the app must return to progress, not start a node over an active resync. */
    public static boolean redirectIfPending(android.app.Activity activity) {
        ResyncSession session = ResyncSession.get();
        if (session.state() != ResyncSession.State.RUNNING
                && session.state() != ResyncSession.State.SUCCEEDED
                && !session.restartRequested()
                && !activity.getSharedPreferences("main_prefs", MODE_PRIVATE).getBoolean(PREF_PENDING, false)) return false;
        activity.startActivity(new Intent(activity, SeedSyncActivity.class));
        activity.finish();
        return true;
    }
    private final ResyncSession session = ResyncSession.get();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText host;
    private Button proceed, restart;
    private TextView status, elapsed, terminal;
    private ProgressBar progress;
    private NestedScrollView logScroll;
    private SharedPreferences prefs;
    private long renderedRevision = -1;
    private boolean leaving;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            if (!leaving) handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("main_prefs", MODE_PRIVATE);
        if (prefs.getBoolean(PREF_PENDING, false)) session.interrupted();
        setContentView(R.layout.seed_sync);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setTitle("Resync node");
        setSupportActionBar(tb);
        KeyboardInsets.install(this, findViewById(R.id.sync_main), tb);
        host = findViewById(R.id.seed_sync_host);
        host.setText(session.host().isEmpty() ? Peers.getDefaultPeers(this) : session.host());
        proceed = findViewById(R.id.seed_sync_proceed);
        restart = findViewById(R.id.seed_sync_restart);
        status = findViewById(R.id.seed_sync_status);
        elapsed = findViewById(R.id.seed_sync_elapsed);
        terminal = findViewById(R.id.seed_sync_log);
        progress = findViewById(R.id.seed_sync_progress);
        logScroll = findViewById(R.id.seed_sync_log_scroll);
        proceed.setOnClickListener(v -> confirmResync());
        restart.setOnClickListener(v -> {
            session.requestRestart();
            // A failed resync can leave a partially stopped node. Finish its normal
            // service shutdown before launching a fresh instance.
            MinimaService.cancelAlarm();
            stopService(new Intent(this, MinimaService.class));
            render();
        });
        findViewById(R.id.seed_sync_copy).setOnClickListener(v ->
                Clip.copy(this, "Resync log", terminal.getText().toString(), "Resync log copied"));
        render();
    }

    /**
     * A resync is not a tidy-up: the node deletes txpow.mv.db and archive.mv.db, refetches
     * the chain and then shuts down. Say so before it happens, and say what is NOT at risk -
     * the wallet and seed are untouched without a seed phrase, and people need to know that
     * before they will press the button on a node holding real funds.
     */
    private void confirmResync() {
        String requestedHost = host.getText().toString().trim();
        if (!ResyncSession.validHost(requestedHost)) {
            host.setError("Enter a hostname or IPv4 address and a port from 1 to 65535");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Resync this node?")
                .setMessage("Minima will rebuild its chain data from " + requestedHost + ".\n\n"
                        + "\u2022 Your wallet and seed phrase are not touched.\n"
                        + "\u2022 The node stops when it finishes and you restart it here.\n"
                        + "\u2022 Any transaction still waiting to be mined is dropped.\n\n"
                        + "This can take a while on a slow connection.")
                .setPositiveButton("Resync", (d, w) -> startResync())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startResync() {
        String requestedHost = host.getText().toString().trim();
        if (!ResyncSession.validHost(requestedHost)) {
            host.setError("Enter a hostname or IPv4 address and a port from 1 to 65535");
            return;
        }
        host.setError(null);
        // Shared with the health check's automatic path - see ResyncLauncher for why the
        // pending flag, the alarms and the service binding all have to be handled here.
        ResyncLauncher.Result result = ResyncLauncher.begin(this, requestedHost);
        if (!result.started) {
            setTextIfChanged(status, result.error);
        }
        render();
    }

    @Override protected void onStart() {
        super.onStart();
        handler.post(tick);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(tick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onStop();
    }

    private void render() {
        if (leaving || isFinishing() || isDestroyed()) return;
        ResyncSession.State state = session.state();
        boolean running = state == ResyncSession.State.RUNNING;
        boolean succeeded = state == ResyncSession.State.SUCCEEDED;
        boolean failed = state == ResyncSession.State.FAILED;
        boolean waitingToRestart = session.restartRequested();
        host.setEnabled(!running && !succeeded && !waitingToRestart);
        proceed.setEnabled(!running && !succeeded && !waitingToRestart);
        setTextIfChanged(proceed, running ? "Resyncing…" : failed ? "Retry resync" : "Resync Node");
        progress.setVisibility(running || waitingToRestart || (succeeded && !MinimaService.isShutdownComplete())
                ? View.VISIBLE : View.GONE);
        restart.setVisibility(succeeded || failed ? View.VISIBLE : View.GONE);
        restart.setEnabled(!waitingToRestart && (!succeeded || MinimaService.isShutdownComplete()));
        if (running) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setTextIfChanged(status, "Resync in progress. Keep Minima Core running; wait for completion before restarting.");
        } else if (!waitingToRestart) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (succeeded) {
                setTextIfChanged(status, MinimaService.isShutdownComplete()
                        ? "Resync complete. You can restart the node now — no phone reboot is needed."
                        : session.result());
                if (prefs.getBoolean(PREF_PENDING, false)) {
                    Peers.setDefaultPeers(this, session.host());
                    prefs.edit().putBoolean(PREF_PENDING, false).apply();
                }
            } else if (failed) {
                setTextIfChanged(status, "Resync not completed. " + session.result() + "\nYou can retry, or restart the node and check its status.");
                if (prefs.getBoolean(PREF_PENDING, false)) prefs.edit().putBoolean(PREF_PENDING, false).apply();
            }
        }
        if (state != ResyncSession.State.IDLE) {
            long seconds = session.elapsedSeconds(System.nanoTime());
            setTextIfChanged(elapsed, "Elapsed " + (seconds / 60) + "m " + (seconds % 60) + "s · latest 600 node log entries");
        }
        long revision = session.log.revision();
        if (revision != renderedRevision) {
            boolean atBottom = !logScroll.canScrollVertically(1);
            terminal.setText(String.join("\n", session.log.snapshot()));
            renderedRevision = revision;
            if (atBottom) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
        if (waitingToRestart) {
            setTextIfChanged(status, "Waiting for the node to shut down safely…");
            if (MinimaService.isShutdownComplete()) {
                leaving = true;
                session.reset();
                prefs.edit().putBoolean(PREF_PENDING, false).apply();
                if (MainActivity.MAIN_ACTIVITY != null) MainActivity.MAIN_ACTIVITY.finish();
                startActivity(new Intent(this, StartServiceActivity.class));
                finish();
            }
        }
    }

    private static void setTextIfChanged(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }
}
