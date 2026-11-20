package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.util.CookeryBlockStateEnvelope;

import net.kaleidoscope.cookery.block.behavior.TrashCanBehavior;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.kaleidoscope.cookery.util.FoliaUtil;
import net.kaleidoscope.cookery.block.entity.render.Particles;
import net.kaleidoscope.cookery.nms.NmsBridgeProvider;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelPipeline;
import com.mojang.datafixers.util.Pair;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.element.FurnitureElement;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.furniture.tick.FurnitureTicker;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundContainerSetContentPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundContainerSetSlotPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEquipmentPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EquipmentSlotProxy;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class TrashCanController extends FurnitureController {
    private static final int SLOTS = 3;
    private static final String DATA_KEY = "kaleidoscopecookery:trashcan";

    private static final NamespacedKey RESTORE_GAMEMODE_KEY =
            new NamespacedKey("kaleidoscopecookeryplugin", "trashcan_restore_gamemode");

    private static final Map<BlockKey, TrashCanController> BY_BLOCK = new ConcurrentHashMap<>();

    private record BlockKey(UUID world, int x, int y, int z) {}
    private static final Map<UUID, TrashCanController> BY_OCCUPANT = new ConcurrentHashMap<>();

    private static final String TELEPORT_BLOCKER = "kaleidoscopecookery_trashcan_spectate";

    private static final double CAMERA_EYE_Y = 0.9;
    private static final float VIEW_PITCH = 0f;

    // 玩家自己的背包容器 id 恒为 0 头盔在该容器的第 5 槽
    private static final int PLAYER_INVENTORY_CONTAINER_ID = 0;
    private static final int HELMET_SLOT = 5;
    private static final String HELMET_REWRITER = "kaleidoscopecookery_trashcan_helmet";
    // 重发间隔 客户端预测性挪走假头盔后最多这么久遮罩就回来
    private static final int HELMET_RESEND_INTERVAL = 10;

    private final TrashCanElement element;
    private final Item[] storage = new Item[SLOTS];
    private final int srcChunkX;
    private final int srcChunkZ;
    private boolean animating;
    private boolean interacting, removed, unloaded;
    private final TrashCanAnimation animation = new TrashCanAnimation();
    private long animationTick;
    private TrashCanAnimation.Pose lastPose = TrashCanAnimation.Pose.REST;
    private CompoundTag rejectedState;
    // 是否有玩家进入桶内 占用时禁止投放取出 桶内掉落物隐藏
    private boolean occupied;
    private UUID occupantId;
    // 占用代次 每次进入自增 待机调度任务带着进入时的代次 代次变了就失效 防止旧任务在快速换人后乱发
    private int occupancyId;
    // 进入前的状态 退出时还原
    private GameMode previousGameMode;
    // 固定视角用的相机实体 玩家旁观它 视角与位置都被锁住
    private Entity camera;
    private final TrashCanBehavior behavior;
    // 占用期的假头盔恒定 进桶时算一次 别每 10 tick 重建一个物品
    private ItemStack helmetCache;

    public TrashCanController(Furniture furniture, TrashCanBehavior behavior) {
        super(furniture);
        Arrays.fill(this.storage, Item.empty());
        WorldPosition pos = furniture.position();
        this.behavior = behavior;
        this.srcChunkX = ((int) Math.floor(pos.x)) >> 4;
        this.srcChunkZ = ((int) Math.floor(pos.z)) >> 4;
        this.element = new TrashCanElement(this, pos);
    }

    public Item[] storage() {
        return TrashCanInventory.copy(storage);
    }

    public boolean isAnimating() {
        return animating;
    }

    public boolean isOccupied() {
        return occupied;
    }

    public boolean isCamera(Entity entity) {
        return camera != null && camera.equals(entity);
    }

    public float facingDegrees() {
        return furniture().position().yRot();
    }

    @Override
    public InteractionResult useOnFurniture(FurnitureHitBox hitBox, InteractEntityContext context) {
        Player player = context.getPlayer();
        if (player == null || context.getHand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!accessible(player)) return InteractionResult.FAIL;
        Item itemInHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (itemInHand.isEmpty()) {
            if (!player.isSecondaryUseActive() || !withdrawItem(player, InteractionHand.MAIN_HAND)) {
                return InteractionResult.PASS;
            }
        } else if (!insertItem(itemInHand, player)) {
            return InteractionResult.FAIL;
        }
        player.swingHand(InteractionHand.MAIN_HAND);
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    private boolean usable() {
        WorldPosition pos = furniture().position();
        return !removed && !unloaded && rejectedState == null && furniture().isValid()
                && Bukkit.isOwnedByCurrentRegion((World) pos.world().platformWorld(), srcChunkX, srcChunkZ);
    }

    private boolean accessible(Player player) {
        if (player == null || interacting || !usable()) return false;
        var bukkit = (org.bukkit.entity.Player) player.platformPlayer();
        return bukkit != null && bukkit.getGameMode() != GameMode.SPECTATOR && Bukkit.isOwnedByCurrentRegion(bukkit)
                && InteractGuard.canInteract(player, furniture().position());
    }

    // Compatibility entry point; creative players also pay, matching upstream.
    public void putItem(Item held, Player player) {
        insertItem(held, player);
    }

    public boolean insertItem(Item held, Player player) {
        if (!accessible(player) || ItemUtils.isEmpty(held)) return false;
        interacting = true;
        try {
            Item expected = held.copy();
            TrashCanInventory.Insert plan = TrashCanInventory.insert(storage, expected);
            validateSave(plan.slots());
            Item current = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!usable() || current.isEmpty() || current.count() != expected.count() || !current.isSimilar(expected)) return false;
            player.setItemInHand(InteractionHand.MAIN_HAND, current.copyWithCount(current.count() - plan.consumed()));
            replace(plan.slots());
            playEffects("minecraft:block.barrel.open", .5f);
            playAnimation(TrashCanAnimation.Clip.PUT);
            return true;
        } finally { interacting = false; }
    }

    // 潜行右键倒空 内容直接销毁不返还 空桶返回 false 让调用方 PASS
    public boolean clearStorage() {
        if (!usable() || interacting) return false;
        boolean any = false;
        for (int i = 0; i < SLOTS; i++) {
            if (!storage[i].isEmpty()) {
                storage[i] = Item.empty();
                any = true;
            }
        }
        if (!any) {
            return false;
        }
        element.refreshItemsAndBroadcast();
        playEffects("minecraft:block.barrel.close", 0.6f);
        playAnimation(TrashCanAnimation.Clip.WITHDRAW);
        furniture().setUnsaved();
        return true;
    }

    public boolean withdrawItem(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !accessible(player)
                || !player.getItemInHand(hand).isEmpty()) return false;
        interacting = true;
        try {
            for (int i = SLOTS - 1; i >= 0; i--) {
                if (storage[i].isEmpty()) continue;
                Item taken = storage[i].copy();
                // Main hand is empty: no inventory merge, drop callback, or loss when full.
                player.setItemInHand(hand, taken);
                storage[i] = Item.empty();
                changed();
                playEffects("minecraft:block.barrel.close", .8f);
                playAnimation(TrashCanAnimation.Clip.WITHDRAW);
                return true;
            }
            return false;
        } finally { interacting = false; }
    }

    private static void validateSave(Item[] slots) {
        CompoundTag root = new CompoundTag();
        CookeryBlockStateEnvelope.put(root, DATA_KEY, TrashCanInventory.write(slots));
        // CE's own encoder rejects payloads that cannot fit its persistent envelope.
        try { net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData.of(root).toBytes(); }
        catch (java.io.IOException error) { throw new IllegalArgumentException("Cannot persist trash can contents", error); }
    }

    private void replace(Item[] next) {
        System.arraycopy(next, 0, storage, 0, SLOTS);
        changed();
    }

    private void changed() {
        furniture().setUnsaved();
        element.refreshItemsAndBroadcast();
    }

    private void playAnimation(TrashCanAnimation.Clip clip) {
        animationTick = ((World) furniture().position().world().platformWorld()).getGameTime();
        animation.start(clip, animationTick);
        animating = true;
        updateAnimationPose();
    }

    private void tickAnimation() {
        if (!usable()) return;
        animationTick = ((World) furniture().position().world().platformWorld()).getGameTime();
        WorldPosition position = furniture().position();
        int hash = ((int) Math.floor(position.y) + (int) Math.floor(position.z) * 31) * 31
                + (int) Math.floor(position.x);
        if (occupied && Math.floorMod(animationTick + hash, 61) == 0)
            animation.start(ThreadLocalRandom.current().nextBoolean() ? TrashCanAnimation.Clip.PLAYER1
                    : TrashCanAnimation.Clip.PLAYER2, animationTick);
        if (!occupied && Math.floorMod(animationTick + hash, 5) == 0) animation.stopIdle();
        animating = animation.moving(animationTick);
        updateAnimationPose();
    }

    private void updateAnimationPose() {
        TrashCanAnimation.Pose next = animation.pose(animationTick);
        if (next.equals(lastPose)) return;
        lastPose = next;
        // Current trackers and later arrivals must see the same pose. Sampling at
        // 20 Hz preserves source times/curves at tick boundaries; clients interpolate.
        element.applyPose(next, furniture().trackedBy(), 1);
    }

    private void resetAnimation() {
        animation.clear();
        animating = false;
        lastPose = TrashCanAnimation.Pose.REST;
        element.applyPose(lastPose, furniture().trackedBy(), 0);
    }

    private void playEffects(String sound, float pitch) {
        Location loc = topCenter();
        Particles.emit(loc.getWorld(), Particle.SMOKE, loc.getX(), loc.getY(), loc.getZ(), 3, 0.2, 0.05, 0.2, 0.01, null);
        loc.getWorld().playSound(loc, sound, SoundCategory.BLOCKS, 1.0f, pitch);
    }

    private Location topCenter() {
        WorldPosition p = furniture().position();
        return new Location((World) p.world().platformWorld(), p.x, p.y + 1.25, p.z);
    }

    // 进入桶内 旁观相机锁视角 戴南瓜遮罩 隐藏掉落物
    public void enter(org.bukkit.entity.Player player) {
        if (!usable() || interacting || occupied || animating || !canHide(player)
                || player.getGameMode() == GameMode.SPECTATOR || !Bukkit.isOwnedByCurrentRegion(player)) {
            return;
        }
        // 掉入也要校验 别让没有交互权限的玩家靠跳进去占用别人领地里的桶
        if (!InteractGuard.canInteract(BukkitAdaptor.adapt(player), furniture().position())) {
            return;
        }
        WorldPosition p = furniture().position();
        World world = (World) p.world().platformWorld();
        int bx = (int) Math.floor(p.x);
        int by = (int) Math.floor(p.y);
        int bz = (int) Math.floor(p.z);
        Location camLoc = new Location(world,
                bx + 0.5, by + CAMERA_EYE_Y, bz + 0.5, facingDegrees(), VIEW_PITCH);

        this.previousGameMode = player.getGameMode();
        markRestoreState(player, previousGameMode);
        this.occupied = true;
        int gen = ++this.occupancyId;
        this.occupantId = player.getUniqueId();
        BY_OCCUPANT.put(occupantId, this);

        ItemDisplay display = world.spawn(camLoc, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setGravity(false);
        });
        this.camera = display;

        player.setGameMode(GameMode.SPECTATOR);
        // 旁观下客户端点背包不发包给服务端 服务端不知道也就不发纠正包
        // 这种纯客户端的错位只能靠定时重发盖回去
        addHelmetRewriter(player);
        scheduleHelmetResend(gen, player);
        Entity cam = this.camera;
        addTeleportBlocker(player);
        FoliaUtil.runLater(() -> {
            if (occupied && player.isOnline() && cam.isValid()) {
                player.setSpectatorTarget(cam);
            }
        }, null, 1L, player);

        element.setItemsHidden(true);
        clearNearbyHostility(player);
        playAnimation(TrashCanAnimation.Clip.ENTER);
    }

    // 进桶头盔
    private ItemStack helmetStack() {
        if (this.helmetCache == null) {
            Item item = InventoryUtils.createOrEmpty(this.behavior.helmetItem);
            this.helmetCache = ItemUtils.isEmpty(item)
                    ? new ItemStack(Material.CARVED_PUMPKIN)
                    : ItemStackUtils.getBukkitStack(item);
        }
        return this.helmetCache;
    }

    // 躲桶是否开放 关掉后落到桶上只是普通落地
    public boolean canHide(org.bukkit.entity.Player player) {
        if (!this.behavior.allowHiding) {
            return false;
        }
        String permission = this.behavior.hidePermission;
        return permission.isEmpty() || player.hasPermission(permission);
    }

    // 只发包不写真实装备栏 写真的会被 /hat 带走 且崩溃时跟着背包落盘
    // 南瓜遮罩由客户端读自己背包的头盔槽渲染 必须发容器槽位包 装备包另发一份让旁人可见
    private void scheduleHelmetResend(int gen, org.bukkit.entity.Player player) {
        sendFakeHelmet(player, helmetStack());
        FoliaUtil.runLater(
                () -> occupancyTick(gen, player), null, HELMET_RESEND_INTERVAL, player);
    }

    private void occupancyTick(int gen, org.bukkit.entity.Player player) {
        if (!occupied || gen != occupancyId || !player.isOnline()) {
            return;
        }
        // 相机可能被 /kill 或别的插件清掉 一旦没了玩家就永久留在无锁定的自由旁观 能穿墙透视
        // 占用期只有这一条循环在跑 自检就挂在它身上
        if (!isSpectatingCamera(player)) {
            exit();
            return;
        }
        sendFakeHelmet(player, helmetStack());
        FoliaUtil.runLater(
                () -> occupancyTick(gen, player), null, HELMET_RESEND_INTERVAL, player);
    }

    private boolean isSpectatingCamera(org.bukkit.entity.Player player) {
        Entity cam = this.camera;
        return cam != null && cam.isValid()
                && player.getGameMode() == GameMode.SPECTATOR
                && player.getSpectatorTarget() == cam;
    }

    private static void sendFakeHelmet(org.bukkit.entity.Player player, ItemStack helmet) {
        Object nmsHelmet = ItemStackUtils.unwrap(helmet);
        Player cePlayer = BukkitAdaptor.adapt(player);
        cePlayer.sendPacket(ClientboundContainerSetSlotPacketProxy.INSTANCE.newInstance(
                PLAYER_INVENTORY_CONTAINER_ID, 0, HELMET_SLOT, nmsHelmet), false);
        cePlayer.sendPacket(ClientboundSetEquipmentPacketProxy.INSTANCE.newInstance(
                player.getEntityId(), List.of(new Pair<>(EquipmentSlotProxy.HEAD, nmsHelmet))), false);
    }

    // 还原成玩家真实装备栏里的头盔 假包发的东西没有真实状态 重发真实值即可
    private static void clearFakeHelmet(org.bukkit.entity.Player player) {
        sendFakeHelmet(player, player.getInventory().getHelmet());
    }


    // 旁观传送(teleport_to_entity)不触发 PlayerTeleportEvent 在玩家管线上挂一道 netty 拦截 直接丢弃该包 进桶挂上 退出摘掉
    private void addTeleportBlocker(org.bukkit.entity.Player player) {
        Channel channel = BukkitNetworkManager.instance().getChannel(player);
        if (channel == null) {
            return;
        }
        runOnChannel(channel, () -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(TELEPORT_BLOCKER) != null || pipeline.get("packet_handler") == null) {
                return;
            }
            pipeline.addBefore("packet_handler", TELEPORT_BLOCKER, new SpectateTeleportBlocker());
        });
    }

    private void removeTeleportBlocker(org.bukkit.entity.Player player) {
        Channel channel = BukkitNetworkManager.instance().getChannel(player);
        if (channel == null) {
            return;
        }
        runOnChannel(channel, () -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(TELEPORT_BLOCKER) != null) {
                pipeline.remove(TELEPORT_BLOCKER);
            }
        });
    }

    private static void runOnChannel(Channel channel, Runnable task) {
        if (channel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            channel.eventLoop().execute(task);
        }
    }

    // 包发出前就替换成假头盔
    private void addHelmetRewriter(org.bukkit.entity.Player player) {
        Channel channel = BukkitNetworkManager.instance().getChannel(player);
        if (channel == null) {
            return;
        }
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(HELMET_REWRITER) != null) {
            return;
        }
        if (pipeline.get("packet_handler") == null) {
            return;
        }
        pipeline.addBefore("packet_handler", HELMET_REWRITER,
                new FakeHelmetRewriter(player.getEntityId(), ItemStackUtils.unwrap(helmetStack())));
    }

    private void removeHelmetRewriter(org.bukkit.entity.Player player) {
        Channel channel = BukkitNetworkManager.instance().getChannel(player);
        if (channel == null) {
            return;
        }
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(HELMET_REWRITER) != null) {
            pipeline.remove(HELMET_REWRITER);
        }
    }

    private static final class FakeHelmetRewriter extends ChannelOutboundHandlerAdapter {
        private final int playerEntityId;
        // 占用期间头盔恒定 构造时算一次 别在每个出站包上重建物品
        private final Object fakeHelmet;

        private FakeHelmetRewriter(int playerEntityId, Object fakeHelmet) {
            this.playerEntityId = playerEntityId;
            this.fakeHelmet = fakeHelmet;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Object rewritten;
            try {
                rewritten = rewrite(msg);
            } catch (Throwable t) {
                rewritten = msg;
            }
            super.write(ctx, rewritten, promise);
        }

        private Object rewrite(Object msg) {
            if (ClientboundContainerSetSlotPacketProxy.CLASS.isInstance(msg)) {
                return rewriteSlot(msg);
            }
            // 玩家点背包时服务端一次性下发整个容器 不走单槽包 漏了这类就会被真实头盔覆盖
            if (ClientboundContainerSetContentPacketProxy.CLASS.isInstance(msg)) {
                return rewriteContent(msg);
            }
            if (ClientboundSetEquipmentPacketProxy.CLASS.isInstance(msg)) {
                return rewriteEquipment(msg);
            }
            return msg;
        }

        private Object rewriteContent(Object msg) {
            ClientboundContainerSetContentPacketProxy proxy = ClientboundContainerSetContentPacketProxy.INSTANCE;
            int container = proxy.getContainerId(msg);
            List<Object> items = proxy.getItems(msg);
            if (container != PLAYER_INVENTORY_CONTAINER_ID || items.size() <= HELMET_SLOT) {
                return msg;
            }
            List<Object> copy = new ArrayList<>(items);
            copy.set(HELMET_SLOT, fakeHelmet);
            return proxy.newInstance(container, proxy.getStateId(msg), copy, proxy.getCarriedItem(msg));
        }

        // 玩家自己的背包容器 只改头盔那一槽 其余原样放行
        private Object rewriteSlot(Object msg) {
            ClientboundContainerSetSlotPacketProxy proxy = ClientboundContainerSetSlotPacketProxy.INSTANCE;
            int container = proxy.getContainerId(msg);
            int slot = proxy.getSlot(msg);
            if (container != PLAYER_INVENTORY_CONTAINER_ID || slot != HELMET_SLOT) {
                return msg;
            }
            return proxy.newInstance(PLAYER_INVENTORY_CONTAINER_ID, proxy.getStateId(msg), HELMET_SLOT, fakeHelmet);
        }

        // 只改这个玩家自己的头部装备 别动其他实体
        private Object rewriteEquipment(Object msg) {
            ClientboundSetEquipmentPacketProxy proxy = ClientboundSetEquipmentPacketProxy.INSTANCE;
            if (proxy.getEntityId(msg) != playerEntityId) {
                return msg;
            }
            List<Pair<Object, Object>> slots = new ArrayList<>(proxy.getSlots(msg));
            boolean changed = false;
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i).getFirst() == EquipmentSlotProxy.HEAD) {
                    slots.set(i, new Pair<>(EquipmentSlotProxy.HEAD, fakeHelmet));
                    changed = true;
                }
            }
            return changed ? proxy.newInstance(playerEntityId, slots) : msg;
        }
    }

    private static final class SpectateTeleportBlocker extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (NmsBridgeProvider.bridge().isSpectatePacket(msg)) {
                return;
            }
            super.channelRead(ctx, msg);
        }
    }

    // 退出垃圾桶 解除相机 还原模式与头盔 传送回桶顶 恢复交互与掉落物显示
    public void exit() {
        exit(false);
    }

    // shutdown=true 为关服路径 只还原玩家关键状态与相机 不做传送和渲染回发 避免关闭阶段的多余/不稳定操作
    private void exit(boolean shutdown) {
        if (FoliaUtil.isFolia() && occupantId != null) {
            org.bukkit.entity.Player player = Bukkit.getPlayer(occupantId);
            if (player != null) {
                FoliaUtil.runEntity(player, () -> exitOwned(shutdown));
                return;
            }
        }
        exitOwned(shutdown);
    }

    private void exitOwned(boolean shutdown) {
        if (!occupied) {
            return;
        }
        this.occupied = false;
        this.occupancyId++;
        UUID id = this.occupantId;
        this.occupantId = null;
        // 先从登记表移除再解除相机 解除会触发停止旁观事件 此时反查为空不会重入
        if (id != null) {
            BY_OCCUPANT.remove(id);
            org.bukkit.entity.Player bp = Bukkit.getPlayer(id);
            if (bp != null) {
                removeTeleportBlocker(bp);
                bp.setSpectatorTarget(null);
                // 必须先摘改写器 否则还原包会被自己改写回假头盔
                removeHelmetRewriter(bp);
                clearFakeHelmet(bp);
                GameMode restoreMode = previousGameMode;
                // 传送落地后再切回游戏模式 否则旁观切生存与传送竞态 玩家会卡进方块
                // 传送被拒时不执行 PDC 标记保留 下次登录由 restoreIfCrashed 兜底
                Runnable restore = () -> {
                    if (restoreMode != null) {
                        bp.setGameMode(restoreMode);
                    }
                    clearRestoreState(bp);
                };
                if (shutdown) {
                    restore.run();
                } else {
                    WorldPosition p = furniture().position();
                    Location out = new Location((World) p.world().platformWorld(),
                            Math.floor(p.x) + 0.5, Math.floor(p.y) + 1.0, Math.floor(p.z) + 0.5,
                            bp.getLocation().getYaw(), bp.getLocation().getPitch());
                    FoliaUtil.teleportThen(bp, out, restore);
                }
            }
        }
        if (camera != null) {
            if (camera.isValid()) {
                camera.remove();
            }
            camera = null;
        }
        if (!shutdown) {
            // 还原盖子与眼睛到静止姿势
            resetAnimation();
            element.setItemsHidden(false);
        }
        this.previousGameMode = null;
    }

    // 头盔是发包的假物品 不落盘也不需要还原 只有游戏模式是真改了玩家状态
    private static void markRestoreState(org.bukkit.entity.Player player, GameMode gameMode) {
        player.getPersistentDataContainer().set(RESTORE_GAMEMODE_KEY, PersistentDataType.STRING, gameMode.name());
    }

    private static void clearRestoreState(org.bukkit.entity.Player player) {
        player.getPersistentDataContainer().remove(RESTORE_GAMEMODE_KEY);
    }

    // 硬崩溃后重新登录时的兜底 据持久化数据把卡在旁观的玩家还原 正常退出已即时还原并清标记 不会进这里
    // 假头盔不落盘 重连时客户端自然拿到真实装备 无需处理
    public static void restoreIfCrashed(org.bukkit.entity.Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        String gameModeName = pdc.get(RESTORE_GAMEMODE_KEY, PersistentDataType.STRING);
        if (gameModeName == null) {
            return;
        }
        player.setSpectatorTarget(null);
        try {
            player.setGameMode(GameMode.valueOf(gameModeName));
        } catch (IllegalArgumentException ignored) {
        }
        clearRestoreState(player);
    }

    // 在桶里死亡后点重生 松开桶的相机/拦截/占用 再据 PDC 还原游戏模式+头盔 让原版重生点与生存模式生效
    public static void handleRespawn(org.bukkit.entity.Player player) {
        TrashCanController c = BY_OCCUPANT.remove(player.getUniqueId());
        boolean marked = player.getPersistentDataContainer().has(RESTORE_GAMEMODE_KEY, PersistentDataType.STRING);
        if (c != null) {
            c.detachForRespawn(player);
        }
        if (c == null && !marked) {
            return;
        }
        // 延后一 tick 待重生完成再还原游戏模式 否则可能被重生逻辑盖掉
        // 重生会换位置 必须走实体调度器 按坐标排会落到旧 region
        FoliaUtil.runLater(
                () -> restoreIfCrashed(player), null, 1L, player);
    }

    // 只松开占用/相机/拦截 不传送不改模式(交给上面延后的 restoreIfCrashed) 先移除登记再解旁观避免重入
    private void detachForRespawn(org.bukkit.entity.Player player) {
        this.occupied = false;
        this.occupancyId++;
        this.occupantId = null;
        removeTeleportBlocker(player);
        player.setSpectatorTarget(null);
        removeHelmetRewriter(player);
        clearFakeHelmet(player);
        if (camera != null) {
            if (camera.isValid()) {
                camera.remove();
            }
            camera = null;
        }
        resetAnimation();
        element.setItemsHidden(false);
        this.previousGameMode = null;
    }

    // onDisable 路径 只做纯内存清理 folia 关服时 region 调度器已在停止流程 排还原任务等于没排
    // 旁观态与游戏模式交给 PDC 标记 下次登录由 restoreIfCrashed 兜底
    public static void releaseAll() {
        for (TrashCanController c : new ArrayList<>(BY_OCCUPANT.values())) {
            if (FoliaUtil.isFolia()) {
                c.detachOnShutdown();
            } else {
                c.exit(true);
            }
        }
        BY_OCCUPANT.clear();
        BY_BLOCK.clear();
    }

    // 只松开占用与相机 不碰玩家状态 PDC 标记保留给下次登录兜底
    private void detachOnShutdown() {
        this.occupied = false;
        this.occupancyId++;
        this.occupantId = null;
        if (camera != null) {
            if (camera.isValid()) {
                camera.remove();
            }
            camera = null;
        }
    }

    // 进桶即安全 仅 paper 走这条即时清仇恨 folia 的 getNearbyEntities 跨 region 会抛
    // folia 改由 TrashCanListener.onTarget 拦索敌 差别只是已锁定的怪要下次索敌才掉仇恨
    private static final double HOSTILITY_CLEAR_RADIUS = 32;

    private void clearNearbyHostility(org.bukkit.entity.Player player) {
        if (FoliaUtil.isFolia()) {
            return;
        }
        for (Entity e : player.getWorld().getNearbyEntities(
                player.getLocation(), HOSTILITY_CLEAR_RADIUS, HOSTILITY_CLEAR_RADIUS, HOSTILITY_CLEAR_RADIUS)) {
            if (e instanceof Mob mob && mob.getTarget() == player) {
                mob.setTarget(null);
            }
        }
    }

    // 落地进桶 落地事件在移动处理中触发 延后一 tick 再切模式与传送 避免在下落步骤里传送玩家
    // landing 单独传入 移动包路径下玩家实体还停在落地前的位置 不能拿 getLocation
    public static void tryEnterOnLand(org.bukkit.entity.Player player, Location landing) {
        TrashCanController ctrl = findUnder(landing);
        if (ctrl == null || ctrl.isOccupied() || ctrl.isAnimating() || !ctrl.canHide(player)) {
            return;
        }
        FoliaUtil.runLater(() -> {
            if (!ctrl.isOccupied() && !ctrl.isAnimating() && player.isOnline()) {
                ctrl.enter(player);
            }
        }, null, 1L, player);
    }

    // 按玩家脚下方块查询唯一垃圾桶 脚下方块或其下一格 检测到多个则返回 null 不进入
    public static TrashCanController findUnder(Location loc) {
        UUID world = loc.getWorld().getUID();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        TrashCanController here = BY_BLOCK.get(new BlockKey(world, bx, by, bz));
        TrashCanController below = BY_BLOCK.get(new BlockKey(world, bx, by - 1, bz));
        if (here != null && below != null && here != below) {
            return null;
        }
        return here != null ? here : below;
    }

    public static TrashCanController byOccupant(UUID playerId) {
        return BY_OCCUPANT.get(playerId);
    }

    private BlockKey myBlockKey() {
        WorldPosition p = furniture().position();
        UUID world = ((World) p.world().platformWorld()).getUID();
        return new BlockKey(world, (int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends FurnitureController> FurnitureTicker<T> createFurnitureTicker() {
        return (FurnitureTicker<T>) (FurnitureTicker<TrashCanController>) (furniture, controller) -> { controller.tickAnimation(); controller.tickFilter(); };
    }

    /** Source suction zone: the top 1/16 block, enabled only by neighbor power. */
    public void tickFilter() {
        if (!usable() || interacting) return;
        WorldPosition pos = furniture().position();
        World world = (World) pos.world().platformWorld();
        int x = (int) Math.floor(pos.x), y = (int) Math.floor(pos.y), z = (int) Math.floor(pos.z);
        // Entity bounds and neighbor signal reads may touch adjacent chunks.
        for (int cx = (x - 1) >> 4; cx <= (x + 1) >> 4; cx++)
            for (int cz = (z - 1) >> 4; cz <= (z + 1) >> 4; cz++)
                if (!Bukkit.isOwnedByCurrentRegion(world, cx, cz)) return;
        if (!world.getBlockAt(x, y, z).isBlockPowered() && !world.getBlockAt(x, y, z).isBlockIndirectlyPowered()) return;
        var zone = new org.bukkit.util.BoundingBox(x, y + 15.0 / 16, z, x + 1, y + 1, z + 1);
        for (Entity entity : world.getNearbyEntities(zone)) {
            if (!(entity instanceof org.bukkit.entity.Item dropped) || !entity.isValid()
                    || !Bukkit.isOwnedByCurrentRegion(entity) || !zone.overlaps(entity.getBoundingBox())) continue;
            filterItem(dropped);
        }
    }

    private void filterItem(org.bukkit.entity.Item entity) {
        if (interacting || !usable()) return;
        interacting = true;
        try {
            ItemStack expected = entity.getItemStack().clone();
            Item[] next = TrashCanInventory.filter(storage, InventoryUtils.wrap(expected));
            if (next == null) return;
            validateSave(next);
            if (!usable() || !entity.isValid() || !expected.equals(entity.getItemStack())) return;
            Location at = entity.getLocation();
            entity.remove();
            if (entity.isValid()) return;
            replace(next);
            Particles.emit(at.getWorld(), Particle.CLOUD, at.getX(), at.getY() + entity.getHeight(), at.getZ(),
                    1, .1, .1, .1, .01, null);
        } finally { interacting = false; }
    }

    @Override
    public void gatherElements(Consumer<FurnitureElement> consumer) {
        consumer.accept(element);
    }

    private void unregisterFromBlock() {
        BY_BLOCK.remove(myBlockKey(), this);
    }

    @Override
    public void onLoad() {
        unloaded = false;
        animation.clear();
        BY_BLOCK.put(myBlockKey(), this);
        element.rebuildPackets();
        resetAnimation();
    }

    @Override
    public void onUnload() {
        unloaded = true;
        animation.clear();
        animating = false;
        unregisterFromBlock();
        if (occupied && !CraftEngine.instance().isStopping()) {
            exit();
        }
    }

    @Override
    public void preRemove(Player player) {
        unregisterFromBlock();
        unloaded = true;
        animation.clear();
        if (occupied) {
            exit();
        }
    }

    @Override
    public void postRemove(Player player) {
        if (removed) return;
        removed = true;
        if (rejectedState != null || interacting) { recoverContents(); return; }
        interacting = true;
        World world = (World) furniture().position().world().platformWorld();
        WorldPosition p = furniture().position();
        Location dropLoc = new Location(world, p.x, p.y + 0.5, p.z);
        try {
            for (int i = 0; i < SLOTS; i++) {
                if (storage[i].isEmpty()) continue;
                ItemStack expected = InventoryUtils.bukkitCopy(storage[i]);
                var drop = world.dropItemNaturally(dropLoc, expected.clone());
                if (!drop.isValid() || !expected.equals(drop.getItemStack())) {
                    if (drop.isValid()) drop.remove();
                    break;
                }
                storage[i] = Item.empty();
            }
        } finally {
            interacting = false;
            if (Arrays.stream(storage).anyMatch(item -> !item.isEmpty())) recoverContents();
        }
    }

    private void recoverContents() {
        CompoundTag saved = new CompoundTag(); saveCustomData(saved);
        WorldPosition pos = furniture().position();
        saved.putString("recovery_world", ((World) pos.world().platformWorld()).getUID().toString());
        saved.putString("recovery_position", pos.x + "," + pos.y + "," + pos.z);
        saved.putString("recovery_furniture", furniture().id().asString());
        var plugin = KaleidoscopeCookeryPlugin.instance();
        var file = plugin.getDataFolder().toPath().resolve("recovery").resolve("trash-can-" + UUID.randomUUID() + ".nbt");
        try {
            byte[] bytes = net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData.of(saved).toBytes();
            java.nio.file.Files.createDirectories(file.getParent());
            try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot preserve trash can contents " + file, error); }
        plugin.getLogger().warning("垃圾桶移除后仍有未发放内容，已保存至 " + file + "；请核对后人工恢复。");
    }

    @Override
    public void saveCustomData(CompoundTag tag) {
        if (rejectedState != null) {
            for (String key : new String[]{DATA_KEY, CookeryBlockStateEnvelope.sidecar(DATA_KEY)}) {
                Tag original = rejectedState.get(key);
                if (original == null) tag.remove(key); else tag.put(key, original.deepClone());
            }
            return;
        }
        CookeryBlockStateEnvelope.put(tag, DATA_KEY, TrashCanInventory.write(storage));
    }

    @Override
    public void loadCustomData(CompoundTag tag) {
        try {
            if (tag.containsKey(DATA_KEY) && !(tag.get(DATA_KEY) instanceof CompoundTag))
                throw new IllegalArgumentException("Invalid trash can payload");
            Item[] restored = TrashCanInventory.read(CookeryBlockStateEnvelope.payload(tag, DATA_KEY));
            System.arraycopy(restored, 0, storage, 0, SLOTS);
            rejectedState = null;
            element.refreshItems();
        } catch (RuntimeException error) {
            rejectedState = tag.deepClone();
            throw error;
        }
    }
}
