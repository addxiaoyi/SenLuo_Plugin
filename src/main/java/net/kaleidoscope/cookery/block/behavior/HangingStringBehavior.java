package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.nms.NmsBridgeProvider;
import net.kaleidoscope.cookery.util.SupportStateUtils;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.bukkit.util.DirectionUtils;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelAccessorProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import net.kaleidoscope.cookery.util.BehaviorConfig;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import org.bukkit.Bukkit;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;

// 悬挂串 辣椒串蘑菇串这类从天花板往下接的方块
// 空手或手持产物右键采摘 未剪过先转成剪过态 已剪过再摘就整段消失
public final class HangingStringBehavior extends BukkitBlockBehavior {
    public static final BlockBehaviorFactory<HangingStringBehavior> FACTORY = new Factory();

    // 1.21.2 起 updateShape 的参数从 6 个变成 8 个 布局完全不同 见 CE BlockBehavior 的 Javadoc
    private static final int SHAPE_LEVEL = VersionHelper.isOrAbove1_21_2 ? 1 : 3;
    private static final int SHAPE_POS = VersionHelper.isOrAbove1_21_2 ? 3 : 4;
    private static final int SHAPE_DIRECTION = VersionHelper.isOrAbove1_21_2 ? 4 : 1;
    private static final int SHAPE_NEIGHBOR_STATE = VersionHelper.isOrAbove1_21_2 ? 6 : 2;
    // 失去支撑后延迟这么多 tick 再塌 与原版悬挂方块一致
    private static final int COLLAPSE_DELAY = 1;

    private final Property<Boolean> isHeadProperty;
    private final Property<Boolean> shearedProperty;
    private final Key harvestItem;
    private final int harvestAmount;
    private final Key harvestSound;
    private final boolean harmsUndead;

    private HangingStringBehavior(BlockDefinition block, Property<Boolean> isHeadProperty,
                                  Property<Boolean> shearedProperty, Key harvestItem,
                                  int harvestAmount, Key harvestSound, boolean harmsUndead) {
        super(block);
        this.isHeadProperty = isHeadProperty;
        this.shearedProperty = shearedProperty;
        this.harvestItem = harvestItem;
        this.harvestAmount = harvestAmount;
        this.harvestSound = harvestSound;
        this.harmsUndead = harmsUndead;
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        Player player = context.getPlayer();
        if (player == null || context.getHand() != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        // 空手或手上已经拿着同种产物才算采摘 拿着别的东西是想放置方块 别抢掉
        Item held = context.getItem();
        if (!HangingStringHarvest.accepts(held, harvestItem)) {
            return InteractionResult.PASS;
        }
        BlockPos pos = context.getClickedPos();
        World world = context.getLevel();
        org.bukkit.World bukkit = (org.bukkit.World) world.platformWorld();
        if (!Bukkit.isOwnedByCurrentRegion(bukkit, pos.x() >> 4, pos.z() >> 4)) return InteractionResult.PASS;
        HangingStringHarvest.pick(bukkit.getBlockAt(pos.x(), pos.y(), pos.z()), player, state, shearedProperty,
                harvestItem, harvestAmount, harvestSound);
        // The source deliberately returns PASS after harvesting as well.
        return InteractionResult.PASS;
    }

    @Override
    public void entityInside(Object thisBlock, Object[] args) {
        if (!harmsUndead) return;
        Object entity = NmsBridgeProvider.bridge().bukkitEntity(args[3]);
        if (!(entity instanceof Mob mob) || !mob.isValid() || !Bukkit.isOwnedByCurrentRegion(mob)) return;
        Tag<EntityType> undead = Bukkit.getTag("entity_types", NamespacedKey.minecraft("sensitive_to_smite"), EntityType.class);
        if (undead == null || !undead.isTagged(mob.getType())) return;
        BlockPos pos = LocationUtils.fromBlockPos(args[2]);
        org.bukkit.World world = mob.getWorld();
        if (!Bukkit.isOwnedByCurrentRegion(world, pos.x() >> 4, pos.z() >> 4)
                || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) return;
        ImmutableBlockState current = CraftEngineBlocks.getCustomBlockState(world.getBlockAt(pos.x(), pos.y(), pos.z()));
        if (current != null && current.owner().value() == blockDefinition) {
            mob.damage(2, DamageSource.builder(DamageType.MAGIC).build());
        }
    }

    // 挂在上方 顶端接天花板的坚固下表面 其余接同种方块
    @Override
    public boolean canSurvive(Object thisBlock, Object[] args) {
        return isSupported(args[1], args[2]);
    }

    private boolean isSupported(Object level, Object pos) {
        Object abovePos = LocationUtils.above(pos);
        Object aboveState = BlockGetterProxy.INSTANCE.getBlockState(level, abovePos);
        ImmutableBlockState aboveCustom = BlockStateUtils.getOptionalCustomBlockState(aboveState).orElse(null);
        if (aboveCustom != null && aboveCustom.owner().value() == super.blockDefinition) {
            return true;
        }
        return SupportStateUtils.isSturdyDown(level, abovePos, aboveState);
    }

    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        Object blockState = args[0];
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(blockState).orElse(null);
        if (state == null || state.isEmpty()) {
            return blockState;
        }
        Object level = args[SHAPE_LEVEL];
        Object pos = args[SHAPE_POS];
        Direction direction = DirectionUtils.fromNMSDirection(args[SHAPE_DIRECTION]);

        // 顶上的支撑没了就排一个 tick 让它塌 直接返回空气会跳过掉落
        if (direction == Direction.UP && !isSupported(level, pos)) {
            LevelAccessorProxy.INSTANCE.scheduleTick$0(level, pos, thisBlock, COLLAPSE_DELAY);
            return blockState;
        }
        // 末端那一节用不同的模型 下面接没接同种方块决定
        if (direction == Direction.DOWN) {
            ImmutableBlockState below = BlockStateUtils.getOptionalCustomBlockState(args[SHAPE_NEIGHBOR_STATE]).orElse(null);
            boolean isHead = below == null || below.owner().value() != super.blockDefinition;
            if (state.get(isHeadProperty) != isHead) {
                return state.with(isHeadProperty, isHead).customBlockState().minecraftState();
            }
        }
        return blockState;
    }

    @Override
    public void tick(Object thisBlock, Object[] args) {
        org.bukkit.World world = LevelProxy.INSTANCE.getWorld(args[1]);
        if (world == null) {
            return;
        }
        BlockPos pos = LocationUtils.fromBlockPos(args[2]);
        if (!Bukkit.isOwnedByCurrentRegion(world, pos.x() >> 4, pos.z() >> 4)
                || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) return;
        var block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        var state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null || state.owner().value() != blockDefinition) return;
        HangingStringHarvest.collapse(block, state, shearedProperty, harvestItem, harvestAmount,
                () -> isSupported(args[1], args[2]));
    }

    private static class Factory implements BlockBehaviorFactory<HangingStringBehavior> {
        private static final int DEFAULT_AMOUNT = 3;
        private static final String DEFAULT_SOUND = "minecraft:block.sweet_berry_bush.pick_berries";

        @Override
        public HangingStringBehavior create(BlockDefinition block, ConfigSection section) {
            String path = section.path();
            Property<Boolean> isHead = BlockBehaviorFactory.getProperty(path, block, "is_head", Boolean.class);
            Property<Boolean> sheared = BlockBehaviorFactory.getProperty(path, block, "sheared", Boolean.class);
            String item = BehaviorConfig.getString(section, null, "harvest_item", "harvest-item");
            if (item == null) {
                throw new IllegalArgumentException("hanging_string 缺少 harvest_item");
            }
            int amount = BehaviorConfig.getInt(section, DEFAULT_AMOUNT, "harvest_amount", "harvest-amount");
            if (amount < 1 || amount > 32) throw new IllegalArgumentException("hanging_string harvest_amount must be 1..32");
            String sound = BehaviorConfig.getString(section, DEFAULT_SOUND, "harvest_sound", "harvest-sound");
            return new HangingStringBehavior(block, isHead, sheared, Key.of(item), amount, Key.of(sound),
                    block.id().equals(Key.of("kaleidoscopecookery:chili_ristra")));
        }
    }
}
