package net.kaleidoscope.cookery.acceptance;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.kaleidoscope.cookery.util.CookeryYaml;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.entity.furniture.element.ItemDisplayFurnitureElement;
import net.momirealms.craftengine.bukkit.entity.furniture.element.ItemDisplayFurnitureElementConfig;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
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
import java.util.UUID;
import java.util.logging.Level;

/** Real CE registration, display construction, variant switching and restart; no simulated clicks. */
final class DecoModelSmoke {
    private static final int CHUNK_X = 12;
    private static final int CHUNK_Z = 0;
    private static final List<String> FIXTURES = List.of("apple_platter", "apple_platter_1", "apple_platter_2",
            "apple_platter_3", "apple_platter_4", "lardpot", "lardpot_open", "teapot", "teapot_boil",
            "kaleidoscope_bar/sofa", "kaleidoscope_bar/sofa_cozy");
    private final JavaPlugin plugin;
    private final World world;
    private final Path checkpoint;
    private final List<String> failures = new ArrayList<>();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final Map<String, String> fixtureIds = new LinkedHashMap<>();
    private final Map<String, Object> furnitureConfig = new LinkedHashMap<>();
    private int checkedItems, checkedFurniture, checkedVariants, placed, reloaded, switched;

    private DecoModelSmoke(JavaPlugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        checkpoint = plugin.getDataFolder().toPath().resolve("deco-model-checkpoint.json");
    }

    static void start(JavaPlugin plugin) {
        World world = Bukkit.getWorld("acceptance-world");
        DecoModelSmoke probe = new DecoModelSmoke(plugin, world);
        if (world == null) {
            probe.failures.add("Deco probe requires acceptance-world");
            probe.finish();
            return;
        }
        world.getChunkAtAsync(CHUNK_X, CHUNK_Z).whenComplete((chunk, failure) -> {
            if (failure != null) {
                probe.failures.add("Chunk load failed: " + failure);
                probe.finish();
            } else Bukkit.getRegionScheduler().execute(plugin, world, CHUNK_X, CHUNK_Z, () -> {
                try { probe.verify(); }
                catch (Exception | LinkageError error) { probe.failures.add("Deco probe: " + error); }
                probe.finish();
            });
        });
    }

    private void verify() throws Exception {
        check(Bukkit.getPluginManager().isPluginEnabled("KaleidoscopeDecoPlugin"), "Deco plugin is not enabled");
        Path content = plugin.getDataFolder().toPath().resolve("../CraftEngine/resources/deco/configuration/content.yml");
        Map<String, Object> config = mapping(CookeryYaml.readMapping(content));
        for (String id : mapping(config.get("items")).keySet()) {
            try {
                var built = BukkitItemManager.instance().getBuildableItem(Key.of(id)).orElseThrow()
                        .buildItem((net.momirealms.craftengine.core.entity.player.Player) null);
                ItemStack item = ItemStackUtils.getBukkitStack(built.minecraftItem());
                check(!item.isEmpty(), id + ": empty item");
                check(Key.of(id).equals(CraftEngineItems.getCustomItemId(item)), id + ": wrong custom identity");
                check(item.equals(ItemStack.deserializeBytes(item.serializeAsBytes())), id + ": serialization changed");
                checkedItems++;
            } catch (Exception | LinkageError error) { failures.add(id + ": " + error); }
        }
        furnitureConfig.putAll(mapping(config.get("furniture")));
        for (var entry : furnitureConfig.entrySet()) {
            try {
                String id = entry.getKey();
                var definition = CraftEngineFurniture.byId(Key.of(id));
                check(definition != null, id + ": missing furniture");
                Map<String, Object> variants = variants(id);
                check(definition.variants().keySet().equals(variants.keySet()), id + ": variant names changed");
                for (String name : variants.keySet()) {
                    var elements = definition.getVariant(name).elementConfigs();
                    check(elements.size() == 1 && elements.getFirst() instanceof ItemDisplayFurnitureElementConfig,
                            id + ": unexpected display definition");
                    var display = (ItemDisplayFurnitureElementConfig) elements.getFirst();
                    check(display.itemId.asString().equals(expectedVisual(id, name)), id + ": wrong variant visual " + name);
                    checkedVariants++;
                }
                checkedFurniture++;
            } catch (Exception | LinkageError error) { failures.add(entry.getKey() + ": " + error); }
        }
        world.addPluginChunkTicket(CHUNK_X, CHUNK_Z, plugin);
        boolean restart = Files.isRegularFile(checkpoint);
        if (restart) {
            var saved = JsonParser.parseString(Files.readString(checkpoint, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : saved.entrySet()) fixtureIds.put(entry.getKey(), entry.getValue().getAsString());
            check(fixtureIds.size() == FIXTURES.size(), "Incomplete saved fixture checkpoint");
        }
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int index = 0; index < FIXTURES.size(); index++) {
            String id = "kaleidoscope_deco:" + FIXTURES.get(index);
            try {
                Furniture furniture;
                if (restart) {
                    UUID uuid = UUID.fromString(fixtureIds.get(id));
                    var meta = Arrays.stream(world.getChunkAt(CHUNK_X, CHUNK_Z).getEntities())
                            .filter(entity -> entity.getUniqueId().equals(uuid)).findFirst().orElseThrow();
                    furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(meta);
                    check(furniture != null && furniture.id().asString().equals(id), id + ": missing restored furniture");
                    reloaded++;
                } else {
                    // Leave room for CE's collision query at the chunk border; adjacent chunks may be unloaded.
                    Location at = new Location(world, CHUNK_X * 16 + 3 + index % 4 * 3, 140, 3 + index / 4 * 4);
                    at.clone().subtract(0, 1, 0).getBlock().setType(Material.STONE);
                    furniture = CraftEngineFurniture.place(at, CraftEngineFurniture.byId(Key.of(id)), "ground", false);
                    check(furniture != null && furniture.isValid(), id + ": placement failed");
                    fixtureIds.put(id, furniture.uuid().toString());
                    placed++;
                }
                String initial = furniture.currentVariant().name();
                check(initial.startsWith("kaleidoscope_deco/"), id + ": controller did not apply its stored variant");
                assertVisual(furniture, id, initial);
                samples.add(Map.of("id", id, "variant", initial, "visual", expectedVisual(id, initial)));
                // Exercise CE element rebuilding; this does not stand in for the player interaction controller.
                try {
                    for (String name : variants(id).keySet()) {
                        if (!furniture.currentVariant().name().equals(name)) {
                            check(furniture.setVariant(name), id + ": variant rejected " + name);
                            switched++;
                        }
                        assertVisual(furniture, id, name);
                    }
                } finally {
                    if (!furniture.currentVariant().name().equals(initial))
                        check(furniture.setVariant(initial), id + ": could not restore initial variant");
                }
                ItemStack drop = ItemStackUtils.getBukkitStack(furniture.buildNewFurnitureItem());
                check(Key.of(id).equals(CraftEngineItems.getCustomItemId(drop)), id + ": internal display helper leaked into drop");
                furniture.setUnsaved();
                furniture.saveIfDirty();
            } catch (Exception | LinkageError error) { failures.add(id + ": " + error); }
        }
        report.put("samples", samples);
        report.put("phase", restart ? "RELOADED" : "PLACED_SAVED");
        if (failures.isEmpty()) write(checkpoint, fixtureIds);
    }

    private void assertVisual(Furniture furniture, String id, String variant) {
        check(furniture.elements().size() == 1 && furniture.elements().getFirst() instanceof ItemDisplayFurnitureElement,
                id + ": unexpected live display");
        var display = (ItemDisplayFurnitureElement) furniture.elements().getFirst();
        check(display.config.itemId.asString().equals(expectedVisual(id, variant)), id + ": stale live variant visual " + variant);
    }

    private Map<String, Object> variants(String id) { return mapping(mapping(furnitureConfig.get(id)).get("variants")); }

    private String expectedVisual(String id, String variant) {
        var elements = (List<?>) mapping(variants(id).get(variant)).get("elements");
        check(elements.size() == 1, id + ": expected one configured display");
        return (String) mapping(elements.getFirst()).get("item");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) { return (Map<String, Object>) value; }

    private void finish() {
        report.put("server", Bukkit.getVersion());
        report.put("checkedItems", checkedItems);
        report.put("checkedFurniture", checkedFurniture);
        report.put("checkedVariants", checkedVariants);
        report.put("placedFurniture", placed);
        report.put("reloadedFurniture", reloaded);
        report.put("switchedVariants", switched);
        report.put("failures", failures);
        report.put("playerFlow", "NOT_TESTED; placement and variant changes use CE server APIs");
        report.put("clientResourcePack", "NOT_TESTED; this verifies server display definitions only");
        try { write(plugin.getDataFolder().toPath().resolve("deco-model-smoke.json"), report); }
        catch (Exception error) { plugin.getLogger().log(Level.SEVERE, "Cannot write Deco model evidence", error); }
        plugin.getLogger().log(failures.isEmpty() ? Level.INFO : Level.SEVERE,
                "DECO_MODEL_SMOKE items=" + checkedItems + " furniture=" + checkedFurniture + " failures=" + failures.size());
        failures.forEach(plugin.getLogger()::severe);
    }

    private static void write(Path path, Object value) throws java.io.IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(value), StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
