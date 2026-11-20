package net.kaleidoscope.cookery.nms;

import net.momirealms.craftengine.core.item.Item;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NativeToolWearTest {
    private final Object identity = new Object();
    private final Item unit = mock(Item.class);

    @Test void eventAdjustedWearMayExceedTheToolBaseAndCancellationCanLeaveDamageUnchanged() {
        assertDoesNotThrow(() -> new NativeToolWear(identity, unit, 2, 1, 1, 10, 30, 40, 2000, false));
        assertDoesNotThrow(() -> new NativeToolWear(identity, unit, 2, 1, 1, 10, 0, 10, 2000, false));
    }

    @Test void negativeEventRepairRetainsTheActualClampedNativeDamage() {
        assertDoesNotThrow(() -> new NativeToolWear(identity, unit, 2, 1, 1, 10, -3, 7, 2000, false));
        assertDoesNotThrow(() -> new NativeToolWear(identity, unit, 2, 1, 1, 10, -30, 0, 2000, false));
    }

    @Test void actualBreakConsumesExactlyOneAndRetainsTheBrokenNativeDamageBoundary() {
        assertDoesNotThrow(() -> new NativeToolWear(identity, unit, 2, 1, 0, 1999, 7, 2000, 2000, true));
        assertThrows(IllegalArgumentException.class,
                () -> new NativeToolWear(identity, unit, 2, 1, 0, 1999, 0, 2000, 2000, true));
    }

    @Test void countComponentOrDamageTransitionCannotBeInventedByAReceipt() {
        assertThrows(IllegalArgumentException.class,
                () -> new NativeToolWear(identity, unit, 2, 1, 2, 10, 1, 11, 2000, false));
        assertThrows(IllegalArgumentException.class,
                () -> new NativeToolWear(identity, unit, 2, 1, 1, 10, 1, 12, 2000, false));
        assertThrows(IllegalArgumentException.class,
                () -> new NativeToolWear(identity, unit, 2, 1, 1, 0, 0, 0, 0, false));
    }
}
