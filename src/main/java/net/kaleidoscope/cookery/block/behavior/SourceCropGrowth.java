package net.kaleidoscope.cookery.block.behavior;

/** The inherited 1.20.1 CropBlock rules used by the three source vegetables. */
final class SourceCropGrowth {
    static final int MAX_AGE = 7;
    static final int PICK_RESET_AGE = 5;
    static final int GROW_LIGHT = 9;
    static final int SURVIVAL_LIGHT = 8;

    private SourceCropGrowth() {}

    static float soilContribution(boolean farmland, int moisture, boolean center) {
        if (!farmland) return 0;
        float contribution = moisture > 0 ? 3 : 1;
        return center ? contribution : contribution / 4;
    }

    static float growthSpeed(float soilSpeed, boolean eastWest, boolean northSouth, boolean diagonal) {
        return (eastWest && northSouth) || diagonal ? soilSpeed / 2 : soilSpeed;
    }

    static int randomTickBound(float growthSpeed) {
        return (int) (25 / growthSpeed) + 1;
    }

    static int bonemealAge(int age, int bonus) {
        return Math.min(MAX_AGE, age + bonus);
    }

    static double shapeHeight(int age) {
        return (age + 2) / 16.0;
    }
}
