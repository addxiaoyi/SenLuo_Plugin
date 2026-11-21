package net.kaleidoscope.cookery.item.behavior;

import net.kaleidoscope.cookery.block.behavior.EightImmortalsTableBehavior;
import net.kaleidoscope.cookery.block.entity.EightImmortalsTableController;
import net.kaleidoscope.cookery.nms.NmsBridgeProvider;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.kaleidoscope.cookery.util.InventoryUtils;
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
public final class EightImmortalsTableItemBehavior extends BlockItemBehavior {
    public static final ItemBehaviorFactory<EightImmortalsTableItemBehavior> FACTORY = new Factory();
    private static final ThreadLocal<Placement> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> false);

    private static final class Placement {
        final World world;
        final BlockPos anchor;
        final ImmutableBlockState state;
        final EightImmortalsTableBehavior behavior;
        final Map<BlockPos, BlockState> previous = new LinkedHashMap<>();
        final Map<BlockPos, EightImmortalsTableController> owned = new LinkedHashMap<>();
        BlockPos writing;
        UUID group;
        boolean committed;

        Placement(Location location, ImmutableBlockState state, EightImmortalsTableBehavior behavior) {
            world = location.getWorld();
            anchor = pos(location);
            this.state = state;
            this.behavior = behavior;
        }
    }

    public EightImmortalsTableItemBehavior(Key block) { super(block); }
    public static boolean placing() { return BUSY.get(); }

    public static boolean placing(BlockEntity entity) {
        Placement placement = ACTIVE.get();
        return placement != null && entity.world != null
                && entity.world.world().platformWorld() == placement.world && placement.previous.containsKey(entity.pos);
    }

    /** Called during controller creation, before CE can dispatch placement physics callbacks. */
    public static void created(EightImmortalsTableController controller) {
        Placement placement = ACTIVE.get();
        BlockEntity entity = controller.blockEntity();
        if (placement != null && entity.pos.equals(placement.writing)
                && controller.behavior().block().id().equals(placement.behavior.block().id())) {
            placement.owned.putIfAbsent(entity.pos, controller);
        }
    }

    @Override protected ImmutableBlockState updateBlockStateProperties(ImmutableBlockState state, Item item) {
        return state;
    }

    @Override public InteractionResult place(BlockPlaceContext context) {
        if (placing() || !validPlayer(context)) return InteractionResult.FAIL;
        BUSY.set(true);
        try { return placeChecked(context); }
        finally { BUSY.remove(); }
    }

    private InteractionResult placeChecked(BlockPlaceContext context) {
        Item held = context.getItem();
        if (held == null || held.isEmpty() || held.count() <= 0 || !context.canPlace()) return InteractionResult.FAIL;
        BlockDefinition definition = CraftEngineBlocks.byId(block());
        if (definition == null) return InteractionResult.FAIL;
        ImmutableBlockState state = getPlacementState(context, definition);
        if (state == null || !adventureAllowed(context)) return InteractionResult.FAIL;
        EightImmortalsTableBehavior behavior = state.behavior().getFirst(EightImmortalsTableBehavior.class);
        if (behavior == null || !held.id().equals(behavior.block().id())) return InteractionResult.FAIL;
        Item source = held.copyWithCount(1);
        int sourceCount = held.count();
        Location location = location((World) context.getLevel().platformWorld(), context.getClickedPos());
        Placement placement = new Placement(location, state, behavior);
        ACTIVE.set(placement);
        try {
            if (!prepare(placement, context) || !attempt(placement, context) || !stillReplaceable(placement, context)
                    || !writeCells(placement, context) || !placementEvents(placement, context) || !placeFunctions(placement, context)) {
                return InteractionResult.FAIL;
            }
            if (!validSource(context, source, sourceCount) || !complete(placement) || !permissions(placement, context)) return InteractionResult.FAIL;
            if (!complete(placement) || !validSource(context, source, sourceCount) || !validPlayer(context)) return InteractionResult.FAIL;
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
        return validPlayer(context) && current != null && !current.isEmpty() && current.count() == sourceCount && current.isSimilar(source);
    }

    private static boolean adventureAllowed(BlockPlaceContext context) {
        var player = context.getPlayer();
        if (player == null || !player.isAdventureMode()) return true;
        BlockPos against = context.getAgainstPos();
        World world = (World) context.getLevel().platformWorld();
        if (!accessible(world, against)) return false;
        Object state = BlockStateUtils.blockDataToBlockState(world.getBlockAt(against.x(), against.y(), against.z()).getBlockData());
        var custom = BlockStateUtils.getOptionalCustomBlockState(state);
        if (Config.simplifyAdventurePlaceCheck() && custom.isPresent()) state = custom.get().visualBlockState().minecraftState();
        return AdventureModeUtils.canPlace(context.getItem(), context.getLevel(), against, state);
    }

    private static boolean validPlayer(BlockPlaceContext context) {
        if (context.getPlayer() == null) return true;
        if (!(context.getPlayer().platformPlayer() instanceof org.bukkit.entity.Player actor)) return false;
        return Bukkit.isOwnedByCurrentRegion(actor) && actor.isOnline() && actor.isValid() && !actor.isDead()
                && actor.getGameMode() != org.bukkit.GameMode.SPECTATOR && actor.getWorld() == context.getLevel().platformWorld();
    }

    @Override protected boolean canPlace(BlockPlaceContext context, ImmutableBlockState state) {
        var behavior = state.behavior().getFirst(EightImmortalsTableBehavior.class);
        if (behavior == null || !validPlayer(context)) return false;
        World world = (World) context.getLevel().platformWorld();
        for (String part : EightImmortalsTableBehavior.PARTS) {
            BlockPos pos = EightImmortalsTableBehavior.cell(context.getClickedPos(), behavior.facing(state), part);
            if (!replaceable(context, pos) || !allowed(context, pos)) return false;
        }
        return super.canPlace(context, state);
    }

    private static boolean prepare(Placement placement, BlockPlaceContext context) {
        for (String part : EightImmortalsTableBehavior.PARTS) {
            BlockPos pos = EightImmortalsTableBehavior.cell(placement.anchor, placement.behavior.facing(placement.state), part);
            if (!replaceable(context, pos) || !allowed(context, pos) || !replaceable(context, pos)) return false;
            placement.previous.put(pos, placement.world.getBlockAt(pos.x(), pos.y(), pos.z()).getState());
        }
        return true;
    }

    private static boolean accessible(World world, BlockPos pos) {
        return Bukkit.isOwnedByCurrentRegion(world, pos.x() >> 4, pos.z() >> 4)
                && pos.y() >= world.getMinHeight() && pos.y() < world.getMaxHeight()
                && world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4);
    }

    private static boolean replaceable(BlockPlaceContext context, BlockPos pos) {
        return validPlayer(context) && accessible((World) context.getLevel().platformWorld(), pos)
                && context.getLevel().getBlock(pos).canBeReplaced(context);
    }

    private static boolean allowed(BlockPlaceContext context, BlockPos pos) {
        if (!validPlayer(context) || !accessible((World) context.getLevel().platformWorld(), pos)) return false;
        var player = context.getPlayer();
        if (player != null && !InteractGuard.canPlace(player, context.getLevel(), pos)) return false;
        // Protection plugins may change player/world state while answering the check.
        if (!validPlayer(context) || !accessible((World) context.getLevel().platformWorld(), pos)) return false;
        return NmsBridgeProvider.bridge().canPlaceKitchenPart(context.getLevel().minecraftWorld(),
                pos.x(), pos.y(), pos.z(), player == null ? null : player.minecraftPlayer(), context.getItem().minecraftItem());
    }

    private static boolean permissions(Placement placement, BlockPlaceContext context) {
        for (BlockPos pos : placement.previous.keySet()) if (!allowed(context, pos)) return false;
        return validPlayer(context);
    }

    private static boolean attempt(Placement placement, BlockPlaceContext context) {
        if (context.getPlayer() == null) return true;
        var player = (org.bukkit.entity.Player) context.getPlayer().platformPlayer();
        for (BlockPos pos : placement.previous.keySet()) {
            var event = new CustomBlockAttemptPlaceEvent(player, location(placement.world, pos), placement.behavior.partState(placement.state, partAt(placement, pos)),
                    DirectionUtils.toBlockFace(context.getClickedFace()), placement.world.getBlockAt(pos.x(), pos.y(), pos.z()), context.getHand());
            if (!event.callEvent() || !stillReplaceable(placement, context)) return false;
        }
        return true;
    }

    private static String partAt(Placement placement, BlockPos pos) {
        return EightImmortalsTableBehavior.PARTS.stream().filter(part ->
                EightImmortalsTableBehavior.cell(placement.anchor, placement.behavior.facing(placement.state), part).equals(pos)).findFirst().orElseThrow();
    }

    private static boolean stillReplaceable(Placement placement, BlockPlaceContext context) {
        for (var entry : placement.previous.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!replaceable(context, pos) || !allowed(context, pos) || !replaceable(context, pos)
                    || !placement.world.getBlockAt(pos.x(), pos.y(), pos.z()).getBlockData().equals(entry.getValue().getBlockData())) return false;
        }
        return true;
    }

    private static boolean writeCells(Placement placement, BlockPlaceContext context) {
        for (String part : EightImmortalsTableBehavior.PARTS) {
            BlockPos pos = EightImmortalsTableBehavior.cell(placement.anchor, placement.behavior.facing(placement.state), part);
            if (!replaceable(context, pos) || !allowed(context, pos) || !replaceable(context, pos)
                    || !placement.world.getBlockAt(pos.x(), pos.y(), pos.z()).getBlockData().equals(placement.previous.get(pos).getBlockData())) return false;
            placement.writing = pos;
            try {
                if (!CraftEngineBlocks.place(location(placement.world, pos), placement.behavior.partState(placement.state, part),
                        UpdateFlags.UPDATE_ALL, false)) return false;
                EightImmortalsTableController current = controller(placement.world, pos);
                if (current == null || current.removed() || placement.owned.get(pos) != current) return false;
            } finally { placement.writing = null; }
        }
        placement.group = UUID.randomUUID();
        for (var entry : placement.owned.entrySet()) {
            if (controller(placement.world, entry.getKey()) != entry.getValue()) return false;
            entry.getValue().assemble(placement.group);
        }
        return complete(placement);
    }

    private static boolean complete(Placement placement) {
        if (placement.group == null || placement.owned.size() != 4) return false;
        for (var entry : placement.owned.entrySet()) {
            EightImmortalsTableController current = controller(placement.world, entry.getKey());
            if (current != entry.getValue() || current.removed() || !current.group().equals(placement.group)
                    || current.behavior().facing(current.blockEntity().blockState) != placement.behavior.facing(placement.state)
                    || !current.behavior().part(current.blockEntity().blockState).equals(partAt(placement, entry.getKey()))) return false;
        }
        return true;
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
        if (!bukkit.callEvent() || !bukkit.canBuild() || !complete(placement) || !permissions(placement, context)) return false;
        var custom = new CustomBlockPlaceEvent(player, location(placement.world, placement.anchor), placement.state, anchor, context.getHand());
        return custom.callEvent() && complete(placement) && permissions(placement, context);
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
        return !cancelled.isCancelled() && complete(placement) && permissions(placement, context);
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

    private static void rollback(Placement placement) {
        IllegalStateException failure = null;
        for (var entry : placement.owned.entrySet()) {
            BlockPos pos = entry.getKey();
            try {
                if (!accessible(placement.world, pos)) throw new IllegalStateException("Table rollback lost region/chunk at " + pos);
                if (controller(placement.world, pos) != entry.getValue()) continue;
                if (!placement.previous.get(pos).update(true, false)) throw new IllegalStateException("Could not roll back table at " + pos);
            } catch (RuntimeException exception) {
                if (failure == null) failure = new IllegalStateException("Table rollback failed", exception);
                else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    private static EightImmortalsTableController controller(World world, BlockPos pos) {
        if (!accessible(world, pos)) return null;
        var entity = BukkitAdaptor.adapt(world).storageWorld().getBlockEntityAtIfLoaded(pos);
        return entity == null ? null : entity.controller.get(EightImmortalsTableController.class, 0);
    }

    private static Location location(World world, BlockPos pos) { return new Location(world, pos.x(), pos.y(), pos.z()); }
    private static BlockPos pos(Location location) { return new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ()); }

    private static final class Factory implements ItemBehaviorFactory<EightImmortalsTableItemBehavior> {
        @Override public EightImmortalsTableItemBehavior create(Pack pack, Path path, Key id, ConfigSection section) {
            return new EightImmortalsTableItemBehavior(section.getNonNullValue("block",
                    net.momirealms.craftengine.core.plugin.config.ConfigConstants.ARGUMENT_SECTION).getAsIdentifier());
        }
    }
}
