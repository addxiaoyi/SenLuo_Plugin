package net.kaleidoscope.cookery.worldgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dormant, synchronous publication primitive. This class neither acquires an exclusion barrier nor
 * supplies a lifecycle caller. Its caller must already own a real scope excluding every relevant
 * generation reader and resource/cache writer, and retain it through failure recovery. Scope checks
 * certify that externally enforced ownership; a flag or CE lease alone does not establish it.
 * No process-crash, arbitrary accessor-side-effect, or lost-exclusion atomicity is promised.
 */
public final class VillagePublicationTransaction {
    public static final int MAX_EDITS = 32;

    public enum Status { NEW, PUBLISHING, COMMITTED, REJECTED, ROLLED_BACK, INDETERMINATE }

    /** Identity-bearing native fields and cache entries must never use their values' equals method. */
    public sealed interface Value permits Reference, MapEntry, IntValue {
        boolean same(Value observed);
    }

    public record Reference(Object value) implements Value {
        @Override public boolean same(Value observed) {
            return observed instanceof Reference reference && reference.value == value;
        }
    }

    /** An absent cache entry is distinct from a present entry, including one whose value is null. */
    public record MapEntry(boolean present, Object value) implements Value {
        public MapEntry {
            if (!present && value != null) throw new IllegalArgumentException("An absent entry cannot have a value");
        }
        @Override public boolean same(Value observed) {
            return observed instanceof MapEntry entry && entry.present == present && entry.value == value;
        }
    }

    public record IntValue(int value) implements Value {
        @Override public boolean same(Value observed) {
            return observed instanceof IntValue integer && integer.value == value;
        }
    }

    /** Narrow accessors must touch only this slot and return a non-null typed state. */
    public interface Slot<V extends Value> {
        V read();
        void write(V value);
    }

    public record Edit<V extends Value>(String name, Slot<V> slot, V before, V after) {
        public Edit {
            if (name == null || name.isBlank() || name.length() > 256)
                throw new IllegalArgumentException("A bounded publication edit name is required");
            Objects.requireNonNull(slot, "Publication slot");
            Objects.requireNonNull(before, "Expected original slot state");
            Objects.requireNonNull(after, "Prepared slot state");
            if (before.getClass() != after.getClass())
                throw new IllegalArgumentException("Publication state kinds differ");
        }
        public boolean changesValue() { return !before.same(after); }
    }

    /**
     * Check the caller's already-held, non-reentrant exclusion ownership and resource generation.
     * Implementations must throw if ownership/generation has been lost, and must not regain an old
     * scope after losing it. The first failed check permanently stops this transaction's accessors.
     * The caller releases the scope only after publish returns or throws, including failed rollback.
     */
    @FunctionalInterface public interface ExclusionScope { void check(); }

    public record Receipt(int changedSlots, int noOpSlots) {}
    public record RollbackFailure(String edit, String phase, Throwable cause) {
        public RollbackFailure {
            Objects.requireNonNull(edit); Objects.requireNonNull(phase); Objects.requireNonNull(cause);
        }
    }

    public static final class PublicationFailure extends IllegalStateException {
        private final Status status;
        private final List<RollbackFailure> rollbackFailures;
        private final List<String> unconfirmedSlots;

        private PublicationFailure(Status status, Throwable cause, List<RollbackFailure> rollbackFailures,
                                   List<String> unconfirmedSlots) {
            super("Village publication failed: " + status, cause);
            this.status = status;
            this.rollbackFailures = List.copyOf(rollbackFailures);
            this.unconfirmedSlots = List.copyOf(unconfirmedSlots);
            for (RollbackFailure failure : rollbackFailures)
                addSuppressed(new IllegalStateException("Rollback " + failure.edit() + " at " + failure.phase(), failure.cause()));
        }

        public Status status() { return status; }
        public List<RollbackFailure> rollbackFailures() { return rollbackFailures; }
        /** Empty does not clear reported rollback faults or turn a thrown failure into success. */
        public List<String> unconfirmedSlots() { return unconfirmedSlots; }
    }

    private final List<Edit<?>> edits;
    private final ExclusionScope scope;
    private final Runnable preflight;
    private final Runnable verifyPublished;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.NEW);

    /**
     * Capture a complete bounded write set. The native adapter must also reject aliases expressed by
     * distinct Slot wrappers for the same owner/field or cache/key; edit labels alone cannot prove it.
     * Preflight and final validation must be read-only and bounded, as must all supplied accessors.
     */
    public VillagePublicationTransaction(List<? extends Edit<?>> edits, ExclusionScope scope,
                                         Runnable preflight, Runnable verifyPublished) {
        Objects.requireNonNull(edits, "Publication edits");
        if (edits.isEmpty() || edits.size() > MAX_EDITS)
            throw new IllegalArgumentException("Village publication edit count is outside its bound");
        this.edits = List.copyOf(edits);
        Set<String> names = new LinkedHashSet<>();
        Set<Slot<?>> slots = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Edit<?> edit : this.edits)
            if (!names.add(edit.name()) || !slots.add(edit.slot()))
                throw new IllegalArgumentException("Duplicate village publication edit");
        this.scope = Objects.requireNonNull(scope, "Already-held publication exclusion scope");
        this.preflight = Objects.requireNonNull(preflight, "Publication preflight");
        this.verifyPublished = Objects.requireNonNull(verifyPublished, "Published-state verification");
    }

    public Status status() { return status.get(); }

    /** One attempt only; synchronous/concurrent reentry is rejected before any scope or callback. */
    public Receipt publish() {
        if (!status.compareAndSet(Status.NEW, Status.PUBLISHING))
            throw new IllegalStateException("Village publication is one-shot and cannot be reentered");
        Attempt attempt = new Attempt();
        try {
            attempt.checkScope();
            preflight.run();
            attempt.checkScope();
            for (Edit<?> edit : edits) attempt.requireState(edit, edit.before(), "preflight");
            for (Edit<?> edit : edits) {
                if (!edit.changesValue()) continue;
                attempt.requireState(edit, edit.before(), "before write");
                attempt.checkScope();
                // Include this slot before invocation: an accessor can throw after changing it.
                attempt.attempted.add(edit);
                write(edit, edit.after());
                attempt.requireState(edit, edit.after(), "after write");
            }
            for (Edit<?> edit : edits) attempt.requireState(edit, edit.after(), "before verification");
            attempt.checkScope();
            verifyPublished.run();
            attempt.checkScope();
            for (Edit<?> edit : edits) attempt.requireState(edit, edit.after(), "after verification");
            attempt.checkScope();
            status.set(Status.COMMITTED);
            return new Receipt(attempt.attempted.size(), edits.size() - attempt.attempted.size());
        } catch (RuntimeException | Error problem) {
            if (attempt.attempted.isEmpty()) {
                status.set(Status.REJECTED);
                throw new PublicationFailure(Status.REJECTED, problem, List.of(), List.of());
            }
            List<RollbackFailure> failures = new ArrayList<>();
            for (int index = attempt.attempted.size() - 1; index >= 0; index--) {
                Edit<?> edit = attempt.attempted.get(index);
                try {
                    Value current = attempt.read(edit);
                    if (edit.before().same(current)) continue;
                    if (!edit.after().same(current))
                        throw new IllegalStateException("Current slot is not the original or this transaction's prepared value");
                    attempt.checkScope();
                    write(edit, edit.before());
                    attempt.requireState(edit, edit.before(), "after restore");
                } catch (RuntimeException | Error rollback) {
                    failures.add(new RollbackFailure(edit.name(), "restore", rollback));
                }
            }
            // Also inspect unattempted and no-op slots. Undeclared accessor side effects cannot be
            // repaired by overwriting unknown state, but must never escape a clean rollback report.
            List<String> unconfirmed = new ArrayList<>();
            for (Edit<?> edit : edits) {
                try { attempt.requireState(edit, edit.before(), "rollback verification"); }
                catch (RuntimeException | Error verification) {
                    unconfirmed.add(edit.name());
                    failures.add(new RollbackFailure(edit.name(), "verification", verification));
                }
            }
            Status terminal = failures.isEmpty() ? Status.ROLLED_BACK : Status.INDETERMINATE;
            status.set(terminal);
            throw new PublicationFailure(terminal, problem, failures, unconfirmed);
        }
    }

    @SuppressWarnings("unchecked")
    private static <V extends Value> void write(Edit<V> edit, Value value) { edit.slot().write((V) value); }

    private final class Attempt {
        final List<Edit<?>> attempted = new ArrayList<>();
        boolean exclusionLost;

        void checkScope() {
            if (exclusionLost) throw new IllegalStateException("Publication exclusion was already lost");
            try { scope.check(); }
            catch (RuntimeException | Error problem) { exclusionLost = true; throw problem; }
        }

        Value read(Edit<?> edit) {
            checkScope();
            Value value = Objects.requireNonNull(edit.slot().read(), "Publication slot returned no state");
            checkScope();
            return value;
        }

        void requireState(Edit<?> edit, Value expected, String phase) {
            if (!expected.same(read(edit)))
                throw new IllegalStateException("Village slot changed at " + phase + ": " + edit.name());
        }
    }
}
