package net.kaleidoscope.cookery.block.behavior;

import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceCropBehaviorTest {
    private SourceCropBehavior behavior(IntegerProperty age, SourceCropBehavior.Crop crop) throws Exception {
        var constructor = SourceCropBehavior.class.getDeclaredConstructor(BlockDefinition.class, IntegerProperty.class, SourceCropBehavior.Crop.class);
        constructor.setAccessible(true);
        return constructor.newInstance(mock(BlockDefinition.class), age, crop);
    }

    @Test void chiliHasExactlyOneGreenRollAndFourRedRolls() {
        assertEquals(Key.of("kaleidoscopecookery:green_chili"), SourceCropBehavior.Crop.CHILI.pickResult(0));
        for (int roll = 1; roll < 5; roll++)
            assertEquals(Key.of("kaleidoscopecookery:red_chili"), SourceCropBehavior.Crop.CHILI.pickResult(roll));
        for (int roll = 0; roll < 5; roll++)
            assertEquals(Key.of("kaleidoscopecookery:tomato"), SourceCropBehavior.Crop.TOMATO.pickResult(roll));
    }

    @Test void lettuceAlwaysPassesRightClickBeforeAccessingTheWorld() throws Exception {
        var behavior = behavior(mock(IntegerProperty.class), SourceCropBehavior.Crop.LETTUCE);
        var context = mock(UseOnContext.class);
        assertEquals(InteractionResult.PASS, behavior.useOnBlock(context, mock(ImmutableBlockState.class)));
        verifyNoInteractions(context);
        assertTrue(SourceCropBehavior.Crop.TOMATO.canPick());
        assertTrue(SourceCropBehavior.Crop.CHILI.canPick());
        assertFalse(SourceCropBehavior.Crop.LETTUCE.canPick());
    }

    @Test void randomTicksAndNativeBonemealStopAtMaturity() throws Exception {
        var age = IntegerProperty.create("age", 0, 7, 0);
        var behavior = behavior(age, SourceCropBehavior.Crop.TOMATO);
        var state = mock(ImmutableBlockState.class);
        Object raw = new Object();
        try (var states = mockStatic(BlockStateUtils.class)) {
            states.when(() -> BlockStateUtils.getOptionalCustomBlockState(raw)).thenReturn(Optional.of(state));
            for (int value = 0; value < 8; value++) {
                when(state.get(age)).thenReturn(value);
                assertEquals(value < 7, behavior.canRandomlyTick(state));
                assertEquals(value < 7, behavior.isValidBonemealTarget(null, new Object[]{null, null, raw}));
            }
            assertTrue(behavior.isBonemealSuccess(null, new Object[0]));
        }
    }

    @Test void sickleLootCarriesBlockAndPlayerButNoHeldFortuneTool() {
        var position = mock(WorldPosition.class);
        var state = mock(ImmutableBlockState.class);
        var player = mock(Player.class);
        var context = SourceCropBehavior.sickleLootContext(position, state, player);
        assertSame(position, context.getOrNull(DirectContextParameters.POSITION));
        assertSame(state, context.getOrNull(DirectContextParameters.CUSTOM_BLOCK_STATE));
        assertSame(player, context.getOrNull(DirectContextParameters.PLAYER));
        assertFalse(context.has(DirectContextParameters.ITEM_IN_HAND));
        verifyNoInteractions(player);
    }

    @Test void foreignRegionAndUnloadedChunkAreRejectedBeforeBlockReads() {
        var world = mock(org.bukkit.World.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            assertFalse(SourceCropBehavior.owned(world, 16, 0));
            verifyNoInteractions(world);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 1, 0)).thenReturn(true);
            assertFalse(SourceCropBehavior.owned(world, 16, 0));
            verify(world).isChunkLoaded(1, 0);
            verifyNoMoreInteractions(world);
        }
    }
}
