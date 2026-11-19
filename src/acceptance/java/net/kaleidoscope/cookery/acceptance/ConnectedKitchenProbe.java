package net.kaleidoscope.cookery.acceptance;

import com.google.gson.Gson;
import net.kaleidoscope.cookery.block.entity.SteamerController;
import net.kaleidoscope.cookery.block.entity.SteamerInventory;
import net.kaleidoscope.cookery.item.SteamerItemContents;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.*;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/** Console-only arrangement and server-side observations. All player actions arrive over the network. */
final class ConnectedKitchenProbe implements Listener {
    private static final int X = 680, Y = 200, Z = 8;
    private static final String FIRST = "SenLuoProbeA", SECOND = "SenLuoProbeB";
    private static final Gson JSON = new Gson();
    private final JavaPlugin plugin;
    private final World world;
    private final List<Map<String, Object>> hits = new ArrayList<>(), drops = new ArrayList<>();
    private BukkitFurniture scarecrow;
    private boolean observeDrops, cancelSmith;
    private int canceled;

    private ConnectedKitchenProbe(JavaPlugin plugin, World world) { this.plugin = plugin; this.world = world; }

    static void enable(JavaPlugin plugin) {
        if (!"127.0.0.1".equals(Bukkit.getIp())) throw new IllegalStateException("Expected loopback server");
        var world = Objects.requireNonNull(Bukkit.getWorld("acceptance-world"));
        var probe = new ConnectedKitchenProbe(plugin, world);
        Bukkit.getPluginManager().registerEvents(probe, plugin);
        var cmd = Objects.requireNonNull(plugin.getCommand("senluoprobe"));
        cmd.setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof ConsoleCommandSender) || args.length != 3 || !args[0].matches("[0-9]{1,8}")
                    || !Set.of(FIRST, SECOND).contains(args[2])) return true;
            probe.dispatch(args[0], args[1], args[2]);
            return true;
        });
    }

    private void reply(String token, Object result) { plugin.getLogger().info("CONNECTED_REPLY " + token + " " + JSON.toJson(result)); }

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
                    case "inspect" -> { }
                    default -> throw new IllegalArgumentException(action);
                }
                reply(token, snapshot(player));
            } catch (Exception | LinkageError error) { reply(token, Map.of("error", error.toString())); }
        }, () -> reply(token, Map.of("error", "retired")));
    }

    private static ItemStack custom(String name, int count) {
        var item = InventoryUtils.bukkitCopy(InventoryUtils.createOrEmpty(Key.of("kaleidoscopecookery:" + name)));
        item.setAmount(count); return item;
    }

    private static List<String> contents(List<SteamerInventory.Slot> slots) {
        return slots.stream().map(slot -> slot == null ? "empty" : slot.item().id() + "*" + slot.item().count()).toList();
    }

    private static Map<String,Object> describe(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return Map.of();
        var item = InventoryUtils.wrap(stack); var data = new LinkedHashMap<String,Object>();
        data.put("id", item.id().toString()); data.put("count", stack.getAmount());
        if (item.id().equals(SteamerItemContents.ID)) data.put("slots", contents(SteamerItemContents.read(item)));
        if (item.id().toString().equals("kaleidoscopecookery:netherite_kitchen_knife")) SmithingSmoke.assertProduct(stack);
        return data;
    }

    private Map<String,Object> snapshot(Player player) {
        var view = player.getOpenInventory(); var state = new LinkedHashMap<String,Object>();
        state.put("view", view.getType());
        // Bukkit's smithing inventory contents expose the three inputs separately from the result.
        var top = new ArrayList<>(Arrays.stream(view.getTopInventory().getContents()).map(ConnectedKitchenProbe::describe).toList());
        if (view.getTopInventory() instanceof org.bukkit.inventory.SmithingInventory smithing && top.size() == 3)
            top.add(describe(smithing.getResult()));
        state.put("top", top);
        state.put("inventory", Arrays.stream(player.getInventory().getStorageContents()).map(ConnectedKitchenProbe::describe).toList());
        state.put("cursor", describe(player.getItemOnCursor())); state.put("canceledSmith", canceled);
        state.put("scarecrow", scarecrow != null && scarecrow.isValid()); state.put("hits", hits); state.put("drops", drops);
        if (scarecrow != null && scarecrow.isValid()) {
            var body = scarecrow.hitboxes().getFirst().part(0);
            if (body.interactive()) throw new IllegalStateException("Expected the non-interactive body hitbox first");
            state.put("scarecrowBodyId", body.entityId());
        }
        var entity = BukkitAdaptor.adapt(world).storageWorld().getBlockEntityAtIfLoaded(new BlockPos(X,Y,Z));
        var steamer = entity == null ? null : entity.controller.get(SteamerController.class,0);
        state.put("placedSlots", steamer == null ? List.of() : contents(steamer.snapshotSlots()));
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
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void dropped(ItemSpawnEvent event) {
        var at = event.getLocation();
        if (observeDrops && at.getWorld() == world && at.getBlockX() >> 4 == X >> 4 && at.getBlockZ() >> 4 == Z >> 4 && Math.abs(at.getY()-Y) < 3)
            drops.add(describe(event.getEntity().getItemStack()));
    }
}
