package net.kaleidoscope.cookery.nms.v1_21_R7;

import net.kaleidoscope.cookery.block.behavior.HangingStringBehavior;
import net.kaleidoscope.cookery.block.behavior.SourceCropBehavior;
import net.kaleidoscope.cookery.block.behavior.StoveBehavior;
import net.kaleidoscope.cookery.worldgen.VillageStructureSources;
import net.kaleidoscope.cookery.worldgen.VillageTemplateMigrator;
import net.kaleidoscope.cookery.worldgen.VillageTemplatePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.DelegatingBlockState;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Paper/Folia 1.21.11 only. All edits are to a newly created, unpublished native template.
 * The caller must establish stable CE resources; this primitive supplies no reload or publication barrier.
 */
public final class VillageTemplateMigration implements VillageTemplateMigrator {
    private static final long MAX_INFLATED_NBT_BYTES = 8_388_608L;
    private static final int MAX_COMPRESSED_NBT_BYTES = 1_048_576;
    private static final Set<String> SOURCE_ROOT_FIELDS = Set.of("DataVersion", "size", "palette", "blocks", "entities");

    @Override public Prepared prepare(VillageStructureSources.Template source, Object rawManager) {
        require(rawManager instanceof StructureTemplateManager, "Village migration requires a native StructureTemplateManager");
        VillageTemplatePlan plan = VillageTemplatePlan.from(source);
        CompoundTag original = readSource(plan);
        Access access = Access.open((StructureTemplateManager) rawManager);
        CompoundTag sanitized = original.copy();
        ListTag palette = list(sanitized, "palette");
        // Internal parser input only: these markers are restored before this template is returned.
        for (int index = 0; index < plan.sourcePalette().size(); index++) {
            if (!plan.sourcePalette().get(index).custom()) continue;
            var marker = new CompoundTag(); marker.putString("Name", "minecraft:structure_void");
            palette.set(index, marker);
        }
        CompoundTag migrated = access.migrate(sanitized);
        Map<VillageTemplatePlan.Position, MigratedBlock> expected = migratedBlocks(plan, migrated, access.lookup);
        StructureTemplate template = new StructureTemplate();
        template.load(access.lookup, migrated);
        require(template.getSize().getX() == plan.size().get(0) && template.getSize().getY() == plan.size().get(1)
                && template.getSize().getZ() == plan.size().get(2), "Native migration changed village dimensions");
        access.restore(template, plan, expected);
        return new Prepared(plan.location(), template, plan, access.targetVersion);
    }

    /** Exercises native bounded NBT decoding and complete sidecar checks without loading a world or CE registry. */
    public static VillageTemplatePlan inspectSource(VillageStructureSources.Template source) {
        VillageTemplatePlan plan = VillageTemplatePlan.from(source);
        readSource(plan);
        return plan;
    }

    private static CompoundTag readSource(VillageTemplatePlan plan) {
        byte[] bytes = plan.source().compressedNbt();
        require(bytes.length <= MAX_COMPRESSED_NBT_BYTES, "Village compressed NBT exceeds byte limit");
        try {
            require(plan.source().sourceSha256().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))),
                    "Village source NBT digest changed");
            CompoundTag root = decodeBounded(bytes);
            require(root.keySet().equals(SOURCE_ROOT_FIELDS), "Unknown village source root fields");
            var states = new ArrayList<VillageTemplatePlan.State>();
            for (Tag value : list(root, "palette")) states.add(state(compound(value)));
            var blocks = new ArrayList<VillageTemplatePlan.SourceBlock>();
            for (Tag value : list(root, "blocks")) {
                CompoundTag block = compound(value);
                require(Set.of("pos", "state", "nbt").containsAll(block.keySet()), "Unknown village source block fields");
                CompoundTag nbt = block.contains("nbt") ? compound(block.get("nbt")) : null;
                int index = integer(block, "state");
                require(index >= 0 && index < states.size(), "Invalid village source palette index");
                if (nbt != null) VillageTemplatePlan.validateSourceBlockEntity(states.get(index), string(nbt, "id"));
                blocks.add(new VillageTemplatePlan.SourceBlock(position(block), index, nbt != null,
                        nbt != null && nbt.contains("LootTable") ? string(nbt, "LootTable") : null));
            }
            plan.validateSource(integer(root, "DataVersion"), vector(list(root, "size")), states, blocks, list(root, "entities").size());
            return root;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot decode village source NBT", failure);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static CompoundTag decodeBounded(byte[] bytes) throws IOException {
        require(bytes.length <= MAX_COMPRESSED_NBT_BYTES, "Village compressed NBT exceeds byte limit");
        return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(MAX_INFLATED_NBT_BYTES));
    }

    private record MigratedBlock(BlockState state, CompoundTag nbt) {}

    private static Map<VillageTemplatePlan.Position, MigratedBlock> migratedBlocks(VillageTemplatePlan plan,
                                                                                  CompoundTag root, HolderGetter<Block> lookup) {
        require(vector(list(root, "size")).equals(plan.size()) && list(root, "entities").isEmpty(),
                "Native migration changed village dimensions/entities");
        var states = new ArrayList<BlockState>();
        for (Tag value : list(root, "palette")) {
            CompoundTag encoded = compound(value);
            VillageTemplatePlan.State required = state(encoded);
            require(required.id().startsWith("minecraft:"), "Unknown namespace after native village migration");
            BlockState decoded = NbtUtils.readBlockState(lookup, encoded);
            VillageTemplatePlan.State roundTrip = state(NbtUtils.writeBlockState(decoded));
            // Native decoding may add legitimate target-version defaults, but may not drop a supplied property.
            require(roundTrip.id().equals(required.id()) && roundTrip.properties().entrySet().containsAll(required.properties().entrySet()),
                    "Unknown vanilla state/property after native migration: " + required);
            states.add(decoded);
        }
        var blocks = new LinkedHashMap<VillageTemplatePlan.Position, MigratedBlock>();
        var observedLoot = new LinkedHashMap<VillageTemplatePlan.Position, String>();
        for (Tag value : list(root, "blocks")) {
            CompoundTag block = compound(value);
            require(Set.of("pos", "state", "nbt").containsAll(block.keySet()), "Unknown migrated village block fields");
            var position = position(block);
            int index = integer(block, "state");
            require(index >= 0 && index < states.size(), "Invalid migrated village palette index");
            CompoundTag nbt = block.contains("nbt") ? compound(block.get("nbt")).copy() : null;
            if (nbt != null && nbt.contains("LootTable")) observedLoot.put(position, string(nbt, "LootTable"));
            require(blocks.put(position, new MigratedBlock(states.get(index), nbt)) == null, "Duplicate migrated village position");
        }
        plan.validatePositions(List.copyOf(blocks.keySet()));
        require(observedLoot.equals(plan.lootTables()), "Native migration changed village loot sidecar");
        for (var position : plan.customBlocks().keySet()) {
            MigratedBlock block = blocks.get(position);
            require(block != null && block.state == Blocks.STRUCTURE_VOID.defaultBlockState() && block.nbt == null,
                    "Incomplete custom state sidecar after native migration");
        }
        return blocks;
    }

    private record Resolved(VillageTemplatePlan.State source, ImmutableBlockState semantic, BlockState nativeState) {
        void checkCurrent() {
            require(nativeState instanceof DelegatingBlockState && ((DelegatingBlockState) nativeState).blockState() == semantic
                    && CraftEngineBlocks.byId(Key.of(source.targetId())) == semantic.owner().value()
                    && properties(semantic).equals(source.properties()), "CE mapping changed during village template preparation");
        }
    }

    private static Resolved resolve(VillageTemplatePlan.State source) {
        var definition = CraftEngineBlocks.byId(Key.of(source.targetId()));
        require(definition != null, "Missing village CE block: " + source.targetId());
        ImmutableBlockState semantic = definition.defaultState();
        require(semantic != null && !semantic.isEmpty(), "Unready village CE block");
        for (var entry : source.properties().entrySet()) {
            Property<?> property = definition.getProperty(entry.getKey());
            require(property != null, "Missing village CE property: " + entry.getKey());
            Object value = property.valueByName(entry.getValue());
            require(value != null, "Unknown village CE property value: " + entry);
            semantic = ImmutableBlockState.with(semantic, property, value);
        }
        require(properties(semantic).equals(source.properties()) && semantic.owner().value() == definition,
                "Village CE properties differ from source sidecar");
        boolean behaviorReady = switch (source.id()) {
            case "kaleidoscope_cookery:stove" -> semantic.hasBlockEntity() && semantic.behavior().getFirst(StoveBehavior.class) != null;
            case "kaleidoscope_cookery:tomato_crop" -> semantic.behavior().getFirst(SourceCropBehavior.class) != null;
            case "kaleidoscope_cookery:chili_ristra" -> semantic.behavior().getFirst(HangingStringBehavior.class) != null;
            default -> false;
        };
        require(behaviorReady && semantic.customBlockState() != null && semantic.customBlockState().minecraftState() instanceof BlockState,
                "Village CE behavior/native state is unavailable");
        var result = new Resolved(source, semantic, (BlockState) semantic.customBlockState().minecraftState());
        result.checkCurrent();
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, String> properties(ImmutableBlockState state) {
        var result = new LinkedHashMap<String, String>();
        state.propertyEntries().forEach((property, value) -> result.put(property.name(), ((Property) property).valueName((Comparable) value)));
        return result;
    }

    private static final class Access {
        final HolderGetter<Block> lookup;
        final Field palettes;
        final Method blocks;
        final Constructor<?> paletteConstructor;
        final Method addToLists;
        final Method buildInfoList;
        final Object structureType;
        final Method converter;
        final int targetVersion;

        private Access(HolderGetter<Block> lookup, Field palettes, Method blocks, Constructor<?> paletteConstructor,
                       Method addToLists, Method buildInfoList, Object structureType, Method converter, int targetVersion) {
            this.lookup = lookup; this.palettes = palettes; this.blocks = blocks; this.paletteConstructor = paletteConstructor;
            this.addToLists = addToLists; this.buildInfoList = buildInfoList; this.structureType = structureType;
            this.converter = converter; this.targetVersion = targetVersion;
        }

        @SuppressWarnings("unchecked")
        static Access open(StructureTemplateManager manager) {
            try {
                Field lookup = StructureTemplateManager.class.getDeclaredField("blockLookup");
                require(lookup.getType() == HolderGetter.class, "Unexpected native village block lookup");
                lookup.setAccessible(true);
                Field palettes = StructureTemplate.class.getDeclaredField("palettes");
                require(palettes.getType() == List.class, "Unexpected native village palette field");
                palettes.setAccessible(true);
                Class<?> palette = Class.forName(StructureTemplate.class.getName() + "$Palette");
                Method blocks = palette.getDeclaredMethod("blocks"); blocks.setAccessible(true);
                Constructor<?> constructor = palette.getDeclaredConstructor(List.class); constructor.setAccessible(true);
                Method add = StructureTemplate.class.getDeclaredMethod("addToLists", StructureBlockInfo.class, List.class, List.class, List.class);
                Method build = StructureTemplate.class.getDeclaredMethod("buildInfoList", List.class, List.class, List.class);
                add.setAccessible(true); build.setAccessible(true);
                ClassLoader loader = StructureTemplate.class.getClassLoader();
                Class<?> type = Class.forName("ca.spottedleaf.dataconverter.minecraft.datatypes.MCDataType", true, loader);
                Object structure = Class.forName("ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry", true, loader)
                        .getField("STRUCTURE").get(null);
                Method convert = Class.forName("ca.spottedleaf.dataconverter.minecraft.MCDataConverter", true, loader)
                        .getMethod("convertTag", type, CompoundTag.class, int.class, int.class);
                int current = (Integer) Class.forName("ca.spottedleaf.dataconverter.minecraft.util.Version", true, loader)
                        .getMethod("getCurrentVersion").invoke(null);
                require(current == 4671, "Village migration supports native data version 4671 only");
                return new Access((HolderGetter<Block>) lookup.get(manager), palettes, blocks, constructor, add, build, structure, convert, current);
            } catch (ReflectiveOperationException failure) {
                throw reflectionFailure("Native 1.21.11 village migration ABI is unavailable", failure);
            }
        }

        CompoundTag migrate(CompoundTag sanitized) {
            try {
                Object result = converter.invoke(null, structureType, sanitized, integer(sanitized, "DataVersion"), targetVersion);
                require(result instanceof CompoundTag, "Native village converter returned no structure");
                return (CompoundTag) result;
            } catch (ReflectiveOperationException failure) {
                throw reflectionFailure("Native village structure migration failed", failure);
            }
        }

        @SuppressWarnings("unchecked")
        void restore(StructureTemplate template, VillageTemplatePlan plan, Map<VillageTemplatePlan.Position, MigratedBlock> expected) {
            try {
                List<Object> templatePalettes = (List<Object>) palettes.get(template);
                require(templatePalettes.size() == 1, "Unexpected migrated native palette count");
                List<StructureBlockInfo> original = (List<StructureBlockInfo>) blocks.invoke(templatePalettes.get(0));
                require(original.size() == plan.volume(), "Native template load changed volume");
                var remaining = new LinkedHashMap<>(expected);
                var resolved = new LinkedHashMap<VillageTemplatePlan.State, Resolved>();
                var full = new ArrayList<StructureBlockInfo>();
                var withNbt = new ArrayList<StructureBlockInfo>();
                var other = new ArrayList<StructureBlockInfo>();
                for (StructureBlockInfo info : original) {
                    var position = new VillageTemplatePlan.Position(info.pos().getX(), info.pos().getY(), info.pos().getZ());
                    MigratedBlock migrated = remaining.remove(position);
                    require(migrated != null && info.state() == migrated.state && Objects.equals(info.nbt(), migrated.nbt),
                            "Native template load changed migrated block/NBT contents");
                    BlockState state = info.state();
                    VillageTemplatePlan.State custom = plan.customBlocks().get(position);
                    if (custom != null) state = resolved.computeIfAbsent(custom, VillageTemplateMigration::resolve).nativeState;
                    CompoundTag nbt = info.nbt();
                    String loot = plan.lootTables().get(position);
                    if (loot != null) {
                        require(nbt != null && loot.equals(string(nbt, "LootTable")), "Missing migrated village chest loot reference");
                        nbt = nbt.copy(); nbt.putString("LootTable", VillageTemplatePlan.targetLootTable(loot));
                    }
                    var replacement = new StructureBlockInfo(new BlockPos(position.x(), position.y(), position.z()), state, nbt);
                    addToLists.invoke(null, replacement, full, withNbt, other);
                }
                require(remaining.isEmpty() && resolved.size() == new java.util.HashSet<>(plan.customBlocks().values()).size(),
                        "Incomplete native village sidecar restoration");
                Object ordered = buildInfoList.invoke(null, full, withNbt, other);
                Object freshPalette = paletteConstructor.newInstance(ordered);
                resolved.values().forEach(Resolved::checkCurrent);
                // No cache registration or query has occurred; replace only this local template's palettes.
                templatePalettes.clear(); templatePalettes.add(freshPalette);
            } catch (ReflectiveOperationException failure) {
                throw reflectionFailure("Cannot restore unpublished native village palette", failure);
            }
        }
    }

    private static VillageTemplatePlan.State state(CompoundTag tag) {
        require(Set.of("Name", "Properties").containsAll(tag.keySet()), "Unknown native village palette fields");
        var properties = new LinkedHashMap<String, String>();
        if (tag.contains("Properties")) {
            CompoundTag encoded = compound(tag.get("Properties"));
            encoded.keySet().forEach(key -> properties.put(key, string(encoded, key)));
        }
        return new VillageTemplatePlan.State(string(tag, "Name"), properties);
    }
    private static VillageTemplatePlan.Position position(CompoundTag tag) {
        List<Integer> values = vector(list(tag, "pos"));
        return new VillageTemplatePlan.Position(values.get(0), values.get(1), values.get(2));
    }
    private static List<Integer> vector(ListTag tag) {
        require(tag.size() == 3, "Invalid native village vector");
        var values = new ArrayList<Integer>();
        for (int index = 0; index < 3; index++) {
            require(tag.get(index) instanceof IntTag, "Invalid native village vector type");
            values.add(tag.getInt(index).orElseThrow());
        }
        return values;
    }
    private static CompoundTag compound(Tag tag) {
        require(tag instanceof CompoundTag, "Expected native village compound");
        return (CompoundTag) tag;
    }
    private static ListTag list(CompoundTag tag, String key) {
        require(tag.get(key) instanceof ListTag, "Expected native village list: " + key);
        return (ListTag) tag.get(key);
    }
    private static int integer(CompoundTag tag, String key) {
        require(tag.get(key) instanceof IntTag, "Expected native village integer: " + key);
        return tag.getInt(key).orElseThrow();
    }
    private static String string(CompoundTag tag, String key) {
        require(tag.get(key) instanceof StringTag, "Expected native village string: " + key);
        return tag.getString(key).orElseThrow();
    }
    private static IllegalStateException reflectionFailure(String message, ReflectiveOperationException failure) {
        return new IllegalStateException(message, failure instanceof InvocationTargetException ? failure.getCause() : failure);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
