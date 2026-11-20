package net.kaleidoscope.cookery.block.listener;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import java.util.Set;

/** Source oil is an eternal upper-face fire support and rejects natural surface spawning. */
public final class OilBlockListener implements Listener {
    private static final Key OIL = Key.of("kaleidoscopecookery:oil_block");
    private static final Set<String> SURFACE_SPAWNS = Set.of("NATURAL", "CHUNK_GEN", "PATROL", "RAID", "REINFORCEMENTS");
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fading(BlockFadeEvent event) {
        Block fire = event.getBlock();
        if (fire.getType() == Material.FIRE && oil(fire.getRelative(BlockFace.DOWN))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void spawning(CreatureSpawnEvent event) {
        if (!SURFACE_SPAWNS.contains(event.getSpawnReason().name())) return;
        Block below = event.getLocation().getBlock().getRelative(BlockFace.DOWN);
        if (oil(below)) event.setCancelled(true);
    }
    private static boolean oil(Block block) {
        if (!Bukkit.isOwnedByCurrentRegion(block.getWorld(), block.getX() >> 4, block.getZ() >> 4)) return false;
        var state = CraftEngineBlocks.getCustomBlockState(block);
        return state != null && OIL.equals(state.owner().value().id());
    }
}
