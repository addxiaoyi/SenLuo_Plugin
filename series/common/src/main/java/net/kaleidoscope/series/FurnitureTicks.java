package net.kaleidoscope.series;

import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.tick.FurnitureTicker;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import java.util.function.Consumer;

/** Resolve the loaded controller on each tick, including CE wrappers retained across Folia reloads. */
public final class FurnitureTicks {
    private FurnitureTicks() {}

    public static <T extends FurnitureController, C extends InteractiveFurniture> FurnitureTicker<T> current(
            Class<C> type, Consumer<C> action) {
        return (furniture, ignored) -> {
            if (furniture.metaDataEntity == null
                    || !(furniture.metaDataEntity.platformEntity() instanceof Entity entity)
                    || !Bukkit.isOwnedByCurrentRegion(entity)) return;
            var loaded = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (loaded == null || !loaded.isValid() || loaded.controller == null) return;
            C controller = loaded.controller.get(type, 0);
            if (controller != null && controller.operational()) action.accept(controller);
        };
    }
}
