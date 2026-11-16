package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.BambooTrayBehavior;
import net.kaleidoscope.cookery.block.entity.render.ItemDisplayPackets;
import net.kaleidoscope.cookery.block.entity.render.ItemDisplaySet;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.world.BlockPos;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BambooTrayElementTest {
    @Test
    void stackCopiesUseFixedFlatRenderingAndRemoveOnlySurplusDisplays() {
        var empty = mock(Item.class);
        when(empty.isEmpty()).thenReturn(true);
        var amount = new AtomicInteger(64);
        var food = mock(Item.class);
        when(food.count()).thenAnswer(call -> amount.get());
        when(food.copy()).thenAnswer(call -> {
            var snapshot = mock(Item.class);
            when(snapshot.count()).thenReturn(amount.get());
            when(snapshot.isSimilar(food)).thenReturn(true);
            return snapshot;
        });
        var controller = mock(BambooTrayController.class);
        var entity = entity();
        when(controller.blockEntity()).thenReturn(entity);
        when(controller.item(anyInt())).thenReturn(empty);
        when(controller.item(0)).thenReturn(food);
        var packets = mock(ItemDisplayPackets.class, RETURNS_SELF);
        try (var builders = mockStatic(ItemDisplayPackets.class);
             var sets = mockConstruction(ItemDisplaySet.class)) {
            builders.when(() -> ItemDisplayPackets.at(any())).thenReturn(packets);
            var element = new BambooTrayElement(controller);
            var displays = sets.constructed().getFirst();
            // The old implementation only inspected size() to iterate its four slots.
            when(displays.size()).thenReturn(4);

            element.refresh();
            verify(displays, times(5)).setPackets(anyInt(), any(), any());
            verify(packets, times(5)).itemTransform((byte) 8);
            verify(packets, times(5)).scale(.5f);
            var rotation = org.mockito.ArgumentCaptor.forClass(Quaternionf.class);
            verify(packets, times(5)).leftRotation(rotation.capture());
            for (var quaternion : rotation.getAllValues()) {
                Vector3f normal = quaternion.transform(new Vector3f(0, 0, 1));
                assertEquals(0, normal.x, 1e-6);
                assertEquals(-1, normal.y, 1e-6);
                assertEquals(0, normal.z, 1e-6);
            }

            clearInvocations(displays, packets);
            element.refresh();
            verifyNoInteractions(displays, packets);

            amount.set(1);
            element.refresh();
            verify(displays).setPackets(eq(0), any(), any());
            for (int index = 1; index < 5; index++) verify(displays).clear(index);
            verify(displays, times(4)).clear(anyInt());

            clearInvocations(displays, packets);
            when(controller.item(0)).thenReturn(empty);
            element.refresh();
            verify(displays).clear(0);
            verifyNoInteractions(packets);
            clearInvocations(displays);
            element.refresh();
            verifyNoInteractions(displays);
        }
    }

    @Test
    void aComponentChangeWithTheSameCountRebuildsItsOwnSlot() {
        var empty = mock(Item.class);
        when(empty.isEmpty()).thenReturn(true);
        var food = mock(Item.class);
        when(food.count()).thenReturn(2);
        when(food.copy()).thenReturn(food);
        when(food.isSimilar(food)).thenReturn(true);
        var dyed = mock(Item.class);
        when(dyed.count()).thenReturn(2);
        when(dyed.copy()).thenReturn(dyed);
        var controller = mock(BambooTrayController.class);
        var entity = entity();
        when(controller.blockEntity()).thenReturn(entity);
        when(controller.item(anyInt())).thenReturn(empty);
        when(controller.item(0)).thenReturn(food);
        when(controller.item(1)).thenReturn(food);
        var packets = mock(ItemDisplayPackets.class, RETURNS_SELF);
        try (var builders = mockStatic(ItemDisplayPackets.class);
             var sets = mockConstruction(ItemDisplaySet.class)) {
            builders.when(() -> ItemDisplayPackets.at(any())).thenReturn(packets);
            var element = new BambooTrayElement(controller);
            var displays = sets.constructed().getFirst();
            when(displays.size()).thenReturn(4);
            element.refresh();
            clearInvocations(displays, packets);

            when(controller.item(1)).thenReturn(dyed);
            element.refresh();
            verify(displays).setPackets(eq(5), any(), any());
            verify(displays).setPackets(eq(6), any(), any());
            verify(displays, times(2)).setPackets(anyInt(), any(), any());
            verify(displays, never()).clear(anyInt());
        }
    }

    private static BlockEntity entity() {
        var behavior = mock(BambooTrayBehavior.class);
        when(behavior.createBlockEntityController(any())).thenReturn(mock(BlockEntityController.class));
        var state = mock(ImmutableBlockState.class);
        when(state.behavior()).thenReturn(behavior);
        return new BlockEntity(new BlockPos(8, 64, 8), state);
    }
}
