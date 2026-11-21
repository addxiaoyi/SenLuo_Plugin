package net.kaleidoscope.cookery.item.behavior;

import net.kaleidoscope.cookery.block.behavior.SteamerBehavior;
import net.kaleidoscope.cookery.block.entity.SteamerController;
import net.kaleidoscope.cookery.block.entity.SteamerInventory;
import net.kaleidoscope.cookery.item.SteamerItemContents;
import net.kaleidoscope.cookery.nms.NmsBridgeProvider;
import net.kaleidoscope.cookery.util.*;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.api.*;
import net.momirealms.craftengine.bukkit.api.event.*;
import net.momirealms.craftengine.bukkit.item.behavior.BlockItemBehavior;
import net.momirealms.craftengine.bukkit.util.*;
import net.momirealms.craftengine.bukkit.world.BukkitExistingBlock;
import net.momirealms.craftengine.core.block.*;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.property.type.SlabType;
import net.momirealms.craftengine.core.entity.player.*;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.plugin.context.*;
import net.momirealms.craftengine.core.plugin.context.function.Function;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.util.*;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import java.util.*;

/** Contents enter the world only after placement callbacks accept the new layer. */
public class SteamerItemBehavior extends BlockItemBehavior {
    public static final ItemBehaviorFactory<SteamerItemBehavior> FACTORY = (pack, path, id, config) ->
            new SteamerItemBehavior(config.getNonNullValue("block",
                    net.momirealms.craftengine.core.plugin.config.ConfigConstants.ARGUMENT_SECTION).getAsIdentifier());
    private static final ThreadLocal<Placement> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> false);
    private static final class Placement {
        final BlockPlaceContext context;
        final World world;
        final BlockPos pos;
        final ImmutableBlockState state;
        final BlockState previous;
        final SteamerController original;
        final CompoundTag saved;
        final CompoundTag inventory;
        SteamerController owned;
        CompoundTag expected;
        boolean writing, written, committed, originalRemoved;
        Placement(BlockPlaceContext context, ImmutableBlockState state, SteamerController original) {
            this.context = context; world = (World) context.getLevel().platformWorld(); pos = context.getClickedPos(); this.state = state;
            previous = world.getBlockAt(pos.x(), pos.y(), pos.z()).getState(); this.original = original;
            saved = new CompoundTag(); if (original != null) original.saveCustomData(saved);
            inventory = SteamerItemContents.payload(original == null ? Collections.nCopies(8, null) : original.snapshotSlots());
        }
    }
    public SteamerItemBehavior(Key block) { super(block); }
    public static boolean placing(BlockEntity entity) {
        Placement p = ACTIVE.get();
        return p != null && p.pos.equals(entity.pos) && entity.world != null && entity.world.world().platformWorld() == p.world;
    }
    public static boolean writing(BlockEntity entity) { Placement p = ACTIVE.get(); return p != null && p.writing && placing(entity); }
    public static boolean suppressRemoval(SteamerController controller) {
        Placement p = ACTIVE.get();
        if (p == null || p.committed || !placing(controller.blockEntity())) return false;
        if (controller == p.original) p.originalRemoved = true;
        return true;
    }
    public static void created(SteamerController controller) {
        Placement p = ACTIVE.get();
        if (p != null && p.writing && p.pos.equals(controller.blockEntity().pos)) p.owned = controller;
    }
    @Override protected ImmutableBlockState updateBlockStateProperties(ImmutableBlockState state, Item item) { return state; }
    @Override public InteractionResult place(BlockPlaceContext context) {
        if (BUSY.get()) return InteractionResult.FAIL;
        BUSY.set(true);
        try { return placeChecked(context); }
        finally { BUSY.remove(); }
    }
    private InteractionResult placeChecked(BlockPlaceContext context) {
        if (context.getClickedFace() != Direction.UP || !validPlayer(context) || !accessible(context, context.getClickedPos())) return InteractionResult.FAIL;
        Item held = context.getItem();
        List<SteamerInventory.Slot> incoming;
        try { incoming = SteamerItemContents.read(held); }
        catch (IllegalArgumentException invalid) { return InteractionResult.FAIL; }
        if (!context.canPlace()) return InteractionResult.FAIL;
        BlockDefinition definition = CraftEngineBlocks.byId(block());
        if (definition == null) return InteractionResult.FAIL;
        ImmutableBlockState state = getPlacementState(context, definition);
        if (state == null || !adventureAllowed(context)) return InteractionResult.FAIL;
        var behavior = state.behavior().getFirst(SteamerBehavior.class);
        if (behavior == null || !block().equals(held.id())) return InteractionResult.FAIL;
        SteamerController old = controller(context);
        List<SteamerInventory.Slot> merged;
        if (old != null) {
            if (!old.hasValidData() || old.behavior().block() != definition || old.removed()
                    || old.blockEntity().blockState.get(behavior.getTypeProperty()) != SlabType.BOTTOM) return InteractionResult.FAIL;
            merged = SteamerItemContents.merge(old.snapshotSlots(), incoming);
        } else {
            merged = new ArrayList<>(incoming); merged.addAll(Collections.nCopies(4, null));
        }
        // Serialize/preflight before any event or world write.
        CompoundTag next = new CompoundTag();
        CookeryBlockStateEnvelope.put(next, SteamerController.DATA_KEY, SteamerItemContents.payload(merged));
        Item source = held.copyWithCount(1); int count = held.count();
        Placement p = new Placement(context, state, old); ACTIVE.set(p);
        try {
            if (!unchanged(p) || !allowed(context) || !unchanged(p) || !attempt(p) || !unchanged(p)
                    || !validSource(context, source, count)) return InteractionResult.FAIL;
            p.writing = true;
            p.written = true;
            // Native placement can mutate the existing controller and then throw.
            // Retain ownership before entering native code so rollback still restores it.
            p.owned = old;
            boolean wrote;
            try { wrote = CraftEngineBlocks.place(location(p), state, UpdateFlags.UPDATE_ALL, false); }
            finally { p.writing = false; }
            SteamerController current = controller(context);
            if (p.owned == null && current == old) p.owned = old;
            if (current == null || current != p.owned || !current.hasValidData() || current.removed()) return InteractionResult.FAIL;
            CompoundTag afterWrite = SteamerItemContents.payload(current.snapshotSlots());
            // Native callbacks must not inject contents which the pending merge would overwrite.
            if (!p.inventory.equals(afterWrite) && (current == old || current.getItemCount() != 0)) return InteractionResult.FAIL;
            if (current != null && current == p.owned) p.expected = snapshot(current);
            if (!wrote || !complete(p) || !events(p) || !complete(p) || !functions(p) || !complete(p)
                    || !allowed(context) || !complete(p) || !validSource(context, source, count)) return InteractionResult.FAIL;
            p.owned.loadCustomData(next);
            p.expected = snapshot(p.owned);
            if (!complete(p) || !validSource(context, source, count)) return InteractionResult.FAIL;
            if (context.getPlayer() != null && !context.getPlayer().isCreativeMode())
                context.getPlayer().getItemInHand(context.getHand()).count(count - 1);
            p.committed = true;
            context.getLevel().storageWorld().blockEntityChanged(p.pos);
            p.owned.refreshElementState();
            if (context.getPlayer() != null) context.getPlayer().swingHand(context.getHand());
            context.getLevel().playBlockSound(position(p), state.settings().sounds().placeSound());
            feedback(context);
            return InteractionResult.SUCCESS;
        } finally {
            try { if (!p.committed && p.written) rollback(p); }
            finally { ACTIVE.remove(); }
        }
    }
    private static boolean unchanged(Placement p) {
        return accessible(p.context, p.pos) && validPlayer(p.context) && controller(p.context) == p.original
                && p.world.getBlockAt(p.pos.x(), p.pos.y(), p.pos.z()).getBlockData().equals(p.previous.getBlockData())
                && p.context.getLevel().getBlock(p.pos).canBeReplaced(p.context)
                && (p.original == null || p.original.hasValidData() && !p.original.removed() && p.saved.equals(snapshot(p.original)));
    }
    private static CompoundTag snapshot(SteamerController controller) { CompoundTag tag = new CompoundTag(); controller.saveCustomData(tag); return tag; }
    private static boolean complete(Placement p) {
        var current = controller(p.context);
        return validPlayer(p.context) && current != null && current == p.owned && !current.removed()
                && current.hasValidData() && current.blockEntity().isValid() && current.blockEntity().blockState == p.state
                && (p.expected == null || p.expected.equals(snapshot(current)));
    }
    private static boolean validSource(BlockPlaceContext context, Item source, int count) {
        Item item = context.getPlayer() == null ? context.getItem() : context.getPlayer().getItemInHand(context.getHand());
        return validPlayer(context) && item != null && !item.isEmpty() && item.count() == count && item.isSimilar(source);
    }
    private static boolean validPlayer(BlockPlaceContext context) {
        if (context.getPlayer() == null) return true;
        if (!(context.getPlayer().platformPlayer() instanceof org.bukkit.entity.Player actor)) return false;
        return Bukkit.isOwnedByCurrentRegion(actor) && actor.isOnline() && actor.isValid() && !actor.isDead()
                && actor.getGameMode() != org.bukkit.GameMode.SPECTATOR && actor.getWorld() == context.getLevel().platformWorld();
    }
    private static boolean accessible(BlockPlaceContext context, BlockPos pos) {
        World world = (World) context.getLevel().platformWorld();
        return Bukkit.isOwnedByCurrentRegion(world, pos.x() >> 4, pos.z() >> 4) && world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)
                && pos.y() >= world.getMinHeight() && pos.y() < world.getMaxHeight();
    }
    private static boolean allowed(BlockPlaceContext context) {
        if (!validPlayer(context) || !accessible(context, context.getClickedPos())) return false;
        if (context.getPlayer() != null && !InteractGuard.canPlace(context.getPlayer(), context.getLevel(), context.getClickedPos())) return false;
        if (!validPlayer(context) || !accessible(context, context.getClickedPos())) return false;
        BlockPos pos = context.getClickedPos();
        return NmsBridgeProvider.bridge().canPlaceKitchenPart(context.getLevel().minecraftWorld(), pos.x(), pos.y(), pos.z(),
                context.getPlayer() == null ? null : context.getPlayer().minecraftPlayer(), context.getItem().minecraftItem());
    }
    private static boolean adventureAllowed(BlockPlaceContext context) {
        if (context.getPlayer() == null || !context.getPlayer().isAdventureMode()) return true;
        BlockPos against = context.getAgainstPos(); if (!accessible(context, against)) return false;
        World world = (World) context.getLevel().platformWorld();
        Object state = BlockStateUtils.blockDataToBlockState(world.getBlockAt(against.x(), against.y(), against.z()).getBlockData());
        var custom = BlockStateUtils.getOptionalCustomBlockState(state);
        if (Config.simplifyAdventurePlaceCheck() && custom.isPresent()) state = custom.get().visualBlockState().minecraftState();
        return AdventureModeUtils.canPlace(context.getItem(), context.getLevel(), against, state);
    }
    private static boolean attempt(Placement p) {
        if (p.context.getPlayer() == null) return true;
        return new CustomBlockAttemptPlaceEvent((org.bukkit.entity.Player) p.context.getPlayer().platformPlayer(), location(p), p.state,
                DirectionUtils.toBlockFace(p.context.getClickedFace()), p.world.getBlockAt(p.pos.x(), p.pos.y(), p.pos.z()), p.context.getHand()).callEvent();
    }
    private static boolean events(Placement p) {
        if (p.context.getPlayer() == null) return true;
        BlockPos against = p.context.getAgainstPos(); if (!accessible(p.context, against)) return false;
        var actor = (org.bukkit.entity.Player) p.context.getPlayer().platformPlayer();
        var block = p.world.getBlockAt(p.pos.x(), p.pos.y(), p.pos.z());
        var event = new BlockPlaceEvent(block, p.previous, p.world.getBlockAt(against.x(), against.y(), against.z()),
                InventoryUtils.bukkitCopy(p.context.getItem()), actor, true,
                p.context.getHand() == InteractionHand.MAIN_HAND ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND);
        if (!event.callEvent() || !event.canBuild() || !complete(p)) return false;
        return new CustomBlockPlaceEvent(actor, location(p), p.state, block, p.context.getHand()).callEvent();
    }
    private static boolean functions(Placement p) {
        var functions = p.state.owner().value().eventFunctions(EventTrigger.PLACE); if (functions.isEmpty()) return true;
        Cancellable cancelled = Cancellable.dummy();
        var holder = ContextHolder.builder().withOptionalParameter(DirectContextParameters.PLAYER, p.context.getPlayer())
                .withParameter(DirectContextParameters.BLOCK, new BukkitExistingBlock(p.world.getBlockAt(p.pos.x(), p.pos.y(), p.pos.z())))
                .withParameter(DirectContextParameters.POSITION, position(p)).withParameter(DirectContextParameters.EVENT, cancelled)
                .withParameter(DirectContextParameters.HAND, p.context.getHand()).withOptionalParameter(DirectContextParameters.ITEM_IN_HAND, p.context.getItem());
        Function.execute(PlayerOptionalContext.of(p.context.getPlayer(), holder), functions);
        return !cancelled.isCancelled();
    }
    private static void rollback(Placement p) {
        if (!accessible(p.context, p.pos)) throw new IllegalStateException("Steamer rollback lost region/chunk");
        var current = controller(p.context);
        if (current != p.owned || current == null) {
            // A callback owns the replacement. Return only the original lower steamer, never the unspent new item.
            if (p.originalRemoved) {
                var slots = SteamerInventory.read(CookeryBlockStateEnvelope.payload(p.saved, SteamerController.DATA_KEY), 1200);
                p.context.getLevel().dropItemNaturally(position(p), SteamerItemContents.build(slots.subList(0, 4)));
            }
            return;
        }
        p.writing = true;
        try {
            if (!p.previous.update(true, false)) throw new IllegalStateException("Steamer block rollback failed");
            if (p.original != null) {
                var restored = controller(p.context);
                if (restored == null) throw new IllegalStateException("Steamer inventory rollback missing controller");
                restored.loadCustomData(p.saved); p.context.getLevel().storageWorld().blockEntityChanged(p.pos); restored.refreshElementState();
            }
        } finally { p.writing = false; }
    }
    private static SteamerController controller(BlockPlaceContext context) {
        if (!accessible(context, context.getClickedPos())) return null;
        var entity = context.getLevel().storageWorld().getBlockEntityAtIfLoaded(context.getClickedPos());
        return entity == null ? null : entity.controller.get(SteamerController.class, 0);
    }
    private static Location location(Placement p) { return new Location(p.world, p.pos.x(), p.pos.y(), p.pos.z()); }
    private static WorldPosition position(Placement p) { return new WorldPosition(p.context.getLevel(), p.pos.x() + .5, p.pos.y() + .5, p.pos.z() + .5); }
    protected void feedback(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        LevelUtils.sendGameEvent((World) context.getLevel().platformWorld(), context.getPlayer() == null ? null :
                (org.bukkit.entity.Player) context.getPlayer().platformPlayer(), org.bukkit.GameEvent.BLOCK_PLACE,
                new org.bukkit.util.Vector(pos.x(), pos.y(), pos.z()));
    }
}
