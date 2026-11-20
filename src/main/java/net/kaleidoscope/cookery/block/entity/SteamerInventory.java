package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.recipe.CookingPlan;
import net.kaleidoscope.cookery.util.BlockEntityNbt;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.libraries.nbt.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Slot indices are physical positions: 0–3 below, 4–7 above. Never compact them. */
public final class SteamerInventory {
    public static final int SIZE = 8;
    private SteamerInventory() {}

    public record Slot(Item item, int progress, int time, CookingPlan plan, boolean blocked) {}

    public static List<Slot> read(CompoundTag data, int defaultTime) {
        List<Slot> result = new ArrayList<>(Collections.nCopies(SIZE, null));
        if (data == null) return result;
        boolean modern = data.containsKey("inventory_schema");
        if (modern && (!(data.get("inventory_schema") instanceof IntTag) || data.getInt("inventory_schema") != 1))
            throw new IllegalArgumentException("Unsupported steamer inventory schema");
        if (modern) for (String required : List.of("data_version", "slots", "cooking_progress", "cooking_time", "completion_blocked", "cooking_plans")) {
            if (!data.containsKey(required)) throw new IllegalArgumentException("Missing steamer field: " + required);
        }
        if (data.containsKey("data_version") && !(data.get("data_version") instanceof IntTag))
            throw new IllegalArgumentException("Invalid steamer item version");
        if (modern && data.containsKey("items") || !modern && data.containsKey("slots"))
            throw new IllegalArgumentException("Ambiguous steamer inventory");
        String listKey = modern ? "slots" : "items";
        ListTag entries = data.getList(listKey);
        if (entries == null && (modern || data.containsKey(listKey)) || entries != null && entries.size() > SIZE)
            throw new IllegalArgumentException("Invalid steamer item list");
        int[] progress = array(data, "cooking_progress", 0);
        int[] time = array(data, "cooking_time", Math.max(1, defaultTime));
        int[] blocked = array(data, "completion_blocked", 0);
        CompoundTag plans = data.getCompound("cooking_plans");
        if (plans == null && data.containsKey("cooking_plans"))
            throw new IllegalArgumentException("Invalid steamer plans");
        if (plans != null) for (String key : plans.keySet()) {
            if (!key.matches("[0-7]")) throw new IllegalArgumentException("Invalid steamer plan slot");
        }
        if (entries == null) {
            if (plans != null && !plans.isEmpty()) throw new IllegalArgumentException("Steamer plans have no items");
            return result;
        }
        int version = BlockEntityNbt.dataVersion(data);
        int denseSlot = 0;
        for (Tag raw : entries) {
            if (!(raw instanceof CompoundTag entry)) throw new IllegalArgumentException("Invalid steamer item entry");
            int index = modern && entry.get("slot") instanceof IntTag ? entry.getInt("slot") : modern ? -1 : denseSlot++;
            if (index < 0 || index >= SIZE || result.get(index) != null)
                throw new IllegalArgumentException("Invalid or duplicate steamer slot");
            CompoundTag wrapped = new CompoundTag();
            Tag itemData = modern ? entry.get("item") : entry;
            if (!(itemData instanceof CompoundTag)) throw new IllegalArgumentException("Invalid steamer item data");
            wrapped.put("item", itemData);
            Item item = BlockEntityNbt.getItem(wrapped, "item", version);
            validateItem(item);
            if (progress[index] < 0 || time[index] < -1 || blocked[index] < 0 || blocked[index] > 1)
                throw new IllegalArgumentException("Invalid steamer cooking state");
            CookingPlan plan = plans != null && plans.containsKey(Integer.toString(index))
                    ? CookingPlan.load(plans.getCompound(Integer.toString(index))) : null;
            if (plan != null && !plan.valid()) throw new IllegalArgumentException("Unreadable saved steamer plan");
            result.set(index, new Slot(item.copy(), progress[index], time[index], plan, blocked[index] != 0));
        }
        if (plans != null) for (String key : plans.keySet()) {
            if (result.get(Integer.parseInt(key)) == null) throw new IllegalArgumentException("Steamer plan has no item");
        }
        return result;
    }

    public static void write(CompoundTag data, Item[] items, int[] progress, int[] time,
                             CookingPlan[] plans, boolean[] blocked) {
        if (items.length != SIZE || progress.length != SIZE || time.length != SIZE || plans.length != SIZE || blocked.length != SIZE)
            throw new IllegalArgumentException("Invalid steamer inventory size");
        ListTag entries = new ListTag();
        CompoundTag savedPlans = new CompoundTag();
        int[] savedBlocked = new int[SIZE];
        int[] savedProgress = new int[SIZE], savedTime = new int[SIZE];
        for (int slot = 0; slot < SIZE; slot++) {
            if (ItemUtils.isEmpty(items[slot])) continue;
            validateItem(items[slot]);
            if (progress[slot] < 0 || time[slot] < -1 || plans[slot] != null && !plans[slot].valid())
                throw new IllegalArgumentException("Invalid steamer cooking state");
            Tag encoded = BlockEntityNbt.itemTag(items[slot]);
            if (!(encoded instanceof CompoundTag)) throw new IllegalArgumentException("Cannot save steamer item");
            CompoundTag entry = new CompoundTag();
            entry.putInt("slot", slot); entry.put("item", encoded.deepClone()); entries.add(entry);
            savedProgress[slot] = progress[slot]; savedTime[slot] = time[slot];
            if (plans[slot] != null) savedPlans.put(Integer.toString(slot), plans[slot].save());
            savedBlocked[slot] = blocked[slot] ? 1 : 0;
        }
        data.putInt("inventory_schema", 1);
        data.remove("items");
        data.put("slots", entries);
        data.putIntArray("cooking_progress", savedProgress);
        data.putIntArray("cooking_time", savedTime);
        data.put("cooking_plans", savedPlans);
        data.putIntArray("completion_blocked", savedBlocked);
    }

    private static void validateItem(Item item) {
        if (ItemUtils.isEmpty(item) || item.count() < 1 || item.maxStackSize() < item.count())
            throw new IllegalArgumentException("Unreadable or invalid steamer item");
    }

    private static int[] array(CompoundTag data, String key, int fallback) {
        if (!data.containsKey(key)) { int[] values = new int[SIZE]; java.util.Arrays.fill(values, fallback); return values; }
        if (!(data.get(key) instanceof IntArrayTag) || data.getIntArray(key).length != SIZE)
            throw new IllegalArgumentException("Invalid steamer array: " + key);
        return data.getIntArray(key).clone();
    }
}
