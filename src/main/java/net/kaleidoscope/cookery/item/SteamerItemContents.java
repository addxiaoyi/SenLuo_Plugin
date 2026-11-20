package net.kaleidoscope.cookery.item;

import net.kaleidoscope.cookery.block.entity.SteamerInventory;
import net.kaleidoscope.cookery.recipe.CookingPlan;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.libraries.nbt.*;
import java.util.*;

/** A portable steamer owns four physical slots. Its custom identity never changes. */
public final class SteamerItemContents {
    public static final Key ID = Key.of("kaleidoscopecookery:steamer");
    public static final Key CUSTOM_DATA = Key.of("minecraft:custom_data");
    public static final String DATA = "kaleidoscopecookery:steamer_contents";
    private static final Key STACK_SIZE = Key.of("minecraft:max_stack_size");
    private SteamerItemContents() {}

    public static List<SteamerInventory.Slot> read(Item item) {
        if (item == null || item.isEmpty() || !ID.equals(item.id())) throw new IllegalArgumentException("Not a steamer item");
        Tag raw = item.getComponentAsSparrowTag(CUSTOM_DATA);
        if (raw != null && !(raw instanceof CompoundTag)) throw new IllegalArgumentException("Invalid steamer custom_data");
        CompoundTag custom = raw == null ? new CompoundTag() : (CompoundTag) raw;
        if (custom.containsKey("BlockEntityTag") || item.getComponentAsSparrowTag(Key.of("minecraft:block_entity_data")) != null)
            throw new IllegalArgumentException("Steamer block-entity data requires migration");
        if (!custom.containsKey(DATA)) {
            return new ArrayList<>(Collections.nCopies(4, null));
        }
        if (item.count() != 1 || !(custom.get(DATA) instanceof CompoundTag data))
            throw new IllegalArgumentException("Filled steamer must be one item with a valid payload");
        List<SteamerInventory.Slot> slots = SteamerInventory.read(data, 1200);
        for (int i = 4; i < 8; i++) if (slots.get(i) != null) throw new IllegalArgumentException("Portable steamer has upper-layer contents");
        return new ArrayList<>(slots.subList(0, 4));
    }

    public static Item build(List<SteamerInventory.Slot> slots) {
        Item base = InventoryUtils.createOrEmpty(ID);
        if (base == null || base.isEmpty()) throw new IllegalStateException("Steamer item is unavailable");
        return withContents(base, slots);
    }

    /** Return a detached result; bad contents never alter the caller's stack or custom metadata. */
    public static Item withContents(Item base, List<SteamerInventory.Slot> slots) {
        if (base == null || base.isEmpty() || !ID.equals(base.id()) || slots.size() != 4)
            throw new IllegalArgumentException("Invalid portable steamer");
        Item[] items = new Item[8]; Arrays.fill(items, Item.empty());
        int[] progress = new int[8], time = new int[8];
        CookingPlan[] plans = new CookingPlan[8]; boolean[] blocked = new boolean[8];
        for (int i = 0; i < 4; i++) {
            var slot = slots.get(i); if (slot == null) continue;
            if (slot.progress() < 0 || slot.time() < -1 || slot.plan() != null && !slot.plan().valid())
                throw new IllegalArgumentException("Invalid portable cooking state");
            items[i] = slot.item(); progress[i] = slot.progress(); time[i] = slot.time(); plans[i] = slot.plan(); blocked[i] = slot.blocked();
        }
        CompoundTag data = new CompoundTag(); data.putInt("data_version", VersionHelper.WORLD_VERSION);
        SteamerInventory.write(data, items, progress, time, plans, blocked);
        Tag raw = base.getComponentAsSparrowTag(CUSTOM_DATA);
        if (raw != null && !(raw instanceof CompoundTag)) throw new IllegalArgumentException("Invalid steamer custom_data");
        CompoundTag custom = raw == null ? new CompoundTag() : ((CompoundTag) raw).deepClone();
        if (custom.containsKey("BlockEntityTag") || base.getComponentAsSparrowTag(Key.of("minecraft:block_entity_data")) != null)
            throw new IllegalArgumentException("Steamer block-entity data requires migration");
        if (slots.stream().allMatch(Objects::isNull)) custom.remove(DATA); else custom.put(DATA, data);
        Item output = base.copyWithCount(1);
        if (output == base) throw new IllegalArgumentException("Steamer copy is not detached");
        output.setSparrowTagComponent(CUSTOM_DATA, custom);
        output.setSparrowTagComponent(STACK_SIZE, new IntTag(slots.stream().allMatch(Objects::isNull) ? 64 : 1));
        return output;
    }

    public static CompoundTag payload(List<SteamerInventory.Slot> slots) {
        if (slots.size() != 8) throw new IllegalArgumentException("Steamer block needs eight slots");
        Item[] items = new Item[8]; Arrays.fill(items, Item.empty());
        int[] progress = new int[8], time = new int[8]; CookingPlan[] plans = new CookingPlan[8]; boolean[] blocked = new boolean[8];
        for (int i = 0; i < 8; i++) {
            var slot = slots.get(i); if (slot == null) continue;
            items[i] = slot.item(); progress[i] = slot.progress(); time[i] = slot.time(); plans[i] = slot.plan(); blocked[i] = slot.blocked();
        }
        CompoundTag data = new CompoundTag(); data.putInt("data_version", VersionHelper.WORLD_VERSION);
        SteamerInventory.write(data, items, progress, time, plans, blocked);
        return data;
    }

    public static List<SteamerInventory.Slot> merge(List<SteamerInventory.Slot> bottom, List<SteamerInventory.Slot> incoming) {
        if (bottom.size() != 8 || incoming.size() != 4) throw new IllegalArgumentException("Invalid steamer layers");
        for (int i = 4; i < 8; i++) if (bottom.get(i) != null) throw new IllegalArgumentException("Existing upper layer is occupied");
        List<SteamerInventory.Slot> result = new ArrayList<>(bottom.subList(0, 4)); result.addAll(incoming); return result;
    }

    /** Build every layer before ownership leaves the block or falling entity. */
    public static List<Item> split(List<SteamerInventory.Slot> slots, int capacity, boolean omitContents) {
        if (slots.size() != 8 || capacity != 4 && capacity != 8) throw new IllegalArgumentException("Invalid steamer capacity");
        for (int i = capacity; i < 8; i++) if (slots.get(i) != null)
            throw new IllegalArgumentException("Steamer contents exceed its physical layers");
        List<Item> drops = new ArrayList<>();
        for (int offset = 0; offset < capacity; offset += 4)
            drops.add(build(omitContents ? Collections.nCopies(4, null) : slots.subList(offset, offset + 4)));
        return List.copyOf(drops);
    }
}
