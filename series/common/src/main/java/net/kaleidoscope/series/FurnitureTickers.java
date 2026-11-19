package net.kaleidoscope.series;

import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.tick.FurnitureTicker;
import org.bukkit.entity.Entity;

import java.util.Objects;
import java.util.function.Consumer;

/** Resolves the live controller when CraftEngine retains a ticker for an earlier furniture wrapper. */
public final class FurnitureTickers {
    private FurnitureTickers() {}

    public static <T extends FurnitureController, C extends FurnitureController> FurnitureTicker<T> current(
            Class<C> type, Consumer<C> action) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(action, "action");
        return (previous, previousController) -> {
            if (previous == null || previous.metaDataEntity() == null) return;
            if (!(previous.metaDataEntity().platformEntity() instanceof Entity entity)) return;
            var expected = previous.uuid();
            if (expected == null) return;
            var current = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (current == null || !current.isValid() || !expected.equals(current.uuid()) || current.controller == null) return;
            int ordinal = ordinal(previous, type, previousController);
            if (ordinal < 0) return;
            C controller = controllerAt(current, type, ordinal);
            if (controller != null) action.accept(controller);
        };
    }

    private static <C extends FurnitureController> int ordinal(Furniture furniture, Class<C> type,
                                                              FurnitureController expected) {
        var root = furniture.controller;
        if (root == null || !type.isInstance(expected)) return -1;
        if (type.isInstance(root)) return root == expected ? 0 : -1;
        int ordinal = 0;
        // CE's get(type, index) uses an absolute behavior slot, not an occurrence of the requested type.
        for (int slot = 0; slot < furniture.config().behaviors().size(); slot++) {
            C candidate = root.get(type, slot);
            if (candidate == expected) return ordinal;
            if (candidate != null) ordinal++;
        }
        return -1;
    }

    private static <C extends FurnitureController> C controllerAt(Furniture furniture, Class<C> type, int ordinal) {
        var root = furniture.controller;
        if (type.isInstance(root)) return ordinal == 0 ? type.cast(root) : null;
        for (int slot = 0; slot < furniture.config().behaviors().size(); slot++) {
            C candidate = root.get(type, slot);
            if (candidate != null && ordinal-- == 0) return candidate;
        }
        return null;
    }
}
