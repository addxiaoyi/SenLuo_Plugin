package net.kaleidoscope.cookery.acceptance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kaleidoscope.cookery.block.entity.BambooTrayController;
import net.kaleidoscope.cookery.catalog.CookeryCatalog;
import net.kaleidoscope.cookery.recipe.BambooTrayMode;
import net.kaleidoscope.cookery.recipe.FoodRecipeRegistry;
import net.kaleidoscope.cookery.util.CookeryBlockStateEnvelope;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.inventory.CraftInventoryProxy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Hopper;
import org.bukkit.block.data.type.PointedDripstone;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** Real CE ticks, vanilla hopper events and disk restarts in one isolated chunk. */
final class BambooTraySmoke implements Listener {
    private static final Key TRAY = Key.of("kaleidoscopecookery:bamboo_tray");
    private static final String SAVE = TRAY.asString();
    private static final int CHUNK_X = 4;
    private static final int CHUNK_Z = 0;
    private static final int HEIGHT = 160;
    private static final int POLL_TICKS = 10;
    private static final int MAX_WORK_TICKS = 2400;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String[] NAMES = {"drying", "sheltered", "dripping", "stacked"};
    private record TrayState(List<String> items, List<Integer> amounts,
                             List<Integer> progress, List<Integer> completion) {}
    private record Checkpoint(int stage, Map<String, TrayState> trays, int hopperCount) {}
    private final JavaPlugin plugin;
    private final World world;
    private final Path checkpoint;
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private final Map<String, BambooTrayController> trays = new LinkedHashMap<>();
    private boolean finished;
    private boolean cancelExtraction = true;
    private int canceledExtractions;
    private int acceptedExtractions;
    private int elapsed;
    private int stage;

    private BambooTraySmoke(JavaPlugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        checkpoint = plugin.getDataFolder().toPath().resolve("bamboo-checkpoint.json");
    }

    static void start(JavaPlugin plugin) {
        World world = Bukkit.getWorld("acceptance-world");
        if (world == null) throw new IllegalStateException("Bamboo probe requires acceptance-world");
        var probe = new BambooTraySmoke(plugin, world);
        // World weather is global on Folia; every block operation below stays in chunk (4,0).
        world.setStorm(false);
        world.setThundering(false);
        world.setClearWeatherDuration(Integer.MAX_VALUE);
        world.getChunkAtAsync(CHUNK_X, CHUNK_Z).whenComplete((chunk, failure) -> {
            if (failure != null) {
                probe.failures.add("Cannot load bamboo fixture chunk: " + failure);
                probe.finish();
            } else Bukkit.getRegionScheduler().execute(plugin, world, CHUNK_X, CHUNK_Z,
                    () -> probe.guard(probe::prepare));
        });
    }

    private Location location(String name) {
        int index = List.of(NAMES).indexOf(name);
        if (index < 0) throw new IllegalArgumentException("Unknown bamboo fixture: " + name);
        return new Location(world, 68 + index % 2 * 5, HEIGHT, 4 + index / 2 * 7);
    }

    private void prepare() throws Exception {
        world.addPluginChunkTicket(CHUNK_X, CHUNK_Z, plugin);
        verifyRecipes();
        Checkpoint saved = Files.exists(checkpoint)
                ? JSON.fromJson(Files.readString(checkpoint, StandardCharsets.UTF_8), Checkpoint.class) : null;
        stage = saved == null ? 1 : saved.stage() + 1;
        check(stage <= 3, "Use a new fixture after the three acceptance cycles");
        if (stage == 1) {
            for (String name : NAMES) {
                Location pos = location(name);
                for (int y = HEIGHT - 1; y < world.getMaxHeight(); y++) {
                    world.getBlockAt(pos.getBlockX(), y, pos.getBlockZ()).setType(Material.AIR);
                }
                pos.clone().subtract(0, 1, 0).getBlock().setType(Material.STONE);
                check(CraftEngineBlocks.place(pos, CraftEngineBlocks.byId(TRAY).defaultState(),
                        UpdateFlags.UPDATE_ALL, false), name + ": placement failed");
            }
            check(CraftEngineBlocks.place(location("stacked").add(0, 1, 0),
                    CraftEngineBlocks.byId(TRAY).defaultState(), UpdateFlags.UPDATE_ALL, false), "Stack placement failed");
            location("sheltered").add(0, 1, 0).getBlock().setType(Material.STONE);
            dripstone("dripping", false);
        }
        later(20, () -> {
            for (String name : NAMES) {
                Location pos = location(name);
                var entity = BukkitAdaptor.adapt(world).storageWorld().getBlockEntityAtIfLoaded(
                        new BlockPos(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ()));
                check(entity != null, name + ": live block entity missing");
                var tray = entity.controller.get(BambooTrayController.class, 0);
                check(tray != null && tray.minecraftContainer() != null, name + ": live container missing");
                trays.put(name, tray);
            }
            if (stage == 1) beginEnvironmentChecks();
            else resume(saved);
        });
    }

    private void verifyRecipes() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.load(plugin.getDataFolder().toPath().getParent()
                .resolve("CraftEngine/resources/cookery_kitchen/configuration/kitchen.yml").toFile());
        var section = yaml.getConfigurationSection("bamboo_tray_recipes");
        check(section != null, "Bamboo configuration missing");
        var registry = FoodRecipeRegistry.instance();
        check(registry.bambooTrayRecipeCount() == section.getKeys(false).size(), "Bamboo registry count differs");
        int checked = 0;
        for (String id : section.getKeys(false)) {
            var row = section.getConfigurationSection(id);
            var recipe = registry.findBambooTray(Key.of(row.getString("require")),
                    BambooTrayMode.parse(row.getString("mode"))).orElseThrow();
            check(recipe.id().asString().equals(id) && recipe.result().asString().equals(row.getString("result"))
                            && recipe.resultCount() == row.getInt("result_count")
                            && recipe.durationTicks() == row.getInt("time"), id + ": runtime recipe changed");
            check(!InventoryUtils.createOrEmpty(recipe.result()).isEmpty(), id + ": output cannot be built");
            checked++;
        }
        report.put("checkedRecipes", checked);
    }

    private void beginEnvironmentChecks() {
        insert("drying", 0, "wet_sponge", 2);
        insert("drying", 1, "stone", 3);
        insert("sheltered", 0, "kelp", 3);
        insert("dripping", 0, "clay", 2);
        insert("stacked", 0, "mud", 1);
        later(60, () -> {
            check(state("drying").progress().getFirst() > 0, "Sky exposure did not dry");
            check(state("stacked").progress().getFirst() > 0, "Stacked tray blocked drying");
            check(state("sheltered").progress().getFirst() == 0, "Opaque roof allowed drying");
            check(state("dripping").progress().getFirst() == 0, "Dripstone without water processed clay");
            cover("drying");
            cover("stacked");
            location("sheltered").add(0, 1, 0).getBlock().setType(Material.GLASS);
            dripstone("dripping", true);
            later(60, () -> {
                check(state("sheltered").progress().getFirst() > 0, "Colorless glass blocked drying");
                check(state("dripping").progress().getFirst() > 0, "Water source dripstone did not wet");
                cover("sheltered");
                dripstone("dripping", false);
                Map<String, TrayState> paused = snapshot();
                later(40, () -> {
                    check(paused.equals(snapshot()), "Covered bamboo batch advanced while paused");
                    report.put("environmentChecks", List.of("sky_drying", "opaque_pause", "glass_drying",
                            "stacked_drying", "invalid_dripstone_pause", "water_dripstone_wetting"));
                    checkpoint("IN_PROGRESS_SAVED");
                });
            });
        });
    }

    private void resume(Checkpoint saved) {
        check(saved != null && saved.trays().equals(snapshot()), "Restart changed tray contents, progress or completion");
        report.put("reloadedTrays", trays.size());
        if (stage == 3) {
            check(hopperCount() == saved.hopperCount() && hopperCount() == 1, "Hopper output changed on restart");
            check(emptySlot("drying", 0), "Extracted tray slot reappeared on restart");
            later(60, () -> {
                check(saved.trays().equals(snapshot()) && hopperCount() == 1, "Empty slot produced again");
                report.put("emptyNoDuplicate", true);
                checkpoint("EMPTY_RELOADED");
            });
            return;
        }
        location("drying").add(0, 1, 0).getBlock().setType(Material.AIR);
        location("stacked").add(0, 2, 0).getBlock().setType(Material.AIR);
        location("sheltered").add(0, 1, 0).getBlock().setType(Material.GLASS);
        dripstone("dripping", true);
        elapsed = 0;
        awaitProducts();
    }

    private void awaitProducts() {
        elapsed += POLL_TICKS;
        check(elapsed <= MAX_WORK_TICKS, "Bamboo recipes did not complete in their source durations");
        if (!matches("drying", Material.SPONGE, 2) || !matches("sheltered", Material.DRIED_KELP, 3)
                || !matches("dripping", Material.MUD, 2) || !matches("stacked", Material.CLAY, 1)) {
            later(POLL_TICKS, this::awaitProducts);
            return;
        }
        report.put("completedOutputs", Map.of("sponge", 2, "dried_kelp", 3, "mud", 2, "clay", 1));
        ItemStack unchanged = inventory("drying").getItem(1);
        check(unchanged != null && unchanged.getType() == Material.STONE && unchanged.getAmount() == 3,
                "No-recipe input was lost");
        var single = trays.get("drying").take(0, false);
        check(single.id().asString().equals("minecraft:sponge") && single.count() == 1
                && matches("drying", Material.SPONGE, 1), "Single take did not preserve remaining output");
        report.put("takenSponge", 1);
        cover("sheltered");
        cover("stacked");
        dripstone("dripping", false);
        dripstone("drying", true);
        location("drying").add(-1, -1, 0).getBlock().setType(Material.REDSTONE_BLOCK);
        var hopperBlock = location("drying").add(0, -1, 0).getBlock();
        hopperBlock.setType(Material.HOPPER);
        var hopper = (org.bukkit.block.data.type.Hopper) hopperBlock.getBlockData();
        hopper.setEnabled(false);
        hopperBlock.setBlockData(hopper);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        elapsed = 0;
        awaitReverse();
    }

    private void awaitReverse() {
        elapsed += POLL_TICKS;
        check(elapsed <= MAX_WORK_TICKS, "Completed drying output did not reverse under water dripstone");
        if (!matches("drying", Material.WET_SPONGE, 1)) {
            later(POLL_TICKS, this::awaitReverse);
            return;
        }
        check(state("drying").completion().getFirst() == 2, "Wetting completion marker missing");
        report.put("reverseWetting", true);
        dripstone("drying", false);
        location("drying").add(-1, -1, 0).getBlock().setType(Material.AIR);
        var block = location("drying").add(0, -1, 0).getBlock();
        var hopper = (org.bukkit.block.data.type.Hopper) block.getBlockData();
        hopper.setEnabled(true);
        block.setBlockData(hopper);
        elapsed = 0;
        awaitCanceledExtraction();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(InventoryMoveItemEvent event) {
        Location source = event.getSource().getLocation();
        if (source == null || !source.getWorld().equals(world) || source.getBlockX() != 68
                || source.getBlockY() != HEIGHT || source.getBlockZ() != 4) return;
        if (cancelExtraction) {
            event.setCancelled(true);
            canceledExtractions++;
        } else if (!event.isCancelled()) acceptedExtractions++;
    }

    private void awaitCanceledExtraction() {
        elapsed += POLL_TICKS;
        check(elapsed <= 400, "Vanilla hopper did not emit cancelable extraction events");
        if (canceledExtractions < 2) {
            later(POLL_TICKS, this::awaitCanceledExtraction);
            return;
        }
        check(matches("drying", Material.WET_SPONGE, 1) && hopperCount() == 0
                && state("drying").completion().getFirst() == 2, "Canceled extraction lost item or completion");
        report.put("canceledExtractions", canceledExtractions);
        cancelExtraction = false;
        elapsed = 0;
        awaitExtraction();
    }

    private void awaitExtraction() {
        elapsed += POLL_TICKS;
        check(elapsed <= 400, "Uncanceled hopper failed to extract completed item");
        if (!emptySlot("drying", 0) || hopperCount() != 1) {
            later(POLL_TICKS, this::awaitExtraction);
            return;
        }
        later(60, () -> {
            check(hopperCount() == 1 && acceptedExtractions == 1, "Hopper duplicated output or extracted unfinished stone");
            check(state("drying").completion().getFirst() == 0 && state("drying").progress().getFirst() == 0,
                    "Hopper empty slot kept its finished state");
            report.put("acceptedExtractions", acceptedExtractions);
            report.put("emptyNoDuplicate", true);
            checkpoint("COMPLETED_RELOADED");
        });
    }

    private Inventory inventory(String name) {
        return CraftInventoryProxy.INSTANCE.newInstance(trays.get(name).minecraftContainer());
    }

    private boolean emptySlot(String name, int slot) {
        ItemStack item = inventory(name).getItem(slot);
        return item == null || item.isEmpty();
    }

    private int hopperCount() {
        var hopper = (Hopper) location("drying").add(0, -1, 0).getBlock().getState();
        int count = 0;
        for (ItemStack item : hopper.getInventory().getContents()) {
            if (item == null || item.isEmpty()) continue;
            check(item.getType() == Material.WET_SPONGE, "Hopper extracted an unfinished or unexpected item");
            count += item.getAmount();
        }
        return count;
    }

    private void insert(String tray, int slot, String item, int amount) {
        var built = InventoryUtils.createOrEmpty(Key.of("minecraft:" + item)).copyWithCount(amount);
        check(trays.get(tray).insert(slot, built) == amount, tray + ": fixture input was rejected");
    }

    private boolean matches(String tray, Material material, int count) {
        ItemStack item = inventory(tray).getItem(0);
        return item != null && item.getType() == material && item.getAmount() == count;
    }

    private void cover(String name) {
        location(name).add(0, name.equals("stacked") ? 2 : 1, 0).getBlock().setType(Material.STONE);
    }

    private void dripstone(String name, boolean water) {
        location(name).add(0, 1, 0).getBlock().setType(Material.AIR);
        location(name).add(0, 3, 0).getBlock().setType(Material.STONE);
        var tip = (PointedDripstone) Material.POINTED_DRIPSTONE.createBlockData();
        tip.setVerticalDirection(BlockFace.DOWN);
        tip.setThickness(PointedDripstone.Thickness.TIP);
        location(name).add(0, 2, 0).getBlock().setBlockData(tip);
        // Contain the source so water never replaces the tray or spreads to another fixture.
        for (BlockFace side : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
            location(name).add(0, 4, 0).getBlock().getRelative(side).setType(Material.STONE);
        }
        location(name).add(0, 4, 0).getBlock().setType(water ? Material.WATER : Material.AIR);
    }

    private TrayState state(String name) {
        CompoundTag root = new CompoundTag();
        trays.get(name).saveCustomData(root);
        CompoundTag payload = CookeryBlockStateEnvelope.payload(root, SAVE);
        List<String> items = new ArrayList<>();
        List<Integer> amounts = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            ItemStack item = inventory(name).getItem(slot);
            items.add(item == null || item.isEmpty() ? "AIR" : item.getType().name());
            amounts.add(item == null || item.isEmpty() ? 0 : item.getAmount());
        }
        List<Integer> progress = Arrays.stream(payload.getIntArray("progress")).boxed().toList();
        List<Integer> completion = new ArrayList<>();
        for (byte value : payload.getByteArray("completion")) completion.add((int) value);
        return new TrayState(List.copyOf(items), List.copyOf(amounts), progress, List.copyOf(completion));
    }

    private Map<String, TrayState> snapshot() {
        Map<String, TrayState> states = new LinkedHashMap<>();
        for (String name : NAMES) states.put(name, state(name));
        return states;
    }

    private void checkpoint(String phase) throws Exception {
        var saved = new Checkpoint(stage, snapshot(), stage == 1 ? 0 : hopperCount());
        Files.writeString(checkpoint, JSON.toJson(saved), StandardCharsets.UTF_8);
        report.put("phase", phase);
        report.put("states", saved.trays());
        report.put("hopperCount", saved.hopperCount());
        finish();
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    private void later(long ticks, CheckedAction action) {
        Bukkit.getRegionScheduler().runDelayed(plugin, world, CHUNK_X, CHUNK_Z,
                ignored -> guard(action), ticks);
    }

    private void guard(CheckedAction action) {
        if (finished) return;
        try { action.run(); }
        catch (Exception | LinkageError failure) {
            failures.add(failure.toString());
            plugin.getLogger().log(Level.SEVERE, "Bamboo acceptance failed", failure);
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
        report.put("failures", failures);
        report.put("playerFlow", "NOT_TESTED");
        report.put("clientResourcePack", "NOT_TESTED");
        report.put("rainAndCrossRegion", "NOT_TESTED");
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            Files.writeString(plugin.getDataFolder().toPath().resolve("bamboo-smoke.json"),
                    JSON.toJson(report), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            plugin.getLogger().log(Level.SEVERE, "Cannot write bamboo smoke evidence", failure);
        }
        plugin.getLogger().log(failures.isEmpty() ? Level.INFO : Level.SEVERE,
                "COOKERY_BAMBOO_SMOKE stage=" + stage + " failures=" + failures.size());
    }

    private static void check(boolean condition, String failure) {
        if (!condition) throw new IllegalStateException(failure);
    }
}
