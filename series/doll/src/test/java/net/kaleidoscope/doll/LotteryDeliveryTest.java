package net.kaleidoscope.doll;

import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.world.WorldPosition;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LotteryDeliveryTest {
    @BeforeAll static void initializeSounds() {
        try (var access = mockStatic(io.papermc.paper.registry.RegistryAccess.class);
             var bridge = mockStatic(io.papermc.paper.InternalAPIBridge.class)) {
            bridge.when(io.papermc.paper.InternalAPIBridge::get).thenReturn(mock(io.papermc.paper.InternalAPIBridge.class));
            var provider = mock(io.papermc.paper.registry.RegistryAccess.class, call -> mock(org.bukkit.Registry.class));
            access.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(provider);
            var registry = org.bukkit.Registry.SOUNDS;
            doAnswer(call -> mock(Sound.class)).when(registry).getOrThrow(any(net.kyori.adventure.key.Key.class));
            assertNotNull(Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
        }
    }

    @Test void nestedTickCannotDeliverTheSamePaidPrizeTwice() throws Exception {
        var fixture = new Fixture();
        when(fixture.world.dropItemNaturally(any(Location.class), any(ItemStack.class))).thenAnswer(call -> {
            fixture.controller.<LotteryController>createFurnitureTicker().tick(fixture.furniture, fixture.controller);
            return fixture.drop;
        });
        fixture.tick();
        verify(fixture.world, times(1)).dropItemNaturally(any(Location.class), any(ItemStack.class));
        assertFalse(fixture.controller.hasContents());
    }

    @Test void canceledDropKeepsThePaidPrize() throws Exception {
        var fixture = new Fixture();
        when(fixture.drop.isValid()).thenReturn(false);
        fixture.tick();
        assertTrue(fixture.controller.hasContents());
        verify(fixture.furniture).setUnsaved();
    }

    @Test void modifiedDropIsRemovedAndThePaidPrizeRetained() throws Exception {
        var fixture = new Fixture();
        when(fixture.drop.getItemStack()).thenReturn(mock(ItemStack.class));
        fixture.tick();
        assertTrue(fixture.controller.hasContents());
        verify(fixture.drop).remove();
    }

    @Test void destroyingTheMachineFromTheSpawnHookDoesNotAlsoReleaseItsPrize() throws Exception {
        var fixture = new Fixture();
        when(fixture.world.dropItemNaturally(any(Location.class), any(ItemStack.class))).thenAnswer(call -> {
            when(fixture.furniture.isValid()).thenReturn(false);
            return fixture.drop;
        });
        fixture.tick();
        assertTrue(fixture.controller.hasContents());
        verify(fixture.drop).remove();
    }

    private static final class Fixture {
        final Furniture furniture = mock(Furniture.class);
        final World world = mock(World.class);
        final DollPlugin plugin = mock(DollPlugin.class);
        final GiftBoxes gifts = mock(GiftBoxes.class);
        final ItemStack gift = mock(ItemStack.class);
        final Item drop = mock(Item.class);
        final LotteryController controller;

        Fixture() throws Exception {
            var ceWorld = mock(net.momirealms.craftengine.core.world.World.class);
            when(ceWorld.platformWorld()).thenReturn(world);
            when(furniture.position()).thenReturn(new WorldPosition(ceWorld, 0, 64, 0));
            when(furniture.isValid()).thenReturn(true);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.catalog()).thenReturn(new DollCatalog(List.of("doll:one"), List.of(),
                    List.of(List.of("doll:one"), List.of("doll:one"), List.of("doll:one")),
                    List.of("minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:diamond"), Map.of()));
            when(plugin.gifts()).thenReturn(gifts);
            when(gifts.create(0, "doll:one")).thenReturn(gift);
            when(gift.clone()).thenReturn(gift);
            when(drop.getItemStack()).thenReturn(gift);
            when(drop.isValid()).thenReturn(true);
            when(world.dropItemNaturally(any(Location.class), any(ItemStack.class))).thenReturn(drop);
            controller = new LotteryController(furniture, plugin);
            Field pending = LotteryController.class.getDeclaredField("pending");
            pending.setAccessible(true);
            pending.set(controller, "doll:one");
        }

        void tick() { controller.<LotteryController>createFurnitureTicker().tick(furniture, controller); }
    }
}
