package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.block.entity.render.Particles;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.Vec3d;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/** Region-local landing transaction; callbacks may veto or replace the affected block. */
public final class StrawLanding {
    public static final Key STRAW = Key.of("kaleidoscopecookery:straw_block");
    private static final Key PANICLE = Key.of("kaleidoscopecookery:rice_panicle");
    private static final Key SEED = Key.of("kaleidoscopecookery:rice_seed");
    private static final Set<Block> ACTIVE = ConcurrentHashMap.newKeySet();
    private StrawLanding() {}

    public static float breakChance(double fallDistance) {
        if (Double.isNaN(fallDistance) || fallDistance < 3) return 0;
        return Math.clamp((float) fallDistance / 30f, 0f, 1f);
    }
    public static void land(Block block, LivingEntity entity, double distance, DoubleSupplier random) {
        if (!owned(block, entity) || !ACTIVE.add(block)) return;
        try {
            ImmutableBlockState original = CraftEngineBlocks.getCustomBlockState(block);
            if (original == null || !original.owner().value().id().equals(STRAW)) return;
            block.getWorld().playSound(block.getLocation(), "minecraft:block.grass.fall", 1f, 1f);
            float chance = breakChance(distance);
            if (chance == 0 || random.getAsDouble() >= chance) return;
            if (!authorized(block, entity)) return;
            // Resolve all drops before destroying a block: unloaded custom items cannot erase the bale.
            Item panicle = InventoryUtils.createOrEmpty(PANICLE), seed = InventoryUtils.createOrEmpty(SEED);
            if (ItemUtils.isEmpty(panicle) || ItemUtils.isEmpty(seed)) return;
            panicle = panicle.copyWithCount(5); seed = seed.copyWithCount(4);
            if (!unchanged(block, entity, original)) return;
            EntityChangeBlockEvent event = new EntityChangeBlockEvent(entity, block, Bukkit.createBlockData(Material.AIR));
            if (!event.callEvent() || !authorized(block, entity) || !unchanged(block, entity, original)) return;
            // The source explicitly destroys without its normal loot, then drops these two stacks.
            if (!CraftEngineBlocks.remove(block, false) || !block.isEmpty()) return;
            var world = BukkitAdaptor.adapt(block.getWorld());
            Vec3d center = new Vec3d(block.getX() + .5, block.getY() + .5, block.getZ() + .5);
            world.dropItemNaturally(center, panicle);
            world.dropItemNaturally(center, seed);
            Particles.emit(block.getWorld(), Particle.CAMPFIRE_COSY_SMOKE, center.x(), center.y(), center.z(),
                    10, .1, .1, .1, .05, null);
        } finally { ACTIVE.remove(block); }
    }
    private static boolean unchanged(Block block, LivingEntity entity, ImmutableBlockState expected) {
        return owned(block, entity) && CraftEngineBlocks.getCustomBlockState(block) == expected;
    }
    private static boolean authorized(Block block, LivingEntity entity) {
        if (!owned(block, entity)) return false;
        return !(entity instanceof Player player) || player.getGameMode() != GameMode.SPECTATOR
                && InteractGuard.canBreak(BukkitAdaptor.adapt(player), BukkitAdaptor.adapt(block.getWorld()),
                        block.getX(), block.getY(), block.getZ());
    }
    private static boolean owned(Block block, LivingEntity entity) {
        return entity != null && entity.isValid() && entity.getWorld() == block.getWorld()
                && Bukkit.isOwnedByCurrentRegion(entity)
                && Bukkit.isOwnedByCurrentRegion(block.getWorld(), block.getX() >> 4, block.getZ() >> 4)
                && block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }
}
