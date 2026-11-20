package net.kaleidoscope.cookery.plugin;

import net.momirealms.craftengine.core.item.behavior.ItemBehaviorType;
import net.momirealms.craftengine.core.util.Key;
import net.kaleidoscope.cookery.item.behavior.SickleRangeHarvestBehavior;
import net.kaleidoscope.cookery.item.behavior.OilPotItemBehavior;
import net.kaleidoscope.cookery.item.behavior.TeapotItemBehavior;
import net.kaleidoscope.cookery.item.behavior.TransmutationLunchBagBehavior;

// 物品行为注册
public final class ItemBehaviors {
    public static ItemBehaviorType<SickleRangeHarvestBehavior> SICKLE_RANGE_HARVEST;
    public static ItemBehaviorType<TeapotItemBehavior> TEAPOT;
    public static ItemBehaviorType<OilPotItemBehavior> OIL_POT;
    public static ItemBehaviorType<TransmutationLunchBagBehavior> TRANSMUTATION_LUNCH_BAG;
    public static ItemBehaviorType<net.kaleidoscope.cookery.item.behavior.EightImmortalsTableItemBehavior> EIGHT_IMMORTALS_TABLE;
    public static ItemBehaviorType<net.kaleidoscope.cookery.item.behavior.PlacedMealItemBehavior> PLACED_MEAL;
    public static ItemBehaviorType<net.kaleidoscope.cookery.item.behavior.SteamerItemBehavior> STEAMER;

    private ItemBehaviors() {}

    public static void register() {
        if (STEAMER == null) STEAMER = RegistryUtils.registerItemBehavior(
                Key.of("kaleidoscopecookery:steamer_item"), net.kaleidoscope.cookery.item.behavior.SteamerItemBehavior.FACTORY);
        if (EIGHT_IMMORTALS_TABLE == null) EIGHT_IMMORTALS_TABLE = RegistryUtils.registerItemBehavior(
                Key.of("kaleidoscopecookery:eight_immortals_table_item"), net.kaleidoscope.cookery.item.behavior.EightImmortalsTableItemBehavior.FACTORY);
        if (PLACED_MEAL == null) PLACED_MEAL = RegistryUtils.registerItemBehavior(
                Key.of("kaleidoscopecookery:placed_meal_item"), net.kaleidoscope.cookery.item.behavior.PlacedMealItemBehavior.FACTORY);
        if (SICKLE_RANGE_HARVEST == null) {
            SICKLE_RANGE_HARVEST = RegistryUtils.registerItemBehavior(
                    Key.of("kaleidoscopecookery:sickle_range_harvest"),
                    SickleRangeHarvestBehavior.FACTORY
            );
        }
        if (SICKLE_RANGE_HARVEST == null) {
            SICKLE_RANGE_HARVEST = RegistryUtils.registerItemBehavior(
                    Key.of("kaleidoscopecookery:sickle_range_harvest"),
                    SickleRangeHarvestBehavior.FACTORY
            );
        }
        if (TEAPOT == null) {
            TEAPOT = RegistryUtils.registerItemBehavior(
                    Key.of("kaleidoscopecookery:teapot_item"),
                    TeapotItemBehavior.FACTORY
            );
        }
        if (OIL_POT == null) {
            OIL_POT = RegistryUtils.registerItemBehavior(
                    Key.of("kaleidoscopecookery:oil_pot_item"),
                    OilPotItemBehavior.FACTORY
            );
        }
        if (TRANSMUTATION_LUNCH_BAG == null) {
            TRANSMUTATION_LUNCH_BAG = RegistryUtils.registerItemBehavior(
                    Key.of("kaleidoscopecookery:transmutation_lunch_bag"),
                    TransmutationLunchBagBehavior.FACTORY
            );
        }
    }
}
