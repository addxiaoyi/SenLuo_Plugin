package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.util.BlockEntityNbt;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.libraries.nbt.*;
import java.util.Arrays;

/** Three source slots: stacked insertion, oldest eviction only when nothing fits. */
final class TrashCanInventory {
    static final int SLOTS = 3;
    record Insert(Item[] slots, int consumed) {}
    private TrashCanInventory() {}

    static Item[] empty() {
        Item[] result = new Item[SLOTS]; Arrays.fill(result, Item.empty()); return result;
    }

    static Item[] copy(Item[] slots) {
        if (slots.length != SLOTS) throw new IllegalArgumentException("Trash can requires three slots");
        return Arrays.stream(slots).map(Item::copy).toArray(Item[]::new);
    }

    static Insert insert(Item[] slots, Item held) {
        requireItem(held);
        Item[] next = copy(slots);
        int remaining = held.count();
        for (int i = 0; i < SLOTS && remaining > 0; i++) {
            if (!next[i].isEmpty() && next[i].isSimilar(held)) {
                int move = Math.min(remaining, Math.max(0, Math.min(64, next[i].maxStackSize()) - next[i].count()));
                next[i] = next[i].copyWithCount(next[i].count() + move);
                remaining -= move;
            }
        }
        for (int i = 0; i < SLOTS && remaining > 0; i++) {
            if (next[i].isEmpty()) {
                int move = Math.min(remaining, Math.min(64, held.maxStackSize()));
                next[i] = held.copyWithCount(move); remaining -= move;
            }
        }
        if (remaining == held.count()) {
            next[0] = next[1]; next[1] = next[2]; next[2] = held.copy(); remaining = 0;
        }
        return new Insert(next, held.count() - remaining);
    }

    /** Null means no filter match. The source discards the ENTIRE matching entity,
     * even when only part fits, the first matching slot is full, or metadata differs. */
    static Item[] filter(Item[] slots, Item incoming) {
        requireItem(incoming);
        for (int i = 0; i < SLOTS; i++) {
            if (slots[i].isEmpty() || !slots[i].id().equals(incoming.id())) continue;
            Item[] next = copy(slots);
            if (slots[i].isSimilar(incoming)) {
                int move = Math.min(incoming.count(), Math.max(0, Math.min(64, slots[i].maxStackSize()) - slots[i].count()));
                next[i] = slots[i].copyWithCount(slots[i].count() + move);
            }
            return next;
        }
        return null;
    }

    static CompoundTag write(Item[] slots) {
        if (slots.length != SLOTS) throw new IllegalArgumentException("Invalid trash can slots");
        ListTag list = new ListTag();
        for (int i = 0; i < SLOTS; i++) {
            if (slots[i].isEmpty()) continue;
            requireItem(slots[i]);
            Tag encoded = BlockEntityNbt.itemTag(slots[i]);
            if (!(encoded instanceof CompoundTag)) throw new IllegalArgumentException("Cannot save trash can item");
            CompoundTag entry = new CompoundTag(); entry.putInt("slot", i); entry.put("item", encoded); list.add(entry);
        }
        CompoundTag data = new CompoundTag(); data.putInt("data_version", VersionHelper.WORLD_VERSION); data.put("items", list);
        return data;
    }

    static Item[] read(CompoundTag data) {
        Item[] result = empty();
        if (data == null) return result;
        if (!(data.get("items") instanceof ListTag list) || list.size() > SLOTS)
            throw new IllegalArgumentException("Invalid trash can inventory");
        if (data.containsKey("data_version") && !(data.get("data_version") instanceof IntTag))
            throw new IllegalArgumentException("Invalid trash can data version");
        int version = BlockEntityNbt.dataVersion(data);
        boolean[] seen = new boolean[SLOTS];
        for (Tag raw : list) {
            if (!(raw instanceof CompoundTag entry) || !(entry.get("slot") instanceof IntTag slotTag)
                    || !(entry.get("item") instanceof CompoundTag)) throw new IllegalArgumentException("Invalid trash can slot");
            int slot = entry.getInt("slot");
            if (slot < 0 || slot >= SLOTS || seen[slot]) throw new IllegalArgumentException("Duplicate or invalid trash can slot");
            seen[slot] = true;
            Item parsed = BlockEntityNbt.getItem(entry, "item", version);
            requireItem(parsed); result[slot] = parsed.copy();
        }
        return result;
    }

    private static void requireItem(Item item) {
        if (item == null || item.isEmpty() || item.count() < 1 || item.count() > item.maxStackSize())
            throw new IllegalArgumentException("Invalid trash can item count");
    }
}
