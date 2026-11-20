package net.kaleidoscope.cookery.recipe;

import net.kaleidoscope.cookery.item.ItemKeys;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.recipe.transform.ItemTransformDataProcessor;
import net.momirealms.craftengine.core.item.recipe.transform.ItemTransformDataProcessors;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import java.util.Set;

/** Preserve user NBT after CE replaces the base custom_data with the target identity. */
public final class KitchenKnifeSmithing implements ItemTransformDataProcessor {
    public static final Key TYPE = Key.of("kaleidoscopecookery:kitchen_knife_smithing");
    static final Key CUSTOM_DATA = Key.of("minecraft:custom_data");
    public static final ItemTransformDataProcessor.Factory<KitchenKnifeSmithing> FACTORY = KitchenKnifeSmithing::fromConfig;

    public static void register() { ItemTransformDataProcessors.register(TYPE, FACTORY); }

    static KitchenKnifeSmithing fromConfig(ConfigSection config) {
        if (!config.keySet().equals(Set.of("type")) || !TYPE.asString().equals(config.getString("type")))
            throw new IllegalArgumentException("Unexpected kitchen knife smithing processor at " + config.path());
        return new KitchenKnifeSmithing();
    }

    @Override public void accept(Item base, Item target, Item output) {
        if (base == null || target == null || output == null || base.isEmpty() || target.isEmpty() || output.isEmpty()
                || base == output || target == output || !ItemKeys.DIAMOND_KITCHEN_KNIFE.equals(base.id())
                || !ItemKeys.NETHERITE_KITCHEN_KNIFE.equals(target.id())
                || !ItemKeys.NETHERITE_KITCHEN_KNIFE.equals(output.id()) || target.count() != 1 || output.count() != 1) {
            throw new IllegalArgumentException("Kitchen knife smithing requires detached diamond-to-netherite output");
        }
        var sourceData = compound(base.getComponentAsSparrowTag(CUSTOM_DATA));
        var targetData = compound(target.getComponentAsSparrowTag(CUSTOM_DATA));
        CompoundTag merged = targetData.deepClone();
        for (var entry : sourceData.entrySet()) {
            // Engine identity/arguments belong to the freshly constructed target item.
            if (!entry.getKey().startsWith("craftengine:")) overlay(merged, entry.getKey(), entry.getValue());
        }
        output.setSparrowTagComponent(CUSTOM_DATA, merged);
        output.customId(ItemKeys.NETHERITE_KITCHEN_KNIFE);
    }

    private static CompoundTag compound(Tag tag) {
        if (tag == null) return new CompoundTag();
        if (tag instanceof CompoundTag compound) return compound;
        throw new IllegalArgumentException("Item custom_data is not a compound");
    }

    /** Base user values win recursively; absent target defaults remain. Inputs are never mutated. */
    private static void overlay(CompoundTag into, String key, Tag value) {
        if (value instanceof CompoundTag source && into.get(key) instanceof CompoundTag destination) {
            for (var entry : source.entrySet()) overlay(destination, entry.getKey(), entry.getValue());
        } else into.put(key, value.deepClone());
    }
}
