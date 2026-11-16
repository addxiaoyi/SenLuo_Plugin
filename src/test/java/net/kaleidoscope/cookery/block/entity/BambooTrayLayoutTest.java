package net.kaleidoscope.cookery.block.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class BambooTrayLayoutTest {
    @ParameterizedTest
    @CsvSource({"0,0", "1,1", "2,2", "16,2", "17,3", "32,3", "33,4", "48,4", "49,5", "64,5", "99,5"})
    void copyCountsFollowOfficialStackBoundaries(int count, int expected) {
        assertEquals(expected, BambooTrayLayout.copies(count));
    }

    @Test
    void pinnedPositionAndRandomSequenceKeepTheirOrder() {
        var positions = BambooTrayLayout.positions(8, 64, 8, 0);
        var first = positions.getFirst();
        var last = positions.getLast();
        assertEquals(.26413959192860154, first.x(), 1e-15);
        assertEquals(.2136217493958034, first.z(), 1e-15);
        assertEquals(42.811897f, first.angleDegrees());
        assertEquals(.29905029337629263, last.x(), 1e-15);
        assertEquals(.24290216741228826, last.z(), 1e-15);
        assertEquals(1.1769104f, last.angleDegrees());
        assertEquals(positions, BambooTrayLayout.positions(8, 64, 8, 0));
        assertThrows(UnsupportedOperationException.class, positions::clear);
    }

    @Test
    void signedBlockCoordinatesAndSlotSeedArePreserved() {
        var first = BambooTrayLayout.positions(-7, -64, -11, 3).getFirst();
        assertEquals(.7126902548915512, first.x(), 1e-15);
        assertEquals(.7749398849046898, first.z(), 1e-15);
        assertEquals(.155, first.y(), 1e-15);
        assertEquals(41.589348f, first.angleDegrees());
        assertNotEquals(first, BambooTrayLayout.positions(-7, -64, -11, 2).getFirst());
    }

    @Test
    void copiesStayInTheirQuadrantsAtTheOfficialLayerHeights() {
        for (int slot = 0; slot < BambooTrayLayout.SLOTS; slot++) {
            var positions = BambooTrayLayout.positions(30_000_000, 319, -30_000_000, slot);
            assertEquals(5, positions.size());
            double centerX = slot % 2 == 0 ? .25 : .75;
            double centerZ = slot < 2 ? .25 : .75;
            for (int copy = 0; copy < positions.size(); copy++) {
                var position = positions.get(copy);
                assertTrue(Math.abs(position.x() - centerX) <= .05);
                assertTrue(Math.abs(position.z() - centerZ) <= .05);
                assertEquals(.14 + copy * .01 + slot * .005, position.y(), 1e-15);
                assertTrue(position.angleDegrees() >= 0 && position.angleDegrees() < 360);
            }
        }
    }

    @Test
    void invalidSlotsAndStackCountsAreRejected() {
        assertThrows(IndexOutOfBoundsException.class, () -> BambooTrayLayout.positions(0, 64, 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> BambooTrayLayout.positions(0, 64, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> BambooTrayLayout.copies(-1));
        assertEquals(5, BambooTrayLayout.copies(Integer.MAX_VALUE));
    }
}
