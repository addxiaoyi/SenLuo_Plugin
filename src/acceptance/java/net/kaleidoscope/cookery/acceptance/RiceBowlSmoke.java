package net.kaleidoscope.cookery.acceptance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kaleidoscope.cookery.catalog.BakedFood;
import net.kaleidoscope.cookery.catalog.CookeryCatalog;
import net.kaleidoscope.cookery.catalog.QualityProfile;
import net.kaleidoscope.cookery.item.CookeryFoodItems;
import net.kaleidoscope.cookery.recipe.DishQuality;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemCraftResult;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** Calls the native recipe manager and persists actual results, without creating a player. */
final class RiceBowlSmoke {
    private static final int CHUNK_X = 10;
    private static final int CHUNK_Z = 0;
    private static final int BATCH_SIZE = 3;
    private static final String NAMESPACE = "kaleidoscopecookery:";
    private static final String RICE = NAMESPACE + "cooked_rice";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final CookeryCatalog CATALOG = CookeryCatalog.bundled();
    private static final List<String> DISHES = List.of("braised_beef", "fish_flavored_shredded_pork",
            "scramble_egg_with_tomatoes", "stir_fried_pork_with_peppers", "sweet_and_sour_pork");
    private static final List<QualityProfile> QUALITIES = List.of(QualityProfile.BASE,
            QualityProfile.of(DishQuality.POOR), QualityProfile.of(DishQuality.STANDARD),
            QualityProfile.of(DishQuality.EXCELLENT), QualityProfile.of(DishQuality.SUPERB));

    private record BowlRecipe(String id, String dish, String result) {}
    private record Checkpoint(int stage, String sourceCommit, String metadata, Map<String, Integer> items) {}
    private final JavaPlugin plugin;
    private final World world;
    private final Path checkpoint;
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private final List<ItemStack> savedProducts = new ArrayList<>();
    private List<BowlRecipe> recipes = List.of();
    private int stage;
    private boolean finished;
    private int qualityCases;
    private int extraQualityCases;
    private int extraConsumptionCases;
    private int duplicateRejected;
    private int mismatchRejected;
    private int repeatedCrafts;
    private int overflowBowls;

    private RiceBowlSmoke(JavaPlugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        checkpoint = plugin.getDataFolder().toPath().resolve("rice-checkpoint.json");
    }

    static void start(JavaPlugin plugin) {
        World world = Bukkit.getWorld("acceptance-world");
        if (world == null) throw new IllegalStateException("Rice bowl probe requires acceptance-world");
        var probe = new RiceBowlSmoke(plugin, world);
        world.getChunkAtAsync(CHUNK_X, CHUNK_Z).whenComplete((chunk, failure) -> {
            if (failure != null) {
                probe.failures.add("Cannot load rice fixture chunk: " + failure);
                probe.finish();
            } else Bukkit.getRegionScheduler().execute(plugin, world, CHUNK_X, CHUNK_Z,
                    () -> probe.guard(probe::prepare));
        });
    }

    private void prepare() throws Exception {
        world.addPluginChunkTicket(CHUNK_X, CHUNK_Z, plugin);
        recipes = readManifest();
        report.put("checkedRecipes", recipes.size());
        Checkpoint saved = Files.isRegularFile(checkpoint)
                ? JSON.fromJson(Files.readString(checkpoint, StandardCharsets.UTF_8), Checkpoint.class) : null;
        stage = saved == null ? 1 : saved.stage() + 1;
        check(stage <= 3, "Use a fresh rice fixture after three cycles");
        if (saved != null) check(CookeryCatalog.SOURCE_COMMIT.equals(saved.sourceCommit()), "Rice checkpoint source changed");
        if (stage == 1) {
            var block = chestLocation().getBlock();
            check(block.isEmpty(), "Fresh rice chest position is occupied");
            block.setType(Material.CHEST);
            check(chestItems().isEmpty(), "Fresh rice chest contains old items");
            runNativeCases();
            check(chest().getInventory().addItem(savedProducts.toArray(ItemStack[]::new)).isEmpty(), "Rice evidence chest overflowed");
            check(chestItems().equals(expectedStored()), "Native batch products differ before saving");
            save("CRAFTED_SAVED");
        } else if (stage == 2) {
            check(saved != null && saved.stage() == 1, "Rice checkpoint phase mismatch");
            check(saved.items().equals(expectedStored()) && saved.items().equals(chestItems()), "Rice products or containers changed after restart");
            check(saved.metadata().equals(metadata()), "Rice result components changed after restart");
            for (ItemStack item : chest().getInventory().getContents()) {
                if (empty(item) || item.getType() == Material.BOWL) continue;
                String id = id(item);
                int index = java.util.stream.IntStream.range(0, recipes.size())
                        .filter(candidate -> recipes.get(candidate).result().equals(id)).findFirst().orElseThrow();
                ItemStack single = item.clone();
                single.setAmount(1);
                assertFood(recipes.get(index), single, QUALITIES.get(index));
            }
            report.put("reloadedResults", recipes.size() * BATCH_SIZE);
            report.put("reloadedBowls", recipes.size() * BATCH_SIZE);
            report.put("metadataPreserved", true);
            chest().getInventory().clear();
            check(chestItems().isEmpty(), "Rice chest did not clear");
            save("RESTORED");
        } else {
            check(saved != null && saved.stage() == 2 && saved.items().isEmpty(), "Rice clear checkpoint mismatch");
            check(chestItems().isEmpty() && saved.metadata().equals(metadata()), "Cleared rice chest was repopulated");
            report.put("emptyNoDuplicate", true);
            save("EMPTY_RELOADED");
        }
    }

    private List<BowlRecipe> readManifest() throws Exception {
        Path path = plugin.getDataFolder().toPath().toAbsolutePath().getParent().getParent().resolve("ce-kitchen-manifest.json");
        JsonObject rows = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("riceBowls");
        check(rows != null && rows.size() == DISHES.size(), "Expected five riceBowls manifest entries");
        List<BowlRecipe> values = new ArrayList<>();
        for (String dish : DISHES) {
            String result = NAMESPACE + dish + "_rice_bowl";
            String recipeId = NAMESPACE + "upstream/kaleidoscope_cookery/" + dish + "_rice_bowl";
            JsonObject row = rows.getAsJsonObject(recipeId);
            check(row != null && row.get("sourceCommit").getAsString().equals(CookeryCatalog.SOURCE_COMMIT)
                    && strings(row, "dishes").contains(NAMESPACE + dish) && strings(row, "rice").contains(RICE)
                    && row.get("result").getAsString().equals(result)
                    && row.get("recipeRemainder").getAsString().equals("minecraft:bowl"), recipeId + ": manifest differs from source");
            values.add(new BowlRecipe(recipeId, NAMESPACE + dish, result));
        }
        return List.copyOf(values);
    }

    private void runNativeCases() {
        for (int index = 0; index < recipes.size(); index++) {
            BowlRecipe recipe = recipes.get(index);
            for (int dish = 0; dish < QUALITIES.size(); dish++) {
                for (int rice = 0; rice < QUALITIES.size(); rice++) {
                    ItemStack[] matrix = matrix(recipe, QUALITIES.get(dish), QUALITIES.get(rice), 1, (dish + rice) % 3);
                    craft(recipe, matrix, QUALITIES.get(Math.max(dish, rice)));
                    qualityCases++;
                }
            }
            checkExtras(recipe);
            checkRejections(recipe);
            checkBatch(recipe, QUALITIES.get(index));
        }
        report.put("qualityCases", qualityCases);
        report.put("extraQualityCases", extraQualityCases);
        report.put("extraConsumptionCases", extraConsumptionCases);
        report.put("duplicateRejected", duplicateRejected);
        report.put("mismatchRejected", mismatchRejected);
        report.put("repeatedCrafts", repeatedCrafts);
        report.put("overflowBowls", overflowBowls);
        report.put("storedResults", recipes.size() * BATCH_SIZE);
        report.put("storedBowls", recipes.size() * BATCH_SIZE);
        report.put("layouts", List.of("top_left_2x2_in_9_slot_matrix", "bottom_right_2x2_in_9_slot_matrix", "full_3x3"));
    }

    private void checkExtras(BowlRecipe recipe) {
        ItemStack[] qualityExtra = matrix(recipe, QUALITIES.get(1), QUALITIES.get(1), 1, 0);
        qualityExtra[8] = food(NAMESPACE + "baozi", QUALITIES.getLast(), 1);
        craft(recipe, qualityExtra, QUALITIES.getLast());
        extraQualityCases++;
        extraConsumptionCases++;
        ItemStack[] stoneExtra = matrix(recipe, QualityProfile.BASE, QualityProfile.BASE, 1, 0);
        stoneExtra[8] = new ItemStack(Material.STONE);
        craft(recipe, stoneExtra, QualityProfile.BASE);
        extraConsumptionCases++;
        ItemStack[] milkExtra = matrix(recipe, QualityProfile.BASE, QualityProfile.BASE, 1, 0);
        milkExtra[8] = new ItemStack(Material.MILK_BUCKET);
        craft(recipe, milkExtra, QualityProfile.BASE);
        extraConsumptionCases++;
    }

    private void checkRejections(BowlRecipe recipe) {
        ItemStack[] duplicateDish = matrix(recipe, QualityProfile.BASE, QualityProfile.BASE, 1, 0);
        duplicateDish[8] = food(recipe.dish(), QualityProfile.BASE, 1);
        reject(duplicateDish, "Duplicate dish slot");
        duplicateRejected++;
        ItemStack[] duplicateRice = matrix(recipe, QualityProfile.BASE, QualityProfile.BASE, 1, 0);
        duplicateRice[8] = food(RICE, QualityProfile.BASE, 1);
        reject(duplicateRice, "Duplicate rice slot");
        duplicateRejected++;
        ItemStack[] mismatch = matrix(recipe, QualityProfile.BASE, QualityProfile.BASE, 1, 0);
        mismatch[0] = new ItemStack(Material.STONE);
        reject(mismatch, "Missing dish input");
        mismatchRejected++;
    }

    private void checkBatch(BowlRecipe recipe, QualityProfile quality) {
        ItemStack[] matrix = matrix(recipe, quality, QualityProfile.BASE, BATCH_SIZE, 2);
        matrix[4] = new ItemStack(Material.STONE, BATCH_SIZE);
        for (int craft = 0; craft < BATCH_SIZE; craft++) {
            ItemCraftResult output = craft(recipe, matrix, quality);
            savedProducts.add(output.getResult().clone());
            for (ItemStack overflow : output.getOverflowItems()) {
                check(overflow.getType() == Material.BOWL, recipe.id() + ": unexpected batch overflow");
                overflowBowls += overflow.getAmount();
                savedProducts.add(overflow.clone());
            }
            matrix = output.getResultingMatrix();
            repeatedCrafts++;
        }
        check(totals(matrix).equals(Map.of("minecraft:bowl", 1)), recipe.id() + ": batch retained food or extra input");
        for (ItemStack remainder : matrix) if (!empty(remainder)) savedProducts.add(remainder.clone());
        reject(matrix, "Exhausted native batch");
    }

    private ItemStack[] matrix(BowlRecipe recipe, QualityProfile dish, QualityProfile rice, int amount, int layout) {
        ItemStack[] matrix = new ItemStack[9];
        matrix[layout == 1 ? 4 : 0] = food(recipe.dish(), dish, amount);
        matrix[layout == 0 ? 4 : 8] = food(RICE, rice, amount);
        return matrix;
    }

    private ItemStack food(String id, QualityProfile quality, int count) {
        Item raw = InventoryUtils.createOrEmpty(Key.of(id));
        check(!raw.isEmpty(), "Cannot construct rice ingredient " + id);
        Item baked = CookeryFoodItems.bake(raw, quality);
        ItemStack stack = ItemStackUtils.getBukkitStack(baked.minecraftItem()).clone();
        stack.setAmount(count);
        return stack;
    }

    private ItemCraftResult craft(BowlRecipe recipe, ItemStack[] matrix, QualityProfile quality) {
        ItemStack[] before = copy(matrix);
        var selected = Bukkit.getCraftingRecipe(matrix, world);
        check(selected instanceof Keyed keyed && keyed.getKey().toString().equals(recipe.id()),
                recipe.id() + ": server recipe manager selected " + selected);
        ItemCraftResult crafted = Bukkit.craftItemResult(matrix, world);
        check(Arrays.equals(before, matrix), recipe.id() + ": native query mutated the caller's matrix");
        assertFood(recipe, crafted.getResult(), quality);
        assertRemainders(before, crafted);
        return crafted;
    }

    private void assertFood(BowlRecipe recipe, ItemStack output, QualityProfile quality) {
        check(!empty(output) && output.getAmount() == 1 && id(output).equals(recipe.result()), recipe.id() + ": native output differs");
        BakedFood expected = BakedFood.of(CATALOG.snapshot().items().get(recipe.result()), quality);
        BakedFood actual = CookeryFoodItems.read(output);
        check(expected != null && expected.equals(actual), recipe.id() + ": result PDC/quality/nutrition differs");
        var food = output.getItemMeta().getFood();
        check(food.getNutrition() == expected.food().nutrition()
                && Math.abs(food.getSaturation() - expected.food().saturation()) < .0001f,
                recipe.id() + ": baked food components differ from PDC");
        check("minecraft:bowl".equals(actual.container()), recipe.id() + ": result lost its eating container");
    }

    private void assertRemainders(ItemStack[] before, ItemCraftResult crafted) {
        ItemStack[] after = crafted.getResultingMatrix();
        check(after.length == 9, "Native remainder matrix changed dimensions");
        Map<String, Integer> expectedOverflow = new LinkedHashMap<>();
        for (int slot = 0; slot < before.length; slot++) {
            ItemStack input = before[slot];
            ItemStack expected = null;
            if (!empty(input)) {
                if (input.getAmount() > 1) { expected = input.clone(); expected.setAmount(input.getAmount() - 1); }
                Material remainder = id(input).equals(RICE) ? Material.BOWL
                        : input.getType() == Material.MILK_BUCKET ? Material.BUCKET : null;
                if (remainder != null) {
                    if (expected == null) expected = new ItemStack(remainder);
                    else expectedOverflow.merge("minecraft:" + remainder.name().toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
                }
            }
            check(same(expected, after[slot]), "Native input/remainder differs in slot " + slot + ": " + after[slot]);
        }
        check(totals(crafted.getOverflowItems().toArray(ItemStack[]::new)).equals(expectedOverflow),
                "Native overflow remainders differ: " + crafted.getOverflowItems());
    }

    private void reject(ItemStack[] matrix, String context) {
        ItemStack[] before = copy(matrix);
        check(Bukkit.getCraftingRecipe(matrix, world) == null, context + ": native recipe unexpectedly matched");
        ItemCraftResult output = Bukkit.craftItemResult(matrix, world);
        check(empty(output.getResult()) && output.getOverflowItems().isEmpty(), context + ": native craft produced output");
        check(Arrays.equals(matrix, before), context + ": native query changed caller input");
        ItemStack[] after = output.getResultingMatrix();
        for (int slot = 0; slot < 9; slot++) check(same(before[slot], after[slot]), context + ": rejected craft consumed input");
    }

    private Map<String, Integer> expectedStored() {
        Map<String, Integer> expected = new LinkedHashMap<>();
        for (BowlRecipe recipe : recipes) expected.put(recipe.result(), BATCH_SIZE);
        expected.put("minecraft:bowl", recipes.size() * BATCH_SIZE);
        return expected;
    }

    private Location chestLocation() { return new Location(world, CHUNK_X * 16 + 2, 128, 2); }

    private Chest chest() {
        check(chestLocation().getBlock().getState() instanceof Chest, "Rice evidence chest did not survive restart");
        return (Chest) chestLocation().getBlock().getState();
    }

    private Map<String, Integer> chestItems() { return totals(chest().getInventory().getContents()); }

    private String metadata() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ItemStack[] contents = chest().getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            byte[] encoded = empty(contents[slot]) ? new byte[0] : contents[slot].serializeAsBytes();
            digest.update(ByteBuffer.allocate(8).putInt(slot).putInt(encoded.length).array());
            digest.update(encoded);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void save(String phase) throws Exception {
        String metadata = metadata();
        Map<String, Integer> items = chestItems();
        Files.writeString(checkpoint, JSON.toJson(new Checkpoint(stage, CookeryCatalog.SOURCE_COMMIT, metadata, items)), StandardCharsets.UTF_8);
        report.put("phase", phase);
        report.put("storedItems", items);
        report.put("chestMetadata", metadata);
        finish();
    }

    private static String id(ItemStack stack) { return ItemStackUtils.wrap(ItemStackUtils.unwrap(stack)).id().asString(); }
    private static boolean empty(ItemStack stack) { return stack == null || stack.isEmpty(); }
    private static boolean same(ItemStack first, ItemStack second) { return empty(first) ? empty(second) : first.equals(second); }
    private static ItemStack[] copy(ItemStack[] matrix) { return Arrays.stream(matrix).map(stack -> stack == null ? null : stack.clone()).toArray(ItemStack[]::new); }

    private static Map<String, Integer> totals(ItemStack[] items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack item : items) if (!empty(item)) counts.merge(id(item), item.getAmount(), Integer::sum);
        return counts;
    }

    private static List<String> strings(JsonObject row, String key) {
        return row.getAsJsonArray(key).asList().stream().map(element -> element.getAsString()).toList();
    }

    private void guard(CheckedAction action) {
        if (finished) return;
        try { action.run(); }
        catch (Exception | LinkageError failure) {
            failures.add(failure.toString());
            plugin.getLogger().log(Level.SEVERE, "Rice bowl acceptance failed", failure);
            finish();
        }
    }

    private void finish() {
        if (finished) return;
        finished = true;
        report.put("sourceCommit", CookeryCatalog.SOURCE_COMMIT);
        report.put("server", Bukkit.getVersion());
        report.put("craftEngine", Bukkit.getPluginManager().getPlugin("CraftEngine").getPluginMeta().getVersion());
        report.put("stage", stage);
        report.put("failures", failures);
        report.put("method", "BUKKIT_NATIVE_CRAFT_ITEM_RESULT_AND_REAL_CHEST; server recipe lookup/assemble/remainders, no player");
        report.put("playerFlow", "NOT_TESTED");
        report.put("shiftClick", "NOT_TESTED");
        report.put("player2x2Inventory", "NOT_TESTED");
        report.put("crafter", "NOT_TESTED");
        report.put("clientResourcePack", "NOT_TESTED");
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            Files.writeString(plugin.getDataFolder().toPath().resolve("rice-smoke.json"), JSON.toJson(report), StandardCharsets.UTF_8);
        } catch (Exception failure) { plugin.getLogger().log(Level.SEVERE, "Cannot write rice evidence", failure); }
        plugin.getLogger().log(failures.isEmpty() ? Level.INFO : Level.SEVERE,
                "COOKERY_RICE_SMOKE stage=" + stage + " failures=" + failures.size());
    }

    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
