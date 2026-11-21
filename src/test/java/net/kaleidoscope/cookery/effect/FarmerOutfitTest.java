package net.kaleidoscope.cookery.effect;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Covers decoded item-ID rules and entity-task lifecycle, not CraftEngine's item-decoding integration. */
class FarmerOutfitTest {
    private static final String[] OUTFIT = {
            "farmer_boots", "farmer_leggings", "farmer_chest_plate", "straw_hat"
    };

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
            assertNotNull(PotionEffectType.DOLPHINS_GRACE);
        }
    }

    @Test void fullFarmerOutfitMatchesUpstreamEffectParameters() {
        try (var inventory = mockStatic(InventoryUtils.class)) {
            Player player = outfitted(inventory, "straw_hat");

            KitchenEffectHandlers.farmerOutfit(player);

            ArgumentCaptor<PotionEffect> effect = ArgumentCaptor.forClass(PotionEffect.class);
            verify(player).addPotionEffect(effect.capture());
            assertSame(PotionEffectType.DOLPHINS_GRACE, effect.getValue().getType());
            assertEquals(25, effect.getValue().getDuration());
            assertEquals(0, effect.getValue().getAmplifier());
            assertTrue(effect.getValue().isAmbient());
            assertTrue(effect.getValue().hasParticles());
            assertFalse(effect.getValue().hasIcon());
        }
    }

    @Test void flowerStrawHatAlsoCompletesTheOutfit() {
        try (var inventory = mockStatic(InventoryUtils.class)) {
            Player player = outfitted(inventory, "straw_hat_flower");
            KitchenEffectHandlers.farmerOutfit(player);
            verify(player).addPotionEffect(any(PotionEffect.class));
        }
    }

    @Test void dryPlayerDoesNotReadOrDecodeEquipment() {
        Player player = mock(Player.class);
        try (var inventory = mockStatic(InventoryUtils.class)) {
            KitchenEffectHandlers.farmerOutfit(player);
            verify(player, never()).getInventory();
            verify(player, never()).addPotionEffect(any(PotionEffect.class));
            inventory.verifyNoInteractions();
        }
    }

    @Test void eachMissingArmorPiecePreventsTheBonus() {
        try (var inventory = mockStatic(InventoryUtils.class)) {
            for (int slot = 0; slot < OUTFIT.length; slot++) {
                Player player = outfitted(inventory, "straw_hat");
                ItemStack[] armor = player.getInventory().getArmorContents();
                armor[slot] = null;
                KitchenEffectHandlers.farmerOutfit(player);
                verify(player, never()).addPotionEffect(any(PotionEffect.class));
            }
        }
    }

    @Test void armorClassifiedAsAirPreventsTheBonus() {
        try (var inventory = mockStatic(InventoryUtils.class)) {
            Player player = outfitted(inventory, "straw_hat");
            ItemStack air = mock(ItemStack.class);
            Material classifiedAsAir = mock(Material.class);
            when(classifiedAsAir.isAir()).thenReturn(true);
            when(air.getType()).thenReturn(classifiedAsAir);
            player.getInventory().getArmorContents()[2] = air;
            KitchenEffectHandlers.farmerOutfit(player);
            verify(player, never()).addPotionEffect(any(PotionEffect.class));
            inventory.verify(() -> InventoryUtils.wrap(air), never());
        }
    }

    @Test void decodedVanillaLeatherIdDoesNotCountAsFarmerArmor() {
        try (var inventory = mockStatic(InventoryUtils.class)) {
            Player player = outfitted(inventory, "straw_hat");
            ItemStack boots = player.getInventory().getArmorContents()[0];
            Item decoded = item("minecraft:leather_boots");
            inventory.when(() -> InventoryUtils.wrap(boots)).thenReturn(decoded);
            KitchenEffectHandlers.farmerOutfit(player);
            verify(player, never()).addPotionEffect(any(PotionEffect.class));
        }
    }

    @Test void decodedMatchingPathInAnotherNamespaceDoesNotCount() {
        try (var inventory = mockStatic(InventoryUtils.class)) {
            Player player = outfitted(inventory, "straw_hat");
            ItemStack boots = player.getInventory().getArmorContents()[0];
            Item decoded = item("other:farmer_boots");
            inventory.when(() -> InventoryUtils.wrap(boots)).thenReturn(decoded);
            KitchenEffectHandlers.farmerOutfit(player);
            verify(player, never()).addPotionEffect(any(PotionEffect.class));
        }
    }

    @Test void farmerItemInTheWrongEquipmentSlotDoesNotCount() {
        try (var inventory = mockStatic(InventoryUtils.class)) {
            Player player = outfitted(inventory, "straw_hat");
            ItemStack[] armor = player.getInventory().getArmorContents();
            ItemStack boots = armor[0];
            armor[0] = armor[1];
            armor[1] = boots;
            KitchenEffectHandlers.farmerOutfit(player);
            verify(player, never()).addPotionEffect(any(PotionEffect.class));
        }
    }

    @Test void rejectedPotionApplicationIsNotForcedOrRetriedImmediately() {
        try (var inventory = mockStatic(InventoryUtils.class)) {
            Player player = outfitted(inventory, "straw_hat");
            when(player.addPotionEffect(any(PotionEffect.class))).thenReturn(false);
            KitchenEffectHandlers.farmerOutfit(player);
            verify(player, times(1)).addPotionEffect(any(PotionEffect.class));
            verify(player, never()).addPotionEffect(any(PotionEffect.class), anyBoolean());
            verify(player, never()).removePotionEffect(any());
        }
    }

    @Test void leavingWaterOrRemovingArmorAllowsNormalExpiryWithoutRemovingOtherEffects() {
        try (var inventory = mockStatic(InventoryUtils.class)) {
            Player player = outfitted(inventory, "straw_hat");
            KitchenEffectHandlers.farmerOutfit(player);
            when(player.isInWater()).thenReturn(false);
            KitchenEffectHandlers.farmerOutfit(player);
            when(player.isInWater()).thenReturn(true);
            player.getInventory().getArmorContents()[3] = null;
            KitchenEffectHandlers.farmerOutfit(player);
            verify(player, times(1)).addPotionEffect(any(PotionEffect.class));
            verify(player, never()).removePotionEffect(any());
        }
    }

    @Test void outfitRefreshRunsEveryTwentyEntitySchedulerTicksWithoutFoodEffects() {
        try (var handlers = mockStatic(KitchenEffectHandlers.class)) {
            handlers.when(KitchenEffectHandlers::create).thenReturn(Map.of());
            SessionFixture fixture = session();
            verify(fixture.player().getScheduler()).runAtFixedRate(any(), any(), any(), eq(1L), eq(1L));
            fixture.advance(19);
            handlers.verify(() -> KitchenEffectHandlers.farmerOutfit(fixture.player()), never());
            fixture.advance(1);
            handlers.verify(() -> KitchenEffectHandlers.farmerOutfit(fixture.player()), times(1));
            fixture.advance(19);
            handlers.verify(() -> KitchenEffectHandlers.farmerOutfit(fixture.player()), times(1));
            fixture.advance(1);
            handlers.verify(() -> KitchenEffectHandlers.farmerOutfit(fixture.player()), times(2));
        }
    }

    @Test void deadPlayersDoNotReceiveOutfitRefreshes() {
        try (var handlers = mockStatic(KitchenEffectHandlers.class)) {
            handlers.when(KitchenEffectHandlers::create).thenReturn(Map.of());
            SessionFixture fixture = session();
            when(fixture.player().isDead()).thenReturn(true);
            fixture.advance(40);
            handlers.verify(() -> KitchenEffectHandlers.farmerOutfit(fixture.player()), never());
        }
    }

    @Test void quitCancelsTheEntityTaskWithoutRemovingPotionEffects() {
        try (var handlers = mockStatic(KitchenEffectHandlers.class)) {
            handlers.when(KitchenEffectHandlers::create).thenReturn(Map.of());
            SessionFixture fixture = session();
            PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
            when(quit.getPlayer()).thenReturn(fixture.player());
            fixture.service().onQuit(quit);
            verify(fixture.task()).cancel();
            verify(fixture.player(), never()).removePotionEffect(any());
        }
    }

    @Test void retiredEntityDropsItsSessionSoRejoinGetsANewTask() {
        try (var handlers = mockStatic(KitchenEffectHandlers.class)) {
            handlers.when(KitchenEffectHandlers::create).thenReturn(Map.of());
            SessionFixture fixture = session();
            fixture.retired().run();
            fixture.service().onJoin(new PlayerJoinEvent(fixture.player(), (net.kyori.adventure.text.Component) null));
            verify(fixture.player().getScheduler(), times(2)).runAtFixedRate(any(), any(), any(), eq(1L), eq(1L));
        }
    }

    @Test void closeStopsRefreshAndKeepsPotionEffectsOnTheirNormalExpiryPath() {
        try (var handlers = mockStatic(KitchenEffectHandlers.class);
             var bukkit = mockStatic(Bukkit.class)) {
            handlers.when(KitchenEffectHandlers::create).thenReturn(Map.of());
            SessionFixture fixture = session();
            bukkit.when(() -> Bukkit.getPlayer(fixture.player().getUniqueId())).thenReturn(fixture.player());
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.player())).thenReturn(true);
            fixture.service().close();
            fixture.advance(40);
            verify(fixture.task()).cancel();
            handlers.verify(() -> KitchenEffectHandlers.farmerOutfit(fixture.player()), never());
            verify(fixture.player(), never()).removePotionEffect(any());
        }
    }

    private static Player outfitted(MockedStatic<InventoryUtils> inventory, String hat) {
        Player player = mock(Player.class);
        PlayerInventory equipment = mock(PlayerInventory.class);
        ItemStack[] armor = new ItemStack[4];
        for (int slot = 0; slot < armor.length; slot++) {
            ItemStack piece = mock(ItemStack.class);
            armor[slot] = piece;
            when(piece.getType()).thenReturn(Material.LEATHER_BOOTS);
            Item decoded = item("kaleidoscopecookery:" + (slot == 3 ? hat : OUTFIT[slot]));
            inventory.when(() -> InventoryUtils.wrap(piece)).thenReturn(decoded);
        }
        when(player.isInWater()).thenReturn(true);
        when(player.getInventory()).thenReturn(equipment);
        when(equipment.getArmorContents()).thenReturn(armor);
        return player;
    }

    private static Item item(String id) {
        Item item = mock(Item.class);
        when(item.id()).thenReturn(Key.of(id));
        return item;
    }

    private static SessionFixture session() {
        Player player = mock(Player.class);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(player.getScheduler()).thenReturn(scheduler);
        AtomicReference<Consumer<ScheduledTask>> callback = new AtomicReference<>();
        AtomicReference<Runnable> retired = new AtomicReference<>();
        when(scheduler.runAtFixedRate(any(), any(), any(), anyLong(), anyLong())).thenAnswer(call -> {
            callback.set(call.getArgument(1));
            retired.set(call.getArgument(2));
            return task;
        });
        CookeryEffects service = new CookeryEffects(mock(JavaPlugin.class));
        service.onJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
        return new SessionFixture(service, player, task, callback.get(), retired.get());
    }

    private record SessionFixture(CookeryEffects service, Player player, ScheduledTask task,
                                  Consumer<ScheduledTask> tick, Runnable retired) {
        void advance(int ticks) {
            for (int i = 0; i < ticks; i++) tick.accept(task);
        }
    }
}
