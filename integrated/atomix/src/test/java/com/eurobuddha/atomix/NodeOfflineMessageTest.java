package com.eurobuddha.atomix;
import com.eurobuddha.atomix.integratedcomms.NodeApi;
import org.junit.Test;
import static org.junit.Assert.*;

/** Silence and refusal are different faults with opposite remedies. The app must never answer one with
 *  the other — live 2026-09-21, a wedged node was reported as "enable AtomiX in Minima Core → Apps". */
public class NodeOfflineMessageTest {

    @Test public void unreadyEmbeddedNodeExplainsReadinessWithoutPairing() {
        String m = NodeApi.offlineMessage(NodeApi.Offline.REFUSED, false);
        assertTrue(m.contains("not ready")); assertFalse(m.contains("Apps"));
    }

    @Test public void silenceAfterAGoodReplyBlamesTheNodeNotThePermission() {
        String m = NodeApi.offlineMessage(NodeApi.Offline.UNREACHABLE, true);
        assertTrue(m.contains("stopped responding"));
        assertTrue(m.contains("busy"));
        assertFalse(m.contains("Enable AtomiX in Minima Core"));
    }

    @Test public void silenceWithNoReplyEverStaysHonestlyAmbiguous() {
        String m = NodeApi.offlineMessage(NodeApi.Offline.UNREACHABLE, false);
        assertTrue(m.contains("Waiting for the embedded node"));
        assertTrue(m.contains("Start Minima Core")); assertFalse(m.contains("Apps"));
    }
}
