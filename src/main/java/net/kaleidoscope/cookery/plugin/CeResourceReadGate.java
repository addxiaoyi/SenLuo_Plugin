package net.kaleidoscope.cookery.plugin;

import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Shares one real CE lease among bounded synchronous readers. This primitive is
 * used only by the source-crop random-growth and harvest callbacks. It does not
 * exclude lifecycle writers which bypass CE's coordinator; other crop/native
 * entry points remain outside this first-stage contract.
 */
public final class CeResourceReadGate {
    public enum Rejection { CLOSED, BUSY, TRANSITION, STALE, POISONED }

    public record Admission(Guard guard, Rejection rejection) {
        public Admission {
            if ((guard == null) == (rejection == null)) throw new IllegalArgumentException("Exactly one admission result is required");
        }
        public boolean admitted() { return guard != null; }
    }

    private sealed interface State {}
    private record Closed() implements State {}
    private record Binding(Object generation, BooleanSupplier stillCurrent) {}
    record Publication(Object generation, BooleanSupplier stillCurrent, Runnable publish) {
        Publication {
            Objects.requireNonNull(generation);
            Objects.requireNonNull(stillCurrent);
            Objects.requireNonNull(publish);
        }
    }
    private record Idle(Binding binding) implements State {}
    private static final class Publishing implements State {}
    private record Acquiring(Binding binding) implements State {}
    private record Epoch(Binding binding, ResourceOperationCoordinator.Lease lease) {}
    private record Active(Epoch epoch, int readers, boolean admitting) implements State {}
    private record Closing(Epoch epoch) implements State {}

    private final ResourceOperationCoordinator coordinator;
    private final BooleanSupplier lifecycleReady;
    private final AtomicReference<State> state = new AtomicReference<>(new Closed());
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Map<Rejection, LongAdder> rejections = new EnumMap<>(Rejection.class);

    public CeResourceReadGate(ResourceOperationCoordinator coordinator, BooleanSupplier lifecycleReady) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.lifecycleReady = Objects.requireNonNull(lifecycleReady);
        for (Rejection reason : Rejection.values()) rejections.put(reason, new LongAdder());
    }

    /**
     * Validate a complete generation under the actual exclusive CE lease. The
     * validator must keep checking the captured registry identities on entry;
     * a name, slot number or isReloading=false alone cannot certify a generation.
     * Busy publication leaves the gate closed. Never waits for CE or a region.
     */
    public boolean publish(Object generation, BooleanSupplier stillCurrent) {
        return publishWithLease(() -> new Publication(generation, stillCurrent, () -> {}));
    }

    /** Prepare, bind, and validate a generation while holding the one lease that excludes readers. */
    boolean publishWithLease(Supplier<Publication> prepare) {
        Objects.requireNonNull(prepare);
        if (failure.get() != null) return false;
        State previous = state.get();
        if (!(previous instanceof Closed || previous instanceof Idle)) return false;
        Publishing publishing = new Publishing();
        if (!state.compareAndSet(previous, publishing)) return false;
        ResourceOperationCoordinator.Lease lease = null;
        try {
            lease = coordinator.acquire();
            Publication publication = prepare.get();
            if (publication == null) return false;
            Binding binding = new Binding(publication.generation(), publication.stillCurrent());
            if (failure.get() != null || !current(binding)) return false;
            publication.publish().run();
            if (failure.get() != null || !current(binding)) return false;
            return state.compareAndSet(publishing, new Idle(binding));
        } catch (ResourceOperationCoordinator.BusyException busy) {
            rejections.get(Rejection.BUSY).increment();
            return false;
        } catch (RuntimeException | LinkageError problem) {
            poison(problem); throw problem;
        } finally {
            if (lease != null) release(lease);
            state.compareAndSet(publishing, new Closed());
        }
    }

    /** New readers fail immediately during a transition or external CE operation. */
    public Admission tryEnter() {
        for (;;) {
            if (failure.get() != null) return rejected(Rejection.POISONED);
            State observed = state.get();
            if (observed instanceof Closed) return rejected(Rejection.CLOSED);
            if (observed instanceof Active active) {
                if (!active.admitting()) return rejected(Rejection.STALE);
                if (active.readers() == Integer.MAX_VALUE) throw new IllegalStateException("CE reader count overflow");
                if (!state.compareAndSet(active, new Active(active.epoch(), active.readers() + 1, true))) continue;
                Guard guard = new Guard(active.epoch());
                boolean admitted = false;
                try {
                    // Join before consulting registry identities. Otherwise the
                    // previous last reader could release CE while this validator
                    // was still reading its captured generation.
                    if (failure.get() != null) return rejected(Rejection.POISONED);
                    if (!current(active.epoch().binding())) { invalidate(); return rejected(Rejection.STALE); }
                    admitted = true;
                    return new Admission(guard, null);
                } catch (RuntimeException | LinkageError problem) { poison(problem); throw problem; }
                finally { if (!admitted) guard.close(); }
            }
            if (!(observed instanceof Idle idle)) return rejected(Rejection.TRANSITION);
            Acquiring acquiring = new Acquiring(idle.binding());
            if (!state.compareAndSet(idle, acquiring)) continue;
            ResourceOperationCoordinator.Lease lease = null;
            boolean transferred = false;
            try {
                lease = coordinator.acquire();
                if (failure.get() != null || !current(idle.binding())) return rejected(Rejection.STALE);
                Epoch epoch = new Epoch(idle.binding(), lease);
                if (!state.compareAndSet(acquiring, new Active(epoch, 1, true))) return rejected(Rejection.STALE);
                transferred = true;
                return new Admission(new Guard(epoch), null);
            } catch (ResourceOperationCoordinator.BusyException busy) {
                state.compareAndSet(acquiring, idle);
                return rejected(Rejection.BUSY);
            } catch (RuntimeException | LinkageError problem) {
                poison(problem); throw problem;
            } finally {
                if (!transferred && lease != null) release(lease);
                state.compareAndSet(acquiring, new Closed());
            }
        }
    }

    /** Stops admission; already admitted operations retain their lease until all return. */
    public void invalidate() {
        for (;;) {
            State observed = state.get();
            State next = observed instanceof Active active
                    ? new Active(active.epoch(), active.readers(), false) : new Closed();
            if (state.compareAndSet(observed, next)) return;
        }
    }

    public Throwable failure() { return failure.get(); }

    public Map<Rejection, Long> rejectedCounts() {
        var result = new EnumMap<Rejection, Long>(Rejection.class);
        rejections.forEach((key, value) -> result.put(key, value.sum()));
        return Map.copyOf(result);
    }

    private boolean current(Binding binding) {
        return lifecycleReady.getAsBoolean() && binding.stillCurrent().getAsBoolean();
    }

    private Admission rejected(Rejection reason) {
        rejections.get(reason).increment(); return new Admission(null, reason);
    }

    private void poison(Throwable problem) {
        failure.compareAndSet(null, problem); invalidate();
    }

    private void release(ResourceOperationCoordinator.Lease lease) {
        try { lease.close(); }
        catch (RuntimeException | LinkageError problem) { poison(problem); throw problem; }
    }

    public final class Guard implements AutoCloseable {
        private final Epoch epoch;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Guard(Epoch epoch) { this.epoch = epoch; }
        public Object generation() { return epoch.binding().generation(); }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            for (;;) {
                State observed = state.get();
                if (!(observed instanceof Active active) || active.epoch() != epoch || active.readers() <= 0) {
                    var problem = new IllegalStateException("CE read lease ownership was lost");
                    poison(problem); throw problem;
                }
                if (active.readers() > 1) {
                    if (state.compareAndSet(active, new Active(epoch, active.readers() - 1, active.admitting()))) return;
                    continue;
                }
                Closing closing = new Closing(epoch);
                if (!state.compareAndSet(active, closing)) continue;
                try { release(epoch.lease()); }
                finally {
                    State next = active.admitting() && failure.get() == null ? new Idle(epoch.binding()) : new Closed();
                    // invalidate() or a newer publication may have superseded this
                    // transition; neither is overwritten by the old last reader.
                    state.compareAndSet(closing, next);
                }
                return;
            }
        }
    }
}
