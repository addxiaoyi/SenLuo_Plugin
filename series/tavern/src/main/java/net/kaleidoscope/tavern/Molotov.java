package net.kaleidoscope.tavern;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import net.kaleidoscope.series.Items;
import net.momirealms.antigrieflib.Flag;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Fire;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/** Hand-held Molotovs and collisions with the source mod's placed Molotov bottles. */
final class Molotov implements Listener, AutoCloseable {
    private static final int[][] NEIGHBORS = {{0,0,0}, {0,1,0}, {0,-1,0}, {1,0,0}, {-1,0,0}, {0,0,1}, {0,0,-1}};
    private final TavernPlugin plugin;
    private final NamespacedKey marker;
    private final Map<UUID, Use> uses = new ConcurrentHashMap<>();
    private final Set<UUID> committing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Trace> traces = new ConcurrentHashMap<>();
    private final Map<UUID, Flight> flights = new ConcurrentHashMap<>();
    private final Map<UUID, MolotovController> furniture = new ConcurrentHashMap<>();
    private final Set<UUID> synthetic = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    Molotov(TavernPlugin plugin) { this.plugin = plugin; marker = new NamespacedKey(plugin, "molotov_projectile_v1"); }

    void loaded(MolotovController controller) {
        if (closed) return;
        MolotovController previous = furniture.put(controller.furniture().uuid(), controller);
        if (previous != null && previous != controller && owned(previous.location())) previous.shutdown();
    }
    void unloaded(MolotovController controller) { furniture.remove(controller.furniture().uuid(), controller); }
    Player responsibleFor(Projectile projectile) {
        Flight flight = flights.get(projectile.getUniqueId());
        if (flight != null) return flight.responsible;
        return projectile.getShooter() instanceof Player player ? player : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void use(PlayerInteractEvent event) {
        // On a block, retain the source BlockItem placement path. Air use begins the spear animation.
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.getHand() == null || event.useItemInHand() == Event.Result.DENY) return;
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        ItemStack held = player.getInventory().getItem(hand);
        if (!MolotovPhysics.ITEM.equals(Items.id(held))) return;
        event.setUseItemInHand(Event.Result.DENY);
        if (!available(player) || !plugin.mayUse(player, player.getLocation()) || uses.containsKey(player.getUniqueId())
                || committing.contains(player.getUniqueId()) || event.getItem() == null || !held.equals(event.getItem())) return;
        var consumable = held.getData(DataComponentTypes.CONSUMABLE);
        if (consumable == null || consumable.consumeSeconds() != MolotovPhysics.USE_TICKS / 20f
                || consumable.animation() != ItemUseAnimation.SPEAR || consumable.hasConsumeParticles()) {
            player.sendMessage("这瓶酒缺少当前版本的投掷组件，请由管理员换取当前 CraftEngine 物品；原物品已保留。");
            return;
        }
        ItemStack original = held.clone();
        int slot = hand == EquipmentSlot.OFF_HAND ? 40 : player.getInventory().getHeldItemSlot();
        Use use = new Use(player, hand, slot, original);
        if (uses.putIfAbsent(player.getUniqueId(), use) != null) return;
        player.startUsingItem(hand);
        var task = player.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (uses.get(player.getUniqueId()) != use) { scheduled.cancel(); return; }
            if (!available(player) || !sameHand(use, use.original) || !player.hasActiveItem()) {
                cancel(player); scheduled.cancel();
            }
        }, () -> uses.remove(player.getUniqueId(), use), 1, 1);
        if (task == null) cancel(player);
    }

    @EventHandler public void release(PlayerStopUsingItemEvent event) {
        Player player = event.getPlayer();
        Use use = uses.remove(player.getUniqueId());
        if (use == null || !committing.add(player.getUniqueId())) return;
        try {
            boolean intact = use.original.isSimilar(event.getItem()) && sameHand(use, use.original);
            if (!intact || !MolotovPhysics.charged(event.getTicksHeldFor()) || !available(player)
                    || !plugin.mayUse(player, player.getLocation())) return;
            Location at = player.getEyeLocation().subtract(0, 0.1, 0);
            if (!plugin.allows(player, Flag.PLACE, at)) return;
            Vector velocity = MolotovPhysics.thrown(at.getPitch(), at.getYaw(), player.getVelocity(), player.isOnGround(), ThreadLocalRandom.current());
            boolean launched = launch(player, true, at, velocity, use.original.asQuantity(1), () -> {
                if (!available(player) || !sameHand(use, use.original) || !plugin.mayUse(player, player.getLocation())
                        || !plugin.allows(player, Flag.PLACE, at)) return false;
                if (player.getGameMode() != GameMode.CREATIVE) player.getInventory().setItem(use.slot,
                        use.original.getAmount() == 1 ? null : use.original.asQuantity(use.original.getAmount() - 1));
                return true;
            });
            if (launched) at.getWorld().playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS,
                    0.5f, MolotovPhysics.throwPitch(ThreadLocalRandom.current().nextFloat()));
        } finally { committing.remove(player.getUniqueId()); }
    }

    /** Spawn and all launch callbacks precede the caller's one-way inventory/furniture commit. */
    boolean launch(Player responsible, boolean playerThrown, Location at, Vector velocity, ItemStack item, BooleanSupplier commit) {
        if (closed || !plugin.isEnabled() || !owned(at) || responsible != null && !available(responsible)) return false;
        Snowball projectile = at.getWorld().spawn(at, Snowball.class, ball -> {
            ball.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 0);
            ball.setItem(item.asQuantity(1));
            ball.setShooter(playerThrown ? responsible : null);
            ball.setVelocity(velocity);
            // Native snowball physics matches ThrowableProjectile. Flights are deliberately transient:
            // chunk unload/disable removes them; no dormant armed entity or refundable duplicate is saved.
            ball.setPersistent(false);
            ball.setGravity(true);
        });
        boolean committed = false;
        try {
            if (!projectile.isValid() || closed || !plugin.isEnabled() || responsible != null && !available(responsible)
                    || !watch(projectile) || !commit.getAsBoolean()) return false;
            projectile.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
            flights.put(projectile.getUniqueId(), new Flight(projectile, responsible));
            committed = true;
            return true;
        } finally {
            if (!committed) { traces.remove(projectile.getUniqueId()); projectile.remove(); }
        }
    }

    private boolean available(Player player) {
        return !closed && plugin.isEnabled() && Bukkit.isOwnedByCurrentRegion(player) && player.isOnline() && !player.isDead();
    }

    static boolean owned(Location at) {
        return at.getWorld() != null && at.getBlockY() >= at.getWorld().getMinHeight() && at.getBlockY() < at.getWorld().getMaxHeight()
                && at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)
                && Bukkit.isOwnedByCurrentRegion(at);
    }

    private static boolean ownedNeighborhood(Location at) {
        if (!owned(at)) return false;
        for (int[] offset : NEIGHBORS) if (!owned(at.clone().add(offset[0], offset[1], offset[2]))) return false;
        return true;
    }

    private boolean marked(Entity entity) { return entity.getPersistentDataContainer().has(marker, PersistentDataType.BYTE); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void launched(ProjectileLaunchEvent event) { if (!marked(event.getEntity())) watch(event.getEntity()); }

    private boolean watch(Projectile projectile) {
        if (closed || !plugin.isEnabled() || !projectile.isValid()) return false;
        Trace trace = new Trace(projectile, projectile.getLocation());
        Trace previous = traces.putIfAbsent(projectile.getUniqueId(), trace);
        if (previous != null) return true;
        var task = projectile.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (closed || traces.get(projectile.getUniqueId()) != trace || !projectile.isValid()) {
                traces.remove(projectile.getUniqueId(), trace); scheduled.cancel(); return;
            }
            traceBottle(projectile, projectile.getLocation());
        }, () -> { traces.remove(projectile.getUniqueId(), trace); flights.remove(projectile.getUniqueId()); }, 1, 1);
        if (task == null) traces.remove(projectile.getUniqueId(), trace);
        return task != null;
    }

    /** CE's interaction hitboxes are client-side; sweep the native bottle outline between actual server positions. */
    private boolean traceBottle(Projectile projectile, Location current) {
        Trace trace = traces.get(projectile.getUniqueId());
        if (trace == null || trace.previous.getWorld() != current.getWorld()) return false;
        Location previous = trace.previous;
        trace.previous = current.clone();
        Vector segment = current.toVector().subtract(previous.toVector());
        double distance = segment.length();
        if (distance < 1.0e-9 || !Double.isFinite(distance)) return false;
        MolotovController nearest = null;
        double best = distance + 1;
        for (MolotovController controller : furniture.values()) {
            Location at = controller.location();
            if (!controller.ground() || at.getWorld() != current.getWorld() || !owned(at) || !controller.usable()) continue;
            var hit = MolotovPhysics.bottleBox(at).rayTrace(previous.toVector(), segment, distance);
            if (hit != null) {
                double along = hit.getHitPosition().distance(previous.toVector());
                if (along < best) { best = along; nearest = controller; }
            }
        }
        if (nearest == null) return false;
        var target = nearest;
        Entity base = target.bukkitEntity();
        if (base == null || !synthetic.add(projectile.getUniqueId())) return false;
        try {
            ProjectileHitEvent collision = new ProjectileHitEvent(projectile, base);
            if (!collision.callEvent() || closed || !projectile.isValid() || !target.struck(projectile)) return false;
            if (flights.containsKey(projectile.getUniqueId())) detonate(projectile);
            return true;
        } finally { synthetic.remove(projectile.getUniqueId()); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (synthetic.contains(projectile.getUniqueId()) || closed) return;
        boolean intercepted = traceBottle(projectile, projectile.getLocation());
        if (!intercepted && event.getHitEntity() != null) {
            var target = CraftEngineFurniture.getLoadedFurnitureByCollider(event.getHitEntity());
            if (target == null) target = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(event.getHitEntity());
            MolotovController controller = target == null ? null : furniture.get(target.uuid());
            if (controller != null) controller.struck(projectile);
        }
        if (flights.containsKey(projectile.getUniqueId())) {
            // Do not inherit the vanilla snowball's special three-damage hit against blazes.
            event.setCancelled(true);
            detonate(projectile);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void nativeDamage(EntityDamageByEntityEvent event) { if (marked(event.getDamager())) event.setCancelled(true); }

    private void detonate(Projectile projectile) {
        Flight flight = flights.remove(projectile.getUniqueId());
        if (flight == null) return;
        traces.remove(projectile.getUniqueId());
        Location at = projectile.getLocation();
        try {
            if (closed || !plugin.isEnabled() || !owned(at)) return;
            Player responsible = flight.responsible;
            // Anti-grief adapters can access player state; do not read a foreign Folia player's region.
            if (responsible != null && !available(responsible)) return;
            for (MolotovPhysics.Column column : MolotovPhysics.fireColumns(ThreadLocalRandom.current())) {
                if (closed || !plugin.isEnabled() || responsible != null && !available(responsible)) break;
                for (int dy = -1; dy <= 1; dy++) {
                    Location target = new Location(at.getWorld(), at.getBlockX() + column.x(), at.getBlockY() + dy, at.getBlockZ() + column.z());
                    if (!ownedNeighborhood(target)) continue;
                    Block block = target.getBlock();
                    if (!block.getType().isAir()) continue;
                    BlockData fire = fireData(block);
                    if (fire == null || !block.canPlace(fire)) continue;
                    // The first viable position terminates the column even when protection vetoes it.
                    if (responsible == null || plugin.allows(responsible, Flag.PLACE, target)) {
                        BlockIgniteEvent ignite = new BlockIgniteEvent(block, BlockIgniteEvent.IgniteCause.FIREBALL, projectile);
                        if (ignite.callEvent() && !closed && plugin.isEnabled() && ownedNeighborhood(target) && block.getType().isAir()
                                && block.canPlace(fire) && (responsible == null || available(responsible)
                                && plugin.allows(responsible, Flag.PLACE, target))) block.setBlockData(fire, true);
                    }
                    break;
                }
            }
            if (closed || !plugin.isEnabled()) return;
            at.getWorld().playSound(at, Sound.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 2, 1);
            at.getWorld().playSound(at, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 2, 1);
            Location particles = at.clone().add(0, 0.5, 0);
            at.getWorld().spawnParticle(Particle.FLAME, particles, 30, 3, 1, 3, 0.1);
            at.getWorld().spawnParticle(Particle.SMOKE, particles, 20, 3, 1, 3, 0.1);
        } finally { projectile.remove(); }
    }

    private static BlockData fireData(Block block) {
        Material below = block.getRelative(BlockFace.DOWN).getType();
        if (below == Material.SOUL_SAND || below == Material.SOUL_SOIL) return Bukkit.createBlockData(Material.SOUL_FIRE);
        Fire fire = (Fire) Bukkit.createBlockData(Material.FIRE);
        for (BlockFace face : fire.getAllowedFaces()) fire.setFace(face, block.getRelative(face).getType().isBurnable());
        return fire;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void signal(BlockRedstoneEvent event) {
        if (closed || event.getOldCurrent() == event.getNewCurrent()) return;
        for (int[] delta : NEIGHBORS) {
            Location at = event.getBlock().getLocation().add(delta[0], delta[1], delta[2]);
            for (MolotovController controller : furniture.values()) if (!controller.ground()
                    && controller.location().getWorld() == at.getWorld() && sameBlock(controller.location(), at) && owned(at)) {
                // The event precedes the native wire write. Query combined neighbor power on the next region tick;
                // two neighbors rising while already powered must not eject two source bottles.
                Bukkit.getRegionScheduler().runDelayed(plugin, at, task -> { if (!closed) controller.refreshPower(); }, 1);
            }
        }
    }

    private static boolean sameBlock(Location a, Location b) { return a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ(); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakFurniture(FurnitureBreakEvent event) {
        MolotovController controller = furniture.get(event.furniture().uuid());
        if (controller != null && controller.blocksBreak(event)) event.setCancelled(true);
    }

    @EventHandler public void removed(EntityRemoveFromWorldEvent event) {
        traces.remove(event.getEntity().getUniqueId());
        flights.remove(event.getEntity().getUniqueId());
        MolotovController controller = furniture.get(event.getEntity().getUniqueId());
        if (controller != null && controller.bukkitEntity() == event.getEntity()) {
            furniture.remove(event.getEntity().getUniqueId(), controller);
            controller.shutdown();
        }
    }

    void start() {
        for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) {
            Bukkit.getRegionScheduler().execute(plugin, world, chunk.getX(), chunk.getZ(), () -> {
                if (!closed && chunk.isLoaded()) for (Entity entity : chunk.getEntities()) loadedEntity(entity);
            });
        }
    }
    @EventHandler public void loadedEntities(EntitiesLoadEvent event) {
        if (!closed) for (Entity entity : event.getEntities()) loadedEntity(entity);
    }

    /** CE can invoke a controller's onLoad before this plugin creates its service. */
    private void loadedEntity(Entity entity) {
        if (entity instanceof Projectile projectile) watch(projectile);
        var current = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
        if (current == null || !current.isValid() || current.controller == null) return;
        if (current.controller instanceof MolotovController controller) {
            recoverController(current, controller);
        } else {
            // Composite controller indices are absolute behavior slots in CE, not type ordinals.
            for (int slot = 0; slot < current.config().behaviors().size(); slot++) {
                MolotovController controller = current.controller.get(MolotovController.class, slot);
                if (controller != null) recoverController(current, controller);
            }
        }
    }

    private void recoverController(net.momirealms.craftengine.core.entity.furniture.Furniture current, MolotovController controller) {
        if (controller.furniture() == current && owned(controller.location()) && furniture.get(current.uuid()) != controller)
            controller.onLoad();
    }

    private boolean sameHand(Use use, ItemStack expected) {
        return (use.hand == EquipmentSlot.OFF_HAND || use.player.getInventory().getHeldItemSlot() == use.slot)
                && expected.equals(use.player.getInventory().getItem(use.slot));
    }
    private void cancel(Player player) {
        Use use = uses.remove(player.getUniqueId());
        if (use != null) clear(use);
    }
    private void clear(Use use) {
        boolean active = use.player.hasActiveItem() && use.original.isSimilar(use.player.getActiveItem());
        if (active) use.player.clearActiveItem();
    }
    @EventHandler public void quit(PlayerQuitEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.LOWEST) public void death(PlayerDeathEvent event) {
        Use use = uses.remove(event.getEntity().getUniqueId());
        if (use == null) return;
        clear(use);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void switchSlot(PlayerItemHeldEvent event) { if (uses.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) { if (uses.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) { if (uses.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) { if (uses.containsKey(event.getWhoClicked().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) { if (uses.containsKey(event.getWhoClicked().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void consume(PlayerItemConsumeEvent event) { if (MolotovPhysics.ITEM.equals(Items.id(event.getItem()))) event.setCancelled(true); }

    @Override public void close() {
        closed = true;
        var pending = List.copyOf(uses.values());
        var loadedFurniture = List.copyOf(furniture.values());
        uses.clear(); committing.clear(); furniture.clear(); traces.clear();
        for (Use use : pending) plugin.onPlayer(use.player, () -> clear(use));
        for (MolotovController controller : loadedFurniture) {
            if (Bukkit.isOwnedByCurrentRegion(controller.location())) controller.shutdown();
            else if (controller.bukkitEntity() != null) {
                var engine = BukkitCraftEngine.instance();
                if (engine != null) engine.scheduler().platform().run(controller::shutdown, () -> {}, controller.bukkitEntity());
            }
        }
        var active = List.copyOf(flights.values());
        flights.clear();
        for (Flight flight : active) {
            Entity entity = flight.projectile;
            if (Bukkit.isOwnedByCurrentRegion(entity)) entity.remove();
            else {
                var engine = BukkitCraftEngine.instance();
                if (engine != null) engine.scheduler().platform().run(entity::remove, () -> {}, entity);
            }
        }
    }

    private record Use(Player player, EquipmentSlot hand, int slot, ItemStack original) {}
    private record Flight(Snowball projectile, Player responsible) {}
    private static final class Trace {
        final Projectile projectile;
        Location previous;
        Trace(Projectile projectile, Location previous) { this.projectile = projectile; this.previous = previous.clone(); }
    }
}
