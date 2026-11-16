package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.block.entity.PlacedMealController;
import net.kaleidoscope.cookery.catalog.CookeryCatalog;
import net.kaleidoscope.cookery.catalog.PlacedMealDefinition;
import net.kaleidoscope.cookery.catalog.PlacedMealLayout;
import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import net.kaleidoscope.cookery.util.BehaviorConfig;
import net.kaleidoscope.cookery.util.BlockStates;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.util.LevelUtils;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.GameEvent;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public final class PlacedMealBehavior extends BukkitBlockBehavior implements EntityBlock {
    public static final BlockBehaviorFactory<PlacedMealBehavior> FACTORY = new Factory();
    private static final CookeryCatalog CATALOG = CookeryCatalog.bundled();
    private PlacedMealDefinition definition;
    private Property<Integer> bites;
    private Property<Direction> facing;
    private Property<Integer> position;
    private Property<String> part;
    private int controllerId;

    private PlacedMealBehavior(BlockDefinition block) { super(block); }
    public PlacedMealDefinition definition() { return definition; }
    public Key id() { return blockDefinition.id(); }
    public int bites(ImmutableBlockState state) { return BlockStates.value(state, bites, 0); }
    public Direction facing(ImmutableBlockState state) { return BlockStates.value(state, facing, Direction.SOUTH); }
    public String part(ImmutableBlockState state) {
        return switch (definition.layout()) {
            case SINGLE -> "single";
            case DOUBLE -> BlockStates.value(state, position, 1) == 0 ? "left" : "right";
            case NINE -> BlockStates.value(state, part, "center");
        };
    }
    public ImmutableBlockState state(ImmutableBlockState base, String cell, int consumed) {
        ImmutableBlockState state = BlockStates.with(base, bites, consumed);
        return switch (definition.layout()) {
            case SINGLE -> state;
            case DOUBLE -> BlockStates.with(state, position, cell.equals("left") ? 0 : 1);
            case NINE -> BlockStates.with(state, part, cell);
        };
    }
    public PlacedMealLayout.Facing orientation(ImmutableBlockState state) {
        return PlacedMealLayout.Facing.valueOf(facing(state).name());
    }
    public BlockPos anchor(BlockPos clicked, ImmutableBlockState state) {
        var anchor = definition.layout().anchor(new PlacedMealLayout.Pos(clicked.x(), clicked.y(), clicked.z()),
                orientation(state), part(state));
        return new BlockPos(anchor.x(), anchor.y(), anchor.z());
    }
    public List<PlacedMealLayout.Cell> cells(BlockPos anchor, ImmutableBlockState state) {
        return definition.layout().cells(new PlacedMealLayout.Pos(anchor.x(), anchor.y(), anchor.z()), orientation(state));
    }
    @Override public boolean hasAnalogOutputSignal(Object block, Object[] args) { return true; }
    @Override public int getAnalogOutputSignal(Object block, Object[] args) {
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        // The source formula goes negative after three bites; redstone output stays in its valid range.
        return state == null ? 0 : Math.clamp((3 - bites(state)) * 5, 0, 15);
    }
    @Override public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        return state(BlockStates.with(state, facing, context.getHorizontalDirection().opposite()),
                definition.layout().anchorPart(), 0);
    }
    @Override public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        var player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        var world = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        if (!InteractGuard.canInteract(player, world, clicked)) return InteractionResult.SUCCESS_AND_CANCEL;
        BlockPos anchor = anchor(clicked, state);
        org.bukkit.World bukkit = (org.bukkit.World) world.platformWorld();
        if (!org.bukkit.Bukkit.isOwnedByCurrentRegion(bukkit, anchor.x() >> 4, anchor.z() >> 4)) return InteractionResult.FAIL;
        BlockEntity entity = world.storageWorld().getBlockEntityAtIfLoaded(anchor);
        var controller = entity == null ? null : entity.controller.get(PlacedMealController.class, controllerId);
        if (controller == null || !controller.complete()) return InteractionResult.FAIL;
        if (!InteractGuard.canInteract(player, world, anchor)) return InteractionResult.SUCCESS_AND_CANCEL;
        if (controller.state().empty()) {
            for (var cell : cells(anchor, state)) {
                var pos = cell.pos();
                if (!InteractGuard.canBreak(player, world, pos.x(), pos.y(), pos.z())) return InteractionResult.SUCCESS_AND_CANCEL;
            }
            return net.kaleidoscope.cookery.block.listener.PlacedMealBreakListener.removeEmpty(
                    (org.bukkit.entity.Player) player.platformPlayer(), controller)
                    ? InteractionResult.SUCCESS_AND_CANCEL : InteractionResult.FAIL;
        }
        var actor = (org.bukkit.entity.Player) player.platformPlayer();
        boolean ate = controller.consume((food, commit) -> KaleidoscopeCookeryPlugin.instance()
                .placedMealConsumption().consume(actor, food, definition.itemId(), commit));
        if (!ate) return InteractionResult.SUCCESS_AND_CANCEL;
        bukkit.playSound(actor.getLocation(), Sound.ENTITY_GENERIC_EAT, SoundCategory.PLAYERS, .5f,
                .9f + ThreadLocalRandom.current().nextFloat() * .1f);
        LevelUtils.sendGameEvent(bukkit, actor, GameEvent.EAT, new Vector(anchor.x(), anchor.y(), anchor.z()));
        player.swingHand(context.getHand());
        return InteractionResult.SUCCESS_AND_CANCEL;
    }
    @Override public BlockEntityController createBlockEntityController(BlockEntity entity) {
        return new PlacedMealController(entity, this);
    }
    @Override public void initControllerId(int id) { controllerId = id; }

    private static final class Factory implements BlockBehaviorFactory<PlacedMealBehavior> {
        @Override public PlacedMealBehavior create(BlockDefinition block, ConfigSection section) {
            var behavior = new PlacedMealBehavior(block);
            String id = BehaviorConfig.getString(section, block.id().asString(), "food_id");
            behavior.definition = PlacedMealDefinition.from(CATALOG.snapshot().items().get(id));
            if (!id.equals(block.id().asString())
                    || BehaviorConfig.getInt(section, -1, "max_bites") != behavior.definition.maxBites()
                    || !BehaviorConfig.getString(section, "", "layout").equals(behavior.definition.layout().id())
                    || !BehaviorConfig.getStringList(section, List.of(), "drops").equals(behavior.definition.drops())) {
                throw new IllegalArgumentException("Placed meal config differs from the pinned catalog: " + block.id());
            }
            behavior.bites = BlockBehaviorFactory.getProperty(section.path(), block, "bites", Integer.class);
            behavior.facing = BlockBehaviorFactory.getProperty(section.path(), block, "facing", Direction.class);
            behavior.position = BlockBehaviorFactory.getOptionalProperty(block, "position", Integer.class);
            behavior.part = BlockBehaviorFactory.getOptionalProperty(block, "part", String.class);
            if (behavior.definition.layout() == PlacedMealLayout.DOUBLE) Objects.requireNonNull(behavior.position, "position");
            if (behavior.definition.layout() == PlacedMealLayout.NINE) Objects.requireNonNull(behavior.part, "part");
            return behavior;
        }
    }
}
