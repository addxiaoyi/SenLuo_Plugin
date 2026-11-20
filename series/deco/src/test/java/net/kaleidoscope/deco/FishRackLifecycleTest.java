package net.kaleidoscope.deco;

import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureVariant;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FishRackLifecycleTest {
    @Test void preSpawnLoadDefersAndPlacementInitializesWhenValid() throws Exception {
        var furniture = mock(Furniture.class, RETURNS_DEEP_STUBS);
        var plugin = mock(DecoPlugin.class);
        var controller = new FishRackController(furniture, plugin);
        var entity = mock(Entity.class, RETURNS_DEEP_STUBS);
        var metadata = mock(net.momirealms.craftengine.core.entity.Entity.class);
        when(metadata.platformEntity()).thenReturn(entity);
        field(furniture, "metaDataEntity", metadata);
        doReturn(variant("ground")).when(furniture).currentVariant();

        assertDoesNotThrow(controller::onLoad);
        verify(furniture, never()).setVariant(anyString());
        verify(entity.getScheduler()).run(eq(plugin), any(), isNull());
        when(furniture.isValid()).thenReturn(true);
        when(furniture.setVariant("fishrack/empty")).thenReturn(true);
        controller.onPlace(null);
        verify(furniture).setVariant("fishrack/empty");
    }

    @Test void deferredTaskCannotChangeAReplacementController() throws Exception {
        var furniture = mock(Furniture.class, RETURNS_DEEP_STUBS);
        var plugin = mock(DecoPlugin.class);
        var controller = new FishRackController(furniture, plugin);
        var entity = mock(Entity.class, RETURNS_DEEP_STUBS);
        var metadata = mock(net.momirealms.craftengine.core.entity.Entity.class);
        when(metadata.platformEntity()).thenReturn(entity);
        field(furniture, "metaDataEntity", metadata);
        var callback = new java.util.concurrent.atomic.AtomicReference<Consumer<ScheduledTask>>();
        when(entity.getScheduler().run(eq(plugin), any(), isNull())).thenAnswer(call -> {
            callback.set(call.getArgument(1)); return mock(ScheduledTask.class);
        });
        controller.onLoad();
        var loaded = mock(BukkitFurniture.class);
        var replacement = new FishRackController(loaded, plugin);
        field(loaded, "controller", replacement);
        when(loaded.isValid()).thenReturn(true);
        when(plugin.isEnabled()).thenReturn(true);
        try (var api = mockStatic(CraftEngineFurniture.class)) {
            api.when(() -> CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity)).thenReturn(loaded);
            callback.get().accept(mock(ScheduledTask.class));
            verify(furniture, never()).setVariant(anyString());
            verify(loaded, never()).setVariant(anyString());
        }
    }

    @Test void alreadyRestoredVariantDoesNotNeedAnotherSwitch() {
        var furniture = mock(Furniture.class, RETURNS_DEEP_STUBS);
        when(furniture.isValid()).thenReturn(true);
        doReturn(variant("fishrack/empty")).when(furniture).currentVariant();
        var controller = new FishRackController(furniture, mock(DecoPlugin.class));
        controller.onLoad();
        verify(furniture, never()).setVariant(anyString());
    }

    private static void field(Furniture furniture, String name, Object value) throws Exception {
        var field = Furniture.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(furniture, value);
    }

    private static FurnitureVariant variant(String name) {
        return new FurnitureVariant(name, null, java.util.List.of(), java.util.List.of(), () -> null, new org.joml.Vector3f());
    }
}
