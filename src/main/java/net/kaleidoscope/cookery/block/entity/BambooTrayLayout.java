package net.kaleidoscope.cookery.block.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Vanilla display transforms for the pinned upstream bamboo-tray renderer. */
final class BambooTrayLayout {
    static final int SLOTS = 4;
    static final int MAX_COPIES = 5;
    static final byte FIXED_TRANSFORM = 8;
    static final float SCALE = .5f;
    private static final int ITEMS_PER_LAYER = 16;
    private static final long HORIZONTAL_MASK = (1L << 26) - 1;
    private static final long VERTICAL_MASK = (1L << 12) - 1;
    private static final long SLOT_SEED_STEP = 31;

    record Copy(double x, double y, double z, float angleDegrees) {}

    private BambooTrayLayout() {}

    static int copies(int count) {
        if (count < 0) throw new IllegalArgumentException("Bamboo tray stack count must not be negative");
        if (count <= 1) return count;
        return Math.min(MAX_COPIES, (count - 1) / ITEMS_PER_LAYER + 2);
    }

    static List<Copy> positions(int x, int y, int z, int slot) {
        Objects.checkIndex(slot, SLOTS);
        // RandomSource.create(long) uses LegacyRandomSource, the same 48-bit LCG as java.util.Random.
        long packed = ((x & HORIZONTAL_MASK) << 38) | ((z & HORIZONTAL_MASK) << 12) | (y & VERTICAL_MASK);
        Random random = new Random(packed + slot * SLOT_SEED_STEP);
        List<Copy> positions = new ArrayList<>(MAX_COPIES);
        for (int copy = 0; copy < MAX_COPIES; copy++) {
            double dx = (random.nextDouble() - .5) * .1;
            double dz = (random.nextDouble() - .5) * .1;
            positions.add(new Copy(.25 + (slot % 2) * .5 + dx,
                    .14 + copy * .01 + slot * .005,
                    .25 + (slot / 2) * .5 + dz, random.nextFloat() * 360));
        }
        return List.copyOf(positions);
    }
}
