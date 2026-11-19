package net.kaleidoscope.tavern;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import net.kaleidoscope.series.InventoryTradeEvent;
import net.kaleidoscope.series.Items;
import net.kaleidoscope.series.SavedItems;
import net.kaleidoscope.series.SavedState;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData;
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
        final UnsafeValues unsafe = mock(UnsafeValues.class);
        final ItemStack original = MolotovTestItems.bottle(2);
        final ItemStack[] slots = new ItemStack[41];
        final Path dataFolder = temporaryFiles.resolve(UUID.randomUUID().toString());
        final List<Launch> launches = new ArrayList<>();
        final List<ItemDisplay> displays = new ArrayList<>();
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
            when(plugin.allows(eq(player), any(), any())).thenReturn(true); when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
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
        @Override public void close() { furnitureApi.close(); items.close(); bukkit.close(); }
    }
}
