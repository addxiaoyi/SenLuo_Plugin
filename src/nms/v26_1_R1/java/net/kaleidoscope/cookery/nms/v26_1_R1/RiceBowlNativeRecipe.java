package net.kaleidoscope.cookery.nms.v26_1_R1;

import net.kaleidoscope.cookery.recipe.RiceBowlRecipe;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.nms.FastNMS;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.recipe.UniqueIdItem;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Native result slots retain their normal debit, remainder and shift-craft lifecycle. */
public final class RiceBowlNativeRecipe extends ShapelessRecipe {
    private final RiceBowlRecipe recipe;
    private final ShapelessRecipe remainderDelegate;

    public RiceBowlNativeRecipe(RiceBowlRecipe recipe) {
        super(new Recipe.CommonInfo(recipe.showNotification()),
                new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.valueOf(recipe.category().name()), recipe.group()),
                ItemStackTemplate.fromNonEmptyStack((ItemStack) recipe.buildVisualOrActualResult(ItemBuildContext.empty()).minecraftItem()),
                ingredients(recipe));
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        if (recipe.ingredientCountSupport()) throw new IllegalArgumentException("Rice bowl input must be debited by the native result slot");
        remainderDelegate = (ShapelessRecipe) FastNMS.INSTANCE.createShapelessRecipe(recipe);
    }

    private static List<Ingredient> ingredients(RiceBowlRecipe recipe) {
        return recipe.ingredientsInUse().stream()
                .map(ingredient -> (Ingredient) FastNMS.INSTANCE.toMinecraftIngredient(ingredient)).toList();
    }

    @Override public boolean matches(CraftingInput input, Level level) {
        // Vanilla shapeless matching rejects extra occupied cells that the source recipe permits.
        return recipe.matches(craftingInput(input));
    }

    @Override public ItemStack assemble(CraftingInput input) {
        return (ItemStack) recipe.assemble(craftingInput(input), ItemBuildContext.empty()).minecraftItem();
    }

    @Override public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> remaining = remainderDelegate.getRemainingItems(input);
        for (int slot = 0; slot < input.size(); slot++) {
            if (recipe.isRice(BukkitItemManager.instance().wrap(input.getItem(slot)))) {
                remaining.set(slot, new ItemStack(Items.BOWL));
            }
        }
        return remaining;
    }

    private static net.momirealms.craftengine.core.item.recipe.input.CraftingInput craftingInput(CraftingInput input) {
        List<UniqueIdItem> items = new ArrayList<>(input.size());
        for (int slot = 0; slot < input.size(); slot++) {
            items.add(UniqueIdItem.of(BukkitItemManager.instance().wrap(input.getItem(slot))));
        }
        return net.momirealms.craftengine.core.item.recipe.input.CraftingInput.of(input.width(), input.height(), items);
    }
}
