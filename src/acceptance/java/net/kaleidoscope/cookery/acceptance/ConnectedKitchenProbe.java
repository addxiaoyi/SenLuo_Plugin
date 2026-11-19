package net.kaleidoscope.cookery.acceptance;

import com.google.gson.Gson;
import net.kaleidoscope.cookery.block.entity.SteamerController;
import net.kaleidoscope.cookery.block.entity.SteamerInventory;
import net.kaleidoscope.cookery.item.SteamerItemContents;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureHitEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.EntityHitResult;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.*;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/** Console-only arrangement and server-side observations. All player actions arrive over the network. */
final class ConnectedKitchenProbe implements Listener {
    private static final int X = 680, Y = 200, Z = 8;
    private static final Key BOARD = Key.of("kaleidoscope_deco:kaleidoscope_bar/blackboard/0");
    private static final Key SOFA = Key.of("kaleidoscope_deco:kaleidoscope_bar/sofa");
    private static final Key DOLL = Key.of("kaleidoscope_doll:doll_0");
    private static final Key TUB = Key.of("kaleidoscope_tavern:pressing_tub");
    private static final Map<String, List<Double>> PERSISTENT_POSITIONS = Map.of(
            "sofa", List.of(X - 1.5, (double) Y, Z + 1.5),
            "doll", List.of(X + 2.5, (double) Y, Z + 1.5),
            "tub", List.of(X + .5, (double) Y, Z - 1.5));
    private static final List<String> GAMEPLAY_NAMES = List.of("chair", "board", "molotov");
    private static final Map<String, Key> GAMEPLAY_KEYS = Map.of(
            "chair", Key.of("kaleidoscope_deco:chair/oak"),
            "board", Key.of("kaleidoscope_tavern:base_sandwich_board"),
            "molotov", Key.of("kaleidoscope_tavern:molotov"));
    private static final Map<String, List<Double>> GAMEPLAY_POSITIONS = Map.of(
            "chair", PERSISTENT_POSITIONS.get("sofa"),
            "board", PERSISTENT_POSITIONS.get("doll"),
            "molotov", PERSISTENT_POSITIONS.get("tub"));
    private static final String FIRST = "SenLuoProbeA", SECOND = "SenLuoProbeB";
    private static final Gson JSON = new Gson();
    private final JavaPlugin plugin;
    private final World world;
    private final List<Map<String, Object>> hits = new ArrayList<>(), drops = new ArrayList<>();
    private final Map<String, Long> furnitureInteractions = new HashMap<>();
    private record GameCombatReceipt(Map<String, Object> values, Cancellable event) {}
    private final List<GameCombatReceipt> gameCombatReceipts = new ArrayList<>();
    private long gameCombatSequence;
    private BukkitFurniture scarecrow;
    private BukkitFurniture board;
    private BukkitFurniture sofa;
    private BukkitFurniture doll;
    private int sofaStage, canceledTrades, changedSlots;
    private boolean nestedSofaRejected;
    private boolean persistenceTracking;
    private boolean gameplayTracking, gameplaySeeded, gameplayPeerStocked;
    private UUID gameCancelPlayer;
    private int gameCanceledTrades;
    private boolean observeDrops, cancelSmith;
    private int canceled, boardDenied, flowerDenied;

    private ConnectedKitchenProbe(JavaPlugin plugin, World world) { this.plugin = plugin; this.world = world; }

    static void enable(JavaPlugin plugin) {
        if (!"127.0.0.1".equals(Bukkit.getIp())) throw new IllegalStateException("Expected loopback server");
        var world = Objects.requireNonNull(Bukkit.getWorld("acceptance-world"));
        var probe = new ConnectedKitchenProbe(plugin, world);
        Bukkit.getPluginManager().registerEvents(probe, plugin);
        probe.observeDecoTrades();
        probe.observeGameplayTrades();
        var cmd = Objects.requireNonNull(plugin.getCommand("senluoprobe"));
        cmd.setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof ConsoleCommandSender) || args.length != 3 || !args[0].matches("[0-9]{1,8}")
                    || !Set.of(FIRST, SECOND).contains(args[2])) return true;
            probe.dispatch(args[0], args[1], args[2]);
            return true;
        });
    }

    private void reply(String token, Object result) { plugin.getLogger().info("CONNECTED_REPLY " + token + " " + JSON.toJson(result)); }

    @SuppressWarnings("unchecked")
    private void observeDecoTrades() {
        var deco = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("KaleidoscopeDecoPlugin"));
        try {
            Class<? extends Event> trade = (Class<? extends Event>) deco.getClass().getClassLoader()
                    .loadClass("net.kaleidoscope.deco.internal.InventoryTradeEvent");
            Bukkit.getPluginManager().registerEvent(trade, this, EventPriority.HIGHEST, (listener, event) -> {
                sofa = liveReference(sofa);
                if (sofa == null || !sofa.isValid()) return;
                try {
                    Player actor = (Player) trade.getMethod("player").invoke(event);
                    if (!FIRST.equals(actor.getName())) return;
                    switch (sofaStage++) {
                        case 0 -> { ((Cancellable) event).setCancelled(true); canceledTrades++; }
                        case 1 -> { actor.getInventory().setHeldItemSlot(1); changedSlots++; }
                        case 2 -> {
                            Player other = Objects.requireNonNull(Bukkit.getPlayerExact(SECOND));
                            if (!Bukkit.isOwnedByCurrentRegion(other)) throw new IllegalStateException("Nested fixture player moved regions");
                            var at = sofa.location().add(0, .45, 0);
                            var hit = new EntityHitResult(Direction.UP, new Vec3d(at.getX(), at.getY(), at.getZ()));
                            var context = new InteractEntityContext(BukkitAdaptor.adapt(other), InteractionHand.MAIN_HAND, hit);
                            nestedSofaRejected = !sofa.controller.useOnFurniture(sofa.hitboxes().getFirst(), context).success();
                        }
                        default -> { }
                    }
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            }, plugin, true);
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Deco trade boundary unavailable", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private void observeGameplayTrades() {
        for (String module : List.of("deco", "tavern")) {
            String pluginName = "Kaleidoscope" + Character.toUpperCase(module.charAt(0)) + module.substring(1) + "Plugin";
            var owner = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin(pluginName));
            try {
                Class<? extends Event> trade = (Class<? extends Event>) owner.getClass().getClassLoader()
                        .loadClass("net.kaleidoscope." + module + ".internal.InventoryTradeEvent");
                Bukkit.getPluginManager().registerEvent(trade, this, EventPriority.HIGHEST, (listener, event) -> {
                    if (!gameplayTracking || gameCancelPlayer == null) return;
                    try {
                        Player actor = (Player) trade.getMethod("player").invoke(event);
                        if (!gameCancelPlayer.equals(actor.getUniqueId()) || actor.getWorld() != world
                                || !Set.of(FIRST, SECOND).contains(actor.getName()) || !Bukkit.isOwnedByCurrentRegion(actor)
                                || actor.getLocation().distanceSquared(new Location(world, X, Y, Z)) > 64) return;
                        gameCancelPlayer = null;
                        ((Cancellable) event).setCancelled(true);
                        gameCanceledTrades++;
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException("Gameplay trade observation unavailable", failure);
                    }
                }, plugin, true);
            } catch (ClassNotFoundException failure) {
                throw new IllegalStateException("Gameplay trade boundary unavailable: " + module, failure);
            }
        }
    }

    private void dispatch(String token, String action, String name) {
        var player = Bukkit.getPlayerExact(name);
        if (player == null) { reply(token, Map.of("error", "Disconnected fixture player")); return; }
        if (action.equals("arrive")) {
            world.getChunkAtAsync(X >> 4, Z >> 4).thenRun(() -> Bukkit.getRegionScheduler().execute(plugin, world, X >> 4, Z >> 4, () -> {
                for (int x = X-4; x <= X+4; x++) for (int z = Z-3; z <= Z+3; z++) world.getBlockAt(x,Y-1,z).setType(Material.STONE);
                world.getBlockAt(X-1,Y,Z-1).setType(Material.SMITHING_TABLE);
                world.getBlockAt(X+1,Y,Z-1).setType(Material.CRAFTING_TABLE);
                var destination = new Location(world, X+(FIRST.equals(name) ? .5 : 1.5),Y,Z+1.5,180,10);
                player.getScheduler().execute(plugin, () -> player.teleportAsync(destination).whenComplete((ok, error) ->
                        reply(token, error == null ? Map.of("arrived", ok) : Map.of("error", error.toString()))),
                        () -> reply(token, Map.of("error", "retired")), 1);
            })).exceptionally(error -> { reply(token, Map.of("error", error.toString())); return null; });
            return;
        }
        player.getScheduler().run(plugin, task -> {
            try {
                if (!Bukkit.isOwnedByCurrentRegion(world, X >> 4, Z >> 4) || player.getWorld() != world || player.isOp()
                        || player.getGameMode() != GameMode.SURVIVAL || player.getLocation().distanceSquared(new Location(world,X,Y,Z)) > 64)
                    throw new IllegalStateException("Expected ordinary survival player inside the fixture region");
                refreshReferences();
                switch (action) {
                    case "supply" -> {
                        // Reset the fixture after an interrupted run. The rest of the world is untouched.
                        observeDrops = false;
                        world.getBlockAt(X,Y,Z).setType(Material.AIR, false);
                        var center = new Location(world, X + .5, Y + .5, Z + .5);
                        for (var drop : world.getNearbyEntitiesByType(org.bukkit.entity.Item.class, center, 2)) drop.remove();
                        drops.clear();
                        observeDrops = true;
                        player.closeInventory(); var inv = player.getInventory(); inv.clear();
                        inv.setItem(0, new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE,3));
                        inv.setItem(1, SmithingSmoke.sourceKnife()); inv.setItem(2, SmithingSmoke.sourceKnife());
                        inv.setItem(3, new ItemStack(Material.NETHERITE_INGOT,5));
                        inv.setItem(4, custom("steamer",1)); inv.setItem(5, custom("raw_dough",4));
                        cancelSmith = true; canceled = 0;
                    }
                    case "scarecrow" -> {
                        if (scarecrow != null && scarecrow.isValid()) scarecrow.destroy();
                        hits.clear(); drops.clear();
                        scarecrow = Objects.requireNonNull(CraftEngineFurniture.place(new Location(world,X+.5,Y,Z-.5),
                                Key.of("kaleidoscopecookery:scarecrow"), "ground", false));
                    }
                    case "tulip" -> {
                        var center = new Location(world, X + 2.5, Y, Z + 1.5);
                        var inv = player.getInventory();
                        inv.clear();
                        if (FIRST.equals(name)) {
                            var ray = center.clone().add(0, .45, -.8).setDirection(new org.bukkit.util.Vector(0, 0, 1));
                            board = CraftEngineFurniture.rayTrace(ray, 1.6);
                            if (board == null) board = Objects.requireNonNull(
                                    CraftEngineFurniture.place(center, BOARD, "ground", false), "Could not place test board at " + center);
                            if (!board.id().equals(BOARD)) throw new IllegalStateException("Unexpected fixture furniture " + board.id());
                            cancelSmith = false;
                            boardDenied = flowerDenied = 0;
                            drops.clear();
                            for (var item : world.getNearbyEntitiesByType(org.bukkit.entity.Item.class, center, 2))
                                if (item.getItemStack().getType().name().endsWith("_TULIP")) item.remove();
                        }
                        for (int i = 0; i < 36; i++) inv.setItem(i, new ItemStack(Material.COBBLESTONE, 64));
                        inv.setItem(0, new ItemStack(Material.RED_TULIP, 2));
                        inv.setItem(1, new ItemStack(Material.SHEARS));
                    }
                    case "clearboard" -> {
                        if (board != null && board.isValid()) board.destroy();
                        board = null;
                    }
                    case "sofa" -> {
                        if (FIRST.equals(name)) {
                            var center = new Location(world, X + 2.5, Y, Z + 1.5);
                            var ray = center.clone().add(0, .45, -.8).setDirection(new org.bukkit.util.Vector(0, 0, 1));
                            var old = CraftEngineFurniture.rayTrace(ray, 1.6);
                            if (old != null) {
                                if (!old.id().equals(SOFA)) throw new IllegalStateException("Unexpected fixture furniture " + old.id());
                                old.destroy();
                            }
                            sofa = Objects.requireNonNull(CraftEngineFurniture.place(center, SOFA, "ground", false));
                            sofaStage = canceledTrades = changedSlots = 0;
                            nestedSofaRejected = false;
                            drops.clear();
                        }
                        var inv = player.getInventory(); inv.clear();
                        inv.setItem(0, new ItemStack(FIRST.equals(name) ? Material.RED_DYE : Material.BLUE_DYE, 3));
                        inv.setItem(1, new ItemStack(Material.BLUE_DYE, 3));
                        inv.setItem(2, ce("kaleidoscope_deco:state_tool", 1));
                        inv.setItem(3, new ItemStack(Material.WATER_BUCKET));
                    }
                    case "clearsofa" -> {
                        if (sofa != null && sofa.isValid()) sofa.destroy();
                        sofa = null;
                    }
                    case "inspectsofa" -> {
                        var ray = new Location(world, X + 2.5, Y + .45, Z + .7)
                                .setDirection(new org.bukkit.util.Vector(0, 0, 1));
                        sofa = CraftEngineFurniture.rayTrace(ray, 1.6);
                        if (sofa != null && !sofa.id().equals(SOFA)) throw new IllegalStateException("Unexpected sofa fixture");
                    }
                    case "doll" -> {
                        var center = new Location(world, X + 2.5, Y, Z + 1.5);
                        var ray = center.clone().add(0, .45, -.8).setDirection(new org.bukkit.util.Vector(0, 0, 1));
                        var old = CraftEngineFurniture.rayTrace(ray, 1.6);
                        if (old != null) {
                            if (!old.id().equals(DOLL)) throw new IllegalStateException("Unexpected fixture furniture " + old.id());
                            old.destroy();
                        }
                        doll = Objects.requireNonNull(CraftEngineFurniture.place(center, DOLL, "ground", false));
                        player.getInventory().clear();
                        player.getInventory().setItem(0, ce("kaleidoscope_doll:tweaks_tool", 1));
                        drops.clear();
                    }
                    case "cleardoll" -> {
                        if (doll != null && doll.isValid()) doll.destroy();
                        doll = null;
                    }
                    case "inspectdoll" -> {
                        var ray = new Location(world, X + 2.5, Y + .45, Z + .7)
                                .setDirection(new org.bukkit.util.Vector(0, 0, 1));
                        doll = CraftEngineFurniture.rayTrace(ray, 1.6);
                        if (doll != null && !doll.id().equals(DOLL)) throw new IllegalStateException("Unexpected doll fixture");
                    }
                    case "gameclear" -> {
                        if (!FIRST.equals(name)) throw new IllegalArgumentException("Only the gameplay seed owner clears fixtures");
                        clearGameplay();
                    }
                    case "gameseed" -> {
                        if (!FIRST.equals(name)) throw new IllegalArgumentException("Only the gameplay seed owner arranges fixtures");
                        prepareGameplay();
                        player.closeInventory();
                        var inv = player.getInventory(); inv.clear();
                        inv.setItem(0, new ItemStack(Material.RED_CARPET, 3));
                        inv.setItem(1, new ItemStack(Material.SHEARS));
                        inv.setItem(2, new ItemStack(Material.RED_DYE, 3));
                        inv.setItem(3, new ItemStack(Material.GLOW_INK_SAC, 2));
                        inv.setItem(4, new ItemStack(Material.HONEYCOMB, 2));
                        inv.setItem(5, new ItemStack(Material.POPPY, 2));
                        inv.setItem(8, new ItemStack(Material.BLUE_CARPET, 2));
                        inv.setHeldItemSlot(0);
                    }
                    case "gamestock" -> {
                        if (!SECOND.equals(name) || !gameplaySeeded || gameplayPeerStocked)
                            throw new IllegalArgumentException("Expected the second gameplay seed player, once before restart");
                        player.closeInventory();
                        player.getInventory().clear();
                        player.getInventory().setHeldItemSlot(0);
                        gameplayPeerStocked = true;
                    }
                    case "gamecancel" -> {
                        if (!gameplayTracking || gameCancelPlayer != null)
                            throw new IllegalStateException("Gameplay trade cancellation is unavailable or already armed");
                        gameCancelPlayer = player.getUniqueId();
                    }
                    case "gameinspect" -> {
                        // Only observations are enabled here. Restart verification never prepares or repairs fixtures.
                        gameplayTracking = true;
                        persistenceTracking = false;
                        observeDrops = true;
                    }
                    case "gamecombat" -> {
                        if (!gameplayTracking) throw new IllegalStateException("Gameplay observations are not enabled");
                    }
                    case "persistclear" -> {
                        if (!FIRST.equals(name)) throw new IllegalArgumentException("Only the seed owner clears fixtures");
                        clearPersistence();
                        persistenceTracking = false;
                    }
                    case "persistseed" -> {
                        if (!FIRST.equals(name)) throw new IllegalArgumentException("Only the seed owner arranges furniture");
                        preparePersistence();
                        player.closeInventory();
                        var inv = player.getInventory(); inv.clear();
                        inv.setItem(0, new ItemStack(Material.RED_DYE, 3));
                        inv.setItem(1, ce("kaleidoscope_deco:state_tool", 1));
                        inv.setItem(2, ce("kaleidoscope_doll:tweaks_tool", 1));
                    }
                    case "persiststock" -> {
                        if (!SECOND.equals(name) || !persistenceTracking)
                            throw new IllegalArgumentException("Expected the second seed player");
                        player.closeInventory();
                        player.getInventory().clear();
                        var berries = new ItemStack(Material.GLOW_BERRIES);
                        var meta = berries.getItemMeta();
                        meta.getPersistentDataContainer().set(new NamespacedKey("senluo_acceptance", "restart_marker"),
                                PersistentDataType.STRING, "three-phase-pressing-tub-v1");
                        berries.setItemMeta(meta);
                        player.getInventory().setItem(0, berries);
                    }
                    case "persistinspect" -> {
                        // Reconnect/restart phases only locate existing entities; never arrange or repair furniture here.
                        persistenceTracking = true;
                        observeDrops = true;
                    }
                    case "inspect" -> { }
                    default -> throw new IllegalArgumentException(action);
                }
                reply(token, action.equals("gamecombat") ? gameCombatSnapshot(player) : snapshot(player));
            } catch (Exception | LinkageError error) {
                reply(token, Map.of("error", error.toString(), "stack",
                        Arrays.stream(error.getStackTrace()).map(StackTraceElement::toString).toList()));
            }
        }, () -> reply(token, Map.of("error", "retired")));
    }

    private static ItemStack custom(String name, int count) {
        return ce("kaleidoscopecookery:" + name, count);
    }

    private static BukkitFurniture liveReference(BukkitFurniture cached) {
        if (cached == null) return null;
        // isValid()/uuid() dereference CE's weak NMS handle. Never call them on an unregistered wrapper.
        var current = net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurnitureManager.instance()
                .loadedFurnitureByMetaEntityId(cached.entityId());
        return current == cached ? current : null;
    }

    private void refreshReferences() {
        scarecrow = liveReference(scarecrow);
        board = liveReference(board);
        sofa = liveReference(sofa);
        doll = liveReference(doll);
    }

    private static ItemStack ce(String id, int count) {
        var item = InventoryUtils.bukkitCopy(InventoryUtils.createOrEmpty(Key.of(id)));
        item.setAmount(count); return item;
    }

    private static List<String> contents(List<SteamerInventory.Slot> slots) {
        return slots.stream().map(slot -> slot == null ? "empty" : slot.item().id() + "*" + slot.item().count()).toList();
    }

    private static Map<String,Object> describe(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return Map.of();
        var item = InventoryUtils.wrap(stack); var data = new LinkedHashMap<String,Object>();
        data.put("id", item.id().toString()); data.put("count", stack.getAmount());
        var pdc = persistentData(stack);
        if (!pdc.isEmpty()) data.put("pdc", pdc);
        if (item.id().equals(SteamerItemContents.ID)) data.put("slots", contents(SteamerItemContents.read(item)));
        if (item.id().toString().equals("kaleidoscopecookery:netherite_kitchen_knife")) SmithingSmoke.assertProduct(stack);
        return data;
    }

    private static Map<String, Object> persistentData(ItemStack stack) {
        var result = new TreeMap<String, Object>();
        if (!stack.hasItemMeta()) return result;
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        for (var key : pdc.getKeys()) {
            Object value;
            if (pdc.has(key, PersistentDataType.BYTE_ARRAY)) value = Map.of("bytes", Base64.getEncoder().encodeToString(pdc.get(key, PersistentDataType.BYTE_ARRAY)));
            else if (pdc.has(key, PersistentDataType.STRING)) value = Map.of("string", pdc.get(key, PersistentDataType.STRING));
            else if (pdc.has(key, PersistentDataType.INTEGER)) value = Map.of("int", pdc.get(key, PersistentDataType.INTEGER));
            else if (pdc.has(key, PersistentDataType.LONG)) value = Map.of("long", pdc.get(key, PersistentDataType.LONG));
            else if (pdc.has(key, PersistentDataType.DOUBLE)) value = Map.of("double", pdc.get(key, PersistentDataType.DOUBLE));
            else if (pdc.has(key, PersistentDataType.FLOAT)) value = Map.of("float", pdc.get(key, PersistentDataType.FLOAT));
            else if (pdc.has(key, PersistentDataType.BYTE)) value = Map.of("byte", pdc.get(key, PersistentDataType.BYTE));
            else if (pdc.has(key, PersistentDataType.SHORT)) value = Map.of("short", pdc.get(key, PersistentDataType.SHORT));
            else if (pdc.has(key, PersistentDataType.INTEGER_ARRAY)) value = Map.of("ints", pdc.get(key, PersistentDataType.INTEGER_ARRAY));
            else if (pdc.has(key, PersistentDataType.LONG_ARRAY)) value = Map.of("longs", pdc.get(key, PersistentDataType.LONG_ARRAY));
            else throw new IllegalStateException("Unsupported fixture PDC type: " + key);
            result.put(key.toString(), value);
        }
        return result;
    }

    private Location persistentPosition(String name) {
        var at = Objects.requireNonNull(PERSISTENT_POSITIONS.get(name));
        return new Location(world, at.get(0), at.get(1), at.get(2));
    }

    private Key persistentKey(String name) {
        return switch (name) { case "sofa" -> SOFA; case "doll" -> DOLL; case "tub" -> TUB;
            default -> throw new IllegalArgumentException(name); };
    }

    private BukkitFurniture findPersistent(String name) {
        var center = persistentPosition(name);
        BukkitFurniture found = null;
        // Inspect metadata entities, rather than replacing furniture when a controller or display is missing.
        for (var entity : world.getChunkAt(center).getEntities()) {
            var candidate = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (candidate == null || candidate.location().distanceSquared(center) > .01) continue;
            if (!candidate.id().equals(persistentKey(name))) throw new IllegalStateException("Unexpected persistent fixture: " + candidate.id());
            if (found != null && !found.uuid().equals(candidate.uuid())) throw new IllegalStateException("Duplicated persistent fixture: " + name);
            found = candidate;
        }
        return found;
    }

    private void clearPersistence() {
        var previous = new LinkedHashMap<UUID, BukkitFurniture>();
        // Validate every occupied test position before removing anything. Unknown furniture is never reset.
        for (String name : List.of("sofa", "doll", "tub")) {
            var center = persistentPosition(name);
            for (var entity : world.getChunkAt(center).getEntities()) {
                var candidate = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
                if (candidate == null || candidate.location().distanceSquared(center) > .01) continue;
                if (!Set.of(SOFA, DOLL, TUB, BOARD).contains(candidate.id()))
                    throw new IllegalStateException("Refusing to clear unknown persistent fixture: " + candidate.id());
                previous.put(candidate.uuid(), candidate);
            }
        }
        observeDrops = false;
        for (var furniture : previous.values()) furniture.destroy();
        var center = new Location(world, X + .5, Y, Z + .5);
        for (var item : world.getNearbyEntitiesByType(org.bukkit.entity.Item.class, center, 5)) item.remove();
        drops.clear();
    }

    private void preparePersistence() {
        clearPersistence();
        for (String name : List.of("sofa", "doll", "tub"))
            Objects.requireNonNull(CraftEngineFurniture.place(persistentPosition(name), persistentKey(name), "ground", false), name);
        persistenceTracking = observeDrops = true;
        drops.clear();
    }

    private static Map<String,Object> itemEvidence(ItemStack item) {
        if (item == null || item.isEmpty()) return Map.of();
        var evidence = new LinkedHashMap<>(describe(item));
        evidence.put("pdc", persistentData(item));
        evidence.put("serialized", Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        return evidence;
    }

    private static Map<String,Object> gameplayItemEvidence(ItemStack item) {
        if (item == null || item.isEmpty()) return Map.of();
        var evidence = new LinkedHashMap<>(itemEvidence(item));
        evidence.put("unitSerialized", Base64.getEncoder().encodeToString(item.asQuantity(1).serializeAsBytes()));
        return evidence;
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> persistentFurniture(String name) {
        var result = new LinkedHashMap<String,Object>();
        result.put("position", PERSISTENT_POSITIONS.get(name));
        result.put("world", world.getName());
        var furniture = findPersistent(name);
        result.put("present", furniture != null && furniture.isValid());
        if (furniture == null || !furniture.isValid()) return result;
        var actual = furniture.location();
        result.put("position", List.of(actual.getX(), actual.getY(), actual.getZ()));
        result.put("world", Objects.requireNonNull(actual.getWorld()).getName());
        result.put("uuid", furniture.uuid().toString());
        result.put("id", furniture.id().asString());
        result.put("variant", furniture.currentVariant().name());
        result.put("hitbox", furniture.interactableEntityIds()[0]);
        var source = furniture.sourceItem();
        result.put("source", source == null || source.isEmpty() ? Map.of()
                : itemEvidence(net.momirealms.craftengine.bukkit.util.ItemStackUtils.getBukkitStack(source)));
        String module = name.equals("sofa") ? "deco" : name.equals("tub") ? "tavern" : "doll";
        String simple = name.equals("sofa") ? "DecoController" : name.equals("tub") ? "PressingController" : "DollController";
        String pluginName = "Kaleidoscope" + Character.toUpperCase(module.charAt(0)) + module.substring(1) + "Plugin";
        try {
            var owner = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin(pluginName));
            var type = (Class<? extends net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController>)
                    owner.getClass().getClassLoader().loadClass("net.kaleidoscope." + module + "." + simple);
            var controller = Objects.requireNonNull(furniture.controller.get(type, 0));
            var saved = new net.momirealms.craftengine.libraries.nbt.CompoundTag();
            controller.saveCustomData(saved);
            result.put("saved", saved.toString());
            boolean blocked = (Boolean) type.getMethod("blocked").invoke(controller);
            result.put("blocked", blocked);
            result.put("hasContents", type.getMethod("hasContents").invoke(controller));
            if (blocked) return result;
            String key = "kaleidoscope_" + module + (name.equals("sofa") ? ":state_v1" : name.equals("tub") ? ":pressing_v1" : ":pose_v1");
            var payload = saved.getCompound(key).getCompound("payload");
            if (name.equals("doll")) result.put("state", Map.of("pose", payload.getString("pose")));
            else if (name.equals("sofa")) result.put("state", Map.of("state", payload.getString("state"),
                    "color", payload.getString("color"), "mode", payload.getString("mode")));
            else {
                var contents = new LinkedHashMap<String,Object>();
                contents.put("amount", payload.getInt("amount"));
                contents.put("ingredients", payload.containsKey("ingredients") ? itemEvidence(ItemStack.deserializeBytes(payload.getByteArray("ingredients"))) : Map.of());
                result.put("state", contents);
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Persistent controller unavailable: " + name, failure); }
        return result;
    }

    private Map<String,Object> persistenceSnapshot() {
        var result = new LinkedHashMap<String,Object>();
        for (String name : List.of("sofa", "doll", "tub")) result.put(name, persistentFurniture(name));
        var center = new Location(world, X + .5, Y, Z + .5);
        result.put("looseItems", world.getNearbyEntitiesByType(org.bukkit.entity.Item.class, center, 5).stream()
                .filter(item -> Math.abs(item.getLocation().getY() - Y) < 3).map(item -> {
                    var data = new LinkedHashMap<String,Object>(itemEvidence(item.getItemStack()));
                    data.put("uuid", item.getUniqueId().toString());
                    var at = item.getLocation(); data.put("position", List.of(at.getX(), at.getY(), at.getZ()));
                    return data;
                }).toList());
        return result;
    }

    private Location gameplayPosition(String name) {
        var at = Objects.requireNonNull(GAMEPLAY_POSITIONS.get(name));
        var location = new Location(world, at.get(0), at.get(1), at.get(2));
        if (!Bukkit.isOwnedByCurrentRegion(location)
                || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4))
            throw new IllegalStateException("Gameplay fixture is outside its owning loaded region: " + name);
        return location;
    }

    private BukkitFurniture findGameplay(String name) {
        var center = gameplayPosition(name);
        BukkitFurniture found = null;
        // Metadata identity is observed afresh after restart; missing furniture is never recreated here.
        for (var entity : world.getChunkAt(center).getEntities()) {
            var candidate = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (candidate == null || candidate.location().distanceSquared(center) > .01) continue;
            if (!candidate.id().equals(GAMEPLAY_KEYS.get(name)))
                throw new IllegalStateException("Unexpected gameplay fixture at " + name + ": " + candidate.id());
            if (found != null && !found.uuid().equals(candidate.uuid()))
                throw new IllegalStateException("Duplicated gameplay fixture: " + name);
            found = candidate;
        }
        return found;
    }

    private void clearGameplay() {
        var previous = new LinkedHashMap<UUID, BukkitFurniture>();
        var allowed = new HashSet<>(GAMEPLAY_KEYS.values());
        allowed.addAll(Set.of(SOFA, DOLL, TUB, BOARD));
        // These are the same three disposable locations used by the original suite. Validate all before deleting any.
        for (String name : GAMEPLAY_NAMES) {
            var center = gameplayPosition(name);
            for (var entity : world.getChunkAt(center).getEntities()) {
                var candidate = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
                if (candidate == null || candidate.location().distanceSquared(center) > .01) continue;
                if (!allowed.contains(candidate.id()))
                    throw new IllegalStateException("Refusing to clear unknown gameplay fixture: " + candidate.id());
                previous.put(candidate.uuid(), candidate);
            }
        }
        observeDrops = false;
        for (var furniture : previous.values()) {
            if (!CraftEngineFurniture.remove(furniture,
                    (net.momirealms.craftengine.core.entity.player.Player) null, false, false))
                throw new IllegalStateException("Could not clear previous gameplay fixture: " + furniture.id());
        }
        var center = new Location(world, X + .5, Y, Z + .5);
        for (var item : world.getNearbyEntitiesByType(org.bukkit.entity.Item.class, center, 5))
            if (Math.abs(item.getLocation().getY() - Y) < 3) item.remove();
        drops.clear();
        gameCancelPlayer = null;
        gameCanceledTrades = 0;
        gameCombatReceipts.clear();
        gameCombatSequence = 0;
        gameplaySeeded = gameplayPeerStocked = false;
        gameplayTracking = observeDrops = true;
        persistenceTracking = false;
    }

    private void prepareGameplay() {
        clearGameplay();
        for (String name : GAMEPLAY_NAMES) {
            var key = GAMEPLAY_KEYS.get(name);
            ItemStack source = ce(key.asString(), 1);
            if (source.isEmpty() || !key.equals(InventoryUtils.wrap(source).id()))
                throw new IllegalStateException("Missing registered gameplay source item: " + name);
            // CE's normal item placement stores one exact held item before constructing the furniture.
            var placement = net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData.ofVariant("ground");
            placement.setItem(InventoryUtils.wrap(source).copyWithCount(1));
            var furniture = Objects.requireNonNull(CraftEngineFurniture.place(gameplayPosition(name),
                    Objects.requireNonNull(CraftEngineFurniture.byId(key)), placement, false),
                    "Could not place gameplay fixture: " + name);
            if (!furniture.isValid()) throw new IllegalStateException("Invalid gameplay fixture after placement: " + name);
            var placedSource = furniture.sourceItem();
            if (placedSource == null || placedSource.isEmpty()
                    || !source.equals(net.momirealms.craftengine.bukkit.util.ItemStackUtils.getBukkitStack(placedSource)))
                throw new IllegalStateException("Gameplay source differs from normal registered item placement: " + name);
        }
        gameplaySeeded = true;
    }

    private static boolean controllerFlag(Object controller, Class<?> type, String name) throws ReflectiveOperationException {
        java.lang.reflect.Method method;
        try { method = type.getMethod(name); }
        catch (NoSuchMethodException notPublic) { method = type.getDeclaredMethod(name); }
        method.setAccessible(true);
        Object value = method.invoke(controller);
        if (!(value instanceof Boolean flag)) throw new IllegalStateException("Not a controller boolean: " + name);
        return flag;
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> gameplayFurniture(String name) {
        var result = new LinkedHashMap<String,Object>();
        result.put("position", GAMEPLAY_POSITIONS.get(name));
        result.put("world", world.getName());
        var furniture = findGameplay(name);
        result.put("present", furniture != null && furniture.isValid());
        if (furniture == null || !furniture.isValid()) return result;
        var actual = furniture.location();
        result.put("position", List.of(actual.getX(), actual.getY(), actual.getZ()));
        result.put("world", Objects.requireNonNull(actual.getWorld()).getName());
        result.put("uuid", furniture.uuid().toString());
        result.put("id", furniture.id().asString());
        result.put("variant", furniture.currentVariant().name());
        int[] hitboxes = furniture.interactableEntityIds();
        if (hitboxes.length == 0) throw new IllegalStateException("Gameplay fixture has no live interaction hitbox: " + name);
        result.put("hitbox", hitboxes[0]);
        var source = furniture.sourceItem();
        result.put("source", source == null || source.isEmpty() ? Map.of()
                : gameplayItemEvidence(net.momirealms.craftengine.bukkit.util.ItemStackUtils.getBukkitStack(source)));
        String module = name.equals("chair") ? "deco" : "tavern";
        String simple = switch (name) {
            case "chair" -> "WoodenSeatController";
            case "board" -> "SandwichBoardController";
            case "molotov" -> "MolotovController";
            default -> throw new IllegalArgumentException(name);
        };
        String saveKey = "kaleidoscope_" + module + switch (name) {
            case "chair" -> ":wooden_seat_v1";
            case "board" -> ":sandwich_board_v1";
            case "molotov" -> ":molotov_storage_v1";
            default -> throw new IllegalArgumentException(name);
        };
        String pluginName = "Kaleidoscope" + Character.toUpperCase(module.charAt(0)) + module.substring(1) + "Plugin";
        try {
            var owner = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin(pluginName));
            var type = (Class<? extends net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController>)
                    owner.getClass().getClassLoader().loadClass("net.kaleidoscope." + module + "." + simple);
            var controller = Objects.requireNonNull(furniture.controller.get(type, 0), "Missing gameplay controller: " + name);
            var saved = new net.momirealms.craftengine.libraries.nbt.CompoundTag();
            controller.saveCustomData(saved);
            result.put("saved", saved.toString());
            boolean blocked = controllerFlag(controller, type, name.equals("chair") ? "blocksRemoval" : "blocked");
            result.put("blocked", blocked);
            result.put("state", Map.of());
            if (blocked) return result;
            if (!(saved.get(saveKey) instanceof net.momirealms.craftengine.libraries.nbt.CompoundTag envelope)
                    || !(envelope.get("payload") instanceof net.momirealms.craftengine.libraries.nbt.CompoundTag payload))
                throw new IllegalStateException("Missing gameplay save envelope: " + name);
            if (name.equals("chair")) result.put("state", Map.of("carpet", payload.getString("carpet")));
            else if (name.equals("board")) result.put("state", Map.of("style", payload.getString("style"),
                    "text", payload.getString("text"), "color", payload.getString("color"),
                    "alignment", payload.getString("alignment"), "waxed", payload.getBoolean("waxed"),
                    "glowing", payload.getBoolean("glowing")));
            else {
                int slots = payload.getInt("slots");
                if (slots < 0 || slots > 9) throw new IllegalStateException("Invalid gameplay Molotov slot count: " + slots);
                var contents = new ArrayList<Map<String,Object>>();
                for (int slot = 0; slot < slots; slot++) contents.add(payload.containsKey("slot_" + slot)
                        ? gameplayItemEvidence(ItemStack.deserializeBytes(payload.getByteArray("slot_" + slot))) : Map.of());
                result.put("state", Map.of("slots", contents, "ground", "GROUND".equals(payload.getString("storage"))));
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Gameplay controller unavailable: " + name, failure);
        }
        return result;
    }

    private Map<String,Object> gameplaySnapshot() {
        var result = new LinkedHashMap<String,Object>();
        for (String name : GAMEPLAY_NAMES) result.put(name, gameplayFurniture(name));
        result.put("canonicalItems", Map.of(
                "chair", gameplayItemEvidence(ce("kaleidoscope_deco:chair/oak", 1)),
                "board", gameplayItemEvidence(ce("kaleidoscope_tavern:poppy_sandwich_board", 1)),
                "molotov", gameplayItemEvidence(ce("kaleidoscope_tavern:molotov", 1))));
        var center = new Location(world, X + .5, Y, Z + .5);
        result.put("looseItems", world.getNearbyEntitiesByType(org.bukkit.entity.Item.class, center, 5).stream()
                .filter(item -> Math.abs(item.getLocation().getY() - Y) < 3).map(item -> {
                    var data = new LinkedHashMap<String,Object>(gameplayItemEvidence(item.getItemStack()));
                    data.put("uuid", item.getUniqueId().toString());
                    var at = item.getLocation();
                    data.put("world", Objects.requireNonNull(at.getWorld()).getName());
                    data.put("position", List.of(at.getX(), at.getY(), at.getZ()));
                    return data;
                }).toList());
        return result;
    }

    @SuppressWarnings("unchecked")
    private String dollPose() {
        var plugin = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("KaleidoscopeDollPlugin"));
        try {
            var type = (Class<? extends net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController>)
                    plugin.getClass().getClassLoader().loadClass("net.kaleidoscope.doll.DollController");
            var data = new net.momirealms.craftengine.libraries.nbt.CompoundTag();
            Objects.requireNonNull(doll.controller.get(type, 0)).saveCustomData(data);
            return data.getCompound("kaleidoscope_doll:pose_v1").getCompound("payload").getString("pose");
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Doll pose controller unavailable", failure);
        }
    }

    private Map<String,Object> snapshot(Player player) {
        refreshReferences();
        var view = player.getOpenInventory(); var state = new LinkedHashMap<String,Object>();
        state.put("view", view.getType());
        state.put("playerUuid", player.getUniqueId().toString());
        state.put("furnitureInteractions", furnitureInteractions.getOrDefault(player.getName(), 0L));
        // Bukkit's smithing inventory contents expose the three inputs separately from the result.
        var top = new ArrayList<>(Arrays.stream(view.getTopInventory().getContents()).map(ConnectedKitchenProbe::describe).toList());
        if (view.getTopInventory() instanceof org.bukkit.inventory.SmithingInventory smithing && top.size() == 3)
            top.add(describe(smithing.getResult()));
        state.put("top", top);
        state.put("inventory", Arrays.stream(player.getInventory().getStorageContents())
                .map(stack -> gameplayTracking ? gameplayItemEvidence(stack) : describe(stack)).toList());
        state.put("cursor", gameplayTracking ? gameplayItemEvidence(player.getItemOnCursor()) : describe(player.getItemOnCursor()));
        state.put("canceledSmith", canceled);
        state.put("scarecrow", scarecrow != null && scarecrow.isValid()); state.put("hits", hits); state.put("drops", drops);
        if (board != null && board.isValid()) {
            state.put("board", board.currentVariant().name());
            state.put("boardHitbox", board.interactableEntityIds()[0]);
            state.put("boardDenied", boardDenied);
            state.put("flowerDenied", flowerDenied);
        }
        if (sofa != null && sofa.isValid()) {
            state.put("sofa", sofa.currentVariant().name());
            state.put("sofaHitbox", sofa.interactableEntityIds()[0]);
        }
        state.put("canceledTrades", canceledTrades);
        state.put("changedSlots", changedSlots);
        state.put("nestedSofaRejected", nestedSofaRejected);
        state.put("heldSlot", player.getInventory().getHeldItemSlot());
        if (doll != null && doll.isValid()) {
            state.put("dollPose", dollPose());
            state.put("dollHitbox", doll.interactableEntityIds()[0]);
        }
        if (scarecrow != null && scarecrow.isValid()) {
            var body = scarecrow.hitboxes().getFirst().part(0);
            if (body.interactive()) throw new IllegalStateException("Expected the non-interactive body hitbox first");
            state.put("scarecrowBodyId", body.entityId());
        }
        var entity = BukkitAdaptor.adapt(world).storageWorld().getBlockEntityAtIfLoaded(new BlockPos(X,Y,Z));
        var steamer = entity == null ? null : entity.controller.get(SteamerController.class,0);
        state.put("placedSlots", steamer == null ? List.of() : contents(steamer.snapshotSlots()));
        if (persistenceTracking) state.put("persistence", persistenceSnapshot());
        if (gameplayTracking) {
            state.put("gameplay", gameplaySnapshot());
            state.put("gameCanceledTrades", gameCanceledTrades);
            state.put("gameCancelArmed", gameCancelPlayer != null);
        }
        return state;
    }

    @EventHandler(priority=EventPriority.LOWEST, ignoreCancelled=true) public void cancelSmith(InventoryClickEvent event) {
        if (cancelSmith && FIRST.equals(event.getWhoClicked().getName()) && event.getView().getType() == InventoryType.SMITHING && event.getRawSlot() == 3) {
            event.setCancelled(true); cancelSmith = false; canceled++;
        }
    }
    @EventHandler(priority=EventPriority.LOWEST) public void protect(FurnitureBreakEvent event) {
        if (event.furniture() == scarecrow && SECOND.equals(event.getPlayer().getName()) && hits.isEmpty()) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void observe(FurnitureBreakEvent event) {
        if (event.furniture() == scarecrow) hits.add(Map.of("player", event.getPlayer().getName(), "tick",world.getGameTime(),
                "canceled",event.isCancelled(), "regionOwned",Bukkit.isOwnedByCurrentRegion(event.getPlayer())));
        recordGameCombat("break", event.getPlayer(), event.furniture(), event);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void observeGameHit(FurnitureHitEvent event) {
        recordGameCombat("hit", event.getPlayer(), event.furniture(), event);
    }

    private void recordGameCombat(String kind, Player player, BukkitFurniture furniture, Cancellable event) {
        if (!gameplayTracking || (!FIRST.equals(player.getName()) && !SECOND.equals(player.getName()))) return;
        if (!Bukkit.isOwnedByCurrentRegion(player))
            throw new IllegalStateException("Gameplay combat observation is outside its player region");
        var at = furniture.location();
        if (player.getWorld() != world || at.getWorld() != world) return;
        String fixture = null;
        for (String name : GAMEPLAY_NAMES) {
            var position = GAMEPLAY_POSITIONS.get(name);
            if (GAMEPLAY_KEYS.get(name).equals(furniture.id())
                    && Math.abs(at.getX() - position.get(0)) < .01
                    && Math.abs(at.getY() - position.get(1)) < .01
                    && Math.abs(at.getZ() - position.get(2)) < .01) {
                fixture = name;
                break;
            }
        }
        if (fixture == null) return;
        if (!Bukkit.isOwnedByCurrentRegion(world, X >> 4, Z >> 4))
            throw new IllegalStateException("Gameplay combat observation is outside its furniture region");
        var location = player.getLocation();
        var values = new LinkedHashMap<String, Object>();
        values.put("sequence", ++gameCombatSequence);
        values.put("kind", kind);
        values.put("player", player.getName());
        values.put("playerUuid", player.getUniqueId().toString());
        values.put("fixture", fixture);
        values.put("uuid", furniture.uuid().toString());
        values.put("id", furniture.id().asString());
        values.put("metaEntityId", furniture.entityId());
        values.put("hitboxes", Arrays.stream(furniture.interactableEntityIds()).boxed().toList());
        values.put("hitTimes", furniture.furniture().settings().hitTimes());
        values.put("wallTimeMillis", System.currentTimeMillis());
        values.put("tick", world.getGameTime());
        values.put("regionOwned", true);
        values.put("playerPosition", List.of(location.getX(), location.getY(), location.getZ()));
        values.put("playerRotation", List.of(location.getYaw(), location.getPitch()));
        values.put("position", List.of(at.getX(), at.getY(), at.getZ()));
        // CE may still change the event's cancellation through its controller after MONITOR.
        // The next read-only region task observes the final value, not just this callback instant.
        gameCombatReceipts.add(new GameCombatReceipt(values, event));
    }

    private Map<String, Object> gameCombatSnapshot(Player player) {
        var combat = new ArrayList<Map<String, Object>>();
        for (var receipt : gameCombatReceipts) {
            var value = new LinkedHashMap<String, Object>(receipt.values());
            value.put("cancelled", receipt.event().isCancelled());
            combat.add(value);
        }
        // This command deliberately avoids controller/NBT and inventory snapshots between native hits.
        return Map.of("playerUuid", player.getUniqueId().toString(), "combat", combat);
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void protectBoard(FurnitureInteractEvent event) {
        if (event.furniture() == board && SECOND.equals(event.getPlayer().getName()) && boardDenied == 0) {
            event.setCancelled(true);
            boardDenied++;
        }
    }
    @EventHandler(priority=EventPriority.MONITOR) public void observeInteraction(FurnitureInteractEvent event) {
        var player = event.getPlayer();
        if ((FIRST.equals(player.getName()) || SECOND.equals(player.getName())) && player.getWorld() == world
                && Bukkit.isOwnedByCurrentRegion(world, X >> 4, Z >> 4))
            furnitureInteractions.merge(player.getName(), 1L, Long::sum);
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void cancelFlower(ItemSpawnEvent event) {
        var at = event.getLocation();
        if (board != null && at.getWorld() == world && at.distanceSquared(board.location()) < .1
                && event.getEntity().getItemStack().getType().name().endsWith("_TULIP") && flowerDenied == 0) {
            event.setCancelled(true);
            flowerDenied++;
        }
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void dropped(ItemSpawnEvent event) {
        var at = event.getLocation();
        if (observeDrops && at.getWorld() == world && at.getBlockX() >> 4 == X >> 4 && at.getBlockZ() >> 4 == Z >> 4 && Math.abs(at.getY()-Y) < 3)
            drops.add(describe(event.getEntity().getItemStack()));
    }
}
