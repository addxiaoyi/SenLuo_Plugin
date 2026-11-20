package net.kaleidoscope.cookery.nms;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Defines the irreversible boundary without hiding native callback side effects as a no-op. */
public final class NativeSickleCropTransaction {
    private NativeSickleCropTransaction() {}

    public interface Operations<T> {
        boolean originalBlockPresent();
        void statisticsAndExhaustion();
        List<T> calculateLootInOriginalWorld();
        boolean commitReset();
        void publish(T drop);
        void afterBreak();
    }

    /** The caller owns a per-block reentry guard for this entire synchronous invocation. */
    public static <T> NativeSickleAccess.Outcome commit(Operations<T> operation, BooleanSupplier sourceUnchanged) {
        if (!operation.originalBlockPresent() || !sourceUnchanged.getAsBoolean()) return NativeSickleAccess.Outcome.rejected();
        // COMMIT: even a throwing native statistics callback may have changed the player.
        try {
            operation.statisticsAndExhaustion();
            if (!operation.originalBlockPresent())
                return NativeSickleAccess.Outcome.partial("native block changed during statistics/exhaustion; no stale reset or own loot");
            if (!sourceUnchanged.getAsBoolean())
                return NativeSickleAccess.Outcome.partial("interaction source changed during statistics/exhaustion; no own loot");
            List<T> drops = List.copyOf(operation.calculateLootInOriginalWorld());
            if (!operation.originalBlockPresent())
                return NativeSickleAccess.Outcome.partial("native block changed during loot calculation; no stale reset or own loot");
            if (!sourceUnchanged.getAsBoolean())
                return NativeSickleAccess.Outcome.partial("interaction source changed during loot calculation; no own loot");
            if (!operation.commitReset())
                return NativeSickleAccess.Outcome.partial("native reset was invalidated; no stale own loot");
            // Native item-spawn callbacks observe the reset. The loot calculation above
            // still observed the old world after native statistics/exhaustion, as source does.
            for (T drop : drops) operation.publish(drop);
            operation.afterBreak();
            return NativeSickleAccess.Outcome.complete();
        } catch (LinkageError failure) {
            return NativeSickleAccess.Outcome.failed("native crop linkage failed after commit",
                    new IllegalStateException("Native crop linkage failure", failure));
        } catch (RuntimeException failure) {
            return NativeSickleAccess.Outcome.failed(
                    "native crop partially committed; statistics/exhaustion or output may already exist", failure);
        }
    }
}
