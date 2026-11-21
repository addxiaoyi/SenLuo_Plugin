package net.kaleidoscope.cookery.plugin;

import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CeResourceReadGateTest {
    private final ResourceOperationCoordinator operations = new ResourceOperationCoordinator();
    private final AtomicBoolean ready = new AtomicBoolean(true), current = new AtomicBoolean(true);
    private final CeResourceReadGate gate = new CeResourceReadGate(operations, ready::get);
    private final Object generation = new Object();

    private void publish() { assertTrue(gate.publish(generation, current::get)); }
    private CeResourceReadGate.Guard enter() {
        var result = gate.tryEnter(); assertTrue(result.admitted(), () -> "Rejected: " + result.rejection());
        assertSame(generation, result.guard().generation()); return result.guard();
    }
    private void writerExcluded() {
        assertThrows(ResourceOperationCoordinator.BusyException.class, operations::acquire);
    }
    private void writerAvailable() { try (var writer = operations.acquire()) { assertTrue(operations.isBusy()); } }

    @Test void startsClosedAndValidatesPublicationInsideTheRealCeLease() {
        assertEquals(CeResourceReadGate.Rejection.CLOSED, gate.tryEnter().rejection());
        assertTrue(gate.publish(generation, () -> { writerExcluded(); return true; }));
        assertFalse(operations.isBusy());
        try (var reader = enter()) { writerExcluded(); }
        writerAvailable();
    }

    @Test void publicationCallbackRunsUnderTheSameExclusiveLeaseAsGenerationValidation() {
        AtomicBoolean callbackRan = new AtomicBoolean();
        assertTrue(gate.publishWithLease(() -> new CeResourceReadGate.Publication(
                generation, current::get, () -> {
                    writerExcluded();
                    callbackRan.set(true);
                })));
        assertTrue(callbackRan.get());
        try (var reader = enter()) { writerExcluded(); }
        writerAvailable();
    }

    @Test void nestedAndConcurrentReadersShareExactlyOneLeaseAndDuplicateCloseIsHarmless() {
        publish(); var outer = enter(); var nested = enter();
        writerExcluded(); outer.close(); outer.close(); writerExcluded();
        nested.close(); writerAvailable();
        try (var next = enter()) { writerExcluded(); }
        assertNull(gate.failure());
    }

    @Test void externalWriterMakesAdmissionBusyWithoutAnyWorldOperationOrForcedLeaseRelease() {
        publish();
        try (var writer = operations.acquire()) {
            assertEquals(CeResourceReadGate.Rejection.BUSY, gate.tryEnter().rejection());
            assertEquals(CeResourceReadGate.Rejection.BUSY, gate.tryEnter().rejection());
            assertTrue(operations.isBusy());
        }
        assertEquals(2, gate.rejectedCounts().get(CeResourceReadGate.Rejection.BUSY));
        try (var reader = enter()) { writerExcluded(); }
        writerAvailable();
    }

    @Test void invalidatingAnActiveGenerationDrainsAllReadersBeforeReleasingTheWriter() {
        publish(); var a = enter(); var b = enter(); gate.invalidate();
        assertEquals(CeResourceReadGate.Rejection.STALE, gate.tryEnter().rejection());
        assertFalse(gate.publish(new Object(), () -> true));
        a.close(); writerExcluded(); b.close(); writerAvailable();
        assertEquals(CeResourceReadGate.Rejection.CLOSED, gate.tryEnter().rejection());
        publish(); try (var reader = enter()) { writerExcluded(); }
    }

    @Test void changedRegistryIdentityOrLifecycleCannotReuseTheOldPublishedGeneration() {
        publish(); current.set(false);
        assertEquals(CeResourceReadGate.Rejection.STALE, gate.tryEnter().rejection()); writerAvailable();
        assertEquals(CeResourceReadGate.Rejection.CLOSED, gate.tryEnter().rejection());
        current.set(true); ready.set(false); assertFalse(gate.publish(generation, current::get));
        writerAvailable(); ready.set(true); publish();
        var reader = enter(); ready.set(false);
        assertEquals(CeResourceReadGate.Rejection.STALE, gate.tryEnter().rejection());
        writerExcluded(); reader.close(); writerAvailable();
    }

    @Test void failedOrBusyPublicationLeavesTheGateClosedAndDoesNotRestoreAnOldBinding() {
        publish();
        try (var writer = operations.acquire()) { assertFalse(gate.publish(new Object(), () -> true)); }
        assertEquals(CeResourceReadGate.Rejection.CLOSED, gate.tryEnter().rejection());
        assertFalse(gate.publish(new Object(), () -> false));
        assertEquals(CeResourceReadGate.Rejection.CLOSED, gate.tryEnter().rejection()); writerAvailable();
    }

    @Test void validationFailurePoisonsAdmissionButLetsExistingReadersReleaseTheirActualLease() {
        AtomicBoolean fail = new AtomicBoolean(); var problem = new IllegalArgumentException("registry lookup failed");
        assertTrue(gate.publish(generation, () -> { if (fail.get()) throw problem; return true; }));
        var reader = enter(); fail.set(true);
        assertSame(problem, assertThrows(IllegalArgumentException.class, gate::tryEnter));
        assertSame(problem, gate.failure());
        assertEquals(CeResourceReadGate.Rejection.POISONED, gate.tryEnter().rejection()); writerExcluded();
        reader.close(); writerAvailable();
        assertFalse(gate.publish(new Object(), () -> true));
    }

    @Test void throwingPublicationNeverLeaksTheExclusiveLease() {
        var problem = new IllegalStateException("invalid generation");
        assertSame(problem, assertThrows(IllegalStateException.class, () -> gate.publish(generation, () -> { throw problem; })));
        assertSame(problem, gate.failure()); writerAvailable();
        assertEquals(CeResourceReadGate.Rejection.POISONED, gate.tryEnter().rejection());
    }

    @Test void anotherRegionMayOwnTheLastReaderAndCloseItsSharedLease() throws Exception {
        publish(); var root = enter();
        var entered = new CountDownLatch(1); var close = new CountDownLatch(1); var failure = new AtomicReference<Throwable>();
        Thread region = new Thread(() -> {
            try (var reader = enter()) { entered.countDown(); assertTrue(close.await(5, TimeUnit.SECONDS)); writerExcluded(); }
            catch (Throwable problem) { failure.set(problem); entered.countDown(); }
        });
        region.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); root.close(); writerExcluded();
        } finally { close.countDown(); region.join(5000); root.close(); }
        assertFalse(region.isAlive()); assertNull(failure.get()); writerAvailable();
    }

    @Test void invalidationDuringPublicationCannotBeOverwrittenByItsOldCompletion() throws Exception {
        var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        var published = new AtomicReference<Boolean>(); var failure = new AtomicReference<Throwable>();
        Thread publisher = new Thread(() -> {
            try { published.set(gate.publish(generation, () -> {
                entered.countDown();
                try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture wait expired"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return true;
            })); } catch (Throwable problem) { failure.set(problem); entered.countDown(); }
        });
        publisher.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(CeResourceReadGate.Rejection.TRANSITION, gate.tryEnter().rejection());
            gate.invalidate(); writerExcluded();
        } finally { proceed.countDown(); publisher.join(5000); }
        assertFalse(publisher.isAlive()); assertNull(failure.get()); assertEquals(Boolean.FALSE, published.get());
        assertEquals(CeResourceReadGate.Rejection.CLOSED, gate.tryEnter().rejection()); writerAvailable();
    }

    @Test void lastCloseRacingANewReaderNeverLeavesThatReaderOutsideTheActualCeExclusion() throws Exception {
        publish();
        for (int iteration = 0; iteration < 200; iteration++) {
            var old = enter(); var start = new CountDownLatch(1); var failure = new AtomicReference<Throwable>();
            Thread region = new Thread(() -> {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    var admission = gate.tryEnter();
                    if (admission.admitted()) {
                        try (var reader = admission.guard()) { writerExcluded(); }
                    } else assertEquals(CeResourceReadGate.Rejection.TRANSITION, admission.rejection());
                } catch (Throwable problem) { failure.set(problem); }
            });
            region.start(); start.countDown(); old.close(); region.join(5000);
            assertFalse(region.isAlive()); assertNull(failure.get(), "iteration " + iteration);
            writerAvailable();
        }
    }

    @Test void aJoiningReaderProtectsItsRegistryValidatorBeforeThePreviousLastReaderCanClose() throws Exception {
        var calls = new AtomicInteger(); var validating = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        assertTrue(gate.publish(generation, () -> {
            if (calls.incrementAndGet() == 4) {
                validating.countDown();
                try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture wait expired"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                writerExcluded();
            }
            return true;
        }));
        var first = enter(); var failure = new AtomicReference<Throwable>();
        Thread joining = new Thread(() -> {
            try (var reader = enter()) { writerExcluded(); }
            catch (Throwable problem) { failure.set(problem); validating.countDown(); }
        });
        joining.start();
        try {
            assertTrue(validating.await(5, TimeUnit.SECONDS));
            first.close(); writerExcluded();
        } finally { proceed.countDown(); joining.join(5000); first.close(); }
        assertFalse(joining.isAlive()); assertNull(failure.get()); writerAvailable();
    }
}
