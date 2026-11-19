package net.kaleidoscope.cookery.acceptance;

import com.google.gson.GsonBuilder;
import net.kaleidoscope.cookery.catalog.BakedFood;
import net.kaleidoscope.cookery.catalog.BiteState;
import net.kaleidoscope.cookery.catalog.CookeryCatalog;
import net.kaleidoscope.cookery.catalog.QualityProfile;
import net.kaleidoscope.cookery.item.CookeryFoodItems;
import net.kaleidoscope.cookery.recipe.DishQuality;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** Checks real CE construction and Bukkit component serialization, without simulating a player. */
public final class FoodSmokePlugin extends JavaPlugin {
    private static final long PROBE_POLL_TICKS = 20;
    private static final long PROBE_WAIT_LIMIT = 20 * 240;

    @Override public void onEnable() {
        if (getConfig().getBoolean("connected-smoke.enabled", false)) ConnectedKitchenProbe.enable(this);
        long bootStarted = System.currentTimeMillis();
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            verify();
            EnchantmentSmoke.verify(this);
            if (getConfig().getBoolean("smithing-smoke.enabled", false)) SmithingSmoke.verify(this);
            if (getConfig().getBoolean("steamer-item-smoke.enabled", false)) SteamerItemSmoke.verify(this);
            long delay = 1;
            boolean millstoneEnabled = getConfig().getBoolean("millstone-smoke.enabled", false);
            boolean resumeMillstone = millstoneEnabled
                    && Files.isRegularFile(getDataFolder().toPath().resolve("millstone-checkpoint.json"));
            // Inspect restored animal progress before other probes consume its 40-second rotation.
            if (resumeMillstone) {
                startProbe("millstone smoke", delay, () -> MillstoneSmoke.start(this));
            }
            List<String> precedingReports = new ArrayList<>();
            if (getConfig().getBoolean("scarecrow-hit-smoke.enabled", false)) {
                startProbe("scarecrow hit smoke", delay, () -> ScarecrowHitSmoke.start(this));
                precedingReports.add("scarecrow-hit-smoke.json");
                delay += 200;
            }
            if (getConfig().getBoolean("scarecrow-smoke.enabled", false)) {
                startProbe("scarecrow smoke", delay, () -> ScarecrowSmoke.start(this));
                precedingReports.add("scarecrow-smoke.json");
                delay += 200;
            }
            if (getConfig().getBoolean("trashcan-smoke.enabled", false)) {
                startProbe("trash can smoke", delay, () -> TrashCanSmoke.start(this));
                precedingReports.add("trashcan-smoke.json");
                delay += 200;
            }
            if (getConfig().getBoolean("kitchen-smoke.enabled", false)) {
                startProbe("kitchen smoke", delay, () -> KitchenSmoke.start(this));
                precedingReports.add("kitchen-smoke.json");
                delay += 200;
            }
            if (getConfig().getBoolean("recipe-smoke.enabled", false)) {
                startProbe("recipe smoke", delay, () -> KitchenRecipeSmoke.start(this));
                precedingReports.add("recipe-smoke.json");
                delay += 200;
            }
            if (getConfig().getBoolean("pot-smoke.enabled", false)) {
                startProbe("pot smoke", delay, () -> PotRecipeSmoke.start(this));
                precedingReports.add("pot-smoke.json");
                delay += 200;
            }
            if (getConfig().getBoolean("bamboo-smoke.enabled", false)) {
                startProbe("bamboo smoke", delay, () -> BambooTraySmoke.start(this));
                precedingReports.add("bamboo-smoke.json");
                delay += 200;
            }
            if (getConfig().getBoolean("plate-smoke.enabled", false)) {
                startProbe("plate smoke", delay, () -> PlateSmoke.start(this));
                precedingReports.add("plate-smoke.json");
                delay += 200;
            }
            if (getConfig().getBoolean("meal-smoke.enabled", false)) {
                startProbe("placed meal smoke", delay, () -> PlacedMealSmoke.start(this));
                precedingReports.add("meal-smoke.json");
                delay += 200;
            }
            if (getConfig().getBoolean("rice-smoke.enabled", false)) {
                startProbe("rice bowl smoke", delay, () -> RiceBowlSmoke.start(this));
                precedingReports.add("rice-smoke.json");
            }
            if (millstoneEnabled && !resumeMillstone) {
                awaitMillstoneStart(List.copyOf(precedingReports), bootStarted, 0);
            }
        }, 100);
    }

    private void awaitMillstoneStart(List<String> reports, long bootStarted, long waitedTicks) {
        startProbe("millstone report barrier", PROBE_POLL_TICKS, () -> {
            for (String name : reports) {
                var path = getDataFolder().toPath().resolve(name);
                boolean currentReport;
                try {
                    currentReport = Files.isRegularFile(path)
                            && Files.getLastModifiedTime(path).toMillis() >= bootStarted;
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException("Cannot inspect current acceptance report " + path, failure);
                }
                if (!currentReport) {
                    check(waitedTicks < PROBE_WAIT_LIMIT, "Timed out waiting for current acceptance report " + name);
                    awaitMillstoneStart(reports, bootStarted, waitedTicks + PROBE_POLL_TICKS);
                    return;
                }
            }
            // Completed reports also include failures; the runner decides acceptance without hiding them.
            getLogger().info("Starting millstone after current probe reports; waitedTicks=" + waitedTicks);
            MillstoneSmoke.start(this);
        });
    }

    private void startProbe(String name, long delay, Runnable probe) {
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            try {
                probe.run();
            } catch (RuntimeException | LinkageError failure) {
                getLogger().log(Level.SEVERE, "Cannot start " + name + " acceptance probe", failure);
            }
        }, delay);
    }

    private void verify() {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        int checkedBites = 0;
        for (var entry : CookeryCatalog.bundled().snapshot().items().values()) {
            if (entry.bites() != null) {
                try {
                    BiteState state = BiteState.of(entry, QualityProfile.BASE);
                    check(state != null && state.remaining() == entry.bites().maxBites(), entry.id() + ": invalid bite state");
                    check(BiteState.decode(state.encode()).equals(state), entry.id() + ": bite state serialization changed");
                    check(entry.bites().visibleBites() > 0
                            && entry.bites().visibleBites() <= entry.bites().placement().slots().size(),
                            entry.id() + ": bite layout capacity mismatch");
                    checkedBites++;
                } catch (Exception | LinkageError exception) {
                    failures.add(entry.id() + ": bite state " + exception);
                }
            }
            BakedFood expected = BakedFood.of(entry, QualityProfile.BASE);
            if (expected == null) continue;
            try {
                var buildable = BukkitItemManager.instance().getBuildableItem(Key.of(entry.id())).orElseThrow();
                var built = buildable.buildItem((net.momirealms.craftengine.core.entity.player.Player) null);
                ItemStack direct = ItemStackUtils.getBukkitStack(built.minecraftItem());
                check(expected.equals(CookeryFoodItems.read(direct)), entry.id() + ": direct CE build omitted/changed food PDC");
                check(direct.getItemMeta().getFood().getNutrition() == expected.food().nutrition(), entry.id() + ": wrong food component");
                ItemStack restored = ItemStack.deserializeBytes(direct.serializeAsBytes());
                check(expected.equals(CookeryFoodItems.read(restored)), entry.id() + ": serialization lost food snapshot");
                // 1.21.1 stores eating effects in food; later versions use consumable.
                boolean modern = net.momirealms.craftengine.core.util.VersionHelper.isOrAbove1_21_2;
                var raw = built.getComponentAsJson(Key.of(modern ? "minecraft:consumable" : "minecraft:food"));
                check(raw != null && raw.isJsonObject(), entry.id() + ": missing consumption component");
                var component = raw.getAsJsonObject();
                long actual = 0;
                String effectsField = modern ? "on_consume_effects" : "effects";
                if (component.has(effectsField)) {
                    for (var effect : component.getAsJsonArray(effectsField)) {
                        if (!modern || "minecraft:apply_effects".equals(effect.getAsJsonObject().get("type").getAsString())) actual++;
                    }
                }
                long vanilla = expected.food().effects().stream().filter(effect -> effect.id().startsWith("minecraft:")).count();
                check(actual == vanilla, entry.id() + ": duplicated/missing vanilla effects");
                var created = InventoryUtils.createOrEmpty(Key.of(entry.id()));
                var quality = CookeryFoodItems.bake(created, QualityProfile.of(DishQuality.STANDARD));
                var scaled = CookeryFoodItems.read(ItemStackUtils.getBukkitStack(quality.minecraftItem()));
                check(BakedFood.of(entry, QualityProfile.of(DishQuality.STANDARD)).equals(scaled), entry.id() + ": quality differs");
                var twice = CookeryFoodItems.bake(quality, QualityProfile.of(DishQuality.STANDARD));
                check(scaled.equals(CookeryFoodItems.read(ItemStackUtils.getBukkitStack(twice.minecraftItem()))), entry.id() + ": quality applied twice");
                checked++;
            } catch (Exception | LinkageError exception) {
                failures.add(entry.id() + ": " + exception);
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("server", Bukkit.getVersion());
        report.put("craftEngine", Bukkit.getPluginManager().getPlugin("CraftEngine").getPluginMeta().getVersion());
        report.put("sourceCommit", CookeryCatalog.SOURCE_COMMIT);
        report.put("checkedConsumables", checked);
        report.put("checkedBites", checkedBites);
        report.put("failures", failures);
        report.put("playerFlow", "NOT_TESTED");
        report.put("clientResourcePack", "NOT_TESTED");
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(getDataFolder().toPath().resolve("food-smoke.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            getLogger().log(Level.SEVERE, "Cannot write food smoke evidence", exception);
        }
        getLogger().log(failures.isEmpty() ? Level.INFO : Level.SEVERE,
                "COOKERY_FOOD_SMOKE checked=" + checked + " failures=" + failures.size());
        failures.forEach(getLogger()::severe);
    }

    private static void check(boolean condition, String failure) {
        if (!condition) throw new IllegalStateException(failure);
    }
}
