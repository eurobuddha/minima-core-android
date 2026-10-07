package com.eurobuddha.minimacore.integrated;

import java.util.ArrayList;
import java.util.List;

/** Persist allowlisted identities, never positions or arbitrary launch targets. */
final class PandamoniumOrder {
    private PandamoniumOrder() {}

    static List<Integer> decode(String saved) {
        List<Integer> order = new ArrayList<>();
        order.add(0); // Core is the home destination, not an integrated app.
        if (saved != null) for (String name : saved.split(",")) {
            for (int i = 1; i < PandamoniumDestinations.CLASSES.length; i++) {
                if (PandamoniumDestinations.CLASSES[i].equals(name) && !order.contains(i)) order.add(i);
            }
        }
        for (int i = 1; i < PandamoniumDestinations.CLASSES.length; i++) {
            if (!order.contains(i)) order.add(i);
        }
        return order;
    }

    static String encode(List<Integer> order) {
        List<String> names = new ArrayList<>();
        for (int destination : order) if (destination > 0 && destination < PandamoniumDestinations.CLASSES.length) {
            names.add(PandamoniumDestinations.CLASSES[destination]);
        }
        return String.join(",", names);
    }
}
