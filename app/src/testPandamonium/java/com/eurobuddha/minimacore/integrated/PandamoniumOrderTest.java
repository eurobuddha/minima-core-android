package com.eurobuddha.minimacore.integrated;

import org.junit.Test;
import java.util.List;
import java.util.HashSet;
import static org.junit.Assert.*;

/** 2.2.34 removed AtomiX, PandaPools and PandaDEX (indices 2, 3, 5): later indices moved down by 3. Saved orders
 *  are keyed by class name, so a user's order survives. */
public class PandamoniumOrderTest {
    @Test public void savedOrderSurvivesReloadAndNewAppsAreAppended() {
        String first = PandamoniumDestinations.CLASSES[9];
        String second = PandamoniumDestinations.CLASSES[2];
        List<Integer> order = PandamoniumOrder.decode(first + "," + second);
        assertEquals(Integer.valueOf(0), order.get(0));
        assertEquals(Integer.valueOf(9), order.get(1));
        assertEquals(Integer.valueOf(2), order.get(2));
        assertEquals(PandamoniumDestinations.CLASSES.length, order.size());
        assertEquals(order, PandamoniumOrder.decode(PandamoniumOrder.encode(order)));
    }

    @Test public void unknownAndDuplicateTargetsCannotChangeAllowlist() {
        String saved = "external.Untrusted," + PandamoniumDestinations.CLASSES[3]
                + "," + PandamoniumDestinations.CLASSES[0] + "," + PandamoniumDestinations.CLASSES[3];
        List<Integer> order = PandamoniumOrder.decode(saved);
        assertEquals(Integer.valueOf(0), order.get(0));
        assertEquals(Integer.valueOf(3), order.get(1));
        assertEquals(PandamoniumDestinations.CLASSES.length, new HashSet<>(order).size());
        assertEquals(order.size(), new HashSet<>(order).size());
    }
    @Test public void walletAppendsWithoutChangingExistingDestinationIdentities() {
        int wallet = PandamoniumDestinations.indexFor("com.eurobuddha.wallet.MainActivity");
        assertEquals(13, wallet);
        assertEquals("minimaWallet", PandamoniumDestinations.NAMES[wallet]);
        assertEquals(PandamoniumDestinations.NAMES.length, PandamoniumDestinations.CLASSES.length);
        assertEquals(PandamoniumDestinations.NAMES.length, PandamoniumDestinations.ICONS.length);
        String saved = String.join(",", java.util.Arrays.copyOfRange(PandamoniumDestinations.CLASSES, 1, wallet));
        List<Integer> order = PandamoniumOrder.decode(saved);
        for (int i = 0; i <= wallet; i++) assertEquals(Integer.valueOf(i), order.get(i));
        assertEquals(order, PandamoniumOrder.decode(PandamoniumOrder.encode(order)));
    }

    @Test public void keyUsesAppendsToExistingDestinations() {
        assertEquals(16, PandamoniumDestinations.indexFor("com.eurobuddha.keyreuses.MainActivity"));
        assertEquals("KeyUses", PandamoniumDestinations.NAMES[16]);
    }

    @Test public void creativeAppsAppendAndPreserveSavedOrder() {
        assertEquals(14, PandamoniumDestinations.indexFor("com.eurobuddha.statenft.MainActivity"));
        assertEquals(15, PandamoniumDestinations.indexFor("com.eurobuddha.salon.MainActivity"));
        assertEquals(14, PandamoniumDestinations.indexFor("com.eurobuddha.statenft.FiltrActivity"));
        String prior = String.join(",", java.util.Arrays.copyOfRange(PandamoniumDestinations.CLASSES, 1, 14));
        List<Integer> order = PandamoniumOrder.decode(prior);
        for (int i = 0; i < 16; i++) assertEquals(Integer.valueOf(i), order.get(i));
        assertEquals(order, PandamoniumOrder.decode(PandamoniumOrder.encode(order)));
        assertEquals(18, PandamoniumDestinations.NAMES.length);
        assertEquals(18, PandamoniumDestinations.ICONS.length);
    }

    @Test public void faucetAppendsWithoutReorderingExistingSavedApps() {
        assertEquals(17,PandamoniumDestinations.indexFor("com.eurobuddha.faucet.MainActivity"));
        assertEquals("Minima Faucet",PandamoniumDestinations.NAMES[17]);
        String saved=String.join(",",java.util.Arrays.copyOfRange(PandamoniumDestinations.CLASSES,1,17));
        List<Integer> order=PandamoniumOrder.decode(saved);
        for(int i=0;i<18;i++)assertEquals(Integer.valueOf(i),order.get(i));
        assertEquals(order,PandamoniumOrder.decode(PandamoniumOrder.encode(order)));
    }
}
