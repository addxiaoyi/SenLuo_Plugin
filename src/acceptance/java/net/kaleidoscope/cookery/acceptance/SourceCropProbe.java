package net.kaleidoscope.cookery.acceptance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.kaleidoscope.cookery.api.event.SickleHarvestEvent;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockAndLightGetterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Opt-in console fixture and observations. Plant/use/dig packets must come from real players.
 * No production behavior, growth, harvest, or shape-install method is invoked by this probe.
 */
final class SourceCropProbe implements Listener {
    private static final int X = 720, Y = 200, Z = 0, CX = 45, CZ = 0;
    private static final String FIRST = "SenLuoProbeA", SECOND = "SenLuoProbeB", PREFIX = "kaleidoscopecookery:";
    private static final List<String> CROPS = List.of("tomato", "chili", "lettuce");
    private static final Set<String> ACTIONS = Set.of("arrive", "observe", "shapes", "reloadstate", "stagearena",
            "stageplant", "stageages", "stagepick", "stageimmature", "stagebone", "stagemature", "stagesickle",
            "stagebreak", "stagenatural", "stagewet", "stagedry", "stagedense", "stageinvalid", "stageboundary", "stageboundarygrowth",
            "stagelight9", "stagelight8", "stagelight7", "stagesky", "supportstone", "stock", "stockfortune", "stockempty", "creative", "survival",
            "tiledropsoff", "tiledropson", "naturalstart", "naturalstop", "armgrow", "armfertilize", "arminteract",
            "armgrowtomato", "armgrowchili", "armgrowlettuce", "armgrowwheat",
            "armfertilizetomato", "armfertilizechili", "armfertilizelettuce", "armfertilizewheat",
            "armoffcount", "armoffitem", "armoffempty",
            "armsickle", "armslot", "armcount", "armitem", "armempty", "armcrop", "disarm", "checkpoint",
            "verifycheckpoint", "sealverify", "emptycheckpoint");
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static volatile SourceCropProbe active;
    private final JavaPlugin plugin;
    private final World world;
    private final String runId = System.getProperty("senluo.sourcecrops.run_id", "UNSET");
    private final String phase = System.getProperty("senluo.sourcecrops.phase", "core");
    private final Set<String> tokens = ConcurrentHashMap.newKeySet();
    private final List<Map<String, Object>> writes = Collections.synchronizedList(new ArrayList<>());
    private final List<Receipt> receipts = Collections.synchronizedList(new ArrayList<>());
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong sequence = new AtomicLong(), epoch = new AtomicLong();
    private volatile String layout = "persist";
    private volatile Arm armed;
    private Integer originalRandomTicks;
    private Integer originalServerRandomTicks;
    private Boolean originalTileDrops;

    private record Pos(int x, int y, int z) {
        List<Integer> list() { return List.of(x, y, z); }
        Location location(World world) { return new Location(world, x, y, z); }
    }
    private record Arm(String kind, UUID player, long expiresAt, long sequence, boolean offHand, Pos target) {}
    private static final class Receipt {
        final Map<String, Object> values;
        final Cancellable event;
        volatile Map<String, Object> after;
        volatile Object finalProposed;
        volatile Map<String, Object> finalDecisions;
        volatile Long finalObservedAt;
        Receipt(Map<String, Object> values, Cancellable event) { this.values = values; this.event = event; }
    }

    private SourceCropProbe(JavaPlugin plugin, World world) { this.plugin = plugin; this.world = world; }

    static void enable(JavaPlugin plugin) {
        if (!"127.0.0.1".equals(Bukkit.getIp())) throw new IllegalStateException("Source crop probe requires loopback binding");
        World world = Objects.requireNonNull(Bukkit.getWorld("acceptance-world"), "Missing isolated acceptance-world");
        var probe = new SourceCropProbe(plugin, world);
        if (!Set.of("core", "seed", "verify", "empty").contains(probe.phase)) throw new IllegalStateException("Invalid source crop phase");
        active = probe;
        Bukkit.getPluginManager().registerEvents(probe, plugin);
        Objects.requireNonNull(plugin.getCommand("senluocrop")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof ConsoleCommandSender) || args.length != 3 || !args[0].matches("[0-9]{1,8}")
                    || !Set.of(FIRST, SECOND).contains(args[2])) return true;
            if (!probe.tokens.add(args[0])) { probe.reply(args[0], Map.of("error", "Duplicate source crop token")); return true; }
            if (!ACTIONS.contains(args[1])) { probe.reply(args[0], Map.of("error", "Unknown source crop action")); return true; }
            probe.dispatch(args[0], args[1], args[2]);
            return true;
        });
    }

    static void disable(JavaPlugin plugin) {
        SourceCropProbe probe = active;
        if (probe == null || probe.plugin != plugin) return;
        probe.armed = null;
        // Plugin disable is performed by the server lifecycle, never a command-driven /reload here.
        try { probe.restoreRules(); }
        catch (RuntimeException failure) { plugin.getLogger().severe("SOURCE_CROP_RULE_RESTORE " + failure); }
        active = null;
    }

    private boolean writable() { return phase.equals("core") || phase.equals("seed"); }
    private void requireWrite(String action) {
        if (!writable()) throw new IllegalStateException("Fixture writes forbidden in phase " + phase + ": " + action);
    }
    private Pos main(int i) { return new Pos(X + 4 + i * 4, Y, Z + 4); }
    private Pos persist(int i) { return new Pos(X + 4 + i * 4, Y, Z + 7); }
    private Pos wheat() { return new Pos(X + 8, Y, Z + 10); }
    private boolean inside(Location at) {
        return at.getWorld() == world && at.getBlockX() >= X && at.getBlockX() < X + 16
                && at.getBlockZ() >= Z && at.getBlockZ() < Z + 16 && at.getBlockY() >= Y - 2 && at.getBlockY() <= Y + 5;
    }
    private void owned() {
        if (!world.isChunkLoaded(CX, CZ) || !Bukkit.isOwnedByCurrentRegion(world, CX, CZ))
            throw new IllegalStateException("Source crop fixture is not loaded and owned");
    }
    private <T> CompletableFuture<T> region(Supplier<T> job, boolean mayLoad) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable schedule = () -> Bukkit.getRegionScheduler().execute(plugin, world, CX, CZ, () -> {
            try { owned(); result.complete(job.get()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        if (mayLoad) world.getChunkAtAsync(CX, CZ).whenComplete((chunk, failure) -> {
            if (failure != null) result.completeExceptionally(failure); else schedule.run();
        });
        else if (!world.isChunkLoaded(CX, CZ)) result.completeExceptionally(new IllegalStateException("Fixture chunk unloaded; observe does not load it"));
        else schedule.run();
        return result;
    }
    private <T> CompletableFuture<T> playerTask(String name, java.util.function.Function<Player, T> job) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) { result.completeExceptionally(new IllegalStateException("Fixture player offline: " + name)); return result; }
        player.getScheduler().run(plugin, task -> {
            try {
                if (!Bukkit.isOwnedByCurrentRegion(player)) throw new IllegalStateException("Player not region-owned");
                result.complete(job.apply(player));
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        }, () -> result.completeExceptionally(new IllegalStateException("Player retired: " + name)));
        return result;
    }
    private CompletableFuture<Void> global(Runnable job) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try { job.run(); result.complete(null); } catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result;
    }

    private void dispatch(String token, String action, String name) {
        try {
            CompletableFuture<?> work;
            if (action.equals("arrive")) {
                work = world.getChunkAtAsync(CX, CZ).thenCompose(chunk -> playerTask(name, player -> {
                    if (player.isOp() || player.getGameMode() != GameMode.SURVIVAL) throw new IllegalStateException("Expected ordinary survival player");
                    return player.teleportAsync(new Location(world, X + (name.equals(FIRST) ? 7.5 : 9.5), Y, Z + 13.5, 180, 0));
                }).thenCompose(future -> future));
            } else if (action.startsWith("stage") || action.equals("supportstone")) {
                requireWrite(action);
                if (!FIRST.equals(name)) throw new IllegalArgumentException("Only first player stages world fixtures");
                work = region(() -> { stage(action); return null; }, action.equals("stagearena"));
                if (action.equals("stagearena")) work = work.thenCompose(ignored -> global(() -> {
                    if (originalServerRandomTicks == null) originalServerRandomTicks = world.getGameRuleValue(GameRule.RANDOM_TICK_SPEED);
                    int before = Objects.requireNonNull(world.getGameRuleValue(GameRule.RANDOM_TICK_SPEED));
                    world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
                    write("FIXTURE_SETUP", "stagearena:pause-natural", null, before, 0);
                }));
            } else if (action.startsWith("stock") || Set.of("creative", "survival").contains(action)) {
                requireWrite(action);
                work = playerTask(name, player -> { stock(action, player); return null; });
            } else if (Set.of("naturalstart", "naturalstop", "tiledropsoff", "tiledropson").contains(action)) {
                requireWrite(action);
                work = global(() -> rules(action));
            } else if (action.startsWith("arm")) {
                requireWrite(action);
                work = playerTask(name, player -> {
                    if (armed != null && armed.expiresAt() >= System.currentTimeMillis()) throw new IllegalStateException("A fault injection is already armed");
                    String fault = action.substring(3);
                    boolean offHand = fault.startsWith("off");
                    if (offHand) fault = fault.substring(3);
                    Pos target = null;
                    for (String type : List.of("grow", "fertilize")) {
                        for (int i = 0; i < CROPS.size(); i++) if (fault.equals(type + CROPS.get(i))) { target = main(i); fault = type; break; }
                        if (fault.equals(type + "wheat")) { target = wheat(); fault = type; }
                    }
                    ItemStack held = offHand ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
                    if (Set.of("count", "item").contains(fault) && held.getType().isAir())
                        throw new IllegalStateException("Count/component mutation requires a nonempty source hand");
                    armed = new Arm(fault, player.getUniqueId(), System.currentTimeMillis() + 30000, sequence.incrementAndGet(), offHand, target);
                    write("FAULT_INJECTION", action, target, null, armEvidence(armed));
                    return null;
                });
            } else if (action.equals("disarm")) { armed = null; work = CompletableFuture.completedFuture(null); }
            else work = CompletableFuture.completedFuture(null);
            work.thenCompose(outcome -> snapshot(action, name).thenApply(report -> {
                if (action.equals("arrive")) report.put("arrived", Boolean.TRUE.equals(outcome));
                return report;
            })).thenAccept(report -> {
                try {
                    if (action.equals("checkpoint")) saveCheckpoint("source-crops-seed.json", "seed", report);
                    if (action.equals("sealverify")) saveCheckpoint("source-crops-verify-final.json", "verify", report);
                    if (action.equals("verifycheckpoint")) compareCheckpoint("source-crops-seed.json", "verify", report, false);
                    if (action.equals("emptycheckpoint")) compareCheckpoint("source-crops-verify-final.json", "empty", report, true);
                    reply(token, report);
                } catch (Throwable failure) { failed(token, action, failure); }
            }).exceptionally(failure -> { failed(token, action, failure); return null; });
        } catch (Throwable failure) { failed(token, action, failure); }
    }

    private void reply(String token, Object result) { plugin.getLogger().info("CONNECTED_REPLY " + token + " " + JSON.toJson(result)); }
    private void failed(String token, String action, Throwable failure) {
        reply(token, Map.of("schema", "source-crops-v1", "runId", runId, "phase", phase, "action", action,
                "status", "ERROR", "error", failure.toString()));
    }
    private void write(String kind, String action, Pos pos, Object before, Object after) {
        var value = new LinkedHashMap<String, Object>();
        value.put("seq", sequence.incrementAndGet()); value.put("actionKind", kind); value.put("action", action);
        value.put("position", pos == null ? null : pos.list()); value.put("before", before); value.put("after", after);
        value.put("wallTimeMillis", System.currentTimeMillis()); value.put("phase", phase);
        writes.add(value);
    }
    private void set(String action, Pos pos, BlockData data, boolean physics) {
        if (pos.x() < X || pos.x() >= X + 16 || pos.z() < Z || pos.z() >= Z + 16 || pos.y() < Y - 2 || pos.y() > Y + 5)
            throw new IllegalArgumentException("Fixture write escaped its approved chunk/height");
        Block block = pos.location(world).getBlock(); String before = block.getBlockData().getAsString();
        if (before.equals(data.getAsString())) return;
        block.setBlockData(data, physics);
        write(action.startsWith("arm") ? "FAULT_INJECTION" : "FIXTURE_SETUP", action, pos, before, data.getAsString());
    }
    private void set(String action, Pos pos, Material material, boolean physics) { set(action, pos, material.createBlockData(), physics); }
    private void soil(String action, Pos pos, int moisture) {
        Farmland data = (Farmland) Material.FARMLAND.createBlockData(); data.setMoisture(moisture);
        set(action, new Pos(pos.x(), Y - 1, pos.z()), data, true);
    }
    private void crop(String action, String name, Pos pos, int age) {
        var definition = Objects.requireNonNull(CraftEngineBlocks.byId(Key.of(PREFIX + name + "_crop")), "Missing crop " + name);
        ImmutableBlockState state = definition.defaultState();
        if (!(state.<Integer>getProperty("age") instanceof IntegerProperty property)) throw new IllegalStateException("Missing crop age property");
        state = state.with(property, age);
        String before = pos.location(world).getBlock().getBlockData().getAsString();
        if (!CraftEngineBlocks.place(pos.location(world), state, UpdateFlags.UPDATE_ALL, false)) throw new IllegalStateException("Fixture CE placement failed: " + name);
        write("FIXTURE_SETUP", action, pos, before, Map.of("id", PREFIX + name + "_crop", "age", age));
    }
    private void light(String action, Pos pos, int rawTarget) {
        // LIGHT one block above: emitted level target+1; acceptance must measure actual light after propagation.
        set(action, new Pos(pos.x(), Y + 1, pos.z()), Bukkit.createBlockData("minecraft:light[level=" + (rawTarget + 1) + "]"), true);
    }
    private void stage(String action) {
        owned();
        if (action.equals("stagearena")) {
            for (int x = X; x < X + 16; x++) for (int z = Z; z < Z + 16; z++) {
                set(action, new Pos(x, Y - 2, z), Material.STONE, false);
                set(action, new Pos(x, Y - 1, z), Material.STONE, false);
                for (int y = Y; y <= Y + 4; y++) {
                    boolean shell = y == Y + 4 || x == X || x == X + 15 || z == Z || z == Z + 15;
                    set(action, new Pos(x, y, z), shell ? Material.STONE : Material.AIR, false);
                }
            }
            world.addPluginChunkTicket(CX, CZ, plugin);
            return;
        }
        if (Set.of("stagelight9", "stagelight8", "stagelight7").contains(action)) {
            int level = Integer.parseInt(action.substring(action.length() - 1));
            for (int i = 0; i < 3; i++) { light(action, main(i), level); light(action, persist(i), level); }
            light(action, wheat(), level);
            return;
        }
        if (action.equals("stagesky")) {
            for (int x = X + 1; x < X + 15; x++) for (int z = Z + 1; z < Z + 15; z++)
                set(action, new Pos(x, Y + 4, z), Material.AIR, true);
            return;
        }
        if (action.equals("supportstone")) {
            for (int i = 0; i < 3; i++) set(action, new Pos(main(i).x(), Y - 1, main(i).z()), Material.STONE, true);
            return;
        }
        armed = null;
        for (int x = X; x < X + 16; x++) for (int z = Z; z < Z + 16; z++)
            set(action, new Pos(x, Y + 4, z), Material.STONE, false);
        // Reclose openings left by the explicit boundary fixture before measuring low light.
        for (int x = X; x < X + 16; x++) for (int z = Z; z < Z + 16; z++)
            if (x == X || x == X + 15 || z == Z || z == Z + 15)
                for (int y = Y; y <= Y + 4; y++) set(action, new Pos(x, y, z), Material.STONE, false);
        // Only the finite crop/light working layer is cleared, never the world or a worldgen pool.
        for (int x = X + 1; x < X + 15; x++) for (int z = Z + 1; z < Z + 15; z++) {
            for (int y = Y; y <= Y + 1; y++) set(action, new Pos(x, y, z), Material.AIR, false);
            if (z <= Z + 11) soil(action, new Pos(x, Y, z), action.equals("stagedry") ? 0 : 7);
        }
        for (Entity entity : world.getChunkAt(CX, CZ).getEntities()) if (entity instanceof Item item && inside(item.getLocation())) {
            write("FIXTURE_SETUP", action, null, itemEvidence(item), "removed fixture drop"); item.remove();
        }
        layout = action;
        if (action.equals("stageages")) {
            for (int i = 0; i < 3; i++) for (int age = 0; age < 8; age++) {
                Pos pos = new Pos(X + 1 + age, Y, Z + 2 + i * 3); crop(action, CROPS.get(i), pos, age); light(action, pos, 14);
            }
            return;
        }
        if (action.equals("stageboundary") || action.equals("stageboundarygrowth")) {
            for (int i = 0; i < 3; i++) {
                Pos pos = new Pos(X + 15, Y, Z + 3 + i * 4);
                set(action, pos, Material.AIR, false); soil(action, pos, 7); crop(action, CROPS.get(i), pos, action.equals("stageboundarygrowth") ? 0 : 7); light(action, pos, 14);
            }
            return;
        }
        int age = Set.of("stagebone", "stagenatural", "stagewet", "stagedry", "stagedense").contains(action) ? 0
                : action.equals("stageimmature") ? 6 : 7;
        for (int i = 0; i < 3; i++) {
            light(action, main(i), 14); light(action, persist(i), 14);
            if (!action.equals("stageplant") && !action.equals("stageinvalid")) crop(action, CROPS.get(i), main(i), age);
            if (action.equals("stageinvalid")) {
                if (i < 2) set(action, new Pos(main(i).x(), Y - 1, main(i).z()), i == 0 ? Material.STONE : Material.DIRT, true);
                else crop(action, CROPS.get(i), main(i), 0);
            }
            if (action.equals("stagedense")) crop(action, CROPS.get(i), new Pos(main(i).x() - 1, Y, main(i).z() + 1), 0);
        }
        if (Set.of("stageplant", "stagenatural", "stagewet", "stagedense").contains(action)) {
            // Native water sources keep the six persistent planting cells hydrated across real stops.
            // This arranges soil only; no crop is created here or in verify/empty.
            for (int x : new int[]{X + 2, X + 6, X + 10, X + 14}) for (int z : new int[]{Z + 2, Z + 6, Z + 10})
                set(action, new Pos(x, Y - 1, z), Material.WATER, true);
        }
        if (Set.of("stagebone", "stagemature", "stagenatural", "stagewet", "stagedry", "stagedense").contains(action)) {
            var data = (org.bukkit.block.data.Ageable) Material.WHEAT.createBlockData(); data.setAge(age);
            set(action, wheat(), data, true); light(action, wheat(), 14);
        }
    }

    private void stock(String action, Player player) {
        if (player.isOp() || player.getWorld() != world) throw new IllegalStateException("Expected non-op fixture player");
        var before = playerEvidence(player);
        if (action.equals("creative") || action.equals("survival")) player.setGameMode(action.equals("creative") ? GameMode.CREATIVE : GameMode.SURVIVAL);
        else {
            player.closeInventory(); var inv = player.getInventory(); inv.clear(); inv.setArmorContents(new ItemStack[4]);
            inv.setItemInOffHand(new ItemStack(Material.AIR)); player.setItemOnCursor(new ItemStack(Material.AIR));
            if (!action.equals("stockempty")) {
                for (int i = 0; i < 3; i++) inv.setItem(i, custom(CROPS.get(i) + "_seed", 64));
                inv.setItem(3, new ItemStack(Material.BONE_MEAL, 64));
                ItemStack sickle = custom("sickle", 1);
                if (action.equals("stockfortune")) sickle.addUnsafeEnchantment(Enchantment.FORTUNE, 3);
                inv.setItem(4, sickle);
                ItemStack tool = new ItemStack(Material.DIAMOND_HOE); tool.addUnsafeEnchantment(Enchantment.FORTUNE, 3); inv.setItem(5, tool);
                inv.setItem(6, new ItemStack(Material.COBBLESTONE, 64));
            }
            inv.setHeldItemSlot(0);
        }
        write("FIXTURE_SETUP", action, null, before, playerEvidence(player));
    }
    private ItemStack custom(String name, int count) {
        ItemStack result = ItemStackUtils.getBukkitStack(InventoryUtils.createOrEmpty(Key.of(PREFIX + name)).minecraftItem());
        if (result == null || result.getType().isAir()) throw new IllegalStateException("Missing fixture item " + name);
        result.setAmount(count); return result;
    }
    private void rules(String action) {
        if (action.equals("naturalstart")) {
            if (originalRandomTicks != null) throw new IllegalStateException("Natural acceleration already active");
            originalRandomTicks = world.getGameRuleValue(GameRule.RANDOM_TICK_SPEED);
            world.setGameRule(GameRule.RANDOM_TICK_SPEED, 64);
            write("FIXTURE_SETUP", action, null, originalRandomTicks, 64);
        } else if (action.equals("naturalstop")) {
            if (originalRandomTicks != null) {
                int before = Objects.requireNonNull(world.getGameRuleValue(GameRule.RANDOM_TICK_SPEED));
                world.setGameRule(GameRule.RANDOM_TICK_SPEED, originalRandomTicks);
                write("FIXTURE_SETUP", action, null, before, originalRandomTicks); originalRandomTicks = null;
            }
        } else {
            boolean before = !Boolean.FALSE.equals(world.getGameRuleValue(GameRule.DO_TILE_DROPS));
            if (originalTileDrops == null) originalTileDrops = before;
            world.setGameRule(GameRule.DO_TILE_DROPS, action.equals("tiledropson"));
            write("FIXTURE_SETUP", action, null, before, action.equals("tiledropson"));
        }
    }
    private void restoreRules() {
        if (originalServerRandomTicks != null) world.setGameRule(GameRule.RANDOM_TICK_SPEED, originalServerRandomTicks);
        else if (originalRandomTicks != null) world.setGameRule(GameRule.RANDOM_TICK_SPEED, originalRandomTicks);
        if (originalTileDrops != null) world.setGameRule(GameRule.DO_TILE_DROPS, originalTileDrops);
        originalRandomTicks = null; originalServerRandomTicks = null; originalTileDrops = null;
    }

    private CompletableFuture<Map<String, Object>> snapshot(String action, String actor) {
        CompletableFuture<Map<String, Object>> fixture = world.isChunkLoaded(CX, CZ)
                ? region(this::fixtureEvidence, false) : CompletableFuture.completedFuture(Map.of("loaded", false, "owned", false));
        Map<String, CompletableFuture<Map<String, Object>>> players = new LinkedHashMap<>();
        for (String name : List.of(FIRST, SECOND)) {
            if (Bukkit.getPlayerExact(name) == null) players.put(name, CompletableFuture.completedFuture(Map.of("online", false)));
            else players.put(name, playerTask(name, this::playerEvidence));
        }
        CompletableFuture<List<Map<String, Object>>> ground = groundEvidence();
        CompletableFuture<Map<String, Object>> rules = new CompletableFuture<>();
        global(() -> rules.complete(Map.of("randomTickSpeed", Objects.requireNonNull(world.getGameRuleValue(GameRule.RANDOM_TICK_SPEED)),
                "doTileDrops", !Boolean.FALSE.equals(world.getGameRuleValue(GameRule.DO_TILE_DROPS))))).exceptionally(error -> { rules.completeExceptionally(error); return null; });
        var all = new ArrayList<CompletableFuture<?>>(); all.add(fixture); all.add(ground); all.add(rules); all.addAll(players.values());
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema", "source-crops-v1"); result.put("runId", runId); result.put("phase", phase);
            result.put("action", action); result.put("actor", actor); result.put("actionKind", kind(action));
            result.put("status", "OBSERVED"); result.put("epoch", epoch.get()); result.put("fixture", fixture.join());
            var playerValues = new LinkedHashMap<String, Object>(); players.forEach((name, future) -> playerValues.put(name, future.join()));
            result.put("players", playerValues);
            var worldValues = new LinkedHashMap<String, Object>(); worldValues.put("name", world.getName()); worldValues.put("uuid", world.getUID().toString());
            worldValues.put("items", ground.join()); worldValues.put("gamerules", rules.join()); worldValues.put("sickle", sickleSettings());
            worldValues.put("loaded", fixture.join().getOrDefault("loaded", false)); worldValues.put("owned", fixture.join().getOrDefault("owned", false));
            result.put("world", worldValues);
            synchronized (writes) { result.put("setupWrites", List.copyOf(writes)); }
            var eventValues = new ArrayList<Map<String, Object>>();
            synchronized (receipts) {
                for (Receipt receipt : receipts) {
                    var value = new LinkedHashMap<>(receipt.values);
                    value.put("cancelled", receipt.event != null && receipt.event.isCancelled());
                    value.put("finalProposed", receipt.finalProposed); value.put("finalDecisions", receipt.finalDecisions);
                    value.put("after", receipt.after); value.put("finalObservedAt", receipt.finalObservedAt); eventValues.add(value);
                }
            }
            result.put("events", eventValues);
            var canonical = new LinkedHashMap<String, Object>();
            var canonicalErrors = new ArrayList<String>();
            for (String name : List.of("tomato_seed", "chili_seed", "lettuce_seed", "tomato", "red_chili", "green_chili", "lettuce", "caterpillar")) {
                try {
                    var buildable = BukkitItemManager.instance().getBuildableItem(Key.of(PREFIX + name)).orElse(null);
                    if (buildable == null) throw new IllegalStateException("CE item is not currently registered");
                    var nativeItem = buildable.buildItem((net.momirealms.craftengine.core.entity.player.Player) null);
                    if (ItemUtils.isEmpty(nativeItem)) throw new IllegalStateException("CE item is not currently buildable");
                    canonical.put(PREFIX + name, item(ItemStackUtils.getBukkitStack(nativeItem.minecraftItem())));
                }
                catch (RuntimeException notRegisteredDuringReload) {
                    // CE can fire its reload event before item definitions finish loading.
                    // Keep read-only polling alive and mark canonical fixtures unavailable;
                    // the runner must wait for a later complete registry snapshot.
                    canonicalErrors.add(PREFIX + name + ": " + notRegisteredDuringReload.getMessage());
                }
            }
            for (Material material : List.of(Material.BONE_MEAL, Material.WHEAT, Material.WHEAT_SEEDS))
                canonical.put(material.getKey().toString(), item(new ItemStack(material)));
            result.put("canonicalItems", canonical);
            result.put("canonicalReady", canonicalErrors.isEmpty());
            result.put("canonicalErrors", List.copyOf(canonicalErrors));
            Map<Key, ?> currentlyLoadedBlocks = CraftEngineBlocks.loadedBlocks();
            result.put("sourceCropBlocksReady", List.of("tomato_crop", "chili_crop", "lettuce_crop").stream()
                    .allMatch(name -> currentlyLoadedBlocks.containsKey(Key.of(PREFIX + name))));
            Arm arm = armed;
            // Observation never consumes even expired fault state; only an explicit disarm,
            // a declared fixture stage, or the matching one-shot event may change it.
            result.put("armed", arm == null ? null : armEvidence(arm));
            synchronized (errors) { result.put("errors", List.copyOf(errors)); }
            result.put("protection", Map.of("status", "NOT_TESTED", "reason", "A real AntiGrief provider is required; cancelling the logging BlockBreakEvent is not protection"));
            result.put("clientVisuals", "NOT_TESTED");
            return result;
        });
    }
    private String kind(String action) {
        if (action.startsWith("arm") || action.equals("disarm")) return "FAULT_INJECTION";
        if (action.startsWith("stage") || action.startsWith("stock") || Set.of("creative", "survival", "supportstone", "naturalstart", "naturalstop", "tiledropsoff", "tiledropson").contains(action)) return "FIXTURE_SETUP";
        if (action.equals("arrive")) return "FIXTURE_TRANSPORT";
        return "OBSERVATION";
    }

    private Map<String, Object> fixtureEvidence() {
        owned(); var result = new LinkedHashMap<String, Object>(); result.put("loaded", true); result.put("owned", true); result.put("layout", layout);
        for (int i = 0; i < 3; i++) {
            result.put(CROPS.get(i), blockEvidence(main(i), true));
            result.put("persist" + Character.toUpperCase(CROPS.get(i).charAt(0)) + CROPS.get(i).substring(1), blockEvidence(persist(i), true));
        }
        result.put("wheat", blockEvidence(wheat(), true));
        var ages = new ArrayList<Map<String, Object>>();
        if (layout.equals("stageages")) for (int i = 0; i < 3; i++) for (int age = 0; age < 8; age++)
            ages.add(blockEvidence(new Pos(X + 1 + age, Y, Z + 2 + i * 3), true));
        result.put("ages", ages);
        var boundary = new ArrayList<Map<String, Object>>();
        if (layout.equals("stageboundary") || layout.equals("stageboundarygrowth")) for (int i = 0; i < 3; i++) boundary.add(blockEvidence(new Pos(X + 15, Y, Z + 3 + i * 4), true));
        result.put("boundary", boundary);
        return result;
    }
    private Map<String, Object> blockEvidence(Pos pos, boolean detailed) {
        var result = new LinkedHashMap<String, Object>(); result.put("position", pos.list());
        boolean loaded = world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4);
        boolean owner = Bukkit.isOwnedByCurrentRegion(world, pos.x() >> 4, pos.z() >> 4);
        result.put("loaded", loaded); result.put("owned", owner);
        if (!loaded || !owner) { result.put("id", null); result.put("age", null); return result; }
        Block block = pos.location(world).getBlock();
        Object rawPos = LocationUtils.toBlockPos(new BlockPos(pos.x(), pos.y(), pos.z()));
        Object level = BukkitAdaptor.adapt(world).minecraftWorld();
        Object rawState = BlockGetterProxy.INSTANCE.getBlockState(level, rawPos);
        result.putAll(stateEvidence(block.getBlockData()));
        result.put("nativeState", rawState.toString()); result.put("nativeStateId", BlockStateUtils.blockStateToId(rawState));
        if (!detailed) return result;
        result.put("rawBrightness", BlockAndLightGetterProxy.INSTANCE.getRawBrightness(level, rawPos, 0));
        result.put("skyLight", (int) block.getLightFromSky()); result.put("blockLight", (int) block.getLightFromBlocks());
        var shapeErrors = new ArrayList<String>();
        try {
            Object farmlandTag = Class.forName("net.minecraft.tags.BlockTags", true, rawState.getClass().getClassLoader())
                    .getField("MAINTAINS_FARMLAND").get(null);
            result.put("maintainsFarmland", invoke(rawState, "is", farmlandTag));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            result.put("maintainsFarmland", null); shapeErrors.add("is(MAINTAINS_FARMLAND): " + failure);
        }
        for (var entry : Map.of("shape", "getShape", "collision", "getCollisionShape", "supportShape", "getBlockSupportShape").entrySet()) {
            try { result.put(entry.getKey(), boxes(invoke(rawState, entry.getValue(), level, rawPos))); }
            catch (ReflectiveOperationException | RuntimeException failure) { result.put(entry.getKey(), null); shapeErrors.add(entry.getValue() + ": " + failure); }
        }
        result.put("nativeQueryPath", "world BlockGetter.getBlockState -> native BlockStateBase tag membership and two-argument shape methods -> VoxelShape.toAabbs; no delegate shortcut/cache rebuild");
        result.put("nativeQueryErrors", shapeErrors);
        var soil = new ArrayList<Map<String, Object>>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Pos near = new Pos(pos.x() + dx, Y - 1, pos.z() + dz);
            var sample = new LinkedHashMap<String, Object>(); sample.put("position", near.list());
            boolean nearLoaded = world.isChunkLoaded(near.x() >> 4, near.z() >> 4), nearOwned = Bukkit.isOwnedByCurrentRegion(world, near.x() >> 4, near.z() >> 4);
            sample.put("loaded", nearLoaded); sample.put("owned", nearOwned);
            if (nearLoaded && nearOwned) {
                BlockData data = near.location(world).getBlock().getBlockData(); sample.put("material", data.getMaterial().name());
                sample.put("moisture", data instanceof Farmland farm ? farm.getMoisture() : null);
                sample.put("cropAbove", blockEvidence(new Pos(near.x(), Y, near.z()), false));
            }
            soil.add(sample);
        }
        result.put("soil", soil);
        BlockData below = world.getBlockAt(pos.x(), pos.y() - 1, pos.z()).getBlockData();
        var support = new LinkedHashMap<String, Object>(); support.put("material", below.getMaterial().name());
        support.put("moisture", below instanceof Farmland farm ? farm.getMoisture() : null); result.put("support", support);
        return result;
    }
    private Map<String, Object> stateEvidence(BlockData data) {
        var result = new LinkedHashMap<String, Object>(); result.put("material", data.getMaterial().name()); result.put("blockData", data.getAsString());
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(BlockStateUtils.blockDataToBlockState(data)).orElse(null);
        Integer age = null;
        if (state != null && state.<Integer>getProperty("age") instanceof IntegerProperty property) age = state.get(property);
        else if (data instanceof org.bukkit.block.data.Ageable value) age = value.getAge();
        result.put("id", state == null ? null : state.owner().value().id().asString()); result.put("age", age);
        result.put("properties", state == null ? data.getAsString() : state.propertiesNbt().toString());
        return result;
    }
    private static Object invoke(Object target, String name, Object... args) throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            boolean compatible = true;
            for (int i = 0; i < args.length; i++) if (args[i] != null && !method.getParameterTypes()[i].isInstance(args[i])) { compatible = false; break; }
            if (compatible) return method.invoke(target, args);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name + "/" + args.length);
    }
    private static List<List<Double>> boxes(Object shape) throws ReflectiveOperationException {
        var result = new ArrayList<List<Double>>();
        for (Object box : (List<?>) invoke(shape, "toAabbs")) {
            var coordinates = new ArrayList<Double>();
            for (String field : List.of("minX", "minY", "minZ", "maxX", "maxY", "maxZ")) coordinates.add(box.getClass().getField(field).getDouble(box));
            result.add(coordinates);
        }
        return result;
    }

    private Map<String, Object> playerEvidence(Player player) {
        if (!Bukkit.isOwnedByCurrentRegion(player)) throw new IllegalStateException("Cross-region player observation");
        var result = new LinkedHashMap<String, Object>(); result.put("online", player.isOnline()); result.put("owned", true);
        result.put("uuid", player.getUniqueId().toString()); result.put("world", player.getWorld().getUID().toString());
        result.put("position", List.of(player.getX(), player.getY(), player.getZ())); result.put("gameMode", player.getGameMode().name());
        result.put("op", player.isOp()); result.put("heldSlot", player.getInventory().getHeldItemSlot());
        result.put("inventory", Arrays.stream(player.getInventory().getStorageContents()).map(this::item).toList());
        result.put("armor", Arrays.stream(player.getInventory().getArmorContents()).map(this::item).toList());
        result.put("offHand", item(player.getInventory().getItemInOffHand())); result.put("cursor", item(player.getItemOnCursor()));
        return result;
    }
    private Map<String, Object> item(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return Map.of();
        var result = new LinkedHashMap<String, Object>(); var unit = stack.clone(); unit.setAmount(1);
        byte[] unitBytes = unit.serializeAsBytes();
        result.put("id", InventoryUtils.wrap(stack).id().asString()); result.put("count", stack.getAmount()); result.put("material", stack.getType().name());
        result.put("serialized", Base64.getEncoder().encodeToString(stack.serializeAsBytes()));
        result.put("unitSerialized", Base64.getEncoder().encodeToString(unitBytes)); result.put("unitSha256", sha(unitBytes));
        var undamaged = unit.clone();
        if (undamaged.getItemMeta() instanceof Damageable damage) { damage.setDamage(0); undamaged.setItemMeta(damage); }
        result.put("undamagedUnitSerialized", Base64.getEncoder().encodeToString(undamaged.serializeAsBytes()));
        result.put("damage", stack.getItemMeta() instanceof Damageable damage ? damage.getDamage() : 0);
        var enchantments = new TreeMap<String, Integer>(); stack.getEnchantments().forEach((key, value) -> enchantments.put(key.getKey().toString(), value));
        result.put("enchantments", enchantments);
        // Complete PDC bytes preserve nested containers and types, including future unsupported primitive types.
        try { result.put("pdc", Map.of("serialized", Base64.getEncoder().encodeToString(stack.getItemMeta().getPersistentDataContainer().serializeToBytes()))); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot serialize fixture PDC", failure); }
        return result;
    }
    private Map<String, Object> itemEvidence(Item entity) {
        var value = new LinkedHashMap<>(item(entity.getItemStack())); value.put("uuid", entity.getUniqueId().toString());
        value.put("world", entity.getWorld().getUID().toString()); value.put("position", List.of(entity.getX(), entity.getY(), entity.getZ()));
        return value;
    }
    private CompletableFuture<List<Map<String, Object>>> groundEvidence() {
        var futures = new ArrayList<CompletableFuture<List<Map<String, Object>>>>();
        for (int cx = CX - 1; cx <= CX + 1; cx++) for (int cz = CZ - 1; cz <= CZ + 1; cz++) {
            if (!world.isChunkLoaded(cx, cz)) continue;
            int chunkX = cx, chunkZ = cz;
            var future = new CompletableFuture<List<Map<String, Object>>>(); futures.add(future);
            Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> {
                try {
                    var items = new ArrayList<Map<String, Object>>();
                    if (world.isChunkLoaded(chunkX, chunkZ) && Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ))
                        for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) if (entity instanceof Item drop) {
                            Location at = drop.getLocation();
                            if (at.getX() >= X - 2 && at.getX() <= X + 18 && at.getZ() >= Z - 2 && at.getZ() <= Z + 18
                                    && at.getY() >= Y - 3 && at.getY() <= Y + 6) items.add(itemEvidence(drop));
                        }
                    future.complete(items);
                } catch (Throwable failure) { future.completeExceptionally(failure); }
            });
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> futures.stream().flatMap(f -> f.join().stream()).toList());
    }
    private Map<String, Object> sickleSettings() {
        var result = new LinkedHashMap<String, Object>();
        try {
            Object behavior = BukkitItemManager.instance().getItemDefinition(Key.of(PREFIX + "sickle")).orElseThrow().behavior();
            Class<?> expected = Class.forName("net.kaleidoscope.cookery.item.behavior.SickleRangeHarvestBehavior");
            if (!expected.isInstance(behavior)) behavior = invoke(behavior, "getFirst", expected);
            for (var entry : Map.of("radius", "horizontalRadius", "height", "height", "cooldownTicks", "cooldownTicks", "damagePerUse", "durabilityPerUse", "damageItem", "damageItem").entrySet()) {
                var field = expected.getDeclaredField(entry.getValue()); field.setAccessible(true); result.put(entry.getKey(), field.get(behavior));
            }
            result.put("source", "read-only production item behavior fields; no mutation");
        } catch (ReflectiveOperationException | RuntimeException failure) { result.put("error", failure.toString()); }
        return result;
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private void saveCheckpoint(String file, String expectedPhase, Map<String, Object> report) throws java.io.IOException {
        if (!phase.equals(expectedPhase) || runId.equals("UNSET")) throw new IllegalStateException("Invalid checkpoint phase/runId");
        if (armed != null || originalRandomTicks != null) throw new IllegalStateException("Disarm faults and restore natural acceleration before checkpoint");
        var players = JSON.toJsonTree(report.get("players")).getAsJsonObject();
        for (String name : List.of(FIRST, SECOND)) if (!players.getAsJsonObject(name).get("online").getAsBoolean()) throw new IllegalStateException("Both players required at checkpoint");
        Files.createDirectories(plugin.getDataFolder().toPath()); Path path = plugin.getDataFolder().toPath().resolve(file);
        Files.writeString(path, JSON.toJson(report) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        report.put("checkpoint", Map.of("file", file, "sha256", sha(Files.readAllBytes(path)), "created", true));
    }
    private void compareCheckpoint(String file, String expectedPhase, Map<String, Object> report, boolean empty) throws java.io.IOException {
        if (!phase.equals(expectedPhase)) throw new IllegalStateException("Invalid checkpoint inspection phase");
        Path path = plugin.getDataFolder().toPath().resolve(file);
        byte[] bytes = Files.readAllBytes(path); JsonObject previous = JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
        JsonObject current = JSON.toJsonTree(report).getAsJsonObject();
        var differences = new ArrayList<String>();
        if (!previous.get("runId").getAsString().equals(runId)) differences.add("runId changed");
        for (String name : List.of(FIRST, SECOND)) for (String key : List.of("uuid", "inventory", "armor", "offHand", "cursor", "heldSlot", "gameMode", "op", "world"))
            if (!Objects.equals(previous.getAsJsonObject("players").getAsJsonObject(name).get(key), current.getAsJsonObject("players").getAsJsonObject(name).get(key))) differences.add(name + "." + key);
        for (String key : List.of("tomato", "chili", "lettuce", "persistTomato", "persistChili", "persistLettuce")) {
            JsonObject now = current.getAsJsonObject("fixture").getAsJsonObject(key), before = previous.getAsJsonObject("fixture").getAsJsonObject(key);
            if (now == null || !now.get("loaded").getAsBoolean() || !now.get("owned").getAsBoolean()) { differences.add(key + ": unavailable"); continue; }
            if (empty) {
                if (!now.get("id").isJsonNull() || !now.get("material").getAsString().equals("AIR")) differences.add(key + ": crop or block resurrected");
            } else for (String field : List.of("id", "age", "position", "shape", "collision", "supportShape", "maintainsFarmland", "rawBrightness", "skyLight", "support"))
                if (!Objects.equals(before.get(field), now.get(field))) differences.add(key + "." + field);
        }
        if (empty && !current.getAsJsonObject("world").getAsJsonArray("items").isEmpty()) differences.add("unexpected ground items");
        report.put("checkpoint", Map.of("file", file, "sha256", sha(bytes), "differences", differences, "matched", differences.isEmpty(), "readOnly", true));
    }

    private boolean actor(Player player) { return player != null && Set.of(FIRST, SECOND).contains(player.getName()); }
    private Map<String, Object> armEvidence(Arm value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("kind", value.kind()); result.put("playerUuid", value.player().toString());
        result.put("expiresAt", value.expiresAt()); result.put("sequence", value.sequence());
        result.put("hand", value.offHand() ? "OFF_HAND" : "MAIN_HAND");
        result.put("target", value.target() == null ? null : value.target().list());
        result.put("expired", value.expiresAt() < System.currentTimeMillis());
        return result;
    }
    private boolean consume(String kind, Player player, Block block) {
        Arm current = armed;
        if (current == null || !current.kind().equals(kind) || !inside(block.getLocation()) || current.expiresAt() < System.currentTimeMillis()
                || (player != null && !current.player().equals(player.getUniqueId()))) return false;
        if (current.target() != null && (block.getX() != current.target().x() || block.getY() != current.target().y()
                || block.getZ() != current.target().z())) return false;
        armed = null;
        var applied = new LinkedHashMap<>(armEvidence(current)); applied.put("armSeq", current.sequence());
        write("FAULT_INJECTION", "applied:" + kind, new Pos(block.getX(), block.getY(), block.getZ()), null, applied);
        return true;
    }
    private Receipt record(String type, Block block, Player player, Cancellable event, Object proposed, Object hand, ItemStack stack) {
        if (!inside(block.getLocation())) return null;
        if (!Bukkit.isOwnedByCurrentRegion(world, block.getX() >> 4, block.getZ() >> 4)) throw new IllegalStateException("Cross-region event snapshot");
        if (receipts.size() >= 20000) throw new IllegalStateException("Source crop event budget exceeded; evidence is not truncated");
        Pos pos = new Pos(block.getX(), block.getY(), block.getZ());
        var values = new LinkedHashMap<String, Object>(); values.put("seq", sequence.incrementAndGet()); values.put("type", type);
        values.put("position", pos.list()); values.put("playerUuid", player == null ? null : player.getUniqueId().toString());
        values.put("observedCancelled", event != null && event.isCancelled()); values.put("before", blockEvidence(pos, false));
        values.put("proposed", proposed); values.put("hand", hand); values.put("item", item(stack));
        values.put("wallTimeMillis", System.currentTimeMillis()); values.put("tick", world.getGameTime()); values.put("owned", true);
        var receipt = new Receipt(values, event); receipts.add(receipt);
        Bukkit.getRegionScheduler().runDelayed(plugin, world, pos.x() >> 4, pos.z() >> 4, task -> {
            receipt.after = blockEvidence(pos, false);
            if (event instanceof BlockGrowEvent grow) receipt.finalProposed = stateEvidence(grow.getNewState().getBlockData());
            else if (event instanceof BlockFertilizeEvent fertilize) receipt.finalProposed = fertilize.getBlocks().stream()
                    .map(state -> Map.of("position", List.of(state.getX(), state.getY(), state.getZ()), "state", stateEvidence(state.getBlockData()))).toList();
            else receipt.finalProposed = proposed;
            var decisions = new LinkedHashMap<String, Object>(); decisions.put("cancelled", event != null && event.isCancelled());
            if (event instanceof PlayerInteractEvent interact) { decisions.put("useBlock", interact.useInteractedBlock().name()); decisions.put("useItem", interact.useItemInHand().name()); }
            if (event instanceof SickleHarvestEvent sickle) decisions.put("costDurability", sickle.costDurability());
            receipt.finalDecisions = decisions;
            receipt.finalObservedAt = System.currentTimeMillis();
        }, 1);
        return receipt;
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void injectGrow(BlockGrowEvent event) {
        if (consume("grow", null, event.getBlock())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void injectFertilize(BlockFertilizeEvent event) {
        if (consume("fertilize", event.getPlayer(), event.getBlock())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void injectInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() != null && consume("interact", event.getPlayer(), event.getClickedBlock())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void injectSickle(SickleHarvestEvent event) {
        if (consume("sickle", event.player(), event.block())) { event.setCancelled(true); event.setCostDurability(false); }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void mutate(BlockBreakEvent event) {
        Player player = event.getPlayer(); Block block = event.getBlock(); Arm current = armed;
        if (current == null || !Set.of("slot", "count", "item", "empty", "crop").contains(current.kind()) || !consume(current.kind(), player, block)) return;
        if (!Bukkit.isOwnedByCurrentRegion(player)) throw new IllegalStateException("Mutation player not owned");
        var before = playerEvidence(player);
        switch (current.kind()) {
            case "slot" -> player.getInventory().setHeldItemSlot((player.getInventory().getHeldItemSlot() + 1) % 9);
            case "count" -> { ItemStack held = hand(player, current).clone(); held.setAmount(held.getAmount() == 1 ? 2 : held.getAmount() - 1); hand(player, current, held); }
            case "item" -> { ItemStack held = hand(player, current).clone(); var meta = held.getItemMeta(); meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "source_crop_mutation"), PersistentDataType.LONG, System.nanoTime()); held.setItemMeta(meta); hand(player, current, held); }
            case "empty" -> hand(player, current, hand(player, current).getType().isAir() ? new ItemStack(Material.COBBLESTONE) : new ItemStack(Material.AIR));
            case "crop" -> set("armcrop", new Pos(block.getX(), block.getY(), block.getZ()), Material.AIR, true);
            default -> throw new IllegalStateException(current.kind());
        }
        write("FAULT_INJECTION", "mutate:" + current.kind(), new Pos(block.getX(), block.getY(), block.getZ()), before, playerEvidence(player));
    }
    private ItemStack hand(Player player, Arm arm) {
        return arm.offHand() ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
    }
    private void hand(Player player, Arm arm, ItemStack stack) {
        if (arm.offHand()) player.getInventory().setItemInOffHand(stack);
        else player.getInventory().setItemInMainHand(stack);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void observeInteract(PlayerInteractEvent event) {
        if (actor(event.getPlayer()) && event.getClickedBlock() != null) record("PlayerInteractEvent", event.getClickedBlock(), event.getPlayer(), event,
                Map.of("action", event.getAction().name(), "useBlock", event.useInteractedBlock().name(), "useItem", event.useItemInHand().name()), event.getHand() == null ? null : event.getHand().name(), event.getItem());
    }
    @EventHandler(priority = EventPriority.MONITOR) public void observePlace(BlockPlaceEvent event) {
        if (!actor(event.getPlayer())) return;
        Receipt receipt = record("BlockPlaceEvent", event.getBlockPlaced(), event.getPlayer(), event,
                stateEvidence(event.getBlockPlaced().getBlockData()), event.getHand().name(), event.getItemInHand());
        if (receipt != null) {
            var before = new LinkedHashMap<>(stateEvidence(event.getBlockReplacedState().getBlockData()));
            before.put("position", List.of(event.getBlockPlaced().getX(), event.getBlockPlaced().getY(), event.getBlockPlaced().getZ()));
            receipt.values.put("before", before);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR) public void observeGrow(BlockGrowEvent event) {
        record("BlockGrowEvent", event.getBlock(), null, event, stateEvidence(event.getNewState().getBlockData()), null, null);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void observeFertilize(BlockFertilizeEvent event) {
        record("BlockFertilizeEvent", event.getBlock(), event.getPlayer(), event,
                event.getBlocks().stream().map(state -> Map.of("position", List.of(state.getX(), state.getY(), state.getZ()), "state", stateEvidence(state.getBlockData()))).toList(), null, null);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void observeBreak(BlockBreakEvent event) {
        if (actor(event.getPlayer())) record("BlockBreakEvent", event.getBlock(), event.getPlayer(), event, null, "HAND", event.getPlayer().getInventory().getItemInMainHand());
    }
    @EventHandler(priority = EventPriority.MONITOR) public void observeSickle(SickleHarvestEvent event) {
        if (actor(event.player())) record("SickleHarvestEvent", event.block(), event.player(), event, Map.of("costDurability", event.costDurability()), "HAND", event.sickle());
    }
    private void dropEvent(String type, Item drop, Player player, Cancellable event, Object extra) {
        if (!inside(drop.getLocation())) return;
        Location at = drop.getLocation(); record(type, at.getBlock(), player, event, Map.of("entity", itemEvidence(drop), "extra", extra), null, drop.getItemStack());
    }
    @EventHandler(priority = EventPriority.MONITOR) public void observeSpawn(ItemSpawnEvent event) { dropEvent("ItemSpawnEvent", event.getEntity(), null, event, Map.of()); }
    @EventHandler(priority = EventPriority.MONITOR) public void observeMerge(ItemMergeEvent event) { dropEvent("ItemMergeEvent", event.getEntity(), null, event, itemEvidence(event.getTarget())); }
    @EventHandler(priority = EventPriority.MONITOR) public void observeDespawn(ItemDespawnEvent event) { dropEvent("ItemDespawnEvent", event.getEntity(), null, event, Map.of()); }
    @EventHandler(priority = EventPriority.MONITOR) public void observePickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && actor(player)) dropEvent("EntityPickupItemEvent", event.getItem(), player, event, Map.of("remaining", event.getRemaining()));
    }
    @EventHandler(priority = EventPriority.MONITOR) public void observeReload(CraftEngineReloadEvent event) { epoch.incrementAndGet(); }
}
