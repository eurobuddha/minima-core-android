package com.eurobuddha.minimacore.main;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.minima.system.network.p2p.P2PManager;

import java.util.Arrays;

/** The BUNDLED node jar (app/libs/minima.jar) takes a published peers list entry only when it is exactly host:port
 *  with a port of 1 to 65535, 50 at most. Before 2.2.128 a port of 70000 in minimapeers.txt threw on the node's
 *  P2PSeedPeers thread, and an uncaught exception there stops the whole app at every start. */
public class BundledJarSeedPeersTest {
    @Test public void aBadEntryInAPublishedListIsLeftOutByTheBundledJar() {
        assertEquals(Arrays.asList("1.2.3.4:9001", "node.example.org:10001"),
                P2PManager.parseSeedPeers("1.2.3.4:9001, node.example.org:10001 ,1.2.3.4:70000,x:-1,5.6.7.8:0,[::1]:9001,evil;quit:9001"));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 5000; i++) many.append("10.").append(i / 250).append('.').append(i % 250).append(".1:9001,");
        assertEquals("every entry is parsed", 5000, P2PManager.parseSeedPeers(many.toString()).size());
        assertEquals("50 are checked", 50, P2PManager.seedChoice(P2PManager.parseSeedPeers(many.toString())).size());
        // 2.2.129: a list cut at the bound loses its last entry, and this machine is never checked
        StringBuilder longList = new StringBuilder();
        while (longList.length() < P2PManager.SEED_LIST_MAX_BYTES - 6) longList.append("x,");
        longList.append("1.2.3.4:9001,5.6.7.8:9001");
        assertEquals(0, P2PManager.parseSeedPeers(P2PManager.cutSeedList(longList.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))).size());
        // 2.2.130: seeding is not asked for more often than its 50 checks take (2.2.129 gated on the checker's queue and never seeded)
        org.junit.Assert.assertTrue(P2PManager.SEED_PEERS_INTERVAL >= P2PManager.SEED_PEERS_MAX * 10000L);
        org.junit.Assert.assertFalse(P2PManager.seedAddressAllowed(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 9001)));
    }
}
