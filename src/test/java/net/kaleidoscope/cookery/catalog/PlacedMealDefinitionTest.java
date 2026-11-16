package net.kaleidoscope.cookery.catalog;

import net.kaleidoscope.cookery.recipe.DishQuality;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlacedMealDefinitionTest {
    private final CookeryCatalog.Snapshot catalog = CookeryCatalog.bundled().snapshot();

    @Test void allThirtyEightSourceMealsKeepBiteFoodAndPlacementMetadata() {
        int meals = 0;
        for (CookeryCatalog.Entry entry : catalog.items().values()) {
            if (entry.bites() == null) continue;
            var meal = PlacedMealDefinition.from(catalog, entry.id());
            assertEquals(entry.id(), meal.itemId());
            assertEquals(entry.bites().maxBites(), meal.maxBites());
            assertEquals(entry.bites().food(), meal.baseFood());
            assertEquals(entry.bites().layout(), meal.layout().id());
            assertEquals(entry.bites().drops(), meal.drops());
            assertEquals(entry.bites().collision(), meal.collision());
            assertEquals(entry.bites().animation(), meal.animation());
            var bite = meal.bite(QualityProfile.BASE);
            assertEquals(entry.bites().food().nutrition(), bite.nutrition());
            assertEquals(entry.bites().food().saturation(), bite.saturation(), 0.000001f);
            meals++;
        }
        assertEquals(38, meals);
    }

    @Test void poorDarkCuisineRoundsToZeroNutritionWithoutChangingWholeItemRules() {
        var meal = PlacedMealDefinition.from(catalog, "kaleidoscopecookery:dark_cuisine");
        var quality = QualityProfile.of(DishQuality.POOR);
        var bite = meal.bite(quality);
        assertEquals(0, bite.nutrition());
        assertEquals(0, bite.saturation());
        assertEquals(1, meal.baseFood().scaled(quality).nutrition());
    }

    @Test void saturationUsesRoundedNutritionAndScaledSourceModifier() {
        var meal = PlacedMealDefinition.from(catalog, "kaleidoscopecookery:dough_drop_soup");
        var bite = meal.bite(QualityProfile.of(DishQuality.POOR));
        assertEquals(2, meal.baseFood().nutrition());
        assertEquals(4, meal.baseFood().saturation());
        assertEquals(1, bite.nutrition());
        assertEquals(0.6f, bite.saturation(), 0.000001f);
        assertNotEquals(meal.baseFood().scaled(QualityProfile.of(DishQuality.POOR)).saturation(), bite.saturation());
        var pork = PlacedMealDefinition.from(catalog, "kaleidoscopecookery:dongpo_pork")
                .bite(QualityProfile.of(DishQuality.POOR));
        assertEquals(2, pork.nutrition());
        assertEquals(0.6252f, pork.saturation(), 0.000001f);
    }

    @Test void qualityPreservesEffectProbabilityAndLevelWhileRoundingDuration() {
        var definition = definition(new FoodDefinition(3, 6, true, false, 32,
                List.of(new ConsumeEffectDefinition("minecraft:speed", 5, 2, 0.25))));
        var bite = definition.bite(new QualityProfile("POOR", 0.3));
        assertTrue(bite.alwaysEat());
        assertEquals(1, bite.nutrition());
        assertEquals(0.6f, bite.saturation(), 0.000001f);
        assertEquals(new PlacedMealDefinition.Effect("minecraft:speed", 2, 2, 0.25), bite.effects().getFirst());
        assertEquals(5, definition.baseFood().effects().getFirst().duration());
    }

    @Test void zeroNutritionAndZeroTickEffectsStayRepresentable() {
        var definition = definition(new FoodDefinition(0, 0, true, false, 32,
                List.of(new ConsumeEffectDefinition("minecraft:instant_health", 1, 0, 1))));
        var bite = definition.bite(new QualityProfile("POOR", 0.3));
        assertEquals(0, bite.nutrition());
        assertEquals(0, bite.saturation());
        assertEquals(0, bite.effects().getFirst().duration());
        assertEquals("minecraft:instant_health", bite.effects().getFirst().id());
    }

    @Test void sourceDropsRemainImmutableAndPreserveDuplicates() {
        var meal = PlacedMealDefinition.from(catalog, "kaleidoscopecookery:cold_style_sashimi");
        assertEquals(List.of("minecraft:bowl", "minecraft:snowball", "minecraft:snowball"), meal.drops());
        assertThrows(UnsupportedOperationException.class, () -> meal.drops().add("minecraft:apple"));
        List<PlacedMealDefinition.Effect> effects = new ArrayList<>();
        var bite = new PlacedMealDefinition.BiteFood(1, 1, false, effects);
        effects.add(new PlacedMealDefinition.Effect("minecraft:speed", 1, 0, 1));
        assertTrue(bite.effects().isEmpty());
    }

    @Test void missingMealsForeignSnapshotsAndInvalidNumbersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PlacedMealDefinition.from(catalog, "minecraft:apple"));
        assertThrows(IllegalArgumentException.class, () -> PlacedMealDefinition.from(catalog, "kaleidoscopecookery:baozi"));
        var foreign = new CookeryCatalog.Snapshot("foreign", catalog.items(), catalog.customEffects());
        assertThrows(IllegalArgumentException.class, () -> PlacedMealDefinition.from(foreign, "kaleidoscopecookery:dark_cuisine"));
        assertThrows(IllegalArgumentException.class, () -> definition(new FoodDefinition(0, 1, false, false, 32, List.of())));
        assertThrows(IllegalArgumentException.class, () -> new PlacedMealDefinition.BiteFood(1, Float.NaN, false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PlacedMealDefinition.Effect("minecraft:speed", -1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PlacedMealDefinition.Effect("minecraft:speed", 1, 0, Double.NaN));
    }

    private static PlacedMealDefinition definition(FoodDefinition food) {
        return new PlacedMealDefinition("kaleidoscopecookery:example", 3, PlacedMealLayout.SINGLE,
                food, List.of("minecraft:bowl"), "[[1,0,1,15,2,15]]", null);
    }
}
