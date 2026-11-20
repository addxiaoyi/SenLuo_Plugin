package net.kaleidoscope.cookery.item;

import net.momirealms.craftengine.core.item.Item;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LunchBagInventoryTest {
    static Item stack(String identity, int count, int maximum) {
        Item item = mock(Item.class, identity);
        when(item.count()).thenReturn(count);
        when(item.maxStackSize()).thenReturn(maximum);
        when(item.isEmpty()).thenReturn(count == 0);
        when(item.isSimilar(any())).thenAnswer(call -> identity.equals(mockingDetails(call.getArgument(0)).getMockCreationSettings().getMockName().toString()));
        when(item.copyWithCount(anyInt())).thenAnswer(call -> stack(identity, call.getArgument(0), maximum));
        when(item.copy()).thenAnswer(call -> stack(identity, count, maximum));
        return item;
    }

    @Test void fitsTwentyFourFullStacksAndLeavesTheTwentyFifthUntouched() {
        var slots = new ArrayList<Item>();
        for (int index = 0; index < 24; index++) assertEquals(64, LunchBagInventory.insert(slots, stack("food", 64, 64), 24));
        assertEquals(24, slots.size());
        Item extra = stack("food", 1, 64);
        assertEquals(0, LunchBagInventory.insert(slots, extra, 24));
        verify(extra, never()).shrink(anyInt());
        assertEquals(1536, slots.stream().mapToInt(Item::count).sum());
    }

    @Test void foodQualityAndPotionComponentsRemainDistinct() {
        Item normal = stack("same-id:base", 12, 64);
        var slots = new ArrayList<>(List.of(normal));
        assertEquals(5, LunchBagInventory.insert(slots, stack("same-id:premium", 5, 64), 24));
        assertEquals(2, slots.size());
        assertSame(normal, slots.getFirst());
        assertEquals(5, slots.getLast().count());
    }

    @Test void fillsExistingSlotsThenReturnsOnlyTheCountThatFits() {
        Item original = stack("food", 60, 64);
        var slots = new ArrayList<>(List.of(original));
        assertEquals(68, LunchBagInventory.insert(slots, stack("food", 100, 64), 2));
        assertEquals(List.of(64, 64), slots.stream().map(Item::count).toList());
        assertEquals(60, original.count());
        verify(original, never()).grow(anyInt());
    }

    @Test void respectsPotionStackLimitsAndBasketCapacity() {
        var slots = new ArrayList<Item>();
        assertEquals(8, LunchBagInventory.insert(slots, stack("potion", 24, 1), 8));
        assertEquals(8, slots.size());
        assertTrue(slots.stream().allMatch(item -> item.count() == 1));
    }

    @Test void invalidInventoryDoesNotModifyExistingStacks() {
        var slots = new ArrayList<>(List.of(stack("food", 64, 64), stack("other", 1, 1)));
        var snapshot = List.copyOf(slots);
        assertThrows(IllegalArgumentException.class, () -> LunchBagInventory.insert(slots, stack("food", 1, 64), 1));
        assertEquals(snapshot, slots);
    }
}
