package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.util.BlockEntityNbt;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.nbt.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrashCanInventoryTest {
    private MockedStatic<Item> empty;
    static Item item(String id, String metadata, int amount) {
        Item value = mock(Item.class);
        when(value.id()).thenReturn(Key.of("minecraft:" + id));
        when(value.count()).thenReturn(amount); when(value.isEmpty()).thenReturn(amount == 0);
        when(value.maxStackSize()).thenReturn(id.equals("diamond_sword") ? 1 : 64);
        when(value.copy()).thenAnswer(call -> item(id, metadata, amount));
        when(value.copyWithCount(anyInt())).thenAnswer(call -> item(id, metadata, call.getArgument(0)));
        when(value.customNameJson()).thenReturn(java.util.Optional.of(new com.google.gson.JsonPrimitive(metadata)));
        when(value.isSimilar(any())).thenAnswer(call -> {
            Item other = call.getArgument(0);
            return other != null && value.id().equals(other.id()) && value.customNameJson().equals(other.customNameJson());
        });
        return value;
    }
    @BeforeEach void setup() {
        Item air = item("air", "", 0); empty = mockStatic(Item.class); empty.when(Item::empty).thenReturn(air);
    }
    @AfterEach void close() { empty.close(); }

    @Test void mergesExactMetadataBeforeFillingEmptySlotsWithoutMutatingInputs() {
        Item[] before = {item("apple", "A", 62), Item.empty(), item("apple", "B", 3)};
        var result = TrashCanInventory.insert(before, item("apple", "A", 8));
        assertEquals(8, result.consumed());
        assertEquals(64, result.slots()[0].count()); assertEquals(6, result.slots()[1].count());
        assertEquals("B", result.slots()[2].customNameJson().orElseThrow().getAsString()); assertEquals(3, result.slots()[2].count());
        assertEquals(62, before[0].count()); assertTrue(before[1].isEmpty());
    }
    @Test void partialFitDoesNotEvictAndUnmergeableFullCanEvictsOnlyOldest() {
        Item[] before = {item("apple", "A", 62), item("bread", "", 64), item("carrot", "", 64)};
        var partial = TrashCanInventory.insert(before, item("apple", "A", 9));
        assertEquals(2, partial.consumed()); assertEquals(Key.of("minecraft:carrot"), partial.slots()[2].id());
        var evicted = TrashCanInventory.insert(partial.slots(), item("apple", "B", 9));
        assertEquals(9, evicted.consumed()); assertEquals(Key.of("minecraft:bread"), evicted.slots()[0].id());
        assertEquals("B", evicted.slots()[2].customNameJson().orElseThrow().getAsString()); assertEquals(9, evicted.slots()[2].count());
    }
    @Test void filterConsumesWholeEntityButFillsOnlyFirstMatchingTypeSlot() {
        Item[] before = {item("apple", "A", 63), item("apple", "A", 2), Item.empty()};
        Item[] next = TrashCanInventory.filter(before, item("apple", "A", 20));
        assertEquals(64, next[0].count()); assertEquals(2, next[1].count()); assertTrue(next[2].isEmpty());
        assertEquals(63, before[0].count());
        Item[] full = TrashCanInventory.filter(next, item("apple", "A", 20));
        assertEquals(2, full[1].count());
        Item[] different = TrashCanInventory.filter(before, item("apple", "B", 20));
        assertEquals(63, different[0].count()); assertEquals("A", different[0].customNameJson().orElseThrow().getAsString());
        assertNull(TrashCanInventory.filter(before, item("bread", "", 20)));
        assertNull(TrashCanInventory.filter(TrashCanInventory.empty(), item("apple", "", 1)));
    }
    @Test void rejectsInvalidCountsAndUnserializableItems() {
        assertThrows(IllegalArgumentException.class, () -> TrashCanInventory.insert(TrashCanInventory.empty(), item("apple", "", 65)));
        assertThrows(IllegalArgumentException.class, () -> TrashCanInventory.insert(TrashCanInventory.empty(), item("diamond_sword", "", 2)));
        try (var nbt = mockStatic(BlockEntityNbt.class)) {
            assertThrows(IllegalArgumentException.class, () -> TrashCanInventory.write(new Item[]{item("apple", "", 1), Item.empty(), Item.empty()}));
        }
    }
    @Test void rejectsDuplicateOutOfRangeAndUnreadableSavedSlots() {
        try (var nbt = mockStatic(BlockEntityNbt.class)) {
            Item apple = item("apple", "", 1);
            nbt.when(() -> BlockEntityNbt.getItem(any(), eq("item"), anyInt())).thenReturn(apple);
            CompoundTag entry = new CompoundTag(); entry.putInt("slot", 1); entry.put("item", new CompoundTag());
            ListTag list = new ListTag(); list.add(entry); list.add(entry.deepClone());
            CompoundTag data = new CompoundTag(); data.put("items", list);
            assertThrows(IllegalArgumentException.class, () -> TrashCanInventory.read(data));
            list.remove(1); entry.putInt("slot", 3);
            assertThrows(IllegalArgumentException.class, () -> TrashCanInventory.read(data));
            entry.putInt("slot", 1);
            Item[] restored = TrashCanInventory.read(data);
            assertTrue(restored[0].isEmpty()); assertEquals(1, restored[1].count()); assertTrue(restored[2].isEmpty());
            Item air = Item.empty();
            nbt.when(() -> BlockEntityNbt.getItem(any(), eq("item"), anyInt())).thenReturn(air);
            assertThrows(IllegalArgumentException.class, () -> TrashCanInventory.read(data));
        }
    }
}
