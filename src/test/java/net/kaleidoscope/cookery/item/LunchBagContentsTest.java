package net.kaleidoscope.cookery.item;

import net.kaleidoscope.cookery.util.BlockEntityNbt;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.libraries.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LunchBagContentsTest {
    @Test void corruptRecordNeverDecodesAsAnEmptyOrPartiallyValidBag() {
        Item bag = mock(Item.class);
        ListTag list = new ListTag();
        list.add(new CompoundTag());
        when(bag.getComponentAsSparrowTag(any())).thenReturn(list);
        assertThrows(IllegalArgumentException.class, () -> LunchBagContents.decode(list, entry -> null));
        verify(bag, never()).setSparrowTagComponent(any(), any());
    }

    @Test void oldSixteenSlotContentsRetainEveryStack() {
        ListTag list = new ListTag();
        for (int i = 0; i < 16; i++) list.add(new CompoundTag());
        Item food = LunchBagInventoryTest.stack("quality", 64, 64);
        assertEquals(16, LunchBagContents.decode(list, entry -> food).size());
    }

    @Test void overCapacityAndOverstackedRecordsAreRejected() {
        ListTag oversized = new ListTag();
        for (int i = 0; i < 25; i++) oversized.add(new CompoundTag());
        assertThrows(IllegalArgumentException.class, () -> LunchBagContents.decode(oversized, entry -> {
            fail("decoder must not see an over-capacity record"); return null;
        }));
        ListTag list = new ListTag(); list.add(new CompoundTag());
        assertThrows(IllegalArgumentException.class, () -> LunchBagContents.decode(list,
                entry -> LunchBagInventoryTest.stack("food", 65, 64)));
        assertThrows(IllegalArgumentException.class, () -> LunchBagContents.decode(new CompoundTag(), entry -> null));
    }

    @Test void failedSerializationDoesNotReplaceExistingContents() {
        Item bag = mock(Item.class);
        try (var codec = mockStatic(BlockEntityNbt.class)) {
            codec.when(() -> BlockEntityNbt.saveItems(anyList())).thenReturn(new ListTag());
            assertThrows(IllegalArgumentException.class, () -> LunchBagContents.write(bag,
                    List.of(LunchBagInventoryTest.stack("food", 64, 64))));
            verify(bag, never()).setSparrowTagComponent(any(), any());
        }
    }
}
