package net.kaleidoscope.cookery.plugin;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import net.momirealms.craftengine.core.world.chunk.CESection;
import net.momirealms.craftengine.core.world.chunk.PalettedContainer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceCropSupportRecoveryTest {
    @Test void closedAndBusyGatesRetryWithoutReadingCeChunks() {
        try (Fixture f = new Fixture()) {
            f.recovery.enqueue(f.world, -2, 3);
            f.recovery.enqueue(f.world, -2, 3);
            f.step();
            verifyNoInteractions(f.lookup);
            assertTrue(f.positions.isEmpty());
            assertTrue(f.gate.publish(new Object(), () -> true));
            try (var writer = f.operations.acquire()) {
                f.step();
                verifyNoInteractions(f.lookup);
            }
            f.step();
            assertEquals(List.of(new BlockPos(-32, -64, 48)), f.positions);
            assertFalse(f.operations.isBusy());
        }
    }

    @Test void eachSliceVisitsAtMost256EntriesAndPreservesNegativeChunkAndSectionCoordinates() {
        try (Fixture f = new Fixture()) {
            f.cropIndexes = Set.of(0, 255, 256, 4095);
            assertTrue(f.gate.publish(new Object(), () -> true));
            f.recovery.enqueue(f.world, -2, 3);
            f.step();
            assertEquals(List.of(new BlockPos(-32, -64, 48), new BlockPos(-17, -64, 63)), f.positions);
            verify(f.section, times(SourceCropSupportRecovery.BLOCK_BUDGET)).getBlockState(anyInt());
            f.step();
            assertEquals(new BlockPos(-32, -63, 48), f.positions.getLast());
            for (int i = 2; i < 16; i++) f.step();
            assertEquals(new BlockPos(-17, -49, 63), f.positions.getLast());
            assertEquals(4, f.positions.size());
            int calls = f.callbacks.size();
            f.recovery.dispatch();
            assertEquals(calls, f.callbacks.size(), "Completed chunks must leave the queue");
            assertFalse(f.operations.isBusy());
        }
    }

    @Test void globalDispatchHasABoundedBudgetEvenForStaleJobs() {
        try (Fixture f = new Fixture()) {
            for (int x = 0; x < 10; x++) f.recovery.enqueue(f.world, x, 3);
            f.recovery.dispatch();
            assertEquals(SourceCropSupportRecovery.DISPATCH_BUDGET, f.callbacks.size());
            f.recovery.dispatch();
            assertEquals(2 * SourceCropSupportRecovery.DISPATCH_BUDGET, f.callbacks.size());
        }
    }

    @Test void foreignRegionsDeferBeforeChunkOrRegistryReads() {
        try (Fixture f = new Fixture()) {
            assertTrue(f.gate.publish(new Object(), () -> true));
            f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(f.world, -2, 3)).thenReturn(false);
            f.recovery.enqueue(f.world, -2, 3);
            f.step();
            verify(f.world, never()).isChunkLoaded(anyInt(), anyInt());
            verifyNoInteractions(f.lookup);
            f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(f.world, -2, 3)).thenReturn(true);
            f.step();
            assertEquals(1, f.positions.size());
        }
    }

    @Test void unloadedChunksAndReplacedWorldsRetireWithoutLoadingAnything() {
        try (Fixture f = new Fixture()) {
            assertTrue(f.gate.publish(new Object(), () -> true));
            when(f.world.isChunkLoaded(-2, 3)).thenReturn(false);
            f.recovery.enqueue(f.world, -2, 3);
            f.step();
            verifyNoInteractions(f.lookup);
            when(f.world.isChunkLoaded(-2, 3)).thenReturn(true);
            f.bukkit.when(() -> Bukkit.getWorld(f.id)).thenReturn(mock(World.class));
            f.recovery.enqueue(f.world, -2, 3);
            f.step();
            verifyNoInteractions(f.lookup);
            assertTrue(f.positions.isEmpty());
        }
    }

    @Test void missingCeChunkRetriesInsteadOfLosingTheReloadReplay() {
        try (Fixture f = new Fixture()) {
            assertTrue(f.gate.publish(new Object(), () -> true));
            when(f.chunk.isLoaded()).thenReturn(false);
            f.recovery.enqueue(f.world, -2, 3);
            f.step();
            assertTrue(f.positions.isEmpty());
            when(f.chunk.isLoaded()).thenReturn(true);
            f.step();
            assertEquals(1, f.positions.size());
        }
    }

    @Test void unloadRetiresAnInFlightScanWithoutRemovingTheReplacement() {
        try (Fixture f = new Fixture()) {
            assertTrue(f.gate.publish(new Object(), () -> true));
            f.recovery.enqueue(f.world, -2, 3);
            f.recovery.dispatch();
            Consumer<ScheduledTask> old = f.callbacks.removeFirst();
            f.recovery.onChunkUnload(new ChunkUnloadEvent(f.bukkitChunk, true));
            f.recovery.enqueue(f.world, -2, 3);
            f.recovery.dispatch();
            old.accept(mock(ScheduledTask.class));
            verifyNoInteractions(f.lookup);
            f.runCallbacks();
            assertEquals(1, f.positions.size());
        }
    }

    @Test void newPublicationRestartsAnUnfinishedScanFromItsFirstSection() {
        try (Fixture f = new Fixture()) {
            f.cropIndexes = Set.of(0, 300);
            assertTrue(f.gate.publish(new Object(), () -> true));
            f.recovery.enqueue(f.world, -2, 3);
            f.step();
            assertEquals(1, f.positions.size());
            f.recovery.recheckLoadedChunks();
            f.step();
            assertEquals(2, f.positions.size());
            assertEquals(f.positions.getFirst(), f.positions.getLast());
            f.step();
            assertEquals(new BlockPos(-20, -63, 50), f.positions.getLast());
        }
    }

    @Test void emptyAndNonCropSectionsAreSkippedWithoutBlockLookups() {
        try (Fixture f = new Fixture()) {
            assertTrue(f.gate.publish(new Object(), () -> true));
            when(f.palette.hasAny(any())).thenReturn(false);
            f.recovery.enqueue(f.world, -2, 3);
            f.step();
            verify(f.section, never()).getBlockState(anyInt());
            assertTrue(f.positions.isEmpty());
        }
    }

    @Test void aDeferredSupportCallbackRevisitsPositionsAlreadyScanned() {
        try (Fixture f = new Fixture()) {
            f.cropIndexes = Set.of(0, 300);
            assertTrue(f.gate.publish(new Object(), () -> true));
            f.recovery.enqueue(f.world, -2, 3);
            f.step();
            f.recovery.requestRecheck(f.world, -2, 3);
            f.recovery.requestRecheck(f.world, -2, 3);
            f.step();
            assertEquals(2, f.positions.size());
            assertEquals(f.positions.getFirst(), f.positions.getLast());
            f.step();
            assertEquals(new BlockPos(-20, -63, 50), f.positions.getLast());
        }
    }

    @Test void aFailedSchedulerConsumerReleasesTheLeaseAndReportsTheChunk() {
        try (Fixture f = new Fixture()) {
            assertTrue(f.gate.publish(new Object(), () -> true));
            f.failScheduling = true;
            f.recovery.enqueue(f.world, -2, 3);
            f.step();
            assertFalse(f.operations.isBusy());
            verify(f.plugin.getLogger()).severe(contains("Crop support recovery failed"));
            try (var writer = f.operations.acquire()) { assertTrue(f.operations.isBusy()); }
        }
    }

    @Test void closeRetiresAlreadyDispatchedCallbacksBeforeWorldReads() {
        try (Fixture f = new Fixture()) {
            assertTrue(f.gate.publish(new Object(), () -> true));
            f.recovery.enqueue(f.world, -2, 3);
            f.recovery.dispatch();
            f.recovery.close();
            f.runCallbacks();
            verifyNoInteractions(f.lookup);
            verify(f.world, never()).isChunkLoaded(anyInt(), anyInt());
            f.recovery.enqueue(f.world, -2, 3);
            f.recovery.dispatch();
            assertTrue(f.callbacks.isEmpty());
        }
    }

    @Test void disablingThePluginStopsAnAlreadyDispatchedCallback() {
        try (Fixture f = new Fixture()) {
            assertTrue(f.gate.publish(new Object(), () -> true));
            f.recovery.enqueue(f.world, -2, 3);
            f.recovery.dispatch();
            when(f.plugin.isEnabled()).thenReturn(false);
            f.runCallbacks();
            verifyNoInteractions(f.lookup);
            verify(f.world, never()).isChunkLoaded(anyInt(), anyInt());
        }
    }

    @Test void thePaletteCannotScheduleACheckForAChangedNativeBlock() {
        World world = mock(World.class);
        org.bukkit.block.Block block = mock(org.bukkit.block.Block.class);
        var pos = new BlockPos(1, 64, 2);
        when(world.getBlockAt(1, 64, 2)).thenReturn(block);
        try (var blocks = mockStatic(CraftEngineBlocks.class)) {
            SourceCropSupportRecovery.scheduleCurrent(world, pos);
            var state = mock(ImmutableBlockState.class, RETURNS_DEEP_STUBS);
            when(state.owner().value().id()).thenReturn(Key.of("other:crop"));
            blocks.when(() -> CraftEngineBlocks.getCustomBlockState(block)).thenReturn(state);
            SourceCropSupportRecovery.scheduleCurrent(world, pos);
            verify(state, never()).behavior();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final KaleidoscopeCookeryPlugin plugin = mock(KaleidoscopeCookeryPlugin.class);
        final ResourceOperationCoordinator operations = new ResourceOperationCoordinator();
        final CeResourceReadGate gate = new CeResourceReadGate(operations, () -> true);
        final World world = mock(World.class);
        final UUID id = UUID.randomUUID();
        final Chunk bukkitChunk = mock(Chunk.class);
        final RegionScheduler scheduler = mock(RegionScheduler.class);
        final SourceCropSupportRecovery.ChunkLookup lookup = mock(SourceCropSupportRecovery.ChunkLookup.class);
        final CEChunk chunk = mock(CEChunk.class);
        final CESection section = mock(CESection.class);
        @SuppressWarnings("unchecked")
        final PalettedContainer<ImmutableBlockState> palette = mock(PalettedContainer.class);
        final ImmutableBlockState crop = mock(ImmutableBlockState.class, RETURNS_DEEP_STUBS);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final List<Consumer<ScheduledTask>> callbacks = new ArrayList<>();
        final List<BlockPos> positions = new ArrayList<>();
        Set<Integer> cropIndexes = Set.of(0);
        boolean failScheduling;
        final SourceCropSupportRecovery recovery = new SourceCropSupportRecovery(plugin, gate, lookup, (w, pos) -> {
            assertSame(world, w);
            assertThrows(ResourceOperationCoordinator.BusyException.class, operations::acquire);
            if (failScheduling) throw new IllegalStateException("schedule failed");
            positions.add(pos);
        });

        Fixture() {
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getLogger()).thenReturn(mock(Logger.class));
            when(world.getUID()).thenReturn(id);
            when(world.isChunkLoaded(-2, 3)).thenReturn(true);
            when(bukkitChunk.getWorld()).thenReturn(world);
            when(bukkitChunk.getX()).thenReturn(-2);
            when(bukkitChunk.getZ()).thenReturn(3);
            when(world.getLoadedChunks()).thenReturn(new Chunk[]{bukkitChunk});
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
            bukkit.when(() -> Bukkit.getWorld(id)).thenReturn(world);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, -2, 3)).thenReturn(true);
            bukkit.when(Bukkit::getRegionScheduler).thenReturn(scheduler);
            when(scheduler.run(eq(plugin), any(World.class), anyInt(), anyInt(), any())).thenAnswer(call -> {
                callbacks.add(call.getArgument(4));
                return mock(ScheduledTask.class);
            });
            when(lookup.find(world, -2, 3)).thenReturn(chunk);
            when(chunk.isLoaded()).thenReturn(true);
            when(chunk.sections()).thenReturn(new CESection[]{section});
            when(section.sectionY()).thenReturn(-4);
            when(section.statesContainer()).thenReturn(palette);
            when(palette.hasAny(any())).thenReturn(true);
            when(crop.owner().value().id()).thenReturn(Key.of("kaleidoscopecookery:tomato_crop"));
            when(section.getBlockState(anyInt())).thenAnswer(call -> cropIndexes.contains(call.getArgument(0)) ? crop : null);
        }

        void step() { recovery.dispatch(); runCallbacks(); }
        void runCallbacks() {
            var batch = List.copyOf(callbacks);
            callbacks.clear();
            batch.forEach(callback -> callback.accept(mock(ScheduledTask.class)));
        }
        @Override public void close() { recovery.close(); bukkit.close(); }
    }
}
