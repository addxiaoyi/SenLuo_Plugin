package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.kaleidoscope.cookery.plugin.CeResourceReadGate;
import net.kaleidoscope.cookery.plugin.SourceCropSupportRecovery;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceCropBehaviorTest {
    private SourceCropBehavior behavior(IntegerProperty age, SourceCropBehavior.Crop crop) throws Exception {
        return behavior(mock(BlockDefinition.class), age, crop);
    }

    private SourceCropBehavior behavior(BlockDefinition block, IntegerProperty age, SourceCropBehavior.Crop crop) throws Exception {
        var constructor = SourceCropBehavior.class.getDeclaredConstructor(BlockDefinition.class, IntegerProperty.class, SourceCropBehavior.Crop.class);
        constructor.setAccessible(true);
        return constructor.newInstance(block, age, crop);
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

    @Test void productionRandomTickFailsClosedBeforeReadingStateWithoutAPublishedGate() throws Exception {
        var behavior = behavior(IntegerProperty.create("age", 0, 7, 0), SourceCropBehavior.Crop.TOMATO);
        var plugin = mock(KaleidoscopeCookeryPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.sourceCropReadGate()).thenReturn(new CeResourceReadGate(new ResourceOperationCoordinator(), () -> true));
        try (var plugins = mockStatic(KaleidoscopeCookeryPlugin.class, CALLS_REAL_METHODS)) {
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            assertDoesNotThrow(() -> behavior.randomTick(null, new Object[0]));
            when(plugin.isEnabled()).thenReturn(false);
            when(plugin.sourceCropReadGate()).thenReturn(null);
            assertDoesNotThrow(() -> behavior.randomTick(null, new Object[0]));
        }
    }

    @Test void randomTickReadsCeStateOnlyAfterAcquiringThePublishedLease() throws Exception {
        var behavior = behavior(IntegerProperty.create("age", 0, 7, 0), SourceCropBehavior.Crop.TOMATO);
        var plugin = mock(KaleidoscopeCookeryPlugin.class);
        var operations = new ResourceOperationCoordinator();
        var gate = new CeResourceReadGate(operations, () -> true);
        assertTrue(gate.publish(new Object(), () -> true));
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.sourceCropReadGate()).thenReturn(gate);
        Object state = new Object();
        try (var plugins = mockStatic(KaleidoscopeCookeryPlugin.class, CALLS_REAL_METHODS);
             var states = mockStatic(BlockStateUtils.class)) {
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            states.when(() -> BlockStateUtils.getOptionalCustomBlockState(state)).thenAnswer(call -> {
                assertThrows(ResourceOperationCoordinator.BusyException.class, operations::acquire);
                return Optional.empty();
            });
            behavior.randomTick(null, new Object[]{state});
            states.verify(() -> BlockStateUtils.getOptionalCustomBlockState(state));
        }
        try (var writer = operations.acquire()) { assertTrue(operations.isBusy()); }
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

    @Test void unavailableSupportTicksAreDeferredBeforeAnyCeStateReads() throws Exception {
        var behavior = behavior(IntegerProperty.create("age", 0, 7, 0), SourceCropBehavior.Crop.TOMATO);
        var plugin = mock(KaleidoscopeCookeryPlugin.class);
        var recovery = mock(SourceCropSupportRecovery.class);
        var gate = new CeResourceReadGate(new ResourceOperationCoordinator(), () -> true);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.sourceCropReadGate()).thenReturn(gate);
        when(plugin.sourceCropSupportRecovery()).thenReturn(recovery);
        Object level = new Object(), pos = new Object(), rawState = new Object();
        try (var plugins = mockStatic(KaleidoscopeCookeryPlugin.class, CALLS_REAL_METHODS);
             var states = mockStatic(BlockStateUtils.class)) {
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            behavior.tick(null, new Object[]{rawState, level, pos});
            verify(recovery).deferTick(level, pos);
            assertTrue(behavior.canSurvive(null, rawState, level, pos));
            states.verifyNoInteractions();
        }
    }

    @Test void unavailableShapeUpdatesKeepTheOriginalStateAndQueueRecovery() throws Exception {
        var behavior = behavior(IntegerProperty.create("age", 0, 7, 0), SourceCropBehavior.Crop.TOMATO);
        var plugin = mock(KaleidoscopeCookeryPlugin.class);
        var recovery = mock(SourceCropSupportRecovery.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.sourceCropReadGate()).thenReturn(new CeResourceReadGate(new ResourceOperationCoordinator(), () -> true));
        when(plugin.sourceCropSupportRecovery()).thenReturn(recovery);
        Object raw = new Object();
        Object[] args = new Object[12];
        java.util.Arrays.fill(args, raw);
        try (var plugins = mockStatic(KaleidoscopeCookeryPlugin.class, CALLS_REAL_METHODS);
             var states = mockStatic(BlockStateUtils.class)) {
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            assertSame(raw, behavior.updateShape(null, args));
            verify(recovery).deferTick(raw, raw);
            states.verifyNoInteractions();
        }
    }

    @Test void supportTickAndSurvivalRejectOldDefinitionsWhileHoldingTheRealLease() throws Exception {
        var behavior = behavior(IntegerProperty.create("age", 0, 7, 0), SourceCropBehavior.Crop.TOMATO);
        var plugin = mock(KaleidoscopeCookeryPlugin.class);
        var operations = new ResourceOperationCoordinator();
        var gate = new CeResourceReadGate(operations, () -> true);
        assertTrue(gate.publish(new Object(), () -> true));
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.sourceCropReadGate()).thenReturn(gate);
        var raw = new Object();
        var oldState = mock(ImmutableBlockState.class, RETURNS_DEEP_STUBS);
        try (var plugins = mockStatic(KaleidoscopeCookeryPlugin.class, CALLS_REAL_METHODS);
             var states = mockStatic(BlockStateUtils.class)) {
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            states.when(() -> BlockStateUtils.getOptionalCustomBlockState(raw)).thenAnswer(call -> {
                assertThrows(ResourceOperationCoordinator.BusyException.class, operations::acquire);
                return Optional.of(oldState);
            });
            behavior.tick(null, new Object[]{raw});
            assertTrue(behavior.canSurvive(null, raw, null, null));
            assertFalse(operations.isBusy());
            states.verify(() -> BlockStateUtils.getOptionalCustomBlockState(raw), times(2));
        }
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

    @Test void offhandPickingCapturesItsOwnSourceDespiteASickleInTheMainHand() throws Exception {
        var definition = mock(BlockDefinition.class);
        var age = IntegerProperty.create("age", 0, 7, 0);
        var behavior = behavior(definition, age, SourceCropBehavior.Crop.TOMATO);
        var state = mock(ImmutableBlockState.class, RETURNS_DEEP_STUBS);
        var world = mock(World.class);
        var bukkitWorld = mock(org.bukkit.World.class);
        var block = mock(Block.class);
        var pos = new BlockPos(8, 64, 8);
        var player = mock(Player.class);
        var actor = mock(org.bukkit.entity.Player.class);
        var plugin = mock(KaleidoscopeCookeryPlugin.class);
        var resourceOperations = new ResourceOperationCoordinator();
        var readGate = new CeResourceReadGate(resourceOperations, () -> true);
        assertTrue(readGate.publish(new Object(), () -> true));
        var context = mock(UseOnContext.class);
        var sickle = mock(Item.class);
        when(sickle.id()).thenReturn(Key.of("kaleidoscopecookery:sickle"));
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(sickle);
        when(player.platformPlayer()).thenReturn(actor);
        when(actor.isOnline()).thenReturn(true);
        when(actor.isValid()).thenReturn(true);
        when(actor.getWorld()).thenReturn(bukkitWorld);
        when(actor.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.sourceCropReadGate()).thenReturn(readGate);
        when(context.getPlayer()).thenReturn(player);
        when(context.getLevel()).thenReturn(world);
        when(context.getClickedPos()).thenReturn(pos);
        when(context.getHand()).thenReturn(InteractionHand.OFF_HAND);
        when(world.platformWorld()).thenReturn(bukkitWorld);
        when(bukkitWorld.getBlockAt(pos.x(), pos.y(), pos.z())).thenReturn(block);
        when(bukkitWorld.isChunkLoaded(0, 0)).thenReturn(true);
        when(block.getWorld()).thenReturn(bukkitWorld);
        when(state.owner().value()).thenReturn(definition);
        doReturn(SourceCropGrowth.MAX_AGE).when(state).get(age);
        try (var bukkit = mockStatic(Bukkit.class); var blocks = mockStatic(CraftEngineBlocks.class);
             var plugins = mockStatic(KaleidoscopeCookeryPlugin.class, CALLS_REAL_METHODS); var protection = mockStatic(InteractGuard.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(bukkitWorld, 0, 0)).thenReturn(true);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(actor)).thenReturn(true);
            blocks.when(() -> CraftEngineBlocks.getCustomBlockState(block)).thenReturn(state);
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);

            assertEquals(InteractionResult.PASS, behavior.useOnBlock(context, state));
            protection.verify(() -> InteractGuard.canBreak(player, world, pos.x(), pos.y(), pos.z()));
            verify(player, atLeastOnce()).getItemInHand(InteractionHand.OFF_HAND);
            verify(player, never()).getItemInHand(InteractionHand.MAIN_HAND);
            verify(player, never()).swingHand(any());

            protection.when(() -> InteractGuard.canBreak(player, world, pos.x(), pos.y(), pos.z())).thenAnswer(call -> {
                assertThrows(ResourceOperationCoordinator.BusyException.class, resourceOperations::acquire);
                when(player.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(sickle);
                return true;
            });
            assertEquals(InteractionResult.PASS, behavior.useOnBlock(context, state));
            protection.verify(() -> InteractGuard.canBreak(player, world, pos.x(), pos.y(), pos.z()), times(2));
            verify(state, never()).with(eq(age), anyInt());
            verify(world, never()).minecraftWorld();
            verify(player, never()).swingHand(any());
        }
    }
}
