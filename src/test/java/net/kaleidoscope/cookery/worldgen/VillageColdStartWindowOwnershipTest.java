package net.kaleidoscope.cookery.worldgen;

import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class VillageColdStartWindowOwnershipTest {
    private final ResourceOperationCoordinator operations = new ResourceOperationCoordinator();

    private void writerExcluded() { assertThrows(ResourceOperationCoordinator.BusyException.class, operations::acquire); }
    private void writerAvailable() { try (var writer = operations.acquire()) { assertTrue(operations.isBusy()); } }

    @Test void actualPrivateLeaseRemainsExcludedUntilTheOwnerClosesAndCannotBeReopened() {
        var ownership = new VillageColdStartWindow.LeaseOwnership(operations);
        ownership.check(); writerExcluded(); ownership.close(); ownership.close(); writerAvailable();
        assertThrows(IllegalStateException.class, ownership::check);
    }

    @Test void foreignCloseCannotReleaseTheOwnersProtectionOrPreventItsFinallyRelease() throws Exception {
        var ownership = new VillageColdStartWindow.LeaseOwnership(operations); var problem = new AtomicReference<Throwable>();
        Thread foreign = new Thread(() -> { try { ownership.close(); } catch (Throwable failure) { problem.set(failure); } });
        foreign.start(); foreign.join(5000); assertFalse(foreign.isAlive()); assertInstanceOf(IllegalStateException.class, problem.get());
        ownership.check(); writerExcluded(); ownership.close(); writerAvailable();
    }

    @Test void failedChecksStayPoisonedWhileOwnerCanStillReleaseAfterTheOriginalStackUnwinds() throws Exception {
        var ownership = new VillageColdStartWindow.LeaseOwnership(operations); var problem = new AtomicReference<Throwable>();
        Thread foreign = new Thread(() -> { try { ownership.check(); } catch (Throwable failure) { problem.set(failure); } });
        foreign.start(); foreign.join(5000); assertFalse(foreign.isAlive()); assertInstanceOf(IllegalStateException.class, problem.get());
        var failed = assertThrows(IllegalStateException.class, ownership::check);
        assertSame(problem.get(), failed.getCause()); writerExcluded(); ownership.close(); writerAvailable();
    }

    @Test void lifecycleFailureIsStickyAndDoesNotReleaseProtectionBeforeOwnerRecoveryReturns() {
        var ownership = new VillageColdStartWindow.LeaseOwnership(operations); var problem = new LinkageError("startup roots changed");
        assertSame(problem, ownership.poison(problem));
        assertSame(problem, assertThrows(IllegalStateException.class, ownership::check).getCause());
        ownership.poison(new IllegalStateException("later"));
        assertSame(problem, assertThrows(IllegalStateException.class, ownership::check).getCause());
        writerExcluded(); ownership.close(); writerAvailable();
    }

    @Test void foreignDuplicateCloseAfterOwnerExitStillRejectsWithoutTouchingAnotherWritersLease() throws Exception {
        var ownership = new VillageColdStartWindow.LeaseOwnership(operations); ownership.close();
        try (var writer = operations.acquire()) {
            var problem = new AtomicReference<Throwable>();
            Thread foreign = new Thread(() -> { try { ownership.close(); } catch (Throwable failure) { problem.set(failure); } });
            foreign.start(); foreign.join(5000); assertFalse(foreign.isAlive()); assertInstanceOf(IllegalStateException.class, problem.get());
            ownership.close(); writerExcluded();
        }
        writerAvailable();
    }

    @Test void anExistingActualCeWriterRejectsAcquisitionWithoutStealingItsLease() {
        try (var writer = operations.acquire()) {
            assertThrows(ResourceOperationCoordinator.BusyException.class, () -> new VillageColdStartWindow.LeaseOwnership(operations));
            writerExcluded();
        }
        writerAvailable();
        assertThrows(UnsupportedOperationException.class, () -> VillageColdStartWindow.open("1.21.10"));
        assertThrows(UnsupportedOperationException.class, () -> VillageColdStartWindow.open(null));
        assertFalse(VillagePublicationTransaction.ExclusionScope.class.isAssignableFrom(VillageColdStartWindow.LeaseOwnership.class),
                "A real CE lease alone is not the native cold-start generation scope");
    }
}
