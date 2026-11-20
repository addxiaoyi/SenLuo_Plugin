package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.block.entity.LongBenchController;
import net.momirealms.craftengine.core.block.*;
import net.momirealms.craftengine.core.block.entity.*;
import net.momirealms.craftengine.core.block.property.*;
import net.momirealms.craftengine.core.entity.player.*;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.*;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LongBenchBehaviorTest {
    private final BlockDefinition definition = mock(BlockDefinition.class);
    private final Property<Direction.Axis> axis = mock(Property.class);
    private final IntegerProperty position = mock(IntegerProperty.class);
    private final LongBenchBehavior behavior = new LongBenchBehavior(definition, axis, position);
    private ImmutableBlockState state(Direction.Axis orientation, BlockDefinition owner) {
        var state = mock(ImmutableBlockState.class, RETURNS_DEEP_STUBS);
        doReturn(orientation).when(state).getNullable(axis); when(state.owner().value()).thenReturn(owner);
        when(state.with(eq(position), anyInt())).thenReturn(state);
        return state;
    }
    @Test void placementIsPerpendicularToAllFourPlayerDirections() {
        assertEquals(Direction.Axis.X, LongBenchBehavior.placementAxis(Direction.NORTH));
        assertEquals(Direction.Axis.X, LongBenchBehavior.placementAxis(Direction.SOUTH));
        assertEquals(Direction.Axis.Z, LongBenchBehavior.placementAxis(Direction.WEST));
        assertEquals(Direction.Axis.Z, LongBenchBehavior.placementAxis(Direction.EAST));
    }
    @Test void eitherAxisUsesNegativeLeftPositiveRightBothMiddleAndNeitherSingle() {
        for (Direction.Axis orientation : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
            var current = state(orientation, definition); var neighbor = state(orientation, definition);
            behavior.connected(current, null, null); verify(current).with(position, 0);
            behavior.connected(current, neighbor, null); verify(current).with(position, 1);
            behavior.connected(current, neighbor, neighbor); verify(current).with(position, 2);
            behavior.connected(current, null, neighbor); verify(current).with(position, 3);
            verify(current, never()).with(eq(axis), any());
        }
    }
    @Test void crossAxisAndDifferentBlocksDoNotJoinEvenWhenSingle() {
        var current = state(Direction.Axis.X, definition);
        var crossAxis = state(Direction.Axis.Z, definition);
        var other = state(Direction.Axis.X, mock(BlockDefinition.class));
        behavior.connected(current, crossAxis, other); verify(current).with(position, 0);
        var matching = state(Direction.Axis.X, definition);
        behavior.connected(current, crossAxis, matching); verify(current).with(position, 3);
        verify(current, never()).with(eq(axis), any());
    }
    @Test void bothHandsReachTheSameControllerButSneakAndForeignRegionDoNotReadStorage() {
        var player = mock(Player.class); var context = mock(UseOnContext.class);
        var world = mock(World.class); var platform = mock(org.bukkit.World.class); var storage = mock(CEWorld.class);
        var state = state(Direction.Axis.X, definition); when(state.behavior()).thenReturn(behavior);
        // A mock entity avoids creating the live seat in its constructor.
        var entityBehavior = mock(LongBenchBehavior.class); when(entityBehavior.createBlockEntityController(any())).thenReturn(mock(BlockEntityController.class));
        when(state.behavior()).thenReturn(entityBehavior);
        var entity = new BlockEntity(new BlockPos(8, 64, 8), state);
        var controller = mock(LongBenchController.class); when(entity.controller.get(LongBenchController.class, 3)).thenReturn(controller);
        when(controller.interact(player)).thenReturn(InteractionResult.SUCCESS_AND_CANCEL); behavior.initControllerId(3);
        when(context.getPlayer()).thenReturn(player); when(context.getClickedPos()).thenReturn(entity.pos);
        when(context.getLevel()).thenReturn(world); when(world.platformWorld()).thenReturn(platform); when(world.storageWorld()).thenReturn(storage);
        when(storage.getBlockEntityAtIfLoaded(entity.pos)).thenReturn(entity);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(platform, 0, 0)).thenReturn(true);
            for (InteractionHand hand : InteractionHand.values()) {
                when(context.getHand()).thenReturn(hand);
                assertEquals(InteractionResult.SUCCESS_AND_CANCEL, behavior.useOnBlock(context, state));
            }
            assertEquals(InteractionResult.SUCCESS_AND_CANCEL, behavior.useWithoutItem(context, state));
            verify(controller, times(3)).interact(player); clearInvocations(storage);
            when(player.isSecondaryUseActive()).thenReturn(true);
            assertEquals(InteractionResult.PASS, behavior.useOnBlock(context, state));
            when(player.isSecondaryUseActive()).thenReturn(false);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(platform, 0, 0)).thenReturn(false);
            assertEquals(InteractionResult.FAIL, behavior.useOnBlock(context, state)); verifyNoInteractions(storage);
        }
    }
}
