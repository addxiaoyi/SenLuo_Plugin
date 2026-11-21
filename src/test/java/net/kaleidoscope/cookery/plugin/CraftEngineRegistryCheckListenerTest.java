package net.kaleidoscope.cookery.plugin;

import com.google.common.collect.ImmutableList;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kaleidoscope.cookery.block.behavior.SourceCropBehavior;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CraftEngineRegistryCheckListenerTest {
    @Test void supportRecoveryStartsOnlyAfterTheShapeGenerationWasPublished() {
        try (Fixture f = new Fixture()) {
            var recovery = mock(SourceCropSupportRecovery.class);
            when(f.plugin.sourceCropSupportRecovery()).thenReturn(recovery);
            f.listener.scheduleSourceCropRebind(false);
            ScheduledTask task = mock(ScheduledTask.class);
            try (var writer = f.operations.acquire()) {
                f.ticks.getFirst().accept(task);
                verifyNoInteractions(recovery);
            }
            f.ticks.getFirst().accept(task);
            verify(recovery).recheckLoadedChunks();
            assertFalse(f.operations.isBusy());
        }
    }

    @Test void reloadCompletionFlagDefersAllRegistryReadsAndBinding() {
        try (Fixture f = new Fixture()) {
            f.reloading.set(true);
            f.listener.scheduleSourceCropRebind(false);
            ScheduledTask task = mock(ScheduledTask.class);

            f.ticks.getFirst().accept(task);
            f.blocks.verifyNoInteractions();
            f.shapes.verifyNoInteractions();
            verify(task, never()).cancel();

            f.reloading.set(false);
            f.ticks.getFirst().accept(task);
            f.shapes.verify(() -> SourceCropBehavior.installShapes(any()));
            verify(task).cancel();
            assertFalse(f.operations.isBusy());
        }
    }

    @Test void busyResourceLeaseDefersReadsThenExcludesAnotherReloadDuringBinding() {
        try (Fixture f = new Fixture()) {
            ResourceOperationCoordinator.Lease otherOperation = f.operations.acquire();
            f.listener.scheduleSourceCropRebind(false);
            ScheduledTask task = mock(ScheduledTask.class);
            try {
                f.ticks.getFirst().accept(task);
                f.blocks.verifyNoInteractions();
                f.shapes.verifyNoInteractions();
                verify(task, never()).cancel();
            } finally {
                otherOperation.close();
            }

            f.ticks.getFirst().accept(task);
            f.shapes.verify(() -> SourceCropBehavior.installShapes(any()));
            verify(task).cancel();
            assertFalse(f.operations.isBusy());
        }
    }

    @Test void shapeFailureReleasesLeaseAndKeepsTheGenerationPermanentlyClosed() {
        try (Fixture f = new Fixture()) {
            f.shapes.when(() -> SourceCropBehavior.installShapes(any())).thenThrow(new IllegalStateException("partial state"));
            f.listener.scheduleSourceCropRebind(false);
            ScheduledTask task = mock(ScheduledTask.class);

            f.ticks.getFirst().accept(task);
            assertFalse(f.operations.isBusy(), "Failed shape binding must release the CE lease");
            verify(task, never()).cancel();
            assertNotNull(f.gate.failure());
            assertEquals(CeResourceReadGate.Rejection.POISONED, f.gate.tryEnter().rejection());

            f.ticks.getFirst().accept(task);
            f.shapes.verify(() -> SourceCropBehavior.installShapes(any()));
            verify(task, never()).cancel();
            assertFalse(f.operations.isBusy());
        }
    }

    @Test void partialPackPublishesOnlyInstalledCropsAndRejectsUnexpectedNewDefinition() {
        try (Fixture f = new Fixture()) {
            Map<Key, BlockDefinition> partial = new HashMap<>(f.readyBlocks);
            partial.remove(Key.of("kaleidoscopecookery:lettuce_crop"));
            f.blocks.when(CraftEngineBlocks::loadedBlocks).thenAnswer(call -> {
                f.assertLeaseHeld();
                return partial;
            });
            f.listener.scheduleSourceCropRebind(false);
            ScheduledTask task = mock(ScheduledTask.class);

            f.ticks.getFirst().accept(task);
            f.shapes.verify(() -> SourceCropBehavior.installShapes(argThat(definitions ->
                    definitions instanceof List<?> bound && bound.size() == 2)));
            verify(task).cancel();
            var admission = f.gate.tryEnter();
            assertTrue(admission.admitted());
            admission.guard().close();
            assertFalse(f.operations.isBusy());

            partial.put(Key.of("kaleidoscopecookery:lettuce_crop"), Fixture.cropDefinition());
            assertEquals(CeResourceReadGate.Rejection.STALE, f.gate.tryEnter().rejection());
            assertFalse(f.operations.isBusy());
        }
    }

    @Test void aPackWithoutAnySourceCropsStillAllowsGuardedSickleReads() {
        try (Fixture f = new Fixture()) {
            for (String name : List.of("tomato_crop", "chili_crop", "lettuce_crop")) {
                f.readyBlocks.remove(Key.of("kaleidoscopecookery:" + name));
            }
            f.listener.scheduleSourceCropRebind(false);
            ScheduledTask task = mock(ScheduledTask.class);
            f.ticks.getFirst().accept(task);
            f.shapes.verifyNoInteractions();
            verify(task).cancel();
            var admission = f.gate.tryEnter();
            assertTrue(admission.admitted());
            try (var guard = admission.guard()) {
                f.assertLeaseHeld();
            }
            assertFalse(f.operations.isBusy());
        }
    }

    @Test void missingApplianceIsReportedWithoutSuppressingCropShapeBinding() {
        try (Fixture f = new Fixture()) {
            f.readyBlocks.remove(Key.of("kaleidoscopecookery:pot"));
            f.listener.scheduleSourceCropRebind(false);
            ScheduledTask task = mock(ScheduledTask.class);

            f.ticks.getFirst().accept(task);

            f.shapes.verify(() -> SourceCropBehavior.installShapes(any()));
            verify(task).cancel();
            verify(f.plugin.getLogger(), atLeastOnce()).severe(anyString());
            assertFalse(f.operations.isBusy());
        }
    }

    @Test void replacingAnAgeStateUnderAnUnchangedDefinitionClosesAdmission() {
        try (Fixture f = new Fixture()) {
            f.listener.scheduleSourceCropRebind(false);
            f.ticks.getFirst().accept(mock(ScheduledTask.class));

            var admission = f.gate.tryEnter();
            assertTrue(admission.admitted());
            admission.guard().close();

            var tomato = f.readyBlocks.get(Key.of("kaleidoscopecookery:tomato_crop"));
            var replacement = mock(ImmutableBlockState.class);
            when(tomato.variantProvider().states()).thenReturn(ImmutableList.of(replacement));

            assertEquals(CeResourceReadGate.Rejection.STALE, f.gate.tryEnter().rejection());
            assertEquals(CeResourceReadGate.Rejection.CLOSED, f.gate.tryEnter().rejection());
            assertFalse(f.operations.isBusy());
        }
    }

    @Test void supersededOrDisabledTasksCancelWithoutAccessingTheRegistry() {
        try (Fixture f = new Fixture()) {
            f.listener.scheduleSourceCropRebind(false);
            f.listener.scheduleSourceCropRebind(false);
            ScheduledTask superseded = mock(ScheduledTask.class);
            f.ticks.getFirst().accept(superseded);
            verify(superseded).cancel();

            when(f.plugin.isEnabled()).thenReturn(false);
            ScheduledTask disabled = mock(ScheduledTask.class);
            f.ticks.getLast().accept(disabled);
            verify(disabled).cancel();
            f.blocks.verifyNoInteractions();
            f.shapes.verifyNoInteractions();
            assertEquals(0, f.reloadChecks.get());
            assertFalse(f.operations.isBusy());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final KaleidoscopeCookeryPlugin plugin = mock(KaleidoscopeCookeryPlugin.class);
        final AtomicBoolean reloading = new AtomicBoolean();
        final AtomicInteger reloadChecks = new AtomicInteger();
        final ResourceOperationCoordinator operations = new ResourceOperationCoordinator();
        final CeResourceReadGate gate = new CeResourceReadGate(operations,
                () -> plugin.isEnabled() && !reloading.get());
        final GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        final List<Consumer<ScheduledTask>> ticks = new ArrayList<>();
        final Map<Key, BlockDefinition> readyBlocks = readyBlocks();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<CraftEngineBlocks> blocks = mockStatic(CraftEngineBlocks.class);
        final MockedStatic<SourceCropBehavior> shapes = mockStatic(SourceCropBehavior.class);
        final CraftEngineRegistryCheckListener listener = new CraftEngineRegistryCheckListener(plugin, gate,
                () -> { reloadChecks.incrementAndGet(); return reloading.get(); });

        Fixture() {
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getLogger()).thenReturn(mock(Logger.class));
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            when(scheduler.runAtFixedRate(eq(plugin), any(), eq(1L), eq(1L))).thenAnswer(call -> {
                ticks.add(call.getArgument(1));
                return mock(ScheduledTask.class);
            });
            blocks.when(CraftEngineBlocks::loadedBlocks).thenAnswer(call -> {
                assertLeaseHeld();
                return readyBlocks;
            });
            shapes.when(() -> SourceCropBehavior.installShapes(any())).thenAnswer(call -> {
                assertLeaseHeld();
                return null;
            });
        }

        void assertLeaseHeld() {
            assertTrue(operations.isBusy(), "Registry reads and binding must hold CE's actual resource lease");
            assertThrows(ResourceOperationCoordinator.BusyException.class, operations::acquire,
                    "A concurrent reload must not acquire a resource lease during binding");
        }

        private static Map<Key, BlockDefinition> readyBlocks() {
            Map<Key, BlockDefinition> result = new HashMap<>();
            BlockDefinition definition = cropDefinition();
            for (String name : List.of("chopping_board", "enamel_basin", "kitchenware_racks", "pot",
                    "shawarma_spit", "steamer", "stockpot", "stove", "fruit_basket", "teapot", "teacup_coaster",
                    "tomato_crop", "chili_crop", "lettuce_crop"))
                result.put(Key.of("kaleidoscopecookery:" + name), definition);
            return result;
        }

        private static BlockDefinition cropDefinition() {
            BlockDefinition definition = mock(BlockDefinition.class, RETURNS_DEEP_STUBS);
            when(definition.variantProvider().states()).thenReturn(ImmutableList.of(mock(ImmutableBlockState.class)));
            return definition;
        }

        @Override public void close() {
            shapes.close();
            blocks.close();
            bukkit.close();
        }
    }
}
