package net.kaleidoscope.cookery.item;

import net.kaleidoscope.cookery.item.listener.LunchBagListener;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LunchBagClickTest {
    @org.junit.jupiter.api.BeforeAll static void initializeInventoryTypes() {
        // Modern Paper initializes menu types through RegistryAccess. These tests
        // never open a menu, but still exercise the real InventoryType constants.
        try (var access = mockStatic(io.papermc.paper.registry.RegistryAccess.class)) {
            var provider = mock(io.papermc.paper.registry.RegistryAccess.class, invocation ->
                    mock(org.bukkit.Registry.class, registryCall ->
                            registryCall.getMethod().getName().equals("getOrThrow")
                                    ? mock(org.bukkit.inventory.MenuType.Typed.class) : null));
            access.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(provider);
            var menus = org.bukkit.Registry.MENU;
            doAnswer(call -> mock(org.bukkit.inventory.MenuType.Typed.class)).when(menus)
                    .getOrThrow(any(net.kyori.adventure.key.Key.class));
            InventoryType.values();
        }
    }

    private ItemStack stack(int amount) {
        ItemStack stack = mock(ItemStack.class);
        Material material = mock(Material.class);
        AtomicInteger count = new AtomicInteger(amount);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenAnswer(call -> count.get());
        doAnswer(call -> { count.set(call.getArgument(0)); return null; }).when(stack).setAmount(anyInt());
        when(stack.clone()).thenAnswer(call -> stack(count.get()));
        return stack;
    }

    @Test void bothRightClickDirectionsDeductOnlyTheAmountThatFits() {
        for (boolean bagInSlot : new boolean[]{true, false}) {
            Player player = mock(Player.class);
            var event = mock(InventoryClickEvent.class);
            var bagStack = stack(1); var foodStack = stack(64); var updatedBag = stack(1);
            Item bag = mock(Item.class); Item food = mock(Item.class);
            when(event.getCurrentItem()).thenReturn(bagInSlot ? bagStack : foodStack);
            when(event.getCursor()).thenReturn(bagInSlot ? foodStack : bagStack);
            when(event.getClick()).thenReturn(ClickType.RIGHT);
            when(event.getSlotType()).thenReturn(InventoryType.SlotType.CONTAINER);
            var inventory = mock(Inventory.class);
            when(inventory.getType()).thenReturn(InventoryType.PLAYER);
            when(event.getClickedInventory()).thenReturn(inventory);
            when(event.getWhoClicked()).thenReturn(player);
            try (var items = mockStatic(InventoryUtils.class); var bags = mockStatic(LunchBagContents.class)) {
                items.when(() -> InventoryUtils.wrap(any(ItemStack.class))).thenAnswer(call ->
                        ((ItemStack) call.getArgument(0)).getAmount() == 1 ? bag : food);
                items.when(() -> InventoryUtils.bukkitCopy(bag)).thenReturn(updatedBag);
                bags.when(() -> LunchBagContents.isLunchBag(bag)).thenReturn(true);
                bags.when(() -> LunchBagContents.add(bag, food)).thenReturn(4);
                new LunchBagListener().onInventoryClick(event);
                verify(event).setCancelled(true);
                var slot = org.mockito.ArgumentCaptor.forClass(ItemStack.class);
                var cursor = org.mockito.ArgumentCaptor.forClass(ItemStack.class);
                verify(event).setCurrentItem(slot.capture());
                verify(player).setItemOnCursor(cursor.capture());
                assertSame(updatedBag, bagInSlot ? slot.getValue() : cursor.getValue());
                assertEquals(60, (bagInSlot ? cursor.getValue() : slot.getValue()).getAmount());
                assertEquals(64, foodStack.getAmount());
            }
        }
    }

    @Test void cancelledInventoryInteractionCannotReadOrWriteABag() {
        var event = mock(InventoryClickEvent.class);
        when(event.isCancelled()).thenReturn(true);
        try (var items = mockStatic(InventoryUtils.class)) {
            new LunchBagListener().onInventoryClick(event);
            items.verifyNoInteractions();
            verify(event, never()).setCurrentItem(any());
        }
    }

    @Test void craftingResultSlotsCannotBeUsedToDuplicateRecipeOutputs() {
        var event = mock(InventoryClickEvent.class);
        var bagStack = stack(1);
        when(event.getCursor()).thenReturn(bagStack);
        when(event.getClick()).thenReturn(ClickType.RIGHT);
        when(event.getSlotType()).thenReturn(InventoryType.SlotType.RESULT);
        when(event.getClickedInventory()).thenReturn(mock(Inventory.class));
        when(event.getWhoClicked()).thenReturn(mock(Player.class));
        Item bag = mock(Item.class); Item empty = mock(Item.class);
        when(empty.isEmpty()).thenReturn(true);
        try (var items = mockStatic(InventoryUtils.class); var bags = mockStatic(LunchBagContents.class);
             var factory = mockStatic(Item.class)) {
            factory.when(Item::empty).thenReturn(empty);
            items.when(() -> InventoryUtils.wrap(bagStack)).thenReturn(bag);
            bags.when(() -> LunchBagContents.isLunchBag(bag)).thenReturn(true);
            new LunchBagListener().onInventoryClick(event);
            verify(event).setCancelled(true);
            bags.verify(() -> LunchBagContents.add(any(), any()), never());
            verify(event, never()).setCurrentItem(any());
        }
    }

    @Test void machineInputsAndSpectatorsCannotBypassNativeSlotRestrictions() {
        for (InventoryType type : new InventoryType[]{InventoryType.FURNACE, InventoryType.BREWING,
                InventoryType.BEACON, InventoryType.ANVIL, InventoryType.PLAYER}) {
            var event = mock(InventoryClickEvent.class);
            var player = mock(Player.class);
            var inventory = mock(Inventory.class);
            var bagStack = stack(1);
            when(inventory.getType()).thenReturn(type);
            when(event.getClickedInventory()).thenReturn(inventory);
            when(event.getWhoClicked()).thenReturn(player);
            when(player.getGameMode()).thenReturn(type == InventoryType.PLAYER
                    ? org.bukkit.GameMode.SPECTATOR : org.bukkit.GameMode.SURVIVAL);
            when(event.getCursor()).thenReturn(bagStack);
            when(event.getClick()).thenReturn(ClickType.RIGHT);
            when(event.getSlotType()).thenReturn(InventoryType.SlotType.CONTAINER);
            Item bag = mock(Item.class); Item empty = mock(Item.class);
            when(empty.isEmpty()).thenReturn(true);
            try (var items = mockStatic(InventoryUtils.class); var bags = mockStatic(LunchBagContents.class);
                 var factory = mockStatic(Item.class)) {
                factory.when(Item::empty).thenReturn(empty);
                items.when(() -> InventoryUtils.wrap(bagStack)).thenReturn(bag);
                bags.when(() -> LunchBagContents.isLunchBag(bag)).thenReturn(true);
                new LunchBagListener().onInventoryClick(event);
                verify(event).setCancelled(true);
                bags.verify(() -> LunchBagContents.removeFirstStack(any()), never());
                bags.verify(() -> LunchBagContents.add(any(), any()), never());
                verify(event, never()).setCurrentItem(any());
                verify(player, never()).setItemOnCursor(any());
            }
        }
    }

    @Test void cancelledDropRestoresOnlyAfterTheServerReinsertsTheOriginalStack() {
        Player player = mock(Player.class);
        var drop = mock(org.bukkit.entity.Item.class);
        var event = new org.bukkit.event.player.PlayerDropItemEvent(player, drop);
        var eatingStack = stack(1);
        Item eating = mock(Item.class);
        when(drop.getItemStack()).thenReturn(eatingStack);
        java.util.concurrent.atomic.AtomicReference<Runnable> queued = new java.util.concurrent.atomic.AtomicReference<>();
        try (var items = mockStatic(InventoryUtils.class); var bags = mockStatic(LunchBagContents.class);
             var consumption = mockStatic(LunchBagEating.class);
             var scheduler = mockStatic(net.kaleidoscope.cookery.util.FoliaUtil.class)) {
            items.when(() -> InventoryUtils.wrap(eatingStack)).thenReturn(eating);
            bags.when(() -> LunchBagContents.isEatingForm(eating)).thenReturn(true);
            scheduler.when(() -> net.kaleidoscope.cookery.util.FoliaUtil.runLater(
                    any(Runnable.class), isNull(), eq(1L), eq(player))).thenAnswer(call -> {
                queued.set(call.getArgument(0)); return null;
            });
            new LunchBagListener().onDrop(event);
            assertTrue(event.isCancelled());
            consumption.verifyNoInteractions();
            assertNotNull(queued.get());
            queued.get().run();
            consumption.verify(() -> LunchBagEating.restore(player));
            verify(drop, never()).setItemStack(any());
        }
    }
}
