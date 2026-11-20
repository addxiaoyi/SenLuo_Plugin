package net.kaleidoscope.cookery.item.behavior;

import net.kaleidoscope.cookery.plugin.CeResourceReadGate;
import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockHitResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.ExistingBlock;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceCropSeedItemBehaviorTest {
    private final ResourceOperationCoordinator operations = new ResourceOperationCoordinator();
    private final CeResourceReadGate gate = new CeResourceReadGate(operations, () -> true);
    private final KaleidoscopeCookeryPlugin plugin = mock(KaleidoscopeCookeryPlugin.class);
    private final SourceCropSeedItemBehavior seed = new SourceCropSeedItemBehavior(Key.of("kaleidoscopecookery:tomato_crop"));

    SourceCropSeedItemBehaviorTest() {
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.sourceCropReadGate()).thenReturn(gate);
    }

    @Test void unavailableResourcesRejectBeforeAnyPlacementContextReads() {
        var context = mock(UseOnContext.class);
        try (var cookery = mockStatic(KaleidoscopeCookeryPlugin.class, CALLS_REAL_METHODS)) {
            cookery.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            assertEquals(InteractionResult.FAIL, seed.useOnBlock(context));
            assertTrue(gate.publish(new Object(), () -> true));
            try (var writer = operations.acquire()) {
                assertEquals(InteractionResult.FAIL, seed.useOnBlock(context));
                assertTrue(operations.isBusy());
            }
            verifyNoInteractions(context);
        }
    }

    @Test void ceContextConstructionAndPlacementShareOneLeaseAndTheOriginalInteraction() {
        assertTrue(gate.publish(new Object(), () -> true));
        var level = mock(World.class);
        var player = mock(Player.class);
        var stack = mock(Item.class);
        var soil = mock(ExistingBlock.class);
        var against = new BlockPos(3, 65, 2);
        var plant = against.relative(Direction.UP);
        var hit = new BlockHitResult(Vec3d.atCenterOf(against), Direction.UP, against, false);
        when(level.getBlock(against)).thenAnswer(call -> {
            assertThrows(ResourceOperationCoordinator.BusyException.class, operations::acquire);
            return soil;
        });

        var behavior = spy(seed);
        try (var cookery = mockStatic(KaleidoscopeCookeryPlugin.class, CALLS_REAL_METHODS)) {
            cookery.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            for (var hand : new InteractionHand[] {InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND}) {
                doAnswer(call -> {
                    assertThrows(ResourceOperationCoordinator.BusyException.class, operations::acquire);
                    BlockPlaceContext placement = call.getArgument(0);
                    assertSame(hit, placement.getHitResult());
                    assertSame(stack, placement.getItem());
                    assertSame(player, placement.getPlayer());
                    assertSame(hand, placement.getHand());
                    assertEquals(against, placement.getAgainstPos());
                    assertEquals(plant, placement.getClickedPos());
                    return InteractionResult.SUCCESS;
                }).when(behavior).place(any(BlockPlaceContext.class));
                var context = new UseOnContext(level, player, hand, stack, hit);
                assertEquals(InteractionResult.SUCCESS, behavior.useOnBlock(context));
                assertFalse(operations.isBusy());
            }
        }
        verify(soil, times(2)).canBeReplaced(any(BlockPlaceContext.class));
    }
}
