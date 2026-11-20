package net.kaleidoscope.cookery.block.behavior;
import net.kaleidoscope.cookery.api.event.SteamerBreakFullEvent;
import net.kaleidoscope.cookery.block.entity.SteamerController;
import net.kaleidoscope.cookery.block.entity.SteamerInteraction;
import net.kaleidoscope.cookery.block.entity.SteamerWorld;
import net.kaleidoscope.cookery.nms.NmsBridgeProvider;
import net.kaleidoscope.cookery.util.ConsoleMessages;

import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.item.behavior.BlockItemBehavior;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitFallableBlock;
import net.momirealms.craftengine.bukkit.plugin.injector.FallingBlockEntityGenerator;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.DirectionUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.bukkit.world.BukkitWorldManager;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.block.property.type.SlabType;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.item.behavior.BlockItem;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.sound.SoundSource;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.MutableBoolean;
import net.momirealms.craftengine.core.world.BlockHitResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.inventory.ItemStack;
import net.momirealms.craftengine.proxy.minecraft.core.Vec3iProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelAccessorProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelReaderProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.dimension.DimensionTypeProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelWriterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.block.BlocksProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.block.FallingBlockProxy;
import org.bukkit.Location;
import net.kaleidoscope.cookery.util.EventUtils;
import net.kaleidoscope.cookery.util.HeatSourceUtils;
import net.kaleidoscope.cookery.util.BehaviorConfig;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.kaleidoscope.cookery.util.Localization;
import net.kaleidoscope.cookery.util.MessageKeys;
import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import static net.momirealms.craftengine.core.block.UpdateFlags.UPDATE_ALL;

public final class SteamerBehavior extends BukkitBlockBehavior implements EntityBlock, BukkitFallableBlock {
    public static final BlockBehaviorFactory<SteamerBehavior> FACTORY = new Factory();

    // 掉落暂存 键为 FallingBlock 实体 值为下落前的蒸笼数据快照
    public static final Map<Object, PendingData> pendingData = new ConcurrentHashMap<>();

    private static final Key PLACE_SOUND = Key.of("minecraft:block.wood.place");
    private static final Key LID_SOUND = Key.of("minecraft:block.barrel.close");
    public static final Key STOVE_BLOCK_KEY = Key.of("kaleidoscopecookery:stove");

    public int campfireStackHeight = 8;
    public int stoveStackHeight = 16;
    public int cookingTime = 200;
    public int particleInterval = 20;
    public int particleCount = 3;
    public boolean portableContents;

    private int controllerId;
    private Property<SlabType> typeProperty;
    private Property<Boolean> hasLidProperty;
    private Property<Boolean> hasBaseProperty;
    private Property<Direction> facingProperty;

    private SteamerBehavior(BlockDefinition blockDefinition) {
        super(blockDefinition);
    }

    // 下落数据快照
    public static class PendingData {
        public final CompoundTag tag;
        public final ImmutableBlockState steamerState;
        public final int controllerId;

        public PendingData(CompoundTag tag, ImmutableBlockState steamerState, int controllerId) {
            this.tag = tag;
            this.steamerState = steamerState;
            this.controllerId = controllerId;
        }
    }

    // 由 SteamerFallingBlockListener 在领地插件取消落地事件、且落点不受支撑时调用 
    // 此时 CE 不会再触发 onLand / onBrokenAfterFall 需手动掉落以免蒸笼及内容物丢失 
    public static void dropPendingSteamer(Object level, Object blockPos, Object fallingBlockEntity) {
        PendingData data = pendingData.remove(fallingBlockEntity);
        if (data == null) {
            return;
        }
        SteamerBehavior behavior = data.steamerState.behavior().getFirst(SteamerBehavior.class);
        if (behavior != null) {
            behavior.dropSteamer(level, blockPos, data);
        }
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        return SteamerInteraction.use(context, state, this, () -> handleStackSteamer(context, state,
                context.getLevel(), context.getPlayer(), context.getHand(), context.getPlayer().getItemInHand(context.getHand())));
    }

    private InteractionResult handleStackSteamer(UseOnContext context, ImmutableBlockState state, World level, Player player, InteractionHand hand, Item itemInHand) {
        // 向上找到第一个可放置的层
        BlockPos placePos = context.getClickedPos();
        ImmutableBlockState walkState = state;
        while (walkState != null
                && walkState.owner().value() == super.blockDefinition
                && !walkState.get(hasLidProperty)
                && walkState.get(typeProperty) == SlabType.DOUBLE) {
            placePos = placePos.above();
            if (!SteamerWorld.accessible(level, placePos)) return InteractionResult.FAIL;
            walkState = level.getBlock(placePos).customBlockState();
        }

        BlockHitResult hitResult = new BlockHitResult(context.getClickedLocation(), Direction.UP, placePos, false);
        BlockPlaceContext placeContext = new BlockPlaceContext(level, player, hand, itemInHand, hitResult);
        // 叠笼实际放置点是向上 walk 出的新位置 与点击位可能跨领地 放动作前再校验目标位置
        if (!InteractGuard.canPlace(player, level, placePos)) {
            return InteractionResult.PASS;
        }

        ImmutableBlockState targetState = level.getBlock(placePos).customBlockState();

        boolean canStackHere = targetState == null
                || level.getBlock(placePos).canBeReplaced(placeContext)
                || (targetState.owner().value() == super.blockDefinition
                && targetState.get(typeProperty) == SlabType.BOTTOM
                && !targetState.get(hasLidProperty));
        if (canStackHere) {
            return placeSteamer(placeContext);
        }
        return InteractionResult.PASS;
    }

    private boolean isThisSteamerItem(Item item) {
        Optional<ItemDefinition> def = item.getDefinition();
        if (def.isEmpty()) {
            return false;
        }
        MutableBoolean sameId = new MutableBoolean(false);
        def.get().behavior().let(BlockItem.class, b -> {
            if (b.block().equals(super.blockDefinition.id())) {
                sameId.set(true);
            }
        });
        return sameId.booleanValue();
    }

    InteractionResult placeSteamer(BlockPlaceContext context) {
        Item held = context.getItem();
        if (held == null || held.isEmpty()) return InteractionResult.FAIL;
        BlockItemBehavior placer = held.getDefinition()
                .map(definition -> definition.behavior().getFirst(BlockItemBehavior.class)).orElse(null);
        if (placer == null || !blockDefinition.id().equals(placer.block())) return InteractionResult.FAIL;
        // CE owns placement events, rollback, item-data loading and the final inventory debit.
        // Reuse the item's actual behavior so future filled-steamer placement can validate/merge contents.
        return placer.place(context);
    }

    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        LevelAccessorProxy.INSTANCE.scheduleTick$0(args[1], args[2], thisBlock, 2);
    }

    @Override
    public void tick(Object thisBlock, Object[] args) {
        Object blockState = args[0], level = args[1], blockPos = args[2];
        Optional<ImmutableBlockState> optionalCustomState = BlockStateUtils.getOptionalCustomBlockState(blockState);
        if (optionalCustomState.isEmpty()) {
            return;
        }
        ImmutableBlockState customState = optionalCustomState.get();

        // 世界底部以下不触发下落
        int minY = DimensionTypeProxy.INSTANCE.getMinY(LevelReaderProxy.INSTANCE.dimensionType(level));
        if (Vec3iProxy.INSTANCE.getY(blockPos) < minY) {
            return;
        }

        Object belowPos = LocationUtils.below(blockPos);
        Object belowState = BlockGetterProxy.INSTANCE.getBlockState(level, belowPos);

        if (!FallingBlockProxy.INSTANCE.isFree(belowState)) {
            return;
        }
        // 快照 NBT 并切断原方块实体的掉落 转交给下落实体
        BlockPos pos = LocationUtils.fromBlockPos(blockPos);
        CompoundTag tag = new CompoundTag();
        BlockEntity blockEntity = BukkitWorldManager.instance().getWorld(
                LevelProxy.INSTANCE.getWorld(level).getUID()).storageWorld().getBlockEntityAtIfLoaded(pos);
        SteamerController controller = blockEntity == null ? null
                : blockEntity.controller.get(SteamerController.class, this.controllerId);
        if (controller != null) {
            if (!controller.hasValidData()) return;
            controller.saveCustomData(tag);
            controller.markFallingAway();
        }

        ImmutableBlockState fallingState = customState.with(hasBaseProperty, false);
        Object fallingBlockEntity = FallingBlockEntityGenerator.fall(level, blockPos, fallingState.customBlockState().minecraftState());
        PendingData pending = new PendingData(tag, fallingState, this.controllerId);
        // CE 26.9 returns an unspawned entity when the change-block event is cancelled.
        if (fallingBlockEntity == null || !EntityProxy.INSTANCE.getBukkitEntity(fallingBlockEntity).isValid()) {
            if (BlockGetterProxy.INSTANCE.getBlockState(level, blockPos) == blockState) {
                if (controller != null) {
                    controller.clearFallingAway();
                }
            } else {
                dropSteamer(level, blockPos, pending);
            }
            return;
        }
        pendingData.put(fallingBlockEntity, pending);
    }

    @Override
    public void onLand(Object thisBlock, Object[] args) {
        Object level = args[0];
        Object blockPos = args[1];
        Object fallingBlock = args[4];
        PendingData data = pendingData.remove(fallingBlock);
        if (data == null) {
            return;
        }

        BlockPos landPos = LocationUtils.fromBlockPos(blockPos);
        CEWorld ceWorld = BukkitWorldManager.instance().getWorld(
                LevelProxy.INSTANCE.getWorld(level).getUID()).storageWorld();

        // 标记落地方块为下落中 避免 onRemove 误掉落
        BlockEntity landingEntity = ceWorld.getBlockEntityAtIfLoaded(landPos);
        if (landingEntity != null) {
            SteamerController landingController = landingEntity.controller.get(SteamerController.class, this.controllerId);
            if (landingController != null) {
                landingController.markFallingAway();
            }
        }

        Object belowPos = LocationUtils.below(blockPos);
        if (!isLandingSupported(level, belowPos)) {
            LevelWriterProxy.INSTANCE.setBlock(level, blockPos, BlocksProxy.AIR$defaultState, UPDATE_ALL);
            dropSteamer(level, blockPos, data);
            return;
        }

        ImmutableBlockState landedState = data.steamerState.with(hasBaseProperty, SteamerWorld.hasBase(level, belowPos, blockDefinition));
        LevelWriterProxy.INSTANCE.setBlock(level, blockPos, landedState.customBlockState().minecraftState(), UPDATE_ALL);
        BlockEntity blockEntity = ceWorld.getBlockEntityAtIfLoaded(landPos);
        if (blockEntity != null) {
            SteamerController controller = blockEntity.controller.get(SteamerController.class, data.controllerId);
            if (controller != null) {
                controller.loadCustomData(data.tag);
                controller.clearFallingAway();
                controller.refreshElementState();
            }
        }
    }

    @Override
    public void onBrokenAfterFall(Object thisBlock, Object[] args) {
        Object level = args[0];
        Object blockPos = args[1];
        Object fallingBlock = args[2];
        PendingData data = pendingData.remove(fallingBlock);
        if (data != null) {
            dropSteamer(level, blockPos, data);
        }
    }

    private boolean isLandingSupported(Object level, Object belowPos) {
        return SteamerWorld.landingSupported(level, belowPos);
    }

    private void dropSteamer(Object level, Object blockPos, PendingData data) {
        try {
            if (portableContents && Boolean.FALSE.equals(LevelProxy.INSTANCE.getWorld(level)
                    .getGameRuleValue(net.kaleidoscope.compat.Rules.DO_TILE_DROPS))) return;
            CEWorld ceWorld = BukkitWorldManager.instance().getWorld(
                    LevelProxy.INSTANCE.getWorld(level).getUID()).storageWorld();
            BlockPos pos = LocationUtils.fromBlockPos(blockPos);
            Vec3d dropPos = Vec3d.atCenterOf(pos);

            CompoundTag sd = net.kaleidoscope.cookery.util.CookeryBlockStateEnvelope.payload(data.tag, SteamerController.DATA_KEY);
            // Decode the complete snapshot before emitting any drops; both old dense and new sparse saves work.
            var slots = net.kaleidoscope.cookery.block.entity.SteamerInventory.read(sd, cookingTime);
            if (portableContents) {
                int count = data.steamerState.get(typeProperty) == SlabType.DOUBLE ? 8 : 4;
                List<Item> drops = net.kaleidoscope.cookery.item.SteamerItemContents.split(slots, count, false);
                for (Item drop : drops) ceWorld.world().dropItemNaturally(dropPos, drop);
                return;
            }
            for (var slot : slots) if (slot != null) ceWorld.world().dropItemNaturally(dropPos, slot.item());

            int amount = (data.steamerState.get(this.typeProperty) == SlabType.DOUBLE) ? 2 : 1;
            Key steamerKey = data.steamerState.owner().value().id();
            Item steamerItem = InventoryUtils.createOrEmpty(steamerKey);
            if (!ItemUtils.isEmpty(steamerItem)) {
                ceWorld.world().dropItemNaturally(dropPos, steamerItem.copyWithCount(amount));
            }
        } catch (Exception e) {
            KaleidoscopeCookeryPlugin.instance().getLogger().log(Level.SEVERE,
                    ConsoleMessages.t("steamer.drop_failed"), e);
        }
    }

    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        BlockPos clickedPos = context.getClickedPos();
        ImmutableBlockState existingState = context.getLevel().getBlock(clickedPos).customBlockState();

        // 原位是蒸笼底层时合并为双层
        if (existingState != null && existingState.owner().value() == super.blockDefinition) {
            if (existingState.get(typeProperty) != SlabType.BOTTOM) return null;
            return existingState.with(this.typeProperty, SlabType.DOUBLE);
        }

        if (!SteamerWorld.accessible(context.getLevel(), clickedPos.below())) return null;
        Object level = context.getLevel().minecraftWorld();
        return state.with(this.typeProperty, SlabType.BOTTOM).with(this.facingProperty, context.getHorizontalDirection().opposite())
                .with(this.hasBaseProperty, shouldHasBase(level, clickedPos));
    }

    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        Object blockState = args[0];
        Object level = args[updateShape$level];
        Object blockPos = args[updateShape$blockPos];
        Direction nmsDirection = DirectionUtils.fromNMSDirection(args[updateShape$direction]);

        LevelAccessorProxy.INSTANCE.scheduleTick$0(level, blockPos, thisBlock, 2);

        ImmutableBlockState customState = BlockStateUtils.getOptionalCustomBlockState(blockState).orElse(null);
        if (customState == null || customState.isEmpty()) {
            return blockState;
        }

        // 上方接蒸笼时收起盖子
        if (nmsDirection == Direction.UP && this.hasLidProperty != null) {
            ImmutableBlockState neighborCustomState = BlockStateUtils.getOptionalCustomBlockState(args[updateShape$neighborState]).orElse(null);
            if (neighborCustomState != null && neighborCustomState.owner().value() == super.blockDefinition) {
                return customState.with(this.hasLidProperty, false).customBlockState().minecraftState();
            }
        }

        // 下方变化时同步底座
        if (nmsDirection == Direction.DOWN && this.hasBaseProperty != null) {
            boolean shouldHaveBase = shouldHasBase(level, LocationUtils.fromBlockPos(blockPos));
            if (customState.get(this.hasBaseProperty) != shouldHaveBase) {
                return customState.with(this.hasBaseProperty, shouldHaveBase).customBlockState().minecraftState();
            }
        }
        return blockState;
    }

    @Override
    public Object playerWillDestroy(Object thisBlock, Object[] args) {
        Object nmsPlayer = args.length > 3 ? args[3] : null;
        CEWorld ceWorld = BukkitWorldManager.instance().getWorld(
                LevelProxy.INSTANCE.getWorld(args[0]).getUID()).storageWorld();
        BlockEntity be = ceWorld.getBlockEntityAtIfLoaded(LocationUtils.fromBlockPos(args[1]));
        SteamerController c = be != null ? be.controller.get(SteamerController.class, this.controllerId) : null;
        if (c == null) {
            return args[2];
        }
        org.bukkit.entity.Player breaker = bukkitPlayer(nmsPlayer);
        if (portableContents && breaker != null && breaker.getGameMode() == org.bukkit.GameMode.CREATIVE) c.skipAllDrops();

        // 蒸笼装满成品被破坏时触发事件 取消则跳过成品特殊掉落
        if (c.isFullOfFinishedProducts()) {
            org.bukkit.entity.Player bukkitPlayer = bukkitPlayer(nmsPlayer);
            if (bukkitPlayer != null) {
                List<ItemStack> products = c.finishedProductStacks();
                BlockPos pos = LocationUtils.fromBlockPos(args[1]);
                Location location = new Location((org.bukkit.World) ceWorld.world().platformWorld(), pos.x(), pos.y(), pos.z());
                boolean cancelled = EventUtils.fireAndCheckCancel(new SteamerBreakFullEvent(bukkitPlayer, location, products));
                if (cancelled) {
                    c.markSkipFoodDrop();
                }
            }
        }
        return args[2];
    }

    private static org.bukkit.entity.Player bukkitPlayer(Object nmsPlayer) {
        return NmsBridgeProvider.bridge().bukkitPlayer(nmsPlayer);
    }

    @Override
    public boolean canBeReplaced(BlockPlaceContext context, ImmutableBlockState state) {
        SlabType type = state.get(this.typeProperty);
        if (type == SlabType.DOUBLE || type == SlabType.TOP) {
            return false;
        }
        if (ItemUtils.isEmpty(context.getItem()) || context.getItem().getDefinition().isEmpty()) {
            return false;
        }
        return isThisSteamerItem(context.getItem());
    }

    @Override
    public boolean canSurvive(Object thisBlock, Object[] args) {
        return true;
    }

    private boolean shouldHasBase(Object level, BlockPos pos) {
        return SteamerWorld.hasBase(level, LocationUtils.below(LocationUtils.toBlockPos(pos)), blockDefinition);
    }

    private void playSound(World world, Vec3d pos, Key sound) {
        world.playSound(pos, sound, 1.0f, 1.0f, SoundSource.BLOCK);
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new SteamerController(blockEntity, this);
    }

    @Override
    public void initControllerId(int id) {
        this.controllerId = id;
    }

    public int getControllerId() {
        return this.controllerId;
    }

    public Property<SlabType> getTypeProperty() {
        return typeProperty;
    }

    public Property<Boolean> getHasLidProperty() {
        return hasLidProperty;
    }

    public Property<Boolean> getHasBaseProperty() {
        return hasBaseProperty;
    }

    public Property<Direction> getFacingProperty() {
        return facingProperty;
    }

    private static class Factory implements BlockBehaviorFactory<SteamerBehavior> {
        @Override
        public SteamerBehavior create(BlockDefinition block, ConfigSection section) {
            SteamerBehavior behavior = new SteamerBehavior(block);
            behavior.typeProperty = BlockBehaviorFactory.getProperty(section.path(), block, "type", SlabType.class);
            behavior.hasLidProperty = BlockBehaviorFactory.getProperty(section.path(), block, "has_lid", Boolean.class);
            behavior.hasBaseProperty = BlockBehaviorFactory.getProperty(section.path(), block, "has_base", Boolean.class);
            behavior.facingProperty = BlockBehaviorFactory.getProperty(section.path(), block, "facing", Direction.class);

            behavior.cookingTime = BehaviorConfig.getInt(section, behavior.cookingTime, "cooking_time", "cooking-time");
            behavior.campfireStackHeight = BehaviorConfig.getInt(section, behavior.campfireStackHeight, "campfire_stack_height", "campfire-stack-height");
            behavior.stoveStackHeight = BehaviorConfig.getInt(section, behavior.stoveStackHeight, "stove_stack_height", "stove-stack-height");
            behavior.particleInterval = BehaviorConfig.getInt(section, behavior.particleInterval, "particle_interval", "particle-interval");
            behavior.particleCount = BehaviorConfig.getInt(section, behavior.particleCount, "particle_count", "particle-count");
            behavior.portableContents = section.getBoolean("portable_contents", false);
            return behavior;
        }
    }

    // pendingData 握着 NMS 实体的强引用 关服时 runLater 不会再跑 必须显式清
    public static void clearAll() {
        pendingData.clear();
    }
}
