package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.entity.render.Particles;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.world.CEWorld;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.World;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

public final class OilBlockController extends BlockEntityController {
    public OilBlockController(BlockEntity blockEntity) { super(blockEntity); }
    @Override public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(
            CEWorld world, ImmutableBlockState state) {
        return BlockEntityController.<C, OilBlockController>createTickerHelper(
                (level, pos, currentState, controller) -> controller.animate(ThreadLocalRandom.current()));
    }
    void animate(RandomGenerator random) {
        if (blockEntity.world == null || !blockEntity.isValid()) return;
        World world = (World) blockEntity.world.world().platformWorld();
        var pos = blockEntity.pos;
        if (!Bukkit.isOwnedByCurrentRegion(world, pos.x() >> 4, pos.z() >> 4)
                || blockEntity.world.getBlockEntityAtIfLoaded(pos) != blockEntity
                || random.nextInt(50) != 0) return;
        double x = pos.x() + .5 + random.nextDouble() / 3 * (random.nextBoolean() ? 1 : -1);
        double y = pos.y() + 1 + random.nextDouble() / 3;
        double z = pos.z() + .5 + random.nextDouble() / 3 * (random.nextBoolean() ? 1 : -1);
        // count=0 sends the source velocity vector to one wax-off particle.
        Particles.emit(world, Particle.WAX_OFF, x, y, z, 0, .3, .1, .3, 1, null);
    }
}
