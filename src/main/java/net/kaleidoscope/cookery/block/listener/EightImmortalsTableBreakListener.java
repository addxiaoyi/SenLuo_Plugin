package net.kaleidoscope.cookery.block.listener;

import net.kaleidoscope.cookery.block.behavior.EightImmortalsTableBehavior;
import net.kaleidoscope.cookery.block.entity.EightImmortalsTableController;
import net.kaleidoscope.cookery.item.behavior.EightImmortalsTableItemBehavior;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/** A player breaking any part must be permitted to remove each matching table part. */
public final class EightImmortalsTableBreakListener implements Listener {
    private static final ThreadLocal<Check> CHECKING = new ThreadLocal<>();
    private static final class Check {
        final Player actor;
        Block linked;
        Check(Player actor) { this.actor = actor; }
    }
    private record Member(EightImmortalsTableController controller, ImmutableBlockState state) {}

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breaking(CustomBlockBreakEvent event) {
        Check active = CHECKING.get();
        if (active != null) {
            // Only the custom event bridged from our linked Bukkit event may bypass recursion.
            if (event.getPlayer() != active.actor || !event.bukkitBlock().equals(active.linked)) event.setCancelled(true);
            return;
        }
        var clicked = table(event.bukkitBlock());
        if (clicked == null) return;
        Player actor = event.getPlayer();
        World world = event.bukkitBlock().getWorld();
        if (!validActor(actor, world) || clicked.removed() || clicked.blockEntity().world == null
                || EightImmortalsTableItemBehavior.placing(clicked.blockEntity())) { event.setCancelled(true); return; }
        var check = new Check(actor);
        CHECKING.set(check);
        try {
            boolean drops = event.dropItems();
            Map<Block, Member> members = new LinkedHashMap<>();
            var entity = clicked.blockEntity(); var behavior = clicked.behavior();
            var level = entity.world.world();
            var player = BukkitAdaptor.adapt(actor);
            var anchor = behavior.anchor(entity.pos, entity.blockState);
            // Collect the full footprint before any protection/event callback can mutate it.
            for (String part : EightImmortalsTableBehavior.PARTS) {
                BlockPos pos = EightImmortalsTableBehavior.cell(anchor, behavior.facing(entity.blockState), part);
                if (!accessible(world, pos.x(), pos.z())) { event.setCancelled(true); return; }
                Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                var member = table(block);
                // A damaged table may be removed; unrelated replacements are never included.
                if (clicked.matches(member, part)) members.put(block, new Member(member, member.blockEntity().blockState));
            }
            if (!members.containsKey(event.bukkitBlock())) { event.setCancelled(true); return; }
            for (Block block : members.keySet()) {
                if (!current(actor, world, members)
                        || !InteractGuard.canBreak(player, level, block.getX(), block.getY(), block.getZ())
                        || !current(actor, world, members)) { event.setCancelled(true); return; }
                if (!block.equals(event.bukkitBlock())) {
                    var linked = new BlockBreakEvent(block, actor);
                    check.linked = block;
                    try {
                        if (!linked.callEvent() || !current(actor, world, members)) { event.setCancelled(true); return; }
                        drops &= linked.isDropItems();
                    } finally { check.linked = null; }
                }
            }
            for (Block block : members.keySet()) {
                if (!current(actor, world, members)
                        || !InteractGuard.canBreak(player, level, block.getX(), block.getY(), block.getZ())) {
                    event.setCancelled(true); return;
                }
            }
            // Recheck every identity after the final callback, without invoking more callbacks.
            if (!current(actor, world, members)) { event.setCancelled(true); return; }
            event.setDropItems(drops);
        } finally { CHECKING.remove(); }
    }
    private static boolean current(Player actor, World world, Map<Block, Member> members) {
        if (!validActor(actor, world)) return false;
        for (var entry : members.entrySet()) {
            Member expected = entry.getValue();
            if (table(entry.getKey()) != expected.controller || expected.controller.removed()
                    || expected.controller.blockEntity().blockState != expected.state) return false;
        }
        return true;
    }
    private static boolean validActor(Player actor, World world) {
        return Bukkit.isOwnedByCurrentRegion(actor) && actor.isValid() && actor.isOnline() && !actor.isDead()
                && actor.getGameMode() != GameMode.SPECTATOR && actor.getWorld() == world;
    }
    private static boolean accessible(World world, int x, int z) {
        return Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4) && world.isChunkLoaded(x >> 4, z >> 4);
    }
    private static EightImmortalsTableController table(Block block) {
        if (!accessible(block.getWorld(), block.getX(), block.getZ())) return null;
        var entity = BukkitAdaptor.adapt(block.getWorld()).storageWorld().getBlockEntityAtIfLoaded(new BlockPos(block.getX(), block.getY(), block.getZ()));
        return entity == null ? null : entity.controller.get(EightImmortalsTableController.class, 0);
    }
}
