package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.block.entity.render.Particles;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.Vec3d;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Commit the state transition before delivering its harvest, including physics collapse. */
final class HangingStringHarvest {
    private static final Set<Block> ACTIVE = ConcurrentHashMap.newKeySet();
    private HangingStringHarvest() {}

    static boolean accepts(Item item, Key harvestItem) {
        return ItemUtils.isEmpty(item) || item.id().equals(harvestItem);
    }

    static boolean pick(Block block, Player player, ImmutableBlockState original, Property<Boolean> sheared,
                        Key harvestItem, int amount, Key sound) {
        if (original == null || !owned(block) || player == null || !ACTIVE.add(block)) return false;
        try {
            if (!eligible(block, player, original, harvestItem)) return false;
            Item output = InventoryUtils.createOrEmpty(harvestItem);
            if (ItemUtils.isEmpty(output)) return false;
            output = output.copyWithCount(amount);
            var particles = CraftEngineBlocks.getBukkitBlockData(original);
            if (!eligible(block, player, original, harvestItem)) return false;
            if (original.get(sheared)) {
                if (!CraftEngineBlocks.remove(block, false) || !block.isEmpty()) return false;
            } else {
                ImmutableBlockState next = original.with(sheared, true);
                if (!CraftEngineBlocks.place(block.getLocation(), next, false, false)
                        || CraftEngineBlocks.getCustomBlockState(block) != next) return false;
            }
            // Source puts all three directly in an empty main hand, even when other stacks exist.
            if (ItemUtils.isEmpty(player.getItemInHand(InteractionHand.MAIN_HAND))) {
                player.setItemInHand(InteractionHand.MAIN_HAND, output);
            } else {
                InventoryUtils.give(player, output);
            }
            block.getWorld().playSound(block.getLocation(), sound.toString(), SoundCategory.BLOCKS, 1f, .8f + (float) Math.random() * .4f);
            Particles.emit(block.getWorld(), Particle.BLOCK, block.getX() + .5, block.getY() + .5, block.getZ() + .5,
                    20, .25, .25, .25, .05, particles);
            return true;
        } finally { ACTIVE.remove(block); }
    }

    static void collapse(Block block, ImmutableBlockState original, Property<Boolean> sheared,
                         Key harvestItem, int amount, BooleanSupplier supported) {
        if (original == null || !owned(block) || !ACTIVE.add(block)) return;
        try {
            if (CraftEngineBlocks.getCustomBlockState(block) != original || supported.getAsBoolean()) return;
            boolean dropItems = !Boolean.FALSE.equals(block.getWorld().getGameRuleValue(GameRule.DO_TILE_DROPS));
            Item output = dropItems ? InventoryUtils.createOrEmpty(harvestItem) : null;
            if (dropItems && ItemUtils.isEmpty(output)) return;
            if (dropItems) output = output.copyWithCount(original.get(sheared) ? amount : amount * 2);
            if (!owned(block) || CraftEngineBlocks.getCustomBlockState(block) != original || supported.getAsBoolean()) return;
            if (!CraftEngineBlocks.remove(block, false) || !block.isEmpty()) return;
            if (dropItems) BukkitAdaptor.adapt(block.getWorld()).dropItemNaturally(
                    new Vec3d(block.getX() + .5, block.getY() + .5, block.getZ() + .5), output);
        } finally { ACTIVE.remove(block); }
    }

    private static boolean eligible(Block block, Player player, ImmutableBlockState original, Key harvestItem) {
        if (!(player.platformPlayer() instanceof org.bukkit.entity.Player bukkit)
                || !bukkit.isValid() || bukkit.getWorld() != block.getWorld()
                || bukkit.getGameMode() == GameMode.SPECTATOR || !Bukkit.isOwnedByCurrentRegion(bukkit)
                || !owned(block) || !accepts(player.getItemInHand(InteractionHand.MAIN_HAND), harvestItem)) return false;
        if (!InteractGuard.canBreak(player, BukkitAdaptor.adapt(block.getWorld()), block.getX(), block.getY(), block.getZ())) return false;
        // Protection adapters can run external callbacks; recheck before touching the world.
        return owned(block) && bukkit.isValid() && bukkit.getWorld() == block.getWorld()
                && bukkit.getGameMode() != GameMode.SPECTATOR && Bukkit.isOwnedByCurrentRegion(bukkit)
                && accepts(player.getItemInHand(InteractionHand.MAIN_HAND), harvestItem)
                && CraftEngineBlocks.getCustomBlockState(block) == original;
    }

    static boolean owned(Block block) {
        return Bukkit.isOwnedByCurrentRegion(block.getWorld(), block.getX() >> 4, block.getZ() >> 4)
                && block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }
}
