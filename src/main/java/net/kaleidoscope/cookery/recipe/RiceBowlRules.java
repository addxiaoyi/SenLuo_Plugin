package net.kaleidoscope.cookery.recipe;

import net.kaleidoscope.cookery.catalog.QualityProfile;
import net.momirealms.craftengine.core.item.Item;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Source custom recipe matching separated from CE's vanilla shapeless companion. */
public final class RiceBowlRules {
    private static final int MIN_GRID_SLOTS = 2;
    private static final int MAX_GRID_SLOTS = 9;

    private RiceBowlRules() {}

    public static boolean matches(List<Item> slots, Predicate<Item> dish, Predicate<Item> rice) {
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(dish, "dish");
        Objects.requireNonNull(rice, "rice");
        if (slots.size() < MIN_GRID_SLOTS || slots.size() > MAX_GRID_SLOTS) return false;
        int dishes = 0;
        int rices = 0;
        for (Item item : slots) {
            if (item == null || item.isEmpty()) continue;
            if (dish.test(item)) dishes++;
            if (rice.test(item)) rices++;
        }
        return dishes == 1 && rices == 1;
    }

    public static QualityProfile bestQuality(List<QualityProfile> values) {
        Objects.requireNonNull(values, "qualities");
        DishQuality best = null;
        for (QualityProfile value : values) {
            if (value == null) throw new IllegalArgumentException("Rice bowl quality is missing");
            if (value.equals(QualityProfile.BASE)) continue;
            DishQuality current;
            try {
                current = DishQuality.valueOf(value.name());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Unknown rice bowl quality: " + value.name(), invalid);
            }
            if (!value.equals(QualityProfile.of(current))) {
                throw new IllegalArgumentException("Rice bowl quality ratio differs from " + current.name());
            }
            if (best == null || current.ordinal() < best.ordinal()) best = current;
        }
        return best == null ? QualityProfile.BASE : QualityProfile.of(best);
    }
}
