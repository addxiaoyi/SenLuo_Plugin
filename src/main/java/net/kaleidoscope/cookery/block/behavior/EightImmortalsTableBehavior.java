package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.block.entity.EightImmortalsTableController;
import net.kaleidoscope.cookery.util.BlockStates;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.core.block.*;
import net.momirealms.craftengine.core.block.behavior.*;
import net.momirealms.craftengine.core.block.entity.*;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import java.util.List;

/** Four parts, oriented exactly as the source (facing is not reversed). */
public final class EightImmortalsTableBehavior extends BukkitBlockBehavior implements EntityBlock {
    public static final BlockBehaviorFactory<EightImmortalsTableBehavior> FACTORY = new Factory();
    public static final List<String> PARTS = List.of("right_bottom", "left_bottom", "right_top", "left_top");
    private final Property<Direction> facing;
    private final Property<String> part;
    public EightImmortalsTableBehavior(BlockDefinition block, Property<Direction> facing, Property<String> part) {
        super(block); this.facing = facing; this.part = part;
    }
    public Direction facing(ImmutableBlockState state) { return BlockStates.value(state, facing, Direction.NORTH); }
    public String part(ImmutableBlockState state) { return BlockStates.value(state, part, "right_bottom"); }
    public ImmutableBlockState partState(ImmutableBlockState state, String name) { return state.with(part, name); }
    public BlockPos anchor(BlockPos pos, ImmutableBlockState state) {
        BlockPos offset = cell(new BlockPos(0, 0, 0), facing(state), part(state));
        return new BlockPos(pos.x() - offset.x(), pos.y(), pos.z() - offset.z());
    }
    public static BlockPos cell(BlockPos anchor, Direction facing, String part) {
        if (!PARTS.contains(part) || facing.axis() == Direction.Axis.Y) throw new IllegalArgumentException("Invalid table orientation");
        int dx = facing == Direction.EAST ? 1 : facing == Direction.WEST ? -1 : 0;
        int dz = facing == Direction.SOUTH ? 1 : facing == Direction.NORTH ? -1 : 0;
        int left = part.startsWith("left") ? 1 : 0, top = part.endsWith("top") ? 1 : 0;
        return new BlockPos(anchor.x() + dz * left + dx * top, anchor.y(), anchor.z() - dx * left + dz * top);
    }
    @Override public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        return state.with(facing, context.getHorizontalDirection()).with(part, "right_bottom");
    }
    @Override public void initControllerId(int id) {
        if (id != 0) throw new IllegalArgumentException("Eight immortals table must be its first controller");
    }
    @Override public BlockEntityController createBlockEntityController(BlockEntity entity) {
        return new EightImmortalsTableController(entity, this);
    }
    private static final class Factory implements BlockBehaviorFactory<EightImmortalsTableBehavior> {
        @Override public EightImmortalsTableBehavior create(BlockDefinition block, ConfigSection section) {
            return new EightImmortalsTableBehavior(block,
                    BlockBehaviorFactory.getProperty(section.path(), block, "facing", Direction.class),
                    BlockBehaviorFactory.getProperty(section.path(), block, "part", String.class));
        }
    }
}
