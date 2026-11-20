package net.kaleidoscope.cookery.nms;

import net.kaleidoscope.cookery.worldgen.VillagePublisher;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Static/isolated accessor checks; no server, final-native-field write or lifecycle barrier is claimed. */
class VillagePublicationNmsArtifactTest {
    private static final String OWNER = "net/kaleidoscope/cookery/nms/v1_21_R7/VillagePublication";
    private static final String TRANSACTION = "net/kaleidoscope/cookery/worldgen/VillagePublicationTransaction";
    private static final String STRUCTURE = "net/minecraft/world/level/levelgen/structure/";

    private record Call(String owner, String name, String descriptor) {}
    private record MethodBody(List<Call> calls, Set<String> strings) {}
    private static Map<String, MethodBody> read(ClassReader reader) {
        var result = new HashMap<String, MethodBody>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                var body = new MethodBody(new ArrayList<>(), new HashSet<>()); result.put(name + descriptor, body);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String target, String name, String descriptor, boolean itf) {
                        body.calls().add(new Call(target, name, descriptor));
                    }
                    @Override public void visitLdcInsn(Object value) { if (value instanceof String text) body.strings().add(text); }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }
    private static Path plugin() { return Path.of(System.getProperty("cookery.pluginJar")); }

    @Test void publicationOnlyStagesNarrowFieldsAndHasNoInstalledProductionCallerOrRegistryRebinding() throws Exception {
        Set<String> fields = new HashSet<>(); List<Call> calls = new ArrayList<>();
        int nativeWrites = 0, cacheWrites = 0;
        try (var jar = new JarFile(plugin().toFile())) {
            for (var entry : jar.stream().filter(entry -> entry.getName().endsWith(".class")).toList()) {
                try (var stream = jar.getInputStream(entry)) {
                    var reader = new ClassReader(stream);
                    boolean adapter = reader.getClassName().startsWith(OWNER);
                    for (var method : read(reader).entrySet()) {
                        for (Call call : method.getValue().calls()) {
                            assertFalse((call.owner().equals(OWNER) || call.owner().equals("net/kaleidoscope/cookery/worldgen/VillagePublisher"))
                                            && call.name().equals("stage"), "Publication must have no installed lifecycle caller");
                            if (!adapter) continue;
                            calls.add(call);
                            assertFalse(call.name().equals("bindValue") || call.name().equals("registerForHolder")
                                    || call.owner().startsWith("net/minecraft/") && Set.of("register", "setBlock", "setBlockState", "placeInWorld").contains(call.name()),
                                    "Publication must retain registered identities and must not generate a world");
                            if (call.owner().equals("java/lang/reflect/Field") && call.name().equals("set")) {
                                nativeWrites++;
                                assertEquals(OWNER + "$Access", reader.getClassName());
                                assertTrue(method.getKey().startsWith("set("));
                            }
                            if (call.owner().equals("java/util/Map") && Set.of("put", "remove").contains(call.name())) {
                                cacheWrites++;
                                assertTrue(method.getKey().startsWith("write("), "Cache mutation belongs only to an owned transaction slot");
                            }
                            if (call.owner().equals(OWNER + "$Access") && call.name().equals("set"))
                                assertTrue(method.getKey().startsWith("write("), "Native field mutation belongs only to an owned transaction slot");
                        }
                        if (adapter) fields.addAll(method.getValue().strings());
                    }
                }
            }
        }
        assertEquals(1, nativeWrites); assertEquals(2, cacheWrites);
        assertTrue(fields.containsAll(Set.of("structureRepository", "list", "rawTemplates", "templates", "maxSize",
                "palettes", "entityInfoList", "persistentDataContainer", "author", "cache", "cachedJigsaws")));
        assertTrue(calls.stream().anyMatch(call -> call.owner().equals(TRANSACTION) && call.name().equals("<init>")));
        assertTrue(calls.stream().anyMatch(call -> call.owner().equals(OWNER.replace("VillagePublication", "VillageTemplateMigration")) && call.name().equals("prepare")),
                "Mutable public Prepared templates must be compared with a fresh complete pinned migration");
        assertTrue(calls.stream().anyMatch(call -> call.owner().equals(OWNER.replace("VillagePublication", "VillagePoolPreparation")) && call.name().equals("prepare")),
                "Prepared effective pools must be checked against current native inputs");
        assertTrue(calls.stream().anyMatch(call -> call.owner().equals(TRANSACTION + "$ExclusionScope") && call.name().equals("check")));
        assertThrows(UnsupportedOperationException.class, () -> VillagePublisher.forMinecraft(null));
        assertThrows(UnsupportedOperationException.class, () -> VillagePublisher.forMinecraft("1.21.10"));
    }

    private static Map<String, String> fields(String className) throws Exception {
        var result = new HashMap<String, String>();
        try (var input = VillagePublicationNmsArtifactTest.class.getClassLoader().getResourceAsStream(className + ".class")) {
            assertNotNull(input, "Pinned native compiler ABI is required");
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    result.put(name, descriptor + ":" + (access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))); return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return result;
    }
    @Test void actualCompilerAbiMatchesEveryWrittenFieldAndTemplatePlacementSnapshot() throws Exception {
        var pool = fields(STRUCTURE + "pools/StructureTemplatePool");
        assertEquals("Ljava/util/List;:" + Opcodes.ACC_FINAL, pool.get("rawTemplates"));
        assertEquals("Lit/unimi/dsi/fastutil/objects/ObjectArrayList;:" + Opcodes.ACC_FINAL, pool.get("templates"));
        assertEquals("I:0", pool.get("maxSize"));
        assertEquals("Ljava/util/List;:" + Opcodes.ACC_FINAL, fields(STRUCTURE + "templatesystem/StructureProcessorList").get("list"));
        var template = fields(STRUCTURE + "templatesystem/StructureTemplate");
        assertEquals("Ljava/util/List;:" + Opcodes.ACC_FINAL, template.get("palettes"));
        assertEquals("Ljava/util/List;:" + Opcodes.ACC_FINAL, template.get("entityInfoList"));
        assertEquals("Ljava/lang/String;:0", template.get("author"));
        assertTrue(template.get("persistentDataContainer").endsWith("/persistence/CraftPersistentDataContainer;:0"));
        var palette = fields(STRUCTURE + "templatesystem/StructureTemplate$Palette");
        assertEquals("Ljava/util/Map;:" + Opcodes.ACC_FINAL, palette.get("cache"));
        assertEquals("Ljava/util/List;:0", palette.get("cachedJigsaws"));
        assertEquals("Ljava/util/Map;:" + Opcodes.ACC_FINAL,
                fields(STRUCTURE + "templatesystem/StructureTemplateManager").get("structureRepository"));
    }

    @Test void nativePaletteCachesUseOrderedStateFiltersAndUnshuffledJigsawRecords() throws Exception {
        Map<String, MethodBody> methods;
        try (var input = getClass().getClassLoader().getResourceAsStream(STRUCTURE + "templatesystem/StructureTemplate$Palette.class")) {
            assertNotNull(input); methods = read(new ClassReader(input));
        }
        var filtered = methods.entrySet().stream().filter(entry -> entry.getKey().startsWith("lambda$blocks$0(")).findFirst().orElseThrow().getValue();
        assertTrue(filtered.calls().stream().anyMatch(call -> call.owner().endsWith("/BlockState") && call.name().equals("is")));
        var jigsaws = methods.get("jigsaws()Ljava/util/List;"); assertNotNull(jigsaws);
        assertTrue(jigsaws.calls().stream().anyMatch(call -> call.owner().equals("java/util/stream/Stream") && call.name().equals("map")));
        assertTrue(jigsaws.calls().stream().anyMatch(call -> call.owner().equals("java/util/stream/Stream") && call.name().equals("toList")));
        assertTrue(jigsaws.calls().stream().noneMatch(call -> call.name().toLowerCase().contains("shuffle") || call.owner().contains("Random")));
        try (var jar = new JarFile(plugin().toFile()); var input = jar.getInputStream(jar.getJarEntry(OWNER + "$QuerySnapshot.class"))) {
            var validator = read(new ClassReader(input));
            assertTrue(validator.values().stream().flatMap(body -> body.calls().stream())
                    .anyMatch(call -> call.owner().equals("java/util/List") && call.name().equals("equals")),
                    "Cached jigsaws must be checked against complete native records");
        }
    }

    static final class GoodField { private final List<?> data = List.of(); private int count; }
    static final class WrongFields { private static final List<?> data = List.of(); private String count; }
    @Test void strictFieldAccessRejectsStaticWrongTypeWrongFinalityAndMissingFieldsBeforeAnyWrite() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{plugin().toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> access = Class.forName(OWNER.replace('/', '.') + "$Access", true, loader);
            Method field = access.getDeclaredMethod("field", Class.class, String.class, Class.class, boolean.class); field.setAccessible(true);
            assertInstanceOf(java.lang.reflect.Field.class, field.invoke(null, GoodField.class, "data", List.class, true));
            assertInstanceOf(java.lang.reflect.Field.class, field.invoke(null, GoodField.class, "count", int.class, false));
            for (Object[] invalid : List.of(new Object[]{WrongFields.class, "data", List.class, true},
                    new Object[]{WrongFields.class, "count", int.class, false}, new Object[]{GoodField.class, "data", List.class, false},
                    new Object[]{GoodField.class, "missing", Object.class, false})) {
                InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> field.invoke(null, invalid));
                assertTrue(thrown.getCause() instanceof IllegalArgumentException || thrown.getCause() instanceof IllegalStateException);
            }
        }
    }
}
