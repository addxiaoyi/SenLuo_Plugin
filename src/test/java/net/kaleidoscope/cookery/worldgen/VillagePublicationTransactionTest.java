package net.kaleidoscope.cookery.worldgen;

import net.kaleidoscope.cookery.worldgen.VillagePublicationTransaction.Edit;
import net.kaleidoscope.cookery.worldgen.VillagePublicationTransaction.IntValue;
import net.kaleidoscope.cookery.worldgen.VillagePublicationTransaction.MapEntry;
import net.kaleidoscope.cookery.worldgen.VillagePublicationTransaction.PublicationFailure;
import net.kaleidoscope.cookery.worldgen.VillagePublicationTransaction.Reference;
import net.kaleidoscope.cookery.worldgen.VillagePublicationTransaction.Status;
import net.kaleidoscope.cookery.worldgen.VillagePublicationTransaction.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class VillagePublicationTransactionTest {
    private static final int SLOT_COUNT = 21; // five cache entries, processor list and three fields in each of five pools

    private static final class Scope implements VillagePublicationTransaction.ExclusionScope {
        boolean owned = true;
        int calls;
        Runnable onCheck = () -> {};
        @Override public void check() {
            calls++; onCheck.run();
            if (!owned) throw new IllegalStateException("fixture exclusion lost");
        }
    }

    private static final class Cell<V extends Value> implements VillagePublicationTransaction.Slot<V> {
        final String name;
        final V before, after;
        V current;
        int reads;
        final List<String> journal;
        Runnable onRead = () -> {};
        java.util.function.Consumer<V> beforeWrite = value -> {};
        java.util.function.Consumer<V> afterWrite = value -> {};

        Cell(String name, V before, V after, List<String> journal) {
            this.name = name; this.before = before; this.after = after; this.current = before; this.journal = journal;
        }
        @Override public V read() { reads++; onRead.run(); return current; }
        @Override public void write(V value) {
            journal.add(name + (value.same(before) ? ":restore" : ":publish"));
            beforeWrite.accept(value); current = value; afterWrite.accept(value);
        }
        Edit<V> edit() { return new Edit<>(name, this, before, after); }
    }

    private static final class Fixture {
        final Scope scope = new Scope();
        final List<String> journal = new ArrayList<>();
        final List<Cell<?>> cells = new ArrayList<>();
        final List<Edit<?>> edits = new ArrayList<>();
        Runnable preflight = () -> {}, verify = () -> {};

        Fixture() {
            for (int biome = 0; biome < 5; biome++)
                add(new Cell<>("template-" + biome, new MapEntry(biome % 2 == 0, biome % 2 == 0 ? new Object() : null),
                        new MapEntry(true, new Object()), journal));
            add(new Cell<>("processor-list", new Reference(new Object()), new Reference(new Object()), journal));
            for (int biome = 0; biome < 5; biome++) {
                add(new Cell<>("raw-" + biome, new Reference(new Object()), new Reference(new Object()), journal));
                add(new Cell<>("expanded-" + biome, new Reference(new Object()), new Reference(new Object()), journal));
                add(new Cell<>("max-size-" + biome, new IntValue(1_000 + biome), new IntValue(Integer.MIN_VALUE), journal));
            }
        }
        void add(Cell<?> cell) { cells.add(cell); edits.add(cell.edit()); }
        VillagePublicationTransaction transaction() {
            return new VillagePublicationTransaction(edits, scope, () -> preflight.run(), () -> verify.run());
        }
        void assertOriginals() { cells.forEach(cell -> assertTrue(cell.before.same(cell.current), cell.name)); }
        void assertPublished() { cells.forEach(cell -> assertTrue(cell.after.same(cell.current), cell.name)); }
    }

    private static PublicationFailure failed(VillagePublicationTransaction transaction) {
        return assertThrows(PublicationFailure.class, transaction::publish);
    }

    @Test void completeNativeShapedWriteSetPreflightsEverySlotBeforeWritingAndVerifiesThePublishedSet() {
        var fixture = new Fixture();
        AtomicInteger preflight = new AtomicInteger(), verified = new AtomicInteger();
        fixture.preflight = () -> { preflight.incrementAndGet(); fixture.assertOriginals(); assertTrue(fixture.journal.isEmpty()); };
        fixture.verify = () -> { verified.incrementAndGet(); fixture.assertPublished(); };
        fixture.cells.getFirst().beforeWrite = value -> {
            assertTrue(fixture.cells.stream().allMatch(cell -> cell.reads > 0), "No write may precede any slot's original-state check");
        };
        var transaction = fixture.transaction();
        assertEquals(Status.NEW, transaction.status());
        assertEquals(new VillagePublicationTransaction.Receipt(SLOT_COUNT, 0), transaction.publish());
        assertEquals(Status.COMMITTED, transaction.status());
        fixture.assertPublished(); assertEquals(1, preflight.get()); assertEquals(1, verified.get());
        assertEquals(fixture.cells.stream().map(cell -> cell.name + ":publish").toList(), fixture.journal);
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void everyWriteFailureBeforeMutationRestoresEarlierSlotsInReverseOrder(int cut) {
        var fixture = new Fixture(); var problem = new IllegalStateException("before write " + cut);
        fixture.cells.get(cut).beforeWrite = value -> { if (!value.same(fixture.cells.get(cut).before)) throw problem; };
        var transaction = fixture.transaction(); var failure = failed(transaction);
        assertSame(problem, failure.getCause()); assertEquals(Status.ROLLED_BACK, failure.status());
        assertEquals(Status.ROLLED_BACK, transaction.status());
        assertTrue(failure.rollbackFailures().isEmpty()); assertTrue(failure.unconfirmedSlots().isEmpty());
        fixture.assertOriginals();
        var expected = new ArrayList<>(fixture.cells.subList(0, cut + 1).stream().map(cell -> cell.name + ":publish").toList());
        for (int index = cut - 1; index >= 0; index--) expected.add(fixture.cells.get(index).name + ":restore");
        assertEquals(expected, fixture.journal);
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void everyWriteFailureAfterMutationIncludesThatFailingSlotInReverseRollback(int cut) {
        var fixture = new Fixture(); var problem = new IllegalArgumentException("after write " + cut);
        fixture.cells.get(cut).afterWrite = value -> { if (!value.same(fixture.cells.get(cut).before)) throw problem; };
        var failure = failed(fixture.transaction());
        assertSame(problem, failure.getCause()); assertEquals(Status.ROLLED_BACK, failure.status());
        assertTrue(failure.rollbackFailures().isEmpty()); fixture.assertOriginals();
        var expected = new ArrayList<>(fixture.cells.subList(0, cut + 1).stream().map(cell -> cell.name + ":publish").toList());
        for (int index = cut; index >= 0; index--) expected.add(fixture.cells.get(index).name + ":restore");
        assertEquals(expected, fixture.journal);
    }

    @Test void beforeIdentityDriftInTheLastSlotRefusesAllWritesIncludingEqualsEquivalentObjects() {
        var fixture = new Fixture();
        Cell<Reference> last = referenceCell(fixture.cells.get(SLOT_COUNT - 2));
        String before = new String("equal"); String other = new String("equal");
        assertEquals(before, other); assertNotSame(before, other);
        var equalityCell = new Cell<>("equals-is-insufficient", new Reference(before), new Reference(new Object()), fixture.journal);
        equalityCell.current = new Reference(other);
        fixture.add(equalityCell);
        var failure = failed(fixture.transaction());
        assertEquals(Status.REJECTED, failure.status()); assertTrue(fixture.journal.isEmpty());
        assertTrue(last.before.same(last.current)); assertSame(other, equalityCell.current.value());
    }

    @Test void typedValuesDistinguishCachePresenceAndIdentityButComparePrimitiveValues() {
        Object object = new Object();
        assertTrue(new Reference(object).same(new Reference(object)));
        assertFalse(new Reference(new String("x")).same(new Reference(new String("x"))));
        assertTrue(new Reference(null).same(new Reference(null)));
        assertFalse(new Reference(object).same(new MapEntry(true, object)));
        assertTrue(new MapEntry(false, null).same(new MapEntry(false, null)));
        assertFalse(new MapEntry(false, null).same(new MapEntry(true, null)));
        assertTrue(new MapEntry(true, object).same(new MapEntry(true, object)));
        assertFalse(new MapEntry(true, new String("x")).same(new MapEntry(true, new String("x"))));
        assertTrue(new IntValue(65_536).same(new IntValue(65_536)));
        assertFalse(new IntValue(65_536).same(new IntValue(65_535)));
        assertFalse(new IntValue(1).same(new Reference(1)));
        assertThrows(IllegalArgumentException.class, () -> new MapEntry(false, object));
    }

    @Test void anAllNoOpPlanStillChecksScopePreflightOriginalsAndFinalValidationWithoutWriting() {
        var fixture = new Fixture(); AtomicInteger preflight = new AtomicInteger(), finalChecks = new AtomicInteger();
        List<Edit<?>> same = new ArrayList<>();
        fixture.cells.forEach(cell -> same.add(noOp(cell.edit())));
        var transaction = new VillagePublicationTransaction(same, fixture.scope, preflight::incrementAndGet, finalChecks::incrementAndGet);
        assertEquals(new VillagePublicationTransaction.Receipt(0, SLOT_COUNT), transaction.publish());
        assertTrue(fixture.journal.isEmpty()); fixture.assertOriginals();
        assertTrue(fixture.cells.stream().allMatch(cell -> cell.reads >= 3));
        assertEquals(1, preflight.get()); assertEquals(1, finalChecks.get());
    }

    private static <V extends Value> Edit<V> noOp(Edit<V> edit) { return new Edit<>(edit.name(), edit.slot(), edit.before(), edit.before()); }

    @Test void rejectedMissingExclusionDoesNotReadSlotsOrInvokeCallbacksAndCannotBeRetried() {
        var fixture = new Fixture(); fixture.scope.owned = false;
        fixture.preflight = () -> fail("No preflight without exclusion"); fixture.verify = () -> fail("No verification without exclusion");
        var transaction = fixture.transaction(); var failure = failed(transaction);
        assertEquals(Status.REJECTED, failure.status());
        assertTrue(fixture.cells.stream().allMatch(cell -> cell.reads == 0)); assertTrue(fixture.journal.isEmpty());
        int checked = fixture.scope.calls; fixture.scope.owned = true;
        assertThrows(IllegalStateException.class, transaction::publish);
        assertEquals(checked, fixture.scope.calls, "One-shot rejection happens before any scope callback");
    }

    @Test void preflightFailureIsRetainedWithoutAnyWriteOrFinalCallback() {
        var fixture = new Fixture(); var problem = new LinkageError("native accessor unavailable");
        fixture.preflight = () -> { throw problem; }; fixture.verify = () -> fail("No verification after rejected preflight");
        var failure = failed(fixture.transaction());
        assertSame(problem, failure.getCause()); assertEquals(Status.REJECTED, failure.status());
        assertTrue(fixture.journal.isEmpty()); assertTrue(fixture.cells.stream().allMatch(cell -> cell.reads == 0));
    }

    @Test void finalValidationFailureRollsBackTheCompleteSetAndIsNeverReturnedAsSuccess() {
        var fixture = new Fixture(); var problem = new AssertionError("native publication failed its final coherence check");
        fixture.verify = () -> { fixture.assertPublished(); throw problem; };
        var failure = failed(fixture.transaction());
        assertSame(problem, failure.getCause()); assertEquals(Status.ROLLED_BACK, failure.status()); fixture.assertOriginals();
        assertEquals(SLOT_COUNT * 2, fixture.journal.size());
        assertEquals(IntStream.range(0, SLOT_COUNT).mapToObj(index -> fixture.cells.get(SLOT_COUNT - 1 - index).name + ":restore").toList(),
                fixture.journal.subList(SLOT_COUNT, SLOT_COUNT * 2));
    }

    @Test void finalCallbackCannotSilentlyChangeATypedValueAfterEarlierVerification() {
        var fixture = new Fixture(); Cell<Reference> target = referenceCell(fixture.cells.get(5)); var intruder = new Object();
        fixture.verify = () -> target.current = new Reference(intruder);
        var failure = failed(fixture.transaction());
        assertEquals(Status.INDETERMINATE, failure.status());
        assertEquals(List.of(target.name), failure.unconfirmedSlots()); assertSame(intruder, target.current.value());
        assertFalse(fixture.journal.contains(target.name + ":restore"), "Unknown callback state must not be overwritten");
        fixture.cells.stream().filter(cell -> cell != target).forEach(cell -> assertTrue(cell.before.same(cell.current), cell.name));
    }

    @Test void driftInAnUnattemptedSlotDuringAnEarlierWriteIsDetectedAndKeptUntouched() {
        var fixture = new Fixture(); Cell<Reference> later = referenceCell(fixture.cells.get(5)); var intruder = new Object();
        fixture.cells.getFirst().afterWrite = value -> {
            if (!value.same(fixture.cells.getFirst().before)) later.current = new Reference(intruder);
        };
        var failure = failed(fixture.transaction());
        assertEquals(Status.INDETERMINATE, failure.status()); assertEquals(List.of(later.name), failure.unconfirmedSlots());
        assertSame(intruder, later.current.value());
        assertFalse(fixture.journal.contains(later.name + ":publish"));
        assertFalse(fixture.journal.contains(later.name + ":restore"));
        fixture.cells.stream().filter(cell -> cell != later).forEach(cell -> assertTrue(cell.before.same(cell.current), cell.name));
    }

    @Test void aSetterThatIgnoresThePreparedValueIsDetectedAndPriorWritesAreRolledBack() {
        var fixture = new Fixture(); Cell<Reference> target = referenceCell(fixture.cells.get(5));
        target.afterWrite = value -> { if (!value.same(target.before)) target.current = target.before; };
        var failure = failed(fixture.transaction());
        assertEquals(Status.ROLLED_BACK, failure.status()); fixture.assertOriginals();
        assertFalse(fixture.journal.contains(target.name + ":restore"), "Already-original slots need no write");
        assertEquals(11, fixture.journal.size());
    }

    @Test void rollbackWriteFailureKeepsTheOriginalCauseAndContinuesRestoringEarlierSlots() {
        var fixture = new Fixture(); var initial = new IllegalStateException("final verification");
        var rollback = new IllegalArgumentException("restore raw field"); Cell<Reference> target = referenceCell(fixture.cells.get(6));
        target.beforeWrite = value -> { if (value.same(target.before)) throw rollback; };
        fixture.verify = () -> { throw initial; };
        var transaction = fixture.transaction(); var failure = failed(transaction);
        assertSame(initial, failure.getCause()); assertEquals(Status.INDETERMINATE, transaction.status());
        assertEquals(List.of(target.name), failure.unconfirmedSlots());
        assertTrue(failure.rollbackFailures().stream().anyMatch(row -> row.edit().equals(target.name) && row.cause() == rollback));
        assertTrue(List.of(failure.getSuppressed()).stream().anyMatch(row -> row.getCause() == rollback));
        assertTrue(target.after.same(target.current));
        fixture.cells.stream().filter(cell -> cell != target).forEach(cell -> assertTrue(cell.before.same(cell.current), cell.name));
    }

    @Test void rollbackReadFailureDoesNotStopEarlierRestoresOrOverwriteTheUnreadSlot() {
        var fixture = new Fixture(); var initial = new IllegalStateException("final verification");
        var unreadable = new IllegalStateException("native field became unreadable");
        Cell<Reference> target = referenceCell(fixture.cells.get(6));
        fixture.verify = () -> { target.onRead = () -> { throw unreadable; }; throw initial; };
        var failure = failed(fixture.transaction());
        assertSame(initial, failure.getCause()); assertEquals(Status.INDETERMINATE, failure.status());
        assertEquals(List.of(target.name), failure.unconfirmedSlots());
        assertFalse(fixture.journal.contains(target.name + ":restore"));
        assertTrue(failure.rollbackFailures().stream().anyMatch(row -> row.cause() == unreadable));
        fixture.cells.stream().filter(cell -> cell != target).forEach(cell -> assertTrue(cell.before.same(cell.current), cell.name));
    }

    @Test void aPreparedWriteThatReturnsAnUnknownValueIsNeverBlindlyRolledBack() {
        var fixture = new Fixture(); var intruder = new Object();
        Cell<Reference> target = referenceCell(fixture.cells.get(6));
        target.afterWrite = value -> { if (!value.same(target.before)) target.current = new Reference(intruder); };
        var failure = failed(fixture.transaction());
        assertEquals(Status.INDETERMINATE, failure.status()); assertEquals(List.of(target.name), failure.unconfirmedSlots());
        assertSame(intruder, target.current.value()); assertFalse(fixture.journal.contains(target.name + ":restore"));
        fixture.cells.stream().filter(cell -> cell != target).forEach(cell -> assertTrue(cell.before.same(cell.current), cell.name));
    }

    @Test void nullReadStateIsRejectedBeforeAnyPublicationAccessor() {
        var fixture = new Fixture(); fixture.cells.getLast().current = null;
        var failure = failed(fixture.transaction());
        assertEquals(Status.REJECTED, failure.status()); assertInstanceOf(NullPointerException.class, failure.getCause());
        assertTrue(fixture.journal.isEmpty());
    }

    @Test void throwAfterRestoringOriginalIsStillAnExplicitRollbackFaultEvenIfAllOriginalsAreObserved() {
        var fixture = new Fixture(); var initial = new IllegalStateException("publication rejected");
        var rollback = new IllegalStateException("restore accessor threw after setting");
        Cell<Reference> target = referenceCell(fixture.cells.get(6));
        target.afterWrite = value -> { if (value.same(target.before)) throw rollback; };
        fixture.verify = () -> { throw initial; };
        var failure = failed(fixture.transaction());
        fixture.assertOriginals(); assertSame(initial, failure.getCause()); assertEquals(Status.INDETERMINATE, failure.status());
        assertTrue(failure.unconfirmedSlots().isEmpty());
        assertEquals(1, failure.rollbackFailures().size()); assertSame(rollback, failure.rollbackFailures().getFirst().cause());
        assertEquals(1, failure.getSuppressed().length);
    }

    @Test void scopeLossAfterAWritePreventsAllRollbackAccessAndCannotBeRepairedByALaterSuccessfulCheck() {
        var fixture = new Fixture(); AtomicInteger failedChecks = new AtomicInteger();
        fixture.cells.getFirst().afterWrite = value -> { if (!value.same(fixture.cells.getFirst().before)) fixture.scope.owned = false; };
        fixture.scope.onCheck = () -> {
            if (!fixture.scope.owned && failedChecks.incrementAndGet() > 1) fixture.scope.owned = true;
        };
        var failure = failed(fixture.transaction());
        assertEquals(Status.INDETERMINATE, failure.status());
        assertEquals(fixture.cells.stream().map(cell -> cell.name).toList(), failure.unconfirmedSlots());
        assertEquals(1, failedChecks.get(), "Lost exclusion is sticky: do not run even a later scope check again");
        assertEquals(List.of(fixture.cells.getFirst().name + ":publish"), fixture.journal);
        assertTrue(fixture.cells.getFirst().after.same(fixture.cells.getFirst().current));
    }

    @Test void scopeLossInsideAReadPreventsTheFollowingWrite() {
        var fixture = new Fixture(); fixture.cells.getLast().onRead = () -> fixture.scope.owned = false;
        var failure = failed(fixture.transaction());
        assertEquals(Status.REJECTED, failure.status()); assertTrue(fixture.journal.isEmpty()); fixture.assertOriginals();
    }

    @Test void scopeLossDuringRollbackStopsEveryFurtherNativeAccessorAndRetainsTheUnrestoredSet() {
        var fixture = new Fixture(); fixture.verify = () -> { throw new IllegalStateException("native verification"); };
        fixture.cells.getLast().afterWrite = value -> { if (value.same(fixture.cells.getLast().before)) fixture.scope.owned = false; };
        var failure = failed(fixture.transaction());
        assertEquals(Status.INDETERMINATE, failure.status()); assertEquals(SLOT_COUNT, failure.unconfirmedSlots().size());
        assertEquals(SLOT_COUNT + 1, fixture.journal.size());
        assertTrue(fixture.cells.getLast().before.same(fixture.cells.getLast().current));
        assertTrue(fixture.cells.getFirst().after.same(fixture.cells.getFirst().current));
    }

    @Test void synchronousReentryIsRejectedBeforeScopeOrCallbacksAndTheOuterAttemptCanFinish() {
        var fixture = new Fixture(); var current = new AtomicReference<VillagePublicationTransaction>();
        fixture.preflight = () -> {
            int calls = fixture.scope.calls;
            assertThrows(IllegalStateException.class, () -> current.get().publish());
            assertEquals(calls, fixture.scope.calls);
        };
        var transaction = fixture.transaction(); current.set(transaction);
        transaction.publish(); assertEquals(Status.COMMITTED, transaction.status()); fixture.assertPublished();
        int calls = fixture.scope.calls; assertThrows(IllegalStateException.class, transaction::publish); assertEquals(calls, fixture.scope.calls);
    }

    @Test void anotherThreadCannotEnterAnAttemptAlreadyInItsPreflight() throws Exception {
        var fixture = new Fixture(); var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        var problem = new AtomicReference<Throwable>(); fixture.preflight = () -> {
            entered.countDown();
            try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        };
        var transaction = fixture.transaction();
        Thread publisher = new Thread(() -> { try { transaction.publish(); } catch (Throwable failure) { problem.set(failure); } });
        publisher.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            int calls = fixture.scope.calls; assertThrows(IllegalStateException.class, transaction::publish); assertEquals(calls, fixture.scope.calls);
        } finally { proceed.countDown(); publisher.join(5000); }
        assertFalse(publisher.isAlive()); assertNull(problem.get()); assertEquals(Status.COMMITTED, transaction.status()); fixture.assertPublished();
    }

    @Test void constructorRefusesUnboundedMissingDuplicateOrIncompatibleEditsWithoutReadingAnything() {
        var fixture = new Fixture(); Edit<?> first = fixture.edits.getFirst();
        assertThrows(IllegalArgumentException.class, () -> new VillagePublicationTransaction(List.of(), fixture.scope, () -> {}, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> new VillagePublicationTransaction(
                java.util.Collections.nCopies(VillagePublicationTransaction.MAX_EDITS + 1, first), fixture.scope, () -> {}, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> new VillagePublicationTransaction(List.of(first, first), fixture.scope, () -> {}, () -> {}));
        var cell = new Cell<>("same-owner", new Reference(new Object()), new Reference(new Object()), fixture.journal);
        assertThrows(IllegalArgumentException.class, () -> new VillagePublicationTransaction(
                List.of(cell.edit(), new Edit<>("another-name", cell, cell.before, cell.after)), fixture.scope, () -> {}, () -> {}));
        var other = new Cell<>(first.name(), new Reference(new Object()), new Reference(new Object()), fixture.journal);
        assertThrows(IllegalArgumentException.class, () -> new VillagePublicationTransaction(List.of(first, other.edit()), fixture.scope, () -> {}, () -> {}));
        assertThrows(NullPointerException.class, () -> new VillagePublicationTransaction(fixture.edits, null, () -> {}, () -> {}));
        assertThrows(NullPointerException.class, () -> new VillagePublicationTransaction(fixture.edits, fixture.scope, null, () -> {}));
        assertThrows(NullPointerException.class, () -> new VillagePublicationTransaction(fixture.edits, fixture.scope, () -> {}, null));
        assertThrows(IllegalArgumentException.class, () -> new Edit<>(" ", cell, cell.before, cell.after));
        assertTrue(fixture.cells.stream().allMatch(candidate -> candidate.reads == 0)); assertEquals(0, fixture.scope.calls);
    }

    @SuppressWarnings("unchecked")
    private static Cell<Reference> referenceCell(Cell<?> cell) {
        assertInstanceOf(Reference.class, cell.before); return (Cell<Reference>) cell;
    }
}
