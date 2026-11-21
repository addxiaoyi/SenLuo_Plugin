package net.kaleidoscope.tavern;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import net.kaleidoscope.series.InventoryTradeEvent;
import net.kaleidoscope.series.Items;
import net.kaleidoscope.series.SavedItems;
import net.kaleidoscope.series.SavedState;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Collider;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData;
import net.momirealms.craftengine.core.entity.furniture.FurnitureSnapshotState;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.lang.ref.WeakReference;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MolotovControllerTest {
    private static final String SAVE = "kaleidoscope_tavern:molotov_storage_v1";
    @TempDir static Path temporaryFiles;
    @BeforeAll static void initialize() { MolotovTestItems.initialize(); }

    @Test void newGroundBottlesAndAllFourEmptyStorageTypesNeedNoInventedSaveData() {
        for (String id : List.of("molotov", "holder", "tilted_rack", "circular_rack", "cellar_cabinet")) {
            try (var f = new Fixture(id)) {
                f.controller.loadCustomData(new CompoundTag()); assertFalse(f.controller.blocked()); assertFalse(f.controller.hasContents());
            }
        }
    }
    @Test void aWronglyTypedSaveIsFrozenAndPreservedVerbatim() {
        try (var f = new Fixture("holder")) {
            CompoundTag root = new CompoundTag(); root.putString(SAVE, "opaque damaged state"); f.controller.loadCustomData(root);
            assertTrue(f.controller.blocked()); CompoundTag output = new CompoundTag(); f.controller.saveCustomData(output);
            assertEquals("opaque damaged state", output.getString(SAVE));
            f.controller.interact(f.player, 0); assertFalse(f.controller.hasContents()); assertEquals(2, f.hand().getAmount());
        }
    }
    @Test void foreignKindsSlotCountsAndUnknownSlotKeysCannotBeSilentlyDropped() {
        for (String bad : List.of("kind", "slots", "unknown")) try (var f = new Fixture("holder")) {
            CompoundTag root = f.saved(); CompoundTag payload = root.getCompound(SAVE).getCompound("payload");
            if (bad.equals("kind")) payload.putString("storage", "CELLAR");
            else if (bad.equals("slots")) payload.putInt("slots", 9);
            else payload.putByteArray("slot_99", MolotovTestItems.bottle(1).serializeAsBytes());
            f.controller.loadCustomData(root); assertTrue(f.controller.blocked(), bad);
            CompoundTag retained = new CompoundTag(); f.controller.saveCustomData(retained); assertEquals(root.get(SAVE), retained.get(SAVE));
        }
    }
    @Test void insertionConsumesExactlyOneFromTheSelectedStackAndKeepsItsComponents() {
        try (var f = new Fixture("holder")) {
            f.slots[0] = f.original.asQuantity(7); f.selected = 1; f.slots[1] = f.original.clone();
            f.controller.interact(f.player, 0);
            assertEquals(7, f.slots[0].getAmount()); assertEquals(1, f.hand().getAmount()); assertTrue(f.controller.hasContents());
            f.slots[1] = null; f.controller.interact(f.player, 0);
            assertEquals(f.original.asQuantity(1), f.hand()); assertFalse(f.controller.hasContents());
        }
    }
    @Test void storageInsertionStillConsumesInCreativeAsTheSourceStorageDoes() {
        try (var f = new Fixture("holder")) { when(f.player.getGameMode()).thenReturn(GameMode.CREATIVE); f.controller.interact(f.player, 0); assertEquals(1, f.hand().getAmount()); assertTrue(f.controller.hasContents()); }
    }
    @Test void aCancelledInventoryEventLeavesBothTheHeldStackAndTheFurnitureUnchanged() {
        try (var f = new Fixture("holder")) {
            f.onEvent = event -> { if (event instanceof InventoryTradeEvent trade) trade.setCancelled(true); };
            f.controller.interact(f.player, 0); assertEquals(f.original, f.hand()); assertFalse(f.controller.hasContents());
        }
    }
    @Test void aTradeCallbackCannotSwitchTheSelectedSlotThenCommitTheOriginalBottle() {
        try (var f = new Fixture("holder")) {
            f.onEvent = event -> { if (event instanceof InventoryTradeEvent) f.selected = 1; };
            f.controller.interact(f.player, 0); assertEquals(f.original, f.slots[0]); assertFalse(f.controller.hasContents());
        }
    }
    @Test void aTradeCallbackCannotLoseItsChangedComponentsQuantityOrAnotherInventorySlot() {
        for (String change : List.of("components", "amount", "other")) try (var f = new Fixture("holder")) {
            f.onEvent = event -> {
                if (!(event instanceof InventoryTradeEvent)) return;
                if (change.equals("components")) f.slots[0] = new MolotovTestItems.Stack(MolotovPhysics.ITEM, 2, "listener-state");
                else if (change.equals("amount")) f.slots[0] = f.original.asQuantity(8);
                else f.slots[5] = new MolotovTestItems.Stack("minecraft:diamond", 1, "listener-item");
            };
            f.controller.interact(f.player, 0); assertFalse(f.controller.hasContents(), change);
            if (change.equals("amount")) assertEquals(8, f.hand().getAmount());
            else if (change.equals("other")) assertEquals("minecraft:diamond", ((MolotovTestItems.Stack) f.slots[5]).id);
            else assertEquals("listener-state", ((MolotovTestItems.Stack) f.hand()).components);
        }
    }
    @Test void disablingOrLoggingOutDuringTheInventoryEventPreservesTheBottle() {
        for (boolean disable : List.of(true, false)) try (var f = new Fixture("holder")) {
            f.onEvent = event -> { if (event instanceof InventoryTradeEvent) {
                if (disable) when(f.plugin.isEnabled()).thenReturn(false); else when(f.player.isOnline()).thenReturn(false);
            }};
            f.controller.interact(f.player, 0); assertEquals(f.original, f.hand()); assertFalse(f.controller.hasContents());
        }
    }
    @Test void synchronousInteractionReentryCannotStoreOrRecoverTheSameBottleTwice() {
        try (var f = new Fixture("holder")) {
            f.onEvent = event -> { if (event instanceof InventoryTradeEvent) f.controller.interact(f.player, 0); };
            f.controller.interact(f.player, 0); assertEquals(1, f.hand().getAmount()); assertTrue(f.controller.hasContents());
            f.slots[0] = null; f.controller.interact(f.player, 0); assertEquals(f.original.asQuantity(1), f.hand()); assertFalse(f.controller.hasContents());
        }
    }
    @Test void occupiedHandsCannotTakeOrReplaceAStoredBottle() {
        try (var f = new Fixture("holder")) {
            f.controller.interact(f.player, 0); f.slots[0] = new MolotovTestItems.Stack("minecraft:diamond", 1, "other");
            f.controller.interact(f.player, 0); assertTrue(f.controller.hasContents()); assertEquals("minecraft:diamond", ((MolotovTestItems.Stack) f.hand()).id);
        }
    }
    @Test void allStorageKindsCanReallyStoreAndRedstoneLaunchOneMolotov() {
        for (String id : List.of("holder", "tilted_rack", "circular_rack", "cellar_cabinet")) try (var f = new Fixture(id)) {
            f.controller.interact(f.player, 0); f.controller.redstone();
            assertEquals(1, f.launches.size(), id); assertFalse(f.controller.hasContents(), id);
            assertEquals(f.original.asQuantity(1), f.launches.getFirst().item);
            assertFalse(f.launches.getFirst().playerThrown);
            verify(f.world, never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
        }
    }
    @Test void aMultiSlotRedstonePulseSelectsExactlyOneOccupiedSourceSlot() {
        try (var f = new Fixture("cellar_cabinet")) {
            f.slots[0] = f.original.asQuantity(9);
            for (int i = 0; i < 9; i++) f.controller.interact(f.player, i);
            assertNull(f.hand()); f.controller.redstone(); assertEquals(1, f.launches.size());
            int returned = 0; for (int i = 0; i < 9; i++) { f.controller.interact(f.player, i); if (f.hand() != null) { returned++; f.slots[0] = null; } }
            assertEquals(8, returned); assertFalse(f.controller.hasContents());
        }
    }
    @Test void combinedNeighborPowerEjectsOnlyOnARisingEdgeRatherThanEveryNeighborChange() {
        try (var f = new Fixture("holder")) {
            f.controller.interact(f.player, 0); f.power = true; f.controller.refreshPower(); assertEquals(1, f.launches.size());
            f.controller.interact(f.player, 0); f.controller.refreshPower(); f.controller.refreshPower();
            assertEquals(1, f.launches.size()); assertTrue(f.controller.hasContents());
            f.power = false; f.controller.refreshPower(); f.power = true; f.controller.refreshPower();
            assertEquals(2, f.launches.size()); assertFalse(f.controller.hasContents());
        }
    }
    @Test void poweredStorageRestartCannotReplayAnOldHighSignalAsANewPulse() {
        try (var f = new Fixture("holder")) {
            f.controller.interact(f.player, 0); f.power = true; f.controller.refreshPower();
            f.controller.interact(f.player, 0); CompoundTag saved = f.saved();
            MolotovController restored = new MolotovController(f.furniture, f.plugin, () -> f.molotov); restored.loadCustomData(saved);
            restored.refreshPower(); assertEquals(1, f.launches.size()); assertTrue(restored.hasContents());
            f.power = false; restored.refreshPower(); f.power = true; restored.refreshPower();
            assertEquals(2, f.launches.size()); assertFalse(restored.hasContents());
        }
    }
    @Test void failedSpawnAndDisabledLaunchCallbacksRetainTheStoredBottle() {
        try (var f = new Fixture("holder")) {
            f.controller.interact(f.player, 0); f.launchAllowed = false; f.controller.redstone(); assertTrue(f.controller.hasContents());
        }
        try (var f = new Fixture("holder")) {
            f.controller.interact(f.player, 0); f.onLaunch = () -> when(f.plugin.isEnabled()).thenReturn(false);
            f.controller.redstone(); assertTrue(f.controller.hasContents());
        }
    }
    @Test void offlineOrForeignRegionOperatorsCannotSpendAStoredBottle() {
        try (var f = new Fixture("holder")) {
            f.controller.interact(f.player, 0); when(f.player.isOnline()).thenReturn(false); f.controller.redstone();
            assertTrue(f.controller.hasContents()); assertTrue(f.launches.isEmpty());
        }
        try (var f = new Fixture("holder")) {
            f.controller.interact(f.player, 0); f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(f.player)).thenReturn(false); f.controller.redstone();
            assertTrue(f.controller.hasContents()); assertTrue(f.launches.isEmpty());
        }
    }
    @Test void sourceItemBytesAndSlotLocationsSurviveAControllerRestartWithoutDuplicateReturns() {
        try (var f = new Fixture("tilted_rack")) {
            f.controller.interact(f.player, 2); CompoundTag saved = f.saved();
            MolotovController restored = new MolotovController(f.furniture, f.plugin, () -> f.molotov); restored.loadCustomData(saved);
            assertFalse(restored.blocked()); f.slots[0] = null; restored.interact(f.player, 0); assertNull(f.hand());
            restored.interact(f.player, 2); assertEquals(f.original.asQuantity(1), f.hand());
            f.slots[0] = null; restored.interact(f.player, 2); assertNull(f.hand());
            CompoundTag empty = new CompoundTag(); restored.saveCustomData(empty);
            MolotovController again = new MolotovController(f.furniture, f.plugin, () -> f.molotov); again.loadCustomData(empty);
            assertFalse(again.hasContents());
        }
    }
    @Test void aCancelledPlacedBottleBreakDoesNotSpawnOrConsumeTheGroundBottle() {
        try (var f = new Fixture("molotov")) {
            f.onEvent = event -> { if (event instanceof FurnitureBreakEvent removal) removal.setCancelled(true); };
            assertFalse(f.controller.struck(f.projectile)); assertTrue(f.launches.isEmpty()); assertTrue(f.valid); assertEquals(0, f.removals);
        }
    }
    @Test void aSuccessfulPlacedBottleHitSpawnsStationaryAndRemovesTheOriginalWithoutDroppingIt() {
        try (var f = new Fixture("molotov")) {
            assertTrue(f.controller.struck(f.projectile)); assertEquals(1, f.removals); assertFalse(f.valid);
            assertEquals(1, f.launches.size()); assertEquals(new Vector(), f.launches.getFirst().velocity);
            assertEquals(new Location(f.world, .5, 64.5, .5), f.launches.getFirst().at);
            assertFalse(f.controller.struck(f.projectile)); assertEquals(1, f.launches.size());
        }
    }
    @Test void failedGroundSpawnLeavesTheBottleInPlaceAndDoesNotIssueGlassEffects() {
        try (var f = new Fixture("molotov")) {
            f.launchAllowed = false; assertFalse(f.controller.struck(f.projectile)); assertTrue(f.valid); assertEquals(0, f.removals);
            verify(f.world, never()).playEffect(any(Location.class), any(Effect.class), any(Material.class));
        }
    }
    @Test void nonPlayerProjectilesRespectMobGriefingBeforeBreakingTheGroundBottle() {
        try (var f = new Fixture("molotov")) {
            when(f.projectile.getShooter()).thenReturn(mock(org.bukkit.entity.Skeleton.class)); when(f.world.getGameRuleValue(GameRules.MOB_GRIEFING)).thenReturn(false);
            assertFalse(f.controller.struck(f.projectile)); assertTrue(f.valid); assertTrue(f.launches.isEmpty());
        }
    }
    @Test void ownerlessProjectilesRetainTheSourcePermissionToBreakEvenWithMobGriefingDisabled() {
        try (var f = new Fixture("molotov")) {
            when(f.projectile.getShooter()).thenReturn(null); when(f.world.getGameRuleValue(GameRules.MOB_GRIEFING)).thenReturn(false);
            assertTrue(f.controller.struck(f.projectile)); assertFalse(f.valid); assertEquals(1, f.launches.size());
        }
    }
    @Test void rackToGroundChainsCannotLoseTheirOriginalProtectionActor() {
        try (var f = new Fixture("molotov")) {
            when(f.projectile.getShooter()).thenReturn(null); when(f.molotov.responsibleFor(f.projectile)).thenReturn(f.player);
            when(f.plugin.allows(eq(f.player), eq(net.momirealms.antigrieflib.Flag.BREAK), any(Location.class))).thenReturn(false);
            assertFalse(f.controller.struck(f.projectile)); assertTrue(f.valid); assertTrue(f.launches.isEmpty());
        }
        try (var f = new Fixture("molotov")) {
            when(f.projectile.getShooter()).thenReturn(null); when(f.molotov.responsibleFor(f.projectile)).thenReturn(f.player);
            when(f.player.isOnline()).thenReturn(false);
            assertFalse(f.controller.struck(f.projectile)); assertTrue(f.valid); assertTrue(f.launches.isEmpty());
        }
    }
    @Test void emptyHandGroundRecoveryCommitsOneFullSourceItemOnlyAfterTheTradeEvent() {
        try (var f = new Fixture("molotov")) {
            f.slots[0] = null; f.controller.interact(f.player, 0);
            assertEquals(1, f.removals); assertEquals(f.original.asQuantity(1), f.hand()); assertFalse(f.valid);
            assertTrue(f.pickupRecords().isEmpty());
        }
    }
    @Test void nativeEntityRemovalHotbarChangesCannotRedirectTheRecoveredBottleOrOverwriteTheNewHand() {
        try (var f = new Fixture("molotov")) {
            f.slots[0] = null;
            ItemStack listenerItem = new MolotovTestItems.Stack("minecraft:diamond", 3, "listener-selected-hand");
            f.onEvent = event -> { if (event instanceof EntityRemoveFromWorldEvent) {
                assertEquals(1, f.pickupRecords().size(), "The full source is durable before native destruction callbacks");
                f.slots[1] = listenerItem; f.selected = 1;
                f.controller.interact(f.player, 0); // Reentry during CE's synchronous native-entity destruction.
            }};
            f.controller.interact(f.player, 0);
            assertSame(listenerItem, f.hand()); assertEquals(1, f.selected);
            assertEquals(f.original.asQuantity(1), f.slots[0]); assertEquals(1, f.removals);
            assertTrue(f.pickupRecords().isEmpty());
            verify(f.inventory, never()).setItemInMainHand(any());
            verify(f.inventory, never()).setStorageContents(any());
        }
    }
    @Test void nativeEntityRemovalReplacingTheOriginalSlotReplansWithoutLosingAnyListenerItem() {
        try (var f = new Fixture("molotov")) {
            f.slots[0] = null;
            ItemStack changed = new MolotovTestItems.Stack("minecraft:diamond", 3, "listener-original-slot");
            ItemStack other = new MolotovTestItems.Stack("minecraft:emerald", 7, "listener-other-slot");
            f.onEvent = event -> { if (event instanceof EntityRemoveFromWorldEvent) { f.slots[0] = changed; f.slots[8] = other; }};
            f.controller.interact(f.player, 0);
            assertEquals(changed, f.slots[0]); assertEquals(other, f.slots[8]);
            assertEquals(f.original.asQuantity(1), f.slots[1]); assertEquals(1, f.removals);
            assertTrue(f.pickupRecords().isEmpty()); verify(f.inventory).setStorageContents(any());
        }
    }
    @Test void aFullInventoryAfterNativeRemovalRetainsOneExactManualSnapshotWithoutReplacingAnySlot() {
        try (var f = new Fixture("molotov")) {
            f.slots[0] = null;
            f.onEvent = event -> { if (event instanceof EntityRemoveFromWorldEvent) for (int i = 0; i < 36; i++)
                f.slots[i] = new MolotovTestItems.Stack("minecraft:diamond", 64, "listener-full-" + i); };
            f.controller.interact(f.player, 0);
            assertEquals(1, f.removals); assertFalse(f.valid);
            for (int i = 0; i < 36; i++) assertEquals("listener-full-" + i, ((MolotovTestItems.Stack) f.slots[i]).components);
            CompoundTag record = f.retainedPickup();
            assertEquals(f.original.asQuantity(1), SavedItems.get(record, "source_item", 1));
            String token = record.getString("recovery_token");
            assertTrue(record.getString("recovery_rule").contains("Never auto-issue"));
            f.controller.interact(f.player, 0);
            assertEquals(1, f.removals); assertEquals(token, f.retainedPickup().getString("recovery_token"));
            verify(f.inventory, never()).setItem(anyInt(), any()); verify(f.inventory, never()).setStorageContents(any());
        }
    }
    @Test void cancellingTheReplannedDeliveryLeavesTheListenersSlotAndOneManualSnapshot() {
        try (var f = new Fixture("molotov")) {
            f.slots[0] = null; int[] trades = {0};
            ItemStack changed = new MolotovTestItems.Stack("minecraft:diamond", 3, "listener-state");
            f.onEvent = event -> {
                if (event instanceof EntityRemoveFromWorldEvent) f.slots[0] = changed;
                if (event instanceof InventoryTradeEvent trade && ++trades[0] == 2) trade.setCancelled(true);
            };
            f.controller.interact(f.player, 0);
            assertEquals(2, trades[0]); assertSame(changed, f.slots[0]); assertNull(f.slots[1]);
            assertEquals(f.original.asQuantity(1), SavedItems.get(f.retainedPickup(), "source_item", 1));
            verify(f.world, never()).playSound(any(Location.class), eq(Sound.BLOCK_STONE_PLACE), any(SoundCategory.class), anyFloat(), anyFloat());
            verify(f.inventory, never()).setStorageContents(any());
        }
    }
    @Test void replannedDeliveryCallbacksCannotLoseTheirOwnInventoryChangesOrRecoverTwice() {
        try (var f = new Fixture("molotov")) {
            f.slots[0] = null; int[] trades = {0};
            ItemStack changed = new MolotovTestItems.Stack("minecraft:diamond", 3, "listener-source-slot");
            ItemStack rewardSlot = new MolotovTestItems.Stack("minecraft:emerald", 4, "listener-reward-slot");
            f.onEvent = event -> {
                if (event instanceof EntityRemoveFromWorldEvent) f.slots[0] = changed;
                if (event instanceof InventoryTradeEvent && ++trades[0] == 2) {
                    f.slots[1] = rewardSlot; f.controller.interact(f.player, 0);
                }
            };
            f.controller.interact(f.player, 0);
            assertSame(changed, f.slots[0]); assertSame(rewardSlot, f.slots[1]);
            assertEquals(1, f.removals); assertEquals(1, f.pickupRecords().size());
            assertEquals(f.original.asQuantity(1), SavedItems.get(f.retainedPickup(), "source_item", 1));
            verify(f.inventory, never()).setStorageContents(any());
        }
    }
    @Test void nativeRemovalDisableLogoutDeathOrRegionTransferPreservesTheBottleWithoutAccessingPlayerSlots() {
        for (String change : List.of("disable", "logout", "death", "foreign")) try (var f = new Fixture("molotov")) {
            f.slots[0] = null;
            f.onEvent = event -> { if (event instanceof EntityRemoveFromWorldEvent) {
                switch (change) {
                    case "disable" -> when(f.plugin.isEnabled()).thenReturn(false);
                    case "logout" -> when(f.player.isOnline()).thenReturn(false);
                    case "death" -> when(f.player.isDead()).thenReturn(true);
                    case "foreign" -> f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(f.player)).thenReturn(false);
                }
            }};
            f.controller.interact(f.player, 0);
            assertEquals(1, f.removals, change); assertNull(f.slots[0], change);
            assertEquals(f.original.asQuantity(1), SavedItems.get(f.retainedPickup(), "source_item", 1), change);
            verify(f.inventory, never()).getItem(anyInt()); verify(f.inventory, never()).setItem(anyInt(), any());
            verify(f.inventory, never()).getStorageContents(); verify(f.inventory, never()).setStorageContents(any());
        }
    }
    @Test void failureToPersistThePreparedSourcePreventsNativeRemovalAndAnyInventoryWrite() throws Exception {
        try (var f = new Fixture("molotov")) {
            f.slots[0] = null; Files.writeString(f.dataFolder, "A regular file cannot contain a recovery folder");
            assertThrows(IllegalStateException.class, () -> f.controller.interact(f.player, 0));
            assertTrue(f.valid); assertEquals(0, f.removals); assertNull(f.slots[0]);
            verify(f.inventory, never()).setItem(anyInt(), any()); verify(f.inventory, never()).setStorageContents(any());
        }
    }
    @Test void aRefusedNativeRemovalKeepsTheGroundSourceAndClearsTheUnneededRecoveryIntent() {
        try (var f = new Fixture("molotov")) {
            f.slots[0] = null; f.removeAllowed = false; f.controller.interact(f.player, 0);
            assertTrue(f.valid); assertNull(f.slots[0]); assertTrue(f.pickupRecords().isEmpty());
        }
    }
    @Test void cancellingGroundPickupBeforeDestroyCreatesNeitherAnItemNorARecoveryIntent() {
        try (var f = new Fixture("molotov")) {
            f.slots[0] = null;
            f.onEvent = event -> { if (event instanceof InventoryTradeEvent trade) trade.setCancelled(true); };
            f.controller.interact(f.player, 0);
            assertTrue(f.valid); assertEquals(0, f.removals); assertNull(f.slots[0]); assertTrue(f.pickupRecords().isEmpty());
        }
    }
    @Test void deliveryPhasePersistenceFailureAfterDestroyKeepsThePreparedSourceAndDoesNotStartAnInventoryWrite() {
        try (var f = new Fixture("molotov")) {
            f.slots[0] = null;
            f.onEvent = event -> { if (event instanceof EntityRemoveFromWorldEvent) {
                Path record = f.pickupRecords().getFirst();
                try { Files.writeString(record.resolveSibling(record.getFileName() + ".tmp"), "block the next phase write"); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }};
            assertThrows(IllegalStateException.class, () -> f.controller.interact(f.player, 0));
            assertFalse(f.valid); assertEquals(1, f.removals); assertNull(f.slots[0]);
            CompoundTag record = f.retainedPickup();
            assertEquals("prepared_before_remove_verify_world_and_inventory", record.getString("recovery_phase"));
            assertEquals(f.original.asQuantity(1), SavedItems.get(record, "source_item", 1));
            verify(f.inventory, never()).setItem(anyInt(), any()); verify(f.inventory, never()).setStorageContents(any());
        }
    }
    @Test void nativeRemovalThrowingAfterDestroyRetainsTheSourceAndNeverBlindlyIssuesOrRetriesIt() {
        try (var f = new Fixture("molotov")) {
            f.slots[0] = null; f.destroyFailure = new IllegalStateException("CE failure after native destruction");
            assertThrows(IllegalStateException.class, () -> f.controller.interact(f.player, 0));
            assertFalse(f.valid); assertNull(f.slots[0]); assertEquals(1, f.removals);
            assertEquals(f.original.asQuantity(1), SavedItems.get(f.retainedPickup(), "source_item", 1));
            f.controller.interact(f.player, 0); assertEquals(1, f.removals); assertEquals(1, f.pickupRecords().size());
            verify(f.inventory, never()).setItem(anyInt(), any()); verify(f.inventory, never()).setStorageContents(any());
        }
    }
    @Test void contentsGuardBlocksExternalBreaksAndDisplaysAreRebuiltThenRemovedOnUnload() {
        try (var f = new Fixture("holder")) {
            f.controller.onLoad(); f.controller.interact(f.player, 0);
            assertTrue(f.controller.blocksBreak(new FurnitureBreakEvent(f.player, f.furniture)));
            assertEquals(1, f.displays.size()); verify(f.displays.getFirst()).setPersistent(false);
            f.controller.onUnload(); verify(f.displays.getFirst()).remove(); verify(f.molotov).unloaded(f.controller);
        }
    }
    @Test void replacementControllersWithTheSameFurnitureUuidClearOldDisplayEntities() {
        try (var f = new Fixture("holder")) {
            f.controller.onLoad(); f.controller.interact(f.player, 0); assertEquals(1, f.displays.size());
            Molotov service = new Molotov(f.plugin); service.loaded(f.controller);
            MolotovController replacement = new MolotovController(f.furniture, f.plugin, () -> service);
            service.loaded(replacement); verify(f.displays.getFirst()).remove();
            service.close();
        }
    }

    @Test void forcedStorageRemovalArchivesEveryExactBottleAndLetsNativeBaseLootContinue() throws Exception {
        try (var f = new Fixture("cellar_cabinet")) {
            f.slots[0] = f.original.asQuantity(9);
            for (int slot = 0; slot < 9; slot++) f.controller.interact(f.player, slot);
            CompoundTag before = f.saved();
            NativeRemoval removal = f.nativeRemoval();
            boolean[] baseLootCanContinue = {false};
            assertDoesNotThrow(() -> { f.furniture.destroy(null); baseLootCanContinue[0] = true; });
            removal.assertDestroyed();
            assertTrue(baseLootCanContinue[0]);
            assertEquals(before.get(SAVE), f.retainedStorage().get(SAVE));
            assertEquals(f.furniture.uuid().toString(), f.retainedStorage().getString("recovery_furniture_uuid"));
            assertTrue(f.recoveryFailures.isEmpty());
            assertNull(f.hand(), "Manual recovery records never issue bottles automatically");
        }
    }

    @Test void forcedStorageRemovalWriteFailureLogsCompleteBinaryStateAndCannotAbortNativeDestruction() throws Exception {
        try (var f = new Fixture("holder")) {
            f.controller.interact(f.player, 0);
            CompoundTag before = f.saved();
            Files.createDirectories(f.dataFolder);
            Files.writeString(f.dataFolder.resolve("recovery"), "not a directory");
            NativeRemoval removal = f.nativeRemoval();
            clearInvocations(f.inventory);
            boolean[] baseLootCanContinue = {false};
            assertDoesNotThrow(() -> { f.furniture.destroy(null); baseLootCanContinue[0] = true; });
            removal.assertDestroyed();
            assertTrue(baseLootCanContinue[0]);
            assertEquals(before.get(SAVE), f.binaryEmergency().get(SAVE));
            assertEquals(f.furniture.uuid().toString(), f.binaryEmergency().getString("recovery_furniture_uuid"));
            verify(f.inventory, never()).setItemInMainHand(any());
            verify(f.inventory, never()).setStorageContents(any());
            assertTrue(f.failureRecord().contains("never auto replay"));
        }
    }

    @Test void forcedStorageRemovalEncodingFailuresLogFullNbtAndCannotEscapeTheNativeFinally() throws Exception {
        for (Throwable failure : List.of(new IllegalStateException("NBT encoder failed"), new NoClassDefFoundError("NBT codec"))) {
            try (var f = new Fixture("holder")) {
                f.controller.interact(f.player, 0);
                CompoundTag before = f.saved();
                NativeRemoval removal = f.nativeRemoval();
                FurniturePersistentData brokenCodec = mock(FurniturePersistentData.class);
                when(brokenCodec.toBytes()).thenThrow(failure);
                CompoundTag[] attemptedRoot = {null};
                try (var codec = mockStatic(FurniturePersistentData.class)) {
                    codec.when(() -> FurniturePersistentData.of(any(CompoundTag.class))).thenAnswer(call -> {
                        CompoundTag tag = call.getArgument(0);
                        if (!tag.containsKey(SAVE)) return call.callRealMethod();
                        attemptedRoot[0] = tag.deepClone();
                        return brokenCodec;
                    });
                    assertDoesNotThrow(() -> f.furniture.destroy(null));
                }
                removal.assertDestroyed();
                CompoundTag expected = f.recoveryMetadata();
                expected.put(SAVE, before.get(SAVE).deepClone());
                assertEquals(expected, attemptedRoot[0]);
                assertArrayEquals(f.original.asQuantity(1).serializeAsBytes(),
                        attemptedRoot[0].getCompound(SAVE).getCompound("payload").getByteArray("slot_0"));
                assertEquals(expected.toString(), f.snbtEmergencyText());
                assertTrue(f.failureRecord().contains("never auto replay"));
                verifyNoInteractions(f.unsafe);
                assertEquals(1, f.hand().getAmount());
            }
        }
    }

    @Test void envelopeEncodingFailuresKeepExactOriginalItemBytesInsteadOfDowngradingToJsonOrComponents() throws Exception {
        for (Throwable failure : List.of(new IllegalStateException("envelope encoder failed"), new NoClassDefFoundError("envelope codec"))) {
            try (var f = new Fixture("holder")) {
                f.controller.interact(f.player, 0);
                byte[] originalBytes = f.original.asQuantity(1).serializeAsBytes();
                NativeRemoval removal = f.nativeRemoval();
                FurniturePersistentData brokenCodec = mock(FurniturePersistentData.class);
                when(brokenCodec.toBytes()).thenThrow(failure);
                try (var codec = mockStatic(FurniturePersistentData.class)) {
                    codec.when(() -> FurniturePersistentData.of(any(CompoundTag.class))).thenReturn(brokenCodec);
                    assertDoesNotThrow(() -> f.furniture.destroy(null));
                }
                removal.assertDestroyed();
                CompoundTag expected = f.recoveryMetadata();
                expected.putString("recovery_storage", "HOLDER");
                expected.putInt("recovery_slots", 1);
                expected.putBoolean("recovery_powered", false);
                expected.putString("recovery_operator", f.player.getUniqueId().toString());
                CompoundTag source = new CompoundTag();
                source.putString("format", "BUKKIT_ITEM_STACK_BYTES");
                source.putByteArray("item_bytes", originalBytes);
                expected.put("recovery_slot_0", source);
                assertEquals(expected.toString(), f.snbtEmergencyText());
                assertEquals(f.original.asQuantity(1), MolotovTestItems.decode(source.getByteArray("item_bytes")));
                assertTrue(f.failureRecord().contains("never auto replay"));
                verifyNoInteractions(f.unsafe);
                assertEquals(1, f.hand().getAmount());
            }
        }
    }

    @Test void unreadableStorageSaveRemainsVerbatimInEmergencyBinaryState() throws Exception {
        try (var f = new Fixture("holder")) {
            CompoundTag corrupt = new CompoundTag();
            corrupt.putString(SAVE, "opaque damaged storage; do not reset");
            f.controller.loadCustomData(corrupt);
            Files.createDirectories(f.dataFolder);
            Files.writeString(f.dataFolder.resolve("recovery"), "not a directory");
            NativeRemoval removal = f.nativeRemoval();
            assertDoesNotThrow(() -> f.furniture.destroy(null));
            removal.assertDestroyed();
            assertEquals(corrupt.get(SAVE), f.binaryEmergency().get(SAVE));
            assertTrue(f.controller.blocked());
            assertEquals(f.original, f.hand());
        }
    }

    @Test void itemByteSerializationFailureRetainsPaperJsonAndDataVersionVerbatim() throws Exception {
        try (var f = new Fixture("holder")) {
            ItemStack item = f.unserializableStoredItem();
            // This stub checks the API record contract, not a live Paper item-codec/component round-trip.
            var json = com.google.gson.JsonParser.parseString("{\"id\":\"minecraft:paper\",\"count\":1,"
                    + "\"components\":{\"minecraft:custom_data\":{\"private_source\":\"exact payload\"}},\"DataVersion\":1234}").getAsJsonObject();
            when(f.unsafe.serializeItemAsJson(item)).thenReturn(json);
            NativeRemoval removal = f.nativeRemoval();
            assertDoesNotThrow(() -> f.furniture.destroy(null));
            removal.assertDestroyed();
            CompoundTag source = new CompoundTag();
            source.putString("format", "PAPER_ITEM_STACK_JSON");
            source.putString("item_json", json.toString());
            assertTrue(f.failureRecord().contains(source.toString()));
            assertTrue(f.failureRecord().contains(f.furniture.uuid().toString()));
            assertTrue(f.failureRecord().contains("never auto replay"));
            assertFalse(f.failureRecord().contains("BEST_EFFORT"));
            verify(f.unsafe).serializeItemAsJson(item);
            verify(f.unsafe, never()).deserializeItemFromJson(any(com.google.gson.JsonObject.class));
            verify(item, never()).getItemMeta();
            assertEquals(f.original, f.hand());
        }
    }

    @Test void failedOrIncompletePaperJsonMakesTheComponentFallbackExplicitlyBestEffort() throws Exception {
        for (String failure : List.of("runtime", "linkage", "null", "missing_data_version", "missing_item_id")) {
            try (var f = new Fixture("holder")) {
                ItemStack item = f.unserializableStoredItem();
                switch (failure) {
                    case "runtime" -> when(f.unsafe.serializeItemAsJson(item)).thenThrow(new IllegalStateException("JSON codec unavailable"));
                    case "linkage" -> when(f.unsafe.serializeItemAsJson(item)).thenThrow(new NoClassDefFoundError("JSON codec"));
                    case "null" -> when(f.unsafe.serializeItemAsJson(item)).thenReturn(null);
                    case "missing_data_version" -> when(f.unsafe.serializeItemAsJson(item)).thenReturn(
                            com.google.gson.JsonParser.parseString("{\"id\":\"minecraft:paper\",\"count\":1}").getAsJsonObject());
                    default -> when(f.unsafe.serializeItemAsJson(item)).thenReturn(
                            com.google.gson.JsonParser.parseString("{\"DataVersion\":1234}").getAsJsonObject());
                }
                var meta = mock(org.bukkit.inventory.meta.ItemMeta.class);
                String components = "[minecraft:custom_data={private_source:\"best effort components\"}]";
                when(item.getItemMeta()).thenReturn(meta);
                when(meta.getAsComponentString()).thenReturn(components);
                NativeRemoval removal = f.nativeRemoval();
                assertDoesNotThrow(() -> f.furniture.destroy(null));
                removal.assertDestroyed();
                CompoundTag source = new CompoundTag();
                source.putString("item", "minecraft:paper" + components);
                assertTrue(f.failureRecord().contains(source.get("item").toString()));
                assertTrue(f.failureRecord().contains("count:1"));
                assertTrue(f.failureRecord().contains("recovery_slot_0"));
                assertTrue(f.failureRecord().contains("recovery_storage"));
                assertTrue(f.failureRecord().contains("BEST_EFFORT_NOT_GUARANTEED_COMPLETE"));
                assertTrue(f.failureRecord().contains(f.furniture.uuid().toString()));
                assertTrue(f.failureRecord().contains("never auto replay"));
                assertFalse(f.failureRecord().contains("PAPER_ITEM_STACK_JSON"));
                verify(f.unsafe, never()).deserializeItemFromJson(any(com.google.gson.JsonObject.class));
                assertEquals(f.original, f.hand());
            }
        }
    }

    @Test void aRejectedTagCloneFailureStillLogsItsOriginalFullSnbtDuringNativeRemoval() throws Exception {
        try (var f = new Fixture("holder")) {
            var rejected = mock(net.momirealms.craftengine.libraries.nbt.Tag.class);
            when(rejected.deepClone()).thenReturn(rejected);
            when(rejected.toString()).thenReturn("\"opaque damaged storage; preserve every byte\"");
            CompoundTag corrupt = new CompoundTag(); corrupt.put(SAVE, rejected);
            f.controller.loadCustomData(corrupt);
            assertTrue(f.controller.blocked());
            when(rejected.deepClone()).thenThrow(new IllegalStateException("cannot clone damaged tag"));
            NativeRemoval removal = f.nativeRemoval();
            assertDoesNotThrow(() -> f.furniture.destroy(null));
            removal.assertDestroyed();
            assertTrue(f.failureRecord().contains("recovery_rejected_snbt"));
            assertTrue(f.failureRecord().contains("opaque damaged storage; preserve every byte"));
            assertTrue(f.failureRecord().contains("never auto replay"));
            assertEquals(f.original, f.hand());
        }
    }

    @Test void failingBestEffortFieldsCannotHideLaterHealthySlotsOrAbortNativeBaseLoot() throws Exception {
        for (boolean linkage : List.of(false, true)) {
            for (String field : List.of("empty", "meta", "components", "type", "count", "all")) {
                try (var f = new Fixture("tilted_rack")) {
                    ItemStack broken = f.unserializableStoredItem();
                    ItemStack second = new MolotovTestItems.Stack(MolotovPhysics.ITEM, 1, "second exact source");
                    ItemStack third = new MolotovTestItems.Stack(MolotovPhysics.ITEM, 1, "third exact source");
                    f.storedItem(1, second); f.storedItem(2, third);
                    Throwable getterFailure = linkage ? new NoClassDefFoundError("broken " + field)
                            : new IllegalStateException("broken " + field);
                    var jsonFailure = new NoClassDefFoundError("JSON codec unavailable");
                    when(f.unsafe.serializeItemAsJson(broken)).thenThrow(jsonFailure);
                    String components = "[minecraft:custom_data={private_source:\"best effort components\"}]";
                    var meta = mock(org.bukkit.inventory.meta.ItemMeta.class);
                    when(broken.getItemMeta()).thenReturn(meta);
                    when(meta.getAsComponentString()).thenReturn(components);
                    switch (field) {
                        case "empty" -> {
                            when(broken.isEmpty()).thenThrow(getterFailure);
                            doReturn(f.original.asQuantity(1).serializeAsBytes()).when(broken).serializeAsBytes();
                        }
                        case "meta" -> when(broken.getItemMeta()).thenThrow(getterFailure);
                        case "components" -> when(meta.getAsComponentString()).thenThrow(getterFailure);
                        case "type" -> when(broken.getType()).thenThrow(getterFailure);
                        case "count" -> when(broken.getAmount()).thenThrow(getterFailure);
                        default -> {
                            when(broken.getItemMeta()).thenThrow(getterFailure);
                            when(broken.getType()).thenThrow(getterFailure);
                            when(broken.getAmount()).thenThrow(getterFailure);
                        }
                    }
                    NativeRemoval removal = f.nativeRemoval();
                    clearInvocations(f.inventory);
                    boolean[] baseLootCanContinue = {false};
                    assertDoesNotThrow(() -> { f.furniture.destroy(null); baseLootCanContinue[0] = true; });
                    removal.assertDestroyed();
                    assertTrue(baseLootCanContinue[0]);
                    CompoundTag expected = f.recoveryMetadata();
                    expected.putString("recovery_storage", "TILTED");
                    expected.putInt("recovery_slots", 3); expected.putBoolean("recovery_powered", false);
                    CompoundTag source = new CompoundTag();
                    if (field.equals("empty")) {
                        source = itemByteRecovery(f.original.asQuantity(1));
                        verify(f.unsafe, never()).serializeItemAsJson(any());
                        verify(broken, times(1)).isEmpty();
                    } else {
                        source.putString("recovery_bytes_failure", "java.lang.IllegalStateException: item byte serializer unavailable");
                        source.putString("recovery_json_failure", jsonFailure.toString());
                        source.putString("recovery_quality", "BEST_EFFORT_NOT_GUARANTEED_COMPLETE");
                        source.putBoolean("recovery_incomplete", true);
                        boolean missingType = field.equals("type") || field.equals("all");
                        boolean missingComponents = field.equals("meta") || field.equals("components") || field.equals("all");
                        boolean missingCount = field.equals("count") || field.equals("all");
                        if (missingType) source.putString("recovery_type_failure", getterFailure.toString());
                        else source.putString("item_type", "minecraft:paper");
                        if (missingComponents) source.putString("recovery_components_failure", getterFailure.toString());
                        else source.putString("item_components", components);
                        if (!missingType && !missingComponents) source.putString("item", "minecraft:paper" + components);
                        if (missingCount) source.putString("recovery_count_failure", getterFailure.toString());
                        else source.putInt("count", 1);
                    }
                    expected.put("recovery_slot_0", source);
                    expected.put("recovery_slot_1", itemByteRecovery(second));
                    expected.put("recovery_slot_2", itemByteRecovery(third));
                    assertEquals(expected.toString(), f.snbtEmergencyText(), f::failureDiagnostic);
                    assertTrue(f.failureRecord().contains("never auto replay"));
                    verifyNoInteractions(f.inventory);
                    assertEquals(f.original, f.hand());
                }
            }
        }
    }

    @Test void anUnrenderableRejectedTagIsNotRenderedTwiceAndCannotAbortNativeDestructionOrBaseLoot() throws Exception {
        for (Throwable renderingFailure : List.of(new IllegalStateException("SNBT visitor unavailable"),
                new NoClassDefFoundError("SNBT visitor"))) {
            try (var f = new Fixture("holder")) {
                var rejected = mock(net.momirealms.craftengine.libraries.nbt.Tag.class);
                when(rejected.deepClone()).thenReturn(rejected);
                doThrow(renderingFailure).when(rejected).accept(any(net.momirealms.craftengine.libraries.nbt.TagVisitor.class));
                CompoundTag corrupt = new CompoundTag(); corrupt.put(SAVE, rejected);
                f.controller.loadCustomData(corrupt);
                assertTrue(f.controller.blocked());
                NativeRemoval removal = f.nativeRemoval();
                var encodingFailure = new IllegalStateException("binary NBT unavailable");
                FurniturePersistentData brokenCodec = mock(FurniturePersistentData.class);
                when(brokenCodec.toBytes()).thenThrow(encodingFailure);
                clearInvocations(f.inventory);
                boolean[] baseLootCanContinue = {false};
                try (var codec = mockStatic(FurniturePersistentData.class)) {
                    codec.when(() -> FurniturePersistentData.of(any(CompoundTag.class))).thenReturn(brokenCodec);
                    assertDoesNotThrow(() -> { f.furniture.destroy(null); baseLootCanContinue[0] = true; });
                }
                removal.assertDestroyed();
                assertTrue(baseLootCanContinue[0]);
                Location at = f.controller.location();
                assertEquals("BEST_EFFORT_INCOMPLETE_SNBT; recovery_furniture_uuid=" + f.furniture.uuid()
                        + "; recovery_world=" + f.world.getUID() + "; recovery_furniture=" + f.furniture.id().asString()
                        + "; recovery_position=" + at.getX() + "," + at.getY() + "," + at.getZ(), f.emergencyText());
                assertSame(encodingFailure, f.recoveryCauses.getFirst());
                assertArrayEquals(new Throwable[]{renderingFailure}, encodingFailure.getSuppressed());
                verify(rejected, times(1)).accept(any(net.momirealms.craftengine.libraries.nbt.TagVisitor.class));
                assertTrue(f.failureRecord().contains("never auto replay"));
                verifyNoInteractions(f.inventory);
                assertEquals(f.original, f.hand());
            }
        }
    }

    private static CompoundTag itemByteRecovery(ItemStack item) {
        CompoundTag source = new CompoundTag();
        source.putString("format", "BUKKIT_ITEM_STACK_BYTES");
        source.putByteArray("item_bytes", item.serializeAsBytes());
        return source;
    }

    @Test void realCraftEngineDestroyCannotBeVetoedByThrowingFromAnyPreRemoveCallback() throws Exception {
        try (var f = new Fixture("holder")) {
            NativeRemoval removal = f.nativeRemoval();
            doThrow(new IllegalStateException("CE finally still destroys")).when(removal.lifecycle).preRemove(any());
            assertThrows(IllegalStateException.class, () -> f.furniture.destroy(null));
            removal.assertDestroyed();
        }
    }

    private record Launch(boolean playerThrown, Location at, Vector velocity, ItemStack item) {}
    private static final class Fixture implements AutoCloseable {
        final TavernPlugin plugin = mock(TavernPlugin.class);
        final BukkitFurniture furniture = mock(BukkitFurniture.class);
        final Molotov molotov = mock(Molotov.class);
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final Projectile projectile = mock(Projectile.class);
        final World world = mock(World.class);
        final org.bukkit.block.Block powerBlock = mock(org.bukkit.block.Block.class);
        final PluginManager manager = mock(PluginManager.class);
        final Logger logger = mock(Logger.class);
        final UnsafeValues unsafe = mock(UnsafeValues.class);
        final ItemStack original = MolotovTestItems.bottle(2);
        final ItemStack[] slots = new ItemStack[41];
        final Path dataFolder = temporaryFiles.resolve(UUID.randomUUID().toString());
        final List<Launch> launches = new ArrayList<>();
        final List<ItemDisplay> displays = new ArrayList<>();
        final List<String> recoveryFailures = new ArrayList<>();
        final List<Throwable> recoveryCauses = new ArrayList<>();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<Items> items = mockStatic(Items.class);
        final MockedStatic<CraftEngineFurniture> furnitureApi = mockStatic(CraftEngineFurniture.class);
        final MolotovController controller;
        Consumer<Event> onEvent = ignored -> {};
        Runnable onLaunch = () -> {};
        int selected, removals;
        boolean valid = true, launchAllowed = true, power, removeAllowed = true;
        RuntimeException destroyFailure;
        Fixture(String id) {
            slots[0] = original.clone();
            when(plugin.isEnabled()).thenReturn(true); when(plugin.mayUse(eq(player), any())).thenReturn(true);
            when(plugin.getName()).thenReturn("KaleidoscopeTavernPlugin"); when(plugin.namespace()).thenReturn("kaleidoscopetavernplugin");
            when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
            when(plugin.allows(eq(player), any(), any())).thenReturn(true); when(plugin.getLogger()).thenReturn(logger);
            doAnswer(call -> { recoveryFailures.add(call.getArgument(1)); recoveryCauses.add(call.getArgument(2)); return null; }).when(logger)
                    .log(eq(java.util.logging.Level.SEVERE), startsWith("MOLOTOV STORAGE RECOVERY FAILED;"), any(Throwable.class));
            when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true); when(player.getInventory()).thenReturn(inventory);
            when(inventory.getHeldItemSlot()).thenAnswer(call -> selected);
            when(inventory.getHolder()).thenReturn(player); when(inventory.getMaxStackSize()).thenReturn(64);
            when(inventory.getItem(anyInt())).thenAnswer(call -> slots[call.getArgument(0, Integer.class)]);
            doAnswer(call -> { slots[call.getArgument(0, Integer.class)] = call.getArgument(1); return null; })
                    .when(inventory).setItem(anyInt(), nullable(ItemStack.class));
            when(inventory.getStorageContents()).thenAnswer(call -> Arrays.copyOf(slots, 36));
            doAnswer(call -> { System.arraycopy(call.getArgument(0, ItemStack[].class), 0, slots, 0, 36); return null; })
                    .when(inventory).setStorageContents(any(ItemStack[].class));
            when(inventory.getItemInMainHand()).thenAnswer(call -> slots[selected]); when(inventory.getContents()).thenAnswer(call -> slots.clone());
            doAnswer(call -> { slots[selected] = call.getArgument(0); return null; }).when(inventory).setItemInMainHand(nullable(ItemStack.class));
            when(world.getUID()).thenReturn(UUID.randomUUID()); when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
            when(world.getBlockAt(any(Location.class))).thenReturn(powerBlock);
            when(powerBlock.isBlockPowered()).thenAnswer(call -> power);
            when(world.getGameRuleValue(GameRules.MOB_GRIEFING)).thenReturn(true);
            var ceWorld = mock(net.momirealms.craftengine.core.world.World.class); when(ceWorld.platformWorld()).thenReturn(world);
            when(furniture.position()).thenReturn(new WorldPosition(ceWorld, .5, 64, .5, 0, 0));
            when(furniture.id()).thenReturn(Key.of("kaleidoscope_tavern:" + id)); when(furniture.uuid()).thenReturn(UUID.randomUUID());
            when(furniture.isValid()).thenAnswer(call -> valid); when(furniture.baseEntity()).thenReturn(mock(Entity.class));
            when(projectile.getShooter()).thenReturn(player); when(projectile.isValid()).thenReturn(true);
            when(molotov.responsibleFor(any())).thenAnswer(call -> call.getArgument(0, Projectile.class).getShooter() instanceof Player p ? p : null);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager); bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
            when(unsafe.deserializeItem(any(byte[].class))).thenAnswer(call -> MolotovTestItems.decode(call.getArgument(0)));
            doAnswer(call -> { onEvent.accept(call.getArgument(0)); return null; }).when(manager).callEvent(any(Event.class));
            items.when(() -> Items.id(any())).thenAnswer(call -> call.getArgument(0) instanceof MolotovTestItems.Stack stack ? stack.id : "minecraft:air");
            items.when(() -> Items.create(MolotovPhysics.ITEM)).thenReturn(original.asQuantity(1));
            when(molotov.launch(any(), eq(false), any(), any(), any(), any())).thenAnswer(call -> {
                onLaunch.run(); if (!launchAllowed) return false;
                if (!call.getArgument(5, BooleanSupplier.class).getAsBoolean()) return false;
                launches.add(new Launch(call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)));
                return true;
            });
            controller = new MolotovController(furniture, plugin, () -> molotov);
            furnitureApi.when(() -> CraftEngineFurniture.remove(eq(furniture), isNull(net.momirealms.craftengine.core.entity.player.Player.class), eq(false), eq(false)))
                    .thenAnswer(call -> {
                        removals++; if (!removeAllowed) return false;
                        controller.preRemove(null); valid = false;
                        // Mirror CE destroy: metadata Entity.remove synchronously publishes this event,
                        // then the controller is unloaded before CraftEngineFurniture.remove returns.
                        manager.callEvent(new EntityRemoveFromWorldEvent(furniture.baseEntity(), world));
                        controller.postRemove(null);
                        if (destroyFailure != null) throw destroyFailure;
                        return true;
                    });
            when(world.spawn(any(Location.class), eq(ItemDisplay.class), org.mockito.ArgumentMatchers.<Consumer<ItemDisplay>>any())).thenAnswer(call -> {
                ItemDisplay display = mock(ItemDisplay.class); when(display.isValid()).thenReturn(true);
                call.<Consumer<ItemDisplay>>getArgument(2).accept(display); displays.add(display); return display;
            });
        }
        ItemStack hand() { return slots[selected]; }
        List<Path> pickupRecords() {
            Path folder = dataFolder.resolve("recovery");
            if (!Files.isDirectory(folder)) return List.of();
            try (var files = Files.list(folder)) {
                return files.filter(path -> path.getFileName().toString().startsWith("molotov-pickup-")
                        && path.getFileName().toString().endsWith(".nbt")).toList();
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        CompoundTag retainedPickup() {
            List<Path> files = pickupRecords(); assertEquals(1, files.size());
            try { return FurniturePersistentData.fromBytes(Files.readAllBytes(files.getFirst())).copyTag(); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        CompoundTag saved() { CompoundTag root = new CompoundTag(); controller.saveCustomData(root); return root; }
        ItemStack unserializableStoredItem() throws ReflectiveOperationException {
            ItemStack item = mock(ItemStack.class);
            when(item.isEmpty()).thenReturn(false);
            when(item.getType()).thenReturn(Material.PAPER);
            when(item.getAmount()).thenReturn(1);
            when(item.serializeAsBytes()).thenThrow(new IllegalStateException("item byte serializer unavailable"));
            storedItem(0, item);
            return item;
        }
        void storedItem(int slot, ItemStack item) throws ReflectiveOperationException {
            var stored = MolotovController.class.getDeclaredField("slots");
            stored.setAccessible(true);
            ((ItemStack[]) stored.get(controller))[slot] = item;
        }
        String failureRecord() { assertEquals(1, recoveryFailures.size()); return recoveryFailures.getFirst(); }
        String failureDiagnostic() {
            var trace = new java.io.StringWriter();
            for (Throwable failure : recoveryCauses) failure.printStackTrace(new java.io.PrintWriter(trace));
            return failureRecord() + "\n" + trace;
        }
        String snbtEmergencyText() {
            String state = emergencyText();
            assertTrue(state.startsWith("snbt="), this::failureDiagnostic);
            return state.substring("snbt=".length());
        }
        String emergencyText() {
            String marker = "; manual state=";
            String record = failureRecord();
            int start = record.indexOf(marker);
            assertTrue(start >= 0, this::failureDiagnostic);
            return record.substring(start + marker.length());
        }
        CompoundTag recoveryMetadata() {
            CompoundTag root = new CompoundTag();
            root.putString("recovery_furniture_uuid", furniture.uuid().toString());
            root.putString("recovery_world", world.getUID().toString());
            root.putString("recovery_furniture", furniture.id().asString());
            Location at = controller.location();
            root.putString("recovery_position", at.getX() + "," + at.getY() + "," + at.getZ());
            return root;
        }
        CompoundTag binaryEmergency() throws java.io.IOException {
            String record = failureRecord();
            int start = record.indexOf("base64_nbt="); assertTrue(start >= 0);
            return FurniturePersistentData.fromBytes(Base64.getDecoder().decode(record.substring(start + "base64_nbt=".length()))).copyTag();
        }
        CompoundTag retainedStorage() throws java.io.IOException {
            try (var files = Files.list(dataFolder.resolve("recovery"))) {
                List<Path> records = files.filter(path -> path.getFileName().toString().endsWith(".nbt")).toList();
                assertEquals(1, records.size());
                return FurniturePersistentData.fromBytes(Files.readAllBytes(records.getFirst())).copyTag();
            }
        }
        NativeRemoval nativeRemoval() throws ReflectiveOperationException {
            ItemDisplay meta = mock(ItemDisplay.class);
            Collider collider = mock(Collider.class);
            FurnitureSnapshotState snapshot = mock(FurnitureSnapshotState.class);
            FurnitureController lifecycle = mock(FurnitureController.class);
            when(snapshot.colliders()).thenReturn(List.of(collider));
            doAnswer(call -> { controller.preRemove(call.getArgument(0)); return null; }).when(lifecycle).preRemove(any());
            doAnswer(call -> { controller.postRemove(call.getArgument(0)); return null; }).when(lifecycle).postRemove(any());
            doAnswer(call -> { valid = false; manager.callEvent(new EntityRemoveFromWorldEvent(meta, world)); return null; }).when(meta).remove();
            setField(Furniture.class, "controller", furniture, lifecycle);
            setField(Furniture.class, "snapshot", furniture, snapshot);
            setField(BukkitFurniture.class, "metaEntity", furniture, new WeakReference<>(meta));
            when(furniture.baseEntity()).thenReturn(meta);
            doCallRealMethod().when(furniture).destroy(any());
            doCallRealMethod().when(furniture).destroySeats();
            return new NativeRemoval(furniture, meta, collider, snapshot, lifecycle);
        }
        @Override public void close() { furnitureApi.close(); items.close(); bukkit.close(); }
    }

    private record NativeRemoval(BukkitFurniture furniture, ItemDisplay meta, Collider collider,
                                 FurnitureSnapshotState snapshot, FurnitureController lifecycle) {
        void assertDestroyed() {
            verify(meta).remove();
            verify(collider).destroy();
            verify(furniture).destroySeats();
            verify(snapshot).destroySeats();
            verify(lifecycle).postRemove(any());
        }
    }

    private static void setField(Class<?> owner, String name, Object target, Object value) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
