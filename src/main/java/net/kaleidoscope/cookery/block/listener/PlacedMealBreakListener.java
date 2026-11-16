package net.kaleidoscope.cookery.block.listener;

import net.kaleidoscope.cookery.block.entity.PlacedMealController;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;

/** Removing any part must respect the protection decision for the entire dish. */
public final class PlacedMealBreakListener implements Listener {
    private static final ThreadLocal<Boolean> CHECKING = ThreadLocal.withInitial(() -> false);
    private static final Map<Block, BlockBreakEvent> BREAKING = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void remember(BlockBreakEvent event) {
        if (meal(event.getBlock()) == null) return;
        BREAKING.put(event.getBlock(), event);
        Bukkit.getRegionScheduler().runDelayed(KaleidoscopeCookeryPlugin.instance(), event.getBlock().getLocation(),
                task -> BREAKING.remove(event.getBlock(), event), 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breaking(CustomBlockBreakEvent event) {
        if (CHECKING.get()) return;
        PlacedMealController meal = meal(event.bukkitBlock());
        if (meal == null) return;
        Verdict verdict = authorize(event.getPlayer(), meal, event.bukkitBlock(), false);
        if (!verdict.allowed()) event.setCancelled(true);
        else event.setDropItems(event.dropItems() && verdict.drops());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void accepted(CustomBlockBreakEvent event) {
        if (CHECKING.get()) return;
        var meal = meal(event.bukkitBlock());
        if (meal == null) return;
        BlockBreakEvent original = BREAKING.remove(event.bukkitBlock());
        boolean creative = event.getPlayer().getGameMode() == GameMode.CREATIVE;
        // A cancelled break never removes the block; discard its drop policy if another removal follows.
        meal.dropDecision(() -> event.isCancelled() || original != null && original.isCancelled()
                || !creative && event.dropItems() && (original == null || original.isDropItems()));
    }

    public static boolean removeEmpty(Player player, PlacedMealController meal) {
        if (!meal.state().empty() || !meal.complete()) return false;
        Block anchor = ((org.bukkit.World) meal.blockEntity().world.world().platformWorld())
                .getBlockAt(meal.blockEntity().pos.x(), meal.blockEntity().pos.y(), meal.blockEntity().pos.z());
        Verdict verdict = authorize(player, meal, anchor, true);
        if (!verdict.allowed()) return false;
        meal.dropDecision(() -> verdict.drops());
        return meal.removeMeal();
    }

    private record Verdict(boolean allowed, boolean drops) {
        private static final Verdict DENIED = new Verdict(false, false);
    }
    private static Verdict authorize(Player player, PlacedMealController meal, Block clicked, boolean includeClicked) {
        if (CHECKING.get()) return Verdict.DENIED;
        CHECKING.set(true);
        try {
            boolean drops = true;
            for (BlockPos pos : meal.footprint()) {
                if (!Bukkit.isOwnedByCurrentRegion(clicked.getWorld(), pos.x() >> 4, pos.z() >> 4)) return Verdict.DENIED;
                Block block = clicked.getWorld().getBlockAt(pos.x(), pos.y(), pos.z());
                var member = meal(block);
                if (member == null || !member.group().equals(meal.group())
                        || !InteractGuard.canBreak(BukkitAdaptor.adapt(player), BukkitAdaptor.adapt(block.getWorld()),
                        pos.x(), pos.y(), pos.z())) return Verdict.DENIED;
                if (!includeClicked && block.equals(clicked)) continue;
                BlockBreakEvent bukkit = new BlockBreakEvent(block, player);
                if (!bukkit.callEvent()) return Verdict.DENIED;
                // CE converts this event to CustomBlockBreakEvent itself; firing it again duplicates hooks.
                drops &= bukkit.isDropItems();
            }
            return meal.complete() ? new Verdict(true, drops) : Verdict.DENIED;
        } finally { CHECKING.remove(); }
    }
    private static PlacedMealController meal(Block block) {
        var entity = BukkitAdaptor.adapt(block.getWorld()).storageWorld().getBlockEntityAtIfLoaded(
                new BlockPos(block.getX(), block.getY(), block.getZ()));
        return entity == null ? null : entity.controller.get(PlacedMealController.class, 0);
    }
}
