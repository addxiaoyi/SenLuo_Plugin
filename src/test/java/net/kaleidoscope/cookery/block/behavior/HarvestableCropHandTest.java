package net.kaleidoscope.cookery.block.behavior;

import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.World;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HarvestableCropHandTest {
    @ParameterizedTest
    @EnumSource(InteractionHand.class)
    void handAwareEntryPointDelegatesToLegacyCropAndPreservesItsResult(InteractionHand hand) {
        var world = mock(World.class);
        var pos = new BlockPos(8, 64, 8);
        var state = mock(ImmutableBlockState.class);
        var player = mock(Player.class);
        for (boolean result : new boolean[]{true, false}) {
            var calls = new AtomicInteger();
            HarvestableCrop crop = new HarvestableCrop() {
                @Override public boolean harvest(World actualWorld, BlockPos actualPos,
                                                 ImmutableBlockState actualState, Player actualPlayer) {
                    assertSame(world, actualWorld);
                    assertSame(pos, actualPos);
                    assertSame(state, actualState);
                    assertSame(player, actualPlayer);
                    calls.incrementAndGet();
                    return result;
                }
            };
            assertEquals(result, crop.harvest(world, pos, state, player, hand));
            assertEquals(1, calls.get());
        }
    }
}
