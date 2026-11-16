package net.kaleidoscope.cookery.recipe;

import net.kaleidoscope.cookery.catalog.QualityProfile;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiceBowlRulesTest {
    private static final String DISH = "kaleidoscopecookery:braised_beef";
    private static final String RICE = "kaleidoscopecookery:cooked_rice";
    private static final Predicate<Item> IS_DISH = item -> item.id().asString().equals(DISH);
    private static final Predicate<Item> IS_RICE = item -> item.id().asString().equals(RICE);

    private static Item item(String id, int count) {
        Item item = mock(Item.class);
        when(item.id()).thenReturn(Key.of(id));
        when(item.count()).thenReturn(count);
        return item;
    }

    private static boolean matches(List<Item> slots) { return RiceBowlRules.matches(slots, IS_DISH, IS_RICE); }

    @Test void dishAndRiceMatchInEitherOrder() {
        Item dish = item(DISH, 1);
        Item rice = item(RICE, 1);
        assertTrue(matches(List.of(dish, rice)));
        assertTrue(matches(List.of(rice, dish)));
    }

    @Test void unrelatedOccupiedSlotsAreAllowed() {
        List<Item> slots = new ArrayList<>();
        slots.add(item(DISH, 1));
        slots.add(item(RICE, 1));
        for (int slot = 2; slot < 9; slot++) slots.add(item("minecraft:stone", 64));
        assertTrue(matches(slots));
    }

    @Test void repeatedDishInTwoSlotsRejectsRegardlessOfStackCounts() {
        assertFalse(matches(List.of(item(DISH, 1), item(DISH, 64), item(RICE, 1))));
    }

    @Test void repeatedRiceInTwoSlotsRejectsRegardlessOfStackCounts() {
        assertFalse(matches(List.of(item(DISH, 1), item(RICE, 1), item(RICE, 64))));
    }

    @Test void aStackCountsAsOneMatchingSlot() {
        Item dish = item(DISH, 64);
        Item rice = item(RICE, 16);
        assertTrue(matches(List.of(dish, rice)));
        verify(dish, never()).shrink(anyInt());
        verify(rice, never()).shrink(anyInt());
    }

    @Test void missingRequiredSlotRejects() {
        assertFalse(matches(List.of(item(DISH, 1), item("minecraft:stone", 1))));
        assertFalse(matches(List.of(item(RICE, 1), item("minecraft:stone", 1))));
    }

    @Test void nullAndEmptySlotsDoNotCount() {
        Item empty = mock(Item.class);
        when(empty.isEmpty()).thenReturn(true);
        assertTrue(matches(Arrays.asList(null, item(DISH, 1), empty, null, item(RICE, 1), null, null, null, null)));
        verify(empty, never()).id();
    }

    @Test void onlyTwoThroughNineGridSlotsAreSupported() {
        assertFalse(matches(List.of()));
        assertFalse(matches(List.of(item(DISH, 1))));
        List<Item> oversized = new ArrayList<>(Collections.nCopies(10, null));
        oversized.set(0, item(DISH, 1));
        oversized.set(9, item(RICE, 1));
        assertFalse(matches(oversized));
    }

    @Test void matchingPredicatesAreCountedIndependentlyLikeSource() {
        Item shared = item("test:both", 1);
        assertTrue(RiceBowlRules.matches(Arrays.asList(shared, null), ignored -> true, ignored -> true));
    }

    @Test void predicateFailureIsNotTreatedAsAnUnrelatedItem() {
        assertThrows(IllegalArgumentException.class, () -> RiceBowlRules.matches(
                List.of(item(DISH, 1), item(RICE, 1)), ignored -> { throw new IllegalArgumentException("invalid item"); }, IS_RICE));
    }

    @Test void noExplicitQualityReturnsBase() {
        assertEquals(QualityProfile.BASE, RiceBowlRules.bestQuality(List.of()));
        assertEquals(QualityProfile.BASE, RiceBowlRules.bestQuality(List.of(QualityProfile.BASE, QualityProfile.BASE)));
    }

    @Test void baseNeverOutranksExplicitPoorQuality() {
        var poor = QualityProfile.of(DishQuality.POOR);
        assertEquals(poor, RiceBowlRules.bestQuality(List.of(QualityProfile.BASE, poor)));
        assertEquals(poor, RiceBowlRules.bestQuality(List.of(poor, QualityProfile.BASE)));
    }

    @Test void extraMatrixItemCanSupplyTheHighestQuality() {
        assertEquals(QualityProfile.of(DishQuality.SUPERB), RiceBowlRules.bestQuality(List.of(
                QualityProfile.of(DishQuality.POOR), QualityProfile.BASE, QualityProfile.of(DishQuality.SUPERB))));
    }

    @Test void qualityOrderingIsStableForEverySourcePair() {
        for (DishQuality first : DishQuality.values()) for (DishQuality second : DishQuality.values()) {
            DishQuality expected = first.ordinal() < second.ordinal() ? first : second;
            assertEquals(QualityProfile.of(expected), RiceBowlRules.bestQuality(List.of(
                    QualityProfile.of(first), QualityProfile.of(second))));
        }
    }

    @Test void unknownQualityIsRejectedEvenAfterSuperb() {
        assertThrows(IllegalArgumentException.class, () -> RiceBowlRules.bestQuality(List.of(
                QualityProfile.of(DishQuality.SUPERB), new QualityProfile("UNKNOWN", 1.5))));
    }

    @Test void forgedKnownQualityRatioIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RiceBowlRules.bestQuality(List.of(new QualityProfile("POOR", 1.9))));
        assertThrows(IllegalArgumentException.class,
                () -> RiceBowlRules.bestQuality(List.of(new QualityProfile("BASE", .3))));
    }

    @Test void nullQualityRequiresTheCallerToValidateItsSnapshot() {
        assertThrows(IllegalArgumentException.class,
                () -> RiceBowlRules.bestQuality(Arrays.asList(QualityProfile.BASE, null)));
    }
}
