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
        assertEquals(50, P2PManager.parseSeedPeers(many.toString()).size());
    }
}
