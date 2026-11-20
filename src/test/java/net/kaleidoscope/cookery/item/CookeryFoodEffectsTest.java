package net.kaleidoscope.cookery.item;

import net.kaleidoscope.cookery.catalog.ConsumeEffectDefinition;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CookeryFoodEffectsTest {
    @BeforeAll static void initializePotionEffects() {
        try (var access = mockStatic(io.papermc.paper.registry.RegistryAccess.class);
             var bridge = mockStatic(io.papermc.paper.InternalAPIBridge.class)) {
            bridge.when(io.papermc.paper.InternalAPIBridge::get)
                    .thenReturn(mock(io.papermc.paper.InternalAPIBridge.class));
            var provider = mock(io.papermc.paper.registry.RegistryAccess.class, call -> mock(org.bukkit.Registry.class));
            access.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(provider);
            var registry = org.bukkit.Registry.MOB_EFFECT;
            doAnswer(call -> mock(PotionEffectType.class)).when(registry)
                    .getOrThrow(any(net.kyori.adventure.key.Key.class));
            assertNotNull(PotionEffectType.SLOWNESS);
        }
    }

    @Test void bakedVanillaEffectsUseTheNativeItemCodecFlags() {
        var type = mock(PotionEffectType.class);
        var source = new ConsumeEffectDefinition("minecraft:slowness", 240, 2, 0.25);
        var actual = CookeryFoodItems.vanillaEffect(type, source);
        assertSame(type, actual.getType());
        assertEquals(240, actual.getDuration());
        assertEquals(2, actual.getAmplifier());
        assertFalse(actual.isAmbient(), "Bukkit's short constructor would make CE and baked items unstackable");
        assertTrue(actual.hasParticles());
        assertTrue(actual.hasIcon());
        assertNull(actual.getHiddenPotionEffect());
    }
}
