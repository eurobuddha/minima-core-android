package com.eurobuddha.minimacore.chat;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import android.app.Application;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.minimacore.utils.MinimaCMD;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An inbox row's sender reaches `keets action:send to:` run as admin: a mailbox envelope whose
 *  "from" carries text after the 64 hex digits (the sidecar's hex decode stops there, so the
 *  signature still verifies) must never become a contact, a receipt or a stored message. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ChatDrainerInboxTest {
    static final String PEER = "0x" + "AB".repeat(32), ATTACKER = "0x" + "66".repeat(32);
    static final String SEND = "send amount:1000 address:" + ATTACKER + " tokenid:0x00";

    static JSONObject row(String id, Object from) {
        JSONObject r = new JSONObject();
        r.put("id", id); r.put("from", from); r.put("ts", 1L); r.put("via", "mailbox");
        r.put("payload", Base64.getEncoder().encodeToString(ChatSchema.encode("n", "hi " + id, "").getBytes()));
        return r;
    }

    @Test public void aSenderOrIdThatIsNotHexNeverReachesACommandOrTheDatabase() throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(), () -> false);
        ChatDb db = ChatDb.get(RuntimeEnvironment.getApplication());
        JSONArray inbox = new JSONArray();
        inbox.add(row("00112233445566778899aabbccddeeff", PEER + ";" + SEND));
        inbox.add(row("00112233445566778899aabbccddee01", PEER + " amount:1000"));
        inbox.add(row("00112233445566778899aabbccddee02", PEER.substring(0, 60)));
        inbox.add(row("00112233445566778899aabbccddee03", null));
        inbox.add(row("0011;" + SEND, PEER));
        inbox.add(row("00112233445566778899aabbccddee04", PEER));   // the one real message
        JSONObject reply = new JSONObject();
        reply.put("status", true); reply.put("response", inbox);
        List<String> ran = new CopyOnWriteArrayList<>();
        try (MockedStatic<MinimaCMD> cmd = mockStatic(MinimaCMD.class)) {
            cmd.when(() -> MinimaCMD.execute(anyString())).thenAnswer(a -> {
                String c = a.getArgument(0); ran.add(c);
                if (c.equals("keets action:inbox")) return reply;
                JSONObject ok = new JSONObject(); ok.put("status", true); ok.put("response", new JSONObject()); return ok;
            });
            java.util.concurrent.Executor real = ChatDrainer.receipts;
            ChatDrainer.receipts = Runnable::run;   // receipts on this thread: the static mock is thread-local
            try { ChatDrainer.drainOnce(mock(MinimaService.class), db); }
            finally { ChatDrainer.receipts = real; }
        }
        for (String c : ran) {
            assertFalse("a stranger's text reached a node command: " + c, c.contains(";") || c.contains(ATTACKER) || c.contains("amount:"));
        }
        assertTrue("the real message gets its receipt", ran.stream().anyMatch(c -> c.startsWith("keets action:send to:" + PEER + " datahex:0x")));
        assertTrue(ran.contains("keets action:ack id:00112233445566778899aabbccddee04"));
        for (String id : new String[]{"00112233445566778899aabbccddeeff", "00112233445566778899aabbccddee01",
                "00112233445566778899aabbccddee02", "00112233445566778899aabbccddee03"})
            assertTrue("a refused row is acked so it never comes back: " + id, ran.contains("keets action:ack id:" + id));
        assertTrue(db.hasContact(PEER));
        assertFalse(db.hasContact(PEER + ";" + SEND));
        assertFalse(db.hasContact(PEER + " amount:1000"));
        assertFalse(db.hasContact(PEER.substring(0, 60)));
    }

    @Test public void mediaRefsAreTheSidecarShapeOnly() {
        String drive = "e".repeat(64), hash = "F".repeat(64);
        for (String ok : new String[]{"keets://" + drive + "/chat/img-1763900000000.jpg?hash=" + hash,
                "keets://" + drive + "/chat/vn-1763900000000.m4a?hash=" + hash, "keets://" + drive + "/salon/a%20b.png"})
            assertTrue(ok, ChatImages.mediaRef(ok));
        for (String bad : new String[]{null, "", "keets://" + drive + "/x;" + SEND, "keets://" + drive + "/x\"\u0000",
                "keets://" + drive + "/x'", "keets://" + drive + "/x\nsend", "keets://abc/x.jpg", "https://" + drive + "/x"})
            assertFalse(String.valueOf(bad), ChatImages.mediaRef(bad));
    }
}
