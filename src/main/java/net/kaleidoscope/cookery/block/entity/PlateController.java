package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.PlateBehavior;
import net.kaleidoscope.cookery.catalog.PlateServing;
import net.kaleidoscope.cookery.item.CookeryFoodItems;
import net.kaleidoscope.cookery.util.BlockEntityNbt;
import net.kaleidoscope.cookery.util.BlockStates;
import net.kaleidoscope.cookery.util.CookeryBlockStateEnvelope;
import net.kaleidoscope.cookery.util.DropUtils;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.kaleidoscope.cookery.util.PlacementGuard;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.IntTag;
import net.momirealms.craftengine.libraries.nbt.ListTag;
import net.momirealms.craftengine.libraries.nbt.StringTag;
import net.momirealms.craftengine.libraries.nbt.Tag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Serving plates contain whole items; directly edible dishes use a separate bite lifecycle. */
public final class PlateController extends BlockEntityController {
    private static final String DATA_KEY = "kaleidoscopecookery:plate";
    private static final int SCHEMA_VERSION = 2;
    private static final int MAX_ITEMS = 64;
    private final PlateBehavior behavior;
    private int remaining;
    private List<Item> portion;
    private List<Item> containers;
    private boolean transferring;
    private boolean removed;

    public PlateController(BlockEntity blockEntity, PlateBehavior behavior) {
        super(blockEntity);
        this.behavior = Objects.requireNonNull(behavior);
        remaining = BlockStates.value(blockEntity.blockState, behavior.servingsProperty(), behavior.maxServings());
        requireCount(remaining, behavior.maxServings());
        // CE clears its item registry before saving worlds during shutdown.
        ensureContents();
    }

    public BlockPos getPos() { return blockEntity.pos; }
    public CEWorld getWorld() { return blockEntity.world; }
    public int servings() { return remaining; }

    public boolean place(Player player, InteractionHand hand, Item held) {
        return refill(held, !player.canInstabuild());
    }

    public boolean refill(Item held) {
        return refill(held, true);
    }

    private boolean refill(Item held, boolean consume) {
        if (removed || transferring || remaining >= behavior.maxServings() || held == null
                || held.isEmpty() || held.count() <= 0 || !behavior.accepts(held.id())) return false;
        transferring = true;
        try {
            ensureContents();
            if (portion.size() != 1 || !portion.getFirst().id().equals(held.id())) return false;
            if (consume) held.shrink(1);
            remaining++;
            syncServingState();
            return true;
        } finally {
            transferring = false;
        }
    }

    public boolean take(Player player, InteractionHand hand) {
        boolean drop = !player.getItemInHand(hand).isEmpty();
        return takeServing(items -> {
            for (Item item : items) {
                if (drop) DropUtils.dropAtHeight(blockEntity, item, 1);
                else InventoryUtils.giveOrHold(player, hand, item);
            }
        });
    }

    /** The receiver owns these copies; failure cannot safely undo a possibly partial transfer. */
    public boolean takeServing(Consumer<List<Item>> receiver) {
        Objects.requireNonNull(receiver, "receiver");
        if (removed || transferring || remaining == 0) return false;
        transferring = true;
        try {
            ensureContents();
            List<Item> output = copies(portion);
            remaining--;
            syncServingState();
            receiver.accept(output);
            return true;
        } finally {
            transferring = false;
        }
    }

    public boolean removeIfEmpty() {
        if (removed || transferring || remaining != 0) return false;
        transferring = true;
        try {
            ensureContents();
            return behavior.removeAfterConsumed(blockEntity.world, blockEntity.pos);
        } finally {
            transferring = false;
        }
    }

    private void ensureContents() {
        if (portion != null) return;
        List<Item> food = build(behavior.servingItems());
        List<Item> drops = build(behavior.drops().stream().map(Key::of).toList());
        if (food.isEmpty()) throw invalid("empty serving definition");
        portion = food;
        containers = drops;
    }

    private List<Item> build(List<Key> ids) {
        List<Item> built = new ArrayList<>(ids.size());
        for (Key id : ids) {
            Item item = InventoryUtils.createOrEmpty(id);
            if (item == null || item.isEmpty()) throw invalid("cannot construct " + id);
            built.add(item.copyWithCount(1));
        }
        return List.copyOf(built);
    }

    private static List<Item> copies(List<Item> items) {
        return items.stream().map(Item::copy).toList();
    }

    private void syncServingState() {
        markChanged();
        BlockStates.sync(blockEntity, BlockStates.with(blockEntity.blockState, behavior.servingsProperty(), remaining));
    }

    private void markChanged() {
        if (blockEntity.world != null) blockEntity.world.blockEntityChanged(blockEntity.pos);
    }

    @Override
    public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(
            CEWorld world, ImmutableBlockState state) {
        return null;
    }

    @Override
    public void onRemove() {
        if (removed) return;
        if (PlacementGuard.isPlacing(blockEntity)) {
            removed = true;
            remaining = 0;
            super.onRemove();
            return;
        }
        ensureContents();
        List<Item> drops = new ArrayList<>();
        for (Item food : portion) {
            int count = remaining;
            while (count > 0) {
                int amount = Math.min(count, Math.max(1, food.maxStackSize()));
                drops.add(food.copyWithCount(amount));
                count -= amount;
            }
        }
        drops.addAll(copies(containers));
        // Spawn listeners may reenter: detach the contents before delivering any item.
        removed = true;
        remaining = 0;
        markChanged();
        try {
            for (Item drop : drops) DropUtils.dropOnRemove(blockEntity, drop);
        } finally {
            super.onRemove();
        }
    }

    @Override
    public void saveCustomData(CompoundTag tag) {
        ensureContents();
        CompoundTag saved = BlockEntityNbt.newData();
        saved.putInt("schema", SCHEMA_VERSION);
        saved.putInt("max_servings", behavior.maxServings());
        saved.putInt("remaining", remaining);
        saved.put("portion", encodeItems(portion));
        saved.put("containers", encodeItems(containers));
        CookeryBlockStateEnvelope.put(tag, DATA_KEY, saved);
    }

    private ListTag encodeItems(List<Item> items) {
        ListTag saved = new ListTag();
        for (Item item : items) {
            Tag encoded = BlockEntityNbt.itemTag(item);
            if (encoded == null) throw invalid("cannot encode " + item.id());
            CompoundTag entry = new CompoundTag();
            entry.put("item", encoded);
            saved.add(entry);
        }
        return saved;
    }

    @Override
    public void loadCustomData(CompoundTag tag) {
        if (!tag.containsKey(CookeryBlockStateEnvelope.sidecar(DATA_KEY))) requireType(tag, DATA_KEY, CompoundTag.class, false);
        CompoundTag saved = CookeryBlockStateEnvelope.payload(tag, DATA_KEY);
        if (saved == null) return;
        requireType(saved, "schema", IntTag.class, false);
        requireType(saved, "data_version", IntTag.class, false);
        Snapshot loaded = switch (saved.getInt("schema", 1)) {
            case 1 -> readLegacy(saved);
            case SCHEMA_VERSION -> readCurrent(saved);
            default -> throw invalid("unsupported schema " + saved.getInt("schema"));
        };
        // Validate and copy every item before replacing live inventory or counters.
        List<Item> food = copies(loaded.portion());
        List<Item> drops = copies(loaded.containers());
        remaining = loaded.remaining();
        portion = food;
        containers = drops;
    }

    private Snapshot readCurrent(CompoundTag saved) {
        requireType(saved, "max_servings", IntTag.class, true);
        requireType(saved, "remaining", IntTag.class, true);
        int count = saved.getInt("remaining");
        requireCount(count, saved.getInt("max_servings"));
        int version = BlockEntityNbt.dataVersion(saved);
        List<Item> food = decodeItems(saved, "portion", version);
        List<Item> drops = decodeItems(saved, "containers", version);
        if (food.isEmpty()) throw invalid("missing serving items");
        HashSet<Key> ids = new HashSet<>();
        for (Item item : food) {
            if (!behavior.servingItems().contains(item.id()) || !ids.add(item.id())) throw invalid("unexpected serving " + item.id());
        }
        return new Snapshot(count, food, drops);
    }

    private Snapshot readLegacy(CompoundTag saved) {
        requireType(saved, "serving", StringTag.class, true);
        PlateServing old;
        try {
            old = PlateServing.decode(saved.getString("serving"));
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Invalid legacy plate serving at " + blockEntity.pos, malformed);
        }
        requireCount(old.bites().remaining(), old.bites().maxBites());
        Key id = Key.of(old.bites().itemId());
        if (!behavior.servingItems().contains(id)) throw invalid("unexpected legacy serving " + id);
        Item source = saved.containsKey("source_item")
                ? BlockEntityNbt.getItem(saved, "source_item", BlockEntityNbt.dataVersion(saved))
                : InventoryUtils.createOrEmpty(id);
        if (source == null || source.isEmpty() || source.count() != 1 || !source.id().equals(id)) {
            throw invalid("invalid legacy source item");
        }
        // Retain existing portions, original components, quality and saved containers on upgrade.
        Item migrated = CookeryFoodItems.bakeBite(source.copy(), old.bites().quality());
        if (migrated == null || migrated.isEmpty()) throw invalid("cannot restore legacy serving");
        return new Snapshot(old.bites().remaining(), List.of(migrated.copyWithCount(1)),
                build(old.drops().stream().map(Key::of).toList()));
    }

    private List<Item> decodeItems(CompoundTag saved, String key, int version) {
        requireType(saved, key, ListTag.class, true);
        ListTag list = saved.getList(key);
        if (list.size() > MAX_ITEMS) throw invalid("too many " + key);
        List<Item> items = new ArrayList<>(list.size());
        for (Tag element : list) {
            if (!(element instanceof CompoundTag entry) || !entry.containsKey("item")) throw invalid("invalid " + key + " entry");
            Item item = BlockEntityNbt.getItem(entry, "item", version);
            if (item == null || item.isEmpty() || item.count() != 1) throw invalid("invalid " + key + " item");
            items.add(item);
        }
        return List.copyOf(items);
    }

    private void requireType(CompoundTag tag, String key, Class<? extends Tag> type, boolean required) {
        Tag value = tag.get(key);
        if (value == null ? required : !type.isInstance(value)) throw invalid("invalid " + key + " type");
    }

    private void requireCount(int count, int maximum) {
        if (maximum < 1 || maximum > 64 || maximum != behavior.maxServings() || count < 0 || count > maximum) {
            throw invalid("invalid portion count " + count + "/" + maximum);
        }
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Plate " + message + " at " + blockEntity.pos);
    }

    private record Snapshot(int remaining, List<Item> portion, List<Item> containers) {}
}
