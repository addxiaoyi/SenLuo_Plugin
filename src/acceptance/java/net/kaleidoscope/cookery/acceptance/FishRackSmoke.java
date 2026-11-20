package net.kaleidoscope.cookery.acceptance;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Calls the installed Deco controller's public region API and observes real spawn cancellation/restart. */
final class FishRackSmoke implements Listener {
    private static final int CHUNK_X = 20;
    private final JavaPlugin plugin;
    private final World world;
    private final Path checkpoint;
    private final List<String> failures = new ArrayList<>();
    private final List<ItemStack> spawned = new ArrayList<>();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private Class<? extends FurnitureController> type;
    private boolean observing, cancelDrops;
    private int canceled;

    private FishRackSmoke(JavaPlugin plugin, World world) {
        this.plugin = plugin; this.world = world;
        checkpoint = plugin.getDataFolder().toPath().resolve("fishrack-checkpoint.json");
    }

    static void start(JavaPlugin plugin) {
        World world = Bukkit.getWorld("acceptance-world");
        if (world == null) throw new IllegalStateException("Fishrack fixture requires acceptance-world");
        FishRackSmoke probe = new FishRackSmoke(plugin, world);
        Bukkit.getPluginManager().registerEvents(probe, plugin);
        world.getChunkAtAsync(CHUNK_X, 0).whenComplete((chunk, failure) -> {
            if (failure != null) { probe.failures.add(failure.toString()); probe.finish(); }
            else Bukkit.getRegionScheduler().execute(plugin, world, CHUNK_X, 0, () -> {
                try { probe.verify(); }
                catch (Exception | LinkageError error) {
                    probe.failures.add(error.toString());
                    plugin.getLogger().log(Level.SEVERE, "Fishrack acceptance failure", error);
                }
                probe.finish();
            });
        });
    }

    private Location at(int index) { return new Location(world, CHUNK_X * 16 + 4 + index * 3, 160, 8); }
    private Furniture place(int index) {
        at(index).clone().subtract(0, 1, 0).getBlock().setType(Material.STONE);
        return CraftEngineFurniture.place(at(index), CraftEngineFurniture.byId(Key.of("kaleidoscope_deco:fishrack_0")), "ground", false);
    }

    private void verify() throws Exception {
        var deco = Bukkit.getPluginManager().getPlugin("KaleidoscopeDecoPlugin");
        check(deco != null && deco.isEnabled(), "Deco not enabled");
        type = Class.forName("net.kaleidoscope.deco.FishRackController", true, deco.getClass().getClassLoader()).asSubclass(FurnitureController.class);
        world.addPluginChunkTicket(CHUNK_X, 0, plugin);
        if (Files.isRegularFile(checkpoint)) { resume(deco.getDataFolder().toPath()); return; }
        Furniture rack = place(0);
        definitions(rack);
        var controller = rack.controller.get(type, 0);
        check(controller != null, "Fishrack behavior not attached");
        ItemStack oversized = fish(Material.COD, 2, "unpersistable fish");
        byte[] noise = new byte[300000]; new java.util.Random(471).nextBytes(noise);
        oversized.editMeta(meta -> meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "large_fish"),
                PersistentDataType.BYTE_ARRAY, noise));
        check(!insert(controller, oversized) && oversized.getAmount() == 2 && contents(controller).isEmpty(),
                "Unpersistable item was consumed or stored");
        report.put("oversizedSaveRejectedBeforePayment", true);
        ItemStack cod = fish(Material.COD, 5, "named cod");
        ItemStack salmon = fish(Material.COOKED_SALMON, 2, "cooked salmon");
        ItemStack tropical = fish(Material.TROPICAL_FISH, 1, "tropical components");
        check(!insert(controller, new ItemStack(Material.PUFFERFISH)), "Accepted fish outside source tag");
        check(insert(controller, cod) && cod.getAmount() == 4, "Did not consume exactly one cod");
        check(insert(controller, salmon) && salmon.getAmount() == 1, "Did not consume exactly one cooked salmon");
        check(insert(controller, tropical) && tropical.isEmpty(), "Did not consume tropical fish");
        check(!insert(controller, cod) && cod.getAmount() == 4, "Full rack consumed another fish");
        check(rack.currentVariant().name().equals("fishrack/cod/salmon/tropical"), "Incorrect mixed fish appearance");
        var contents = contents(controller);
        check(contents.size() == 3 && contents.getFirst().equals(cod.asQuantity(1)), "Lost first-slot metadata");
        contents.getFirst().setAmount(32);
        check(contents(controller).getFirst().getAmount() == 1, "Snapshot exposes mutable stored items");
        rack.setUnsaved(); rack.saveIfDirty();
        write(checkpoint, Map.of("uuid", rack.uuid().toString(), "items", contents(controller).stream()
                .map(item -> Base64.getEncoder().encodeToString(item.serializeAsBytes())).toList()));
        report.put("phase", "THREE_FISH_SAVED");
        report.put("storedItems", 3);
        report.put("sourceConsumptionChecks", 4);
    }

    private void resume(Path decoFolder) throws Exception {
        var saved = JsonParser.parseString(Files.readString(checkpoint, StandardCharsets.UTF_8)).getAsJsonObject();
        UUID uuid = UUID.fromString(saved.get("uuid").getAsString());
        var meta = Arrays.stream(world.getChunkAt(CHUNK_X, 0).getEntities())
                .filter(entity -> entity.getUniqueId().equals(uuid)).findFirst().orElseThrow();
        Furniture rack = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(meta);
        check(rack != null, "CE did not restore fishrack");
        definitions(rack);
        var controller = rack.controller.get(type, 0);
        List<ItemStack> expected = new ArrayList<>();
        for (var item : saved.getAsJsonArray("items")) expected.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(item.getAsString())));
        check(contents(controller).equals(expected), "Restart lost item components/order");
        check(rack.currentVariant().name().equals("fishrack/cod/salmon/tropical"), "Restart lost display state");
        report.put("reloadedItems", 3);

        observing = true; cancelDrops = true;
        check(!take(controller, false), "Canceled spawn was treated as successful take");
        check(canceled == 1 && contents(controller).equals(expected), "Canceled take lost/changed stored fish");
        check(rack.currentVariant().name().equals("fishrack/cod/salmon/tropical"), "Canceled take changed model");
        cancelDrops = false;
        check(take(controller, true) && spawned.isEmpty(), "Creative take produced a reward");
        check(take(controller, false), "Survival take failed");
        check(spawned.equals(List.of(expected.get(1))), "LIFO take changed cooked fish components");
        check(contents(controller).equals(List.of(expected.getFirst())), "Take removed wrong slot");
        report.put("canceledTakeChecks", 1); report.put("creativeTakeChecks", 1); report.put("lifoTakeChecks", 1);

        check(insert(controller, expected.get(2).clone()), "Could not refill tropical slot");
        check(insert(controller, expected.getFirst().clone()), "Could not refill cod slot");
        List<ItemStack> removalExpected = contents(controller).reversed();
        spawned.clear(); rack.destroy();
        check(spawned.equals(removalExpected), "Removal did not return each exact stored fish once");
        check(contents(controller).isEmpty(), "Removed controller still owns delivered fish");
        int drops = spawned.size(); controller.postRemove(null);
        check(spawned.size() == drops, "Repeated postRemove duplicated fish");
        report.put("breakReturnedItems", drops);

        Furniture blockedDrop = place(1);
        var blockedController = blockedDrop.controller.get(type, 0);
        check(insert(blockedController, expected.getFirst().clone()), "Cannot seed canceled removal");
        spawned.clear(); cancelDrops = true; blockedDrop.destroy(); cancelDrops = false;
        check(spawned.isEmpty() && contents(blockedController).equals(List.of(expected.getFirst())), "Canceled removal lost recovery contents");
        try (var paths = Files.list(decoFolder.resolve("recovery"))) {
            Path recovery = paths.filter(path -> path.getFileName().toString().startsWith(blockedDrop.uuid() + "-"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Canceled removal has no durable recovery record"));
            var root = net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData.fromBytes(Files.readAllBytes(recovery)).copyTag();
            var data = root.getCompound("kaleidoscope_deco:fishrack_v1").getCompound("payload");
            check(data.getInt("count") == 1 && ItemStack.deserializeBytes(data.getByteArray("fish_0")).equals(expected.getFirst()),
                    "Durable recovery did not preserve exact canceled fish");
        }
        report.put("canceledRemovalRecoveryChecks", 1);
        observing = false;
        report.put("phase", "RELOADED_TAKEN_REMOVED");
        // Archive the consumed checkpoint so later runs start a fresh two-cycle
        // test rather than looking for the deliberately destroyed entity.
        Files.move(checkpoint, checkpoint.resolveSibling("fishrack-completed-" + uuid + ".json"));
    }

    private ItemStack fish(Material material, int count, String label) {
        ItemStack item = new ItemStack(material, count);
        item.editMeta(meta -> {
            meta.displayName(net.kyori.adventure.text.Component.text(label));
            meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "fish_components"), PersistentDataType.BYTE_ARRAY, new byte[]{3, 4, 5});
        });
        return item;
    }

    private void definitions(Furniture rack) {
        for (int i = 0; i < 4; i++) {
            var definition = CraftEngineFurniture.byId(Key.of("kaleidoscope_deco:fishrack_" + i));
            check(definition.settings().hitTimes() == 1, "Source fishrack must break on one hit");
            check(definition.settings().sounds().placeSound().id().equals(Key.of("minecraft:block.wood.hit")), "Wrong fishrack place sound");
            check(definition.settings().sounds().breakSound().id().equals(Key.of("minecraft:block.wood.break")), "Wrong fishrack break sound");
            var loot = definition.loot().getRandomItems(net.momirealms.craftengine.core.plugin.context.ContextHolder.empty(), rack.world());
            check(loot.size() == 1, "Wrong fishrack loot count");
            var item = net.momirealms.craftengine.bukkit.util.ItemStackUtils.getBukkitStack(loot.getFirst());
            check(item.getAmount() == 1 && Key.of("kaleidoscope_deco:fishrack_0").equals(
                    net.momirealms.craftengine.bukkit.api.CraftEngineItems.getCustomItemId(item)), "Fishrack loot must return empty state");
        }
        report.put("sourceLootAndBreakDefinitions", 4);
    }

    private boolean insert(FurnitureController controller, ItemStack item) throws Exception {
        return (boolean) type.getMethod("insert", ItemStack.class).invoke(controller, item);
    }
    private boolean take(FurnitureController controller, boolean creative) throws Exception {
        return (boolean) type.getMethod("take", Location.class, boolean.class).invoke(controller, at(0).add(0, 1, 0), creative);
    }
    @SuppressWarnings("unchecked") private List<ItemStack> contents(FurnitureController controller) throws Exception {
        return (List<ItemStack>) type.getMethod("contents").invoke(controller);
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void cancel(ItemSpawnEvent event) {
        if (observing && event.getLocation().getWorld() == world && event.getLocation().getBlockX() >> 4 == CHUNK_X && cancelDrops) {
            event.setCancelled(true); canceled++;
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void observe(ItemSpawnEvent event) {
        if (observing && event.getLocation().getWorld() == world && event.getLocation().getBlockX() >> 4 == CHUNK_X)
            spawned.add(event.getEntity().getItemStack().clone());
    }

    private void finish() {
        HandlerList.unregisterAll(this);
        report.put("server", Bukkit.getVersion()); report.put("failures", failures);
        report.put("playerFlow", "NOT_TESTED; uses installed public controller APIs and real Bukkit ItemSpawnEvent");
        try { write(plugin.getDataFolder().toPath().resolve("fishrack-smoke.json"), report); }
        catch (Exception error) { plugin.getLogger().log(Level.SEVERE, "Cannot write fishrack report", error); }
        plugin.getLogger().log(failures.isEmpty() ? Level.INFO : Level.SEVERE, "DECO_FISHRACK_SMOKE failures=" + failures.size());
        failures.forEach(plugin.getLogger()::severe);
    }
    private static void write(Path path, Object report) throws java.io.IOException {
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
