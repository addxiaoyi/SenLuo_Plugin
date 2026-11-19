package net.kaleidoscope.series;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InventoryTradeTest {
    @Test void changingTheHeldSlotInsideTheFinalCallbackAlsoAbortsTheWrite() {
        var inventory = inventory(stack("carpet", 3, 64), stack("carpet", 2, 64));
        int[] selected = {1}; when(inventory.getHeldItemSlot()).thenAnswer(call -> selected[0]);
        assertFalse(InventoryTrade.exchangeHeld(inventory, item -> true, 1, List.of(), () -> { selected[0] = 0; return true; }));
        verify(inventory, never()).setStorageContents(any());
    }
    @Test void aHeldPaymentLeavesAnEarlierIdenticalStackUntouched() {
        var inventory = inventory(stack("carpet", 3, 64), stack("carpet", 2, 64));
        when(inventory.getHeldItemSlot()).thenReturn(1);
        assertTrue(InventoryTrade.exchangeHeld(inventory, item -> item.toString().equals("carpet"), 1, List.of(), () -> true));
        var changed = written(inventory);
        assertEquals(3, changed[0].getAmount()); assertEquals(1, changed[1].getAmount());
    }
    @Test void anInsufficientHeldStackDoesNotBorrowFromOtherIdenticalStacks() {
        var inventory = inventory(stack("carpet", 64, 64), stack("carpet", 1, 64));
        when(inventory.getHeldItemSlot()).thenReturn(1);
        assertFalse(InventoryTrade.exchangeHeld(inventory, item -> true, 2, List.of(), () -> true));
        verify(inventory, never()).setStorageContents(any());
    }
    @Test void aTradeCallbackChangingTheHeldSlotCannotCommitThePlannedPayment() {
        var inventory = inventory(stack("carpet", 3, 64), stack("carpet", 2, 64));
        int[] selected = {1}; when(inventory.getHeldItemSlot()).thenAnswer(call -> selected[0]);
        try (var player = new PlayerFixture(inventory)) {
            doAnswer(call -> { selected[0] = 0; return null; }).when(player.plugins).callEvent(any());
            assertFalse(InventoryTrade.exchangeHeld(inventory, item -> true, 1, List.of(), () -> true));
            verify(inventory, never()).setStorageContents(any());
        }
    }
    @Test void insufficientPaymentLeavesAllSlotsUntouched() {
        ItemStack coin = stack("coin", 2, 64);
        PlayerInventory inventory = inventory(coin, null);
        assertFalse(InventoryTrade.exchange(inventory, coin::isSimilar, 3, List.of(stack("doll", 1, 64))));
        verify(inventory, never()).setStorageContents(any());
        assertEquals(2, coin.getAmount());
    }

    @Test void fullInventoryDoesNotConsumePaymentOrEarlierRewards() {
        ItemStack coin = stack("coin", 10, 64);
        PlayerInventory inventory = inventory(coin, stack("stone", 64, 64));
        assertFalse(InventoryTrade.exchange(inventory, coin::isSimilar, 1,
                List.of(stack("coin", 1, 64), stack("doll", 1, 64))));
        verify(inventory, never()).setStorageContents(any());
        assertEquals(10, coin.getAmount());
    }

    @Test void paymentCanFreeTheOnlySlotForTheReward() {
        ItemStack coin = stack("coin", 1, 64);
        PlayerInventory inventory = inventory(coin);
        assertTrue(InventoryTrade.exchange(inventory, coin::isSimilar, 1, List.of(stack("doll", 1, 64))));
        ItemStack[] written = written(inventory);
        assertTrue(written[0].isSimilar(stack("doll", 1, 64)));
        assertEquals(1, written[0].getAmount());
        assertEquals(1, coin.getAmount());
    }

    @Test void rewardsMergeThenSplitAtTheirOwnStackLimit() {
        PlayerInventory inventory = inventory(stack("bottle", 14, 16), null);
        ItemStack reward = stack("bottle", 5, 16);
        assertTrue(InventoryTrade.give(inventory, reward));
        ItemStack[] written = written(inventory);
        assertEquals(16, written[0].getAmount());
        assertEquals(3, written[1].getAmount());
        assertEquals(5, reward.getAmount());
    }

    @Test void identicalMaterialWithDifferentMetadataCannotPay() {
        ItemStack ordinary = stack("plain", 8, 64);
        PlayerInventory inventory = inventory(stack("named", 8, 64));
        assertFalse(InventoryTrade.exchange(inventory, ordinary::isSimilar, 1, List.of()));
        verify(inventory, never()).setStorageContents(any());
    }

    @Test void invalidRewardNeverCommitsAnAlreadySimulatedPayment() {
        ItemStack coin = stack("coin", 1, 64);
        PlayerInventory inventory = inventory(coin);
        assertThrows(IllegalArgumentException.class, () -> InventoryTrade.exchange(inventory,
                coin::isSimilar, 1, List.of(stack("empty", 0, 64))));
        verify(inventory, never()).setStorageContents(any());
        assertEquals(1, coin.getAmount());
    }

    @Test void associatedStateCommitsAfterTheEventAndBeforeTheInventory() {
        var order = new ArrayList<String>();
        PlayerInventory inventory = inventory(stack("coin", 2, 64));
        try (var fixture = new PlayerFixture(inventory)) {
            doAnswer(call -> { order.add("event"); return null; }).when(fixture.plugins).callEvent(any());
            doAnswer(call -> { order.add("inventory"); return null; }).when(inventory).setStorageContents(any());
            assertTrue(InventoryTrade.exchange(inventory, ignored -> true, 1, List.of(), () -> {
                order.add("state");
                return true;
            }));
            assertEquals(List.of("event", "state", "inventory"), order);
        }
    }

    @Test void rejectedAssociatedStateDoesNotTakePayment() {
        PlayerInventory inventory = inventory(stack("coin", 2, 64));
        assertFalse(InventoryTrade.exchange(inventory, ignored -> true, 1, List.of(), () -> false));
        verify(inventory, never()).setStorageContents(any());
    }

    @Test void cancellationDoesNotCommitAssociatedState() {
        PlayerInventory inventory = inventory(stack("coin", 2, 64));
        var committed = new AtomicBoolean();
        try (var fixture = new PlayerFixture(inventory)) {
            doAnswer(call -> {
                ((InventoryTradeEvent) call.getArgument(0)).setCancelled(true);
                return null;
            }).when(fixture.plugins).callEvent(any());
            assertFalse(InventoryTrade.exchange(inventory, ignored -> true, 1, List.of(),
                    () -> { committed.set(true); return true; }));
            assertFalse(committed.get());
            verify(inventory, never()).setStorageContents(any());
        }
    }

    @Test void eventInventoryDriftDoesNotOverwriteTheListenersChange() {
        ItemStack coin = stack("coin", 2, 64);
        PlayerInventory inventory = inventory(coin);
        var committed = new AtomicBoolean();
        try (var fixture = new PlayerFixture(inventory)) {
            doAnswer(call -> { coin.subtract(1); return null; }).when(fixture.plugins).callEvent(any());
            assertFalse(InventoryTrade.exchange(inventory, ignored -> true, 1, List.of(),
                    () -> { committed.set(true); return true; }));
            assertFalse(committed.get());
            assertEquals(1, coin.getAmount());
            verify(inventory, never()).setStorageContents(any());
        }
    }

    @Test void playerRetirementDuringTheEventLeavesPaymentAndStateAlone() {
        PlayerInventory inventory = inventory(stack("coin", 2, 64));
        var committed = new AtomicBoolean();
        try (var fixture = new PlayerFixture(inventory)) {
            doAnswer(call -> { when(fixture.player.isOnline()).thenReturn(false); return null; })
                    .when(fixture.plugins).callEvent(any());
            assertFalse(InventoryTrade.exchange(inventory, ignored -> true, 1, List.of(),
                    () -> { committed.set(true); return true; }));
            assertFalse(committed.get());
            verify(inventory, never()).setStorageContents(any());
        }
    }

    @Test void foreignRegionCannotReadOrMutateTheInventory() {
        PlayerInventory inventory = inventory(stack("coin", 2, 64));
        try (var fixture = new PlayerFixture(inventory)) {
            fixture.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.player)).thenReturn(false);
            assertFalse(InventoryTrade.exchange(inventory, ignored -> true, 1, List.of()));
            verify(inventory, never()).getStorageContents();
            verify(inventory, never()).setStorageContents(any());
        }
    }

    @Test void nestedPaymentIsRejectedAndAnExceptionReleasesTheGuard() {
        PlayerInventory inventory = inventory(stack("coin", 2, 64));
        try (var fixture = new PlayerFixture(inventory)) {
            doAnswer(call -> {
                assertFalse(InventoryTrade.exchange(inventory, ignored -> true, 1, List.of()));
                return null;
            }).when(fixture.plugins).callEvent(any());
            assertThrows(IllegalStateException.class, () -> InventoryTrade.exchange(
                    inventory, ignored -> true, 1, List.of(), () -> { throw new IllegalStateException("refused"); }));
            assertTrue(InventoryTrade.exchange(inventory, ignored -> true, 1, List.of()));
            verify(inventory).setStorageContents(any());
        }
    }

    private static final class PlayerFixture implements AutoCloseable {
        final Player player = mock(Player.class);
        final PluginManager plugins = mock(PluginManager.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);

        PlayerFixture(PlayerInventory inventory) {
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.isOnline()).thenReturn(true);
            when(inventory.getHolder()).thenReturn(player);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        }
        @Override public void close() { bukkit.close(); }
    }

    private static PlayerInventory inventory(ItemStack... contents) {
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getStorageContents()).thenAnswer(call -> contents.clone());
        when(inventory.getMaxStackSize()).thenReturn(64);
        return inventory;
    }

    private static ItemStack[] written(PlayerInventory inventory) {
        var captured = ArgumentCaptor.forClass(ItemStack[].class);
        verify(inventory).setStorageContents(captured.capture());
        return captured.getValue();
    }

    private static ItemStack stack(String kind, int count, int limit) {
        int[] amount = {count};
        return mock(ItemStack.class, call -> switch (call.getMethod().getName()) {
            case "getAmount" -> amount[0];
            case "getMaxStackSize" -> limit;
            case "isEmpty" -> amount[0] == 0;
            case "toString" -> kind;
            case "isSimilar" -> call.getArgument(0) != null && kind.equals(call.getArgument(0).toString());
            case "clone" -> stack(kind, amount[0], limit);
            case "asQuantity" -> stack(kind, call.getArgument(0), limit);
            case "subtract" -> { amount[0] -= (int) call.getArgument(0); yield call.getMock(); }
            case "add" -> { amount[0] += (int) call.getArgument(0); yield call.getMock(); }
            default -> throw new AssertionError("Unexpected stack access: " + call.getMethod());
        });
    }
}
