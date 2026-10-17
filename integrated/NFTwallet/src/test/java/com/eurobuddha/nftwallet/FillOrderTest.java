package com.eurobuddha.nftwallet;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The slot-fill rule for multi-image selection: the tapped slot first, then forward through the
 * collection, wrapping around, skipping slots that already hold an image.
 *
 * Mirrors MintView.fillOrder — kept in step by hand because MintView is an Android View class and
 * cannot be instantiated in a JVM test.
 */
public class FillOrderTest {

    private static List<Integer> fillOrder(int startIdx, int size, int count, Set<Integer> taken) {
        List<Integer> out = new ArrayList<>();
        if (count <= 0) return out;
        out.add(startIdx);
        for (int step = 1; step < size && out.size() < count; step++) {
            int idx = ((startIdx - 1 + step) % size) + 1;
            if (!taken.contains(idx)) out.add(idx);
        }
        return out;
    }

    private static Set<Integer> taken(Integer... ids) { return new HashSet<>(Arrays.asList(ids)); }

    /** Empty collection, pick 10 from the top: they land on slots 1..10 in order. */
    @Test public void fillsFirstTenOfAnEmptyCollection() {
        assertEquals(Arrays.asList(1,2,3,4,5,6,7,8,9,10),
                fillOrder(1, 20, 10, taken()));
    }

    /** Occupied slots are skipped, so a part-finished collection completes in one pass. */
    @Test public void skipsSlotsThatAlreadyHaveImages() {
        assertEquals(Arrays.asList(1,3,6,7),
                fillOrder(1, 8, 4, taken(2, 4, 5)));
    }

    /** Starting mid-collection wraps around to the beginning. */
    @Test public void wrapsAroundFromTheTappedSlot() {
        assertEquals(Arrays.asList(4,5,1,2),
                fillOrder(4, 5, 4, taken(3)));
    }

    /** The tapped slot is always first even when filled — that is how you redo a single image. */
    @Test public void tappedSlotIsReplacedEvenIfFilled() {
        assertEquals(Arrays.asList(3), fillOrder(3, 5, 1, taken(3)));
        assertEquals(Arrays.asList(3, 4), fillOrder(3, 5, 2, taken(1, 2, 3)));
    }

    /** More images than empty slots: the list stops, and the caller reports the leftovers. */
    @Test public void neverExceedsTheCollectionSize() {
        List<Integer> out = fillOrder(1, 3, 10, taken());
        assertEquals(Arrays.asList(1, 2, 3), out);
    }

    @Test public void emptySelectionFillsNothing() {
        assertEquals(0, fillOrder(1, 5, 0, taken()).size());
    }
}
