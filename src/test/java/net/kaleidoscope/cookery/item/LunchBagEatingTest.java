package net.kaleidoscope.cookery.item;

import net.kaleidoscope.cookery.api.KaleidoscopeAdvancements;
import net.kaleidoscope.cookery.nms.NmsBridge;
import net.kaleidoscope.cookery.nms.NmsBridgeProvider;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LunchBagEatingTest {
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final PluginManager events = mock(PluginManager.class);
    private final NmsBridge bridge = mock(NmsBridge.class);
    private final Item eating = mock(Item.class);
    private final Item bag = mock(Item.class);
    private final Item empty = mock(Item.class);
    private final Object remainder = new Object();
    private final AtomicInteger hunger = new AtomicInteger(4);
    private final List<Item> contents = new ArrayList<>();
    private final Map<ItemStack, Item> wrappers = new IdentityHashMap<>();
    private final Map<Item, String> kinds = new IdentityHashMap<>();
    private ItemStack held;
    private int revision;
    private MockedStatic<InventoryUtils> items;
    private MockedStatic<LunchBagContents> storage;
    private MockedStatic<CookeryFoodItems> foods;
    private MockedStatic<NmsBridgeProvider> nativeBridge;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<KaleidoscopeAdvancements> advancements;

    private ItemStack visible(String identity, int count, boolean edible, boolean potion) {
        ItemStack stack = mock(ItemStack.class, identity);
        ItemMeta meta = mock(ItemMeta.class);
        when(meta.hasFood()).thenReturn(edible);
        when(stack.getItemMeta()).thenReturn(meta);
        when(stack.getAmount()).thenReturn(count);
        when(stack.clone()).thenReturn(stack);
        when(stack.asQuantity(anyInt())).thenReturn(stack);
        when(stack.getType()).thenReturn(potion ? Material.POTION : Material.PAPER);
        when(stack.isSimilar(any())).thenAnswer(call -> identity.equals(mockingDetails(call.getArgument(0)).getMockCreationSettings().getMockName().toString()));
        return stack;
    }

    private Item food(String kind, int count) {
        Item item = mock(Item.class, kind);
        kinds.put(item, kind);
        when(item.count()).thenReturn(count);
        when(item.isEmpty()).thenReturn(count == 0);
        when(item.minecraftItem()).thenReturn(item);
        when(item.copyWithCount(anyInt())).thenAnswer(call -> food(kind, call.getArgument(0)));
        return item;
    }

    @BeforeEach void setup() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getFoodLevel()).thenAnswer(call -> hunger.get());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(empty.isEmpty()).thenReturn(true);
        held = visible("eating", 1, false, false);
        when(inventory.getItem(EquipmentSlot.HAND)).thenAnswer(call -> held);
        doAnswer(call -> { held = call.getArgument(1); return null; }).when(inventory).setItem(eq(EquipmentSlot.HAND), any());
        items = mockStatic(InventoryUtils.class);
        storage = mockStatic(LunchBagContents.class);
        foods = mockStatic(CookeryFoodItems.class);
        nativeBridge = mockStatic(NmsBridgeProvider.class);
        bukkit = mockStatic(Bukkit.class);
        advancements = mockStatic(KaleidoscopeAdvancements.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(events);
        nativeBridge.when(NmsBridgeProvider::bridge).thenReturn(bridge);
        storage.when(() -> LunchBagContents.read(eating)).thenAnswer(call -> new ArrayList<>(contents));
        storage.when(() -> LunchBagContents.toBagForm(eating)).thenReturn(bag);
        storage.when(() -> LunchBagContents.canAdd(any())).thenReturn(true);
        storage.when(() -> LunchBagContents.write(eq(bag), anyList())).thenAnswer(call -> {
            contents.clear();
            for (Item item : (List<Item>) call.getArgument(1)) if (!item.isEmpty()) contents.add(item);
            revision++; return null;
        });
        items.when(() -> InventoryUtils.bukkitCopy(any())).thenAnswer(call -> {
            Item item = call.getArgument(0);
            if (item == eating) return visible("eating", 1, false, false);
            if (item == bag) return visible("bag-" + revision, 1, false, false);
            String kind = kinds.get(item);
            ItemStack stack = visible(kind, item.count(), !kind.equals("potion"), kind.equals("potion"));
            wrappers.put(stack, item); return stack;
        });
        items.when(() -> InventoryUtils.wrap(any(ItemStack.class))).thenAnswer(call -> wrappers.get(call.getArgument(0)));
        items.when(() -> InventoryUtils.wrapNative(remainder)).thenReturn(empty);
        when(bridge.finishConsumingItem(any(), eq(player))).thenAnswer(call -> {
            Item item = call.getArgument(0);
            if (!"potion".equals(kinds.get(item))) hunger.set(Math.min(20, hunger.get() + 8));
            return remainder;
        });
    }

    @AfterEach void close() {
        advancements.close(); bukkit.close(); nativeBridge.close(); foods.close(); storage.close(); items.close();
    }

    @Test void oneUseEatsUntilFullAndStillDrinksLaterPotions() {
        contents.add(food("steak", 5)); contents.add(food("potion", 1));
        LunchBagEating.finish(player, EquipmentSlot.HAND, eating);
        assertEquals(20, hunger.get());
        assertEquals(1, contents.size());
        assertEquals(3, contents.getFirst().count());
        verify(bridge, times(3)).finishConsumingItem(any(), eq(player));
        advancements.verify(() -> KaleidoscopeAdvancements.recordEvent(player, "use_transmutation_lunch_bag"));
    }

    @Test void fullHungerKeepsFoodButConsumesPotionAndReturnsItsBottle() {
        hunger.set(20); contents.add(food("steak", 5)); contents.add(food("potion", 1));
        Item bottle = mock(Item.class);
        items.when(() -> InventoryUtils.wrapNative(remainder)).thenReturn(bottle);
        LunchBagEating.finish(player, EquipmentSlot.HAND, eating);
        assertEquals(5, contents.getFirst().count());
        verify(bridge).finishConsumingItem(any(), eq(player));
        items.verify(() -> InventoryUtils.give(player, bottle));
    }

    @Test void cancelledFoodIsNeverRemovedOrPassedToVanilla() {
        contents.add(food("steak", 5));
        doAnswer(call -> { ((PlayerItemConsumeEvent) call.getArgument(0)).setCancelled(true); return null; })
                .when(events).callEvent(any(PlayerItemConsumeEvent.class));
        LunchBagEating.finish(player, EquipmentSlot.HAND, eating);
        assertEquals(5, contents.getFirst().count());
        verifyNoInteractions(bridge);
        storage.verify(() -> LunchBagContents.write(any(), anyList()), never());
    }

    @Test void staleCallbackCannotReplaceAnotherHeldItem() {
        contents.add(food("steak", 5)); held = visible("other", 1, false, false);
        LunchBagEating.finish(player, EquipmentSlot.HAND, eating);
        assertEquals(5, contents.getFirst().count());
        verify(inventory, never()).setItem(any(EquipmentSlot.class), any());
        verifyNoInteractions(events, bridge);
    }

    @Test void eventChangingTheHandAbortsBeforePayment() {
        contents.add(food("steak", 5));
        doAnswer(call -> { held = visible("other", 1, false, false); return null; })
                .when(events).callEvent(any(PlayerItemConsumeEvent.class));
        LunchBagEating.finish(player, EquipmentSlot.HAND, eating);
        assertEquals(5, contents.getFirst().count());
        verifyNoInteractions(bridge);
        verify(inventory, never()).setItem(any(EquipmentSlot.class), any());
    }

    @Test void reentrantNativeCallbackSeesTheCommittedBagAndCannotEatTwice() {
        contents.add(food("steak", 1));
        when(bridge.finishConsumingItem(any(), eq(player))).thenAnswer(call -> {
            assertTrue(contents.isEmpty());
            LunchBagEating.finish(player, EquipmentSlot.HAND, eating);
            return remainder;
        });
        LunchBagEating.finish(player, EquipmentSlot.HAND, eating);
        verify(bridge).finishConsumingItem(any(), eq(player));
        assertTrue(contents.isEmpty());
    }
}
