package net.kaleidoscope.cookery.worldgen;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VillageTemplatePlanTest {
    private final VillageStructureSources sources = VillageStructureSources.bundled();

    private VillageTemplatePlan.State state(JsonObject row) {
        var properties = new LinkedHashMap<String, String>();
        if (row.has("Properties")) row.getAsJsonObject("Properties").entrySet().forEach(entry -> properties.put(entry.getKey(), entry.getValue().getAsString()));
        return new VillageTemplatePlan.State(row.get("Name").getAsString(), properties);
    }

    /** Complete position ledger with the pinned custom/BE semantics; other cells use a known vanilla palette entry. */
    private List<VillageTemplatePlan.SourceBlock> ledger(VillageTemplatePlan plan) {
        int vanilla = 0;
        while (plan.sourcePalette().get(vanilla).custom()) vanilla++;
        var blocks = new LinkedHashMap<VillageTemplatePlan.Position, VillageTemplatePlan.SourceBlock>();
        for (int y = 0; y < plan.size().get(1); y++) for (int x = 0; x < plan.size().get(0); x++) for (int z = 0; z < plan.size().get(2); z++) {
            var pos = new VillageTemplatePlan.Position(x, y, z);
            blocks.put(pos, new VillageTemplatePlan.SourceBlock(pos, vanilla, false, null));
        }
        for (var element : plan.source().sourceMetadata().getAsJsonArray("blockEntities")) {
            JsonObject row = element.getAsJsonObject(); var pos = row.getAsJsonArray("position");
            var position = new VillageTemplatePlan.Position(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt());
            int index = plan.sourcePalette().indexOf(state(row.getAsJsonObject("block")));
            assertTrue(index >= 0);
            blocks.put(position, new VillageTemplatePlan.SourceBlock(position, index, true, plan.lootTables().get(position)));
        }
        plan.customBlocks().forEach((position, state) -> blocks.put(position,
                new VillageTemplatePlan.SourceBlock(position, plan.sourcePalette().indexOf(state), false, null)));
        return new ArrayList<>(blocks.values());
    }

    @Test void allFivePlansKeepStableLocationsPropertiesAndExactCustomAndLootSidecars() {
        int[] expectedCustom = {7, 10, 13, 1, 10};
        String[] facing = {"north", "north", "east", "east", "north"};
        for (int index = 0; index < sources.templates().size(); index++) {
            var source = sources.templates().get(index); var plan = VillageTemplatePlan.from(source);
            assertEquals("kaleidoscopecookery:village/houses/" + source.biome() + "_kitchen", plan.location());
            assertEquals(expectedCustom[index], plan.customBlocks().size()); assertEquals(2, plan.lootTables().size());
            var stove = plan.customBlocks().values().stream().filter(state -> state.id().endsWith(":stove")).findFirst().orElseThrow();
            assertEquals(Map.of("lit", "false", "facing", facing[index]), stove.properties());
            assertEquals("kaleidoscopecookery:stove", stove.targetId());
            assertDoesNotThrow(() -> plan.validateSource(3465, plan.size(), plan.sourcePalette(), ledger(plan), 0));
            assertEquals(List.of("kaleidoscopecookery:chest/village_chest", "kaleidoscopecookery:chest/village_hide_chest"),
                    plan.lootTables().values().stream().map(VillageTemplatePlan::targetLootTable).sorted().toList());
        }
    }

    @Test void semanticSidecarsCollectionsAndPropertiesAreDefensiveAndImmutable() {
        var source = sources.template("snowy"); var plan = VillageTemplatePlan.from(source);
        source.sourceMetadata().getAsJsonArray("customBlocks").remove(0);
        assertEquals(10, plan.customBlocks().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.size().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.sourcePalette().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.customBlocks().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.lootTables().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.customBlocks().values().iterator().next().properties().clear());
        var mutable = new LinkedHashMap<>(Map.of("age", "7"));
        var state = new VillageTemplatePlan.State("kaleidoscope_cookery:tomato_crop", mutable);
        mutable.put("age", "0"); assertEquals("7", state.properties().get("age"));
    }

    @Test void incompleteOrMisplacedCustomSidecarDoesNotPassTheSourceLedger() {
        var source = sources.template("plains"); var original = VillageTemplatePlan.from(source);
        var metadata = source.sourceMetadata(); metadata.getAsJsonArray("customBlocks").remove(0);
        var incomplete = VillageTemplatePlan.fromMetadata(source, metadata);
        assertThrows(IllegalArgumentException.class, () -> incomplete.validateSource(3465, original.size(), original.sourcePalette(), ledger(original), 0));
        var blocks = ledger(original);
        int custom = 0; while (!original.sourcePalette().get(blocks.get(custom).paletteIndex()).custom()) custom++;
        var before = blocks.get(custom);
        blocks.set(custom, new VillageTemplatePlan.SourceBlock(before.position(), before.paletteIndex(), true, null));
        assertThrows(IllegalArgumentException.class, () -> original.validateSource(3465, original.size(), original.sourcePalette(), blocks, 0));
    }

    @Test void unknownNamespacesCustomIdsPropertiesAndNonStringValuesFailClosed() {
        var source = sources.template("plains");
        for (String unknown : List.of("othermod:stove", "kaleidoscope_cookery:unknown", "bad id")) {
            JsonObject metadata = source.sourceMetadata();
            metadata.getAsJsonArray("palette").get(0).getAsJsonObject().addProperty("Name", unknown);
            assertThrows(IllegalArgumentException.class, () -> VillageTemplatePlan.fromMetadata(source, metadata), unknown);
        }
        for (String property : List.of("unknown", "facing")) {
            JsonObject metadata = source.sourceMetadata();
            JsonObject stove = metadata.getAsJsonArray("customBlocks").get(0).getAsJsonObject();
            stove.getAsJsonObject("Properties").addProperty(property, "up");
            assertThrows(IllegalArgumentException.class, () -> VillageTemplatePlan.fromMetadata(source, metadata));
        }
        JsonObject metadata = source.sourceMetadata();
        metadata.getAsJsonArray("customBlocks").get(0).getAsJsonObject().getAsJsonObject("Properties").addProperty("lit", false);
        assertThrows(IllegalArgumentException.class, () -> VillageTemplatePlan.fromMetadata(source, metadata));
    }

    @Test void unknownOrMissingChestReferencesCannotBecomeVanillaFallbackLoot() {
        var source = sources.template("plains"); var plan = VillageTemplatePlan.from(source);
        var blocks = ledger(plan);
        int chest = 0; while (blocks.get(chest).sourceLootTable() == null) chest++;
        var original = blocks.get(chest);
        blocks.set(chest, new VillageTemplatePlan.SourceBlock(original.position(), original.paletteIndex(), true, null));
        assertThrows(IllegalArgumentException.class, () -> plan.validateSource(3465, plan.size(), plan.sourcePalette(), blocks, 0));
        blocks.set(chest, new VillageTemplatePlan.SourceBlock(original.position(), original.paletteIndex(), true, "minecraft:chests/simple_dungeon"));
        assertThrows(IllegalArgumentException.class, () -> plan.validateSource(3465, plan.size(), plan.sourcePalette(), blocks, 0));
        assertThrows(IllegalArgumentException.class, () -> VillageTemplatePlan.targetLootTable("kaleidoscope_cookery:chest/unknown"));
    }

    @Test void wrongDimensionsVersionEntitiesIndicesDuplicateAndOutOfBoundsPositionsAreRejected() {
        var plan = VillageTemplatePlan.from(sources.template("desert")); var blocks = ledger(plan);
        assertThrows(IllegalArgumentException.class, () -> plan.validateSource(3466, plan.size(), plan.sourcePalette(), blocks, 0));
        assertThrows(IllegalArgumentException.class, () -> plan.validateSource(3465, List.of(1, 1, 1), plan.sourcePalette(), blocks, 0));
        assertThrows(IllegalArgumentException.class, () -> plan.validateSource(3465, plan.size(), plan.sourcePalette(), blocks, 1));
        var invalid = new ArrayList<>(blocks); var first = invalid.get(0);
        invalid.set(0, new VillageTemplatePlan.SourceBlock(first.position(), plan.sourcePalette().size(), false, null));
        assertThrows(IllegalArgumentException.class, () -> plan.validateSource(3465, plan.size(), plan.sourcePalette(), invalid, 0));
        invalid.set(0, invalid.get(1));
        assertThrows(IllegalArgumentException.class, () -> plan.validateSource(3465, plan.size(), plan.sourcePalette(), invalid, 0));
        invalid.set(0, new VillageTemplatePlan.SourceBlock(new VillageTemplatePlan.Position(48, 0, 0), first.paletteIndex(), false, null));
        assertThrows(IllegalArgumentException.class, () -> plan.validateSource(3465, plan.size(), plan.sourcePalette(), invalid, 0));
        assertThrows(IllegalArgumentException.class, () -> plan.validatePositions(List.of(first.position())));
        var positions = blocks.stream().map(VillageTemplatePlan.SourceBlock::position).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        positions.set(0, positions.get(1));
        assertThrows(IllegalArgumentException.class, () -> plan.validatePositions(positions));
    }

    @Test void sourceDigestStableLocationAndSupportedNativeVersionCannotBeInvented() {
        var source = sources.template("plains");
        for (String field : List.of("sourceSha256", "template")) {
            JsonObject metadata = source.sourceMetadata(); metadata.addProperty(field, "changed");
            assertThrows(IllegalArgumentException.class, () -> VillageTemplatePlan.fromMetadata(source, metadata));
        }
        assertThrows(UnsupportedOperationException.class, () -> VillageTemplateMigrator.forMinecraft("1.21.10"));
        assertThrows(UnsupportedOperationException.class, () -> VillageTemplateMigrator.forMinecraft(null));
        var plan = VillageTemplatePlan.from(source);
        assertThrows(IllegalArgumentException.class, () -> new VillageTemplateMigrator.Prepared("wrong", new Object(), plan, 4671));
        assertThrows(IllegalArgumentException.class, () -> new VillageTemplateMigrator.Prepared(plan.location(), new Object(), plan, 3465));
    }

    @Test void onlyKnownSourceBlockEntitiesOnTheirMatchingVanillaBlocksAreAccepted() {
        var chest = new VillageTemplatePlan.State("minecraft:chest", Map.of());
        assertDoesNotThrow(() -> VillageTemplatePlan.validateSourceBlockEntity(chest, "minecraft:chest"));
        for (String id : List.of("othermod:chest", "minecraft:unknown", "minecraft:campfire"))
            assertThrows(IllegalArgumentException.class, () -> VillageTemplatePlan.validateSourceBlockEntity(chest, id));
        assertThrows(IllegalArgumentException.class, () -> VillageTemplatePlan.validateSourceBlockEntity(chest, null));
        assertThrows(IllegalArgumentException.class, () -> VillageTemplatePlan.validateSourceBlockEntity(
                new VillageTemplatePlan.State("kaleidoscope_cookery:stove", Map.of("facing", "north", "lit", "false")), "minecraft:chest"));
    }
}
