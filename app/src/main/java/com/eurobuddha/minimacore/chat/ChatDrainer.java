package com.eurobuddha.minimacore.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import com.eurobuddha.minimacore.R;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.minimacore.utils.MinimaCMD;
import com.eurobuddha.minimacore.utils.logger;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.util.Base64;

/**
 * The ONE drainer of this node's keets message inbox (`keets action:inbox`). Every
 * message is stored to ChatDb FIRST, acked after, then fanned out:
 *  - a HIGH-importance notification (tap opens the chat thread),
 *  - a {"event":"KEETS"} NOTIFY broadcast to enabled companion APKs (the Android
 *    equivalent of the MDS KEETS event — companions store their own copy, never ack),
 *  - the in-app UI listener (ChatView refresh).
 * The node's own MDS-event poller is gated off on this line (no MDS), so inbox
 * messages wait here until this drainer runs — nothing is lost while the app is off:
 * offline messages additionally sit at the keets fleet mailboxes.
 */
public class ChatDrainer {

    public static final String CHANNEL_ID = "MinimaChatChannel";
    private static final long POLL_MS = 5000;

    /** UI refresh hooks — ChatView / ChatThreadActivity register while alive; run on a
     *  background thread, so listeners post to main themselves. */
    public static final java.util.concurrent.CopyOnWriteArraySet<Runnable> UI_LISTENERS =
            new java.util.concurrent.CopyOnWriteArraySet<>();

    private static java.lang.Thread mThread = null;
    private static volatile boolean mStop = false;
    private static String mLastErr = null;

    public static synchronized void start(MinimaService zService) {
        if (mThread != null) return;
        mStop = false;

        NotificationManager nm = (NotificationManager) zService.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Chat messages", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Incoming KeetChat messages");
        nm.createNotificationChannel(ch);

        mThread = new java.lang.Thread(() -> runLoop(zService), "keets-chat-drainer");
        mThread.setDaemon(true);
        mThread.start();
    }

    public static synchronized void stop() {
        mStop = true;
        if (mThread != null) { mThread.interrupt(); mThread = null; }
    }

    private static void runLoop(MinimaService zService) {
        ChatDb db = ChatDb.get(zService);
        while (!mStop) {
            try { java.lang.Thread.sleep(POLL_MS); } catch (InterruptedException e) { break; }
            if (!MinimaCMD.checkMinimaStarted()) continue;
            try {
                JSONObject res = MinimaCMD.execute("keets action:inbox");
                if (!(Boolean) res.getOrDefault("status", false)) {
                    //sidecar starting is normal — but log a CHANGED error so a wedged
                    //command path is never silent (the 401 token bug cost a device test)
                    String err = "" + res.get("error");
                    if (!err.equals(mLastErr)) { mLastErr = err; logger.log("[CHAT] inbox unavailable: " + err); }
                    continue;
                }
                mLastErr = null;
                JSONArray inbox = (JSONArray) res.get("response");
                int fresh = 0;
                String lastFrom = null, lastName = null, lastBody = null;
                for (Object o : inbox) {
                    JSONObject msg = (JSONObject) o;
                    String from = (String) msg.get("from");
                    String raw;
                    try { raw = new String(Base64.getDecoder().decode((String) msg.get("payload")), "UTF-8"); }
                    catch (Exception e) { raw = "" + msg.get("message"); }
                    ChatSchema.Decoded d = ChatSchema.decode(raw);

                    ChatDb.Msg m = new ChatDb.Msg();
                    m.msgid = d.id != null ? d.id : (String) msg.get("id");
                    m.pubkey = from;
                    m.incoming = true;
                    m.body = d.body;
                    m.name = d.name;
                    m.ref = d.ref;
                    m.ts = d.ts != 0 ? d.ts : Long.parseLong("" + msg.get("ts"));
                    m.via = (String) msg.get("via");
                    boolean isNew = db.addMessage(m);
                    if (isNew) {
                        db.ensureContact(from, d.name);
                        fresh++;
                        lastFrom = from;
                        lastName = db.contactName(from);
                        lastBody = d.body;

                        // companion fan-out — the Android twin of the MDS KEETS event
                        JSONObject ev = new JSONObject();
                        ev.put("event", "KEETS");
                        JSONObject data = new JSONObject();
                        data.put("id", m.msgid);
                        data.put("from", from);
                        data.put("ts", m.ts);
                        data.put("payload", msg.get("payload"));
                        data.put("message", d.body);
                        data.put("name", d.name);
                        data.put("ref", d.ref);
                        data.put("via", m.via);
                        ev.put("data", data);
                        zService.sendBroadcastNotify(ev, false);
                    }
                    // ack ONLY after the durable store write
                    MinimaCMD.execute("keets action:ack id:" + msg.get("id"));
                }
                if (fresh > 0) {
                    notifyUser(zService, fresh, lastFrom, lastName, lastBody);
                    for (Runnable l : UI_LISTENERS) { try { l.run(); } catch (Exception e) {} }
                }
            } catch (Exception exc) {
                logger.log("[CHAT] drainer: " + exc);
            }
        }
    }

    private static void notifyUser(MinimaService zService, int zFresh, String zFrom, String zName, String zBody) {
        try {
            Intent it = new Intent(zService, ChatThreadActivity.class);
            it.putExtra("pubkey", zFrom);
            it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(zService, 3, it,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification n = new NotificationCompat.Builder(zService, CHANNEL_ID)
                    .setContentTitle(zFresh > 1 ? "KeetChat — " + zFresh + " new messages" : zName)
                    .setContentText(zFresh > 1 ? "Tap to open" : zBody)
                    .setSmallIcon(R.drawable.ic_minima)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            NotificationManager nm = (NotificationManager) zService.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify("keetchat", 3, n);
        } catch (Exception exc) {
            logger.log("[CHAT] notify: " + exc);
        }
    }
}
