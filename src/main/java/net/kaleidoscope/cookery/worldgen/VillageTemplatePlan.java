package net.kaleidoscope.cookery.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable semantic sidecar for one pinned, still unpublished source template. */
public final class VillageTemplatePlan {
    private static final String SOURCE_NAMESPACE = "kaleidoscope_cookery:";
    private static final String TARGET_NAMESPACE = "kaleidoscopecookery:";
    public record Position(int x, int y, int z) {}
    public record State(String id, Map<String, String> properties) {
        public State {
            VillageLootSpec.require(id != null && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") && !id.contains(".."),
                    "Invalid village source state ID");
            properties = Map.copyOf(properties);
        }
        public boolean custom() { return id.startsWith(SOURCE_NAMESPACE); }
        public String targetId() {
            validateSourceState(this);
            VillageLootSpec.require(custom(), "Only source custom states have a CE mapping");
            return TARGET_NAMESPACE + id.substring(SOURCE_NAMESPACE.length());
        }
    }
    public record SourceBlock(Position position, int paletteIndex, boolean hasBlockEntityNbt, String sourceLootTable) {}

    private final VillageStructureSources.Template source;
    private final List<Integer> size;
    private final List<State> sourcePalette;
    private final Map<Position, State> customBlocks;
    private final Map<Position, String> lootTables;
    private final String location;

    private VillageTemplatePlan(VillageStructureSources.Template source, List<Integer> size,
                                List<State> palette, Map<Position, State> customBlocks, Map<Position, String> lootTables, String location) {
        this.source = source; this.size = List.copyOf(size); this.sourcePalette = List.copyOf(palette);
        this.customBlocks = Map.copyOf(customBlocks); this.lootTables = Map.copyOf(lootTables); this.location = location;
    }

    public static VillageTemplatePlan from(VillageStructureSources.Template source) {
        return fromMetadata(source, source.sourceMetadata());
    }

    static VillageTemplatePlan fromMetadata(VillageStructureSources.Template source, JsonObject metadata) {
        VillageLootSpec.require(source.sourceSha256().equals(string(metadata.get("sourceSha256"))), "Changed village source digest");
        VillageLootSpec.require(integer(metadata.get("dataVersion")) == 3465, "Unknown village source data version");
        String location = TARGET_NAMESPACE + "village/houses/" + source.biome() + "_kitchen";
        VillageLootSpec.require(location.equals(string(metadata.get("template"))), "Unstable village template location");
        var size = new ArrayList<Integer>();
        metadata.getAsJsonArray("size").forEach(value -> size.add(integer(value)));
        VillageLootSpec.require(size.size() == 3 && size.stream().allMatch(value -> value >= 1 && value <= 48), "Invalid village dimensions");
        int volume = size.get(0) * size.get(1) * size.get(2);
        VillageLootSpec.require(integer(metadata.get("blocks")) == volume, "Incomplete village source volume");
        var palette = new ArrayList<State>();
        metadata.getAsJsonArray("palette").forEach(value -> palette.add(state(value.getAsJsonObject(), false)));
        VillageLootSpec.require(!palette.isEmpty() && palette.size() <= volume, "Invalid village source palette");
        var custom = new LinkedHashMap<Position, State>();
        for (var value : metadata.getAsJsonArray("customBlocks")) {
            JsonObject row = value.getAsJsonObject();
            var position = position(row.getAsJsonArray("position"));
            inside(position, size);
            State state = state(row, true);
            VillageLootSpec.require(state.custom() && palette.contains(state), "Invalid village custom sidecar state");
            VillageLootSpec.require(custom.put(position, state) == null, "Duplicate village custom sidecar position");
        }
        VillageLootSpec.require(!custom.isEmpty() && custom.size() <= volume, "Empty or oversized village custom sidecar");
        var loot = new LinkedHashMap<Position, String>();
        for (var value : metadata.getAsJsonArray("blockEntities")) {
            JsonObject row = value.getAsJsonObject();
            JsonObject nbt = row.getAsJsonObject("nbt");
            if (!nbt.has("LootTable")) continue;
            Position position = position(row.getAsJsonArray("position"));
            inside(position, size);
            String table = string(nbt.get("LootTable"));
            targetLootTable(table);
            VillageLootSpec.require("minecraft:chest".equals(string(row.getAsJsonObject("block").get("Name")))
                    && loot.put(position, table) == null, "Invalid village source loot sidecar");
        }
        VillageLootSpec.require(loot.size() == 2, "Incomplete village source loot sidecar");
        return new VillageTemplatePlan(source, size, palette, custom, loot, location);
    }

    public VillageStructureSources.Template source() { return source; }
    public List<Integer> size() { return size; }
    public int volume() { return size.get(0) * size.get(1) * size.get(2); }
    public List<State> sourcePalette() { return sourcePalette; }
    public Map<Position, State> customBlocks() { return customBlocks; }
    public Map<Position, String> lootTables() { return lootTables; }
    public String location() { return location; }

    /** Compare every original palette state and every custom position before native migration. */
    public void validateSource(int dataVersion, List<Integer> actualSize, List<State> actualPalette,
                               List<SourceBlock> blocks, int entityCount) {
        VillageLootSpec.require(dataVersion == 3465 && actualSize.equals(size), "Village source version/dimensions changed");
        actualPalette.forEach(VillageTemplatePlan::validateSourceState);
        VillageLootSpec.require(actualPalette.equals(sourcePalette), "Village source palette differs from sidecar");
        VillageLootSpec.require(entityCount == 0 && blocks.size() == volume(), "Village source volume/entities changed");
        var positions = new HashSet<Position>();
        var observedCustom = new LinkedHashMap<Position, State>();
        var observedLoot = new LinkedHashMap<Position, String>();
        for (SourceBlock block : blocks) {
            inside(block.position(), size);
            VillageLootSpec.require(positions.add(block.position()), "Duplicate village source position");
            VillageLootSpec.require(block.paletteIndex() >= 0 && block.paletteIndex() < actualPalette.size(), "Invalid village palette index");
            State state = actualPalette.get(block.paletteIndex());
            if (block.sourceLootTable() != null) {
                VillageLootSpec.require(block.hasBlockEntityNbt() && state.id().equals("minecraft:chest"), "Loot table outside source chest");
                targetLootTable(block.sourceLootTable());
                observedLoot.put(block.position(), block.sourceLootTable());
            }
            if (state.custom()) {
                VillageLootSpec.require(!block.hasBlockEntityNbt(), "Unsupported source custom block-entity NBT");
                observedCustom.put(block.position(), state);
            }
        }
        VillageLootSpec.require(observedCustom.equals(customBlocks), "Incomplete or changed village custom sidecar");
        VillageLootSpec.require(observedLoot.equals(lootTables), "Incomplete or changed village loot sidecar");
    }

    public void validatePositions(List<Position> positions) {
        VillageLootSpec.require(positions.size() == volume(), "Migrated village volume changed");
        var unique = new HashSet<Position>();
        for (Position position : positions) {
            inside(position, size);
            VillageLootSpec.require(unique.add(position), "Duplicate migrated village position");
        }
    }

    public static String targetLootTable(String source) {
        VillageLootSpec.require(source != null && Set.of(SOURCE_NAMESPACE + "chest/village_chest", SOURCE_NAMESPACE + "chest/village_hide_chest")
                .contains(source), "Unknown village source loot table");
        return TARGET_NAMESPACE + source.substring(SOURCE_NAMESPACE.length());
    }

    public static void validateSourceBlockEntity(State state, String id) {
        VillageLootSpec.require(id != null && Set.of("minecraft:chest", "minecraft:jigsaw", "minecraft:campfire").contains(id)
                && state != null && state.id().equals(id), "Unknown or mismatched village source block-entity ID");
    }

    private static State state(JsonObject row, boolean positioned) {
        var allowed = positioned ? Set.of("Name", "Properties", "position") : Set.of("Name", "Properties");
        VillageLootSpec.require(allowed.containsAll(row.keySet()) && row.has("Name"), "Unknown village palette fields");
        var properties = new LinkedHashMap<String, String>();
        if (row.has("Properties")) row.getAsJsonObject("Properties").entrySet().forEach(entry ->
                properties.put(entry.getKey(), string(entry.getValue())));
        State state = new State(string(row.get("Name")), properties);
        validateSourceState(state);
        return state;
    }

    private static void validateSourceState(State state) {
        VillageLootSpec.require(state.id().startsWith("minecraft:") || state.custom(), "Unknown village source namespace");
        if (!state.custom()) return;
        Map<String, Set<String>> allowed = switch (state.id().substring(SOURCE_NAMESPACE.length())) {
            case "stove" -> Map.of("facing", Set.of("north", "south", "east", "west"), "lit", Set.of("false", "true"));
            case "tomato_crop" -> Map.of("age", Set.of("7"));
            case "chili_ristra" -> Map.of("sheared", Set.of("false", "true"), "is_head", Set.of("false", "true"));
            default -> throw new IllegalArgumentException("Unknown village source custom state: " + state.id());
        };
        VillageLootSpec.require(state.properties().keySet().equals(allowed.keySet()) && state.properties().entrySet().stream()
                .allMatch(entry -> allowed.get(entry.getKey()).contains(entry.getValue())), "Unknown village source custom properties");
    }

    private static Position position(JsonArray values) {
        VillageLootSpec.require(values.size() == 3, "Invalid village sidecar position");
        return new Position(integer(values.get(0)), integer(values.get(1)), integer(values.get(2)));
    }
    private static void inside(Position position, List<Integer> size) {
        VillageLootSpec.require(position != null && position.x() >= 0 && position.x() < size.get(0)
                && position.y() >= 0 && position.y() < size.get(1) && position.z() >= 0 && position.z() < size.get(2),
                "Village position outside source bounds");
    }
    private static String string(JsonElement value) {
        VillageLootSpec.require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Expected village string");
        return value.getAsString();
    }
    private static int integer(JsonElement value) {
        VillageLootSpec.require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Expected village integer");
        int result = value.getAsInt();
        VillageLootSpec.require(Integer.toString(result).equals(value.getAsString()), "Expected canonical village integer");
        return result;
    }
}
