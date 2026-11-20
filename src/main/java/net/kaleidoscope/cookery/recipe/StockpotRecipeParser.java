package net.kaleidoscope.cookery.recipe;

import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.lifecycle.LoadingStage;
import net.momirealms.craftengine.core.util.Key;
import net.kaleidoscope.cookery.recipe.edit.RecipeFileStore;
import net.kaleidoscope.cookery.recipe.edit.RecipeSourceIndex;

import java.nio.file.Path;
import java.util.List;

/** Imported recipes use the strict schema and participate in the existing atomic reload. */
final class StockpotRecipeParser extends FoodRecipeManager.CookeryIdParser {
    static final LoadingStage STAGE = new LoadingStage("stockpot imported recipes");
    StockpotRecipeParser() {
        super(STAGE, List.of(FoodRecipeManager.STOCK_FOOD_RAW), RecipeSourceIndex.Kind.STOCKPOT,
                "stockpot_recipes", "stockpot-recipes");
    }

    @Override protected void reset() {
        RecipeSourceIndex.instance().clearKind(kind());
        FoodRecipeRegistry.instance().clearStockpot();
    }

    @Override protected int parseAndCount(Pack pack, Path path, Key id, ConfigSection section,
                                          RecipeFileStore.SourceTarget target) {
        StockpotRecipe recipe = StockpotRecipe.fromConfig(id, section);
        FoodRecipeManager.validateStockpotRecipe(recipe, section.path());
        if (duplicated(id)) {
            throw new IllegalArgumentException(section.path() + ": duplicate stockpot recipe id " + id);
        }
        RecipeSourceIndex.instance().put(kind(), id, path, target, recipe, false);
        FoodRecipeRegistry.instance().registerStockpot(recipe);
        return 1;
    }
}
