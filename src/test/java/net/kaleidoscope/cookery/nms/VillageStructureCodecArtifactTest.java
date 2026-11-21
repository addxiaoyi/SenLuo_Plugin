package net.kaleidoscope.cookery.nms;

import net.kaleidoscope.cookery.worldgen.VillageStructureCodec;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Class-file checks only: never initialize Minecraft registries to simulate a codec pass. */
class VillageStructureCodecArtifactTest {
    private static final String OWNER = "net/kaleidoscope/cookery/nms/v1_21_R7/VillageStructureCodecNative";
    private static final String STRUCTURE = "net/minecraft/world/level/levelgen/structure/";
    private record Body(Set<String> calls, Set<String> fields, Set<String> constants, Set<Integer> integers) {}
    private static Map<String, Body> read(ClassReader reader) {
        var result = new HashMap<String, Body>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                Body body = new Body(new LinkedHashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
                result.put(name + descriptor, body);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) { body.calls().add(owner + "." + name + desc); }
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String desc) { body.fields().add(owner + "." + name + desc); }
                    @Override public void visitLdcInsn(Object value) { if (value instanceof String text) body.constants().add(text); if (value instanceof Integer count) body.integers().add(count); }
                    @Override public void visitIntInsn(int opcode, int operand) { body.integers().add(operand); }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }
    private static Map<String, Body> nativeClass(String name) throws Exception {
        try (var input = VillageStructureCodecArtifactTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, "Actual native compiler ABI is required"); return read(new ClassReader(input));
        }
    }

    @Test void actualNativeAbiSavesAndRestoresPiecesThroughNamedElementRegistryCodecs() throws Exception {
        var piece = nativeClass(STRUCTURE + "PoolElementStructurePiece");
        assertTrue(piece.containsKey("<init>(L" + STRUCTURE + "pieces/StructurePieceSerializationContext;Lnet/minecraft/nbt/CompoundTag;)V"));
        Body saved = piece.get("addAdditionalSaveData(L" + STRUCTURE + "pieces/StructurePieceSerializationContext;Lnet/minecraft/nbt/CompoundTag;)V");
        assertNotNull(saved);
        assertTrue(saved.fields().contains(STRUCTURE + "pools/StructurePoolElement.CODECLcom/mojang/serialization/Codec;"));
        assertTrue(saved.calls().contains("net/minecraft/core/RegistryAccess.createSerializationContext(Lcom/mojang/serialization/DynamicOps;)Lnet/minecraft/resources/RegistryOps;"));
        assertTrue(saved.constants().containsAll(Set.of("pool_element", "rotation", "PosX", "PosY", "PosZ", "ground_level_delta", "junctions")));
        assertTrue(nativeClass(STRUCTURE + "StructurePiece").containsKey("createTag(L" + STRUCTURE + "pieces/StructurePieceSerializationContext;)Lnet/minecraft/nbt/CompoundTag;"));
        assertTrue(nativeClass(STRUCTURE + "pieces/StructurePieceSerializationContext").containsKey("<init>(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/core/RegistryAccess;L" + STRUCTURE + "templatesystem/StructureTemplateManager;)V"));
        var single = nativeClass(STRUCTURE + "pools/SinglePoolElement");
        Body template = single.get("encodeTemplate(Lcom/mojang/datafixers/util/Either;Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;");
        assertNotNull(template);
        assertTrue(template.calls().contains("com/mojang/datafixers/util/Either.left()Ljava/util/Optional;"));
        assertTrue(template.calls().contains("com/mojang/serialization/DataResult.error(Ljava/util/function/Supplier;)Lcom/mojang/serialization/DataResult;"));
        assertTrue(template.fields().contains("net/minecraft/resources/Identifier.CODECLcom/mojang/serialization/Codec;"));
        assertTrue(nativeClass("net/minecraft/nbt/NbtAccounter").containsKey("<init>(JI)V"));
        assertTrue(nativeClass("net/minecraft/nbt/NbtIo").containsKey("read(Ljava/io/DataInput;Lnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/CompoundTag;"));
    }

    @Test void shippedInspectorUsesRealCodecsBoundedNbtAndTransformsWithoutPlacementOrQueryCacheWarming() throws Exception {
        var calls = new HashSet<String>(); var constants = new HashSet<String>(); var integers = new HashSet<Integer>();
        try (var jar = new JarFile(Path.of(System.getProperty("cookery.pluginJar")).toFile())) {
            for (var entry : jar.stream().filter(entry -> entry.getName().endsWith(".class")).toList()) {
                try (var input = jar.getInputStream(entry)) {
                    var reader = new ClassReader(input); boolean inspector = reader.getClassName().startsWith(OWNER);
                    for (Body method : read(reader).values()) for (String call : method.calls()) {
                        assertFalse(call.startsWith("net/kaleidoscope/cookery/worldgen/VillageStructureCodec.inspect("),
                                "The production plugin must not install this dormant acceptance inspection");
                        if (!inspector) continue;
                        calls.add(call); constants.addAll(method.constants()); integers.addAll(method.integers());
                        assertFalse(call.contains(".filterBlocks(") || call.contains(".getJigsaws(") || call.contains(".placeInWorld(")
                                || call.contains(".getShuffledTemplates(") || call.contains(".getMaxSize(")
                                || call.startsWith("java/lang/reflect/Field.set(") || call.contains(".bindValue(")
                                || call.startsWith("net/momirealms/craftengine/bukkit/api/CraftEngineBlocks.place("),
                                "Codec checks must not place, publish or mutate native query caches: " + call);
                    }
                }
            }
        }
        assertTrue(calls.contains("com/mojang/serialization/Codec.encodeStart(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"));
        assertTrue(calls.contains("com/mojang/serialization/Codec.parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"));
        assertTrue(calls.contains(STRUCTURE + "PoolElementStructurePiece.<init>(L" + STRUCTURE + "pieces/StructurePieceSerializationContext;Lnet/minecraft/nbt/CompoundTag;)V"));
        assertTrue(calls.stream().anyMatch(call -> call.endsWith(".createTag(L" + STRUCTURE + "pieces/StructurePieceSerializationContext;)Lnet/minecraft/nbt/CompoundTag;")));
        assertTrue(calls.contains("net/minecraft/nbt/NbtAccounter.<init>(JI)V"));
        assertTrue(calls.contains("net/minecraft/world/level/block/state/BlockState.mirror(Lnet/minecraft/world/level/block/Mirror;)Lnet/minecraft/world/level/block/state/BlockState;"));
        assertTrue(calls.contains("net/minecraft/world/level/block/state/BlockState.rotate(Lnet/minecraft/world/level/block/Rotation;)Lnet/minecraft/world/level/block/state/BlockState;"));
        assertTrue(constants.containsAll(Set.of("element_type", "location", "processors", "projection", "minecraft:legacy_single_pool_element", "minecraft:jigsaw")));
        assertTrue(integers.contains(65_536)); assertTrue(integers.contains(32)); assertTrue(integers.contains(20));
        assertThrows(UnsupportedOperationException.class, () -> VillageStructureCodec.forMinecraft(null));
        assertThrows(UnsupportedOperationException.class, () -> VillageStructureCodec.forMinecraft("1.21.10"));
    }

    @Test void templatePlacementRunsNativeProcessorsBeforeMirrorAndRotationAndElementCodecNeedsBootstrap() throws Exception {
        var methods = nativeClass(STRUCTURE + "templatesystem/StructureTemplate");
        var placement = methods.entrySet().stream().filter(entry -> entry.getKey().startsWith("placeInWorld(")).findFirst().orElseThrow().getValue();
        assertTrue(placement.calls().stream().anyMatch(call -> call.contains(".processBlockInfos(")));
        assertTrue(placement.calls().stream().anyMatch(call -> call.contains("BlockState.mirror(")));
        assertTrue(placement.calls().stream().anyMatch(call -> call.contains("BlockState.rotate(")));
        List<String> ordered = new ArrayList<>(placement.calls());
        int process = java.util.stream.IntStream.range(0, ordered.size()).filter(index -> ordered.get(index).contains(".processBlockInfos(")).findFirst().orElseThrow();
        int mirror = java.util.stream.IntStream.range(0, ordered.size()).filter(index -> ordered.get(index).contains("BlockState.mirror(")).findFirst().orElseThrow();
        int rotate = java.util.stream.IntStream.range(0, ordered.size()).filter(index -> ordered.get(index).contains("BlockState.rotate(")).findFirst().orElseThrow();
        assertTrue(process < mirror && mirror < rotate, "Exact crop processor predicates run before state mirror then rotation");
        Body initialized = nativeClass(STRUCTURE + "pools/StructurePoolElement").get("<clinit>()V");
        assertTrue(initialized.fields().contains("net/minecraft/core/registries/BuiltInRegistries.STRUCTURE_POOL_ELEMENTLnet/minecraft/core/Registry;"),
                "A named-element round-trip must not be claimed from tests that avoid actual Minecraft registry bootstrap");
    }
}
