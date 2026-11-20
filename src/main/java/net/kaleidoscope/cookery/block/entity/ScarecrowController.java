package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.util.CookeryBlockStateEnvelope;

import net.kaleidoscope.cookery.api.KaleidoscopeAdvancements;

import net.kaleidoscope.cookery.block.behavior.ScarecrowBehavior;
import net.kaleidoscope.cookery.item.ItemKeys;
import net.kaleidoscope.cookery.util.ChunkIndex;
import net.kaleidoscope.cookery.util.FoliaUtil;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.kaleidoscope.cookery.util.LightBlocks;
import net.kaleidoscope.cookery.util.ScarecrowInventoryTransfer;
import net.kaleidoscope.cookery.util.FurnitureRecovery;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.element.FurnitureElement;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.furniture.tick.FurnitureTicker;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Parrot;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;

// 稻草人
public final class ScarecrowController extends FurnitureController {
    private static final String DATA_KEY = "kaleidoscopecookery:scarecrow";

    private static final ChunkIndex<Protection> INDEX = new ChunkIndex<>();

    private final Item[] items = new Item[ScarecrowElement.SLOTS];
    private final ScarecrowElement element;
    private final ScarecrowBehavior behavior;
    // 每个槽一个交互箱 命中哪个箱就是操作哪个槽 不用拿命中高度反推
    private final FurnitureHitBox[] slotHitboxes = new FurnitureHitBox[ScarecrowElement.SLOTS];

    private int tickCounter;
    private UUID perchUuid;
    private long perchSinceTick;
    private final ScarecrowHitState hitState = new ScarecrowHitState();
    private float wobbleAngle;
    private FurnitureBreakEvent approvedBreak;
    private long approvedBreakTick = Long.MIN_VALUE;
    private boolean playerBreakEffects;
    private long nextInteractTick;
    private long equipmentRevision;
    private boolean interacting, removing, unloaded;
    private boolean removalConfirmed, removalSettled, creativeRemoval;
    private CompoundTag rejectedState;
    private Protection indexedProtection;
    // 点亮的那一格 家具被旋转过后按现位置反算会清错格 所以记下点灯时那个坐标
    private BlockPos litPos;

    public ScarecrowController(Furniture furniture, ScarecrowBehavior behavior) {
        super(furniture);
        this.behavior = behavior;
        this.element = new ScarecrowElement(this, behavior);
        Arrays.fill(this.items, Item.empty());
    }

    public Item item(int slot) {
        return this.items[slot].copy();
    }

    public float wobbleAngle() { return wobbleAngle; }


    public static boolean protects(World world, int x, int y, int z) {
        return INDEX.anyMatch(world, x, z, protection -> protection.covers(x, y, z));
    }

    // 关服只做纯内存清理 region 与实体调度器不会自己清
    public static void clearIndex() {
        INDEX.clear();
    }

    // 副手挂着灯笼就在灯笼那一格放一个真的 light 方块 取下或移除时熄掉
    private void refreshLight() {
        boolean lit = ItemKeys.isLantern(this.items[ScarecrowElement.SLOT_OFF_HAND].vanillaId());
        BlockPos target = lit ? lightPos() : null;
        if (this.litPos != null && !this.litPos.equals(target)) {
            LightBlocks.clear(furniture().world(), this.litPos);
            this.litPos = null;
        }
        if (target != null && LightBlocks.set(furniture().world(), target, this.behavior.lanternLightLevel)) {
            this.litPos = target;
        }
    }

    private BlockPos lightPos() {
        return BlockPos.fromVec3d(Furniture.getRelativePosition(
                furniture().position(), this.behavior.slots[ScarecrowElement.SLOT_OFF_HAND].position()));
    }

    private void clearLight() {
        if (this.litPos != null) {
            LightBlocks.clear(furniture().world(), this.litPos);
            this.litPos = null;
        }
    }

    private void addToIndex() {
        removeFromIndex();
        WorldPosition position = furniture().position();
        World world = (World) position.world().platformWorld();
        this.indexedProtection = new Protection(
                position.x, position.y, position.z, this.behavior.protectionRadius);
        INDEX.register(this.indexedProtection, world,
                (int) Math.floor(position.x), (int) Math.floor(position.z), this.indexedProtection.indexRadius());
    }

    private void removeFromIndex() {
        if (this.indexedProtection != null) {
            INDEX.unregister(this.indexedProtection);
            this.indexedProtection = null;
        }
    }


    @Override
    public InteractionResult useOnFurniture(FurnitureHitBox hitBox, InteractEntityContext context) {
        Player player = context.getPlayer();
        if (player == null || context.getHand() != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (!accessible(player)) return InteractionResult.FAIL;
        int slot = slotOf(hitBox);
        if (slot < 0) {
            return InteractionResult.PASS;
        }
        return exchange(player, slot == ScarecrowElement.SLOT_HEAD);
    }

    private boolean usable() {
        var pos=furniture().position();
        return !removing&&!unloaded&&rejectedState==null&&furniture().isValid()
                &&Bukkit.isOwnedByCurrentRegion((World)pos.world().platformWorld(),((int)Math.floor(pos.x))>>4,((int)Math.floor(pos.z))>>4);
    }
    private boolean accessible(Player player) {
        if(player==null||interacting||!usable())return false;
        return player.platformPlayer() instanceof org.bukkit.entity.Player actor && actor.getGameMode()!=GameMode.SPECTATOR
                &&Bukkit.isOwnedByCurrentRegion(actor)&&actor.getWorld()==furniture().position().world().platformWorld()
                &&InteractGuard.canInteract(player,furniture().position());
    }

    /** The source selects the hand area as a whole; either side uses main hand first. */
    public InteractionResult exchange(Player player,boolean head) {
        if(!accessible(player))return InteractionResult.FAIL;
        Item expected=player.getItemInHand(InteractionHand.MAIN_HAND).copy();
        if(!expected.isEmpty()&&expected.vanillaId().equals(net.momirealms.craftengine.core.util.Key.of("minecraft:name_tag")))
            return InteractionResult.PASS;
        long now=((World)furniture().position().world().platformWorld()).getGameTime();
        if(now<nextInteractTick)return InteractionResult.PASS;
        // Entering either source handler starts cooldown even if the input is rejected.
        nextInteractTick=now+behavior.interactCooldownTicks;
        interacting=true;
        try {
            var plan=ScarecrowEquipment.plan(items,head,expected,!expected.isEmpty()&&isHeadItem(expected),player.canInstabuild());
            if(plan==null)return InteractionResult.PASS;
            long revision=equipmentRevision;
            Item[] next=Arrays.stream(items).map(Item::copy).toArray(Item[]::new);
            next[plan.slot()]=plan.stored();
            validateEquipment(next);
            var actor=(org.bukkit.entity.Player)player.platformPlayer();
            var inventory=actor.getInventory();
            ItemStack[] before=plan.taking()?copyStacks(inventory.getStorageContents()):null;
            ItemStack[] after=plan.taking()?ScarecrowInventoryTransfer.plan(inventory,InventoryUtils.bukkitCopy(plan.hand())):null;
            if(plan.taking()&&after==null)return InteractionResult.FAIL;
            Item held=player.getItemInHand(InteractionHand.MAIN_HAND);
            if(!usable()||equipmentRevision!=revision||!sameStack(held,expected)
                    ||before!=null&&!Arrays.equals(before,inventory.getStorageContents()))return InteractionResult.FAIL;
            // No event-emitting drop/give call: the empty hand guarantees storage space.
            // Keep ownership in the furniture until the inventory write succeeds.
            try {
                if(plan.taking())inventory.setStorageContents(after);
                else player.setItemInHand(InteractionHand.MAIN_HAND,plan.hand());
            } catch(RuntimeException error) {
                if(plan.taking()&&Arrays.equals(after,inventory.getStorageContents()))inventory.setStorageContents(before);
                else if(!plan.taking()&&sameStack(player.getItemInHand(InteractionHand.MAIN_HAND),plan.hand()))
                    player.setItemInHand(InteractionHand.MAIN_HAND,expected);
                throw error;
            }
            this.items[plan.slot()]=plan.stored();equipmentRevision++;
            refresh();
            if(!plan.taking()) {
                var pos=furniture().position();
                ((World)pos.world().platformWorld()).playSound(new Location((World)pos.world().platformWorld(),pos.x,pos.y,pos.z),
                        "minecraft:entity.item_frame.add_item",SoundCategory.NEUTRAL,1,1);
            }
            player.swingHand(InteractionHand.MAIN_HAND);
            if(plan.placedHead())KaleidoscopeAdvancements.recordEvent(player,"place_head_on_scarecrow");
            return InteractionResult.SUCCESS_AND_CANCEL;
        } finally {
            interacting=false;
            if(removalConfirmed)finishRemoval();
        }
    }

    private static ItemStack[] copyStacks(ItemStack[] source) {
        return Arrays.stream(source).map(item->item==null?null:item.clone()).toArray(ItemStack[]::new);
    }
    private static boolean sameStack(Item first,Item second) {
        return first!=null&&first.count()==second.count()&&(second.isEmpty()?first.isEmpty():first.isSimilar(second));
    }
    private static void validateEquipment(Item[] slots) {
        CompoundTag root=new CompoundTag();
        CookeryBlockStateEnvelope.put(root,DATA_KEY,ScarecrowEquipment.write(slots));
        try {net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData.of(root).toBytes();}
        catch(java.io.IOException error){throw new IllegalArgumentException("Cannot persist scarecrow equipment",error);}
    }

    private int slotOf(FurnitureHitBox hitBox) {
        if(hitBox==null)return -1;
        for (int slot = 0; slot < this.slotHitboxes.length; slot++) {
            if (this.slotHitboxes[slot] == hitBox) {
                return slot;
            }
        }
        return -1;
    }

    // 身体箱走配置里的 variants.hitboxes 只用来挨打 三个槽箱由行为自己造
    @Override
    public void gatherHitboxes(Consumer<FurnitureHitBox> consumer) {
        for (int slot = 0; slot < ScarecrowElement.SLOTS; slot++) {
            FurnitureHitBox hitBox = this.behavior.slots[slot].hitbox().create(furniture());
            this.slotHitboxes[slot] = hitBox;
            consumer.accept(hitBox);
        }
    }

    private boolean isHeadItem(Item item) {
        Material material = material(item);
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_HEAD") || name.endsWith("_SKULL");
    }

    private Material material(Item item) {
        var key=item.vanillaId();
        return key==null?null:Material.matchMaterial(key.asString());
    }

    // trackedBy 是列表快照 getTrackedBy 返回的是活的集合 别的 region 线程正在改它
    private void refresh() {
        furniture().setUnsaved();
        if(removing||unloaded)return;
        this.element.rebuild();
        furniture().trackedBy().forEach(this.element::update);
        refreshLight();
        // 少了这句物品永远不落盘 重开服手上的东西就没了 也取不出来
        furniture().setUnsaved();
    }


    @Override
    @SuppressWarnings("unchecked")
    public <T extends FurnitureController> FurnitureTicker<T> createFurnitureTicker() {
        return (FurnitureTicker<T>) (FurnitureTicker<ScarecrowController>) (furniture, controller) -> {
            controller.tickWobble();
            if (controller.behavior.parrotPerch) controller.tickPerch();
        };
    }

    private void tickWobble() {
        if (!usable()) return;
        long now = ((World) furniture().position().world().platformWorld()).getGameTime();
        if (approvedBreak != null && approvedBreakTick != now) approvedBreak = null;
        float next = hitState.angle(now);
        if (next != wobbleAngle) {
            wobbleAngle = next;
            element.applyWobble(next, furniture().trackedBy(), 1);
        }
    }

    private void tickPerch() {
        if(!usable()||interacting)return;
        if (++this.tickCounter < this.behavior.parrotScanInterval) {
            return;
        }
        this.tickCounter = 0;
        WorldPosition position = furniture().position();
        World world = (World) position.world().platformWorld();
        // 锚点是无敌不落盘的隐形载具 鹦鹉死了它自己不会消失
        // 只判锚点还在就 return 会让这个稻草人从此再也停不了第二只鹦鹉 必须连乘客一起判
        if (this.perchUuid != null) {
            Entity anchor = world.getEntity(this.perchUuid);
            if (anchor != null && !anchor.isDead() && !anchor.getPassengers().isEmpty()) {
                return;
            }
            releasePerch();
        }
        Parrot parrot = findParrot(world, position);
        if (parrot == null) {
            return;
        }
        // 家具 ticker 跑在本家具的实体调度器上 鹦鹉又限定在同一区块 两者必然同 region
        // 再往实体调度器投递会推迟一 tick 期间鹦鹉可能飞进别的 region perchUuid 就成了跨线程写
        perch(world, position, parrot);
    }

    // 只扫稻草人自己所在的区块 区块必然完整属于一个 region 不会撞 getEntities 的跨 region 检查
    private Parrot findParrot(World world, WorldPosition position) {
        int chunkX = (int) Math.floor(position.x) >> 4;
        int chunkZ = (int) Math.floor(position.z) >> 4;
        if (!Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            return null;
        }
        double range = this.behavior.parrotPickupRange;
        for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
            if (entity.getType() != EntityType.PARROT || !(entity instanceof Parrot parrot)) {
                continue;
            }
            if (parrot.isDead() || parrot.isSitting() || parrot.isInsideVehicle()) {
                continue;
            }
            Location location = parrot.getLocation();
            double dx = location.getX() - position.x;
            double dy = location.getY() - position.y;
            double dz = location.getZ() - position.z;
            if (dx * dx + dy * dy + dz * dz <= range * range) {
                return parrot;
            }
        }
        return null;
    }

    // 不落盘的隐形载具 崩服重启后自然消失 鹦鹉掉下来即可 不需要崩溃恢复快照
    private void perch(World world, WorldPosition position, Parrot parrot) {
        float yaw = (float) Math.toRadians(-position.yRot());
        Location seat = new Location(world,
                position.x + Math.cos(yaw) * this.behavior.shoulderOffset.x,
                position.y + this.behavior.shoulderOffset.y,
                position.z - Math.sin(yaw) * this.behavior.shoulderOffset.x);
        // 肩膀位可能落进邻区块 不归本 region 时生成会抛 下次扫描再试
        if (!Bukkit.isOwnedByCurrentRegion(seat)) {
            return;
        }
        ArmorStand anchor = world.spawn(seat, ArmorStand.class, stand -> {
            stand.setMarker(true);
            stand.setInvisible(true);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setGravity(false);
            stand.setPersistent(false);
        });
        if (!anchor.addPassenger(parrot)) {
            anchor.remove();
            return;
        }
        this.perchUuid = anchor.getUniqueId();
        this.perchSinceTick = world.getGameTime();
    }

    private void releasePerch() {
        if (this.perchUuid == null) {
            return;
        }
        World world = (World) furniture().position().world().platformWorld();
        Entity anchor = world.getEntity(this.perchUuid);
        this.perchUuid = null;
        if (anchor == null) {
            return;
        }
        FoliaUtil.runEntity(anchor, () -> {
            anchor.eject();
            anchor.remove();
        });
    }


    @Override
    public void gatherElements(Consumer<FurnitureElement> consumer) {
        consumer.accept(this.element);
    }

    @Override
    public void onLoad() {
        unloaded=false;
        hitState.reset(); wobbleAngle = 0; approvedBreak = null;
        addToIndex();
        this.element.rebuild();
        refreshLight();
    }

    @Override
    public void onPlace(Player player) {
        this.element.rebuild();
        refreshLight();
    }

    @Override
    public void onUnload() {
        unloaded=true;
        hitState.reset(); wobbleAngle = 0; approvedBreak = null;
        removeFromIndex();
        // 这里跑在区块系统的实体状态变更回调里 移除实体会被 Paper 拒绝并刷一整页栈
        // 锚点是 setPersistent(false) 的 跟着区块一起消失 忘掉 uuid 就行
        this.perchUuid = null;
        // 区块卸载不动世界方块 光源跟着区块一起存盘 下次 onLoad 再认回来
        this.litPos = null;
    }

    @Override
    public InteractionResult onPlayerHit(Player player, FurnitureHitBox hitBox) {
        // CE invokes this during LOWEST FurnitureHitEvent. Defer all mutations
        // until its break event has passed protection listeners.
        return InteractionResult.PASS;
    }

    public boolean playerStrike(org.bukkit.entity.Player actor) {
        approvedBreak = null;
        if (interacting || !usable() || actor == null || !Bukkit.isOwnedByCurrentRegion(actor)
                || actor.getWorld() != furniture().position().world().platformWorld()
                || actor.getGameMode() != GameMode.SURVIVAL && actor.getGameMode() != GameMode.CREATIVE) return false;
        var pos = furniture().position();
        Location at = new Location((World) pos.world().platformWorld(), pos.x, pos.y, pos.z);
        if (!InteractGuard.canBreak(actor, at) || !usable()) return false;
        long now = at.getWorld().getGameTime();
        if (hitState.strike(now, actor.getGameMode() == GameMode.CREATIVE) == ScarecrowHitState.Result.DESTROY) return true;
        // Source releases an occupied shoulder only once it has been held >20 ticks.
        if (perchUuid != null && perchSinceTick + 20 < now) releasePerch();
        ScarecrowHitEffects.hit(at, actor);
        return false;
    }

    /** Called only for the final, uncanceled break event; consumed by immediate preRemove. */
    public void acceptPlayerBreak(FurnitureBreakEvent event) {
        if (event == null || event.isCancelled() || !usable()) return;
        approvedBreak = event;
        approvedBreakTick = ((World) furniture().position().world().platformWorld()).getGameTime();
    }

    @Override
    public void preRemove(Player player) {
        if (removing) return;
        removing=true;
        creativeRemoval=player!=null&&player.canInstabuild();
        var actor = player == null ? null : (org.bukkit.entity.Player) player.platformPlayer();
        // CE event functions run after Bukkit MONITOR and may cancel or change drops.
        // Read the final event state here rather than freezing the MONITOR verdict.
        boolean approved = actor != null && approvedBreak != null && !approvedBreak.isCancelled()
                && actor.getUniqueId().equals(approvedBreak.getPlayer().getUniqueId())
                && approvedBreakTick == ((World) furniture().position().world().platformWorld()).getGameTime();
        playerBreakEffects = approved;
        if (approved && !approvedBreak.dropItems()) creativeRemoval = true;
        approvedBreak = null;
        removeFromIndex();
        releasePerch();
        clearLight();
    }

    @Override public void postRemove(Player player) {
        removalConfirmed=true;
        if(!interacting)finishRemoval();
    }

    private void finishRemoval() {
        if(removalSettled)return;
        removalSettled=true;
        try { settleEquipment(); }
        finally {
            if (playerBreakEffects) {
                var pos = furniture().position();
                ScarecrowHitEffects.broken(new Location((World) pos.world().platformWorld(), pos.x, pos.y, pos.z));
            }
        }
    }

    private void settleEquipment() {
        if(rejectedState!=null) {FurnitureRecovery.save(this,"scarecrow");return;}
        if(creativeRemoval) {Arrays.fill(items,Item.empty());return;}
        var pos=furniture().position();World world=(World)pos.world().platformWorld();
        Location at=new Location(world,pos.x,pos.y+1,pos.z);
        try {
            for(int slot=0;slot<items.length;slot++) {
                if(items[slot].isEmpty())continue;
                ItemStack expected=InventoryUtils.bukkitCopy(items[slot]);
                var drop=world.dropItemNaturally(at,expected.clone());
                if(!drop.isValid()||!expected.equals(drop.getItemStack())) {
                    if(drop.isValid())drop.remove();break;
                }
                items[slot]=Item.empty();equipmentRevision++;
            }
        } finally {
            if(Arrays.stream(items).anyMatch(item->!item.isEmpty()))FurnitureRecovery.save(this,"scarecrow");
        }
    }

    @Override
    public void saveCustomData(CompoundTag tag) {
        if(rejectedState!=null) {
            for(String key:new String[]{DATA_KEY,CookeryBlockStateEnvelope.sidecar(DATA_KEY)}) {
                var original=rejectedState.get(key);
                if(original==null)tag.remove(key);else tag.put(key,original.deepClone());
            }
            return;
        }
        CookeryBlockStateEnvelope.put(tag,DATA_KEY,ScarecrowEquipment.write(items));
    }

    @Override
    public void loadCustomData(CompoundTag tag) {
        if(interacting)throw new IllegalStateException("Cannot reload scarecrow during exchange");
        try {
            if(tag.containsKey(DATA_KEY)&&!(tag.get(DATA_KEY) instanceof CompoundTag))throw new IllegalArgumentException("Invalid scarecrow payload");
            Item[] restored=ScarecrowEquipment.read(CookeryBlockStateEnvelope.payload(tag,DATA_KEY));
            System.arraycopy(restored,0,items,0,items.length);equipmentRevision++;
            rejectedState=null;this.element.rebuild();
        } catch(RuntimeException error) {rejectedState=tag.deepClone();throw error;}
    }

    static final class Protection {
        private final double x;
        private final double y;
        private final double z;
        private final double radius;

        Protection(double x, double y, double z, double radius) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
        }

        boolean covers(int blockX, int blockY, int blockZ) {
            // Source queries AABB(farmland).inflate(radius) against a 0.5 x 2.375 entity.
            // AABB.intersects uses strict bounds: merely touching a face is not protection.
            return this.x + .25 > blockX - radius && this.x - .25 < blockX + 1.0 + radius
                    && this.y + 2.375 > blockY - radius && this.y < blockY + 1.0 + radius
                    && this.z + .25 > blockZ - radius && this.z - .25 < blockZ + 1.0 + radius;
        }

        int indexRadius() {
            // Fractional anchors plus entity width can reach the next integer/chunk bucket.
            return (int) Math.ceil(radius + .25);
        }
    }
}
