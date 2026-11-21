package net.kaleidoscope.cookery.worldgen;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class VillageLootSpecTest {
    private String resource() throws Exception {
        try (var stream = VillageLootSpecTest.class.getResourceAsStream("/village-loot.json")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void assertMutationRejected(String original, String replacement) throws Exception {
        String bundled = resource();
        assertTrue(bundled.contains(original), "Mutation fixture was not found: " + original);
        String changed = bundled.replace(original, replacement);
        assertNotEquals(bundled, changed, "Mutation did not change the fixture");
        assertThrows(IllegalArgumentException.class, () -> VillageLootSpec.parse(changed));
    }

    @Test void completeSourceOrderIncludesBlankAndRealPotStockpotRecipes() {
        VillageLootSpec spec = VillageLootSpec.bundled();
        assertEquals(18, spec.itemMappings().size()); assertEquals(37, spec.recipes().size());
        assertNull(spec.recipes().get(0).recipe());
        assertEquals("kaleidoscopecookery:dark_cuisine", spec.recipes().get(0).result());
        var slime = spec.recipes().get(2).recipe();
        assertEquals(6, slime.ingredients().size()); assertTrue(slime.ingredients().stream().allMatch("minecraft:slime_ball"::equals));
        var soup = spec.recipes().stream().filter(choice -> choice.result().endsWith(":dough_drop_soup")).findFirst().orElseThrow().recipe();
        assertEquals("stockpot", soup.type()); assertEquals("minecraft:water_bucket", soup.soupBase());
        assertThrows(UnsupportedOperationException.class, () -> spec.recipes().clear());
    }

    @Test void alreadyRolledRecipeIndexIsDecodedWithoutMakingAnotherRandomChoice() {
        var spec = VillageLootSpec.bundled();
        for (int i = 0; i < 37; i++) {
            var token = spec.token("v1|recipe|" + i, VillageLootSpec.NORMAL_TABLE);
            assertNull(token.sourceItem()); assertSame(spec.recipes().get(i), token.recipe());
        }
        var tomato = spec.token("v1|item|kaleidoscope_cookery:tomato", VillageLootSpec.NORMAL_TABLE);
        assertNull(tomato.recipe()); assertEquals("kaleidoscope_cookery:tomato", tomato.sourceItem());
    }

    @Test void wrongVersionUnknownItemAndNoncanonicalOrOutOfRangeChoiceFailClosed() {
        var spec = VillageLootSpec.bundled();
        for (String value : new String[]{"v2|recipe|1", "v1|recipe|-1", "v1|recipe|01", "v1|recipe|37",
                "v1|recipe|1extra", "v1|item|minecraft:diamond", "v1|item|kaleidoscope_cookery:wild_rice_seed", "v1|unknown|1"})
            assertThrows(IllegalArgumentException.class, () -> spec.token(value, VillageLootSpec.NORMAL_TABLE), value);
    }

    @Test void tokensAreScopedToTheExactSourceTableEvenWithinThePluginNamespace() {
        var spec = VillageLootSpec.bundled();
        assertThrows(IllegalArgumentException.class, () -> spec.token("v1|recipe|0", VillageLootSpec.HIDDEN_TABLE));
        assertThrows(IllegalArgumentException.class, () -> spec.token("v1|item|kaleidoscope_cookery:tomato", VillageLootSpec.HIDDEN_TABLE));
        assertThrows(IllegalArgumentException.class, () -> spec.token("v1|item|kaleidoscope_cookery:slime_ball_meal", VillageLootSpec.NORMAL_TABLE));
        assertThrows(IllegalArgumentException.class, () -> spec.token("v1|recipe|0", "kaleidoscopecookery:chest/recipe_randomly"));
        assertEquals("kaleidoscope_cookery:slime_ball_meal", spec.token("v1|item|kaleidoscope_cookery:slime_ball_meal", VillageLootSpec.HIDDEN_TABLE).sourceItem());
    }

    @Test void alteredSourceOrCandidateOrderingDoesNotSilentlyActivate() throws Exception {
        assertMutationRejected("\"sourceCommit\": \"2f4e386ce23f49a385ddf003c67fc6415c55417a\"",
                "\"sourceCommit\": \"other\"");
        assertMutationRejected("\"index\": 0", "\"index\": 36");
    }

    @Test void changedItemMappingTargetFailsClosed() throws Exception {
        assertMutationRejected("\"kaleidoscope_cookery:tomato\": \"kaleidoscopecookery:tomato\"",
                "\"kaleidoscope_cookery:tomato\": \"kaleidoscopecookery:oil\"");
    }

    @Test void changedRecipeSourceResultFailsClosed() throws Exception {
        assertMutationRejected("\"sourceResult\": \"kaleidoscope_cookery:slime_ball_meal\"",
                "\"sourceResult\": \"kaleidoscope_cookery:dark_cuisine\"");
    }

    @Test void changedRecipeIngredientsFailClosed() throws Exception {
        assertMutationRejected("\"minecraft:slime_ball\"", "\"minecraft:magma_cream\"");
    }

    @Test void changedRecipeSourceHashFailsClosed() throws Exception {
        assertMutationRejected("\"sha256\": \"c1c7c7acce0d5b62cba787b696b1f31ca060e070f2b32fffed6fb64e0f88d3e6\"",
                "\"sha256\": \"d1c7c7acce0d5b62cba787b696b1f31ca060e070f2b32fffed6fb64e0f88d3e6\"");
    }

    @Test void changedSourceFileHashesFailClosed() throws Exception {
        assertMutationRejected("\"src/main/java/com/github/ysbbbbbb/kaleidoscopecookery/init/registry/FoodBiteRegistry.java\": \"60a1dd6af63ab34ddcab7954ed2add0f1f807aa87576efcadc2af3a7e36ed1ac\"",
                "\"src/main/java/com/github/ysbbbbbb/kaleidoscopecookery/init/registry/FoodBiteRegistry.java\": \"70a1dd6af63ab34ddcab7954ed2add0f1f807aa87576efcadc2af3a7e36ed1ac\"");
        assertMutationRejected("\"src/generated/resources/data/diet/tags/items/grains.json\": \"36312e5af483afda22b0e2b61a4935833594c376bc6cad638c9eae332d572475\"",
                "\"src/generated/resources/data/diet/tags/items/grains.json\": \"46312e5af483afda22b0e2b61a4935833594c376bc6cad638c9eae332d572475\"");
    }
}
