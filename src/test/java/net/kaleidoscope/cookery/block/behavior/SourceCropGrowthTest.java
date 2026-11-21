package net.kaleidoscope.cookery.block.behavior;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SourceCropGrowthTest {
    @Test void hydrationAndThreeByThreeSoilPreserveVanillaGrowthSpeed() {
        assertEquals(0, SourceCropGrowth.soilContribution(false, 7, true));
        assertEquals(1, SourceCropGrowth.soilContribution(true, 0, true));
        assertEquals(3, SourceCropGrowth.soilContribution(true, 1, true));
        assertEquals(.25f, SourceCropGrowth.soilContribution(true, 0, false));
        assertEquals(.75f, SourceCropGrowth.soilContribution(true, 7, false));
        float wet = 1 + SourceCropGrowth.soilContribution(true, 7, true)
                + 8 * SourceCropGrowth.soilContribution(true, 7, false);
        float dry = 1 + SourceCropGrowth.soilContribution(true, 0, true)
                + 8 * SourceCropGrowth.soilContribution(true, 0, false);
        assertEquals(10, wet);
        assertEquals(4, dry);
        assertEquals(3, SourceCropGrowth.randomTickBound(wet));
        assertEquals(7, SourceCropGrowth.randomTickBound(dry));
    }

    @Test void straightRowsKeepSpeedWhileCrossRowsAndDiagonalsHalveIt() {
        assertEquals(10, SourceCropGrowth.growthSpeed(10, true, false, false));
        assertEquals(10, SourceCropGrowth.growthSpeed(10, false, true, false));
        assertEquals(5, SourceCropGrowth.growthSpeed(10, true, true, false));
        assertEquals(5, SourceCropGrowth.growthSpeed(10, false, false, true));
        assertEquals(6, SourceCropGrowth.randomTickBound(5));
    }

    @Test void bonemealUsesTwoToFiveAgesAndCapsAtSeven() {
        assertEquals(2, SourceCropGrowth.bonemealAge(0, 2));
        assertEquals(5, SourceCropGrowth.bonemealAge(0, 5));
        assertEquals(7, SourceCropGrowth.bonemealAge(6, 5));
        assertEquals(5, SourceCropGrowth.PICK_RESET_AGE);
        assertEquals(9, SourceCropGrowth.GROW_LIGHT);
        assertEquals(8, SourceCropGrowth.SURVIVAL_LIGHT);
    }

    @Test void sourceSelectionBoxesGrowOnePixelAtEachAge() {
        for (int age = 0; age <= 7; age++) assertEquals((age + 2) / 16d, SourceCropGrowth.shapeHeight(age));
    }
}
