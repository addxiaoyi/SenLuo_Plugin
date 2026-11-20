package net.kaleidoscope.cookery.nms;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the shipped dormant ABI; natural generation, codec and publication still need server acceptance. */
class VillagePoolNmsArtifactTest {
    private static final String OWNER = "net/kaleidoscope/cookery/nms/v1_21_R7/VillagePoolPreparation";
    private static final String POOLS = "net/minecraft/world/level/levelgen/structure/pools/";
    private static final String PROCESSORS = "net/minecraft/world/level/levelgen/structure/templatesystem/";

    @Test void shippedPreparationReadsResolvedRegistriesAndBuildsFreshPoolsWithoutAnyPublicationOrWorldMutation() throws Exception {
        Set<String> calls = new HashSet<>(), strings = new HashSet<>();
        List<String> crops = new ArrayList<>();
        try (var jar = new JarFile(Path.of(System.getProperty("cookery.pluginJar")).toFile())) {
            var entries = jar.stream().filter(entry -> entry.getName().startsWith(OWNER) && entry.getName().endsWith(".class")).toList();
            assertFalse(entries.isEmpty());
            for (var entry : entries) try (var stream = jar.getInputStream(entry)) {
                new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                                calls.add(owner + "." + name + descriptor);
                                assertFalse((owner.startsWith("net/minecraft/") && Set.of("register", "registerForHolder", "bindValue", "setProjection",
                                        "setBlock", "setBlockState", "place", "onResourceManagerReload").contains(name))
                                        || (owner.equals("java/lang/reflect/Field") && name.startsWith("set") && !name.equals("setAccessible"))
                                        || (owner.endsWith("CraftEngineBlocks") && Set.of("place", "remove").contains(name))
                                        || owner.endsWith("StructureTemplateManager"), "Dormant construction must not publish or place: " + owner + "." + name);
                                assertFalse(name.equals("visualBlockState") || owner.equals("java/util/Random") || owner.contains("ThreadLocalRandom"),
                                        "Use current CE server states and native rule RNG");
                            }
                            @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                                assertFalse((opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) && owner.startsWith("net/minecraft/"),
                                        "Existing native state must not be mutated");
                            }
                            @Override public void visitLdcInsn(Object value) {
                                if (value instanceof String text) {
                                    strings.add(text);
                                    if (method.equals("prepare") && Set.of("tomato", "chili", "lettuce").contains(text)) crops.add(text);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        assertEquals(List.of("tomato", "chili", "lettuce"), crops);
        assertTrue(calls.contains("net/minecraft/core/RegistryAccess.lookupOrThrow(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Registry;"));
        assertTrue(calls.stream().anyMatch(call -> call.contains(".getOrThrow(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Holder$Reference;")));
        assertTrue(calls.contains(POOLS + "StructureTemplatePool.<init>(Lnet/minecraft/core/Holder;Ljava/util/List;)V"));
        assertTrue(calls.contains(POOLS + "StructureTemplatePool.getTemplates()Ljava/util/List;"));
        assertTrue(calls.contains(POOLS + "StructureTemplatePool.getFallback()Lnet/minecraft/core/Holder;"));
        assertTrue(calls.contains(POOLS + "StructurePoolElement.legacy(Ljava/lang/String;Lnet/minecraft/core/Holder;)Ljava/util/function/Function;"));
        assertTrue(calls.stream().anyMatch(call -> call.contains("ImmutableBlockState.customBlockState()")));
        assertTrue(calls.stream().anyMatch(call -> call.contains(".minecraftState()Ljava/lang/Object;")));
        assertTrue(strings.containsAll(Set.of("templates", "template", "processors", "overrideLiquidSettings", "elements", "rules", "inputPredicate",
                "locPredicate", "posPredicate", "blockEntityModifier", "blockState", "probability", "age", "7")));
        assertFalse(strings.stream().anyMatch(Set.of("rawTemplates", "maxSize", "structureRepository", "bindValue", "visualBlockState")::contains));
        assertTrue(calls.stream().anyMatch(call -> call.contains("VillagePoolPlan.requireExpandedMatches(Ljava/util/List;Ljava/util/List;)V")));
    }

    @Test void nativeProcessorContainsExactlyOneFirstMatchRuleProcessorWithTwoOrderedPointThreeThreeStatePredicates() throws Exception {
        Map<String, Integer> constructors = new HashMap<>();
        List<Float> probabilities = new ArrayList<>(); List<Integer> stateArguments = new ArrayList<>();
        List<String> unconditionalLocations = new ArrayList<>();
        try (var jar = new JarFile(Path.of(System.getProperty("cookery.pluginJar")).toFile());
             var stream = jar.getInputStream(jar.getJarEntry(OWNER + ".class"))) {
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("processor")) return null;
                    assertEquals("(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;)L" + PROCESSORS + "StructureProcessorList;", descriptor);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
                            if (method.equals("<init>")) constructors.merge(owner + descriptor, 1, Integer::sum);
                            assertFalse(owner.contains("RandomSource"), "A processor must not independently seed either rule");
                        }
                        @Override public void visitVarInsn(int opcode, int variable) { if (opcode == Opcodes.ALOAD) stateArguments.add(variable); }
                        @Override public void visitLdcInsn(Object value) { if (value instanceof Float probability) probabilities.add(probability); }
                        @Override public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
                            if (opcode == Opcodes.GETSTATIC) unconditionalLocations.add(owner + "." + field);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(Map.of(PROCESSORS + "StructureProcessorList(Ljava/util/List;)V", 1,
                PROCESSORS + "RuleProcessor(Ljava/util/List;)V", 1,
                PROCESSORS + "ProcessorRule(L" + PROCESSORS + "RuleTest;L" + PROCESSORS + "RuleTest;Lnet/minecraft/world/level/block/state/BlockState;)V", 2,
                PROCESSORS + "RandomBlockStateMatchTest(Lnet/minecraft/world/level/block/state/BlockState;F)V", 2), constructors);
        assertEquals(List.of(0.33F, 0.33F), probabilities);
        // Static helper arguments: tomato=0, chili=1, lettuce=2. Both rules see the original tomato.
        assertEquals(List.of(0, 1, 0, 2), stateArguments);
        assertEquals(List.of(PROCESSORS + "AlwaysTrueTest.INSTANCE", PROCESSORS + "AlwaysTrueTest.INSTANCE"), unconditionalLocations);
    }
}
