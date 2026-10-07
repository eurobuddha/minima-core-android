package com.eurobuddha.terminalide.receiver;

import android.content.Context;
import org.json.JSONObject;
import com.eurobuddha.minimaapi.direct.DirectNodeEvents;

/** Application-owned local log sink. Called by the host on a bounded background worker. */
public final class MinimaNotifyReceiver implements DirectNodeEvents.Listener {
    private final Context context;
    private ReceiverDB db;
    private long nextPrune;
    public MinimaNotifyReceiver(Context context) { this.context = context.getApplicationContext(); }
    @Override public synchronized void onEvent(JSONObject event) {
        if (!"MINIMALOG".equals(event.optString("event"))) return;
        JSONObject data = event.optJSONObject("data");
        if (data == null) return;
        if (db == null) db = new ReceiverDB(context);
        else if (!db.isOpen()) db.reOpen();
        long now = System.currentTimeMillis();
        if (now >= nextPrune) { db.deleteOldMessages(); nextPrune = now + 60_000; }
        db.insertEvent("MINIMALOG", data.toString());
    }
}
