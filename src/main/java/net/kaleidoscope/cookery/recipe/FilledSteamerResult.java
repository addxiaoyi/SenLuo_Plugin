package net.kaleidoscope.cookery.recipe;

import net.kaleidoscope.cookery.block.entity.SteamerInventory;
import net.kaleidoscope.cookery.item.SteamerItemContents;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.recipe.result.*;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import java.util.*;

/** Exact pinned Forge-NBT recipe result, encoded with the current server's item codec. */
public record FilledSteamerResult(Key ingredient) implements PostProcessor {
    public static final Key TYPE = Key.of("kaleidoscopecookery:filled_steamer");
    public static void register() { PostProcessors.register(TYPE, FilledSteamerResult::fromConfig); }
    static FilledSteamerResult fromConfig(ConfigSection config) {
        if (!config.keySet().equals(Set.of("type", "ingredient")) || !TYPE.asString().equals(config.getString("type")))
            throw new IllegalArgumentException("Unexpected filled-steamer recipe config");
        return new FilledSteamerResult(Key.of(config.getString("ingredient")));
    }
    public FilledSteamerResult {
        if (!Set.of(Key.of("kaleidoscopecookery:raw_dough"), Key.of("kaleidoscopecookery:stuffed_dough_food")).contains(ingredient))
            throw new IllegalArgumentException("Unsupported filled-steamer ingredient");
    }
    @Override public Item process(Item result, ItemBuildContext context) {
        Item raw = InventoryUtils.createOrEmpty(ingredient);
        if (raw == null || raw.isEmpty()) throw new IllegalStateException("Missing filled-steamer ingredient " + ingredient);
        List<SteamerInventory.Slot> slots = new ArrayList<>();
        for (int i = 0; i < 4; i++) slots.add(new SteamerInventory.Slot(raw.copyWithCount(1), 0, 1200, null, false));
        return SteamerItemContents.withContents(result, slots);
    }
}
