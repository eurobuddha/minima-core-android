package com.eurobuddha.minimacore.integrated;

import org.junit.Test;
import java.util.List;
import java.util.HashSet;
import static org.junit.Assert.*;

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
}
