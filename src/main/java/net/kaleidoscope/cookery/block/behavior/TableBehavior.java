package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.block.entity.TableController;
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
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.proxy.minecraft.core.BlockPosProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;

// 桌子沿一个水平轴自由拼接
public final class TableBehavior extends BukkitBlockBehavior implements EntityBlock {
    public static final BlockBehaviorFactory<TableBehavior> FACTORY = new Factory();

    public static final int LINE_X = 0;
    public static final int LINE_Z = 1;

    private static final int SINGLE = 0;
    private static final int LEFT = 1;
    private static final int MIDDLE = 2;
    private static final int RIGHT = 3;


    private final IntegerProperty lineProperty;
    private final IntegerProperty positionProperty;
    private int controllerId;

    private TableBehavior(BlockDefinition block, IntegerProperty lineProperty, IntegerProperty positionProperty) {
        super(block);
        this.lineProperty = lineProperty;
        this.positionProperty = positionProperty;
    }

    public int line(ImmutableBlockState state) {
        return BlockStates.value(state, this.lineProperty, this.lineProperty.defaultValue());
    }

    public int position(ImmutableBlockState state) {
        return BlockStates.value(state, this.positionProperty, this.positionProperty.defaultValue());
    }

    @Override
    public void initControllerId(int id) {
        this.controllerId = id;
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new TableController(blockEntity, this);
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        Player player = context.getPlayer();
        if (player == null || context.getHand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        BlockEntity entity = context.getLevel().storageWorld().getBlockEntityAtIfLoaded(context.getClickedPos());
        if (entity == null) return InteractionResult.PASS;
        return entity.controller.let(TableController.class, controllerId, table -> { return table.interact(player); });
    }

    @Override
    public Object playerWillDestroy(Object thisBlock, Object[] args) {
        Object nmsPlayer = args.length > 3 ? args[3] : null;
        org.bukkit.entity.Player player = nmsPlayer == null ? null
                : net.kaleidoscope.cookery.nms.NmsBridgeProvider.bridge().bukkitPlayer(nmsPlayer);
        if (player != null && player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            var world = net.momirealms.craftengine.bukkit.world.BukkitWorldManager.instance().getWorld(
                    net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy.INSTANCE.getWorld(args[0]).getUID());
            BlockEntity entity = world.storageWorld().getBlockEntityAtIfLoaded(LocationUtils.fromBlockPos(args[1]));
            if (entity != null) entity.controller.let(TableController.class, controllerId, TableController::markCreativeBreak);
        }
        return args[2];
    }

    // 放置时定轴 玩家朝东西就沿南北接
    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        Object level = context.getLevel().minecraftWorld();
        Object pos = LocationUtils.toBlockPos(context.getClickedPos());
        return context.getHorizontalDirection().axis() == Direction.Axis.X
                ? checkNorthSouth(level, pos, state)
                : checkEastWest(level, pos, state);
    }

    // 邻居变化时重算自己
    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.owner().value() != super.blockDefinition) {
            return args[0];
        }
        Object level = args[updateShape$level];
        Object pos = args[updateShape$blockPos];
        Direction.Axis axis = DirectionUtils.fromNMSDirection(args[updateShape$direction]).axis();
        if (axis == Direction.Axis.X) {
            return checkEastWest(level, pos, state).customBlockState().minecraftState();
        }
        if (axis == Direction.Axis.Z) {
            return checkNorthSouth(level, pos, state).customBlockState().minecraftState();
        }
        return args[0];
    }

    private ImmutableBlockState checkEastWest(Object level, Object pos, ImmutableBlockState base) {
        if (line(base) == LINE_Z && position(base) != SINGLE) {
            return base;
        }
        boolean east = canLink(level, BlockPosProxy.INSTANCE.offset(pos, 1, 0, 0), LINE_Z);
        boolean west = canLink(level, BlockPosProxy.INSTANCE.offset(pos, -1, 0, 0), LINE_Z);
        return joined(base, LINE_X, east, west);
    }

    private ImmutableBlockState checkNorthSouth(Object level, Object pos, ImmutableBlockState base) {
        if (line(base) == LINE_X && position(base) != SINGLE) {
            return base;
        }
        boolean south = canLink(level, BlockPosProxy.INSTANCE.offset(pos, 0, 0, 1), LINE_X);
        boolean north = canLink(level, BlockPosProxy.INSTANCE.offset(pos, 0, 0, -1), LINE_X);
        return joined(base, LINE_Z, south, north);
    }

    private ImmutableBlockState joined(ImmutableBlockState base, int line, boolean forward, boolean backward) {
        if (!forward && !backward) {
            return base.with(this.positionProperty, SINGLE);
        }
        int position = forward && backward ? MIDDLE : forward ? LEFT : RIGHT;
        return base.with(this.positionProperty, position).with(this.lineProperty, line);
    }

    private boolean canLink(Object level, Object pos, int line) {
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(
                BlockGetterProxy.INSTANCE.getBlockState(level, pos)).orElse(null);
        if (state == null || state.owner().value() != super.blockDefinition) {
            return false;
        }
        return line(state) != line || position(state) == SINGLE;
    }

    private static class Factory implements BlockBehaviorFactory<TableBehavior> {
        @Override
        public TableBehavior create(BlockDefinition block, ConfigSection section) {
            return new TableBehavior(block,
                    (IntegerProperty) BlockBehaviorFactory.getProperty(section.path(), block, "line", Integer.class),
                    (IntegerProperty) BlockBehaviorFactory.getProperty(section.path(), block, "position", Integer.class));
        }
    }
}
