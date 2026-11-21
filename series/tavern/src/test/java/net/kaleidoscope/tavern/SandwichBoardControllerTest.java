package net.kaleidoscope.tavern;

import net.kaleidoscope.series.ControllerIndex;
import net.kaleidoscope.series.InventoryTradeEvent;
import net.kaleidoscope.series.Items;
import net.kaleidoscope.series.SavedState;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData;
import net.momirealms.craftengine.core.entity.furniture.FurnitureVariant;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SandwichBoardControllerTest {
    @Test void loadBeforeNativeEntityInsertionDefersVariantRestoreUntilPlace() throws Exception {
        try (var f = new Fixture()) {
            f.variant = "ground";
            when(f.furniture.isValid()).thenReturn(false);
            assertDoesNotThrow(f.controller::onLoad);
            verify(f.furniture, never()).setVariant(anyString(), anyBoolean());
            verify(f.display, never()).rebuild();
            when(f.furniture.isValid()).thenReturn(true);
            f.controller.onPlace(null);
            assertEquals("poppy", f.variant);
            verify(f.furniture).setVariant("poppy", true);
            verify(f.display).rebuild();
        }
    }

    @Test void nativeGroundAliasAndPatternRestoreDoNotRepeatPlacementCollisionChecks() throws Exception {
        try (var f = new Fixture()) {
            // Actual CE can reject a non-forced variant while its existing hitbox occupies the same space.
            doReturn(false).when(f.furniture).setVariant(anyString());
            f.variant = "ground";
            f.controller.onLoad();
            assertEquals("poppy", f.variant);
            f.use(Material.ALLIUM);
            assertEquals("allium", f.variant);
            assertEquals("allium", f.controller.state().style());
            assertEquals(1, f.hand().getAmount());
            verify(f.furniture).setVariant("poppy", true);
            verify(f.furniture).setVariant("allium", true);
            verify(f.furniture, never()).setVariant(anyString());
        }
    }

    @Test void aVariantCallbackChangingTheHandRollsBackOnlyItsProvisionalPattern() throws Exception {
        try (var f = new Fixture()) {
            doAnswer(call -> { f.variant = "allium"; f.slots[0].setAmount(3); return true; }).when(f.furniture).setVariant("allium", true);
            var before = f.controller.state(); f.use(Material.ALLIUM);
            assertSame(before, f.controller.state()); assertEquals("poppy", f.variant);
            assertEquals(3, f.hand().getAmount()); verify(f.furniture, never()).setUnsaved();
            verify(f.furniture).setVariant("poppy", true);
        }
    }
    @Test void aFailedProvisionalRollbackIsReportedInsteadOfSilentlyClaimingSuccess() throws Exception {
        try (var f = new Fixture()) {
            doAnswer(call -> { f.variant = "allium"; f.slots[0].setAmount(3); return true; }).when(f.furniture).setVariant("allium", true);
            doReturn(false).when(f.furniture).setVariant("poppy", true);
            var before = f.controller.state();
            assertThrows(IllegalStateException.class, () -> f.use(Material.ALLIUM));
            assertSame(before, f.controller.state()); assertEquals(3, f.hand().getAmount()); verify(f.furniture, never()).setUnsaved();
        }
    }
    @Test void alreadySelectedVariantDoesNotFailTheSecondEditOrRecreateItsHitbox() throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.controller.edit(f.player, f.controller.state(), "one", "CENTER"));
            assertTrue(f.controller.edit(f.player, f.controller.state(), "two", "LEFT"));
            verify(f.furniture, never()).setVariant(anyString());
            verify(f.furniture, never()).setVariant(anyString(), anyBoolean());
        }
    }
    @Test void aRejectedPatternVariantDoesNotConsumeFlowersOrCommitState() throws Exception {
        try (var f = new Fixture()) {
            doReturn(false).when(f.furniture).setVariant("allium", true);
            var original = f.controller.state(); f.use(Material.ALLIUM);
            assertSame(original, f.controller.state()); assertEquals(2, f.hand().getAmount());
            verify(f.furniture, never()).setUnsaved();
        }
    }
    @Test void ceItemsUsingVanillaDyeOrFlowerMaterialsAreNotConsumedAsVanillaIngredients() throws Exception {
        try (var f = new Fixture()) {
            f.items.when(() -> Items.id(any())).thenReturn("another_plugin:custom_item");
            var original = f.controller.state(); f.use(Material.ALLIUM); f.use(Material.RED_DYE);
            assertSame(original, f.controller.state()); assertEquals(2, f.hand().getAmount());
            verify(f.controller, times(2)).openEditor(f.player, original);
        }
    }
    @Test void successfulEditMarksDirtyAndAStaleSecondEditorCannotOverwriteIt() throws Exception {
        try (var f = new Fixture()) {
            var original = f.controller.state();
            assertTrue(f.controller.edit(f.player, original, "今日菜单", "LEFT"));
            assertFalse(f.controller.edit(f.player, original, "stale", "RIGHT"));
            assertEquals("今日菜单", f.controller.state().text());
            verify(f.furniture).setUnsaved(); verify(f.display).rebuild();
        }
    }
    @Test void malformedDialogFieldsAreRejectedWithoutChangingStateOrThrowing() throws Exception {
        try (var f = new Fixture()) {
            var before = f.controller.state();
            assertFalse(f.controller.edit(f.player, before, null, "LEFT"));
            assertFalse(f.controller.edit(f.player, before, "text", null));
            assertFalse(f.controller.edit(f.player, before, "text", "forged"));
            assertFalse(f.controller.edit(f.player, before, "x".repeat(321), "CENTER"));
            assertSame(before, f.controller.state()); verify(f.furniture, never()).setUnsaved();
        }
    }
    @Test void unloadDisableDistanceProtectionAndOfflinePlayersInvalidateDelayedEdits() throws Exception {
        try (var f = new Fixture()) {
            var before = f.controller.state();
            f.controller.onUnload(); assertFalse(f.controller.edit(f.player, before, "x", "LEFT"));
            f.controller.onLoad(); when(f.plugin.isEnabled()).thenReturn(false); assertFalse(f.controller.edit(f.player, before, "x", "LEFT"));
            when(f.plugin.isEnabled()).thenReturn(true); when(f.plugin.mayUse(eq(f.player), any())).thenReturn(false);
            assertFalse(f.controller.edit(f.player, before, "x", "LEFT"));
            when(f.plugin.mayUse(eq(f.player), any())).thenReturn(true); when(f.player.isOnline()).thenReturn(false);
            assertFalse(f.controller.edit(f.player, before, "x", "LEFT"));
        }
    }
    @Test void aReloadedReplacementControllerInvalidatesTheOldEditor() throws Exception {
        try (var f = new Fixture()) {
            var original = f.controller.state();
            var replacement = mock(SandwichBoardController.class);
            var location = f.controller.location();
            when(replacement.location()).thenReturn(location); when(replacement.furniture()).thenReturn(f.furniture);
            f.plugin.boards.add(replacement);
            assertFalse(f.controller.edit(f.player, original, "stale", "LEFT"));
        }
    }
    @Test void dyeConsumesExactlyOneFromTheSelectedStackAndDoesNotTouchAnEarlierIdenticalStack() throws Exception {
        try (var f = new Fixture()) {
            f.selected = 5; f.slots[0] = new Stack(Material.RED_DYE, 3, "tag"); f.slots[5] = new Stack(Material.RED_DYE, 2, "tag");
            f.controller.interact(f.player);
            assertEquals("red", f.controller.state().color()); assertEquals(3, f.slots[0].getAmount()); assertEquals(1, f.slots[5].getAmount());
            verify(f.furniture).setUnsaved();
        }
    }
    @Test void aCancelledDyeTradeLeavesTheWholeInventoryAndBoardUntouched() throws Exception {
        try (var f = new Fixture()) {
            f.slots[0] = new Stack(Material.RED_DYE, 2, "tag"); f.event = e -> e.setCancelled(true);
            var before = f.controller.state(); f.controller.interact(f.player);
            assertSame(before, f.controller.state()); assertEquals(2, f.slots[0].getAmount()); verify(f.furniture, never()).setUnsaved();
        }
    }
    @Test void changingSelectedSlotComponentsQuantityModeOrPluginInCallbackAbortsTheCommit() throws Exception {
        for (int change = 0; change < 5; change++) try (var f = new Fixture()) {
            f.slots[0] = new Stack(Material.RED_DYE, 2, "tag");
            int kind = change;
            f.event = e -> { switch (kind) {
                case 0 -> f.selected = 1;
                case 1 -> f.slots[0] = new Stack(Material.RED_DYE, 2, "different");
                case 2 -> f.slots[0].setAmount(3);
                case 3 -> when(f.player.getGameMode()).thenReturn(GameMode.CREATIVE);
                default -> when(f.plugin.isEnabled()).thenReturn(false);
            } };
            var before = f.controller.state(); f.controller.interact(f.player);
            assertSame(before, f.controller.state()); verify(f.furniture, never()).setUnsaved();
        }
    }
    @Test void dyeGlowInkAndWaxAllPersistAndWaxBlocksEditingButAllowsFlowers() throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.controller.edit(f.player, f.controller.state(), "保留文本", "RIGHT"));
            f.use(Material.RED_DYE); f.use(Material.GLOW_INK_SAC); f.use(Material.INK_SAC); f.use(Material.GLOW_INK_SAC); f.use(Material.HONEYCOMB);
            var waxed = f.controller.state(); assertTrue(waxed.waxed()); assertTrue(waxed.glowing());
            assertFalse(f.controller.edit(f.player, waxed, "x", "LEFT"));
            f.use(Material.BLUE_DYE); assertSame(waxed, f.controller.state()); assertEquals(2, f.hand().getAmount());
            f.use(Material.ALLIUM);
            assertEquals(waxed.pattern("allium"), f.controller.state()); assertEquals(1, f.hand().getAmount());
        }
    }
    @Test void switchingBackToTheOriginallyPlacedStyleResetsTheFutureDropToThatStyle() throws Exception {
        try (var f = new Fixture()) {
            f.use(Material.ALLIUM); assertEquals("kaleidoscope_tavern:allium_sandwich_board", f.dropId);
            f.use(Material.POPPY); assertEquals("kaleidoscope_tavern:poppy_sandwich_board", f.dropId);
        }
    }
    @Test void creativeDyeChangesStateWithoutReducingTheSelectedStack() throws Exception {
        try (var f = new Fixture()) {
            when(f.player.getGameMode()).thenReturn(GameMode.CREATIVE); f.use(Material.RED_DYE);
            assertEquals("red", f.controller.state().color()); assertEquals(2, f.hand().getAmount());
        }
    }
    @Test void realByteRestartRestoresPatternTextDyeAlignmentWaxGlowAndFutureDrop() throws Exception {
        byte[] bytes;
        SandwichBoardState expected;
        try (var f = new Fixture()) {
            f.controller.edit(f.player, f.controller.state(), "菜单\n<red>literal", "RIGHT");
            f.use(Material.ALLIUM); f.use(Material.RED_DYE); f.use(Material.GLOW_INK_SAC); f.use(Material.HONEYCOMB);
            expected = f.controller.state(); CompoundTag saved = new CompoundTag(); f.controller.saveCustomData(saved);
            bytes = FurniturePersistentData.of(saved).toBytes();
        }
        try (var f = new Fixture()) {
            f.controller.loadCustomData(FurniturePersistentData.fromBytes(bytes).copyTag()); f.controller.onLoad();
            assertFalse(f.controller.blocked()); assertEquals(expected, f.controller.state());
            assertEquals(expected.itemId(), f.dropId); assertFalse(f.controller.edit(f.player, expected, "x", "LEFT"));
        }
    }
    @Test void aFutureOrCorruptSaveIsKeptOpaqueAndCannotBeOperated() throws Exception {
        try (var f = new Fixture()) {
            var future = SavedState.envelope(SavedState.namespace(SandwichBoardController.SAVE_KEY), 2, SavedState.SERIES_SOURCE, f.controller.state().save());
            var root = new CompoundTag(); root.put(SandwichBoardController.SAVE_KEY, future);
            f.controller.loadCustomData(root); assertTrue(f.controller.blocked());
            f.use(Material.RED_DYE); assertEquals("white", f.controller.state().color()); assertEquals(2, f.hand().getAmount());
            var after = new CompoundTag(); f.controller.saveCustomData(after); assertEquals(root, after);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final TavernPlugin plugin = mock(TavernPlugin.class);
        final Furniture furniture = mock(Furniture.class);
        final World world = mock(World.class);
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final SandwichBoardDisplay display = mock(SandwichBoardDisplay.class);
        final ItemStack[] slots = new ItemStack[41];
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<Items> items = mockStatic(Items.class);
        final PluginManager manager = mock(PluginManager.class);
        final SandwichBoardController controller;
        int selected;
        String variant = "ground";
        String dropId;
        Consumer<InventoryTradeEvent> event = e -> {};

        Fixture() throws Exception {
            Field boards = TavernPlugin.class.getDeclaredField("boards"); boards.setAccessible(true); boards.set(plugin, new ControllerIndex<>());
            when(plugin.isEnabled()).thenReturn(true); when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            when(plugin.mayUse(eq(player), any())).thenReturn(true);
            when(player.isOnline()).thenReturn(true); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getGameMode()).thenReturn(GameMode.SURVIVAL); when(player.getInventory()).thenReturn(inventory);
            when(inventory.getHeldItemSlot()).thenAnswer(c -> selected); when(inventory.getItemInMainHand()).thenAnswer(c -> hand());
            when(inventory.getContents()).thenAnswer(c -> slots.clone());
            doAnswer(c -> { slots[selected] = c.getArgument(0); return null; }).when(inventory).setItemInMainHand(nullable(ItemStack.class));
            when(furniture.id()).thenReturn(Key.of("kaleidoscope_tavern:poppy_sandwich_board"));
            when(furniture.uuid()).thenReturn(UUID.randomUUID()); when(furniture.isValid()).thenReturn(true);
            when(furniture.currentVariant()).thenAnswer(c -> new FurnitureVariant(variant, null, java.util.List.of(), java.util.List.of(), () -> null, new org.joml.Vector3f(), null));
            when(furniture.setVariant(anyString())).thenAnswer(c -> { String name = c.getArgument(0); if (name.equals(variant)) return false; variant = name; return true; });
            when(furniture.setVariant(anyString(), eq(true))).thenAnswer(c -> { variant = c.getArgument(0); return true; });
            var ceWorld = mock(net.momirealms.craftengine.core.world.World.class); when(ceWorld.platformWorld()).thenReturn(world);
            when(furniture.position()).thenReturn(new WorldPosition(ceWorld, .5, 64, .5));
            when(world.getUID()).thenReturn(UUID.randomUUID()); when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true); bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            doAnswer(c -> { if (c.getArgument(0) instanceof InventoryTradeEvent trade) event.accept(trade); return null; }).when(manager).callEvent(any());
            items.when(() -> Items.create(anyString())).thenAnswer(c -> { dropId = c.getArgument(0); return new Stack(Material.PAPER, 1, dropId); });
            items.when(() -> Items.id(any())).thenAnswer(c -> c.getArgument(0, ItemStack.class).getType().getKey().toString());
            controller = spy(new SandwichBoardController(furniture, plugin));
            doReturn(mock(Item.class)).when(controller).ceItem(any()); doNothing().when(controller).openEditor(any(), any());
            Field displayField = SandwichBoardController.class.getDeclaredField("display"); displayField.setAccessible(true); displayField.set(controller, display);
            controller.onLoad(); clearInvocations(furniture, display);
        }
        ItemStack hand() { return slots[selected] == null ? new Stack(Material.AIR, 0, "") : slots[selected]; }
        void use(Material material) { slots[selected] = new Stack(material, 2, "component"); controller.interact(player); }
        @Override public void close() { items.close(); bukkit.close(); }
    }

    private static final class Stack extends ItemStack {
        private final Material type; private final String component; private int amount;
        Stack(Material type, int amount, String component) { super(); this.type = type; this.amount = amount; this.component = component; }
        @Override public Material getType() { return type; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public boolean isEmpty() { return amount <= 0; }
        @Override public Stack clone() { return new Stack(type, amount, component); }
        @Override public Stack asQuantity(int quantity) { return new Stack(type, quantity, component); }
        @Override public boolean isSimilar(ItemStack other) { return other instanceof Stack value && type == value.type && component.equals(value.component); }
        @Override public boolean equals(Object other) { return other instanceof Stack value && amount == value.amount && isSimilar(value); }
        @Override public int hashCode() { return Objects.hash(type, amount, component); }
    }
}
