package net.kaleidoscope.cookery.recipe;

import com.google.gson.JsonObject;
import net.kaleidoscope.cookery.catalog.QualityProfile;
import net.kaleidoscope.cookery.item.CookeryFoodItems;
import net.kaleidoscope.cookery.nms.NmsBridgeProvider;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.item.recipe.BukkitRecipeManager;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.recipe.CustomShapelessRecipe;
import net.momirealms.craftengine.core.item.recipe.RecipeSerializer;
import net.momirealms.craftengine.core.item.recipe.RecipeSerializers;
import net.momirealms.craftengine.core.item.recipe.UniqueIdItem;
import net.momirealms.craftengine.core.item.recipe.input.CraftingInput;
import net.momirealms.craftengine.core.item.recipe.input.RecipeInput;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Source custom crafting: one dish slot and one rice slot; extra slots retain vanilla consumption. */
public final class RiceBowlRecipe extends CustomShapelessRecipe {
    public static final Key TYPE = Key.of("kaleidoscopecookery:rice_bowl");
    private static final Set<String> FIELDS = Set.of("type", "category", "ingredients", "result");

    public RiceBowlRecipe(CustomShapelessRecipe base) {
        super(base.id(), base.showNotification(), base.result(), base.visualResult(), base.group(), base.category(),
                base.ingredientsInUse(), base.functions(), null, true, false);
        if (ingredientsInUse().size() != 2 || result().count() != 1
                || ingredientsInUse().stream().anyMatch(ingredient -> ingredient.count() != 1)) {
            throw new IllegalArgumentException("Rice bowl requires two single-slot ingredients and one result: " + id());
        }
        if (ingredientsInUse().getFirst().items().stream().anyMatch(ingredientsInUse().get(1).items()::contains)) {
            throw new IllegalArgumentException("Rice and dish inputs overlap: " + id());
        }
    }

    public static void register() {
        RecipeSerializers.register(TYPE, new Serializer());
        BukkitRecipeManager.RECIPE_GENERATOR.put(TYPE, recipe ->
                NmsBridgeProvider.bridge().createRiceBowlRecipe((RiceBowlRecipe) recipe));
    }

    @Override public Key serializerType() { return TYPE; }
    @Override public boolean requiresInput() { return true; }

    @Override public boolean matches(RecipeInput input) {
        if (!(input instanceof CraftingInput grid)) return false;
        return RiceBowlRules.matches(grid.items.stream().map(UniqueIdItem::item).toList(),
                item -> ingredientsInUse().getFirst().test(UniqueIdItem.of(item)), this::isRice);
    }

    public boolean isRice(Item item) {
        return item != null && !item.isEmpty() && ingredientsInUse().get(1).test(UniqueIdItem.of(item));
    }

    @Override public Item assemble(RecipeInput input, ItemBuildContext context) {
        if (!(input instanceof CraftingInput grid) || !matches(grid)) return Item.empty();
        return buildResult(grid.items.stream().map(UniqueIdItem::item).toList(), context);
    }

    @Override public Item assembleVisual(RecipeInput input, ItemBuildContext context) {
        return assemble(input, context);
    }

    public Item buildResult(List<Item> grid, ItemBuildContext context) {
        if (!RiceBowlRules.matches(grid,
                item -> ingredientsInUse().getFirst().test(UniqueIdItem.of(item)), this::isRice)) return Item.empty();
        List<QualityProfile> qualities = new ArrayList<>();
        for (Item item : grid) {
            if (item == null || item.isEmpty()) continue;
            var baked = CookeryFoodItems.read(InventoryUtils.bukkitCopy(item));
            if (baked != null) qualities.add(baked.quality());
        }
        QualityProfile quality = RiceBowlRules.bestQuality(qualities);
        Item output = result().buildItem(context);
        if (output == null || output.isEmpty()) throw new IllegalStateException("Cannot build rice bowl " + id());
        if (quality.equals(QualityProfile.BASE)) return CookeryFoodItems.bake(output, quality);
        return FlexMatcher.applyQuality(output, output.id(), DishQuality.valueOf(quality.name()));
    }

    private static final class Serializer implements RecipeSerializer<RiceBowlRecipe> {
        @Override public RiceBowlRecipe readConfig(Key id, ConfigSection config) {
            if (!config.keySet().equals(FIELDS)) throw new IllegalArgumentException("Unexpected rice bowl fields at " + config.path());
            if (!TYPE.asString().equals(config.getString("type")) || !"misc".equals(config.getString("category"))) {
                throw new IllegalArgumentException("Rice bowl type/category differs at " + config.path());
            }
            ConfigSection result = config.getNonNullSection("result");
            if (!result.keySet().equals(Set.of("id", "count"))
                    || !(result.get("count") instanceof Integer count) || count != 1) {
                throw new IllegalArgumentException("Rice bowl requires a fixed single result at " + config.path());
            }
            return new RiceBowlRecipe(CustomShapelessRecipe.SERIALIZER.readConfig(id, config));
        }
        @Override public RiceBowlRecipe readJson(Key id, JsonObject json) {
            throw new IllegalArgumentException("Rice bowls require the pinned CraftEngine configuration: " + id);
        }
    }
}
