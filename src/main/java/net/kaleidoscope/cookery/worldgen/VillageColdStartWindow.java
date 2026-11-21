package net.kaleidoscope.cookery.worldgen;

import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dormant narrow initial-startup ownership. No task registration or publication caller is supplied.
 * The version adapter accepts only the pinned synchronous CE after-enable call on the actual native
 * server thread before normal initial world creation. It is not an online generation/reload barrier.
 * Actual target runtime acceptance, including this stack-window predicate, remains required.
 */
public interface VillageColdStartWindow extends VillagePublicationTransaction.ExclusionScope, AutoCloseable {
    Object nativeRegistryAccess();
    Object nativeStructureTemplateManager();
    @Override void close();

    static VillageColdStartWindow open(String minecraftVersion) {
        if (!"1.21.11".equals(minecraftVersion))
            throw new UnsupportedOperationException("Village cold-start ownership supports pinned Paper/Folia 1.21.11 only");
        try {
            return (VillageColdStartWindow) Class.forName("net.kaleidoscope.cookery.nms.v1_21_R7.VillageColdStartWindowNative")
                    .getMethod("open").invoke(null);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException problem) throw problem;
            if (cause instanceof Error problem) throw problem;
            throw new IllegalStateException("Cannot acquire native village cold-start ownership", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot load native village cold-start ownership", failure);
        }
    }

    /**
     * Owns only one actual CE lease and its thread/closed/failure state. This helper deliberately does
     * not implement ExclusionScope or certify generation, native roots, or startup ordering. The R7
     * window privately owns it; callers never receive its real lease or an injectable native window.
     */
    final class LeaseOwnership implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final ResourceOperationCoordinator.Lease lease;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Throwable failure;

        public LeaseOwnership(ResourceOperationCoordinator coordinator) {
            lease = Objects.requireNonNull(coordinator, "CE resource coordinator").acquire();
        }

        public void check() {
            if (Thread.currentThread() != owner) throw poison(new IllegalStateException("Cold-start scope is owned by another thread"));
            if (closed.get()) throw new IllegalStateException("Cold-start scope is closed");
            if (failure != null) throw new IllegalStateException("Cold-start scope failed and cannot be reused", failure);
        }

        public <T extends Throwable> T poison(T problem) {
            Objects.requireNonNull(problem, "Cold-start failure");
            synchronized (this) { if (failure == null) failure = problem; }
            return problem;
        }

        /** Owner finally may release after failure/stack unwind; a foreign close cannot drop protection. */
        @Override public void close() {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException("Only the cold-start owner may close its lease");
            if (closed.compareAndSet(false, true)) lease.close();
        }
    }
}
