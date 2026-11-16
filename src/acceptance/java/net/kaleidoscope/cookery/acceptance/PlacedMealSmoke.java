package net.kaleidoscope.cookery.acceptance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kaleidoscope.cookery.block.behavior.PlacedMealBehavior;
import net.kaleidoscope.cookery.block.entity.PlacedMealController;
import net.kaleidoscope.cookery.catalog.CookeryCatalog;
import net.kaleidoscope.cookery.catalog.PlacedMealDefinition;
import net.kaleidoscope.cookery.catalog.PlacedMealLayout;
import net.kaleidoscope.cookery.catalog.PlacedMealState;
import net.kaleidoscope.cookery.catalog.QualityProfile;
import net.kaleidoscope.cookery.item.CookeryFoodItems;
import net.kaleidoscope.cookery.item.behavior.PlacedMealItemBehavior;
import net.kaleidoscope.cookery.recipe.DishQuality;
import net.kaleidoscope.cookery.util.CookeryBlockStateEnvelope;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

/** Real placement, group state and drops; consumption callbacks stand in for a player. */
final class PlacedMealSmoke implements Listener {
    private static final int CHUNK_X = 8;
    private static final int CHUNK_Z = 0;
    private static final int HEIGHT = 128;
    private static final int ROWS = 4;
    private static final int SPACING = 3;
    private static final int LAYER_HEIGHT = 8;
    private static final String SAVE = "kaleidoscopecookery:placed_meal";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<PlacedMealDefinition> MEALS = CookeryCatalog.bundled().snapshot().items().values().stream()
            .filter(entry -> entry.bites() != null).sorted(java.util.Comparator.comparing(CookeryCatalog.Entry::id))
            .map(PlacedMealDefinition::from).toList();

    private record SavedMeal(String state, String group, String facing) {}
    private record Checkpoint(int stage, String sourceCommit, Map<String, SavedMeal> states) {}
    private final JavaPlugin plugin;
    private final World world;
    private final Path checkpoint;
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private final Map<String, Integer> spawned = new LinkedHashMap<>();
    private int stage;
    private int reentryChecks;
    private int committedBites;
    private boolean finished;

    private PlacedMealSmoke(JavaPlugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        checkpoint = plugin.getDataFolder().toPath().resolve("meal-checkpoint.json");
    }

    static void start(JavaPlugin plugin) {
        World world = Bukkit.getWorld("acceptance-world");
        if (world == null) throw new IllegalStateException("Meal probe requires acceptance-world");
        var probe = new PlacedMealSmoke(plugin, world);
        world.getChunkAtAsync(CHUNK_X, CHUNK_Z).whenComplete((chunk, failure) -> {
            if (failure != null) {
                probe.failures.add("Cannot load meal fixture chunk: " + failure);
                probe.finish();
            } else Bukkit.getRegionScheduler().execute(plugin, world, CHUNK_X, CHUNK_Z,
                    () -> probe.guard(probe::prepare));
        });
    }

    private Location location(PlacedMealDefinition meal) {
        int index = MEALS.indexOf(meal);
        check(index >= 0, "Unknown meal fixture " + meal.itemId());
        return new Location(world, CHUNK_X * 16 + 2 + index % ROWS * SPACING,
                HEIGHT + index / (ROWS * ROWS) * LAYER_HEIGHT,
                CHUNK_Z * 16 + 2 + index / ROWS % ROWS * SPACING);
    }

    private ImmutableBlockState placementState(PlacedMealDefinition meal) {
        CompoundTag properties = new CompoundTag();
        properties.putString("facing", facing(meal).name().toLowerCase(java.util.Locale.ROOT));
        return CraftEngineBlocks.byId(Key.of(meal.itemId())).getBlockState(properties);
    }

    private PlacedMealLayout.Facing facing(PlacedMealDefinition meal) {
        return PlacedMealLayout.Facing.values()[MEALS.indexOf(meal) % PlacedMealLayout.Facing.values().length];
    }

    private List<PlacedMealLayout.Cell> cells(PlacedMealDefinition meal) {
        Location at = location(meal);
        return meal.layout().cells(new PlacedMealLayout.Pos(at.getBlockX(), at.getBlockY(), at.getBlockZ()), facing(meal));
    }

    private Location location(PlacedMealLayout.Cell cell) {
        var pos = cell.pos();
        check((pos.x() >> 4) == CHUNK_X && (pos.z() >> 4) == CHUNK_Z, "Meal fixture exceeded its owned chunk");
        return new Location(world, pos.x(), pos.y(), pos.z());
    }

    private QualityProfile quality(PlacedMealDefinition meal) {
        return meal.layout() == PlacedMealLayout.NINE ? QualityProfile.BASE
                : QualityProfile.of(DishQuality.values()[MEALS.indexOf(meal) % DishQuality.values().length]);
    }

    private void prepare() throws Exception {
        world.addPluginChunkTicket(CHUNK_X, CHUNK_Z, plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        verifyDefinitions();
        Checkpoint saved = Files.isRegularFile(checkpoint)
                ? JSON.fromJson(Files.readString(checkpoint, StandardCharsets.UTF_8), Checkpoint.class) : null;
        stage = saved == null ? 1 : saved.stage() + 1;
        check(stage <= 3, "Use a fresh meal fixture after three cycles");
        if (saved != null) check(CookeryCatalog.SOURCE_COMMIT.equals(saved.sourceCommit()), "Meal checkpoint source changed");
        if (stage == 1) {
            assertDrops(Map.of(), "Fresh fixture");
            for (int layer = 0; layer < 3; layer++) {
                for (int x = CHUNK_X * 16; x < (CHUNK_X + 1) * 16; x++) {
                    for (int z = CHUNK_Z * 16; z < (CHUNK_Z + 1) * 16; z++) {
                        world.getBlockAt(x, HEIGHT + layer * LAYER_HEIGHT - 1, z).setType(Material.STONE);
                    }
                }
            }
            verifyBlockedPlacements();
            for (PlacedMealDefinition meal : MEALS) place(meal);
        }
        later(20, () -> {
            if (stage == 1) savePartialMeals();
            else if (stage == 2) finishMeals(saved);
            else verifyRemoved(saved);
        });
    }

    private void verifyDefinitions() throws Exception {
        JsonObject manifest = JsonParser.parseString(Files.readString(plugin.getDataFolder().toPath().toAbsolutePath().getParent().getParent()
                .resolve("ce-kitchen-manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("placedMeals");
        check(MEALS.size() == 38 && manifest != null && manifest.size() == MEALS.size(), "Expected all 38 source meals");
        int states = 0;
        int cellCount = 0;
        Map<String, Integer> layouts = new LinkedHashMap<>();
        for (PlacedMealDefinition meal : MEALS) {
            JsonObject row = manifest.getAsJsonObject(meal.itemId());
            check(row != null && row.get("sourceCommit").getAsString().equals(CookeryCatalog.SOURCE_COMMIT)
                    && row.get("maxBites").getAsInt() == meal.maxBites()
                    && row.get("layout").getAsString().equals(meal.layout().id()), meal.itemId() + ": manifest differs from catalog");
            var definition = CraftEngineBlocks.byId(Key.of(meal.itemId()));
            check(definition != null, meal.itemId() + ": block not registered");
            int expectedStates = 4 * (meal.maxBites() + 1) * cells(meal).size();
            check(row.get("states").getAsInt() == expectedStates
                    && definition.variantProvider().states().size() == expectedStates, meal.itemId() + ": state variants incomplete");
            for (ImmutableBlockState variant : definition.variantProvider().states()) {
                check(definition.getBlockState(variant.propertiesNbt()) == variant && variant.visualBlockState() != null,
                        meal.itemId() + ": state/model serialization differs");
                var detached = new BlockEntity(blockPos(location(meal)), variant);
                var original = detached.controller.get(PlacedMealController.class, 0);
                check(original != null, meal.itemId() + ": detached controller missing");
                CompoundTag tag = new CompoundTag();
                original.saveCustomData(tag);
                var restored = new BlockEntity(blockPos(location(meal)), variant).controller.get(PlacedMealController.class, 0);
                restored.loadCustomData(tag);
                check(original.state().equals(restored.state()) && original.group().equals(restored.group()),
                        meal.itemId() + ": detached state did not roundtrip");
                states++;
            }
            check(!InventoryUtils.createOrEmpty(Key.of(meal.itemId())).isEmpty(), meal.itemId() + ": whole item not buildable");
            for (String drop : meal.drops()) {
                check(!InventoryUtils.createOrEmpty(Key.of(drop)).isEmpty(), meal.itemId() + ": extra not buildable " + drop);
            }
            layouts.merge(meal.layout().id(), 1, Integer::sum);
            cellCount += cells(meal).size();
        }
        report.put("checkedMeals", MEALS.size());
        report.put("checkedStates", states);
        report.put("checkedCells", cellCount);
        report.put("layouts", layouts);
    }

    private void verifyBlockedPlacements() {
        for (PlacedMealDefinition meal : MEALS) {
            for (var cell : cells(meal)) check(location(cell).getBlock().isEmpty(), "Fresh meal cell is occupied");
            var obstruction = location(cells(meal).getLast()).getBlock();
            obstruction.setType(Material.STONE);
            check(!PlacedMealItemBehavior.placeAt(location(meal), placementState(meal)), meal.itemId() + ": placement replaced an obstacle");
            for (var cell : cells(meal)) {
                var block = location(cell).getBlock();
                check(block.equals(obstruction) ? block.getType() == Material.STONE : block.isEmpty(),
                        meal.itemId() + ": blocked placement changed another cell");
            }
            obstruction.setType(Material.AIR);
        }
        assertDrops(Map.of(), "Blocked placement");
        report.put("blockedPlacements", MEALS.size());
    }

    private void place(PlacedMealDefinition meal) {
        check(PlacedMealItemBehavior.placeAt(location(meal), placementState(meal)), meal.itemId() + ": multi-cell placement failed");
        var held = CookeryFoodItems.bake(InventoryUtils.createOrEmpty(Key.of(meal.itemId())), quality(meal));
        check(held != null && !held.isEmpty(), meal.itemId() + ": quality item could not be built");
        root(meal).loadCustomDataFromItem(held);
    }

    private void savePartialMeals() throws Exception {
        for (PlacedMealDefinition meal : MEALS) {
            assertGroup(meal, 0);
            var controller = root(meal);
            PlacedMealState before = controller.state();
            AtomicReference<BooleanSupplier> deferred = new AtomicReference<>();
            check(!controller.consume((food, commit) -> { deferred.set(commit); return false; }),
                    meal.itemId() + ": cancelled consumer succeeded");
            check(deferred.get() != null && !deferred.get().getAsBoolean(), meal.itemId() + ": expired commit consumed a bite");
            check(controller.state().equals(before), meal.itemId() + ": cancelled consumer spent a bite");
            consume(meal);
            assertGroup(meal, 1);
            verifyBadSave(meal);
        }
        assertDrops(Map.of(), "Partial consumption");
        report.put("placedMeals", MEALS.size());
        report.put("cancelledConsumes", MEALS.size());
        report.put("expiredCommitsRejected", MEALS.size());
        report.put("badSaveRejected", MEALS.size());
        save("PARTIAL_SAVED", snapshot());
    }

    private void verifyBadSave(PlacedMealDefinition meal) {
        var controller = root(meal);
        var before = controller.state();
        UUID group = controller.group();
        CompoundTag valid = new CompoundTag();
        controller.saveCustomData(valid);
        CompoundTag payload = CookeryBlockStateEnvelope.payload(valid, SAVE);
        payload.putString("schema", "invalid");
        CompoundTag malformed = new CompoundTag();
        CookeryBlockStateEnvelope.put(malformed, SAVE, payload);
        boolean rejected = false;
        try { controller.loadCustomData(malformed); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected && controller.state().equals(before) && controller.group().equals(group),
                meal.itemId() + ": malformed save replaced live state");
        assertGroup(meal, before.consumed());
    }

    private void finishMeals(Checkpoint saved) {
        check(saved != null && saved.stage() == 1 && saved.states().equals(snapshot()), "Meal group, quality or bites changed on restart");
        report.put("reloadedMeals", MEALS.size());
        Map<String, Integer> extras = new LinkedHashMap<>();
        for (PlacedMealDefinition meal : MEALS) {
            assertGroup(meal, 1);
            while (!root(meal).state().empty()) consume(meal);
            assertGroup(meal, meal.maxBites());
            check(!root(meal).consume((food, commit) -> { throw new IllegalStateException("Exhausted meal offered a bite"); }),
                    meal.itemId() + ": exhausted meal consumed again");
        }
        assertDrops(Map.of(), "Last bite must retain the exhausted meal");
        for (PlacedMealDefinition meal : MEALS) {
            check(root(meal).removeMeal(), meal.itemId() + ": exhausted removal failed");
            meal.drops().forEach(id -> extras.merge(id, 1, Integer::sum));
        }
        later(20, () -> {
            assertRemoved();
            assertDrops(extras, "Exhausted meal extras");
            report.put("exhaustedMeals", MEALS.size());
            report.put("removedExhausted", MEALS.size());
            report.put("extraDrops", extras);
            for (PlacedMealDefinition meal : MEALS) place(meal);
            later(20, this::breakUntouched);
        });
    }

    private void breakUntouched() {
        Map<String, Integer> expected = new LinkedHashMap<>();
        int membersBroken = 0;
        for (PlacedMealDefinition meal : MEALS) {
            assertGroup(meal, 0);
            var target = cells(meal).stream().filter(cell -> !cell.part().equals(meal.layout().anchorPart()))
                    .findFirst().orElse(cells(meal).getFirst());
            if (!target.part().equals(meal.layout().anchorPart())) membersBroken++;
            check(CraftEngineBlocks.remove(location(target).getBlock(), null, false, true, false),
                    meal.itemId() + ": member break failed");
            expected.put(meal.itemId(), 1);
        }
        report.put("nonAnchorBreaks", membersBroken);
        later(20, () -> {
            assertRemoved();
            assertDrops(expected, "Untouched meal recovery");
            report.put("untouchedWholeDrops", expected);
            save("REMOVED", Map.of());
        });
    }

    private void verifyRemoved(Checkpoint saved) {
        check(saved != null && saved.stage() == 2 && saved.states().isEmpty(), "Unexpected meal removal checkpoint");
        assertRemoved();
        later(60, () -> {
            assertRemoved();
            assertDrops(Map.of(), "Removed meal restart");
            report.put("absentMeals", MEALS.size());
            report.put("absentCells", MEALS.stream().mapToInt(meal -> cells(meal).size()).sum());
            report.put("emptyNoDuplicate", true);
            save("REMOVAL_RELOADED", Map.of());
        });
    }

    private void consume(PlacedMealDefinition meal) {
        var controller = root(meal);
        var before = controller.state();
        check(controller.consume((food, commit) -> {
            check(food.equals(meal.bite(before.quality())), meal.itemId() + ": prepared bite differs from its snapshot");
            check(!controller.consume((nested, nestedCommit) -> { throw new IllegalStateException("Reentrant meal delivery"); }),
                    meal.itemId() + ": reentrant consume succeeded");
            check(!controller.removeMeal(), meal.itemId() + ": meal removed during delivery");
            check(commit.getAsBoolean(), meal.itemId() + ": first commit was rejected");
            check(!commit.getAsBoolean(), meal.itemId() + ": duplicate commit spent another bite");
            reentryChecks++;
            return true;
        }), meal.itemId() + ": consuming a bite failed");
        committedBites++;
        assertGroup(meal, before.consumed() + 1);
    }

    private PlacedMealController root(PlacedMealDefinition meal) { return controller(blockPos(location(meal))); }

    private PlacedMealController controller(BlockPos pos) {
        var entity = BukkitAdaptor.adapt(world).storageWorld().getBlockEntityAtIfLoaded(pos);
        check(entity != null, "Meal block entity missing at " + pos);
        var controller = entity.controller.get(PlacedMealController.class, 0);
        check(controller != null, "Meal controller missing at " + pos);
        return controller;
    }

    private void assertGroup(PlacedMealDefinition meal, int consumed) {
        var root = root(meal);
        check(root.complete() && root.state().consumed() == consumed && root.state().quality().equals(quality(meal)),
                meal.itemId() + ": root state, quality or completeness differs");
        BlockPos anchor = blockPos(location(meal));
        for (var cell : cells(meal)) {
            var state = CraftEngineBlocks.getCustomBlockState(location(cell).getBlock());
            check(state != null && state.owner().value().id().asString().equals(meal.itemId()), meal.itemId() + ": member missing");
            var behavior = state.behavior().getFirst(PlacedMealBehavior.class);
            BlockPos pos = blockPos(location(cell));
            check(behavior != null && behavior.anchor(pos, state).equals(anchor)
                    && behavior.part(state).equals(cell.part()) && behavior.bites(state) == consumed
                    && behavior.orientation(state) == facing(meal), meal.itemId() + ": member state/anchor differs");
            var member = controller(pos);
            check(member.complete() && member.group().equals(root.group()) && member.state().equals(root.state()),
                    meal.itemId() + ": cell generation or save differs");
        }
    }

    private Map<String, SavedMeal> snapshot() {
        Map<String, SavedMeal> saved = new LinkedHashMap<>();
        for (PlacedMealDefinition meal : MEALS) {
            var controller = root(meal);
            saved.put(meal.itemId(), new SavedMeal(controller.state().encode(), controller.group().toString(), facing(meal).name()));
        }
        return saved;
    }

    private void assertRemoved() {
        for (PlacedMealDefinition meal : MEALS) for (var cell : cells(meal)) {
            Location pos = location(cell);
            check(pos.getBlock().isEmpty() && BukkitAdaptor.adapt(world).storageWorld()
                    .getBlockEntityAtIfLoaded(blockPos(pos)) == null, meal.itemId() + ": removed cell survived at " + pos);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent event) {
        if (!inFixture(event.getLocation())) return;
        try {
            var item = ItemStackUtils.wrap(ItemStackUtils.unwrap(event.getEntity().getItemStack()));
            spawned.merge(item.id().asString(), item.count(), Integer::sum);
        } catch (RuntimeException failure) { failures.add("Cannot read meal drop event: " + failure); }
    }

    private boolean inFixture(Location pos) {
        return pos.getWorld() == world && (pos.getBlockX() >> 4) == CHUNK_X && (pos.getBlockZ() >> 4) == CHUNK_Z
                && pos.getY() >= HEIGHT - 1 && pos.getY() < HEIGHT + 3 * LAYER_HEIGHT;
    }

    private void assertDrops(Map<String, Integer> expected, String context) {
        check(spawned.equals(expected), context + ": spawn event totals differ: " + spawned);
        Map<String, Integer> visible = new LinkedHashMap<>();
        for (var entity : world.getChunkAt(CHUNK_X, CHUNK_Z).getEntities()) {
            if (entity instanceof org.bukkit.entity.Item drop && inFixture(drop.getLocation())) {
                var item = ItemStackUtils.wrap(ItemStackUtils.unwrap(drop.getItemStack()));
                visible.merge(item.id().asString(), item.count(), Integer::sum);
                drop.remove();
            }
        }
        check(visible.equals(expected), context + ": visible item totals differ: " + visible);
        spawned.clear();
    }

    private static BlockPos blockPos(Location at) { return new BlockPos(at.getBlockX(), at.getBlockY(), at.getBlockZ()); }

    private void save(String phase, Map<String, SavedMeal> states) throws Exception {
        Files.writeString(checkpoint, JSON.toJson(new Checkpoint(stage, CookeryCatalog.SOURCE_COMMIT, states)), StandardCharsets.UTF_8);
        report.put("phase", phase);
        report.put("states", states);
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
            plugin.getLogger().log(Level.SEVERE, "Placed meal acceptance failed", failure);
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
        report.put("committedBites", committedBites);
        report.put("reentryChecks", reentryChecks);
        report.put("failures", failures);
        report.put("method", "SERVER_API_AND_REAL_CE_DROPS; consumption commits have no player");
        report.put("playerNutritionAndEffects", "NOT_TESTED");
        report.put("playerFlow", "NOT_TESTED");
        report.put("clientResourcePack", "NOT_TESTED");
        report.put("protectionAndMultiplayer", "NOT_TESTED");
        report.put("crossRegion", "NOT_TESTED");
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            Files.writeString(plugin.getDataFolder().toPath().resolve("meal-smoke.json"), JSON.toJson(report), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) { plugin.getLogger().log(Level.SEVERE, "Cannot write meal acceptance evidence", failure); }
        plugin.getLogger().log(failures.isEmpty() ? Level.INFO : Level.SEVERE,
                "COOKERY_MEAL_SMOKE stage=" + stage + " failures=" + failures.size());
    }

    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
