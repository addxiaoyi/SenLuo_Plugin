package net.kaleidoscope.cookery.plugin;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kaleidoscope.cookery.block.behavior.SourceCropBehavior;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.bukkit.world.BukkitWorldManager;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelAccessorProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Replays support checks which CE can discard while its native delegates are unloaded. */
public final class SourceCropSupportRecovery implements Listener, AutoCloseable {
    static final int DISPATCH_BUDGET = 4;
    static final int BLOCK_BUDGET = 256;
    private static final Set<Key> CROPS = Set.of(Key.of("kaleidoscopecookery:tomato_crop"),
            Key.of("kaleidoscopecookery:chili_crop"), Key.of("kaleidoscopecookery:lettuce_crop"));

    private record ChunkKey(UUID world, int x, int z) {}
    private static final class Scan {
        final ChunkKey key;
        final World world;
        final AtomicBoolean restart = new AtomicBoolean();
        int section, offset;
        Scan(World world, int x, int z) { this.world = world; key = new ChunkKey(world.getUID(), x, z); }
    }

    private final KaleidoscopeCookeryPlugin plugin;
    private final CeResourceReadGate gate;
    @FunctionalInterface
    interface ChunkLookup { CEChunk find(World world, int x, int z); }
    private final ChunkLookup loadedChunk;
    private final BiConsumer<World, BlockPos> scheduleSupport;
    private final ConcurrentHashMap<ChunkKey, Scan> pending = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Scan> queue = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;
    private ScheduledTask dispatcher;

    public SourceCropSupportRecovery(KaleidoscopeCookeryPlugin plugin, CeResourceReadGate gate) {
        this(plugin, gate, SourceCropSupportRecovery::loadedChunk, SourceCropSupportRecovery::scheduleCurrent);
    }

    SourceCropSupportRecovery(KaleidoscopeCookeryPlugin plugin, CeResourceReadGate gate,
                              ChunkLookup loadedChunk, BiConsumer<World, BlockPos> scheduleSupport) {
        this.plugin = plugin;
        this.gate = gate;
        this.loadedChunk = loadedChunk;
        this.scheduleSupport = scheduleSupport;
    }

    public void start() {
        dispatcher = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> dispatch(), 1, 1);
    }

    /** Called on the global scheduler after publication; only captures loaded chunk addresses. */
    public void recheckLoadedChunks() {
        if (closed || !plugin.isEnabled()) return;
        for (World world : Bukkit.getWorlds())
            for (var chunk : world.getLoadedChunks()) enqueue(world, chunk.getX(), chunk.getZ(), true);
    }

    /** Native level/position conversion uses no CE block definitions or chunk reads. */
    public void deferTick(Object level, Object rawPos) {
        if (closed || !plugin.isEnabled()) return;
        var pos = LocationUtils.fromBlockPos(rawPos);
        requestRecheck(LevelProxy.INSTANCE.getWorld(level), pos.x() >> 4, pos.z() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        var chunk = event.getChunk();
        enqueue(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        var chunk = event.getChunk();
        pending.remove(new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        pending.keySet().removeIf(key -> key.world().equals(world));
    }

    void enqueue(World world, int x, int z) {
        enqueue(world, x, z, false);
    }

    void requestRecheck(World world, int x, int z) {
        if (closed) return;
        Scan fresh = new Scan(world, x, z);
        Scan selected = pending.compute(fresh.key, (key, current) -> {
            if (current == null) return fresh;
            current.restart.set(true);
            return current;
        });
        if (selected == fresh) queue.add(fresh);
    }

    private void enqueue(World world, int x, int z, boolean restart) {
        if (closed) return;
        Scan scan = new Scan(world, x, z);
        // A new resource generation must revisit earlier sections too. Replacing
        // the scan identity also retires any old in-flight continuation.
        if (restart) { pending.put(scan.key, scan); queue.add(scan); }
        else if (pending.putIfAbsent(scan.key, scan) == null) queue.add(scan);
    }

    void dispatch() {
        if (closed || !plugin.isEnabled()) return;
        // Stale queue entries count toward the budget too, so unloading a large
        // world cannot turn one global tick into an unbounded cleanup pass.
        for (int i = 0; i < DISPATCH_BUDGET; i++) {
            Scan scan = queue.poll();
            if (scan == null) return;
            if (pending.get(scan.key) != scan) continue;
            try {
                Bukkit.getRegionScheduler().run(plugin, scan.world, scan.key.x(), scan.key.z(), task -> resume(scan));
            } catch (RuntimeException failure) {
                pending.remove(scan.key, scan);
                if (!closed && plugin.isEnabled()) plugin.getLogger().warning("Could not schedule crop support recovery for "
                        + scan.key + ": " + failure.getMessage());
            }
        }
    }

    private void resume(Scan scan) {
        if (closed || !plugin.isEnabled() || pending.get(scan.key) != scan) return;
        boolean complete = true;
        boolean allowRestart = false;
        try {
            if (Bukkit.getWorld(scan.key.world()) != scan.world) return;
            if (!Bukkit.isOwnedByCurrentRegion(scan.world, scan.key.x(), scan.key.z())) { complete = false; return; }
            if (!scan.world.isChunkLoaded(scan.key.x(), scan.key.z())) return;
            allowRestart = true;
            try (var guard = gate.tryEnter().guard()) {
                if (guard == null) { complete = false; return; }
                CEChunk chunk = loadedChunk.find(scan.world, scan.key.x(), scan.key.z());
                if (chunk == null || !chunk.isLoaded()) { complete = false; return; }
                if (scan.restart.getAndSet(false)) { scan.section = 0; scan.offset = 0; }
                complete = scanSlice(scan, chunk);
            }
        } catch (RuntimeException failure) {
            plugin.getLogger().severe("Crop support recovery failed for " + scan.key + ": " + failure.getMessage());
        } finally {
            boolean retry = !complete;
            boolean canRestart = allowRestart;
            pending.compute(scan.key, (key, current) -> {
                if (current != scan) return current;
                // Serialize completion with a simultaneous deferred callback. A
                // late request must either restart this scan or create a new one.
                if (!closed && (retry || canRestart && scan.restart.get())) { queue.add(scan); return scan; }
                return null;
            });
        }
    }

    private boolean scanSlice(Scan scan, CEChunk chunk) {
        var sections = chunk.sections();
        int examined = 0;
        while (scan.section < sections.length && examined < BLOCK_BUDGET) {
            var section = sections[scan.section];
            if (section == null || section.isEmpty() || !section.statesContainer().hasAny(SourceCropSupportRecovery::isCrop)) {
                scan.section++;
                scan.offset = 0;
                continue;
            }
            while (scan.offset < 4096 && examined < BLOCK_BUDGET) {
                examined++;
                int index = scan.offset++;
                if (!isCrop(section.getBlockState(index))) continue;
                var pos = new BlockPos((scan.key.x() << 4) + (index & 15),
                        (section.sectionY() << 4) + (index >> 8), (scan.key.z() << 4) + ((index >> 4) & 15));
                // The CE palette is only a coarse index. The actual native state
                // and its current behavior are looked up again before scheduling.
                scheduleSupport.accept(scan.world, pos);
            }
            if (scan.offset == 4096) { scan.section++; scan.offset = 0; }
        }
        return scan.section == sections.length;
    }

    private static boolean isCrop(ImmutableBlockState state) {
        return state != null && !state.isEmpty() && CROPS.contains(state.owner().value().id());
    }

    private static CEChunk loadedChunk(World world, int x, int z) {
        var ceWorld = BukkitWorldManager.instance().getWorld(world.getUID());
        var storage = ceWorld == null ? null : ceWorld.storageWorld();
        return storage == null ? null : storage.getChunkAtIfLoaded(x, z);
    }

    static void scheduleCurrent(World world, BlockPos pos) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(world.getBlockAt(pos.x(), pos.y(), pos.z()));
        if (!isCrop(state) || state.behavior().getFirst(SourceCropBehavior.class) == null) return;
        Object rawState = state.customBlockState().minecraftState();
        var ceWorld = BukkitWorldManager.instance().getWorld(world.getUID());
        if (ceWorld != null) LevelAccessorProxy.INSTANCE.scheduleTick$0(ceWorld.minecraftWorld(),
                LocationUtils.toBlockPos(pos), BlockStateUtils.getBlockOwner(rawState), 1);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (dispatcher != null) dispatcher.cancel();
        pending.clear();
        queue.clear();
    }
}
