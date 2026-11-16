package net.kaleidoscope.cookery.catalog;

import java.util.List;
import java.util.Objects;

/** Source food properties for a directly edible block, separate from whole-item consumption. */
public record PlacedMealDefinition(String itemId, int maxBites, PlacedMealLayout layout,
                                   FoodDefinition baseFood, List<String> drops,
                                   String collision, String animation) {
    public PlacedMealDefinition {
        CatalogJson.requireId(itemId);
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(baseFood, "baseFood");
        if (maxBites < 1 || maxBites > 64 || drops == null || drops.size() > 64
                || collision == null || collision.isBlank()
                || animation != null && !animation.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid placed meal definition: " + itemId);
        }
        if (baseFood.nutrition() == 0 && baseFood.saturation() != 0) {
            throw new IllegalArgumentException("Zero-nutrition meal cannot recover a saturation modifier: " + itemId);
        }
        drops = List.copyOf(drops);
        drops.forEach(CatalogJson::requireId);
    }

    public static PlacedMealDefinition from(CookeryCatalog.Entry entry) {
        if (entry == null || entry.bites() == null) {
            throw new IllegalArgumentException("Item has no placed meal definition");
        }
        CookeryCatalog.Bites bites = entry.bites();
        return new PlacedMealDefinition(entry.id(), bites.maxBites(), PlacedMealLayout.of(bites.layout()),
                bites.food(), bites.drops(), bites.collision(), bites.animation());
    }

    public static PlacedMealDefinition from(CookeryCatalog.Snapshot snapshot, String itemId) {
        if (snapshot == null || !CookeryCatalog.SOURCE_COMMIT.equals(snapshot.sourceCommit())) {
            throw new IllegalArgumentException("Placed meal catalog source differs");
        }
        CatalogJson.requireId(itemId);
        return from(snapshot.items().get(itemId));
    }

    public BiteFood bite(QualityProfile quality) {
        Objects.requireNonNull(quality, "quality");
        double ratio = quality.ratio();
        int nutrition = Math.toIntExact(Math.round(baseFood.nutrition() * ratio));
        // FoodData.eat multiplies rounded nutrition by the quality-scaled source modifier.
        double modifier = baseFood.nutrition() == 0 ? 0 : baseFood.saturation() / (2d * baseFood.nutrition());
        float saturation = (float) (2d * nutrition * modifier * ratio);
        List<Effect> effects = baseFood.effects().stream().map(effect -> new Effect(effect.id(),
                Math.toIntExact(Math.round(effect.duration() * ratio)), effect.amplifier(), effect.probability())).toList();
        return new BiteFood(nutrition, saturation, baseFood.alwaysEat(), effects);
    }

    public record BiteFood(int nutrition, float saturation, boolean alwaysEat, List<Effect> effects) {
        public BiteFood {
            if (nutrition < 0 || nutrition > 4_000 || !Float.isFinite(saturation)
                    || saturation < 0 || saturation > 3_200_000 || effects == null || effects.size() > 32) {
                throw new IllegalArgumentException("Invalid placed meal bite");
            }
            effects = List.copyOf(effects);
        }
    }

    public record Effect(String id, int duration, int amplifier, double probability) {
        public Effect {
            CatalogJson.requireId(id);
            if (duration < 0 || duration > 144_000_000 || amplifier < 0 || amplifier > 255
                    || !Double.isFinite(probability) || probability < 0 || probability > 1) {
                throw new IllegalArgumentException("Invalid placed meal effect: " + id);
            }
        }
    }
}
