package net.kaleidoscope.cookery.block.behavior;

import net.momirealms.craftengine.core.block.*;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EightImmortalsTableBehaviorTest {
    private final Property<Direction> facing = mock(Property.class);
    private final Property<String> part = mock(Property.class);
    private final EightImmortalsTableBehavior behavior = new EightImmortalsTableBehavior(mock(BlockDefinition.class), facing, part);
    @Test void allFourSourceFacingsHaveCorrectLeftTopAndAnchorInverse() {
        BlockPos anchor = new BlockPos(8, 70, 8);
        int[][] expected = {{-1,0,0,-1},{1,0,0,1},{0,-1,1,0},{0,1,-1,0}};
        Direction[] directions = {Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST};
        for (int i = 0; i < directions.length; i++) {
            Direction direction = directions[i]; int[] d = expected[i];
            assertEquals(new BlockPos(8+d[0],70,8+d[1]), EightImmortalsTableBehavior.cell(anchor,direction,"left_bottom"));
            assertEquals(new BlockPos(8+d[2],70,8+d[3]), EightImmortalsTableBehavior.cell(anchor,direction,"right_top"));
            assertEquals(new BlockPos(8+d[0]+d[2],70,8+d[1]+d[3]), EightImmortalsTableBehavior.cell(anchor,direction,"left_top"));
            for (String name : EightImmortalsTableBehavior.PARTS) {
                var state = mock(ImmutableBlockState.class); when(state.getNullable(facing)).thenReturn(direction); when(state.getNullable(part)).thenReturn(name);
                assertEquals(anchor, behavior.anchor(EightImmortalsTableBehavior.cell(anchor,direction,name),state));
            }
            assertEquals(4, Set.copyOf(EightImmortalsTableBehavior.PARTS.stream().map(p -> EightImmortalsTableBehavior.cell(anchor,direction,p)).toList()).size());
        }
    }
    @Test void placementKeepsPlayerFacingAndAlwaysUsesRightBottom() {
        var context = mock(BlockPlaceContext.class); var state = mock(ImmutableBlockState.class); var rotated = mock(ImmutableBlockState.class); var result = mock(ImmutableBlockState.class);
        for (Direction direction : new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}) {
            when(context.getHorizontalDirection()).thenReturn(direction); when(state.with(facing,direction)).thenReturn(rotated);
            when(rotated.with(part,"right_bottom")).thenReturn(result);
            assertSame(result,behavior.updateStateForPlacement(context,state)); verify(state).with(facing,direction);
        }
    }
    @Test void invalidPartAndVerticalFacingAreRejected() {
        var pos = new BlockPos(0,0,0);
        assertThrows(IllegalArgumentException.class,()->EightImmortalsTableBehavior.cell(pos,Direction.UP,"right_bottom"));
        assertThrows(IllegalArgumentException.class,()->EightImmortalsTableBehavior.cell(pos,Direction.NORTH,"center"));
    }
}
