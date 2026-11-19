package net.kaleidoscope.deco;

import net.kaleidoscope.series.InventoryTradeEvent;
import net.kaleidoscope.series.ItemState;
import net.kaleidoscope.series.Items;
import net.kaleidoscope.series.SavedState;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData;
import net.momirealms.craftengine.core.entity.furniture.FurnitureVariant;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DecoControllerTest {
    private static final NamespacedKey ITEM_STATE = new NamespacedKey("kaleidoscopedecoplugin", "deco_state_v1");
    private static final String SAVE_KEY = "kaleidoscope_deco:state_v1";
    private static final String SOFA = "kaleidoscope_deco:kaleidoscope_bar/sofa";
    private static final String RED = SOFA.replace(':', '/') + "/style/red/single";
    private static final String BOARD = "kaleidoscope_deco:kaleidoscope_bar/blackboard/9";
    private static final String EMPTY = "kaleidoscope_deco:kaleidoscope_bar/blackboard/0";

    @Test void restartRestoresSavedSofaStyleAndFutureDropWithoutAnItemPlacementHook() throws Exception {
        byte[] persisted;
        String redLeft = SOFA.replace(':', '/') + "/style/red/left";
        try (var fixture = new Fixture(SOFA, "minecraft:red_dye", 2)) {
            when(fixture.plugin.styles()).thenReturn(Set.of(RED, redLeft));
            fixture.furniture.config().variants().put(redLeft, null);
            fixture.controller.interact(fixture.player);
            fixture.slots[1] = stack("kaleidoscope_deco:state_tool", 1);
            fixture.heldSlot = 1;
            fixture.controller.interact(fixture.player);
            verify(fixture.furniture, times(2)).setUnsaved();

            CompoundTag data = new CompoundTag(), entity = new CompoundTag();
            fixture.controller.saveCustomData(data);
            entity.put("data", data);
            persisted = FurniturePersistentData.of(entity).toBytes();
        }

        try (var restored = new Fixture(SOFA, "minecraft:red_dye", 1)) {
            var source = mock(net.momirealms.craftengine.core.item.Item.class);
            doReturn(source).when(restored.controller).sourceItem(SOFA, "red", "left");
            when(restored.plugin.reachable(SOFA, SOFA)).thenReturn(true);
            CompoundTag data = FurniturePersistentData.fromBytes(persisted).copyTag().getCompound("data");

            restored.controller.loadCustomData(data);
            restored.controller.onLoad();

            assertFalse(restored.controller.blocked());
            assertEquals("red", restored.saved("color"));
            assertEquals("left", restored.saved("mode"));
            verify(restored.furniture).setVariant(redLeft);
            verify(restored.controller).sourceItem(SOFA, "red", "left");
            verify(restored.furniture).setSourceItem(source);
            verify(restored.controller, never()).loadCustomDataFromItem(any());
            CompoundTag savedAgain = new CompoundTag();
            restored.controller.saveCustomData(savedAgain);
            assertEquals(data, savedAgain);
        }
    }

    @Test void placedItemRestoresStyleAndFutureDropAfterCraftEngineLoadCallbacks() {
        try (var fixture = new Fixture(SOFA, "minecraft:red_dye", 2)) {
            CompoundTag payload = new CompoundTag();
            payload.putString("state", SOFA);
            payload.putString("color", "red");
            payload.putString("mode", "left");
            CompoundTag root = new CompoundTag();
            root.put(SAVE_KEY, SavedState.envelope("kaleidoscope_deco:state", 1, SavedState.SERIES_SOURCE, payload));
            ItemStack packed = packedItem(root);
            var held = mock(net.momirealms.craftengine.core.item.Item.class);
            doReturn(packed).when(fixture.controller).bukkitStack(held);
            when(fixture.plugin.reachable(SOFA, SOFA)).thenReturn(true);
            var restoredSource = mock(net.momirealms.craftengine.core.item.Item.class);
            doReturn(restoredSource).when(fixture.controller).sourceItem(SOFA, "red", "left");

            // The real CE placement path runs both callbacks before restoring the held item's PDC.
            fixture.controller.loadCustomData(new CompoundTag());
            fixture.controller.onLoad();
            fixture.controller.onPlace(null);
            clearInvocations(fixture.furniture, fixture.controller);
            fixture.controller.loadCustomDataFromItem(held);

            assertFalse(fixture.controller.blocked());
            assertEquals("red", fixture.saved("color"));
            assertEquals("left", fixture.saved("mode"));
            verify(fixture.furniture).setVariant(SOFA.replace(':', '/') + "/style/red/left");
            verify(fixture.controller).sourceItem(SOFA, "red", "left");
            verify(fixture.furniture).setSourceItem(restoredSource);
        }
    }

    @Test void freshPlacedItemDoesNotReplayDefaultCallbacksWhenItHasNoStoredState() {
        try (var fixture = new Fixture(SOFA, "minecraft:red_dye", 2)) {
            ItemStack plain = mock(ItemStack.class);
            ItemMeta meta = mock(ItemMeta.class);
            when(plain.getItemMeta()).thenReturn(meta);
            when(plain.serializeAsBytes()).thenReturn(new byte[] {1});
            when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
            var held = mock(net.momirealms.craftengine.core.item.Item.class);
            doReturn(plain).when(fixture.controller).bukkitStack(held);
            fixture.controller.onLoad();
            fixture.controller.onPlace(null);
            clearInvocations(fixture.furniture, fixture.controller);

            fixture.controller.loadCustomDataFromItem(held);

            assertEquals("none", fixture.saved("color"));
            assertEquals("single", fixture.saved("mode"));
            verify(fixture.furniture, never()).setVariant(anyString());
            verify(fixture.furniture, never()).setSourceItem(any());
        }
    }

    @Test void rejectedSofaVariantDoesNotTakeDyeOrSaveAnUnappliedStyle() {
        try (var fixture = new Fixture(SOFA, "minecraft:red_dye", 2)) {
            when(fixture.furniture.setVariant(RED)).thenReturn(false);
            fixture.controller.interact(fixture.player);
            assertEquals("none", fixture.saved("color"));
            verify(fixture.inventory, never()).setStorageContents(any());
            verify(fixture.furniture, never()).setSourceItem(any());
            verify(fixture.furniture, never()).setUnsaved();
        }
    }

    @Test void canceledSofaExchangeLeavesStateAndInventoryUntouched() {
        try (var fixture = new Fixture(SOFA, "minecraft:red_dye", 2)) {
            doAnswer(call -> {
                ((InventoryTradeEvent) call.getArgument(0)).setCancelled(true);
                return null;
            }).when(fixture.events).callEvent(any(InventoryTradeEvent.class));
            fixture.controller.interact(fixture.player);
            assertEquals("none", fixture.saved("color"));
            verify(fixture.furniture, never()).setVariant(anyString());
            verify(fixture.furniture, never()).setSourceItem(any());
            verify(fixture.inventory, never()).setStorageContents(any());
        }
    }

    @Test void sourceEncodingFailureCannotTakeDyeAndDoesNotLeaveFurnitureLocked() {
        try (var fixture = new Fixture(SOFA, "minecraft:red_dye", 2)) {
            doThrow(new IllegalStateException("invalid source"))
                    .when(fixture.controller).sourceItem(SOFA, "red", "single");
            assertThrows(IllegalStateException.class, () -> fixture.controller.interact(fixture.player));
            assertEquals("none", fixture.saved("color"));
            verify(fixture.events, never()).callEvent(any(InventoryTradeEvent.class));
            verify(fixture.inventory, never()).setStorageContents(any());
            doReturn(fixture.preparedSource).when(fixture.controller).sourceItem(SOFA, "red", "single");
            fixture.controller.interact(fixture.player);
            assertEquals("red", fixture.saved("color"));
            assertEquals(1, fixture.slots[0].getAmount());
        }
    }

    @Test void applyingSameColorAgainDoesNotConsumeAnotherDye() {
        try (var fixture = new Fixture(SOFA, "minecraft:red_dye", 2)) {
            fixture.controller.interact(fixture.player);
            fixture.controller.interact(fixture.player);
            assertEquals("red", fixture.saved("color"));
            assertEquals(1, fixture.slots[0].getAmount());
            verify(fixture.events, times(1)).callEvent(any(InventoryTradeEvent.class));
            verify(fixture.furniture, times(1)).setVariant(RED);
        }
    }

    @Test void reentrantDyeEventCommitsExactlyOneStyleAndOnePayment() {
        try (var fixture = new Fixture(SOFA, "minecraft:red_dye", 2)) {
            doAnswer(call -> {
                fixture.controller.interact(fixture.player);
                return null;
            }).when(fixture.events).callEvent(any(InventoryTradeEvent.class));
            fixture.controller.interact(fixture.player);
            assertEquals("red", fixture.saved("color"));
            assertEquals("single", fixture.saved("mode"));
            assertEquals(1, fixture.slots[0].getAmount());
            verify(fixture.events, times(1)).callEvent(any(InventoryTradeEvent.class));
            verify(fixture.furniture, times(1)).setVariant(RED);
            verify(fixture.furniture, times(1)).setSourceItem(fixture.preparedSource);
            verify(fixture.controller).sourceItem(SOFA, "red", "single");
        }
    }

    @Test void switchingHotbarDuringDyeEventDoesNotApplyOrConsume() {
        try (var fixture = new Fixture(SOFA, "minecraft:red_dye", 2)) {
            doAnswer(call -> { fixture.heldSlot = 1; return null; })
                    .when(fixture.events).callEvent(any(InventoryTradeEvent.class));
            fixture.controller.interact(fixture.player);
            assertEquals("none", fixture.saved("color"));
            verify(fixture.furniture, never()).setVariant(anyString());
            verify(fixture.inventory, never()).setStorageContents(any());
        }
    }

    @Test void furnitureEntryPointRejectsNestedUseDuringDyeEvent() {
        try (var fixture = new Fixture(SOFA, "minecraft:red_dye", 2)) {
            var context = mock(InteractEntityContext.class);
            var actor = mock(net.momirealms.craftengine.core.entity.player.Player.class);
            var hitbox = mock(FurnitureHitBox.class);
            when(context.getHand()).thenReturn(InteractionHand.MAIN_HAND);
            when(context.getPlayer()).thenReturn(actor);
            when(actor.platformPlayer()).thenReturn(fixture.player);
            doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                    .when(fixture.plugin).attempt(eq(fixture.player), any(Runnable.class));
            doAnswer(call -> {
                assertTrue(fixture.controller.isInteracting());
                assertEquals(InteractionResult.FAIL, fixture.controller.useOnFurniture(hitbox, context));
                return null;
            }).when(fixture.events).callEvent(any(InventoryTradeEvent.class));
            assertEquals(InteractionResult.SUCCESS_AND_CANCEL, fixture.controller.useOnFurniture(hitbox, context));
            assertFalse(fixture.controller.isInteracting());
            assertEquals("red", fixture.saved("color"));
            assertEquals(1, fixture.slots[0].getAmount());
            verify(fixture.events, times(1)).callEvent(any(InventoryTradeEvent.class));
        }
    }

    @Test void furnitureRemovedByDyeEventDoesNotApplyOrConsume() {
        try (var fixture = new Fixture(SOFA, "minecraft:red_dye", 2)) {
            doAnswer(call -> { when(fixture.furniture.isValid()).thenReturn(false); return null; })
                    .when(fixture.events).callEvent(any(InventoryTradeEvent.class));
            fixture.controller.interact(fixture.player);
            assertEquals("none", fixture.saved("color"));
            verify(fixture.furniture, never()).setVariant(anyString());
            verify(fixture.inventory, never()).setStorageContents(any());
        }
    }

    @Test void changingHeldToolDuringDropHookRemovesDropAndKeepsDecoratedBoard() {
        try (var fixture = new Fixture(BOARD, "minecraft:shears", 1)) {
            var rule = new DecoRule(List.of("minecraft:shears"), false, -1, EMPTY, List.of(), false,
                    false, List.of("minecraft:red_tulip"));
            when(fixture.plugin.entry(BOARD)).thenReturn(new DecoPlugin.Entry(BOARD, "test", "test", List.of(rule)));
            var reward = stack("minecraft:red_tulip", 1);
            fixture.items.when(() -> Items.create("minecraft:red_tulip")).thenReturn(reward);
            Item drop = mock(Item.class);
            when(drop.isValid()).thenReturn(true);
            when(drop.getItemStack()).thenReturn(reward);
            doAnswer(call -> {
                fixture.heldSlot = 1;
                return drop;
            }).when(fixture.world).dropItem(any(Location.class), any(ItemStack.class), any(Consumer.class));
            fixture.controller.interact(fixture.player);
            assertEquals(BOARD, fixture.saved("state"));
            verify(drop).remove();
            verify(fixture.furniture, never()).setVariant(anyString());
            verify(fixture.furniture, never()).setSourceItem(any());
        }
    }

    @Test void rejectedHarvestVariantRemovesDropAndKeepsDecoratedBoard() {
        try (var fixture = new Fixture(BOARD, "minecraft:shears", 1)) {
            var drop = fixture.harvestDrop();
            when(fixture.furniture.setVariant(EMPTY.replace(':', '/'))).thenReturn(false);
            fixture.controller.interact(fixture.player);
            assertEquals(BOARD, fixture.saved("state"));
            verify(drop).remove();
            verify(fixture.furniture, never()).setSourceItem(any());
            verify(fixture.inventory, never()).setItemInMainHand(any());
        }
    }

    @Test void successfulHarvestChangesStateAndKeepsExactlyOneDrop() {
        try (var fixture = new Fixture(BOARD, "minecraft:shears", 1)) {
            var drop = fixture.harvestDrop();
            fixture.controller.interact(fixture.player);
            assertEquals(EMPTY, fixture.saved("state"));
            verify(drop, never()).remove();
            verify(fixture.world, times(1)).dropItem(any(Location.class), any(ItemStack.class), any(Consumer.class));
            verify(fixture.furniture).setSourceItem(fixture.preparedSource);
            verify(fixture.furniture).setUnsaved();
            verify(fixture.inventory, never()).setItemInMainHand(any());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Furniture furniture = mock(Furniture.class);
        final DecoPlugin plugin = mock(DecoPlugin.class);
        final Player player = mock(Player.class);
        final World world = mock(World.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final PluginManager events = mock(PluginManager.class);
        final net.momirealms.craftengine.core.item.Item preparedSource =
                mock(net.momirealms.craftengine.core.item.Item.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<Items> items = mockStatic(Items.class);
        final DecoController controller;
        ItemStack[] slots;
        int heldSlot;

        Fixture(String state, String held, int count) {
            when(furniture.id()).thenReturn(Key.of(state));
            when(furniture.isValid()).thenReturn(true);
            var ceWorld = mock(net.momirealms.craftengine.core.world.World.class);
            when(ceWorld.platformWorld()).thenReturn(world);
            when(furniture.position()).thenReturn(new WorldPosition(ceWorld, 0, 64, 0));
            var definition = mock(FurnitureDefinition.class);
            when(furniture.config()).thenReturn(definition);
            Map<String, FurnitureVariant> variants = new HashMap<>();
            variants.put(RED, null);
            variants.put(EMPTY.replace(':', '/'), null);
            when(definition.variants()).thenReturn(variants);
            when(furniture.setVariant(anyString())).thenReturn(true);
            when(plugin.styles()).thenReturn(Set.of(RED));
            when(plugin.stateKey()).thenReturn(ITEM_STATE);
            when(plugin.mayUse(any(Player.class), any(Location.class))).thenReturn(true);
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
            controller = spy(new DecoController(furniture, plugin));
            doReturn(preparedSource).when(controller).sourceItem(anyString(), anyString(), anyString());
        }

        String saved(String field) {
            CompoundTag root = new CompoundTag();
            controller.saveCustomData(root);
            return SavedState.payload(root.getCompound("kaleidoscope_deco:state_v1"),
                    "kaleidoscope_deco:state", SavedState.SERIES_SOURCE, 1, null).getString(field);
        }

        Item harvestDrop() {
            var rule = new DecoRule(List.of("minecraft:shears"), false, -1, EMPTY, List.of(), false,
                    false, List.of("minecraft:red_tulip"));
            when(plugin.entry(BOARD)).thenReturn(new DecoPlugin.Entry(BOARD, "test", "test", List.of(rule)));
            var reward = stack("minecraft:red_tulip", 1);
            items.when(() -> Items.create("minecraft:red_tulip")).thenReturn(reward);
            Item drop = mock(Item.class);
            when(drop.isValid()).thenReturn(true);
            when(drop.getItemStack()).thenReturn(reward);
            when(world.dropItem(any(Location.class), any(ItemStack.class), any(Consumer.class))).thenReturn(drop);
            return drop;
        }

        @Override public void close() { items.close(); bukkit.close(); }
    }

    private static ItemStack packedItem(CompoundTag saved) {
        ItemStack original = mock(ItemStack.class), copy = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(original.clone()).thenReturn(copy);
        when(copy.getItemMeta()).thenReturn(meta);
        when(copy.serializeAsBytes()).thenReturn(new byte[] {1});
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        ItemState.write(original, ITEM_STATE, saved);
        var bytes = ArgumentCaptor.forClass(byte[].class);
        verify(pdc).set(eq(ITEM_STATE), eq(PersistentDataType.BYTE_ARRAY), bytes.capture());
        when(pdc.has(ITEM_STATE)).thenReturn(true);
        when(pdc.get(ITEM_STATE, PersistentDataType.BYTE_ARRAY)).thenReturn(bytes.getValue());
        return copy;
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
