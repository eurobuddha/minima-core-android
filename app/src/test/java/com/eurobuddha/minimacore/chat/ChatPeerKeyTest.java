package com.eurobuddha.minimacore.chat;

import static org.junit.Assert.*;

import org.junit.Test;

/** `keets action:send to:<key>` runs as admin and the node splits it at every ";" and every space: the one
 *  builder refuses any key that is not exactly 0x + 64 hex. */
public class ChatPeerKeyTest {
    static final String PEER = "0x" + "aB".repeat(32);

    @Test public void aRealKeyBuildsTheCommand() {
        assertEquals("keets action:send to:" + PEER + " datahex:0x6869", ChatSchema.sendCommand(PEER, "hi"));
        assertSame("the inbox uses the same pattern object, not a second one", ChatSchema.PEER_KEY, ChatDrainer.PEER_KEY);
    }

    @Test public void anythingElseIsRefusedNeverEscaped() {
        for (String bad : new String[]{null, "", PEER + ";send amount:1000 address:0x" + "66".repeat(32), PEER + " amount:1",
                PEER + "\n", PEER + "\t", " " + PEER, PEER.substring(0, 65), PEER + "0", "0X" + PEER.substring(2),
                PEER.substring(2), "0x" + "zz".repeat(32), PEER + "]", PEER + "\"", "max:" + PEER}) {
            assertFalse(String.valueOf(bad), ChatSchema.isPeerKey(bad));
            try {
                ChatSchema.sendCommand(bad, "hi");
                fail("built a command for " + bad);
            } catch (IllegalArgumentException refused) {
                assertFalse("the refusal must not repeat the bad text", bad != null && !bad.isEmpty() && refused.getMessage().contains(bad));
            }
        }
    }
}
