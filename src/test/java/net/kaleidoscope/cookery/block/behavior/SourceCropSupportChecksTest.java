package net.kaleidoscope.cookery.block.behavior;

import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kaleidoscope.cookery.plugin.CeResourceReadGate;
import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceCropSupportChecksTest {
    private static final BlockPos POS = new BlockPos(17, 69, -2);
    private static final Key TOMATO = Key.of("kaleidoscopecookery:tomato_crop");
    private static final Key LETTUCE = Key.of("kaleidoscopecookery:lettuce_crop");

    private final World world = world();
    private final ResourceOperationCoordinator operations = new ResourceOperationCoordinator();
    private final CeResourceReadGate gate = new CeResourceReadGate(operations, () -> true);
    private final Plugin plugin = plugin();
    private final ManualScheduler scheduler = new ManualScheduler();

    @Test void routesCrossRegionIntentsBeforeCheckingTargetOwnership() {
        var recheck = mock(SourceCropSupportChecks.Recheck.class);
        try (var bukkit = mockStatic(Bukkit.class);
             var queue = queue(2, 2, recheck)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 1, -1)).thenReturn(false);
            when(world.isChunkLoaded(1, -1)).thenReturn(true);
            assertTrue(queue.schedule(world, POS, TOMATO, 1));
            assertEquals(List.of(new ManualScheduler.Dispatch(world, 1, -1, 1)), scheduler.dispatches());

            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 1, -1)).thenReturn(true);
            scheduler.tick(queue);
            verify(recheck).check(world, POS, TOMATO);
        }
    }

    @Test void schedulesAtTheOwnerAndRetainsTheEarliestDuplicateDelay() {
        List<BlockPos> checked = new ArrayList<>();
        BlockPos later = POS.above();
        try (var bukkit = mockStatic(Bukkit.class);
             var queue = queue(4, 4, (w, pos, key) -> checked.add(pos))) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 1, -1)).thenReturn(true);
            when(world.isChunkLoaded(1, -1)).thenReturn(true);
            assertTrue(queue.schedule(world, POS, TOMATO, 2));
            var first = scheduler.tasks.getFirst();
            assertTrue(queue.schedule(world, POS, TOMATO, 1));
            assertTrue(first.cancelled);
            assertTrue(queue.schedule(world, POS, TOMATO, 2));
            assertTrue(queue.schedule(world, later, LETTUCE, 2));
            assertEquals(List.of(2L, 1L, 2L), scheduler.delays());
            assertEquals(List.of(new ManualScheduler.Dispatch(world, 1, -1, 2),
                    new ManualScheduler.Dispatch(world, 1, -1, 1),
                    new ManualScheduler.Dispatch(world, 1, -1, 2)), scheduler.dispatches());

            scheduler.tick(queue);
            assertEquals(List.of(POS), checked);
            scheduler.tick(queue);
            assertEquals(List.of(POS, later), checked);
            scheduler.tick(queue);
            assertEquals(List.of(POS, later), checked);
        }
    }

    @Test void waitsOutClosedAndBusyGenerationsThenChecksUnderTheNewLeaseOnce() {
        var recheck = mock(SourceCropSupportChecks.Recheck.class);
        try (var bukkit = mockStatic(Bukkit.class);
             var queue = queue(1, 1, (w, pos, key) -> {
                 assertThrows(ResourceOperationCoordinator.BusyException.class, operations::acquire);
                 recheck.check(w, pos, key);
             })) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 1, -1)).thenReturn(true);
            when(world.isChunkLoaded(1, -1)).thenReturn(true);
            assertTrue(queue.schedule(world, POS, TOMATO, 1));
            gate.invalidate();
            scheduler.tick(queue);
            verifyNoInteractions(recheck);
            assertTrue(gate.publish(new Object(), () -> true));
            try (var writer = operations.acquire()) {
                scheduler.tick(queue);
                verifyNoInteractions(recheck);
            }

            scheduler.tick(queue);
            scheduler.tick(queue);
            verify(recheck).check(world, POS, TOMATO);
            verifyNoMoreInteractions(recheck);
            assertFalse(operations.isBusy());
        }
    }

    @Test void followsOwnershipAndRetainsChecksWhileTheChunkIsUnloaded() {
        var recheck = mock(SourceCropSupportChecks.Recheck.class);
        try (var bukkit = mockStatic(Bukkit.class);
             var queue = queue(1, 1, recheck)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 1, -1)).thenReturn(true);
            assertTrue(queue.schedule(world, POS, TOMATO, 2));
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 1, -1)).thenReturn(false);
            scheduler.tick(queue);
            verify(world, never()).isChunkLoaded(1, -1);
            verifyNoInteractions(recheck);

            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 1, -1)).thenReturn(true);
            when(world.isChunkLoaded(1, -1)).thenReturn(false, false, true);
            for (int tick = 0; tick < 8; tick++) scheduler.tick(queue);
            verify(recheck).check(world, POS, TOMATO);
            verifyNoMoreInteractions(recheck);
        }
    }

    @Test void boundsCapacityAndAttemptsWithoutForeignWorkStarvingThisOwner() {
        List<BlockPos> checked = new ArrayList<>();
        BlockPos foreign = new BlockPos(-1, 69, -2);
        BlockPos next = POS.above();
        try (var bukkit = mockStatic(Bukkit.class);
             var queue = queue(3, 1, (w, pos, key) -> checked.add(pos))) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 1, -1)).thenReturn(true);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, -1, -1)).thenReturn(true);
            when(world.isChunkLoaded(1, -1)).thenReturn(true);
            when(world.isChunkLoaded(-1, -1)).thenReturn(true);
            assertTrue(queue.schedule(world, foreign, TOMATO, 1));
            assertTrue(queue.schedule(world, POS, TOMATO, 1));
            assertTrue(queue.schedule(world, next, TOMATO, 1));
            assertTrue(queue.schedule(world, next, TOMATO, 1));
            assertFalse(queue.schedule(world, next, LETTUCE, 1));

            for (int tick = 0; tick < 5; tick++) scheduler.tick(queue);
            assertEquals(3, checked.size());
            assertEquals(Set.of(foreign, POS, next), Set.copyOf(checked));
        }
    }

    @Test void retriesUseTheGlobalDispatchBudgetAndRotateUnavailableEntries() {
        var recheck = mock(SourceCropSupportChecks.Recheck.class);
        List<BlockPos> positions = List.of(POS, new BlockPos(33, 69, -2),
                new BlockPos(49, 69, -2), new BlockPos(65, 69, -2));
        try (var bukkit = mockStatic(Bukkit.class);
             var queue = queue(8, 2, recheck)) {
            for (BlockPos position : positions) {
                bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, position.x() >> 4, position.z() >> 4))
                        .thenReturn(true);
            }
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
            for (BlockPos position : positions) assertTrue(queue.schedule(world, position, TOMATO, 1));

            assertEquals(2, scheduler.dispatches().size(), "initial scheduling respects the dispatch budget");
            scheduler.tick(queue);
            assertEquals(4, scheduler.dispatches().size(), "remaining initial intents are dispatched at tick start");

            scheduler.tick(queue);
            assertEquals(6, scheduler.dispatches().size(), "only two retries are dispatched per tick");
            assertEquals(List.of(1, 2), scheduler.dispatches().subList(4, 6).stream()
                    .map(ManualScheduler.Dispatch::chunkX).toList());

            scheduler.tick(queue);
            assertEquals(8, scheduler.dispatches().size(), "the next two entries get their turn");
            assertEquals(List.of(3, 4), scheduler.dispatches().subList(6, 8).stream()
                    .map(ManualScheduler.Dispatch::chunkX).toList());
            verifyNoInteractions(recheck);
        }
    }

    @Test void tickStartDoesNotDispatchAnIntentAlreadyBeingSubmitted() throws Exception {
        var enteredScheduler = new CountDownLatch(1);
        var releaseScheduler = new CountDownLatch(1);
        var submissions = new AtomicInteger();
        var schedulerApi = mock(RegionScheduler.class);
        var scheduledTask = mock(ScheduledTask.class);
        when(schedulerApi.runDelayed(nullable(Plugin.class), any(World.class), anyInt(), anyInt(), any(), anyLong()))
                .thenAnswer(call -> {
                    submissions.incrementAndGet();
                    enteredScheduler.countDown();
                    assertTrue(releaseScheduler.await(3, TimeUnit.SECONDS));
                    return scheduledTask;
                });
        var problem = new AtomicReference<Throwable>();
        var queue = new SourceCropSupportChecks(4, 1, () -> null, mock(SourceCropSupportChecks.Recheck.class),
                schedulerApi, () -> plugin);
        try (var bukkit = mockStatic(Bukkit.class); queue) {
            Thread submitting = new Thread(() -> {
                try { queue.schedule(world, POS, TOMATO, 2); }
                catch (Throwable failure) { problem.set(failure); }
            });
            submitting.start();
            assertTrue(enteredScheduler.await(3, TimeUnit.SECONDS), "initial dispatch did not reach the scheduler");
            queue.onTickStart(new ServerTickStartEvent(1));
            assertEquals(1, submissions.get(), "tick start must not claim an in-flight dispatch");
            releaseScheduler.countDown();
            submitting.join(3000);
            assertFalse(submitting.isAlive(), "dispatch thread did not finish");
            assertNull(problem.get());
            assertEquals(1, submissions.get());
        } finally {
            releaseScheduler.countDown();
        }
    }

    @Test void schedulerSupplierFailureReleasesTheDispatchClaimForRetry() {
        var failure = new IllegalStateException("scheduler unavailable");
        var calls = new AtomicInteger();
        var schedulerApi = mock(RegionScheduler.class);
        when(schedulerApi.runDelayed(nullable(Plugin.class), any(World.class), anyInt(), anyInt(), any(), anyLong()))
                .thenReturn(mock(ScheduledTask.class));
        var queue = new SourceCropSupportChecks(2, 1, () -> null, mock(SourceCropSupportChecks.Recheck.class),
                schedulerApi, () -> {
                    if (calls.getAndIncrement() == 0) throw failure;
                    return plugin;
                });
        try (var bukkit = mockStatic(Bukkit.class); queue) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 1, -1)).thenReturn(true);
            when(world.isChunkLoaded(1, -1)).thenReturn(true);
            assertTrue(queue.schedule(world, POS, TOMATO, 2));
            queue.onTickStart(new ServerTickStartEvent(1));
            verify(schedulerApi, times(1)).runDelayed(nullable(Plugin.class), eq(world), eq(1), eq(-1), any(), eq(2L));
        }
    }

    @Test void admissionFailureDiscardsTheEntryInsteadOfLeavingItRunning() {
        var recheck = mock(SourceCropSupportChecks.Recheck.class);
        var problem = new IllegalStateException("poisoned CE admission");
        Supplier<CeResourceReadGate.Guard> failingAdmission = () -> { throw problem; };
        try (var bukkit = mockStatic(Bukkit.class);
             var queue = queue(1, 1, failingAdmission, recheck)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 1, -1)).thenReturn(true);
            when(world.isChunkLoaded(1, -1)).thenReturn(true);
            assertTrue(queue.schedule(world, POS, TOMATO, 1));

            var thrown = assertThrows(IllegalStateException.class, () -> scheduler.tick(queue));
            assertSame(problem, thrown);
            assertEquals(1, scheduler.dispatches().size());

            scheduler.tick(queue);
            assertEquals(1, scheduler.dispatches().size(), "failed entries are discarded");
            verifyNoInteractions(recheck);
        }
    }

    @Test void cancelledWorldUnloadPreservesWorkAndShutdownClearsIt() {
        var recheck = mock(SourceCropSupportChecks.Recheck.class);
        try (var bukkit = mockStatic(Bukkit.class);
             var queue = queue(3, 3, recheck)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 1, -1)).thenReturn(true);
            when(world.isChunkLoaded(1, -1)).thenReturn(true);
            assertTrue(queue.schedule(world, POS, TOMATO, 1));
            assertTrue(queue.schedule(world, POS.above(), TOMATO, 2));
            World other = world();
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(other, 1, -1)).thenReturn(true);
            when(other.isChunkLoaded(1, -1)).thenReturn(true);
            assertTrue(queue.schedule(other, POS, LETTUCE, 1));
            var cancelled = new WorldUnloadEvent(world);
            cancelled.setCancelled(true);
            queue.onWorldUnload(cancelled);
            scheduler.tick(queue);
            verify(recheck).check(world, POS, TOMATO);
            verify(recheck).check(other, POS, LETTUCE);

            queue.onWorldUnload(new WorldUnloadEvent(world));
            scheduler.tick(queue);
            verifyNoMoreInteractions(recheck);
            assertTrue(queue.schedule(other, POS.above(), LETTUCE, 1));
            queue.close();
            assertFalse(queue.schedule(other, POS, LETTUCE, 1));
            scheduler.tick(queue);
            verifyNoMoreInteractions(recheck);
        }
    }

    private SourceCropSupportChecks queue(int capacity, int limit, SourceCropSupportChecks.Recheck recheck) {
        assertTrue(gate.publish(new Object(), () -> true));
        return queue(capacity, limit, () -> gate.tryEnter().guard(), recheck);
    }

    private SourceCropSupportChecks queue(int capacity, int limit,
                                          Supplier<CeResourceReadGate.Guard> admission,
                                          SourceCropSupportChecks.Recheck recheck) {
        return new SourceCropSupportChecks(capacity, limit, admission, recheck, scheduler.api, () -> plugin);
    }

    private static World world() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        return world;
    }

    private static Plugin plugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }

    private static final class ManualScheduler {
        private final List<Task> tasks = new ArrayList<>();
        private final List<Dispatch> dispatches = new ArrayList<>();
        private final RegionScheduler api = mock(RegionScheduler.class);

        ManualScheduler() {
            when(api.runDelayed(nullable(Plugin.class), any(World.class), anyInt(), anyInt(), any(), anyLong()))
                    .thenAnswer(call -> {
                        World world = call.getArgument(1);
                        int chunkX = call.getArgument(2), chunkZ = call.getArgument(3);
                        long delay = call.getArgument(5);
                        dispatches.add(new Dispatch(world, chunkX, chunkZ, delay));
                        Task task = new Task(call.getArgument(4), delay);
                        tasks.add(task);
                        doAnswer(ignored -> {
                            task.cancelled = true;
                            return null;
                        }).when(task.handle).cancel();
                        return task.handle;
                    });
        }

        List<Long> delays() {
            return tasks.stream().map(task -> task.delay).toList();
        }

        List<Dispatch> dispatches() { return List.copyOf(dispatches); }

        void tick(SourceCropSupportChecks queue) {
            queue.onTickStart(new ServerTickStartEvent(1));
            for (Task task : List.copyOf(tasks)) {
                if (task.cancelled || task.fired) continue;
                if (--task.delay <= 0) {
                    task.fired = true;
                    task.callback.accept(task.handle);
                }
            }
        }

        record Dispatch(World world, int chunkX, int chunkZ, long delay) {}
    }

    private static final class Task {
        final Consumer<ScheduledTask> callback;
        final ScheduledTask handle = mock(ScheduledTask.class);
        long delay;
        boolean cancelled;
        boolean fired;

        Task(Consumer<ScheduledTask> callback, long delay) {
            this.callback = callback;
            this.delay = delay;
        }
    }
}
