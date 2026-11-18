package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.block.entity.LongBenchController;
import net.kaleidoscope.cookery.util.BlockStates;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.DirectionUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.proxy.minecraft.core.BlockPosProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;

/** A bench keeps its placement axis and connects only to benches on that axis. */
public final class LongBenchBehavior extends BukkitBlockBehavior implements EntityBlock {
    public static final BlockBehaviorFactory<LongBenchBehavior> FACTORY = new Factory();
    private final Property<Direction.Axis> axis;
    private final IntegerProperty position;
    private int controllerId;

    LongBenchBehavior(BlockDefinition block, Property<Direction.Axis> axis, IntegerProperty position) {
        super(block); this.axis = axis; this.position = position;
    }
    public Direction.Axis axis(ImmutableBlockState state) {
        return BlockStates.value(state, axis, Direction.Axis.X);
    }
    @Override public void initControllerId(int id) { controllerId = id; }
    @Override public BlockEntityController createBlockEntityController(BlockEntity entity) {
        return new LongBenchController(entity, this);
    }
    @Override public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null || context.getPlayer().isSecondaryUseActive()) return InteractionResult.PASS;
        var pos = context.getClickedPos();
        var world = (org.bukkit.World) context.getLevel().platformWorld();
        if (!org.bukkit.Bukkit.isOwnedByCurrentRegion(world, pos.x() >> 4, pos.z() >> 4)) return InteractionResult.FAIL;
        BlockEntity entity = context.getLevel().storageWorld().getBlockEntityAtIfLoaded(context.getClickedPos());
        if (entity == null) return InteractionResult.PASS;
        LongBenchController bench = entity.controller.get(LongBenchController.class, controllerId);
        return bench == null ? InteractionResult.PASS : bench.interact(context.getPlayer());
    }
    @Override public InteractionResult useWithoutItem(UseOnContext context, ImmutableBlockState state) {
        return useOnBlock(context, state);
    }
    @Override public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        Direction.Axis placedAxis = placementAxis(context.getHorizontalDirection());
        return connected(context.getLevel().minecraftWorld(), LocationUtils.toBlockPos(context.getClickedPos()),
                state.with(axis, placedAxis));
    }
    @Override public Object updateShape(Object thisBlock, Object[] args) {
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.owner().value() != blockDefinition
                || DirectionUtils.fromNMSDirection(args[updateShape$direction]).axis() != axis(state)) return args[0];
        return connected(args[updateShape$level], args[updateShape$blockPos], state).customBlockState().minecraftState();
    }
    private ImmutableBlockState connected(Object level, Object pos, ImmutableBlockState state) {
        Direction.Axis current = axis(state);
        int dx = current == Direction.Axis.X ? 1 : 0, dz = current == Direction.Axis.Z ? 1 : 0;
        return connected(state, BlockStateUtils.getOptionalCustomBlockState(BlockGetterProxy.INSTANCE.getBlockState(level,
                BlockPosProxy.INSTANCE.offset(pos, -dx, 0, -dz))).orElse(null),
                BlockStateUtils.getOptionalCustomBlockState(BlockGetterProxy.INSTANCE.getBlockState(level,
                BlockPosProxy.INSTANCE.offset(pos, dx, 0, dz))).orElse(null));
    }
    static Direction.Axis placementAxis(Direction facing) {
        return facing.axis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
    }
    ImmutableBlockState connected(ImmutableBlockState state, ImmutableBlockState negativeState, ImmutableBlockState positiveState) {
        Direction.Axis current = axis(state);
        boolean negative = links(negativeState, current), positive = links(positiveState, current);
        return state.with(position, negative ? (positive ? 2 : 1) : (positive ? 3 : 0));
    }
    private boolean links(ImmutableBlockState neighbor, Direction.Axis current) {
        return neighbor != null && neighbor.owner().value() == blockDefinition && axis(neighbor) == current;
    }
    private static final class Factory implements BlockBehaviorFactory<LongBenchBehavior> {
        @Override public LongBenchBehavior create(BlockDefinition block, ConfigSection section) {
            return new LongBenchBehavior(block,
                    BlockBehaviorFactory.getProperty(section.path(), block, "axis", Direction.Axis.class),
                    (IntegerProperty) BlockBehaviorFactory.getProperty(section.path(), block, "position", Integer.class));
        }
    }
}
