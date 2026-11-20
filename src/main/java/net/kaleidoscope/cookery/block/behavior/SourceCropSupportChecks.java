package net.kaleidoscope.cookery.block.behavior;

import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import net.kaleidoscope.cookery.plugin.CeResourceReadGate;
import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;

/** Retains observed checks across CE reloads without keeping chunks or regions alive. */
public final class SourceCropSupportChecks implements Listener, AutoCloseable {
    private static final int MAX_PENDING = 2048;
    private static final int MAX_CHECKS_PER_TICK = 16;

    @FunctionalInterface
    interface Recheck {
        void check(World world, BlockPos pos, Key definitionId);
    }

    private record Target(UUID worldId, BlockPos pos, Key definitionId) {}

    private static final class Pending {
        final Target target;
        final World world;
        final int delayTicks;
        io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduledTask;
        boolean dispatching;
        boolean running;

        Pending(Target target, World world, int delayTicks) {
            this.target = target;
            this.world = world;
            this.delayTicks = delayTicks;
        }

        boolean ownedAndLoaded() {
            BlockPos pos = target.pos();
            return owned(world, pos) && world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4);
        }
    }

    private final Map<Target, Pending> pending = new LinkedHashMap<>();
    private final int capacity;
    private final int tickBudget;
    private final Supplier<CeResourceReadGate.Guard> admission;
    private final Recheck recheck;
    private final RegionScheduler scheduler;
    private final Supplier<org.bukkit.plugin.Plugin> plugin;
    private final AtomicInteger remainingDispatches;
    private final AtomicInteger remainingChecks;
    private volatile boolean closed;

    public SourceCropSupportChecks() {
        this(MAX_PENDING, MAX_CHECKS_PER_TICK, KaleidoscopeCookeryPlugin::tryEnterSourceCropResources,
                SourceCropBehavior::recheckSupport, Bukkit.getRegionScheduler(), KaleidoscopeCookeryPlugin::instance);
    }

    SourceCropSupportChecks(int capacity, int tickBudget, Supplier<CeResourceReadGate.Guard> admission,
                            Recheck recheck, RegionScheduler scheduler,
                            Supplier<org.bukkit.plugin.Plugin> plugin) {
        if (capacity < 1 || tickBudget < 1) throw new IllegalArgumentException("Support queue limits must be positive");
        this.capacity = capacity;
        this.tickBudget = tickBudget;
        this.admission = Objects.requireNonNull(admission);
        this.recheck = Objects.requireNonNull(recheck);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.plugin = Objects.requireNonNull(plugin);
        this.remainingDispatches = new AtomicInteger(tickBudget);
        this.remainingChecks = new AtomicInteger(tickBudget);
    }

    /** Returns false if the caller must fall back to native scheduling. */
    public boolean schedule(World world, BlockPos pos, Key definitionId, int delayTicks) {
        if (delayTicks < 1 || delayTicks > 2) throw new IllegalArgumentException("Expected one or two owner ticks");
        if (closed || world == null || pos == null || definitionId == null) return false;
        Target target = new Target(world.getUID(), new BlockPos(pos.x(), pos.y(), pos.z()), definitionId);
        Pending added;
        synchronized (pending) {
            if (closed) return false;
            Pending previous = pending.get(target);
            if (previous != null && previous.world == world && previous.delayTicks <= delayTicks) return true;
            if (previous == null && pending.size() >= capacity) return false;
            if (previous != null && previous.scheduledTask != null) previous.scheduledTask.cancel();
            added = new Pending(target, world, delayTicks);
            pending.put(target, added);
        }
        // Initial callbacks use the same bounded budget as retries.  A reload can
        // produce a burst of shape updates; dispatching every new intent here would
        // bypass the per-tick cap before ServerTickStartEvent gets a chance to run.
        if (claimDispatch(added)) dispatch(added, delayTicks);
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTickStart(ServerTickStartEvent event) {
        // This is a global event. It only resets a bounded dispatch budget; all world access stays
        // inside RegionScheduler callbacks on the owning region thread.
        List<Pending> retryDispatch = new ArrayList<>();
        synchronized (pending) {
            if (closed) return;
            remainingDispatches.set(tickBudget);
            remainingChecks.set(tickBudget);
            for (Pending candidate : pending.values()) {
                if (!candidate.running && candidate.scheduledTask == null
                        && !candidate.dispatching && retryDispatch.size() < tickBudget && tryReserveDispatch()) {
                    candidate.dispatching = true;
                    retryDispatch.add(candidate);
                }
            }
        }
        for (Pending candidate : retryDispatch) dispatch(candidate, candidate.delayTicks);
    }

    private void dispatch(Pending candidate, long delayTicks) {
        try {
            var owner = plugin.get();
            if (closed || owner == null || !owner.isEnabled()) {
                clearDispatchClaim(candidate);
                return;
            }
            var task = scheduler.runDelayed(owner, candidate.world, candidate.target.pos().x() >> 4,
                    candidate.target.pos().z() >> 4, ignored -> runOnOwner(candidate), Math.max(1, delayTicks));
            synchronized (pending) {
                if (!closed && pending.get(candidate.target) == candidate
                        && candidate.dispatching && !candidate.running) {
                    candidate.scheduledTask = task;
                    candidate.dispatching = false;
                } else if (task != null) {
                    task.cancel();
                    if (pending.get(candidate.target) == candidate) candidate.dispatching = false;
                }
            }
        } catch (RuntimeException ignored) {
            // A region being torn down may reject a task; retain the bounded record for the next
            // native callback or a subsequent duplicate schedule.
            clearDispatchClaim(candidate);
        } catch (LinkageError problem) {
            clearDispatchClaim(candidate);
            throw problem;
        }
    }

    private boolean claimDispatch(Pending candidate) {
        synchronized (pending) {
            if (closed || pending.get(candidate.target) != candidate || candidate.running
                    || candidate.scheduledTask != null || candidate.dispatching || !tryReserveDispatch()) return false;
            candidate.dispatching = true;
            return true;
        }
    }

    private void clearDispatchClaim(Pending candidate) {
        synchronized (pending) {
            if (pending.get(candidate.target) == candidate) candidate.dispatching = false;
        }
    }

    private boolean tryReserveDispatch() {
        return remainingDispatches.getAndUpdate(value -> value > 0 ? value - 1 : value) > 0;
    }

    private void runOnOwner(Pending candidate) {
        synchronized (pending) {
            if (closed || pending.get(candidate.target) != candidate || candidate.running) return;
            candidate.scheduledTask = null;
            candidate.running = true;
        }
        try {
            if (!candidate.ownedAndLoaded()) {
                retry(candidate);
                return;
            }
            if (remainingChecks.getAndUpdate(value -> value > 0 ? value - 1 : value) <= 0) {
                retry(candidate);
                return;
            }
            try (var guard = admission.get()) {
                if (guard == null) {
                    retry(candidate);
                    return;
                }
                synchronized (pending) {
                    if (closed || !pending.remove(candidate.target, candidate)) return;
                }
                recheck.check(candidate.world, candidate.target.pos(), candidate.target.definitionId());
            }
        } catch (RuntimeException | LinkageError problem) {
            // A poisoned/invalid CE read or a region probe failure must not leave this
            // entry running forever. Preserve the failure for the scheduler to report.
            discard(candidate);
            throw problem;
        }
    }

    private void retry(Pending candidate) {
        synchronized (pending) {
            if (closed || pending.get(candidate.target) != candidate) return;
            candidate.running = false;
            // Requeue without scheduling from the owner callback. The global tick
            // budget is the sole source of retry dispatches, and rotating the entry
            // prevents a repeatedly unavailable region from starving later entries.
            pending.remove(candidate.target);
            pending.put(candidate.target, candidate);
        }
    }

    private void discard(Pending candidate) {
        synchronized (pending) {
            if (pending.remove(candidate.target, candidate)) {
                candidate.running = false;
                if (candidate.scheduledTask != null) {
                    candidate.scheduledTask.cancel();
                    candidate.scheduledTask = null;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (event.isCancelled()) return;
        World world = event.getWorld();
        synchronized (pending) {
            pending.values().removeIf(candidate -> {
                if (candidate.world != world) return false;
                if (candidate.scheduledTask != null) candidate.scheduledTask.cancel();
                return true;
            });
        }
    }

    @Override
    public void close() {
        synchronized (pending) {
            closed = true;
            pending.values().forEach(candidate -> {
                if (candidate.scheduledTask != null) candidate.scheduledTask.cancel();
            });
            pending.clear();
        }
    }

    private static boolean owned(World world, BlockPos pos) {
        return Bukkit.isOwnedByCurrentRegion(world, pos.x() >> 4, pos.z() >> 4);
    }
}
