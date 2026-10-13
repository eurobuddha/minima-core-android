package com.eurobuddha.minimacore.chat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;

/**
 * KeetChat local history. The drainer acks the node's sidecar inbox ONLY after the
 * message is in here, so this database is the source of truth for chat history.
 * Messages are keyed by the wire msgid (ChatSchema id, falling back to the keets
 * inbox id) — INSERT OR IGNORE makes re-delivery a no-op.
 */
public class ChatDb extends SQLiteOpenHelper {

    private static ChatDb instance;

    public static synchronized ChatDb get(Context zContext) {
        if (instance == null) instance = new ChatDb(zContext.getApplicationContext());
        return instance;
    }

    private ChatDb(Context zContext) {
        super(zContext, "keetchat.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (" +
                "msgid TEXT PRIMARY KEY, pubkey TEXT NOT NULL, incoming INTEGER NOT NULL," +
                "body TEXT NOT NULL, name TEXT DEFAULT '', ref TEXT DEFAULT ''," +
                "ts INTEGER NOT NULL, via TEXT DEFAULT '', read INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_messages_pubkey ON messages(pubkey, ts)");
        // contact = keets pubkey (0x + 64 hex, ALWAYS stored and shown in full);
        // maximapk reserved for the Parlons interop era
        db.execSQL("CREATE TABLE contacts (" +
                "pubkey TEXT PRIMARY KEY, name TEXT NOT NULL, maximapk TEXT DEFAULT ''," +
                "added INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {}

    public static class Msg {
        public String msgid, pubkey, body, name, ref, via;
        public boolean incoming, read;
        public long ts;
    }

    public static class Thread {
        public String pubkey, name, lastBody;
        public long lastTs;
        public int unread;
    }

    /** @return true if the message was new (false = duplicate re-delivery). */
    public boolean addMessage(Msg m) {
        ContentValues v = new ContentValues();
        v.put("msgid", m.msgid);
        v.put("pubkey", m.pubkey);
        v.put("incoming", m.incoming ? 1 : 0);
        v.put("body", m.body);
        v.put("name", m.name == null ? "" : m.name);
        v.put("ref", m.ref == null ? "" : m.ref);
        v.put("ts", m.ts);
        v.put("via", m.via == null ? "" : m.via);
        v.put("read", m.read ? 1 : 0);
        return getWritableDatabase().insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public void setVia(String zMsgId, String zVia) {
        ContentValues v = new ContentValues();
        v.put("via", zVia);
        getWritableDatabase().update("messages", v, "msgid=?", new String[]{zMsgId});
    }

    public ArrayList<Msg> thread(String zPubkey) {
        ArrayList<Msg> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT msgid,pubkey,incoming,body,name,ref,ts,via,read FROM messages WHERE pubkey=? ORDER BY ts ASC, rowid ASC",
                new String[]{zPubkey});
        while (c.moveToNext()) {
            Msg m = new Msg();
            m.msgid = c.getString(0); m.pubkey = c.getString(1); m.incoming = c.getInt(2) == 1;
            m.body = c.getString(3); m.name = c.getString(4); m.ref = c.getString(5);
            m.ts = c.getLong(6); m.via = c.getString(7); m.read = c.getInt(8) == 1;
            out.add(m);
        }
        c.close();
        return out;
    }

    /** One row per contact (contacts with no messages included), newest activity first. */
    public ArrayList<Thread> threads() {
        ArrayList<Thread> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ct.pubkey, ct.name," +
                " (SELECT body FROM messages m WHERE m.pubkey=ct.pubkey ORDER BY ts DESC, rowid DESC LIMIT 1)," +
                " (SELECT MAX(ts) FROM messages m WHERE m.pubkey=ct.pubkey)," +
                " (SELECT COUNT(*) FROM messages m WHERE m.pubkey=ct.pubkey AND incoming=1 AND read=0)" +
                " FROM (SELECT pubkey, name, added FROM contacts" +
                "       UNION SELECT DISTINCT pubkey, pubkey, 0 FROM messages WHERE pubkey NOT IN (SELECT pubkey FROM contacts)) ct" +
                " ORDER BY 4 IS NULL, 4 DESC, ct.added ASC", null);
        while (c.moveToNext()) {
            Thread t = new Thread();
            t.pubkey = c.getString(0);
            t.name = c.getString(1);
            t.lastBody = c.isNull(2) ? "" : c.getString(2);
            t.lastTs = c.isNull(3) ? 0 : c.getLong(3);
            t.unread = c.getInt(4);
            out.add(t);
        }
        c.close();
        return out;
    }

    public void markThreadRead(String zPubkey) {
        getWritableDatabase().execSQL("UPDATE messages SET read=1 WHERE pubkey=? AND incoming=1 AND read=0",
                new String[]{zPubkey});
    }

    public int unreadCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages WHERE incoming=1 AND read=0", null);
        int n = c.moveToNext() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** Create the contact if unknown; sender's self-declared name, else the FULL pubkey. */
    public void ensureContact(String zPubkey, String zSenderName) {
        ContentValues v = new ContentValues();
        v.put("pubkey", zPubkey);
        v.put("name", (zSenderName == null || zSenderName.equals("")) ? zPubkey : zSenderName);
        v.put("added", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("contacts", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void putContact(String zPubkey, String zName) {
        ensureContact(zPubkey, zName);
        ContentValues v = new ContentValues();
        v.put("name", (zName == null || zName.equals("")) ? zPubkey : zName);
        getWritableDatabase().update("contacts", v, "pubkey=?", new String[]{zPubkey});
    }

    public String contactName(String zPubkey) {
        Cursor c = getReadableDatabase().rawQuery("SELECT name FROM contacts WHERE pubkey=?", new String[]{zPubkey});
        String n = c.moveToNext() ? c.getString(0) : null;
        c.close();
        return n == null ? zPubkey : n;
    }

    public void removeContact(String zPubkey) {
        getWritableDatabase().delete("contacts", "pubkey=?", new String[]{zPubkey});
        getWritableDatabase().delete("messages", "pubkey=?", new String[]{zPubkey});
    }
}
