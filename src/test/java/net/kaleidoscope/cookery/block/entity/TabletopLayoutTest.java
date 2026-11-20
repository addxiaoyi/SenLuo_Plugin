package net.kaleidoscope.cookery.block.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TabletopLayoutTest {
    @Test void sourceLayoutScalesOffsetsBeforeRotatingAndLowersBlockItems() {
        var x = TabletopLayout.pose(4, 0, 0, false);
        assertEquals(-.1625f, x.x(), 1e-6); assertEquals(.195f, x.z(), 1e-6);
        assertEquals(.8125f, x.y(), 1e-6); assertEquals(Math.PI, x.yaw(), 1e-6);
        var z = TabletopLayout.pose(4, 0, 1, true);
        assertEquals(-.195f, z.x(), 1e-6); assertEquals(-.1625f, z.z(), 1e-6);
        assertEquals(.65f, z.y(), 1e-6); assertEquals(Math.PI / 2, z.yaw(), 1e-6);
        var last = TabletopLayout.pose(4, 3, 0, false);
        assertEquals(.832f, last.y(), 1e-6);
        var single = TabletopLayout.pose(1, 0, 1, false);
        assertEquals(0, single.x(), 1e-6); assertEquals(0, single.z(), 1e-6);
    }
    @Test void unsupportedCapacityCannotIndexUnrelatedTransforms() {
        assertThrows(IllegalArgumentException.class, () -> TabletopLayout.pose(5, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> TabletopLayout.pose(0, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> TabletopLayout.pose(4, 4, 0, false));
    }
}
