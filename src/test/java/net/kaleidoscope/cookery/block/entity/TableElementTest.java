package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.entity.render.ItemDisplayPackets;
import net.kaleidoscope.cookery.block.entity.render.ItemDisplaySet;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.World;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TableElementTest {
    @Test void contentsUseFixedRenderingAndClearingSlotsRemovesOnlyTheirDisplays() {
        var controller = mock(TableController.class);
        var state = mock(ImmutableBlockState.class);
        var entity = mock(BlockEntity.class);
        entity.world = mock(CEWorld.class);
        try {
            var field = BlockEntity.class.getDeclaredField("pos"); field.setAccessible(true);
            field.set(entity, new BlockPos(8, 64, 8));
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        when(entity.world.world()).thenReturn(mock(World.class));
        when(controller.blockEntity()).thenReturn(entity);
        var carpet = mock(Item.class);
        var food = mock(Item.class);
        var block = mock(Item.class); when(block.isBlockItem()).thenReturn(true);
        when(controller.displayedItems()).thenReturn(List.of(food, block));
        when(controller.line(state)).thenReturn(0);
        var packets = mock(ItemDisplayPackets.class, RETURNS_SELF);
        try (var builders = mockStatic(ItemDisplayPackets.class);
             var inventory = mockStatic(InventoryUtils.class);
             var sets = mockConstruction(ItemDisplaySet.class)) {
            builders.when(ItemDisplayPackets::builder).thenReturn(packets);
            builders.when(() -> ItemDisplayPackets.at(any())).thenReturn(packets);
            inventory.when(() -> InventoryUtils.createOrEmpty(any())).thenReturn(carpet);
            var element = new TableElement(controller);
            var displays = sets.constructed().getFirst();
            element.rebuild(state);
            verify(displays, times(3)).setPackets(anyInt(), any(), any());
            verify(displays).clear(3); verify(displays).clear(4);
            verify(packets, times(2)).itemTransform((byte) 8);
            verify(packets, times(2)).scale(.65f);
            verify(packets).translation(eq(.1625f), eq(.8125f), eq(-.065f));
            verify(packets).translation(eq(-.1625f), floatThat(y -> Math.abs(y-.6565f)<1e-6), eq(.065f));
            var rotations = org.mockito.ArgumentCaptor.forClass(Quaternionf.class);
            verify(packets, times(3)).leftRotation(rotations.capture());
            Vector3f facing = rotations.getAllValues().getFirst().transform(new Vector3f(0, 0, 1));
            assertEquals(-1, facing.z, 1e-6);
            clearInvocations(displays, packets);
            when(controller.displayedItems()).thenReturn(List.of());
            element.rebuild(state);
            for (int i = 1; i <= 4; i++) verify(displays).clear(i);
            verify(displays).setPackets(eq(0), any(), any());
        }
    }
}
