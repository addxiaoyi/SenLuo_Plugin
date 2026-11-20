package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.nms.NmsBridgeProvider;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import org.bukkit.entity.LivingEntity;
import java.util.concurrent.ThreadLocalRandom;

/** Replace vanilla fallOn entirely, as the source does, so landing never deals fall damage. */
public final class StrawBlockBehavior extends BukkitBlockBehavior {
    public static final BlockBehaviorFactory<StrawBlockBehavior> FACTORY = new Factory();
    private final Property<Direction.Axis> axis;
    private StrawBlockBehavior(BlockDefinition block, Property<Direction.Axis> axis) {
        super(block); this.axis = axis;
    }
    @Override public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        return state.with(axis, context.getClickedFace().axis());
    }
    @Override public void fallOn(Object thisBlock, Object[] args) {
        Object entity = NmsBridgeProvider.bridge().bukkitEntity(args[3]);
        if (!(entity instanceof LivingEntity living)) return;
        var pos = LocationUtils.fromBlockPos(args[2]);
        if (!org.bukkit.Bukkit.isOwnedByCurrentRegion(living.getWorld(), pos.x() >> 4, pos.z() >> 4)) return;
        StrawLanding.land(living.getWorld().getBlockAt(pos.x(), pos.y(), pos.z()), living,
                ((Number) args[4]).doubleValue(), ThreadLocalRandom.current()::nextFloat);
    }
    private static final class Factory implements BlockBehaviorFactory<StrawBlockBehavior> {
        @Override public StrawBlockBehavior create(BlockDefinition block, ConfigSection section) {
            return new StrawBlockBehavior(block, BlockBehaviorFactory.getProperty(section.path(), block, "axis", Direction.Axis.class));
        }
    }
}
