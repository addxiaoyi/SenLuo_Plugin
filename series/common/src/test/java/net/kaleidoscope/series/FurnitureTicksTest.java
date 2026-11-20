package net.kaleidoscope.series;

import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class FurnitureTicksTest {
    static class TickController extends InteractiveFurniture {
        TickController(Furniture furniture) { super(furniture, null); }
        @Override protected void interact(Player player) {}
        void advance() {}
    }

    private static void field(Furniture furniture, String name, Object value) throws Exception {
        var field = Furniture.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(furniture, value);
    }

    @Test void retainedTickerAdvancesOnlyTheCurrentControllerAfterReload() throws Exception {
        Furniture old = mock(Furniture.class);
        var entity = mock(Entity.class);
        var meta = mock(net.momirealms.craftengine.core.entity.Entity.class);
        when(meta.platformEntity()).thenReturn(entity);
        field(old, "metaDataEntity", meta);
        var loaded = mock(BukkitFurniture.class);
        var previous = mock(TickController.class);
        var current = mock(TickController.class);
        field(loaded, "controller", current);
        when(loaded.isValid()).thenReturn(true);
        when(current.get(TickController.class, 0)).thenReturn(current);
        when(current.operational()).thenReturn(true);
        var ticker = FurnitureTicks.<FurnitureController, TickController>current(TickController.class, TickController::advance);
        try (var bukkit = mockStatic(Bukkit.class); var api = mockStatic(CraftEngineFurniture.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(entity)).thenReturn(true);
            api.when(() -> CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity)).thenReturn(loaded);
            ticker.tick(old, previous);
            verify(current).advance();
            verifyNoInteractions(previous);
            // A retired wrapper must never resume after its live entity unloads.
            api.when(() -> CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity)).thenReturn(null);
            ticker.tick(old, previous);
            verify(current, times(1)).advance();
            verifyNoInteractions(previous);
        }
    }

    @Test void invalidMissingAndStoppedControllersDoNotAdvance() throws Exception {
        Furniture furniture = mock(Furniture.class);
        var entity = mock(Entity.class);
        var meta = mock(net.momirealms.craftengine.core.entity.Entity.class);
        when(meta.platformEntity()).thenReturn(entity);
        field(furniture, "metaDataEntity", meta);
        var loaded = mock(BukkitFurniture.class);
        var controller = mock(TickController.class);
        field(loaded, "controller", controller);
        var ticker = FurnitureTicks.<FurnitureController, TickController>current(TickController.class, TickController::advance);
        try (var bukkit = mockStatic(Bukkit.class); var api = mockStatic(CraftEngineFurniture.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(entity)).thenReturn(true);
            api.when(() -> CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity)).thenReturn(loaded);
            ticker.tick(furniture, controller); // invalid furniture
            verifyNoInteractions(controller);
            when(loaded.isValid()).thenReturn(true);
            ticker.tick(furniture, controller); // controller type absent after reload
            when(controller.get(TickController.class, 0)).thenReturn(controller);
            ticker.tick(furniture, controller); // disabled plugin or rejected save
            verify(controller, never()).advance();
        }
    }

    @Test void foreignRegionOrAbsentEntityNeverLooksUpFurniture() throws Exception {
        Furniture furniture = mock(Furniture.class);
        var entity = mock(Entity.class);
        var meta = mock(net.momirealms.craftengine.core.entity.Entity.class);
        when(meta.platformEntity()).thenReturn(entity);
        var controller = mock(TickController.class);
        var ticker = FurnitureTicks.<FurnitureController, TickController>current(TickController.class, TickController::advance);
        try (var bukkit = mockStatic(Bukkit.class); var api = mockStatic(CraftEngineFurniture.class)) {
            ticker.tick(furniture, controller);
            field(furniture, "metaDataEntity", meta);
            ticker.tick(furniture, controller);
            api.verifyNoInteractions();
            verifyNoInteractions(controller);
        }
    }
}
