package net.kaleidoscope.cookery.acceptance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kaleidoscope.cookery.block.entity.PlateController;
import net.kaleidoscope.cookery.catalog.CookeryCatalog;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** Exercises real CE plates and drops through controller APIs, without a simulated player. */
final class PlateSmoke implements Listener {
    private static final int CHUNK_X = 6;
    private static final int CHUNK_Z = 0;
    private static final int HEIGHT = 128;
    private static final int GRID_COLUMNS = 4;
    private static final int GRID_SPACING = 3;
    private static final String NAMESPACE = "kaleidoscopecookery:";
    private static final String BOWL = "minecraft:bowl";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    // Fixed PlateRegistry values; expectations do not come from the generated CE configuration.
    private static final List<PlateCase> PLATES = List.of(
            new PlateCase("shengjian_mantou_plate", 5, List.of(NAMESPACE + "shengjian_mantou")),
            new PlateCase("baozi_plate", 5, List.of(NAMESPACE + "baozi")),
            new PlateCase("qingtuan_plate", 4, List.of(NAMESPACE + "qingtuan")),
            new PlateCase("sticky_candy_plate", 4, List.of(NAMESPACE + "sticky_candy")),
            new PlateCase("sticky_rice_cake_plate", 5, List.of(NAMESPACE + "sticky_rice_cake")),
            new PlateCase("zongzi_plate", 4, List.of(NAMESPACE + "zongzi")),
            new PlateCase("berry_platter", 4, List.of("minecraft:sweet_berries", "minecraft:glow_berries")),
            new PlateCase("apple_platter", 4, List.of("minecraft:apple")),
            new PlateCase("tomato_platter", 5, List.of(NAMESPACE + "tomato")),
            new PlateCase("watermelon_platter", 3, List.of("minecraft:melon_slice")),
            new PlateCase("chorus_fruit_platter", 5, List.of("minecraft:chorus_fruit")));

    private record PlateCase(String name, int servings, List<String> items) {
        Key id() { return Key.of(NAMESPACE + name); }
    }

    private record Checkpoint(int stage, String sourceCommit, Map<String, Integer> servings) {}

    private final JavaPlugin plugin;
    private final World world;
    private final Path checkpoint;
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private final Map<String, Integer> spawned = new LinkedHashMap<>();
    private int stage;
    private int reentryChecks;
    private boolean finished;

    private PlateSmoke(JavaPlugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        checkpoint = plugin.getDataFolder().toPath().resolve("plate-checkpoint.json");
    }

    static void start(JavaPlugin plugin) {
        World world = Bukkit.getWorld("acceptance-world");
        if (world == null) throw new IllegalStateException("Plate probe requires acceptance-world");
        var probe = new PlateSmoke(plugin, world);
        world.getChunkAtAsync(CHUNK_X, CHUNK_Z).whenComplete((chunk, failure) -> {
            if (failure != null) {
                probe.failures.add("Cannot load plate fixture chunk: " + failure);
                probe.finish();
            } else Bukkit.getRegionScheduler().execute(plugin, world, CHUNK_X, CHUNK_Z,
                    () -> probe.guard(probe::prepare));
        });
    }

    private Location location(PlateCase plate) {
        int index = PLATES.indexOf(plate);
        check(index >= 0, "Unknown plate fixture " + plate.name());
        int x = CHUNK_X * 16 + 2 + index % GRID_COLUMNS * GRID_SPACING;
        int z = CHUNK_Z * 16 + 2 + index / GRID_COLUMNS * GRID_SPACING;
        check((x >> 4) == CHUNK_X && (z >> 4) == CHUNK_Z, "Plate fixture crossed a region boundary");
        return new Location(world, x, HEIGHT, z);
    }

    private void prepare() throws Exception {
        world.addPluginChunkTicket(CHUNK_X, CHUNK_Z, plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Checkpoint saved = Files.isRegularFile(checkpoint)
                ? JSON.fromJson(Files.readString(checkpoint, StandardCharsets.UTF_8), Checkpoint.class) : null;
        stage = saved == null ? 1 : saved.stage() + 1;
        check(stage <= 3, "Use a fresh fixture after three plate acceptance cycles");
        if (saved != null) {
            check(CookeryCatalog.SOURCE_COMMIT.equals(saved.sourceCommit()), "Plate checkpoint source changed");
        }
        for (PlateCase plate : PLATES) {
            var definition = CraftEngineBlocks.byId(plate.id());
            check(definition != null, plate.name() + ": block is not registered");
            Property<Integer> property = definition.defaultState().getProperty("servings");
            check(property != null && definition.defaultState().get(property) == plate.servings(),
                    plate.name() + ": default block state is not full");
            check(!InventoryUtils.createOrEmpty(plate.id()).isEmpty(), plate.name() + ": plate item cannot be built");
            for (String id : plate.items()) built(id, 1);
        }
        report.put("checkedPlates", PLATES.size());
        if (stage == 1) {
            check(collectWorldDrops().isEmpty(), "Fresh plate fixture contains old drops");
            // A complete floor keeps natural drops inside the owned chunk until they are counted.
            for (int x = CHUNK_X * 16; x < (CHUNK_X + 1) * 16; x++) {
                for (int z = CHUNK_Z * 16; z < (CHUNK_Z + 1) * 16; z++) {
                    world.getBlockAt(x, HEIGHT - 1, z).setType(Material.STONE);
                }
            }
            for (PlateCase plate : PLATES) {
                Location pos = location(plate);
                check(pos.getBlock().isEmpty(), plate.name() + ": fresh fixture position is occupied");
                place(plate);
            }
        }
        later(20, () -> {
            if (stage == 1) emptyFullPlates();
            else if (stage == 2) resumeEmptyPlates(saved);
            else verifyRemoved(saved);
        });
    }

    private void place(PlateCase plate) {
        check(CraftEngineBlocks.place(location(plate), CraftEngineBlocks.byId(plate.id()).defaultState(),
                UpdateFlags.UPDATE_ALL, false), plate.name() + ": CE placement failed");
    }

    private void emptyFullPlates() throws Exception {
        Map<String, Integer> takenItems = new LinkedHashMap<>();
        int takenServings = 0;
        for (PlateCase plate : PLATES) {
            assertServings(plate, plate.servings());
            PlateController controller = controller(plate);
            Item held = built(plate.items().getFirst(), 2);
            check(!controller.refill(held) && held.count() == 2, plate.name() + ": full plate consumed a refill");
            check(!controller.removeIfEmpty(), plate.name() + ": full plate was removed as empty");
            for (int remaining = plate.servings() - 1; remaining >= 0; remaining--) {
                take(plate, controller, remaining).forEach((id, count) -> takenItems.merge(id, count, Integer::sum));
                takenServings++;
            }
            check(!controller.takeServing(items -> { throw new IllegalStateException("Empty plate yielded food"); }),
                    plate.name() + ": empty take succeeded");
            assertServings(plate, 0);
        }
        check(spawned.isEmpty() && collectWorldDrops().isEmpty(), "Taking the last serving dropped a bowl or food early");
        report.put("defaultFull", PLATES.size());
        report.put("emptySaved", PLATES.size());
        report.put("takenServings", takenServings);
        report.put("takenItems", takenItems);
        report.put("berryOutputs", Map.of("minecraft:sweet_berries", takenItems.get("minecraft:sweet_berries"),
                "minecraft:glow_berries", takenItems.get("minecraft:glow_berries")));
        report.put("noEarlyBowl", true);
        save("EMPTY_PLATES_SAVED", snapshot());
    }

    private void resumeEmptyPlates(Checkpoint saved) {
        check(saved != null && saved.stage() == 1 && saved.servings().equals(snapshot()),
                "Empty plate servings changed on disk restart");
        check(collectWorldDrops().isEmpty() && spawned.isEmpty(), "Empty reload produced drops");
        int refilled = 0;
        for (PlateCase plate : PLATES) {
            assertServings(plate, 0);
            PlateController controller = controller(plate);
            Item wrong = built("minecraft:stone", 2);
            check(!controller.refill(wrong) && wrong.count() == 2, plate.name() + ": invalid refill consumed input");
            if (plate.items().size() == 1) {
                Item held = built(plate.items().getFirst(), 2);
                check(controller.refill(held) && held.count() == 1, plate.name() + ": refill did not consume exactly one");
                assertServings(plate, 1);
                take(plate, controller, 0);
                refilled++;
            } else {
                for (String id : plate.items()) {
                    Item held = built(id, 2);
                    check(!controller.refill(held) && held.count() == 2, "Mixed berry platter accepted a refill");
                }
                assertServings(plate, 0);
            }
            check(controller.removeIfEmpty(), plate.name() + ": next empty interaction did not remove the plate");
            check(!controller.removeIfEmpty(), plate.name() + ": removed plate could return a second bowl");
        }
        report.put("reloadedEmpty", PLATES.size());
        report.put("singleRefills", refilled);
        report.put("berryRefillRejected", true);
        later(20, () -> {
            assertRemoved();
            assertDrops(Map.of(BOWL, PLATES.size()), "Empty plate removal");
            report.put("emptyBowls", PLATES.size());
            for (PlateCase plate : PLATES) place(plate);
            later(20, this::breakPartialPlates);
        });
    }

    private void breakPartialPlates() {
        Map<String, Integer> expected = new LinkedHashMap<>();
        for (PlateCase plate : PLATES) {
            assertServings(plate, plate.servings());
            take(plate, controller(plate), plate.servings() - 1);
            plate.items().forEach(id -> expected.merge(id, plate.servings() - 1, Integer::sum));
            expected.merge(BOWL, 1, Integer::sum);
            // Include CE loot evaluation so a duplicate whole-plate loot entry fails conservation.
            check(CraftEngineBlocks.remove(location(plate).getBlock(), null, false, true, false),
                    plate.name() + ": CE break failed");
        }
        later(20, () -> {
            assertRemoved();
            assertDrops(expected, "Partial plate break");
            report.put("breakBowls", PLATES.size());
            report.put("breakDrops", expected);
            report.put("removedPlates", PLATES.size() * 2);
            save("PLATES_REMOVED", Map.of());
        });
    }

    private void verifyRemoved(Checkpoint saved) {
        check(saved != null && saved.stage() == 2 && saved.servings().isEmpty(), "Unexpected removed-plate checkpoint");
        assertRemoved();
        later(60, () -> {
            assertRemoved();
            assertDrops(Map.of(), "Removed plate reload");
            report.put("absentPlates", PLATES.size());
            report.put("emptyNoDuplicate", true);
            save("REMOVAL_RELOADED", Map.of());
        });
    }

    private Map<String, Integer> take(PlateCase plate, PlateController controller, int remaining) {
        Map<String, Integer> received = new LinkedHashMap<>();
        check(controller.takeServing(items -> {
            check(controller.servings() == remaining, plate.name() + ": serving was not committed before delivery");
            check(!controller.takeServing(nested -> { throw new IllegalStateException("Reentrant serving delivered"); }),
                    plate.name() + ": synchronous reentrant take succeeded");
            Item nestedRefill = built(plate.items().getFirst(), 2);
            check(!controller.refill(nestedRefill) && nestedRefill.count() == 2,
                    plate.name() + ": synchronous reentrant refill succeeded");
            check(!controller.removeIfEmpty(), plate.name() + ": synchronous reentrant removal succeeded");
            items.forEach(item -> add(received, item));
            reentryChecks++;
        }), plate.name() + ": serving take failed");
        Map<String, Integer> expected = new LinkedHashMap<>();
        plate.items().forEach(id -> expected.merge(id, 1, Integer::sum));
        check(received.equals(expected), plate.name() + ": serving outputs differ: " + received);
        assertServings(plate, remaining);
        return received;
    }

    private BlockEntity entity(PlateCase plate) {
        Location pos = location(plate);
        var entity = BukkitAdaptor.adapt(world).storageWorld().getBlockEntityAtIfLoaded(
                new BlockPos(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ()));
        check(entity != null, plate.name() + ": live block entity missing");
        return entity;
    }

    private PlateController controller(PlateCase plate) {
        var controller = entity(plate).controller.get(PlateController.class, 0);
        check(controller != null, plate.name() + ": live plate controller missing");
        return controller;
    }

    private void assertServings(PlateCase plate, int expected) {
        var state = CraftEngineBlocks.getCustomBlockState(location(plate).getBlock());
        check(state != null && state.owner().value().id().equals(plate.id()), plate.name() + ": plate block was replaced");
        Property<Integer> property = state.getProperty("servings");
        check(property != null && state.get(property) == expected && controller(plate).servings() == expected,
                plate.name() + ": block/controller serving count differs from " + expected);
    }

    private Map<String, Integer> snapshot() {
        Map<String, Integer> servings = new LinkedHashMap<>();
        for (PlateCase plate : PLATES) servings.put(plate.name(), controller(plate).servings());
        return servings;
    }

    private void assertRemoved() {
        for (PlateCase plate : PLATES) {
            check(location(plate).getBlock().isEmpty(), plate.name() + ": removed plate block survived");
            Location pos = location(plate);
            check(BukkitAdaptor.adapt(world).storageWorld().getBlockEntityAtIfLoaded(
                    new BlockPos(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ())) == null,
                    plate.name() + ": removed plate controller survived");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawned(ItemSpawnEvent event) {
        if (!inFixture(event.getLocation())) return;
        try { add(spawned, ItemStackUtils.wrap(ItemStackUtils.unwrap(event.getEntity().getItemStack()))); }
        catch (RuntimeException failure) { failures.add("Cannot inspect plate item spawn: " + failure); }
    }

    private boolean inFixture(Location pos) {
        return pos.getWorld() == world && (pos.getBlockX() >> 4) == CHUNK_X
                && (pos.getBlockZ() >> 4) == CHUNK_Z && Math.abs(pos.getY() - HEIGHT) <= 3;
    }

    private Map<String, Integer> collectWorldDrops() {
        Map<String, Integer> drops = new LinkedHashMap<>();
        for (var entity : world.getChunkAt(CHUNK_X, CHUNK_Z).getEntities()) {
            if (entity instanceof org.bukkit.entity.Item item && inFixture(item.getLocation())) {
                add(drops, ItemStackUtils.wrap(ItemStackUtils.unwrap(item.getItemStack())));
                entity.remove();
            }
        }
        return drops;
    }

    private void assertDrops(Map<String, Integer> expected, String context) {
        check(spawned.equals(expected), context + ": successful spawn events differ: " + spawned);
        Map<String, Integer> visible = collectWorldDrops();
        check(visible.equals(expected), context + ": visible item totals differ: " + visible);
        spawned.clear();
    }

    private static Item built(String id, int count) {
        Item item = InventoryUtils.createOrEmpty(Key.of(id));
        check(!item.isEmpty(), "Cannot build plate fixture item " + id);
        return item.copyWithCount(count);
    }

    private static void add(Map<String, Integer> totals, Item item) {
        check(item != null && !item.isEmpty() && item.count() > 0, "Plate delivered an empty item");
        totals.merge(item.id().asString(), item.count(), Integer::sum);
    }

    private void save(String phase, Map<String, Integer> servings) throws Exception {
        Files.writeString(checkpoint, JSON.toJson(new Checkpoint(stage, CookeryCatalog.SOURCE_COMMIT, servings)),
                StandardCharsets.UTF_8);
        report.put("phase", phase);
        report.put("servings", servings);
        finish();
    }

    private void later(long ticks, CheckedAction action) {
        Bukkit.getRegionScheduler().runDelayed(plugin, world, CHUNK_X, CHUNK_Z, ignored -> guard(action), ticks);
    }

    private void guard(CheckedAction action) {
        if (finished) return;
        try { action.run(); }
        catch (Exception | LinkageError failure) {
            failures.add(failure.toString());
            plugin.getLogger().log(Level.SEVERE, "Plate acceptance failed", failure);
            finish();
        }
    }

    private void finish() {
        if (finished) return;
        finished = true;
        HandlerList.unregisterAll(this);
        report.put("sourceCommit", CookeryCatalog.SOURCE_COMMIT);
        report.put("server", Bukkit.getVersion());
        report.put("craftEngine", Bukkit.getPluginManager().getPlugin("CraftEngine").getPluginMeta().getVersion());
        report.put("stage", stage);
        report.put("reentryChecks", reentryChecks);
        report.put("failures", failures);
        report.put("method", "SERVER_API_AND_REAL_CE_DROPS; controller delivery, block state and disk restart");
        report.put("playerFlow", "NOT_TESTED");
        report.put("clientResourcePack", "NOT_TESTED");
        report.put("protectionAndMultiplayer", "NOT_TESTED");
        report.put("crossRegion", "NOT_TESTED");
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            Files.writeString(plugin.getDataFolder().toPath().resolve("plate-smoke.json"), JSON.toJson(report),
                    StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            plugin.getLogger().log(Level.SEVERE, "Cannot write plate acceptance evidence", failure);
        }
        plugin.getLogger().log(failures.isEmpty() ? Level.INFO : Level.SEVERE,
                "COOKERY_PLATE_SMOKE stage=" + stage + " failures=" + failures.size());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
