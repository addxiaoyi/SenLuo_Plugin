package net.kaleidoscope.cookery.nms.v1_21_R7;

import com.mojang.datafixers.util.Either;
import net.kaleidoscope.cookery.worldgen.VillagePoolPlan;
import net.kaleidoscope.cookery.worldgen.VillagePoolPreparer;
import net.kaleidoscope.cookery.worldgen.VillagePublicationTransaction;
import net.kaleidoscope.cookery.worldgen.VillageStructureCodec;
import net.kaleidoscope.cookery.worldgen.VillageTemplateMigrator;
import net.kaleidoscope.cookery.worldgen.VillageTemplatePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pools.LegacySinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.DelegatingBlockState;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Native codecs and delegated transforms; no world, registry, pool, template or query-cache writes. */
public final class VillageStructureCodecNative implements VillageStructureCodec {
    private static final int MAX_ENCODED_BYTES = 65_536;
    private static final int MAX_NBT_DEPTH = 32;
    private static final BlockPos POSITION = new BlockPos(32, 64, 48);

    @Override public Report inspect(Object rawRegistry, Object rawManager, List<VillageTemplateMigrator.Prepared> templates,
                                    VillagePoolPreparer.Prepared prepared, VillagePublicationTransaction.ExclusionScope scope) {
        java.util.Objects.requireNonNull(scope, "A real already-held publication scope is required").check();
        require(rawRegistry instanceof RegistryAccess && rawManager instanceof StructureTemplateManager,
                "Native village codec roots are required");
        RegistryAccess registry = (RegistryAccess) rawRegistry; StructureTemplateManager manager = (StructureTemplateManager) rawManager;
        MinecraftServer server = MinecraftServer.getServer();
        require(server != null && server.registryAccess() == registry && server.getStructureManager() == manager,
                "Native codec roots are not the active server roots");
        require(templates.size() == 5 && prepared.plan().pools().keySet().equals(Set.copyOf(VillagePoolPlan.POOL_KEYS)),
                "Native codec inspection requires all five candidates");
        require(templates.stream().allMatch(template -> template.sidecar().customBlocks().size() <= 1_024),
                "Native custom transform count exceeds bounded inspection");
        Access access = new Access(); Map<?, ?> repository = (Map<?, ?>) access.get(access.repository, manager);
        require(repository.getClass() == java.util.concurrent.ConcurrentHashMap.class && repository.size() <= 65_536,
                "Unexpected or oversized native template cache");
        Map<?, ?> beforeCache = new LinkedHashMap<>(repository);
        var processorKey = ResourceKey.create(Registries.PROCESSOR_LIST, Identifier.parse(VillagePoolPlan.PROCESSOR));
        var processors = registry.lookupOrThrow(Registries.PROCESSOR_LIST);
        Holder.Reference<StructureProcessorList> named = processors.getOrThrow(processorKey);
        require(named == prepared.namedProcessorHolder() && named.value() == prepared.expectedProcessorValue()
                && processors.getValue(processorKey) == named.value(), "Published named processor binding changed");
        RegistryOps<Tag> ops = registry.createSerializationContext(NbtOps.INSTANCE);
        StructurePieceSerializationContext context = new StructurePieceSerializationContext(server.getResourceManager(), registry, manager);
        var byLocation = new LinkedHashMap<String, VillageTemplateMigrator.Prepared>();
        for (var template : List.copyOf(templates)) {
            require(template.nativeTemplate() instanceof StructureTemplate && template.targetDataVersion() == 4671
                    && template.location().equals(VillagePoolPlan.location(template.sidecar().source().biome()))
                    && byLocation.put(template.location(), template) == null, "Unexpected native village template candidate");
            var size = ((StructureTemplate) template.nativeTemplate()).getSize();
            require(List.of(size.getX(), size.getY(), size.getZ()).equals(template.sidecar().size()),
                    "Native codec template dimensions differ from its authenticated source sidecar");
            Object cached = repository.get(Identifier.parse(template.location()));
            require(cached instanceof Optional<?> && ((Optional<?>) cached).orElse(null) == template.nativeTemplate(),
                    "Codec inspection requires each exact candidate already published");
        }
        var pieces = new ArrayList<Piece>(); var transformed = new ArrayList<Transform>();
        for (int index = 0; index < VillagePoolPlan.BIOMES.size(); index++) {
            scope.check(); String biome = VillagePoolPlan.BIOMES.get(index), poolKey = VillagePoolPlan.POOL_KEYS.get(index);
            var candidate = byLocation.get(VillagePoolPlan.location(biome)); require(candidate != null, "Missing native codec template");
            var poolRegistry = registry.lookupOrThrow(Registries.TEMPLATE_POOL);
            StructureTemplatePool pool = poolRegistry.getValue(ResourceKey.create(Registries.TEMPLATE_POOL, Identifier.parse(poolKey)));
            require(pool == prepared.expectedPoolValues().get(poolKey) && pool.getFallback() == prepared.plan().pools().get(poolKey).fallback(),
                    "Published native pool/fallback changed");
            StructurePoolElement element = access.element(pool, candidate.location(), named);
            for (Rotation rotation : Rotation.values()) {
                scope.check();
                Tag encoded = StructurePoolElement.CODEC.encodeStart(ops, element).getOrThrow();
                require(encoded instanceof CompoundTag, "Native element codec did not emit a compound");
                validateElement((CompoundTag) encoded, candidate.location());
                byte[] elementBytes = write((CompoundTag) encoded);
                StructurePoolElement decoded = StructurePoolElement.CODEC.parse(ops, read(elementBytes)).getOrThrow();
                access.named(decoded, candidate.location(), named);
                require(encoded.equals(StructurePoolElement.CODEC.encodeStart(ops, decoded).getOrThrow()), "Native element codec did not round-trip exactly");
                BoundingBox box = decoded.getBoundingBox(manager, POSITION, rotation);
                var piece = new PoolElementStructurePiece(manager, decoded, POSITION, 0, rotation, box, LiquidSettings.APPLY_WATERLOGGING);
                CompoundTag saved = piece.createTag(context); validatePiece(saved, candidate.location());
                byte[] pieceBytes = write(saved); CompoundTag loaded = read(pieceBytes);
                var restored = new PoolElementStructurePiece(context, loaded);
                access.named(restored.getElement(), candidate.location(), named);
                require(restored.getPosition().equals(POSITION) && restored.getGroundLevelDelta() == 0 && restored.getRotation() == rotation
                        && restored.getJunctions().isEmpty() && bounds(restored.getBoundingBox()).equals(bounds(box)),
                        "Saved native piece lost position/rotation/ground/bounds/junction semantics");
                require(saved.equals(restored.createTag(context)), "Saved native piece codec did not round-trip exactly");
                pieces.add(new Piece(biome, candidate.location(), VillagePoolPlan.PROCESSOR, candidate.sidecar().source().sourceCommit(),
                        candidate.sidecar().source().sourceSha256(), rotation.getSerializedName(),
                        elementBytes.length, sha256(elementBytes), pieceBytes.length, sha256(pieceBytes), bounds(box)));
            }
            access.transforms(candidate, biome, transformed, scope);
        }
        scope.check();
        require(server.registryAccess() == registry && server.getStructureManager() == manager
                && access.get(access.repository, manager) == repository && repository.size() == beforeCache.size(), "Codec inspection changed native roots/cache size");
        beforeCache.forEach((key, value) -> require(repository.get(key) == value, "Codec inspection changed a native template cache value"));
        require(pieces.size() == 20, "Incomplete native codec rotation coverage");
        int expectedTransforms = templates.stream().mapToInt(template -> template.sidecar().customBlocks().size()).sum() * 12;
        require(transformed.size() == expectedTransforms, "Incomplete native custom-state mirror/rotation coverage");
        return new Report("REAL_NATIVE_NAMED_ELEMENT_AND_SAVED_PIECE_CODECS; NO_WORLD_PLACEMENT_OR_RESTART", 5, pieces.size(), pieces.size(),
                transformed.size(), pieces, transformed);
    }

    private static void validateElement(CompoundTag tag, String location) {
        require(tag.keySet().equals(Set.of("element_type", "location", "processors", "projection")),
                "Native named element contains unexpected or inline fields");
        require(string(tag, "element_type").equals("minecraft:legacy_single_pool_element") && string(tag, "location").equals(location)
                && string(tag, "processors").equals(VillagePoolPlan.PROCESSOR) && string(tag, "projection").equals("rigid"),
                "Native element did not encode stable named legacy/processor semantics");
    }
    private static void validatePiece(CompoundTag tag, String location) {
        require(Set.of("id", "BB", "O", "GD", "PosX", "PosY", "PosZ", "ground_level_delta", "pool_element", "rotation", "junctions", "liquid_settings")
                .containsAll(tag.keySet()) && tag.get("pool_element") instanceof CompoundTag, "Unexpected saved native piece fields");
        require(string(tag, "id").equals("minecraft:jigsaw"), "Saved native piece lost its registered jigsaw type");
        validateElement((CompoundTag) tag.get("pool_element"), location);
    }
    private static String string(CompoundTag tag, String key) {
        require(tag.get(key) instanceof StringTag, "Expected stable native string field: " + key); return tag.getString(key).orElseThrow();
    }
    private static byte[] write(CompoundTag tag) {
        var output = new LimitedOutput();
        try (var data = new DataOutputStream(output)) { NbtIo.write(tag, data); return output.bytes.toByteArray(); }
        catch (IOException failure) { throw new IllegalStateException("Cannot encode bounded native piece NBT", failure); }
    }
    private static CompoundTag read(byte[] bytes) {
        require(bytes.length > 0 && bytes.length <= MAX_ENCODED_BYTES, "Native piece bytes exceed limit");
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            CompoundTag value = NbtIo.read(input, new NbtAccounter(MAX_ENCODED_BYTES, MAX_NBT_DEPTH));
            require(input.available() == 0, "Native piece NBT has trailing bytes"); return value;
        } catch (IOException failure) { throw new IllegalStateException("Cannot decode bounded native piece NBT", failure); }
    }
    private static final class LimitedOutput extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int size;
        @Override public void write(int value) throws IOException { reserve(1); bytes.write(value); }
        @Override public void write(byte[] value, int offset, int length) throws IOException { reserve(length); bytes.write(value, offset, length); }
        private void reserve(int count) throws IOException {
            if (count < 0 || size > MAX_ENCODED_BYTES - count) throw new IOException("Native encoded NBT exceeds byte limit"); size += count;
        }
    }
    private static List<Integer> bounds(BoundingBox box) { return List.of(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()); }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class Access {
        final Field repository = field(StructureTemplateManager.class, "structureRepository", Map.class);
        final Field location = field(SinglePoolElement.class, "template", Either.class);
        final Field processors = field(SinglePoolElement.class, "processors", Holder.class);
        final Field liquid = field(SinglePoolElement.class, "overrideLiquidSettings", Optional.class);
        final Field palettes = field(StructureTemplate.class, "palettes", List.class);
        final Method blocks;
        Access() {
            try { blocks = Class.forName(StructureTemplate.class.getName() + "$Palette").getDeclaredMethod("blocks"); blocks.setAccessible(true); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native codec palette ABI unavailable", failure); }
        }
        StructurePoolElement element(StructureTemplatePool pool, String name, Object holder) {
            require(pool.getTemplates().size() <= VillagePoolPlan.MAX_POOL_ENTRIES, "Native pool exceeds codec inspection bound");
            StructurePoolElement found = null;
            for (var entry : pool.getTemplates()) if (entry.getFirst().getClass() == LegacySinglePoolElement.class) {
                Either<?, ?> named = (Either<?, ?>) get(location, entry.getFirst());
                if (!named.left().map(Object::toString).orElse("").equals(name)) continue;
                require(found == null && entry.getSecond() == 4, "Duplicate or changed native kitchen weight"); found = entry.getFirst();
            }
            require(found != null, "Published native kitchen element is unavailable"); named(found, name, holder); return found;
        }
        void named(StructurePoolElement value, String name, Object holder) {
            require(value.getClass() == LegacySinglePoolElement.class && value.getProjection() == StructureTemplatePool.Projection.RIGID
                    && ((Either<?, ?>) get(location, value)).left().map(Object::toString).orElse("").equals(name)
                    && get(processors, value) == holder && ((Optional<?>) get(liquid, value)).isEmpty(), "Native decoded element lost named holder/template semantics");
        }
        void transforms(VillageTemplateMigrator.Prepared prepared, String biome, List<Transform> rows, VillagePublicationTransaction.ExclusionScope scope) {
            StructureTemplate template = (StructureTemplate) prepared.nativeTemplate(); List<?> values = (List<?>) get(palettes, template);
            require(values.size() == 1, "Unexpected native palette count during transform inspection");
            final List<?> blocks;
            try { blocks = (List<?>) this.blocks.invoke(values.getFirst()); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot inspect actual native custom transform states", failure); }
            require(blocks.size() == prepared.sidecar().volume(), "Native transform palette volume changed");
            var remaining = new HashSet<>(prepared.sidecar().customBlocks().keySet());
            for (Object value : blocks) {
                var block = (StructureBlockInfo) value;
                var position = new VillageTemplatePlan.Position(block.pos().getX(), block.pos().getY(), block.pos().getZ());
                var expected = prepared.sidecar().customBlocks().get(position); if (expected == null) continue;
                Set<String> supported = switch (expected.id()) {
                    case "kaleidoscope_cookery:stove" -> Set.of("facing", "lit");
                    case "kaleidoscope_cookery:tomato_crop" -> Set.of("age");
                    case "kaleidoscope_cookery:chili_ristra" -> Set.of("sheared", "is_head");
                    default -> throw new IllegalStateException("Unsupported native village transform semantics: " + expected.id());
                };
                require(expected.properties().keySet().equals(supported), "Native source has additional unsupported directional transform properties");
                require(remaining.remove(position), "Duplicate native custom transform position");
                current(block.state(), expected.targetId(), expected.properties());
                ImmutableBlockState originalSemantic = ((DelegatingBlockState) block.state()).blockState();
                for (Mirror mirror : Mirror.values()) for (Rotation rotation : Rotation.values()) {
                    scope.check(); BlockState result = block.state().mirror(mirror).rotate(rotation);
                    var properties = new LinkedHashMap<>(expected.properties());
                    if (properties.containsKey("facing")) {
                        net.minecraft.core.Direction direction = net.minecraft.core.Direction.byName(properties.get("facing"));
                        require(direction != null, "Unknown source direction in native transform check");
                        properties.put("facing", rotation.rotate(mirror.mirror(direction)).getSerializedName());
                    }
                    current(result, expected.targetId(), properties);
                    require(((DelegatingBlockState) block.state()).blockState() == originalSemantic,
                            "Native transform changed its original CE generation");
                    current(block.state(), expected.targetId(), expected.properties());
                    rows.add(new Transform(biome, List.of(position.x(), position.y(), position.z()), expected.targetId(), mirror.getSerializedName(),
                            rotation.getSerializedName(), expected.properties(), properties));
                }
            }
            require(remaining.isEmpty(), "Missing native custom transform positions");
        }
        static Field field(Class<?> owner, String name, Class<?> type) {
            try {
                Field value = owner.getDeclaredField(name); require(value.getType() == type && !java.lang.reflect.Modifier.isStatic(value.getModifiers()), "Unexpected native codec field ABI");
                value.setAccessible(true); return value;
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native codec field unavailable: " + name, failure); }
        }
        Object get(Field field, Object owner) {
            try { return field.get(owner); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot inspect native codec field: " + field.getName(), failure); }
        }
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static void current(BlockState raw, String id, Map<String, String> expected) {
        require(raw instanceof DelegatingBlockState, "Native transform lost its CE delegate");
        ImmutableBlockState state = ((DelegatingBlockState) raw).blockState(); var properties = new LinkedHashMap<String, String>();
        require(state != null && !state.isEmpty() && state.customBlockState() != null && state.customBlockState().minecraftState() == raw
                && CraftEngineBlocks.byId(Key.of(id)) == state.owner().value(), "Native transform lost its current CE semantic mapping");
        state.propertyEntries().forEach((property, value) -> properties.put(property.name(), ((Property) property).valueName((Comparable) value)));
        require(properties.equals(expected), "Native transform changed unsupported properties or failed to transform facing");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
