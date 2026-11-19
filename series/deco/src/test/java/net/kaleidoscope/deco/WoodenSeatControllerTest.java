package net.kaleidoscope.deco;

import net.kaleidoscope.series.InventoryTradeEvent;
import net.kaleidoscope.series.Items;
import net.kaleidoscope.series.SavedState;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Collider;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData;
import net.momirealms.craftengine.core.entity.furniture.FurnitureSnapshotState;
import net.momirealms.craftengine.core.entity.furniture.FurnitureVariant;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.seat.Seat;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WoodenSeatControllerTest {
    private static final String CHAIR = "kaleidoscope_deco:chair/oak";
    private static final String STOOL = "kaleidoscope_deco:cook_stool/oak";
    @TempDir Path dataFolder;

    @Test void survivalPlacementConsumesOneAndChangesOnlyTheFurnitureVariant() {
        try (var f = new Fixture(CHAIR, "minecraft:red_carpet", 3)) {
            assertEquals(InteractionResult.SUCCESS_AND_CANCEL, f.use());
            assertEquals("red", f.controller.carpet());
            assertEquals(2, f.slots[0].getAmount());
            verify(f.furniture).setVariant("cushion/red", true);
            verify(f.furniture).setUnsaved();
            verify(f.furniture, never()).setSourceItem(any());
            assertTrue(f.drops.isEmpty());
            assertFalse(f.controller.blocksRemoval());
        }
    }

    @Test void survivalSwapReturnsPreviousCarpetAtSourceHeight() {
        try (var f = new Fixture(CHAIR, "minecraft:blue_carpet", 3)) {
            f.load("red");
            f.use();
            assertEquals("blue", f.controller.carpet());
            assertEquals(2, f.slots[0].getAmount());
            assertEquals(1, f.drops.size());
            assertEquals("minecraft:red_carpet", f.drops.getFirst().getItemStack().toString());
            verify(f.drops.getFirst()).setPickupDelay(10);
            verify(f.world).dropItem(argThat(at -> at.getY() == 65), any(ItemStack.class), any(Consumer.class));
        }
    }

    @Test void applyingSameColorIsAnExactNoop() {
        try (var f = new Fixture(CHAIR, "minecraft:red_carpet", 3)) {
            f.load("red");
            f.use();
            assertEquals(3, f.slots[0].getAmount());
            verify(f.events, never()).callEvent(any());
            verify(f.furniture, never()).setVariant(anyString(), anyBoolean());
            assertTrue(f.drops.isEmpty());
        }
    }

    @Test void shearsRemoveOneCushionWithoutDamageOrSeatFallback() {
        try (var f = new Fixture(CHAIR, "minecraft:shears", 1)) {
            f.load("red");
            f.use();
            f.use();
            assertEquals("empty", f.controller.carpet());
            assertEquals(1, f.slots[0].getAmount());
            assertEquals(1, f.drops.size());
            verify(f.furniture, times(1)).setVariant("ground", true);
            verify(f.hitbox, never()).seats();
            verify(f.events, times(1)).callEvent(any(InventoryTradeEvent.class));
        }
    }

    @Test void creativePlacementAndSwapNeitherConsumeNorRefund() {
        try (var f = new Fixture(CHAIR, "minecraft:red_carpet", 3)) {
            when(f.player.getGameMode()).thenReturn(GameMode.CREATIVE);
            f.use();
            f.slots[0] = stack("minecraft:blue_carpet", 3);
            f.use();
            assertEquals("blue", f.controller.carpet());
            assertEquals(3, f.slots[0].getAmount());
            assertTrue(f.drops.isEmpty());
        }
    }

    @Test void creativeShearsDiscardCushionWithoutReward() {
        try (var f = new Fixture(CHAIR, "minecraft:shears", 1)) {
            f.load("red");
            when(f.player.getGameMode()).thenReturn(GameMode.CREATIVE);
            f.use();
            assertEquals("empty", f.controller.carpet());
            assertTrue(f.drops.isEmpty());
        }
    }

    @Test void otherHeldItemsUseNativeSeatingEvenWhenSneaking() {
        try (var f = new Fixture(CHAIR, "minecraft:stone", 1)) {
            when(f.actor.isSecondaryUseActive()).thenReturn(true);
            Seat occupied = mock(Seat.class), free = mock(Seat.class);
            when(occupied.isOccupied()).thenReturn(true);
            when(f.hitbox.seats()).thenReturn(new Seat[]{occupied, free});
            when(free.spawnSeat(f.actor, f.position)).thenReturn(true);
            f.use();
            verify(occupied, never()).spawnSeat(any(), any());
            verify(free).spawnSeat(f.actor, f.position);
            verify(f.events, never()).callEvent(any());
        }
    }

    @Test void cookStoolDoesNotInventCushionOrSeatingBehavior() {
        try (var f = new Fixture(STOOL, "minecraft:red_carpet", 3)) {
            assertEquals(InteractionResult.PASS, f.use());
            assertEquals("empty", f.controller.carpet());
            verify(f.hitbox, never()).seats();
            verify(f.events, never()).callEvent(any());
        }
    }

    @Test void offhandAndNonPlayerContextsPassWithoutTouchingInventory() {
        try (var f = new Fixture(CHAIR, "minecraft:red_carpet", 3)) {
            when(f.context.getHand()).thenReturn(InteractionHand.OFF_HAND);
            assertEquals(InteractionResult.PASS, f.use());
            when(f.context.getHand()).thenReturn(InteractionHand.MAIN_HAND);
            when(f.context.getPlayer()).thenReturn(null);
            assertEquals(InteractionResult.PASS, f.use());
            verify(f.inventory, never()).getItemInMainHand();
        }
    }

    @Test void protectionAndSpectatorAndDisabledPluginPreventAllEffects() {
        try (var f = new Fixture(CHAIR, "minecraft:red_carpet", 3)) {
            when(f.plugin.mayUse(any(), any())).thenReturn(false);
            assertEquals(InteractionResult.FAIL, f.use());
            when(f.plugin.mayUse(any(), any())).thenReturn(true);
            when(f.player.getGameMode()).thenReturn(GameMode.SPECTATOR);
            assertEquals(InteractionResult.FAIL, f.use());
            when(f.player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(f.plugin.isEnabled()).thenReturn(false);
            assertEquals(InteractionResult.FAIL, f.use());
            verify(f.events, never()).callEvent(any());
            verify(f.furniture, never()).setVariant(anyString(), anyBoolean());
        }
    }

    @Test void crossRegionAndStaleFurnitureCannotConsumeCarpet() {
        try (var f = new Fixture(CHAIR, "minecraft:red_carpet", 3)) {
            f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(false);
            assertEquals(InteractionResult.FAIL, f.use());
            f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
            when(f.furniture.isValid()).thenReturn(false);
            assertEquals(InteractionResult.FAIL, f.use());
            verify(f.events, never()).callEvent(any());
        }
    }

    @Test void cancelledTradePreservesCushionAndInventory() {
        try (var f = new Fixture(CHAIR, "minecraft:blue_carpet", 3)) {
            f.load("red");
            doAnswer(call -> { ((InventoryTradeEvent) call.getArgument(0)).setCancelled(true); return null; })
                    .when(f.events).callEvent(any(InventoryTradeEvent.class));
            f.use();
            f.assertUnchanged("red", 3);
            assertTrue(f.drops.isEmpty());
        }
    }

    @Test void selectedSlotDriftDuringTradeCannotApplyOrConsume() {
        try (var f = new Fixture(CHAIR, "minecraft:red_carpet", 3)) {
            doAnswer(call -> { f.heldSlot = 1; return null; }).when(f.events).callEvent(any(InventoryTradeEvent.class));
            f.use();
            f.assertUnchanged("empty", 3);
        }
    }

    @Test void gameModeDriftDuringTradeCannotChangeSurvivalAccounting() {
        try (var f = new Fixture(CHAIR, "minecraft:red_carpet", 3)) {
            doAnswer(call -> { when(f.player.getGameMode()).thenReturn(GameMode.CREATIVE); return null; })
                    .when(f.events).callEvent(any(InventoryTradeEvent.class));
            f.use();
            f.assertUnchanged("empty", 3);
        }
    }

    @Test void forcedNativeRemovalDuringTradeAbortsPaymentAndPreservesItsState() throws Exception {
        try (var f = new Fixture(CHAIR, "minecraft:red_carpet", 3)) {
            NativeRemoval nativeRemoval = f.nativeRemoval();
            doAnswer(call -> {
                assertTrue(f.controller.blocksRemoval());
                assertEquals(InteractionResult.FAIL, f.use());
                assertDoesNotThrow(() -> f.furniture.destroy(f.actor));
                return null;
            }).when(f.events).callEvent(any(InventoryTradeEvent.class));
            f.use();
            f.assertUnchanged("empty", 3);
            verify(f.events, times(1)).callEvent(any(InventoryTradeEvent.class));
            assertTrue(f.controller.blocksRemoval());
            assertEquals(f.save().get(WoodenSeatController.SAVE_KEY), f.recoveries().getFirst().get(WoodenSeatController.SAVE_KEY));
            nativeRemoval.assertDestroyed();
        }
    }

    @Test void cancelledRefundDropKeepsOldCushionAndPayment() {
        try (var f = new Fixture(CHAIR, "minecraft:blue_carpet", 3)) {
            f.load("red");
            f.acceptDrops = false;
            f.use();
            f.assertUnchanged("red", 3);
            assertFalse(f.drops.getFirst().isValid());
        }
    }

    @Test void modifiedRefundDropIsRemovedAndOldCushionRemains() {
        try (var f = new Fixture(CHAIR, "minecraft:blue_carpet", 3)) {
            f.load("red");
            f.substituteDrop = stack("minecraft:diamond", 64);
            f.use();
            f.assertUnchanged("red", 3);
            verify(f.drops.getFirst()).remove();
        }
    }

    @Test void spawnHookInventoryChangeIsPreservedWithoutConsumingOrDuplicating() {
        try (var f = new Fixture(CHAIR, "minecraft:blue_carpet", 3)) {
            f.load("red");
            ItemStack external = stack("minecraft:diamond", 2);
            f.dropHook = () -> f.slots[1] = external;
            f.use();
            f.assertUnchanged("red", 3);
            assertSame(external, f.slots[1]);
            verify(f.drops.getFirst()).remove();
        }
    }

    @Test void spawnHookPluginDisableRemovesRefundAndReleasesBusyFlag() {
        try (var f = new Fixture(CHAIR, "minecraft:blue_carpet", 3)) {
            f.load("red");
            f.dropHook = () -> when(f.plugin.isEnabled()).thenReturn(false);
            f.use();
            f.assertUnchanged("red", 3);
            assertFalse(f.controller.blocksRemoval());
            verify(f.drops.getFirst()).remove();
        }
    }

    @Test void refundSpawnDisconnectOrDeathCannotChargeOrApplyACushion() {
        for (boolean disconnect : new boolean[]{true, false}) {
            try (var f = new Fixture(CHAIR, "minecraft:blue_carpet", 3)) {
                f.load("red");
                f.dropHook = () -> {
                    if (disconnect) when(f.player.isOnline()).thenReturn(false);
                    else when(f.player.isDead()).thenReturn(true);
                };
                f.use();
                f.assertUnchanged("red", 3);
                verify(f.drops.getFirst()).remove();
                assertFalse(f.controller.blocksRemoval());
            }
        }
    }

    @Test void forcedRemovalFromRefundSpawnDoesNotLeaveAProvisionalCarpetOrCharge() throws Exception {
        try (var f = new Fixture(CHAIR, "minecraft:blue_carpet", 3)) {
            f.load("red");
            NativeRemoval nativeRemoval = f.nativeRemoval();
            f.dropHook = () -> f.furniture.destroy(f.actor);
            f.use();
            f.assertUnchanged("red", 3);
            verify(f.drops.getFirst()).remove();
            assertEquals(f.save().get(WoodenSeatController.SAVE_KEY), f.recoveries().getFirst().get(WoodenSeatController.SAVE_KEY));
            nativeRemoval.assertDestroyed();
        }
    }

    @Test void rejectedVariantRemovesRefundWithoutCharging() {
        try (var f = new Fixture(CHAIR, "minecraft:blue_carpet", 3)) {
            f.load("red");
            when(f.furniture.setVariant("cushion/blue", true)).thenReturn(false);
            f.use();
            assertEquals("red", f.controller.carpet());
            assertEquals(3, f.slots[0].getAmount());
            verify(f.inventory, never()).setStorageContents(any());
            verify(f.drops.getFirst()).remove();
        }
    }

    @Test void variantHookFailureRollsVisualBackAndRemovesProvisionalRefund() {
        try (var f = new Fixture(CHAIR, "minecraft:blue_carpet", 3)) {
            f.load("red");
            when(f.furniture.setVariant("cushion/blue", true)).thenAnswer(call -> {
                when(f.plugin.isEnabled()).thenReturn(false);
                return true;
            });
            f.use();
            assertEquals("red", f.controller.carpet());
            assertEquals(3, f.slots[0].getAmount());
            verify(f.furniture).setVariant("cushion/red", true);
            verify(f.inventory, never()).setStorageContents(any());
            verify(f.drops.getFirst()).remove();
        }
    }

    @Test void survivalBreakRefundsCarpetExactlyOnceAndKeepsBaseChairAsSource() {
        try (var f = new Fixture(CHAIR, "minecraft:stone", 1)) {
            f.load("red");
            f.controller.preRemove(f.actor);
            f.controller.preRemove(f.actor);
            assertEquals("empty", f.controller.carpet());
            assertEquals(1, f.drops.size());
            assertEquals("minecraft:red_carpet", f.drops.getFirst().getItemStack().toString());
            verify(f.furniture, never()).setSourceItem(any());
        }
    }

    @Test void creativeBreakDoesNotRefundCarpet() {
        try (var f = new Fixture(CHAIR, "minecraft:stone", 1)) {
            f.load("red");
            when(f.actor.canInstabuild()).thenReturn(true);
            f.controller.preRemove(f.actor);
            assertEquals("empty", f.controller.carpet());
            assertTrue(f.drops.isEmpty());
        }
    }

    @Test void externalRemovalStillReturnsInstalledCarpetOnce() {
        try (var f = new Fixture(CHAIR, "minecraft:stone", 1)) {
            f.load("red");
            f.controller.preRemove(null);
            assertEquals(1, f.drops.size());
            assertTrue(f.controller.blocksRemoval());
        }
    }

    @Test void cancelledBreakRefundArchivesStateAndLetsRealCraftEngineDestructionComplete() throws Exception {
        try (var f = new Fixture(CHAIR, "minecraft:stone", 1)) {
            f.load("red");
            f.acceptDrops = false;
            NativeRemoval nativeRemoval = f.nativeRemoval();
            boolean[] baseLootCanContinue = {false};
            assertDoesNotThrow(() -> { f.furniture.destroy(f.actor); baseLootCanContinue[0] = true; });
            assertEquals("red", f.controller.carpet());
            assertTrue(baseLootCanContinue[0]);
            assertTrue(f.controller.blocksRemoval());
            CompoundTag recovery = f.recoveries().getFirst();
            assertEquals(f.save().get(WoodenSeatController.SAVE_KEY), recovery.get(WoodenSeatController.SAVE_KEY));
            assertEquals("carpet refund was cancelled or changed", recovery.getString("recovery_reason"));
            nativeRemoval.assertDestroyed();
            f.controller.preRemove(f.actor);
            assertEquals(1, f.drops.size());
            assertEquals(1, f.recoveries().size());
        }
    }

    @Test void modifiedBreakRefundIsRemovedAndOnlyOriginalCushionIsArchived() throws Exception {
        try (var f = new Fixture(CHAIR, "minecraft:stone", 1)) {
            f.load("red");
            f.substituteDrop = stack("minecraft:diamond", 64);
            f.nativeRemoval();
            assertDoesNotThrow(() -> f.furniture.destroy(f.actor));
            verify(f.drops.getFirst()).remove();
            assertEquals(f.save().get(WoodenSeatController.SAVE_KEY), f.recoveries().getFirst().get(WoodenSeatController.SAVE_KEY));
        }
    }

    @Test void recoveryWriteFailureIsSevereButCannotAbortNativeDestructionOrRetryRefund() throws Exception {
        try (var f = new Fixture(CHAIR, "minecraft:stone", 1)) {
            f.load("red");
            f.acceptDrops = false;
            Files.createDirectories(f.folder);
            Files.writeString(f.folder.resolve("recovery"), "not a directory");
            NativeRemoval nativeRemoval = f.nativeRemoval();
            assertDoesNotThrow(() -> f.furniture.destroy(f.actor));
            nativeRemoval.assertDestroyed();
            verify(f.logger).log(eq(java.util.logging.Level.SEVERE), startsWith("DECO SEAT RECOVERY WRITE FAILED; manual state="), any(Throwable.class));
            f.controller.preRemove(f.actor);
            assertEquals(1, f.drops.size());
        }
    }

    @Test void realCraftEngineDestroyStillTearsDownWhenAnyControllerThrows() throws Exception {
        try (var f = new Fixture(CHAIR, "minecraft:stone", 1)) {
            NativeRemoval nativeRemoval = f.nativeRemoval();
            doThrow(new IllegalStateException("cannot veto here")).when(nativeRemoval.lifecycle).preRemove(f.actor);
            assertThrows(IllegalStateException.class, () -> f.furniture.destroy(f.actor));
            nativeRemoval.assertDestroyed();
        }
    }

    @Test void restartRoundTripRestoresTheSameCushionAndOpaqueSaveEnvelope() throws Exception {
        byte[] bytes;
        CompoundTag before;
        try (var f = new Fixture(CHAIR, "minecraft:red_carpet", 3)) {
            f.use();
            before = f.save();
            bytes = FurniturePersistentData.of(before).toBytes();
        }
        try (var f = new Fixture(CHAIR, "minecraft:stone", 1)) {
            f.controller.loadCustomData(FurniturePersistentData.fromBytes(bytes).copyTag());
            f.controller.onLoad();
            assertEquals("red", f.controller.carpet());
            verify(f.furniture).setVariant("cushion/red", true);
            assertEquals(before, f.save());
            assertFalse(f.controller.blocksRemoval());
        }
    }

    @Test void freshPlacementUsesEmptyAppearanceWithoutCreatingRefunds() {
        try (var f = new Fixture(STOOL, "minecraft:stone", 1)) {
            f.controller.loadCustomData(new CompoundTag());
            f.controller.onLoad();
            f.controller.onPlace(f.actor);
            assertEquals("empty", f.controller.carpet());
            verify(f.furniture, times(2)).setVariant("ground", true);
            assertTrue(f.drops.isEmpty());
        }
    }

    @Test void repeatedLoadOfAlreadyActiveVariantIsSuccessfulWithoutReapplying() {
        try (var f = new Fixture(CHAIR, "minecraft:stone", 1)) {
            f.load("red");
            FurnitureVariant active = new FurnitureVariant("cushion/red", null, List.of(), List.of(),
                    () -> null, new org.joml.Vector3f(), null);
            when(f.furniture.currentVariant()).thenReturn(active);
            when(f.furniture.setVariant(anyString())).thenReturn(false);
            assertDoesNotThrow(f.controller::onLoad);
            assertDoesNotThrow(() -> f.controller.onPlace(f.actor));
            verify(f.furniture, never()).setVariant(anyString());
            verify(f.furniture, never()).setVariant(anyString(), anyBoolean());
        }
    }

    @Test void restoringSavedCushionUsesForceForItsUnchangedPhysicalGeometry() {
        try (var f = new Fixture(CHAIR, "minecraft:stone", 1)) {
            f.load("blue");
            when(f.furniture.setVariant(anyString())).thenReturn(false);
            assertDoesNotThrow(f.controller::onLoad);
            verify(f.furniture).setVariant("cushion/blue", true);
            verify(f.furniture, never()).setVariant(anyString());
        }
    }

    @Test void applyingCushionUsesForceWhenNativePlacementCollisionWouldRejectIt() {
        try (var f = new Fixture(CHAIR, "minecraft:blue_carpet", 3)) {
            when(f.furniture.setVariant(anyString())).thenReturn(false);
            f.use();
            assertEquals("blue", f.controller.carpet());
            assertEquals(2, f.slots[0].getAmount());
            verify(f.furniture).setVariant("cushion/blue", true);
            verify(f.furniture, never()).setVariant(anyString());
        }
    }

    @Test void approvedNativeBreakDropSuppressionAlsoSuppressesTheSeparateCarpet() {
        try (var f = new Fixture(CHAIR, "minecraft:stone", 1)) {
            f.load("red");
            FurnitureBreakEvent event = mock(FurnitureBreakEvent.class);
            when(event.player()).thenReturn(f.player);
            when(event.dropItems()).thenReturn(false);
            f.controller.acceptPlayerBreak(event);
            f.controller.preRemove(f.actor);
            assertEquals("empty", f.controller.carpet());
            assertTrue(f.drops.isEmpty());
        }
    }

    @Test void lateCancelledBreakCannotSuppressLaterExternalRemoval() {
        try (var f = new Fixture(CHAIR, "minecraft:stone", 1)) {
            f.load("red");
            FurnitureBreakEvent event = mock(FurnitureBreakEvent.class);
            when(event.player()).thenReturn(f.player);
            f.controller.acceptPlayerBreak(event);
            when(event.isCancelled()).thenReturn(true);
            f.controller.preRemove(f.actor);
            assertEquals(1, f.drops.size());
        }
    }

    @Test void expiredBreakSuppressionCannotAffectNextTickRemoval() {
        try (var f = new Fixture(CHAIR, "minecraft:stone", 1)) {
            f.load("red");
            FurnitureBreakEvent event = mock(FurnitureBreakEvent.class);
            when(event.player()).thenReturn(f.player);
            f.controller.acceptPlayerBreak(event);
            when(f.world.getGameTime()).thenReturn(1L);
            f.controller.preRemove(f.actor);
            assertEquals(1, f.drops.size());
        }
    }

    @Test void unreadableRawTagBlocksUseAndIsArchivedWhenNativeRemovalCannotBeCancelled() throws Exception {
        try (var f = new Fixture(CHAIR, "minecraft:red_carpet", 3)) {
            CompoundTag invalid = new CompoundTag();
            invalid.putString(WoodenSeatController.SAVE_KEY, "opaque damaged seat data");
            f.controller.loadCustomData(invalid);
            assertTrue(f.controller.blocksRemoval());
            assertEquals(InteractionResult.FAIL, f.use());
            NativeRemoval nativeRemoval = f.nativeRemoval();
            assertDoesNotThrow(() -> f.furniture.destroy(f.actor));
            assertEquals(invalid, f.save());
            assertEquals(invalid.get(WoodenSeatController.SAVE_KEY), f.recoveries().getFirst().get(WoodenSeatController.SAVE_KEY));
            nativeRemoval.assertDestroyed();
            f.controller.onLoad();
            verify(f.furniture, never()).setVariant(anyString());
        }
    }

    @Test void unknownColorAndOtherSeatAndFutureSchemaArePreservedInsteadOfReset() {
        for (String problem : new String[]{"color", "seat", "future", "foreign"}) {
            try (var f = new Fixture(CHAIR, "minecraft:red_carpet", 3)) {
                CompoundTag invalid = saved(problem.equals("seat") ? STOOL : CHAIR,
                        problem.equals("color") ? "diamond" : "red");
                if (problem.equals("future")) invalid.getCompound(WoodenSeatController.SAVE_KEY).putInt("schema_version", 99);
                if (problem.equals("foreign")) invalid.getCompound(WoodenSeatController.SAVE_KEY).putString("source_commit", "other");
                f.controller.loadCustomData(invalid);
                assertTrue(f.controller.blocksRemoval(), problem);
                assertEquals(invalid, f.save(), problem);
            }
        }
    }

    @Test void legacyCushionedCookStoolCannotSilentlyLoseItsCarpet() throws Exception {
        try (var f = new Fixture(STOOL, "minecraft:red_carpet", 3)) {
            CompoundTag invalid = saved(STOOL, "red");
            f.controller.loadCustomData(invalid);
            assertTrue(f.controller.blocksRemoval());
            assertEquals(invalid, f.save());
            assertDoesNotThrow(() -> f.controller.preRemove(f.actor));
            assertEquals(invalid.get(WoodenSeatController.SAVE_KEY), f.recoveries().getFirst().get(WoodenSeatController.SAVE_KEY));
        }
    }

    private static CompoundTag saved(String id, String color) {
        CompoundTag root = new CompoundTag(), payload = new CompoundTag();
        payload.putString("seat", id);
        payload.putString("carpet", color);
        root.put(WoodenSeatController.SAVE_KEY, SavedState.envelope(
                SavedState.namespace(WoodenSeatController.SAVE_KEY), 1, SavedState.SERIES_SOURCE, payload));
        return root;
    }

    private final class Fixture implements AutoCloseable {
        final BukkitFurniture furniture = mock(BukkitFurniture.class);
        final DecoPlugin plugin = mock(DecoPlugin.class);
        final Player player = mock(Player.class);
        final net.momirealms.craftengine.core.entity.player.Player actor = mock(net.momirealms.craftengine.core.entity.player.Player.class);
        final World world = mock(World.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final PluginManager events = mock(PluginManager.class);
        final Logger logger = mock(Logger.class);
        final FurnitureHitBox hitbox = mock(FurnitureHitBox.class);
        final InteractEntityContext context = mock(InteractEntityContext.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<Items> items = mockStatic(Items.class);
        final ArrayList<Item> drops = new ArrayList<>();
        final WorldPosition position;
        final WoodenSeatController controller;
        final Path folder = dataFolder.resolve(UUID.randomUUID().toString());
        ItemStack[] slots;
        int heldSlot;
        boolean acceptDrops = true;
        ItemStack substituteDrop;
        Runnable dropHook = () -> {};

        Fixture(String id, String held, int count) {
            when(furniture.id()).thenReturn(Key.of(id));
            when(furniture.isValid()).thenReturn(true);
            when(furniture.uuid()).thenReturn(UUID.randomUUID());
            var ceWorld = mock(net.momirealms.craftengine.core.world.World.class);
            when(ceWorld.platformWorld()).thenReturn(world);
            position = new WorldPosition(ceWorld, 0, 64, 0);
            when(furniture.position()).thenReturn(position);
            var definition = mock(FurnitureDefinition.class);
            when(furniture.config()).thenReturn(definition);
            Map<String, FurnitureVariant> variants = new HashMap<>();
            variants.put("ground", null);
            for (String color : WoodenSeats.COLORS) variants.put("cushion/" + color, null);
            when(definition.variants()).thenReturn(variants);
            when(furniture.setVariant(anyString())).thenReturn(false);
            when(furniture.setVariant(anyString(), eq(true))).thenReturn(true);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getDataFolder()).thenReturn(folder.toFile());
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(plugin.mayUse(any(Player.class), any(Location.class))).thenReturn(true);
            doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                    .when(plugin).attempt(eq(player), any(Runnable.class));
            when(context.getHand()).thenReturn(InteractionHand.MAIN_HAND);
            when(context.getPlayer()).thenReturn(actor);
            when(actor.platformPlayer()).thenReturn(player);
            when(hitbox.seats()).thenReturn(new Seat[0]);
            when(player.getInventory()).thenReturn(inventory);
            when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.isOnline()).thenReturn(true);
            when(inventory.getHolder()).thenReturn(player);
            when(inventory.getMaxStackSize()).thenReturn(64);
            slots = new ItemStack[]{stack(held, count), stack("minecraft:stone", 1)};
            when(inventory.getStorageContents()).thenAnswer(call -> slots.clone());
            when(inventory.getHeldItemSlot()).thenAnswer(call -> heldSlot);
            when(inventory.getItemInMainHand()).thenAnswer(call -> slots[heldSlot]);
            doAnswer(call -> { slots = call.getArgument(0); return null; }).when(inventory).setStorageContents(any());
            bukkit.when(Bukkit::getPluginManager).thenReturn(events);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Player.class))).thenReturn(true);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
            items.when(() -> Items.id(any(ItemStack.class))).thenAnswer(call -> call.getArgument(0).toString());
            items.when(() -> Items.create(anyString())).thenAnswer(call -> stack(call.getArgument(0), 1));
            when(world.dropItem(any(Location.class), any(ItemStack.class), any(Consumer.class))).thenAnswer(call -> {
                Item drop = mock(Item.class);
                boolean[] valid = {acceptDrops};
                ItemStack value = substituteDrop == null ? call.getArgument(1) : substituteDrop;
                when(drop.isValid()).thenAnswer(ignored -> valid[0]);
                when(drop.getItemStack()).thenReturn(value);
                doAnswer(ignored -> { valid[0] = false; return null; }).when(drop).remove();
                drops.add(drop);
                ((Consumer<Item>) call.getArgument(2)).accept(drop);
                dropHook.run();
                return drop;
            });
            controller = new WoodenSeatController(furniture, plugin);
        }

        InteractionResult use() { return controller.useOnFurniture(hitbox, context); }
        void load(String color) { controller.loadCustomData(saved(furniture.id().asString(), color)); }
        CompoundTag save() { CompoundTag root = new CompoundTag(); controller.saveCustomData(root); return root; }
        List<CompoundTag> recoveries() throws IOException {
            if (!Files.isDirectory(folder.resolve("recovery"))) return List.of();
            List<CompoundTag> saved = new ArrayList<>();
            try (var files = Files.list(folder.resolve("recovery"))) {
                for (Path file : files.toList()) saved.add(FurniturePersistentData.fromBytes(Files.readAllBytes(file)).copyTag());
            }
            return saved;
        }
        NativeRemoval nativeRemoval() throws ReflectiveOperationException {
            ItemDisplay meta = mock(ItemDisplay.class);
            Collider collider = mock(Collider.class);
            FurnitureSnapshotState snapshot = mock(FurnitureSnapshotState.class);
            FurnitureController lifecycle = mock(FurnitureController.class);
            when(snapshot.colliders()).thenReturn(List.of(collider));
            doAnswer(call -> { controller.preRemove(call.getArgument(0)); return null; }).when(lifecycle).preRemove(any());
            setField(Furniture.class, "controller", furniture, lifecycle);
            setField(Furniture.class, "snapshot", furniture, snapshot);
            setField(BukkitFurniture.class, "metaEntity", furniture, new WeakReference<>(meta));
            doCallRealMethod().when(furniture).destroy(any());
            doCallRealMethod().when(furniture).destroySeats();
            return new NativeRemoval(furniture, meta, collider, snapshot, lifecycle);
        }
        void assertUnchanged(String color, int count) {
            assertEquals(color, controller.carpet());
            assertEquals(count, slots[0].getAmount());
            verify(furniture, never()).setVariant(anyString(), anyBoolean());
            verify(inventory, never()).setStorageContents(any());
            verify(furniture, never()).setUnsaved();
        }
        @Override public void close() { items.close(); bukkit.close(); }
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

    private static ItemStack stack(String id, int count) {
        int[] amount = {count};
        return mock(ItemStack.class, call -> switch (call.getMethod().getName()) {
            case "getAmount" -> amount[0];
            case "isEmpty" -> amount[0] == 0;
            case "toString" -> id;
            case "clone" -> stack(id, amount[0]);
            case "isSimilar" -> call.getArgument(0) != null && id.equals(call.getArgument(0).toString());
            case "equals" -> call.getArgument(0) instanceof ItemStack other
                    && id.equals(other.toString()) && amount[0] == other.getAmount();
            case "subtract" -> { amount[0] -= (int) call.getArgument(0); yield call.getMock(); }
            default -> throw new AssertionError("Unexpected stack method: " + call.getMethod());
        });
    }
}
