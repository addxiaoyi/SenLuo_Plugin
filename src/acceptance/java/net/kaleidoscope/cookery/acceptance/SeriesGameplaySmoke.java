package net.kaleidoscope.cookery.acceptance;

import com.google.gson.GsonBuilder;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.hitbox.InteractionFurnitureHitboxConfig;
import net.momirealms.craftengine.bukkit.entity.furniture.hitbox.ShulkerFurnitureHitboxConfig;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.item.behavior.FurnitureItemBehavior;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.entity.furniture.AlignmentRule;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData;
import net.momirealms.craftengine.core.entity.furniture.FurnitureVariant;
import net.momirealms.craftengine.core.entity.furniture.RotationRule;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** Actual CE definitions, controllers, display creation and binary state round-trips; no simulated player. */
final class SeriesGameplaySmoke {
    private static final int CHUNK_X = 28, CHUNK_Z = 0, Y = 160;
    private static final String TAVERN = "kaleidoscope_tavern:", DECO = "kaleidoscope_deco:";
    private static final String BOARD_SAVE = TAVERN + "sandwich_board_v1";
    private static final String SEAT_SAVE = DECO + "wooden_seat_v1";
    private static final String STORAGE_SAVE = TAVERN + "molotov_storage_v1";
    private static final List<String> STYLES = List.of("base", "grass", "allium", "azure_bluet", "cornflower", "orchid",
            "peony", "pink_petals", "pitcher_plant", "poppy", "sunflower", "torchflower", "tulip", "wither_rose");
    private static final List<String> WOODS = List.of("acacia", "bamboo", "birch", "cherry", "crimson", "dark_oak",
            "jungle", "mangrove", "oak", "pale_oak", "spruce", "warped");
    private static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
            "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final List<String> STORAGES = List.of("molotov", "holder", "tilted_rack", "circular_rack", "cellar_cabinet");
    private static final List<String> SAMPLES = List.of(TAVERN + "base_sandwich_board", TAVERN + "poppy_sandwich_board",
            DECO + "chair/oak", DECO + "cook_stool/bamboo", TAVERN + "molotov", TAVERN + "holder",
            TAVERN + "tilted_rack", TAVERN + "circular_rack", TAVERN + "cellar_cabinet");

    private final JavaPlugin plugin;
    private final World world;
    private final String runId = System.getProperty("senluo.series.smoke.run_id", UUID.randomUUID().toString());
    private final List<String> failures = new ArrayList<>();
    private final List<Map<String, Object>> definitions = new ArrayList<>(), samples = new ArrayList<>();
    private final List<Placed> placed = new ArrayList<>();
    private final List<BlockSnapshot> changedBlocks = new ArrayList<>();
    private final Set<UUID> originalEntities = new HashSet<>(), createdEntities = new HashSet<>();
    private int checkedDefinitions, checkedVariants, checkedItems, roundTrips, removedFurniture;
    private int liveStorageDisplays, cleanedEntities;
    private boolean ticket;

    private record BlockSnapshot(Block block, BlockData data) {}
    private record Placed(Furniture furniture, FurnitureController controller, String saveKey, CompoundTag empty) {}

    private SeriesGameplaySmoke(JavaPlugin plugin, World world) { this.plugin = plugin; this.world = world; }

    /** Call once after CE and the Deco/Tavern modules have finished loading. */
    static void start(JavaPlugin plugin) {
        World world = Bukkit.getWorld("acceptance-world");
        var probe = new SeriesGameplaySmoke(plugin, world);
        if (world == null) {
            probe.failures.add("Requires the isolated acceptance-world; no other world was modified");
            probe.finish();
            return;
        }
        world.getChunkAtAsync(CHUNK_X, CHUNK_Z).whenComplete((chunk, failure) -> {
            if (failure != null) { probe.failures.add("Chunk load: " + failure); probe.finish(); return; }
            Bukkit.getRegionScheduler().execute(plugin, world, CHUNK_X, CHUNK_Z, probe::run);
        });
    }

    private void run() {
        try {
            check(Bukkit.isOwnedByCurrentRegion(at(0)), "Probe is not on its owning region");
            world.addPluginChunkTicket(CHUNK_X, CHUNK_Z, plugin); ticket = true;
            for (Entity entity : world.getChunkAt(CHUNK_X, CHUNK_Z).getEntities()) originalEntities.add(entity.getUniqueId());
            for (String style : STYLES) inspectDefinition(TAVERN + style + "_sandwich_board", "board");
            for (String storage : STORAGES) inspectDefinition(TAVERN + storage, storage);
            for (String wood : WOODS) {
                inspectDefinition(DECO + "chair/" + wood, "chair");
                inspectDefinition(DECO + "cook_stool/" + wood, "stool");
            }
            check(checkedDefinitions == 43, "Expected 43 native definitions; got " + checkedDefinitions);
            for (int i = 0; i < SAMPLES.size(); i++) {
                try { inspectPlacement(i, SAMPLES.get(i)); }
                catch (Exception | LinkageError failure) { failed(SAMPLES.get(i), failure); }
            }
        } catch (Exception | LinkageError failure) { failed("Probe setup", failure); }
        finally {
            try { cleanup(); } catch (Exception | LinkageError failure) { failed("Probe cleanup", failure); }
            finish();
        }
    }

    private void inspectDefinition(String id, String kind) {
        try {
            FurnitureDefinition definition = Objects.requireNonNull(CraftEngineFurniture.byId(Key.of(id)), "Missing furniture " + id);
            check(!definition.behaviors().isEmpty(), id + ": behavior registration missing");
            var nativeItem = BukkitItemManager.instance().getItemDefinition(Key.of(id)).orElseThrow();
            check(nativeItem.behavior() instanceof FurnitureItemBehavior, id + ": missing native furniture item behavior");
            var behavior = (FurnitureItemBehavior) nativeItem.behavior();
            check(behavior.furniture().equals(Key.of(id)), id + ": furniture item points elsewhere");
            var rule = Objects.requireNonNull(behavior.rules().get("ground"), "Missing ground placement rule " + id);
            check(rule.alignmentRule() == AlignmentRule.CENTER, id + ": placement alignment differs");
            RotationRule rotation = STORAGES.contains(kind) ? RotationRule.FOUR : RotationRule.SIXTEEN;
            check(rule.rotationRule() == rotation, id + ": native rotation differs from " + rotation);
            check(definition.variants().containsKey("ground"), id + ": ground variant missing");
            int expectedVariants = kind.equals("board") ? 15 : kind.equals("chair") ? 17 : 1;
            check(definition.variants().size() == expectedVariants, id + ": expected " + expectedVariants + " variants");
            if (kind.equals("board")) check(definition.variants().keySet().containsAll(STYLES), id + ": a board style is missing");
            if (kind.equals("chair")) for (String color : COLORS) {
                check(definition.variants().containsKey("cushion/" + color), id + ": missing cushion " + color);
                build(id + "/cushion/" + color);
            }
            for (FurnitureVariant variant : definition.variants().values()) {
                inspectHitboxes(id, kind, variant); checkedVariants++;
                check(!variant.elementConfigs().isEmpty(), id + ": empty native display geometry");
            }
            ItemStack stack = build(id);
            if (kind.equals("molotov")) {
                var consumable = stack.getData(DataComponentTypes.CONSUMABLE);
                check(consumable != null && consumable.consumeSeconds() == 3600 && consumable.animation() == ItemUseAnimation.SPEAR
                        && !consumable.hasConsumeParticles(), "Molotov permanent throw component differs");
            }
            definitions.add(Map.of("id", id, "kind", kind, "variants", expectedVariants, "rotation", rotation.name(),
                    "hitboxes", definition.getVariant("ground").hitBoxConfigs().size()));
            checkedDefinitions++;
        } catch (Exception | LinkageError failure) { failed("Definition " + id, failure); }
    }

    private void inspectHitboxes(String id, String kind, FurnitureVariant variant) {
        var boxes = variant.hitBoxConfigs();
        int count = kind.equals("chair") || kind.equals("stool") || kind.equals("holder") || kind.equals("tilted_rack") ? 2 : 1;
        check(boxes.size() == count, id + "/" + variant.name() + ": hitbox count differs");
        check(boxes.getFirst() instanceof InteractionFurnitureHitboxConfig, id + ": native interaction hitbox missing");
        var interaction = (InteractionFurnitureHitboxConfig) boxes.getFirst();
        double width = switch (kind) { case "board" -> .75; case "molotov", "holder" -> .375;
            case "tilted_rack" -> .625; case "stool" -> .8; default -> 1; };
        double height = switch (kind) { case "board" -> 1.375; case "molotov", "tilted_rack" -> .875;
            case "circular_rack" -> .125; case "stool" -> .5; default -> 1; };
        near(interaction.size().x, width, id + ": native width");
        near(interaction.size().y, height, id + ": native height");
        check(interaction.seats().length == (kind.equals("chair") ? 1 : 0), id + ": native seat definition differs");
        if (kind.equals("chair") || kind.equals("stool")) {
            check(boxes.get(1) instanceof ShulkerFurnitureHitboxConfig, id + ": physical shulker missing");
            var shulker = (ShulkerFurnitureHitboxConfig) boxes.get(1);
            near(shulker.scale(), kind.equals("chair") ? .8 : .6, id + ": collision scale");
            near(shulker.position().y, kind.equals("chair") ? -.2 : -.18, id + ": collision offset");
            check(shulker.peek() == 0 && shulker.direction() == Direction.UP && !shulker.interactive()
                    && !shulker.interactionEntity(), id + ": physical shulker settings differ");
        } else if (kind.equals("holder") || kind.equals("tilted_rack")) {
            for (int i = 0; i < 2; i++) {
                check(boxes.get(i) instanceof InteractionFurnitureHitboxConfig, id + ": rectangular native hitbox part missing");
                var part = (InteractionFurnitureHitboxConfig) boxes.get(i);
                near(part.size().x, width, id + ": rectangular hitbox width");
                near(part.size().y, height, id + ": rectangular hitbox height");
                near(part.position().x, kind.equals("holder") ? 0 : i == 0 ? -.1875 : .1875, id + ": local hitbox X");
                near(part.position().z, kind.equals("holder") ? i == 0 ? -.1875 : .1875 : .125, id + ": local hitbox Z");
            }
        }
    }

    private void inspectPlacement(int index, String id) throws Exception {
        Location at = at(index);
        prepare(at);
        Furniture furniture = Objects.requireNonNull(CraftEngineFurniture.place(at,
                Objects.requireNonNull(CraftEngineFurniture.byId(Key.of(id))), "ground", false), id + ": native placement failed");
        // Record placement before controller lookup, so even a linkage failure is included in cleanup.
        Placed entry = new Placed(furniture, null, null, null); placed.add(entry);
        boolean board = id.endsWith("_sandwich_board"), seat = id.startsWith(DECO);
        String module = seat ? "deco" : "tavern";
        String simple = board ? "SandwichBoardController" : seat ? "WoodenSeatController" : "MolotovController";
        FurnitureController controller = controller(furniture, module, simple);
        controller.onLoad();
        String key = board ? BOARD_SAVE : seat ? SEAT_SAVE : STORAGE_SAVE;
        CompoundTag empty = save(controller);
        check(empty.get(key) instanceof CompoundTag, id + ": native save envelope absent");
        entry = new Placed(furniture, controller, key, empty.deepClone()); placed.set(placed.size() - 1, entry);
        CompoundTag seeded = empty.deepClone(), payload = seeded.getCompound(key).getCompound("payload");
        int slots = 0;
        if (board) {
            payload.putString("text", "CE smoke <literal> 中文\nSecond line"); payload.putString("color", "light_blue");
            payload.putString("alignment", "RIGHT"); payload.putBoolean("waxed", true); payload.putBoolean("glowing", true);
        } else if (id.startsWith(DECO + "chair/")) payload.putString("carpet", "blue");
        else if (!seat) {
            slots = switch (id) {
                case TAVERN + "molotov" -> 0;
                case TAVERN + "holder" -> 1;
                case TAVERN + "tilted_rack" -> 3;
                case TAVERN + "circular_rack" -> 6;
                case TAVERN + "cellar_cabinet" -> 9;
                default -> throw new IllegalStateException("Unexpected storage fixture " + id);
            };
            check(payload.getInt("slots") == slots, id + ": expected " + slots + " native storage slots");
            for (int slot = 0; slot < slots; slot++) {
                ItemStack bottle = build(TAVERN + "molotov", false);
                final int number = slot;
                bottle.editMeta(meta -> meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "series_smoke"),
                        PersistentDataType.STRING, runId + "/" + index + "/" + number));
                payload.putByteArray("slot_" + slot, bottle.serializeAsBytes());
            }
        }
        controller.loadCustomData(seeded); controller.onLoad();
        assertUnblocked(controller, board ? "blocked" : seat ? "blocksRemoval" : "blocked");
        List<String> elements = new ArrayList<>();
        controller.gatherElements(element -> { elements.add(element.getClass().getName()); element.activate(); });
        if (board) check(elements.stream().anyMatch(name -> name.endsWith("SandwichBoardDisplay")), id + ": CE board display not activated");
        CompoundTag saved = save(controller);
        check(saved.getCompound(key).getCompound("payload").equals(payload), id + ": live controller rejected/changed seeded state");
        byte[] encoded = FurniturePersistentData.of(saved).toBytes();
        CompoundTag decoded = FurniturePersistentData.fromBytes(encoded).copyTag();
        check(decoded.equals(saved), id + ": native NBT binary round-trip differs");
        controller.loadCustomData(decoded); controller.onLoad();
        check(save(controller).equals(saved), id + ": controller restore differs after NBT binary round-trip");
        assertUnblocked(controller, board ? "blocked" : seat ? "blocksRemoval" : "blocked");
        if (slots != 0) verifyStorageDisplays(at, saved.getCompound(key).getCompound("payload"), slots);
        roundTrips++;
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("id", id); evidence.put("uuid", furniture.uuid().toString()); evidence.put("controller", controller.getClass().getName());
        evidence.put("variant", furniture.currentVariant().name()); evidence.put("bytes", encoded.length);
        evidence.put("filledStorageSlots", slots); evidence.put("activatedElements", elements); evidence.put("state", saved.toString());
        samples.add(evidence);
    }

    private void verifyStorageDisplays(Location at, CompoundTag payload, int slots) {
        List<ItemDisplay> displays = Arrays.stream(world.getChunkAt(CHUNK_X, CHUNK_Z).getEntities())
                .filter(ItemDisplay.class::isInstance).map(ItemDisplay.class::cast)
                // CE's own furniture metadata is also an ItemDisplay at this exact position.
                // Only seeded bottle displays carry this run's item marker; all 19 remain mandatory.
                .filter(this::markedStorageDisplay)
                .filter(display -> !originalEntities.contains(display.getUniqueId()) && display.getLocation().distanceSquared(at) < .1).toList();
        for (ItemDisplay display : displays) createdEntities.add(display.getUniqueId());
        check(displays.size() == slots, "Expected " + slots + " real storage displays at " + at + "; got " + displays.size());
        for (int i = 0; i < slots; i++) {
            ItemStack expected = ItemStack.deserializeBytes(payload.getByteArray("slot_" + i));
            check(displays.stream().anyMatch(display -> expected.equals(display.getItemStack())), "Storage display lost slot " + i + " components");
        }
        for (ItemDisplay display : displays) {
            check(!display.isPersistent(), "Fixture storage display is persistent");
        }
        liveStorageDisplays += displays.size();
    }

    private boolean markedStorageDisplay(ItemDisplay display) {
        var meta = display.getItemStack().getItemMeta();
        String value = meta == null ? null : meta.getPersistentDataContainer().get(
                new NamespacedKey(plugin, "series_smoke"), PersistentDataType.STRING);
        return value != null && value.startsWith(runId + "/");
    }

    private void prepare(Location at) {
        for (int dy = -1; dy <= 3; dy++) {
            Location target = at.clone().add(0, dy, 0);
            check(Bukkit.isOwnedByCurrentRegion(target) && target.getChunk().isLoaded(), "Fixture left its owned loaded region");
            Block block = target.getBlock();
            check(CraftEngineBlocks.getCustomBlockState(block) == null, "Fixture position contains an existing custom block: " + target);
            changedBlocks.add(new BlockSnapshot(block, block.getBlockData().clone()));
            block.setType(dy == -1 ? Material.STONE : Material.AIR, false);
        }
    }

    private Location at(int index) {
        Location at = new Location(world, CHUNK_X * 16 + 2.5 + index % 3 * 4, Y, CHUNK_Z * 16 + 2.5 + index / 3 * 4);
        check(at.getBlockX() >> 4 == CHUNK_X && at.getBlockZ() >> 4 == CHUNK_Z, "Fixture exceeded its single scheduled chunk");
        at.setYaw(index % 4 * 90f);
        return at;
    }

    private ItemStack build(String id) { return build(id, true); }

    private ItemStack build(String id, boolean countDefinitionItem) {
        var item = BukkitItemManager.instance().getBuildableItem(Key.of(id)).orElseThrow(() -> new IllegalStateException("Missing item " + id))
                .buildItem((net.momirealms.craftengine.core.entity.player.Player) null);
        ItemStack stack = ItemStackUtils.getBukkitStack(item.minecraftItem());
        check(!stack.isEmpty() && stack.equals(ItemStack.deserializeBytes(stack.serializeAsBytes())), id + ": native item serialization differs");
        if (countDefinitionItem) checkedItems++;
        return stack.clone();
    }

    private FurnitureController controller(Furniture furniture, String module, String simple) throws ClassNotFoundException {
        String pluginName = "Kaleidoscope" + Character.toUpperCase(module.charAt(0)) + module.substring(1) + "Plugin";
        Plugin owner = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin(pluginName), "Missing " + pluginName);
        check(owner.isEnabled(), pluginName + " is disabled");
        Class<? extends FurnitureController> type = owner.getClass().getClassLoader()
                .loadClass("net.kaleidoscope." + module + "." + simple).asSubclass(FurnitureController.class);
        return Objects.requireNonNull(furniture.controller.get(type, 0), "Native controller missing for " + furniture.id());
    }

    private static CompoundTag save(FurnitureController controller) { CompoundTag root = new CompoundTag(); controller.saveCustomData(root); return root; }
    private static void assertUnblocked(FurnitureController controller, String name) throws ReflectiveOperationException {
        Method method;
        try { method = controller.getClass().getMethod(name); }
        catch (NoSuchMethodException missingPublic) { method = controller.getClass().getDeclaredMethod(name); }
        method.setAccessible(true);
        check(Boolean.FALSE.equals(method.invoke(controller)), controller.getClass().getSimpleName() + ": state is blocked/busy");
    }

    private void cleanup() {
        try { discoverPartialFixtures(); }
        catch (Exception | LinkageError failure) { failed("Discover partial fixture cleanup", failure); }
        for (Placed entry : placed) {
            try {
                if (entry.controller != null && entry.empty != null) {
                    entry.controller.loadCustomData(entry.empty.deepClone()); entry.controller.onLoad();
                    check(save(entry.controller).equals(entry.empty), "Cannot reset fixture state before removal: " + entry.furniture.id());
                }
                if (entry.furniture.isValid()) check(CraftEngineFurniture.remove(entry.furniture,
                        (net.momirealms.craftengine.core.entity.player.Player) null, false, false), "Native fixture removal failed: " + entry.furniture.id());
                check(!entry.furniture.isValid(), "Fixture furniture remains valid: " + entry.furniture.id());
                removedFurniture++;
            } catch (Exception | LinkageError failure) { failed("Cleanup " + entry.furniture.id(), failure); }
        }
        for (UUID uuid : createdEntities) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null && entity.isValid()) {
                failures.add("Fixture storage display survived native cleanup: " + uuid);
                if (Bukkit.isOwnedByCurrentRegion(entity)) entity.remove();
            } else cleanedEntities++;
        }
        for (int i = changedBlocks.size() - 1; i >= 0; i--) {
            BlockSnapshot before = changedBlocks.get(i);
            try {
                check(Bukkit.isOwnedByCurrentRegion(before.block.getLocation()), "Cannot restore fixture block outside its region");
                before.block.setBlockData(before.data, false);
            } catch (Exception | LinkageError failure) { failed("Restore fixture block " + before.block.getLocation(), failure); }
        }
        if (ticket) { world.removePluginChunkTicket(CHUNK_X, CHUNK_Z, plugin); ticket = false; }
    }

    /** A controller activation may throw after CE registered its metadata, before place() returned. */
    private void discoverPartialFixtures() {
        for (Entity entity : world.getChunkAt(CHUNK_X, CHUNK_Z).getEntities()) {
            if (originalEntities.contains(entity.getUniqueId())) continue;
            if (entity instanceof ItemDisplay display && markedStorageDisplay(display)) createdEntities.add(entity.getUniqueId());
            Furniture candidate = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (candidate == null || placed.stream().anyMatch(entry -> entry.furniture.uuid().equals(candidate.uuid()))) continue;
            for (int i = 0; i < SAMPLES.size(); i++) {
                if (!SAMPLES.get(i).equals(candidate.id().asString())) continue;
                Location expected = at(i);
                var actual = candidate.position();
                if (Math.abs(actual.x - expected.getX()) < 1e-5 && Math.abs(actual.y - expected.getY()) < 1e-5
                        && Math.abs(actual.z - expected.getZ()) < 1e-5) {
                    placed.add(new Placed(candidate, null, null, null));
                    break;
                }
            }
        }
    }

    private void failed(String scope, Throwable failure) {
        failures.add(scope + ": " + failure);
        plugin.getLogger().log(Level.SEVERE, "Series gameplay smoke: " + scope, failure);
    }

    private void finish() {
        var report = new LinkedHashMap<String, Object>();
        report.put("runId", runId); report.put("server", Bukkit.getVersion());
        Plugin engine = Bukkit.getPluginManager().getPlugin("CraftEngine");
        report.put("craftEngine", engine == null ? "MISSING" : engine.getPluginMeta().getVersion());
        report.put("world", world == null ? "MISSING" : world.getName()); report.put("chunk", List.of(CHUNK_X, CHUNK_Z));
        report.put("checkedDefinitions", checkedDefinitions); report.put("expectedDefinitions", 43); report.put("checkedVariants", checkedVariants);
        report.put("checkedItems", checkedItems); report.put("placedFurniture", placed.size()); report.put("expectedSamples", SAMPLES.size());
        report.put("binaryStateRoundTrips", roundTrips); report.put("liveStorageDisplays", liveStorageDisplays);
        report.put("removedFurniture", removedFurniture); report.put("cleanedStorageDisplays", cleanedEntities);
        report.put("definitions", definitions); report.put("samples", samples); report.put("failures", failures);
        report.put("passed", failures.isEmpty() && checkedDefinitions == 43 && checkedVariants == 431 && checkedItems == 235
                && roundTrips == SAMPLES.size() && removedFurniture == SAMPLES.size()
                && liveStorageDisplays == 19 && cleanedEntities == 19);
        report.put("placementMethod", "REAL_CE_SERVER_API; real controllers and native display factories activated on owning region");
        report.put("persistenceMethod", "saveCustomData -> FurniturePersistentData bytes -> loadCustomData; live in-process round-trip only");
        report.put("playerFlow", "NOT_TESTED; no real or fake player, no editor, thrown projectile, redstone or seating interaction");
        report.put("clientVisual", "NOT_TESTED; no client resource-pack, visible text, pose or physical collision parity acceptance");
        report.put("serverRestart", "NOT_TESTED");
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            Files.writeString(plugin.getDataFolder().toPath().resolve("series-gameplay-smoke.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
        } catch (Exception failure) { plugin.getLogger().log(Level.SEVERE, "Cannot write series gameplay smoke report", failure); }
        plugin.getLogger().log(failures.isEmpty() ? Level.INFO : Level.SEVERE, "SERIES_GAMEPLAY_SMOKE definitions=" + checkedDefinitions
                + " placed=" + placed.size() + " roundTrips=" + roundTrips + " removed=" + removedFurniture + " failures=" + failures.size());
    }

    private static void near(double actual, double expected, String scope) { check(Math.abs(actual - expected) < 1e-5, scope + ": " + actual + " != " + expected); }
    private static void check(boolean condition, String failure) { if (!condition) throw new IllegalStateException(failure); }
}
