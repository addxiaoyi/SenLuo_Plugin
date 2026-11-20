package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.TableBehavior;
import net.kaleidoscope.cookery.block.entity.render.TrackedPlayers;
import net.kaleidoscope.cookery.item.CarpetColors;
import net.kaleidoscope.cookery.util.*;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.render.element.BlockEntityElement;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.libraries.nbt.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Four individual tabletop items, plus a separate carpet, matching the pinned source. */
public final class TableController extends BlockEntityController {
    private static final String DATA_KEY = "kaleidoscopecookery:table";
    private final TableBehavior behavior;
    private final TableElement element = new TableElement(this);
    private volatile Key carpet;
    private volatile List<Item> contents = List.of();
    private boolean interacting, removing, removed, creativeBreak;
    private CompoundTag rejectedState;

    public TableController(BlockEntity blockEntity, TableBehavior behavior) {
        super(blockEntity);
        this.behavior = behavior;
    }

    public int line(ImmutableBlockState state) { return behavior.line(state); }
    public int position(ImmutableBlockState state) { return behavior.position(state); }
    @Nullable public Key carpet() { return carpet; }
    public List<Item> displayedItems() { return contents.stream().map(Item::copy).toList(); }
    public void markCreativeBreak() { creativeBreak = true; }

    public InteractionResult interact(Player player) {
        if (interacting || removing || removed || rejectedState != null || !owned(player)) return InteractionResult.FAIL;
        interacting = true;
        try {
            if (!InteractGuard.canInteract(player, blockEntity.world.world(), blockEntity.pos) || !owned(player))
                return InteractionResult.FAIL;
            Item held = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!ItemUtils.isEmpty(held) && CarpetColors.isCarpet(held.id())) return replaceCarpet(player, held);
            if (ItemUtils.isEmpty(held)) {
                if (contents.isEmpty()) return InteractionResult.PASS;
                Item taken = contents.getLast();
                contents = List.copyOf(contents.subList(0, contents.size() - 1));
                markChanged();
                try { DropUtils.dropAtHeight(blockEntity, taken, .75); }
                finally { refresh(); }
                return success(player, "entity.item_frame.remove_item");
            }
            if (contents.size() == 4) return InteractionResult.PASS;
            Item stored = held.copyWithCount(1);
            // Unlike InventoryUtils.shrinkHeld, source split(1) also consumes creative items.
            player.setItemInHand(InteractionHand.MAIN_HAND, held.copyWithCount(held.count() - 1));
            List<Item> updated = new ArrayList<>(contents);
            updated.add(stored);
            contents = List.copyOf(updated);
            markChanged();
            refresh();
            return success(player, "entity.item_frame.add_item");
        } finally { interacting = false; }
    }

    private InteractionResult replaceCarpet(Player player, Item held) {
        Key next = held.id();
        if (next.equals(carpet)) return InteractionResult.PASS;
        Item expected = held.copy();
        Item refund = carpet == null ? null : InventoryUtils.createOrEmpty(carpet);
        if (carpet != null && ItemUtils.isEmpty(refund)) return InteractionResult.FAIL;
        Item current = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!owned(player) || ItemUtils.isEmpty(current) || current.count() != expected.count()
                || !current.isSimilar(expected)) return InteractionResult.FAIL;
        player.setItemInHand(InteractionHand.MAIN_HAND, current.copyWithCount(current.count() - 1));
        carpet = next;
        markChanged();
        try { if (refund != null) DropUtils.dropAtHeight(blockEntity, refund, .75); }
        finally { refresh(); }
        return success(player, "block.wool.place");
    }

    private boolean owned(Player player) {
        if (player == null || removed || removing || rejectedState != null
                || blockEntity.world == null || !blockEntity.isValid()) return false;
        var bukkit = (org.bukkit.entity.Player) player.platformPlayer();
        var world = (org.bukkit.World) blockEntity.world.world().platformWorld();
        return bukkit != null && bukkit.getGameMode() != GameMode.SPECTATOR
                && Bukkit.isOwnedByCurrentRegion(bukkit)
                && Bukkit.isOwnedByCurrentRegion(world, blockEntity.pos.x() >> 4, blockEntity.pos.z() >> 4)
                && blockEntity.world.getBlockEntityAtIfLoaded(blockEntity.pos) == blockEntity;
    }

    private InteractionResult success(Player player, String sound) {
        if (!removed) blockEntity.world.world().playBlockSound(Vec3d.atCenterOf(blockEntity.pos),
                Key.of("minecraft:" + sound), 1f, 1f);
        player.swingHand(InteractionHand.MAIN_HAND);
        return InteractionResult.SUCCESS_AND_CANCEL;
    }
    private void markChanged() {
        if (blockEntity.world != null) blockEntity.world.blockEntityChanged(blockEntity.pos);
    }
    private void refresh() {
        if (removed || blockEntity.world == null) return;
        redraw(blockEntity.blockState());
    }
    private void redraw(ImmutableBlockState state) {
        element.rebuild(state);
        TrackedPlayers.forEach(blockEntity, element::update);
    }
    @Override public void preBlockStateChange(ImmutableBlockState newState) {
        if (blockEntity.world == null || removed) return;
        ImmutableBlockState current = blockEntity.blockState();
        if (line(current) != line(newState) || position(current) != position(newState)) redraw(newState);
    }
    @Override public boolean hasElement() { return true; }
    @Override public void gatherElements(Consumer<BlockEntityElement> consumer) { consumer.accept(element); }

    @Override public void onRemove() {
        if (removed || removing) return;
        if (rejectedState != null) throw new IllegalStateException("Cannot remove table with unreadable contents");
        removing = true;
        try {
            if (PlacementGuard.isPlacing(blockEntity)) {
                contents = List.of(); carpet = null; removed = true;
                super.onRemove();
                return;
            }
            List<Item> drops = new ArrayList<>(displayedItems());
            if (carpet != null && !creativeBreak) {
                Item refund = InventoryUtils.createOrEmpty(carpet);
                if (ItemUtils.isEmpty(refund)) throw new IllegalStateException("Missing table carpet " + carpet);
                drops.add(refund);
            }
            // Creative destruction returns tabletop contents, but not the carpet.
            removed = true; contents = List.of(); carpet = null;
            markChanged();
            try { for (Item drop : drops) DropUtils.dropOnRemove(blockEntity, drop); }
            finally { super.onRemove(); }
        } finally { removing = false; }
    }

    @Override public void saveCustomData(CompoundTag tag) {
        if (rejectedState != null) {
            for (String key : new String[]{DATA_KEY, CookeryBlockStateEnvelope.sidecar(DATA_KEY)}) {
                Tag raw = rejectedState.get(key);
                if (raw != null) tag.put(key, raw.deepClone());
                else tag.remove(key);
            }
            return;
        }
        CompoundTag data = BlockEntityNbt.newData();
        data.putInt("schema", 1);
        if (carpet != null) data.putString("carpet", carpet.asString());
        ListTag stored = new ListTag();
        for (Item item : contents) {
            Tag encoded = BlockEntityNbt.itemTag(item);
            if (encoded == null) throw new IllegalStateException("Cannot encode tabletop item " + item.id());
            CompoundTag entry = new CompoundTag();
            entry.put("item", encoded); stored.add(entry);
        }
        data.put("items", stored);
        CookeryBlockStateEnvelope.put(tag, DATA_KEY, data);
    }

    @Override public void loadCustomData(CompoundTag tag) {
        try {
            if (!tag.containsKey(CookeryBlockStateEnvelope.sidecar(DATA_KEY))
                    && tag.containsKey(DATA_KEY) && !(tag.get(DATA_KEY) instanceof CompoundTag))
                throw new IllegalArgumentException("Invalid table payload");
            CompoundTag data = CookeryBlockStateEnvelope.payload(tag, DATA_KEY);
            Key restoredCarpet = null;
            List<Item> restored = new ArrayList<>();
            if (data != null) {
                if (data.containsKey("schema") && (!(data.get("schema") instanceof IntTag) || data.getInt("schema") != 1))
                    throw new IllegalArgumentException("Unsupported table schema");
                if (data.containsKey("data_version") && !(data.get("data_version") instanceof IntTag))
                    throw new IllegalArgumentException("Invalid table data version");
                if (data.containsKey("carpet")) {
                    restoredCarpet = CarpetColors.parse(data.getString("carpet", null));
                    if (restoredCarpet == null) throw new IllegalArgumentException("Invalid table carpet");
                }
                if (data.containsKey("schema") && !data.containsKey("items"))
                    throw new IllegalArgumentException("Missing table items");
                if (data.containsKey("items")) {
                    if (!(data.get("items") instanceof ListTag list) || list.size() > 4)
                        throw new IllegalArgumentException("Invalid tabletop items");
                    int version = BlockEntityNbt.dataVersion(data);
                    for (Tag raw : list) {
                        if (!(raw instanceof CompoundTag entry) || !entry.containsKey("item"))
                            throw new IllegalArgumentException("Invalid tabletop entry");
                        Item item = BlockEntityNbt.getItem(entry, "item", version);
                        if (ItemUtils.isEmpty(item) || item.count() != 1)
                            throw new IllegalArgumentException("Unreadable tabletop item");
                        restored.add(item.copy());
                    }
                }
            }
            contents = List.copyOf(restored); carpet = restoredCarpet;
            rejectedState = null;
        } catch (RuntimeException failure) {
            rejectedState = tag.deepClone();
            throw failure;
        }
        element.rebuild(blockEntity.blockState());
    }
}
