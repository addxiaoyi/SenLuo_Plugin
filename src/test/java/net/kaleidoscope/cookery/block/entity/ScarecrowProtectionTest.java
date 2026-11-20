package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.util.ChunkIndex;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScarecrowProtectionTest {
    @Test void sourceCuboidIncludesDiagonalFarmlandOutsideTheOldSphere() {
        var protection = new ScarecrowController.Protection(.5, 64, .5, 16);
        assertTrue(protection.covers(16, 80, 16));
        assertTrue(protection.covers(-16, 48, -16));
        assertFalse(protection.covers(17, 64, 0));
    }
    @Test void sourceEntityHeightExtendsAboveItsAnchorWithStrictFaceBoundaries() {
        var protection = new ScarecrowController.Protection(.5, 64, .5, 16);
        assertTrue(protection.covers(0, 82, 0));
        assertFalse(protection.covers(0, 83, 0));
        assertFalse(protection.covers(0, 47, 0));
        assertTrue(protection.covers(0, 48, 0));
        var touching = new ScarecrowController.Protection(.25, 64, .25, 16);
        assertFalse(touching.covers(-17, 64, 0));
    }
    @Test void queryUsesEntityWidthAtFractionalAndNegativePositions() {
        var positive = new ScarecrowController.Protection(.9, 64, .9, 16);
        assertTrue(positive.covers(17, 64, 17));
        assertFalse(positive.covers(18, 64, 0));
        var negative = new ScarecrowController.Protection(-.1, 64, -.1, 16);
        assertTrue(negative.covers(-17, 64, -17));
        assertFalse(negative.covers(-18, 64, 0));
    }
    @Test void boundaryBucketsIncludeFullEntityBoundsAndUnregisterWithoutWorldLeakage() {
        var world = mock(World.class); var other = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID()); when(other.getUID()).thenReturn(UUID.randomUUID());
        var index = new ChunkIndex<ScarecrowController.Protection>();
        var protection = new ScarecrowController.Protection(15.9, 64, 15.9, 16);
        // The source entity extends 0.25 blocks beyond its center, including the next bucket.
        index.register(protection, world, 15, 15, protection.indexRadius());
        assertTrue(index.anyMatch(world, 32, 32, p -> p.covers(32, 64, 32)));
        assertFalse(index.anyMatch(other, 32, 32, p -> p.covers(32, 64, 32)));
        index.unregister(protection);
        assertFalse(index.anyMatch(world, 32, 32, p -> p.covers(32, 64, 32)));
    }
}
