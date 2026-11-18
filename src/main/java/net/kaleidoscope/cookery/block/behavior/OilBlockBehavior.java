package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.block.entity.OilBlockController;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;

/** Friction and fire tags are native block settings; particles need a server ticker. */
public final class OilBlockBehavior extends BukkitBlockBehavior implements EntityBlock {
    public static final BlockBehaviorFactory<OilBlockBehavior> FACTORY = (block, section) -> new OilBlockBehavior(block);
    private OilBlockBehavior(BlockDefinition block) { super(block); }
    @Override public void initControllerId(int id) {}
    @Override public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new OilBlockController(blockEntity);
    }
}
