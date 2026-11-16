package net.kaleidoscope.cookery.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlacedMealStateTest {
    private final CookeryCatalog.Snapshot catalog = CookeryCatalog.bundled().snapshot();
    private final PlacedMealDefinition meal = PlacedMealDefinition.from(catalog, "kaleidoscopecookery:blaze_lamb_chop");

    @Test void freshPartialAndEmptyStateRoundTripWithoutRefillingTheMeal() {
        var fresh = PlacedMealState.fresh(meal, new QualityProfile("STANDARD", 0.6));
        assertTrue(fresh.untouched());
        assertEquals(3, fresh.remaining());
        var partial = fresh.consumeOne();
        assertEquals(2, partial.remaining());
        assertEquals(3, fresh.remaining());
        assertEquals(partial, PlacedMealState.decode(partial.encode(), meal));
        var empty = partial.consumeOne().consumeOne();
        assertTrue(empty.empty());
        assertSame(empty, empty.consumeOne());
        assertEquals(empty, PlacedMealState.decode(empty.encode(), meal));
        assertFalse(empty.untouched());
        assertEquals(0.6, empty.quality().ratio());
    }

    @Test void lootRecoversOnlyUntouchedFoodAndDropsOnlyContainersAfterAnyBite() {
        for (var entry : catalog.items().values()) {
            if (entry.bites() == null) continue;
            var definition = PlacedMealDefinition.from(entry);
            var fresh = PlacedMealState.fresh(definition, QualityProfile.BASE);
            assertEquals(List.of(entry.id()), fresh.dropIds(definition));
            var partial = fresh.consumeOne();
            assertEquals(entry.bites().drops(), partial.dropIds(definition));
            while (!partial.empty()) partial = partial.consumeOne();
            assertEquals(entry.bites().drops(), partial.dropIds(definition));
        }
    }

    @Test void anotherMealOrCapacityCannotReuseTheSavedCounter() {
        var state = PlacedMealState.fresh(meal, QualityProfile.BASE);
        var other = PlacedMealDefinition.from(catalog, "kaleidoscopecookery:crystal_lamb_chop");
        assertThrows(IllegalArgumentException.class, () -> state.dropIds(other));
        assertThrows(IllegalArgumentException.class, () -> PlacedMealState.decode(state.encode(), other));
        assertThrows(IllegalArgumentException.class, () -> PlacedMealState.decode(
                state.encode().replace("\"maxBites\":3", "\"maxBites\":4"), meal));
    }

    @Test void duplicateUnknownMissingOrWrongTypedFieldsAreRejected() {
        String saved = PlacedMealState.fresh(meal, QualityProfile.BASE).encode();
        for (String broken : List.of(
                saved.replace("\"version\":1", "\"version\":1,\"version\":1"),
                saved.replace("\"version\":1", "\"version\":\"1\""),
                saved.replace("\"version\":1", "\"version\":1.00000000000000000001"),
                saved.replace("\"version\":1", "\"version\":2"),
                saved.replace("\"consumed\":0", "\"consumed\":-1"),
                saved.replace("\"consumed\":0", "\"consumed\":4"),
                saved.replace("\"consumed\":0", "\"consumed\":2147483648"),
                saved.replace("\"consumed\":0,", ""),
                saved.replace("\"maxBites\":3", "\"maxBites\":65"),
                saved.replace("\"ratio\":1.0", "\"ratio\":\"1.0\""),
                saved.replace("\"ratio\":1.0", "\"ratio\":0"),
                saved.replace("\"ratio\":1.0", "\"ratio\":1e9999"),
                saved.replace("\"ratio\":1.0", "\"ratio\":1.0,\"other\":true"),
                saved.replace("\"quality\":{\"name\":\"BASE\",\"ratio\":1.0}", "\"quality\":null"),
                saved.replace("\"version\":1", "\"version\":1,\"other\":true"),
                saved.replace(CookeryCatalog.SOURCE_COMMIT, "foreign"),
                saved.replace(meal.itemId(), "../invalid"),
                saved + "{}")) {
            assertThrows(IllegalArgumentException.class, () -> PlacedMealState.decode(broken), broken);
        }
    }

    @Test void oversizedMalformedAndEmptyPayloadsFailClosed() {
        for (String broken : List.of("", "{}", "[]", "null", "{broken", " ".repeat(4097),
                "{\"unexpected\":\"" + "食".repeat(1400) + "\"}")) {
            assertThrows(IllegalArgumentException.class, () -> PlacedMealState.decode(broken));
        }
        assertThrows(IllegalArgumentException.class, () -> PlacedMealState.decode(null));
    }
}
