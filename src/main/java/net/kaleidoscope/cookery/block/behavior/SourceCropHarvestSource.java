package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.ItemUtils;

/** Snapshot only the interaction source that protection and logging callbacks can invalidate. */
record SourceCropHarvestSource(KaleidoscopeCookeryPlugin plugin, org.bukkit.entity.Player actor,
                               int slot, Item held, int count) {
    static SourceCropHarvestSource capture(Player player) {
        KaleidoscopeCookeryPlugin plugin = KaleidoscopeCookeryPlugin.instance();
        if (plugin == null || !plugin.isEnabled()
                || !(player.platformPlayer() instanceof org.bukkit.entity.Player actor)) return null;
        Item item = player.getItemInHand(InteractionHand.MAIN_HAND);
        return new SourceCropHarvestSource(plugin, actor, actor.getInventory().getHeldItemSlot(),
                ItemUtils.isEmpty(item) ? null : item.copyWithCount(1), ItemUtils.isEmpty(item) ? 0 : item.count());
    }

    boolean matches(Player player) {
        if (!plugin.isEnabled() || KaleidoscopeCookeryPlugin.instance() != plugin
                || player.platformPlayer() != actor || actor.getInventory().getHeldItemSlot() != slot) return false;
        Item current = player.getItemInHand(InteractionHand.MAIN_HAND);
        return held == null ? ItemUtils.isEmpty(current)
                : !ItemUtils.isEmpty(current) && current.count() == count && current.isSimilar(held);
    }
}
