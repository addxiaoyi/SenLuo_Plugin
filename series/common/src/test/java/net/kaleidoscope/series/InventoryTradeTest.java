package net.kaleidoscope.series;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InventoryTradeTest {
    @Test void capturedHandSlotConsumesThereEvenWhenEarlierSlotsContainIdenticalItems() {
        ItemStack earlier = stack("fish", 30, 64), held = stack("fish", 5, 64);
        PlayerInventory inventory = inventory(earlier, held);
        assertTrue(InventoryTrade.exchangeSlot(inventory, 1, held::isSimilar, 1, List.of(), () -> true));
        assertEquals(30, written(inventory)[0].getAmount());
        assertEquals(4, written(inventory)[1].getAmount());
        assertEquals(5, held.getAmount());
    }

    @Test void capturedSlotCannotBorrowPaymentFromOtherSlotsOrCommitAfterSlotSwitch() {
        ItemStack fish = stack("fish", 30, 64);
        PlayerInventory inventory = inventory(fish, stack("stone", 5, 64));
        assertFalse(InventoryTrade.exchangeSlot(inventory, 1, fish::isSimilar, 1, List.of(), () -> true));
        assertFalse(InventoryTrade.exchangeSlot(inventory, 0, fish::isSimilar, 1, List.of(), () -> false));
        verify(inventory, never()).setStorageContents(any());
        assertThrows(IllegalArgumentException.class, () -> InventoryTrade.exchangeSlot(inventory, -1, fish::isSimilar, 1, List.of(), () -> true));
        assertThrows(IllegalArgumentException.class, () -> InventoryTrade.exchangeSlot(inventory, 2, fish::isSimilar, 1, List.of(), () -> true));
    }

    @Test void staleFurnitureCannotCommitPaymentOrRewards() {
        ItemStack flower = stack("flower", 1, 64);
        PlayerInventory inventory = inventory(flower);
        assertFalse(InventoryTrade.exchange(inventory, flower::isSimilar, 1,
                List.of(stack("refund", 1, 64)), () -> false));
        verify(inventory, never()).setStorageContents(any());
        assertEquals(1, flower.getAmount());
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
