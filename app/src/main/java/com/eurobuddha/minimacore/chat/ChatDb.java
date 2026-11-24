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
        // Version 3, and it stays 3 (2.2.122): 2.2.118 moved it to 4 only to run a purge once, and an older copy of the
        // app then could not open the chat database at all (SQLiteOpenHelper refuses a database newer than itself).
        super(zContext, "keetchat.db", null, 3);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (" +
                "msgid TEXT PRIMARY KEY, pubkey TEXT NOT NULL, incoming INTEGER NOT NULL," +
                "body TEXT NOT NULL, name TEXT DEFAULT '', ref TEXT DEFAULT ''," +
                "ts INTEGER NOT NULL, via TEXT DEFAULT '', read INTEGER DEFAULT 0," +
                "state TEXT DEFAULT '', amt TEXT DEFAULT '', tokname TEXT DEFAULT ''," +
                "txid TEXT DEFAULT '', peerread INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_messages_pubkey ON messages(pubkey, ts)");
        // contact = keets pubkey (0x + 64 hex, ALWAYS stored and shown in full);
        // maximapk reserved for the Parlons interop era
        db.execSQL("CREATE TABLE contacts (" +
                "pubkey TEXT PRIMARY KEY, name TEXT NOT NULL, maximapk TEXT DEFAULT ''," +
                "payaddr TEXT DEFAULT '', lastreceipt TEXT DEFAULT ''," +
                "added INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            for (String c : new String[]{"state TEXT DEFAULT ''", "amt TEXT DEFAULT ''",
                    "tokname TEXT DEFAULT ''", "txid TEXT DEFAULT ''", "peerread INTEGER DEFAULT 0"}) {
                try { db.execSQL("ALTER TABLE messages ADD COLUMN " + c); } catch (Exception e) {}
            }
            for (String c : new String[]{"payaddr TEXT DEFAULT ''", "lastreceipt TEXT DEFAULT ''"}) {
                try { db.execSQL("ALTER TABLE contacts ADD COLUMN " + c); } catch (Exception e) {}
            }
        }
        if (oldV < 3) {
            // Repair unnamed contacts using names already received from that peer.
            // Outgoing names belong to us. Never overwrite a user-named contact.
            db.execSQL("UPDATE contacts SET name=COALESCE((SELECT trim(m.name) FROM messages m " +
                    "WHERE m.pubkey=contacts.pubkey AND m.incoming=1 AND trim(m.name)<>'' " +
                    "AND lower(trim(m.name))<>lower(contacts.pubkey) ORDER BY m.rowid DESC LIMIT 1),name) " +
                    "WHERE trim(name)='' OR lower(trim(name))=lower(pubkey)");
        }
    }

    /** SECURITY (2.2.118, moved here in 2.2.122): rows stored before 2.2.110 validated the inbox can carry a sender with
     *  text after the key, and the thread screen put it into "keets action:send to:" run as admin. They are removed at
     *  every open, with no change of the database version: a row an older copy of the app wrote in between goes too,
     *  and that older copy can still open the database. Nothing is found after the first time (addMessage and
     *  ensureContact refuse such a key). */
    /** A version 4 file (2.2.118-2.2.121 source, never released) has the version 3 tables: it opens, it is not refused
     *  for ever with "Can't downgrade database" (2.2.125). */
    @Override
    public void onDowngrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV != 4) super.onDowngrade(db, oldV, newV);   // 2.2.127: only version 4 is known to have these tables; anything newer is refused as before
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (db.isReadOnly()) return;
        try {
            int[] n = purgeBadKeys(db);
            if (n[0] + n[1] > 0) com.eurobuddha.minimacore.utils.logger.log("[CHAT] SECURITY: rows whose key is not 0x + 64 hex removed: "
                    + n[0] + " contacts, " + n[1] + " messages");
        } catch (Exception e) {
            com.eurobuddha.minimacore.utils.logger.log("[CHAT] the check for rows with a bad key could not run: " + e);   // sendCommand still refuses them
        }
    }

    /** Deletes every contact and message whose pubkey is not exactly a keets key (ChatSchema.PEER_KEY).
     *  @return {contacts removed, messages removed} */
    static int[] purgeBadKeys(SQLiteDatabase db) {
        int[] removed = new int[2];
        String[] tables = {"contacts", "messages"};
        for (int i = 0; i < tables.length; i++) {
            ArrayList<String> bad = new ArrayList<>();
            try (Cursor c = db.rawQuery("SELECT DISTINCT pubkey FROM " + tables[i], null)) {
                while (c.moveToNext()) if (!ChatSchema.isPeerKey(c.getString(0))) bad.add(c.getString(0));
            }
            for (String key : bad) removed[i] += db.delete(tables[i], "pubkey=?", new String[]{key});
        }
        // 2.2.123: a pay address that is not exactly an address is blanked (the contact stays; the address is asked for again)
        ArrayList<String> badPay = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT pubkey, payaddr FROM contacts WHERE payaddr IS NOT NULL AND payaddr<>''", null)) {
            while (c.moveToNext()) if (!ChatSchema.isPayAddr(c.getString(1))) badPay.add(c.getString(0));
        }
        for (String key : badPay) db.execSQL("UPDATE contacts SET payaddr='' WHERE pubkey=?", new Object[]{key});
        return removed;
    }

    public static class Msg {
        public String msgid, pubkey, body, name, ref, via;
        public String state = "", amt = "", tokname = "", txid = "";
        public boolean incoming, read, peerread;
        public long ts;
    }

    public static class Thread {
        public String pubkey, name, lastBody;
        public String lastState = "";
        public boolean lastMine;
        public long lastTs;
        public int unread;
    }

    /** @return true if the message was new (false = duplicate re-delivery). */
    public boolean addMessage(Msg m) {
        if (!ChatSchema.isPeerKey(m.pubkey)) return false;   // never store a key that could not be sent to
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
        v.put("state", m.state == null ? "" : m.state);
        v.put("amt", m.amt == null ? "" : m.amt);
        v.put("tokname", m.tokname == null ? "" : m.tokname);
        v.put("txid", m.txid == null ? "" : m.txid);
        v.put("peerread", m.peerread ? 1 : 0);
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
                "SELECT msgid,pubkey,incoming,body,name,ref,ts,via,read,state,amt,tokname,txid,peerread FROM messages WHERE pubkey=? ORDER BY ts ASC, rowid ASC",
                new String[]{zPubkey});
        while (c.moveToNext()) {
            Msg m = new Msg();
            m.msgid = c.getString(0); m.pubkey = c.getString(1); m.incoming = c.getInt(2) == 1;
            m.body = c.getString(3); m.name = c.getString(4); m.ref = c.getString(5);
            m.ts = c.getLong(6); m.via = c.getString(7); m.read = c.getInt(8) == 1;
            m.state = c.getString(9); m.amt = c.getString(10); m.tokname = c.getString(11);
            m.txid = c.getString(12); m.peerread = c.getInt(13) == 1;
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
                // Retain typed metadata so only display labels are translated.
                " (SELECT m.body" +
                "   FROM messages m WHERE m.pubkey=ct.pubkey ORDER BY ts DESC, rowid DESC LIMIT 1)," +
                " (SELECT MAX(ts) FROM messages m WHERE m.pubkey=ct.pubkey)," +
                " (SELECT COUNT(*) FROM messages m WHERE m.pubkey=ct.pubkey AND incoming=1 AND read=0)," +
                " (SELECT m.state FROM messages m WHERE m.pubkey=ct.pubkey ORDER BY ts DESC, rowid DESC LIMIT 1)" +
                " FROM (SELECT pubkey, name, added FROM contacts" +
                "       UNION SELECT DISTINCT pubkey, pubkey, 0 FROM messages WHERE pubkey NOT IN (SELECT pubkey FROM contacts)) ct" +
                " ORDER BY 4 DESC, ct.added ASC", null);   // SQLite sorts NULL last under DESC
        while (c.moveToNext()) {
            Thread t = new Thread();
            t.pubkey = c.getString(0);
            t.name = c.getString(1);
            t.lastBody = c.isNull(2) ? "" : c.getString(2);
            t.lastTs = c.isNull(3) ? 0 : c.getLong(3);
            t.unread = c.getInt(4);
            t.lastState = c.isNull(5) ? "" : c.getString(5);
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

    /** A received name fills an unnamed contact AND follows a contact whose name the app
     *  itself learned from an earlier message (2.0.35+). A name the user typed never
     *  changes: it matches neither the bare key nor any name the sender ever declared. */
    public void ensureContact(String zPubkey, String zSenderName) {
        if (!ChatSchema.isPeerKey(zPubkey)) return;   // never store a key that could not be sent to
        String name = zSenderName == null ? "" : zSenderName.trim();
        ContentValues v = new ContentValues();
        v.put("pubkey", zPubkey);
        v.put("name", name.isEmpty() ? zPubkey : name);
        v.put("added", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict("contacts", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (!unnamedContact(zPubkey, name)) {
            ContentValues renamed = new ContentValues();
            renamed.put("name", name);
            // Conditional SQL also preserves a local rename racing this inbox callback.
            db.update("contacts", renamed,
                    "pubkey=? AND (trim(name)='' OR lower(trim(name))=lower(?)"
                    + " OR lower(trim(name)) IN (SELECT lower(trim(name)) FROM messages WHERE pubkey=? AND incoming=1 AND trim(name)<>''))",
                    new String[]{zPubkey, zPubkey, zPubkey});
        }
    }

    static boolean unnamedContact(String pubkey, String name) {
        return name == null || name.trim().isEmpty() || name.trim().equalsIgnoreCase(pubkey);
    }

    public void putContact(String zPubkey, String zName) {
        ensureContact(zPubkey, zName);
        ContentValues v = new ContentValues();
        v.put("name", (zName == null || zName.equals("")) ? zPubkey : zName);
        getWritableDatabase().update("contacts", v, "pubkey=?", new String[]{zPubkey});
    }

    /** True if a contact row exists (user-added, or auto-created by a real message). */
    public boolean hasContact(String zPubkey) {
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM contacts WHERE pubkey=?", new String[]{zPubkey});
        boolean has = c.moveToNext();
        c.close();
        return has;
    }

    public String contactName(String zPubkey) {
        Cursor c = getReadableDatabase().rawQuery("SELECT name FROM contacts WHERE pubkey=?", new String[]{zPubkey});
        String n = c.moveToNext() ? c.getString(0) : null;
        c.close();
        return n == null ? zPubkey : n;
    }

    /** A message's own timestamp, 0 if unknown (receipt ref resolution). */
    public long messageTs(String zMsgId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT ts FROM messages WHERE msgid=?", new String[]{zMsgId});
        long t = c.moveToNext() ? c.getLong(0) : 0;
        c.close();
        return t;
    }

    /** Read receipt from the peer: mark our outgoing messages up to zUptoTs as peer-read. */
    public void markPeerRead(String zPubkey, long zUptoTs) {
        getWritableDatabase().execSQL(
                "UPDATE messages SET peerread=1 WHERE pubkey=? AND incoming=0 AND ts<=?",
                new Object[]{zPubkey, zUptoTs});
    }

    public void setContactPayAddr(String zPubkey, String zAddr) {
        if (zAddr != null && !zAddr.isEmpty() && !ChatSchema.isPayAddr(zAddr)) return;   // 2.2.123: an address, or nothing
        ContentValues v = new ContentValues();
        v.put("payaddr", zAddr);
        getWritableDatabase().update("contacts", v, "pubkey=?", new String[]{zPubkey});
    }

    public String contactPayAddr(String zPubkey) {
        Cursor c = getReadableDatabase().rawQuery("SELECT payaddr FROM contacts WHERE pubkey=?", new String[]{zPubkey});
        String a = c.moveToNext() ? c.getString(0) : "";
        c.close();
        return ChatSchema.isPayAddr(a) ? a : "";   // 2.2.123: never hand out text that is not exactly an address
    }

    /** Receipt dedupe: the newest incoming msgid we have receipted to this contact. */
    public String lastReceipt(String zPubkey) {
        Cursor c = getReadableDatabase().rawQuery("SELECT lastreceipt FROM contacts WHERE pubkey=?", new String[]{zPubkey});
        String a = c.moveToNext() ? c.getString(0) : "";
        c.close();
        return a == null ? "" : a;
    }

    public void setLastReceipt(String zPubkey, String zMsgId) {
        ContentValues v = new ContentValues();
        v.put("lastreceipt", zMsgId);
        getWritableDatabase().update("contacts", v, "pubkey=?", new String[]{zPubkey});
    }

    /** Newest incoming message in a thread (for receipts). */
    public Msg latestIncoming(String zPubkey) {
        ArrayList<Msg> all = thread(zPubkey);
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).incoming) return all.get(i);
        return null;
    }

    public void deleteMessage(String zMsgId) {
        getWritableDatabase().delete("messages", "msgid=?", new String[]{zMsgId});
    }

    public void removeContact(String zPubkey) {
        getWritableDatabase().delete("contacts", "pubkey=?", new String[]{zPubkey});
        getWritableDatabase().delete("messages", "pubkey=?", new String[]{zPubkey});
    }

    /** Clear a conversation but KEEP the contact (it stays in the new-chat picker).
     *  This is "Delete chat"; {@link #removeContact} is the explicit "Delete contact". */
    public void clearMessages(String zPubkey) {
        getWritableDatabase().delete("messages", "pubkey=?", new String[]{zPubkey});
    }

    /** Every saved keets contact (name + pubkey), for the new-chat picker — includes
     *  contacts with no messages. Name falls back to the full pubkey (never truncated). */
    public ArrayList<Thread> contactList() {
        ArrayList<Thread> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT pubkey, name FROM contacts ORDER BY name COLLATE NOCASE", null);
        while (c.moveToNext()) {
            Thread t = new Thread();
            t.pubkey = c.getString(0);
            t.name = c.getString(1);
            out.add(t);
        }
        c.close();
        return out;
    }
}
