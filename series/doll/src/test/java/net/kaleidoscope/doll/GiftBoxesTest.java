package net.kaleidoscope.doll;

import net.kaleidoscope.series.InventoryTrade;
import net.kaleidoscope.series.InventoryTradeEvent;
import net.kaleidoscope.series.Items;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GiftBoxesTest {
    private static final String BOX = "kaleidoscope_doll:green_doll_gift_box";
    private static final String DOLL = "kaleidoscope_doll:doll_0";
    private static final String OTHER_DOLL = "kaleidoscope_doll:doll_1";

    @Test void wrongPdcTypeIsRejectedWithoutReadingItAsAString() {
        try (var fixture = new Fixture()) {
            when(fixture.data.has(fixture.key)).thenReturn(true);
            when(fixture.data.has(fixture.key, PersistentDataType.INTEGER)).thenReturn(true);
            when(fixture.data.has(fixture.key, PersistentDataType.STRING)).thenReturn(false);
            when(fixture.data.get(fixture.key, PersistentDataType.STRING))
                    .thenThrow(new IllegalArgumentException("Stored type is INTEGER"));

            assertDoesNotThrow(fixture::open);

            verify(fixture.data, never()).get(fixture.key, PersistentDataType.STRING);
            verify(fixture.data, never()).set(eq(fixture.key), eq(PersistentDataType.STRING), anyString());
            verify(fixture.data, never()).remove(fixture.key);
            verify(fixture.player).sendMessage("礼盒没有有效的奖品记录，请从抽奖机获取。");
            fixture.assertNoCommit();
            verify(fixture.plugin, never()).attempt(any(), any());
        }
    }

    @Test void missingPrizeRecordCannotOpenABox() {
        try (var fixture = new Fixture()) {
            when(fixture.data.has(fixture.key, PersistentDataType.STRING)).thenReturn(false);
            fixture.open();
            verify(fixture.data, never()).get(fixture.key, PersistentDataType.STRING);
            fixture.assertNoCommit();
        }
    }

    @Test void prizeFromAnotherTierCannotOpenABox() {
        try (var fixture = new Fixture()) {
            when(fixture.data.get(fixture.key, PersistentDataType.STRING)).thenReturn(OTHER_DOLL);
            fixture.open();
            fixture.items.verify(() -> Items.create(anyString()), never());
            fixture.assertNoCommit();
        }
    }

    @Test void eventItemWithDifferentComponentsFromTheActualHandIsRejected() {
        try (var fixture = new Fixture()) {
            fixture.contents.get()[0] = stack("gift:other-components", 2, fixture.meta);
            fixture.open();
            verify(fixture.data, never()).has(fixture.key, PersistentDataType.STRING);
            verify(fixture.plugin, never()).attempt(any(), any());
            fixture.assertNoCommit();
        }
    }

    @Test void eventItemWithDifferentQuantityFromTheActualHandIsRejected() {
        try (var fixture = new Fixture()) {
            fixture.contents.get()[0] = stack("gift:original", 1, fixture.meta);
            fixture.open();
            verify(fixture.plugin, never()).attempt(any(), any());
            fixture.assertNoCommit();
        }
    }

    @Test void disabledPluginRejectsOpeningAtTheEntryPoint() {
        try (var fixture = new Fixture()) {
            when(fixture.plugin.isEnabled()).thenReturn(false);
            fixture.open();
            verify(fixture.event, never()).setCancelled(anyBoolean());
            verify(fixture.plugin, never()).attempt(any(), any());
            fixture.items.verify(() -> Items.id(any()), never());
            fixture.assertNoCommit();
        }
    }

    @Test void switchingSelectedSlotDuringTradeEventRejectsAnOtherwiseUnchangedInventory() {
        try (var fixture = new Fixture()) {
            ItemStack[] unchanged = fixture.contents.get();
            fixture.onTrade = event -> fixture.heldSlot.set(1);

            fixture.open();

            verify(fixture.events).callEvent(any(InventoryTradeEvent.class));
            assertSame(unchanged, fixture.contents.get());
            assertEquals(2, fixture.gift.getAmount());
            fixture.assertNoCommit();
        }
    }

    @Test void disablingPluginDuringTradeEventRejectsAnOtherwiseUnchangedInventory() {
        try (var fixture = new Fixture()) {
            ItemStack[] unchanged = fixture.contents.get();
            fixture.onTrade = event -> when(fixture.plugin.isEnabled()).thenReturn(false);

            fixture.open();

            verify(fixture.events).callEvent(any(InventoryTradeEvent.class));
            assertSame(unchanged, fixture.contents.get());
            assertEquals(2, fixture.gift.getAmount());
            fixture.assertNoCommit();
        }
    }

    @Test void changingHandComponentsDuringTradeDoesNotOverwriteTheListenersItem() {
        try (var fixture = new Fixture()) {
            ItemStack replacement = stack("gift:listener-components", 2, fixture.meta);
            fixture.onTrade = event -> fixture.contents.get()[0] = replacement;
            fixture.open();
            assertSame(replacement, fixture.contents.get()[0]);
            assertEquals(2, replacement.getAmount());
            fixture.assertNoCommit();
        }
    }

    @Test void changingHandQuantityDuringTradeDoesNotOverwriteTheListenersChange() {
        try (var fixture = new Fixture()) {
            fixture.onTrade = event -> fixture.gift.subtract(1);
            fixture.open();
            assertSame(fixture.gift, fixture.contents.get()[0]);
            assertEquals(1, fixture.gift.getAmount());
            fixture.assertNoCommit();
        }
    }

    @Test void beforeCommitIndependentlyRejectsDifferentHandComponents() {
        assertHandGuardRejects(fixture -> fixture.contents.get()[0] = stack("gift:changed-components", 2, fixture.meta));
    }

    @Test void beforeCommitIndependentlyRejectsDifferentHandQuantity() {
        assertHandGuardRejects(fixture -> fixture.gift.subtract(1));
    }

    @Test void cancelledTradeKeepsTheBoxAndDoesNotCollectADoll() {
        try (var fixture = new Fixture()) {
            fixture.onTrade = event -> event.setCancelled(true);
            fixture.open();
            assertEquals(2, fixture.gift.getAmount());
            fixture.assertNoCommit();
        }
    }

    @Test void successfulOpenCommitsOnePaymentAndRewardBeforeCollectingExactlyOnce() {
        try (var fixture = new Fixture()) {
            fixture.open();
            ItemStack[] committed = fixture.contents.get();
            assertEquals(1, committed[0].getAmount());
            assertTrue(committed[0].isSimilar(fixture.gift));
            assertTrue(committed[1].isSimilar(fixture.reward));
            assertEquals(1, committed[1].getAmount());
            assertEquals(2, fixture.gift.getAmount(), "Planning must leave the previous stack snapshot alone");
            var order = inOrder(fixture.inventory, fixture.plugin);
            order.verify(fixture.inventory).setStorageContents(any(ItemStack[].class));
            order.verify(fixture.plugin).collected(fixture.player, DOLL);
            verify(fixture.plugin, times(1)).collected(any(), anyString());
            verify(fixture.events, times(1)).callEvent(any(InventoryTradeEvent.class));
            fixture.items.verify(() -> Items.create(DOLL), times(1));
        }
    }

    @Test void staleEventCannotCollectAgainAfterTheFirstOpenChangesTheHandQuantity() {
        try (var fixture = new Fixture()) {
            fixture.open();
            fixture.open();
            verify(fixture.inventory, times(1)).setStorageContents(any(ItemStack[].class));
            verify(fixture.plugin, times(1)).collected(fixture.player, DOLL);
            assertEquals(1, fixture.contents.get()[0].getAmount());
        }
    }

    @Test void reopeningFromTheTradeEventCannotCommitOrCollectTheSameBoxTwice() {
        try (var fixture = new Fixture()) {
            fixture.onTrade = event -> fixture.open();
            fixture.open();
            verify(fixture.events, times(1)).callEvent(any(InventoryTradeEvent.class));
            verify(fixture.inventory, times(1)).setStorageContents(any(ItemStack[].class));
            verify(fixture.plugin, times(1)).collected(fixture.player, DOLL);
            assertEquals(1, fixture.contents.get()[0].getAmount());
            assertTrue(fixture.contents.get()[1].isSimilar(fixture.reward));
        }
    }

    @Test void offhandAndDeniedInteractionsAreIgnored() {
        try (var fixture = new Fixture()) {
            when(fixture.event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
            fixture.open();
            when(fixture.event.getHand()).thenReturn(EquipmentSlot.HAND);
            when(fixture.event.useItemInHand()).thenReturn(Event.Result.DENY);
            fixture.open();
            verify(fixture.event, never()).setCancelled(anyBoolean());
            verify(fixture.plugin, never()).attempt(any(), any());
            fixture.assertNoCommit();
        }
    }

    private static void assertHandGuardRejects(Consumer<Fixture> change) {
        try (var fixture = new Fixture(); var trade = mockStatic(InventoryTrade.class)) {
            AtomicReference<Boolean> allowed = new AtomicReference<>();
            trade.when(() -> InventoryTrade.exchange(eq(fixture.inventory), any(), eq(1), anyList(), any()))
                    .thenAnswer(call -> {
                        change.accept(fixture);
                        BooleanSupplier beforeCommit = call.getArgument(4);
                        boolean result = beforeCommit.getAsBoolean();
                        allowed.set(result);
                        return result;
                    });
            fixture.open();
            assertEquals(Boolean.FALSE, allowed.get(), "The gift-specific hand guard must reject the drift itself");
            fixture.assertNoCommit();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DollPlugin plugin = mock(DollPlugin.class);
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        final PluginManager events = mock(PluginManager.class);
        final PersistentDataContainer data = mock(PersistentDataContainer.class);
        final ItemMeta meta = mock(ItemMeta.class);
        final NamespacedKey key = new NamespacedKey("kaleidoscopedollplugin", "gift_doll_v1");
        final ItemStack gift = stack("gift:original", 2, meta);
        final ItemStack reward = stack("doll:reward", 1, null);
        final AtomicReference<ItemStack[]> contents = new AtomicReference<>(new ItemStack[] {gift, null, null});
        final AtomicInteger heldSlot = new AtomicInteger();
        final MockedStatic<Items> items = mockStatic(Items.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final GiftBoxes gifts;
        Consumer<InventoryTradeEvent> onTrade = ignored -> {};

        Fixture() {
            when(plugin.getName()).thenReturn("KaleidoscopeDollPlugin");
            when(plugin.namespace()).thenReturn("kaleidoscopedollplugin");
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.catalog()).thenReturn(new DollCatalog(List.of(DOLL, OTHER_DOLL), List.of(),
                    List.of(List.of(DOLL), List.of(OTHER_DOLL), List.of(OTHER_DOLL)),
                    List.of("minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:diamond"), Map.of()));
            doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                    .when(plugin).attempt(eq(player), any());
            when(meta.getPersistentDataContainer()).thenReturn(data);
            when(data.has(key, PersistentDataType.STRING)).thenReturn(true);
            when(data.get(key, PersistentDataType.STRING)).thenReturn(DOLL);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.isOnline()).thenReturn(true);
            when(player.getInventory()).thenReturn(inventory);
            when(inventory.getHolder()).thenReturn(player);
            when(inventory.getMaxStackSize()).thenReturn(64);
            when(inventory.getHeldItemSlot()).thenAnswer(call -> heldSlot.get());
            when(inventory.getItemInMainHand()).thenAnswer(call -> contents.get()[heldSlot.get()]);
            when(inventory.getStorageContents()).thenAnswer(call -> contents.get().clone());
            doAnswer(call -> { contents.set(call.getArgument(0)); return null; })
                    .when(inventory).setStorageContents(any(ItemStack[].class));
            when(event.getPlayer()).thenReturn(player);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);
            when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
            when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
            when(event.getItem()).thenReturn(gift);
            items.when(() -> Items.id(gift)).thenReturn(BOX);
            items.when(() -> Items.create(DOLL)).thenReturn(reward);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);
            bukkit.when(Bukkit::getPluginManager).thenReturn(events);
            doAnswer(call -> { onTrade.accept(call.getArgument(0)); return null; })
                    .when(events).callEvent(any(InventoryTradeEvent.class));
            gifts = new GiftBoxes(plugin);
        }

        void open() { gifts.open(event); }

        void assertNoCommit() {
            verify(inventory, never()).setStorageContents(any(ItemStack[].class));
            verify(plugin, never()).collected(any(), anyString());
        }

        @Override public void close() { bukkit.close(); items.close(); }
    }

    private static ItemStack stack(String components, int count, ItemMeta meta) {
        AtomicInteger amount = new AtomicInteger(count);
        return mock(ItemStack.class, call -> switch (call.getMethod().getName()) {
            case "getAmount" -> amount.get();
            case "getMaxStackSize" -> 64;
            case "getItemMeta" -> meta;
            case "isEmpty" -> amount.get() == 0;
            case "toString" -> components;
            case "isSimilar" -> call.getArgument(0) != null && components.equals(call.getArgument(0).toString());
            case "clone" -> stack(components, amount.get(), meta);
            case "asOne" -> stack(components, 1, meta);
            case "asQuantity" -> stack(components, call.getArgument(0), meta);
            case "subtract" -> { amount.addAndGet(-(int) call.getArgument(0)); yield call.getMock(); }
            case "add" -> { amount.addAndGet(call.getArgument(0)); yield call.getMock(); }
            default -> Answers.RETURNS_DEFAULTS.answer(call);
        });
    }
}
