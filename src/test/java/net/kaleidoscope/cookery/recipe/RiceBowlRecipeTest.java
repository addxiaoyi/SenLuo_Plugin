package net.kaleidoscope.cookery.recipe;

import com.google.gson.JsonObject;
import net.kaleidoscope.cookery.catalog.BakedFood;
import net.kaleidoscope.cookery.catalog.QualityProfile;
import net.kaleidoscope.cookery.item.CookeryFoodItems;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.recipe.CraftingRecipeCategory;
import net.momirealms.craftengine.core.item.recipe.CustomShapelessRecipe;
import net.momirealms.craftengine.core.item.recipe.Ingredient;
import net.momirealms.craftengine.core.item.recipe.RecipeSerializer;
import net.momirealms.craftengine.core.item.recipe.UniqueIdItem;
import net.momirealms.craftengine.core.item.recipe.input.CraftingInput;
import net.momirealms.craftengine.core.item.recipe.input.RecipeInput;
import net.momirealms.craftengine.core.item.recipe.result.CustomRecipeResult;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.UniqueKey;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RiceBowlRecipeTest {
    private static final Key ID = Key.of("kaleidoscopecookery:upstream/kaleidoscope_cookery/braised_beef_rice_bowl");
    private static final Key OUTPUT = Key.of("kaleidoscopecookery:braised_beef_rice_bowl");
    private final Item empty = mock(Item.class);
    private final Item dish = mock(Item.class);
    private final Item rice = mock(Item.class);
    private final Item extra = mock(Item.class);
    private final Item output = mock(Item.class);
    private final Item baked = mock(Item.class);
    private final Ingredient dishRole = mock(Ingredient.class);
    private final Ingredient riceRole = mock(Ingredient.class);
    private final CustomShapelessRecipe base = mock(CustomShapelessRecipe.class);
    private final CustomRecipeResult recipeResult = mock(CustomRecipeResult.class);
    private final ItemBuildContext context = mock(ItemBuildContext.class);
    private final Map<Item, UniqueIdItem> unique = new IdentityHashMap<>();
    private final Map<Item, ItemStack> stacks = new IdentityHashMap<>();
    private final List<MockedStatic<?>> handles = new ArrayList<>();
    private MockedStatic<CookeryFoodItems> foods;
    private MockedStatic<FlexMatcher> qualityBuilder;

    @BeforeEach void prepare() {
        when(empty.isEmpty()).thenReturn(true);
        when(dish.count()).thenReturn(64);
        when(rice.count()).thenReturn(64);
        when(extra.count()).thenReturn(32);
        when(output.id()).thenReturn(OUTPUT);
        when(output.count()).thenReturn(1);
        when(baked.count()).thenReturn(1);
        var ids = staticMock(UniqueIdItem.class);
        ids.when(() -> UniqueIdItem.of(any())).thenAnswer(call -> unique.get(call.getArgument(0)));
        for (Item item : List.of(empty, dish, rice, extra, output)) {
            UniqueIdItem wrapped = mock(UniqueIdItem.class);
            when(wrapped.item()).thenReturn(item);
            when(wrapped.isEmpty()).thenReturn(item == empty);
            unique.put(item, wrapped);
            stacks.put(item, mock(ItemStack.class));
        }
        var items = staticMock(Item.class);
        items.when(Item::empty).thenReturn(empty);
        var inventory = staticMock(InventoryUtils.class);
        inventory.when(() -> InventoryUtils.bukkitCopy(any())).thenAnswer(call -> stacks.get(call.getArgument(0)));
        foods = staticMock(CookeryFoodItems.class);
        foods.when(() -> CookeryFoodItems.bake(output, QualityProfile.BASE)).thenReturn(baked);
        qualityBuilder = staticMock(FlexMatcher.class);
        qualityBuilder.when(() -> FlexMatcher.applyQuality(eq(output), eq(OUTPUT), any())).thenReturn(baked);
        when(dishRole.count()).thenReturn(1);
        when(riceRole.count()).thenReturn(1);
        when(dishRole.items()).thenReturn(List.of(mock(UniqueKey.class)));
        when(riceRole.items()).thenReturn(List.of(mock(UniqueKey.class)));
        when(dishRole.test(any(UniqueIdItem.class))).thenAnswer(call -> call.getArgument(0) == unique.get(dish));
        when(riceRole.test(any(UniqueIdItem.class))).thenAnswer(call -> call.getArgument(0) == unique.get(rice));
        when(base.id()).thenReturn(ID);
        when(base.result()).thenReturn(recipeResult);
        when(base.group()).thenReturn("rice_bowl");
        when(base.category()).thenReturn(CraftingRecipeCategory.MISC);
        when(base.ingredientsInUse()).thenReturn(List.of(dishRole, riceRole));
        when(recipeResult.count()).thenReturn(1);
        when(recipeResult.buildItem(context)).thenReturn(output);
    }

    @AfterEach void close() {
        for (int index = handles.size() - 1; index >= 0; index--) handles.get(index).close();
    }

    @Test void craftingRebuildsFromInputAndLeavesEverySlotDebitToNativeCode() {
        var recipe = new RiceBowlRecipe(base);
        assertEquals(RiceBowlRecipe.TYPE, recipe.serializerType());
        assertTrue(recipe.requiresInput());
        assertTrue(recipe.alwaysRebuildOutput());
        assertFalse(recipe.ingredientCountSupport());
        assertEquals(2, recipe.placementInfo().ingredients().size());
    }

    @Test void matchingCountsOccupiedRolesAndAllowsUnrelatedExtraSlots() throws ReflectiveOperationException {
        var recipe = new RiceBowlRecipe(base);
        assertTrue(recipe.matches(grid(dish, rice, extra, empty)));
        assertTrue(recipe.matches(grid(empty, extra, rice, empty, dish, extra, empty, empty, empty)));
        assertFalse(recipe.matches(grid(dish, dish, rice, empty)));
        assertFalse(recipe.matches(grid(dish, rice, rice, extra)));
        assertFalse(recipe.matches(grid(dish, empty, extra, empty)));
        assertFalse(recipe.matches(mock(RecipeInput.class)));
        assertTrue(recipe.isRice(rice));
        assertFalse(recipe.isRice(dish));
        assertFalse(recipe.isRice(empty));
        assertFalse(recipe.isRice(null));
        verify(dish, never()).count(anyInt());
        verify(rice, never()).count(anyInt());
    }

    @Test void assembleAndVisualProduceTheSameQualityAwareSingleOutput() throws ReflectiveOperationException {
        var recipe = new RiceBowlRecipe(base);
        quality(dish, QualityProfile.of(DishQuality.POOR));
        quality(rice, QualityProfile.BASE);
        quality(extra, QualityProfile.of(DishQuality.SUPERB));
        CraftingInput input = grid(dish, rice, extra, empty);
        assertSame(baked, recipe.assemble(input, context));
        assertSame(baked, recipe.assembleVisual(input, context));
        assertEquals(1, baked.count());
        qualityBuilder.verify(() -> FlexMatcher.applyQuality(output, OUTPUT, DishQuality.SUPERB), times(2));
        foods.verify(() -> CookeryFoodItems.bake(output, QualityProfile.BASE), never());
        verify(recipeResult, times(2)).buildItem(context);
        verify(dish, never()).count(anyInt());
        verify(rice, never()).count(anyInt());
        verify(extra, never()).count(anyInt());
    }

    @Test void ungradedInputsUseBaseSnapshotWithoutInventingQualityLore() {
        var recipe = new RiceBowlRecipe(base);
        quality(rice, QualityProfile.BASE);
        assertSame(baked, recipe.buildResult(List.of(dish, rice), context));
        foods.verify(() -> CookeryFoodItems.bake(output, QualityProfile.BASE));
        qualityBuilder.verifyNoInteractions();
    }

    @Test void absentOrInvalidGridCannotBuildOutputThroughEitherPublicEntry() throws ReflectiveOperationException {
        var recipe = new RiceBowlRecipe(base);
        assertSame(empty, recipe.buildResult(List.of(), context));
        assertSame(empty, recipe.buildResult(List.of(dish, rice, rice), context));
        assertSame(empty, recipe.assemble(null, context));
        assertSame(empty, recipe.assembleVisual(mock(RecipeInput.class), context));
        assertSame(empty, recipe.assemble(grid(dish, extra), context));
        verify(recipeResult, never()).buildItem(any());
        foods.verifyNoInteractions();
        qualityBuilder.verifyNoInteractions();
    }

    @Test void corruptExtraFoodMetadataCannotDowngradeQualityAndProduceAResult() {
        var recipe = new RiceBowlRecipe(base);
        foods.when(() -> CookeryFoodItems.read(stacks.get(extra))).thenThrow(new IllegalArgumentException("Bad food PDC"));
        assertThrows(IllegalArgumentException.class, () -> recipe.buildResult(List.of(dish, rice, extra), context));
        verify(recipeResult, never()).buildItem(any());
        qualityBuilder.verifyNoInteractions();
    }

    @Test void malformedQualityAndUnbuildableResultFailBeforeDelivery() {
        var recipe = new RiceBowlRecipe(base);
        quality(extra, new QualityProfile("SUPERB", .4));
        assertThrows(IllegalArgumentException.class, () -> recipe.buildResult(List.of(dish, rice, extra), context));
        verify(recipeResult, never()).buildItem(any());
        foods.when(() -> CookeryFoodItems.read(stacks.get(extra))).thenReturn(null);
        when(recipeResult.buildItem(context)).thenReturn(empty);
        assertThrows(IllegalStateException.class, () -> recipe.buildResult(List.of(dish, rice), context));
    }

    @Test void constructorRejectsMultipleAmountsAndOverlappingIngredientRoles() {
        when(recipeResult.count()).thenReturn(2);
        assertThrows(IllegalArgumentException.class, () -> new RiceBowlRecipe(base));
        when(recipeResult.count()).thenReturn(1);
        when(riceRole.count()).thenReturn(2);
        assertThrows(IllegalArgumentException.class, () -> new RiceBowlRecipe(base));
        when(riceRole.count()).thenReturn(1);
        when(base.ingredientsInUse()).thenReturn(List.of(dishRole));
        assertThrows(IllegalArgumentException.class, () -> new RiceBowlRecipe(base));
        when(base.ingredientsInUse()).thenReturn(List.of(dishRole, riceRole));
        List<UniqueKey> sharedItems = dishRole.items();
        when(riceRole.items()).thenReturn(sharedItems);
        assertThrows(IllegalArgumentException.class, () -> new RiceBowlRecipe(base));
    }

    @Test void serializerRejectsWrongFieldsTypeCategoryAndResultBeforeCallingCe() throws ReflectiveOperationException {
        RecipeSerializer<RiceBowlRecipe> serializer = serializer();
        for (String field : List.of("type", "category", "ingredients", "result")) {
            var config = config(); config.remove(field);
            assertThrows(IllegalArgumentException.class, () -> serializer.readConfig(ID, ConfigSection.of("recipe", config)));
        }
        var unknown = config(); unknown.put("hidden_output", "minecraft:diamond");
        assertThrows(IllegalArgumentException.class, () -> serializer.readConfig(ID, ConfigSection.of("recipe", unknown)));
        for (Map.Entry<String, String> changed : Map.of("type", "minecraft:shapeless", "category", "equipment").entrySet()) {
            var config = config(); config.put(changed.getKey(), changed.getValue());
            assertThrows(IllegalArgumentException.class, () -> serializer.readConfig(ID, ConfigSection.of("recipe", config)));
        }
        for (Map<String, Object> result : List.<Map<String, Object>>of(
                Map.of("id", OUTPUT.asString(), "count", 2), Map.of("id", OUTPUT.asString()),
                Map.of("id", OUTPUT.asString(), "count", 1.9), Map.of("id", OUTPUT.asString(), "count", true),
                Map.of("id", OUTPUT.asString(), "count", "1"),
                Map.of("id", OUTPUT.asString(), "count", 1, "nbt", "{}"))) {
            var config = config(); config.put("result", result);
            assertThrows(IllegalArgumentException.class, () -> serializer.readConfig(ID, ConfigSection.of("recipe", config)));
        }
        assertThrows(IllegalArgumentException.class, () -> serializer.readJson(ID, new JsonObject()));
    }

    private void quality(Item item, QualityProfile quality) {
        BakedFood food = mock(BakedFood.class);
        when(food.quality()).thenReturn(quality);
        foods.when(() -> CookeryFoodItems.read(stacks.get(item))).thenReturn(food);
    }

    private CraftingInput grid(Item... slots) throws ReflectiveOperationException {
        CraftingInput grid = mock(CraftingInput.class);
        Field items = CraftingInput.class.getField("items");
        items.setAccessible(true);
        items.set(grid, Arrays.stream(slots).map(unique::get).toList());
        return grid;
    }

    @SuppressWarnings("unchecked")
    private static RecipeSerializer<RiceBowlRecipe> serializer() throws ReflectiveOperationException {
        var constructor = Class.forName(RiceBowlRecipe.class.getName() + "$Serializer").getDeclaredConstructor();
        constructor.setAccessible(true);
        return (RecipeSerializer<RiceBowlRecipe>) constructor.newInstance();
    }

    private static Map<String, Object> config() {
        return new LinkedHashMap<>(Map.of("type", RiceBowlRecipe.TYPE.asString(), "category", "misc",
                "ingredients", List.of(List.of("kaleidoscopecookery:braised_beef"), List.of("kaleidoscopecookery:cooked_rice")),
                "result", Map.of("id", OUTPUT.asString(), "count", 1)));
    }

    private <T> MockedStatic<T> staticMock(Class<T> type) {
        MockedStatic<T> handle = mockStatic(type); handles.add(handle); return handle;
    }
}
