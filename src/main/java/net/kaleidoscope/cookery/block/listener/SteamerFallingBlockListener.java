package net.kaleidoscope.cookery.block.listener;
import net.kaleidoscope.cookery.util.FoliaUtil;
import net.kaleidoscope.cookery.block.entity.SteamerWorld;
import net.kaleidoscope.cookery.nms.NmsBridgeProvider;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import net.kaleidoscope.cookery.block.behavior.SteamerBehavior;
import net.kaleidoscope.cookery.util.HeatSourceUtils;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.bukkit.world.BukkitWorld;
import net.momirealms.craftengine.bukkit.world.BukkitWorldManager;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.entity.CraftEntityProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SteamerFallingBlockListener implements Listener {

    private static final Set<Object> ceCancelledEntities = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityChangeBlockLowest(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock fb)) return;
        Object nmsEntity = getNmsHandle(fb);
        if (nmsEntity == null || !SteamerBehavior.pendingData.containsKey(nmsEntity)) return;
        // 蒸笼底层是重力方块 落地变方块时绝不能掉落原版方块物品
        fb.setDropItem(false);
        // 标记为待处理 无论此刻是否已被其它插件取消 都在 HIGHEST 阶段统一裁决 避免落地丢失
        ceCancelledEntities.add(nmsEntity);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock fb)) return;
        Object nmsEntity = getNmsHandle(fb);
        if (nmsEntity == null) return;
        // 只处理带蒸笼标记的下落实体
        if (!ceCancelledEntities.remove(nmsEntity)) return;
        SteamerBehavior.PendingData data = SteamerBehavior.pendingData.get(nmsEntity);
        if (data == null) return;

        Block block = event.getBlock();
        BukkitWorld world = BukkitAdaptor.adapt(block.getWorld());
        if (world == null) return;
        CEWorld ceWorld = world.storageWorld();
        Object level = ceWorld.world().minecraftWorld();
        Object landingPos = LocationUtils.toBlockPos(block.getX(), block.getY(), block.getZ());
        Object belowPos = LocationUtils.toBlockPos(block.getX(), block.getY() - 1, block.getZ());

        if (!SteamerWorld.landingSupported(level, belowPos)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCancelledLanding(EntityChangeBlockEvent event) {
        if (!event.isCancelled() || !(event.getEntity() instanceof FallingBlock fb)) return;
        Object handle = getNmsHandle(fb);
        if (handle == null || !SteamerBehavior.pendingData.containsKey(handle)) return;
        Block block = event.getBlock();
        BukkitWorld world = BukkitAdaptor.adapt(block.getWorld());
        if (world == null) return;
        // Preserve other plugins' cancellation, transfer the saved contents once, then retire the falling entity.
        SteamerBehavior.dropPendingSteamer(world.minecraftWorld(), LocationUtils.toBlockPos(block.getX(), block.getY(), block.getZ()), handle);
        fb.remove();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof FallingBlock fb)) return;
        Object nmsEntity = getNmsHandle(fb);
        if (nmsEntity == null) return;
        ceCancelledEntities.remove(nmsEntity);
        // pendingData 仍在 表示既没成功落地 也没被落地事件处理
        // 此时 CE 的 onLand 与 onBrokenAfterFall 都不会触发 且 dropItem 已关闭 必须手动掉落以免凭空消失
        if (!SteamerBehavior.pendingData.containsKey(nmsEntity)) return;
        Location loc = fb.getLocation();
        World bukkitWorld = loc.getWorld();
        BukkitWorld world = bukkitWorld == null ? null : BukkitAdaptor.adapt(bukkitWorld);
        CEWorld ceWorld = world == null ? null : world.storageWorld();
        if (ceWorld == null) {
            SteamerBehavior.pendingData.remove(nmsEntity);
            return;
        }
        Object level = ceWorld.world().minecraftWorld();
        Object blockPos = LocationUtils.toBlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        // 延迟一 tick 再掉落 成功落地时 onLand 会在本 tick 内清掉 pendingData
        // 下一 tick 若仍在 pendingData 才说明确实丢失 避免与正常落地重复掉落
        FoliaUtil.runLater(() -> {
            if (SteamerBehavior.pendingData.containsKey(nmsEntity)) {
                SteamerBehavior.dropPendingSteamer(level, blockPos, nmsEntity);
            }
        }, 1L, ceWorld.world(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    private static Object getNmsHandle(Entity entity) {
        return NmsBridgeProvider.bridge().nmsHandle(entity);
    }
}
