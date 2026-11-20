package net.kaleidoscope.cookery.recipe.edit;

import net.kaleidoscope.cookery.recipe.StockpotRecipe;
import net.kaleidoscope.cookery.recipe.StockpotVisuals;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StockpotRecipeDraftTest {
    private static final StockpotVisuals VISUALS = new StockpotVisuals(Key.of("test:cooking"), Key.of("test:finished"), 0x123456, 0x654321);

    @Test void editingPreservesStrictFieldsAndOriginalIdentity() {
        StockpotRecipe original = new StockpotRecipe(Key.of("test:soup"), StockpotRecipe.Mode.EXACT,
                List.of(Set.of(Key.of("minecraft:bone"), Key.of("minecraft:carrot"))), Key.of("minecraft:water_bucket"),
                Key.of("minecraft:beef"), 2, 240, null, VISUALS);
        StockpotRecipeDraft draft = StockpotRecipeDraft.editing(original);
        assertFalse(draft.isNew());
        assertSame(original, draft.originalRecipe());
        assertEquals(original, draft.toRecipe());
        draft.time(300);
        assertEquals(300, draft.toRecipe().time());
        assertEquals(240, original.time());
    }

    @Test void ingredientInputIsDefensivelyCopied() {
        StockpotRecipeDraft draft = StockpotRecipeDraft.creating(Key.of("test:new"));
        var input = new java.util.HashSet<>(Set.of(Key.of("minecraft:bone")));
        draft.ingredients(List.of(input));
        input.add(Key.of("minecraft:carrot"));
        assertEquals(Set.of(Key.of("minecraft:bone")), draft.ingredients().getFirst());
    }
}
