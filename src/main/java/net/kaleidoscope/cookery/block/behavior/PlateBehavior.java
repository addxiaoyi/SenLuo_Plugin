package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.block.entity.PlateController;
import net.kaleidoscope.cookery.util.BehaviorConfig;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.kaleidoscope.cookery.util.BlockStates;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.sound.SoundSource;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;

import java.util.List;
import java.util.HashSet;

/** CraftEngine interaction bridge for the source plate and platter blocks. */
public final class PlateBehavior extends BukkitBlockBehavior implements EntityBlock {
    public static final BlockBehaviorFactory<PlateBehavior> FACTORY = new Factory();
    private static final Key ADD_SOUND = Key.of("minecraft:entity.item_frame.add_item");
    private static final Key TAKE_SOUND = Key.of("minecraft:entity.item_frame.remove_item");

    private int controllerId;
    private Property<Integer> servingsProperty;
    private Property<Direction> facingProperty;
    private List<Key> servingItems = List.of();
    private List<String> drops = List.of();
    private int maxServings = 1;

    private PlateBehavior(BlockDefinition blockDefinition) {
        super(blockDefinition);
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        Player player = context.getPlayer();
        if (player == null || context.getHand() == InteractionHand.OFF_HAND) {
            return InteractionResult.PASS;
        }
        World world = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!InteractGuard.canInteract(player, world, pos)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        BlockEntity blockEntity = world.storageWorld().getBlockEntityAtIfLoaded(pos);
        if (blockEntity == null) {
            return InteractionResult.PASS;
        }
        PlateController controller = blockEntity.controller.get(PlateController.class, controllerId);
        if (controller == null) {
            return InteractionResult.PASS;
        }
        Item held = player.getItemInHand(InteractionHand.MAIN_HAND);
        boolean refilled = !held.isEmpty() && controller.place(player, InteractionHand.MAIN_HAND, held);
        boolean changed = refilled;
        if (!changed) {
            if (controller.servings() > 0) changed = controller.take(player, InteractionHand.MAIN_HAND);
            else if (InteractGuard.canBreak(player, world, pos.x(), pos.y(), pos.z())) changed = controller.removeIfEmpty();
        }
        if (!changed) {
            return InteractionResult.PASS;
        }
        player.swingHand(InteractionHand.MAIN_HAND);
        world.playSound(Vec3d.atCenterOf(pos), refilled ? ADD_SOUND : TAKE_SOUND, 1, 1, SoundSource.BLOCK);
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    public boolean accepts(Key id) {
        return id != null && servingItems.size() == 1 && servingItems.contains(id);
    }

    public List<Key> servingItems() { return servingItems; }

    public int maxServings() {
        return maxServings;
    }

    public List<String> drops() {
        return drops;
    }

    public Property<Integer> servingsProperty() {
        return servingsProperty;
    }

    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        return BlockStates.with(state, facingProperty, context.getHorizontalDirection().opposite());
    }

    public boolean removeAfterConsumed(CEWorld world, BlockPos pos) {
        org.bukkit.World bukkit = (org.bukkit.World) world.world().platformWorld();
        return CraftEngineBlocks.remove(bukkit.getBlockAt(pos.x(), pos.y(), pos.z()), null, false, true, true);
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new PlateController(blockEntity, this);
    }

    @Override
    public void initControllerId(int id) {
        controllerId = id;
    }

    private static final class Factory implements BlockBehaviorFactory<PlateBehavior> {
        @Override
        public PlateBehavior create(BlockDefinition block, ConfigSection section) {
            PlateBehavior behavior = new PlateBehavior(block);
            behavior.servingsProperty = BlockBehaviorFactory.getProperty(section.path(), block, "servings", Integer.class);
            behavior.facingProperty = BlockBehaviorFactory.getOptionalProperty(block, "facing", Direction.class);
            behavior.maxServings = BehaviorConfig.getInt(section, 1, "max_servings", "max-servings");
            behavior.servingItems = BehaviorConfig.getStringList(section, List.of(), "serving_items", "serving-items")
                    .stream().map(Key::of).toList();
            behavior.drops = List.copyOf(BehaviorConfig.getStringList(section, List.of(), "drops"));
            if (behavior.maxServings < 1 || behavior.maxServings > 64 || behavior.servingItems.isEmpty()
                    || new HashSet<>(behavior.servingItems).size() != behavior.servingItems.size()
                    || behavior.servingsProperty.indexOf(0) == -1
                    || behavior.servingsProperty.indexOf(behavior.maxServings) == -1) {
                throw new IllegalArgumentException("Invalid serving plate definition at " + section.path());
            }
            behavior.drops.forEach(Key::of);
            return behavior;
        }
    }
}
