package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.block.entity.TeaBannerController;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.block.property.type.AnchorType;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;

/** Server-side state bridge for the Cookery tea banner. */
public final class TeaBannerBehavior extends BukkitBlockBehavior implements EntityBlock {
    public static final BlockBehaviorFactory<TeaBannerBehavior> FACTORY = new Factory();

    private int controllerId;
    private Property<AnchorType> anchorTypeProperty;
    private Property<Integer> colorProperty;
    private Property<Integer> patternProperty;

    private TeaBannerBehavior(BlockDefinition blockDefinition) {
        super(blockDefinition);
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        Player player = context.getPlayer();
        if (player == null || context.getHand() == InteractionHand.OFF_HAND) {
            return InteractionResult.PASS;
        }
        if (!InteractGuard.canInteract(player, context.getLevel(), context.getClickedPos())) {
            return InteractionResult.PASS;
        }
        BlockEntity blockEntity = context.getLevel().storageWorld().getBlockEntityAtIfLoaded(context.getClickedPos());
        if (blockEntity == null) {
            return InteractionResult.PASS;
        }
        TeaBannerController controller = blockEntity.controller.get(TeaBannerController.class, controllerId);
        return controller == null ? InteractionResult.PASS : controller.use(player, context.getHand());
    }

    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        return state.get(anchorTypeProperty) == AnchorType.CEILING ? null : state;
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new TeaBannerController(blockEntity, this);
    }

    public Property<Integer> colorProperty() {
        return colorProperty;
    }

    public Property<Integer> patternProperty() {
        return patternProperty;
    }

    @Override
    public void initControllerId(int id) {
        controllerId = id;
    }

    private static final class Factory implements BlockBehaviorFactory<TeaBannerBehavior> {
        @Override
        public TeaBannerBehavior create(BlockDefinition block, ConfigSection section) {
            TeaBannerBehavior behavior = new TeaBannerBehavior(block);
            behavior.anchorTypeProperty = BlockBehaviorFactory.getProperty(section.path(), block, "face", AnchorType.class);
            behavior.colorProperty = BlockBehaviorFactory.getProperty(section.path(), block, "color", Integer.class);
            behavior.patternProperty = BlockBehaviorFactory.getProperty(section.path(), block, "pattern", Integer.class);
            return behavior;
        }
    }
}
