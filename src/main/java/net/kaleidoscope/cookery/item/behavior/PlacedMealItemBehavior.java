package net.kaleidoscope.cookery.item.behavior;

import net.kaleidoscope.cookery.block.behavior.PlacedMealBehavior;
import net.kaleidoscope.cookery.block.entity.PlacedMealController;
import net.kaleidoscope.cookery.item.CookeryFoodItems;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.kaleidoscope.cookery.util.PlacementGuard;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockAttemptPlaceEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockPlaceEvent;
import net.momirealms.craftengine.bukkit.item.behavior.BlockItemBehavior;
import net.momirealms.craftengine.bukkit.util.AdventureModeUtils;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.DirectionUtils;
import net.momirealms.craftengine.bukkit.util.LevelUtils;
import net.momirealms.craftengine.bukkit.world.BukkitExistingBlock;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.context.ContextHolder;
import net.momirealms.craftengine.core.plugin.context.EventTrigger;
import net.momirealms.craftengine.core.plugin.context.PlayerOptionalContext;
import net.momirealms.craftengine.core.plugin.context.function.Function;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.util.Cancellable;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import org.bukkit.Bukkit;
import org.bukkit.GameEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One placement owns its cells until all events agree and the source item is debited. */
public final class PlacedMealItemBehavior extends BlockItemBehavior {
    public static final ItemBehaviorFactory<PlacedMealItemBehavior> FACTORY = new Factory();
    private static final ThreadLocal<Placement> ACTIVE = new ThreadLocal<>();

    private static final class Placement {
        final World world;
        final BlockPos anchor;
        final ImmutableBlockState state;
        final PlacedMealBehavior behavior;
        final Map<BlockPos, BlockState> previous = new LinkedHashMap<>();
        final Map<BlockPos, PlacedMealController> owned = new LinkedHashMap<>();
        BlockPos writing;
        UUID group;
        boolean committed;

        Placement(Location location, ImmutableBlockState state, PlacedMealBehavior behavior) {
            world = location.getWorld();
            anchor = pos(location);
            this.state = state;
            this.behavior = behavior;
        }
    }

    public PlacedMealItemBehavior(Key block) { super(block); }
    public static boolean placing() { return ACTIVE.get() != null; }

    public static boolean placing(BlockEntity entity) {
        Placement placement = ACTIVE.get();
        return placement != null && entity.world != null
                && entity.world.world().platformWorld() == placement.world && placement.previous.containsKey(entity.pos);
    }

    /** Called during controller creation, before CE can dispatch placement physics callbacks. */
    public static void created(PlacedMealController controller) {
        Placement placement = ACTIVE.get();
        BlockEntity entity = controller.blockEntity();
        if (placement != null && entity.pos.equals(placement.writing)
                && controller.behavior().id().equals(placement.behavior.id())) {
            placement.owned.putIfAbsent(entity.pos, controller);
        }
    }

    @Override protected ImmutableBlockState updateBlockStateProperties(ImmutableBlockState state, Item item) {
        return state;
    }

    @Override public InteractionResult place(BlockPlaceContext context) {
        if (placing()) return InteractionResult.FAIL;
        Item held = context.getItem();
        if (held == null || held.isEmpty() || held.count() <= 0 || !context.canPlace()) return InteractionResult.FAIL;
        CookeryFoodItems.read(InventoryUtils.bukkitCopy(held));
        BlockDefinition definition = CraftEngineBlocks.byId(block());
        if (definition == null) return InteractionResult.FAIL;
        ImmutableBlockState state = getPlacementState(context, definition);
        if (state == null || !adventureAllowed(context)) return InteractionResult.FAIL;
        PlacedMealBehavior behavior = state.behavior().getFirst(PlacedMealBehavior.class);
        if (behavior == null || !held.id().asString().equals(behavior.definition().itemId())) return InteractionResult.FAIL;
        Item source = held.copyWithCount(1);
        int sourceCount = held.count();
        Location location = location((World) context.getLevel().platformWorld(), context.getClickedPos());
        Placement placement = new Placement(location, state, behavior);
        ACTIVE.set(placement);
        try {
            if (!prepare(placement, context) || !attempt(placement, context) || !stillReplaceable(placement, context)
                    || !writeCells(placement) || !placementEvents(placement, context) || !placeFunctions(placement, context)) {
                return InteractionResult.FAIL;
            }
            if (!validSource(context, source, sourceCount) || !complete(placement)) return InteractionResult.FAIL;
            PlacedMealController root = placement.owned.get(placement.anchor);
            root.loadCustomDataFromItem(source.copy());
            if (!complete(placement) || !validSource(context, source, sourceCount)) return InteractionResult.FAIL;
            // No callbacks occur between the final ownership/source checks and this debit.
            if (context.getPlayer() != null && !context.getPlayer().isCreativeMode()) {
                Item current = context.getPlayer().getItemInHand(context.getHand());
                current.count(current.count() - 1);
            }
            placement.committed = true;
            placementFeedback(placement, context);
            return InteractionResult.SUCCESS;
        } finally {
            try { if (!placement.committed) rollback(placement); }
            finally { ACTIVE.remove(); }
        }
    }

    private static boolean validSource(BlockPlaceContext context, Item source, int sourceCount) {
        Item current = context.getPlayer() == null ? context.getItem() : context.getPlayer().getItemInHand(context.getHand());
        return current != null && !current.isEmpty() && current.count() == sourceCount && current.isSimilar(source);
    }

    private static boolean adventureAllowed(BlockPlaceContext context) {
        var player = context.getPlayer();
        if (player == null || !player.isAdventureMode()) return true;
        BlockPos against = context.getAgainstPos();
        World world = (World) context.getLevel().platformWorld();
        Object state = BlockStateUtils.blockDataToBlockState(world.getBlockAt(against.x(), against.y(), against.z()).getBlockData());
        var custom = BlockStateUtils.getOptionalCustomBlockState(state);
        if (Config.simplifyAdventurePlaceCheck() && custom.isPresent()) state = custom.get().visualBlockState().minecraftState();
        return AdventureModeUtils.canPlace(context.getItem(), context.getLevel(), against, state);
    }

    @Override protected boolean canPlace(BlockPlaceContext context, ImmutableBlockState state) {
        var behavior = state.behavior().getFirst(PlacedMealBehavior.class);
        if (behavior == null) return false;
        World world = (World) context.getLevel().platformWorld();
        for (var cell : behavior.cells(context.getClickedPos(), state)) {
            BlockPos pos = new BlockPos(cell.pos().x(), cell.pos().y(), cell.pos().z());
            if (!accessible(world, pos) || !world.getBlockAt(pos.x(), pos.y(), pos.z()).isReplaceable()
                    || !allowed(context, pos)) return false;
        }
        return super.canPlace(context, state);
    }

    private static boolean prepare(Placement placement, BlockPlaceContext context) {
        for (var cell : placement.behavior.cells(placement.anchor, placement.state)) {
            BlockPos pos = new BlockPos(cell.pos().x(), cell.pos().y(), cell.pos().z());
            if (!accessible(placement.world, pos) || !allowed(context, pos)) return false;
            Block block = placement.world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (!block.isReplaceable()) return false;
            placement.previous.put(pos, block.getState());
        }
        return true;
    }

    private static boolean accessible(World world, BlockPos pos) {
        return world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)
                && Bukkit.isOwnedByCurrentRegion(world, pos.x() >> 4, pos.z() >> 4)
                && pos.y() >= world.getMinHeight() && pos.y() < world.getMaxHeight();
    }

    private static boolean allowed(BlockPlaceContext context, BlockPos pos) {
        return context == null || context.getPlayer() == null
                || InteractGuard.canPlace(context.getPlayer(), context.getLevel(), pos);
    }

    private static boolean attempt(Placement placement, BlockPlaceContext context) {
        if (context.getPlayer() == null) return true;
        var player = (org.bukkit.entity.Player) context.getPlayer().platformPlayer();
        for (BlockPos pos : placement.previous.keySet()) {
            var event = new CustomBlockAttemptPlaceEvent(player, location(placement.world, pos), placement.state,
                    DirectionUtils.toBlockFace(context.getClickedFace()), placement.world.getBlockAt(pos.x(), pos.y(), pos.z()), context.getHand());
            if (!event.callEvent()) return false;
        }
        return true;
    }

    private static boolean stillReplaceable(Placement placement, BlockPlaceContext context) {
        for (BlockPos pos : placement.previous.keySet()) {
            if (!accessible(placement.world, pos) || !allowed(context, pos)
                    || !placement.world.getBlockAt(pos.x(), pos.y(), pos.z()).isReplaceable()) return false;
        }
        return true;
    }

    private static boolean writeCells(Placement placement) {
        for (var cell : placement.behavior.cells(placement.anchor, placement.state)) {
            BlockPos pos = new BlockPos(cell.pos().x(), cell.pos().y(), cell.pos().z());
            if (!accessible(placement.world, pos) || !placement.world.getBlockAt(pos.x(), pos.y(), pos.z()).isReplaceable()) return false;
            placement.writing = pos;
            try {
                if (!CraftEngineBlocks.place(location(placement.world, pos), placement.behavior.state(placement.state, cell.part(), 0),
                        UpdateFlags.UPDATE_ALL, false)) return false;
                PlacedMealController current = controller(placement.world, pos);
                if (current == null || placement.owned.get(pos) != current) return false;
            } finally { placement.writing = null; }
        }
        PlacedMealController root = placement.owned.get(placement.anchor);
        if (root == null || !root.assemble()) return false;
        placement.group = root.group();
        return complete(placement);
    }

    private static boolean complete(Placement placement) {
        if (placement.group == null || placement.owned.size() != placement.previous.size()) return false;
        for (var entry : placement.owned.entrySet()) {
            PlacedMealController current = controller(placement.world, entry.getKey());
            if (current != entry.getValue() || !current.group().equals(placement.group)) return false;
        }
        return placement.owned.get(placement.anchor).complete();
    }

    private static boolean placementEvents(Placement placement, BlockPlaceContext context) {
        if (context.getPlayer() == null) return true;
        var player = (org.bukkit.entity.Player) context.getPlayer().platformPlayer();
        Block anchor = placement.world.getBlockAt(placement.anchor.x(), placement.anchor.y(), placement.anchor.z());
        BlockPos againstPos = context.getAgainstPos();
        Block against = placement.world.getBlockAt(againstPos.x(), againstPos.y(), againstPos.z());
        var hand = context.getHand() == InteractionHand.MAIN_HAND ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        var item = InventoryUtils.bukkitCopy(context.getItem());
        List<BlockState> previous = new ArrayList<>(placement.previous.values());
        BlockPlaceEvent bukkit = previous.size() == 1
                ? new BlockPlaceEvent(anchor, previous.getFirst(), against, item, player, true, hand)
                : new BlockMultiPlaceEvent(previous, against, item, player, true, hand);
        if (!bukkit.callEvent() || !bukkit.canBuild() || !complete(placement)) return false;
        var custom = new CustomBlockPlaceEvent(player, location(placement.world, placement.anchor), placement.state, anchor, context.getHand());
        return custom.callEvent() && complete(placement);
    }

    private static boolean placeFunctions(Placement placement, BlockPlaceContext context) {
        List<Function<net.momirealms.craftengine.core.plugin.context.Context>> functions = placement.behavior.block().eventFunctions(EventTrigger.PLACE);
        if (functions.isEmpty()) return true;
        Cancellable cancelled = Cancellable.dummy();
        var holder = ContextHolder.builder()
                .withOptionalParameter(DirectContextParameters.PLAYER, context.getPlayer())
                .withParameter(DirectContextParameters.BLOCK, new BukkitExistingBlock(placement.world.getBlockAt(
                        placement.anchor.x(), placement.anchor.y(), placement.anchor.z())))
                .withParameter(DirectContextParameters.POSITION, position(context, placement.anchor))
                .withParameter(DirectContextParameters.EVENT, cancelled)
                .withParameter(DirectContextParameters.HAND, context.getHand())
                .withOptionalParameter(DirectContextParameters.ITEM_IN_HAND, context.getItem());
        Function.execute(PlayerOptionalContext.of(context.getPlayer(), holder), functions);
        return !cancelled.isCancelled() && complete(placement);
    }

    private static WorldPosition position(BlockPlaceContext context, BlockPos pos) {
        return new WorldPosition(context.getLevel(), pos.x() + .5, pos.y() + .5, pos.z() + .5);
    }

    private void placementFeedback(Placement placement, BlockPlaceContext context) {
        if (context.getPlayer() != null) context.getPlayer().swingHand(context.getHand());
        context.getLevel().playBlockSound(position(context, placement.anchor), placement.state.settings().sounds().placeSound());
        sendPlaceGameEvent(context, placement.anchor);
    }

    protected void sendPlaceGameEvent(BlockPlaceContext context, BlockPos anchor) {
        LevelUtils.sendGameEvent((World) context.getLevel().platformWorld(),
                context.getPlayer() == null ? null : (org.bukkit.entity.Player) context.getPlayer().platformPlayer(),
                GameEvent.BLOCK_PLACE, new Vector(anchor.x(), anchor.y(), anchor.z()));
    }

    /** Server fixtures use the same cell ownership and rollback path, without a player debit. */
    public static boolean placeAt(Location location, ImmutableBlockState state) {
        if (placing()) return false;
        var behavior = state.behavior().getFirst(PlacedMealBehavior.class);
        if (behavior == null) throw new IllegalArgumentException("Not a meal state");
        Placement placement = new Placement(location, state, behavior);
        ACTIVE.set(placement);
        try {
            if (!prepare(placement, null) || !writeCells(placement)) return false;
            placement.committed = true;
            return true;
        } finally {
            try { if (!placement.committed) rollback(placement); }
            finally { ACTIVE.remove(); }
        }
    }

    private static void rollback(Placement placement) {
        IllegalStateException failure = null;
        for (var entry : placement.owned.entrySet()) {
            BlockPos pos = entry.getKey();
            if (controller(placement.world, pos) != entry.getValue()) continue;
            try {
                if (!placement.previous.get(pos).update(true, false)) throw new IllegalStateException("Could not roll back meal at " + pos);
            } catch (RuntimeException exception) {
                if (failure == null) failure = new IllegalStateException("Meal rollback failed", exception);
                else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    private static PlacedMealController controller(World world, BlockPos pos) {
        if (!accessible(world, pos)) return null;
        var entity = BukkitAdaptor.adapt(world).storageWorld().getBlockEntityAtIfLoaded(pos);
        return entity == null ? null : entity.controller.get(PlacedMealController.class, 0);
    }

    private static Location location(World world, BlockPos pos) { return new Location(world, pos.x(), pos.y(), pos.z()); }
    private static BlockPos pos(Location location) { return new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ()); }

    private static final class Factory implements ItemBehaviorFactory<PlacedMealItemBehavior> {
        @Override public PlacedMealItemBehavior create(Pack pack, Path path, Key id, ConfigSection section) {
            return new PlacedMealItemBehavior(section.getNonNullValue("block",
                    net.momirealms.craftengine.core.plugin.config.ConfigConstants.ARGUMENT_SECTION).getAsIdentifier());
        }
    }
}
