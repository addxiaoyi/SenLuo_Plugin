package net.kaleidoscope.cookery.item;

import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.ItemUtils;

import java.util.List;

/** Component-sensitive stack insertion, shared by bag and basket transfers. */
public final class LunchBagInventory {
    private LunchBagInventory() {}

    public static int insert(List<Item> slots, Item incoming, int capacity) {
        if (ItemUtils.isEmpty(incoming)) return 0;
        if (capacity < 1 || slots.size() > capacity || incoming.count() < 1 || incoming.maxStackSize() < 1)
            throw new IllegalArgumentException("Invalid lunch bag inventory");
        int remaining = incoming.count();
        int maximum = incoming.maxStackSize();
        for (int index = 0; index < slots.size() && remaining > 0; index++) {
            Item stored = slots.get(index);
            if (!stored.isSimilar(incoming)) continue;
            int room = Math.min(maximum, stored.maxStackSize()) - stored.count();
            if (room <= 0) continue;
            int moved = Math.min(room, remaining);
            slots.set(index, stored.copyWithCount(stored.count() + moved));
            remaining -= moved;
        }
        while (remaining > 0 && slots.size() < capacity) {
            int moved = Math.min(maximum, remaining);
            slots.add(incoming.copyWithCount(moved));
            remaining -= moved;
        }
        return incoming.count() - remaining;
    }
}
