package com.eurobuddha.minimacore.chat;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** A database written before 2.2.110 can hold a sender with text after the key; opening its thread sent
 *  a read receipt to it (`keets action:send to:<that text>`, run as admin). The upgrade removes those rows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ChatDbPurgeTest {
    static final String PEER = "0x" + "AB".repeat(32);
    static final String POISON = PEER + ";send amount:1000 address:0x" + "66".repeat(32) + " tokenid:0x00";

    @After public void forgetTheChatDb() throws Exception {
        java.lang.reflect.Field f = ChatDb.class.getDeclaredField("instance");
        f.setAccessible(true);
        ChatDb db = (ChatDb) f.get(null);
        if (db != null) db.close();
        f.set(null, null);
        RuntimeEnvironment.getApplication().deleteDatabase("keetchat.db");
    }

    @Test public void theUpgradeRemovesRowsWhoseKeyIsNotAKeetsKeyAndKeepsTheRest() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        forgetTheChatDb();
        // The version 3 database of 2.2.109, as that app created it.
        java.io.File file = c.getDatabasePath("keetchat.db");
        file.getParentFile().mkdirs();
        SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(file, null);
        old.execSQL("CREATE TABLE messages (msgid TEXT PRIMARY KEY, pubkey TEXT NOT NULL, incoming INTEGER NOT NULL,"
                + "body TEXT NOT NULL, name TEXT DEFAULT '', ref TEXT DEFAULT '', ts INTEGER NOT NULL, via TEXT DEFAULT '',"
                + "read INTEGER DEFAULT 0, state TEXT DEFAULT '', amt TEXT DEFAULT '', tokname TEXT DEFAULT '',"
                + "txid TEXT DEFAULT '', peerread INTEGER DEFAULT 0)");
        old.execSQL("CREATE TABLE contacts (pubkey TEXT PRIMARY KEY, name TEXT NOT NULL, maximapk TEXT DEFAULT '',"
                + "payaddr TEXT DEFAULT '', lastreceipt TEXT DEFAULT '', added INTEGER NOT NULL)");
        for (String key : new String[]{PEER, POISON, PEER + " amount:1", PEER.substring(0, 60)})
            old.execSQL("INSERT INTO contacts (pubkey,name,added) VALUES (?,?,1)", new Object[]{key, "n"});
        int id = 0;
        for (String key : new String[]{PEER, PEER, POISON, POISON, "stranger"})
            old.execSQL("INSERT INTO messages (msgid,pubkey,incoming,body,ts) VALUES (?,?,1,'hi',1)", new Object[]{"m" + id++, key});
        old.setVersion(3);
        old.close();

        ChatDb db = ChatDb.get(c);
        assertTrue(db.hasContact(PEER));
        assertEquals(2, db.thread(PEER).size());
        assertEquals("only the real contact is left", 1, db.contactList().size());
        assertEquals("only the real thread is left", 1, db.threads().size());
        assertFalse(db.hasContact(POISON));
        assertTrue(db.thread(POISON).isEmpty());
        assertTrue(db.thread("stranger").isEmpty());
        // 2.2.122: the purge does not move the database version, so an older copy of the app can still open it
        db.close();
        SQLiteDatabase after = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        try { assertEquals("the chat database stays at version 3", 3, after.getVersion()); } finally { after.close(); }
    }

    // 2.2.123 (review of 2.2.118-2.2.121): a contact's pay address goes into "send address:" run as admin
    @Test public void aPayAddressIsAnAddressOrNothing() throws Exception {
        ChatDb db = ChatDb.get(RuntimeEnvironment.getApplication());
        String hex = "0x" + "CD".repeat(32), mx = "MxG080CRJB1D4NHGRYGNF7Q52FK7023UM3FUUPVD1W1WCQZSA8MDQ25982N842G";
        assertTrue(ChatSchema.isPayAddr(hex)); assertTrue(ChatSchema.isPayAddr(mx));
        for (String bad : new String[]{mx + ";send amount:1000 address:0x" + "66".repeat(32), mx + " burn:1000", hex + "0", "Mx", "", null, mx + "\n", "0X" + "CD".repeat(32)})
            assertFalse(String.valueOf(bad), ChatSchema.isPayAddr(bad));
        db.putContact(PEER, "n");
        db.setContactPayAddr(PEER, mx + ";send amount:1000 address:0x" + "66".repeat(32));
        assertEquals("a bad address is not stored", "", db.contactPayAddr(PEER));
        db.setContactPayAddr(PEER, mx);
        assertEquals(mx, db.contactPayAddr(PEER));
        // a row written by an older copy or a restored file: blanked at the next open, the contact kept
        db.getWritableDatabase().execSQL("UPDATE contacts SET payaddr=? WHERE pubkey=?", new Object[]{mx + ";send amount:1 address:0xEE", PEER});
        assertEquals("never handed out", "", db.contactPayAddr(PEER));
        ChatDb.purgeBadKeys(db.getWritableDatabase());
        assertTrue(db.hasContact(PEER));
        try (android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT payaddr FROM contacts WHERE pubkey=?", new String[]{PEER})) {
            assertTrue(c.moveToNext()); assertEquals("", c.getString(0));
        }
    }

    @Test public void aBadKeyIsNeverStoredAgain() throws Exception {
        ChatDb db = ChatDb.get(RuntimeEnvironment.getApplication());
        db.ensureContact(POISON, "x");
        db.putContact(POISON, "x");
        ChatDb.Msg m = new ChatDb.Msg();
        m.msgid = "p1"; m.pubkey = POISON; m.body = "hi"; m.ts = 1; m.incoming = true;
        db.addMessage(m);
        assertTrue(db.contactList().isEmpty());
        assertTrue(db.threads().isEmpty());
    }
}
