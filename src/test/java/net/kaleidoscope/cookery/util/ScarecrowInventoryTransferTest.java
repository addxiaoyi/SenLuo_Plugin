package net.kaleidoscope.cookery.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ScarecrowInventoryTransferTest {
    private ItemStack item(String components,int amount) {
        ItemStack stack=mock(ItemStack.class);AtomicInteger count=new AtomicInteger(amount);
        when(stack.getAmount()).thenAnswer(call->count.get());when(stack.getMaxStackSize()).thenReturn(64);
        when(stack.isEmpty()).thenAnswer(call->count.get()==0);when(stack.toString()).thenReturn(components);
        when(stack.isSimilar(any())).thenAnswer(call->components.equals(call.getArgument(0).toString()));
        when(stack.clone()).thenAnswer(call->item(components,count.get()));
        doAnswer(call->{count.set(call.getArgument(0));return null;}).when(stack).setAmount(anyInt());return stack;
    }
    @Test void mergesComponentsBeforeFillingFirstEmptyInventorySlotAndNeverMutatesSource() {
        PlayerInventory inventory=mock(PlayerInventory.class);when(inventory.getMaxStackSize()).thenReturn(64);
        ItemStack first=item("other",63),merge=item("reward",63);
        ItemStack[] original={null,first,merge};when(inventory.getStorageContents()).thenReturn(original);
        ItemStack[] next=ScarecrowInventoryTransfer.plan(inventory,item("reward",2));
        assertEquals(1,next[0].getAmount());assertEquals(63,next[1].getAmount());assertEquals(64,next[2].getAmount());
        assertNull(original[0]);assertEquals(63,merge.getAmount());assertEquals(63,first.getAmount());
    }
    @Test void fullInventoryRejectsWithoutPartialWrite() {
        PlayerInventory inventory=mock(PlayerInventory.class);when(inventory.getMaxStackSize()).thenReturn(64);
        ItemStack original=item("reward",63);when(inventory.getStorageContents()).thenReturn(new ItemStack[]{original});
        assertNull(ScarecrowInventoryTransfer.plan(inventory,item("reward",2)));assertEquals(63,original.getAmount());
        verify(inventory,never()).setStorageContents(any());
    }
}
