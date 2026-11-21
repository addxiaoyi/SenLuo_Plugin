package net.kaleidoscope.cookery.nms;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Native class handling is separate from the legacy configurable Ageable policy. */
public interface NativeSickleAccess {
    enum Kind { MATURE_CROP, IMMATURE_CROP, VEGETATION, OTHER }
    enum Status { NOT_COMMITTED, COMMITTED, PARTIAL }

    interface Snapshot { Kind kind(); }

    /** PARTIAL includes irreversible native statistics/exhaustion; it is never a no-op. */
    record Outcome(Status status, String detail, RuntimeException failure, NativeToolWear wear) {
        public Outcome(Status status, String detail, RuntimeException failure) { this(status, detail, failure, null); }
        public Outcome {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            if (failure != null && status != Status.PARTIAL)
                throw new IllegalArgumentException("A native failure requires an explicit partial outcome");
            if (wear != null && status == Status.NOT_COMMITTED)
                throw new IllegalArgumentException("A rejected native action cannot certify wear");
        }
        public boolean committed() { return status != Status.NOT_COMMITTED; }
        public Outcome withWear(NativeToolWear receipt) { return new Outcome(status, detail, failure, receipt); }
        public static Outcome rejected() { return new Outcome(Status.NOT_COMMITTED, "precommit rejected", null); }
        public static Outcome complete() { return new Outcome(Status.COMMITTED, "complete", null); }
        public static Outcome partial(String detail) { return new Outcome(Status.PARTIAL, detail, null); }
        public static Outcome failed(String detail, RuntimeException failure) {
            return new Outcome(Status.PARTIAL, detail, Objects.requireNonNull(failure));
        }
    }

    Snapshot inspect(Block block);
    boolean matches(Block block, Snapshot snapshot);
    default boolean blacklisted(Snapshot snapshot) { return false; }
    Outcome harvestCrop(Block block, Player player, Snapshot snapshot, BooleanSupplier sourceUnchanged);
    Outcome destroyVegetation(Block block, Player player, Snapshot snapshot, BooleanSupplier sourceUnchanged);
}
