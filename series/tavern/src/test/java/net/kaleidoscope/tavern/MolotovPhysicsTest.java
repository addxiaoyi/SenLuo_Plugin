package net.kaleidoscope.tavern;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static net.kaleidoscope.tavern.MolotovPhysics.Storage.*;
import static org.junit.jupiter.api.Assertions.*;

class MolotovPhysicsTest {
    @Test void releaseThresholdAndFullUseDurationMatchTheSource() {
        assertFalse(MolotovPhysics.charged(9)); assertFalse(MolotovPhysics.charged(-1));
        assertTrue(MolotovPhysics.charged(10)); assertTrue(MolotovPhysics.charged(72000));
        assertEquals(72000, MolotovPhysics.USE_TICKS);
    }
    @Test void zeroSpreadStillInheritsHorizontalAndOnlyAirborneVerticalShooterMovement() {
        var random = new Random() { @Override public double nextDouble() { return 0.5; } };
        assertEquals(new Vector(1, 0, 2.8), MolotovPhysics.thrown(0, 0, new Vector(1, 3, 2), true, random));
        assertEquals(new Vector(1, 3, 2.8), MolotovPhysics.thrown(0, 0, new Vector(1, 3, 2), false, random));
    }
    @Test void trajectoryKeepsSourceTriangularSpreadWithoutRenormalizingItAway() {
        var random = new Random() { int n; @Override public double nextDouble() { return (n++ & 1) == 0 ? 1 : 0; } };
        Vector velocity = MolotovPhysics.thrown(0, 0, new Vector(), true, random);
        assertEquals(0.0172275 * 0.8, velocity.getX(), 1e-12);
        assertEquals(0.0172275 * 0.8, velocity.getY(), 1e-12);
        assertEquals((1 + 0.0172275) * 0.8, velocity.getZ(), 1e-12);
    }
    @Test void throwSoundPitchKeepsTheOriginalRangeAndFormula() {
        assertEquals(0.5f, MolotovPhysics.throwPitch(0));
        assertEquals(1f / 3, MolotovPhysics.throwPitch(1), 1e-6);
        assertEquals(0.4f, MolotovPhysics.throwPitch(0.5f));
    }
    @Test void innerDiscAlwaysIgnitesButTheActualSourceNeverVisitsOutsideTheSevenBySevenSquare() {
        var random = new Random() { @Override public float nextFloat() { return 1; } };
        var columns = MolotovPhysics.fireColumns(random);
        assertEquals(29, columns.size());
        assertTrue(columns.stream().allMatch(c -> c.x() * c.x() + c.z() * c.z() <= 9));
    }
    @Test void edgeProbabilityIncludesAllTwentyCornerColumnsWhenRandomIsZero() {
        var random = new Random() { @Override public float nextFloat() { return 0; } };
        var columns = MolotovPhysics.fireColumns(random);
        assertEquals(49, columns.size());
        assertTrue(columns.contains(new MolotovPhysics.Column(3, 3)));
        assertTrue(columns.stream().allMatch(c -> Math.abs(c.x()) <= 3 && Math.abs(c.z()) <= 3));
    }
    @Test void edgeChanceUsesTheSourceFloatRoundingAndStrictComparison() {
        float threshold = (1 - (float) (Math.sqrt(18) - 3) / 2) * 0.6f;
        assertFalse(MolotovPhysics.fireColumns(new Random() { @Override public float nextFloat() { return threshold; } })
                .contains(new MolotovPhysics.Column(3, 3)));
        assertTrue(MolotovPhysics.fireColumns(new Random() { @Override public float nextFloat() { return Math.nextDown(threshold); } })
                .contains(new MolotovPhysics.Column(3, 3)));
    }
    @Test void storageKindsRetainOneThreeSixAndNineSlots() {
        assertEquals(0, GROUND.slots); assertEquals(1, HOLDER.slots); assertEquals(3, TILTED.slots);
        assertEquals(6, CIRCULAR.slots); assertEquals(9, CELLAR.slots);
        assertThrows(IllegalArgumentException.class, () -> MolotovPhysics.Storage.of("kaleidoscope_tavern:unrelated"));
    }
    @Test void tiltedRackSlotSelectionRotatesWithTheSourceLocalCoordinates() {
        assertEquals(0, MolotovPhysics.clickedSlot(TILTED, BlockFace.NORTH, BlockFace.NORTH, 0.9, 0, 0));
        assertEquals(1, MolotovPhysics.clickedSlot(TILTED, BlockFace.SOUTH, BlockFace.SOUTH, 0.5, 0, 0));
        assertEquals(2, MolotovPhysics.clickedSlot(TILTED, BlockFace.EAST, BlockFace.EAST, 0, 0, 0.1));
    }
    @Test void cellarSelectionRequiresTheFrontAndRetainsTheNineGridCoordinates() {
        assertEquals(-1, MolotovPhysics.clickedSlot(CELLAR, BlockFace.NORTH, BlockFace.SOUTH, .9, .9, 0));
        assertEquals(0, MolotovPhysics.clickedSlot(CELLAR, BlockFace.NORTH, BlockFace.NORTH, .9, .9, 0));
        assertEquals(8, MolotovPhysics.clickedSlot(CELLAR, BlockFace.NORTH, BlockFace.NORTH, .1, .1, 0));
    }
    @Test void circularSelectionUsesTheSourceStrictSixtyDegreeSectors() {
        assertEquals(4, MolotovPhysics.clickedSlot(CIRCULAR, BlockFace.SOUTH, BlockFace.UP, 1, 0, .5));
        assertEquals(3, MolotovPhysics.clickedSlot(CIRCULAR, BlockFace.SOUTH, BlockFace.UP, .5, 0, 0));
        assertEquals(2, MolotovPhysics.clickedSlot(CIRCULAR, BlockFace.SOUTH, BlockFace.UP, 0, 0, .5));
        assertEquals(0, MolotovPhysics.clickedSlot(CIRCULAR, BlockFace.SOUTH, BlockFace.UP, .5, 0, 1));
    }
    @Test void redstoneLaunchPositionsAndVelocityRangesMatchEachSourceStorage() {
        var block = new Location(null, 2, 3, 4);
        assertEquals(new Location(null, 2.5, 3.875, 4), MolotovPhysics.shootPosition(HOLDER, block, BlockFace.NORTH));
        assertEquals(new Location(null, 2.5, 3.875, 5), MolotovPhysics.shootPosition(TILTED, block, BlockFace.NORTH));
        assertEquals(new Location(null, 2.5, 3.5, 4.5), MolotovPhysics.shootPosition(CIRCULAR, block, BlockFace.NORTH));
        assertEquals(new Vector(0, .1875, -.5), MolotovPhysics.rackVelocity(HOLDER, BlockFace.NORTH, 0));
        assertEquals(new Vector(0, .75 * 1.5, 1.5), MolotovPhysics.rackVelocity(TILTED, BlockFace.NORTH, 1));
        assertEquals(new Vector(0, 2.5, 0), MolotovPhysics.rackVelocity(CIRCULAR, BlockFace.NORTH, 1));
        assertEquals(new Vector(2.5, .25, 0), MolotovPhysics.rackVelocity(CELLAR, BlockFace.EAST, 1));
    }
    @Test void bottleCollisionUsesTheActualSixByFourteenBySixSourceOutline() {
        var box = MolotovPhysics.bottleBox(new Location(null, 0, 10, 0));
        assertEquals(6.0 / 16, box.getWidthX()); assertEquals(14.0 / 16, box.getHeight());
        assertNotNull(box.rayTrace(new Vector(.5, 10.5, -2), new Vector(0, 0, 1), 4));
        assertNull(box.rayTrace(new Vector(.1, 10.5, -2), new Vector(0, 0, 1), 4));
    }
}
