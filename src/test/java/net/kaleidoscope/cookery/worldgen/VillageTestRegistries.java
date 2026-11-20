package net.kaleidoscope.cookery.worldgen;

import io.papermc.paper.InternalAPIBridge;
import io.papermc.paper.registry.RegistryAccess;
import org.bukkit.Material;
import org.bukkit.Registry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/** Initializes Paper's registry-backed material queries before these no-server fixtures use them. */
final class VillageTestRegistries {
    private VillageTestRegistries() {}

    static void initializeMaterials() {
        try (var access = mockStatic(RegistryAccess.class);
             var bridge = mockStatic(InternalAPIBridge.class)) {
            bridge.when(InternalAPIBridge::get).thenReturn(mock(InternalAPIBridge.class));
            var provider = mock(RegistryAccess.class, call -> mock(Registry.class));
            access.when(RegistryAccess::registryAccess).thenReturn(provider);
            // Another no-server test may have initialized these fields already.
            // PAPER has no block type; an empty block registry models that lookup.
            assertNotNull(Registry.BLOCK);
            assertFalse(Material.PAPER.isAir());
        }
    }
}
