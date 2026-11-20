package net.kaleidoscope.cookery.nms;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
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

/** Checks the built opt-in probe; this never substitutes for a real cold-start rollback run. */
class VillagePublicationSmokeArtifactTest {
    private static final String ACCEPTANCE = "net/kaleidoscope/cookery/acceptance/";
    private static final String SMOKE = ACCEPTANCE + "VillagePublicationSmoke";
    private static final String WORLDGEN = "net/kaleidoscope/cookery/worldgen/";
    private record Call(String owner, String name, String descriptor) {}
    private record Body(List<Call> calls, Set<String> strings, Set<String> handles, List<Integer> opcodes) {}

    private static Map<String, Body> read(JarFile jar, String owner) throws Exception {
        var entry = jar.getJarEntry(owner + ".class"); assertNotNull(entry, owner);
        var result = new HashMap<String, Body>();
        try (var stream = jar.getInputStream(entry)) {
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    var body = new Body(new ArrayList<>(), new HashSet<>(), new HashSet<>(), new ArrayList<>());
                    result.put(name + descriptor, body);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                            body.calls().add(new Call(owner, name, descriptor)); body.opcodes().add(opcode);
                        }
                        @Override public void visitInsn(int opcode) { body.opcodes().add(opcode); }
                        @Override public void visitLdcInsn(Object value) { if (value instanceof String string) body.strings().add(string); }
                        @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
                            for (Object value : arguments) if (value instanceof Handle handle)
                                body.handles().add(handle.getOwner() + "." + handle.getName() + handle.getDesc());
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return result;
    }
    private static Body named(Map<String, Body> methods, String name) {
        return methods.entrySet().stream().filter(entry -> entry.getKey().startsWith(name + "("))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
    }
    private static JarFile probe() throws Exception {
        return new JarFile(Path.of(System.getProperty("cookery.acceptanceJar")).toFile());
    }

    @Test void builtProbeRegistersOnlyAtOnLoadAndChecksMissingCallbackBeforePostworldProbes() throws Exception {
        try (var jar = probe()) {
            var plugin = read(jar, ACCEPTANCE + "FoodSmokePlugin");
            assertEquals(List.of(new Call(SMOKE, "register", "(Lorg/bukkit/plugin/java/JavaPlugin;)V")), named(plugin, "onLoad").calls());
            assertEquals(new Call(SMOKE, "checkOnEnable", "(Lorg/bukkit/plugin/java/JavaPlugin;)V"), named(plugin, "onEnable").calls().getFirst());
            var smoke = read(jar, SMOKE);
            var register = named(smoke, "register");
            assertTrue(register.strings().contains("village-publication-smoke.enabled"));
            assertTrue(register.opcodes().contains(Opcodes.ICONST_0), "The feature must be explicitly opt-in");
            assertTrue(register.calls().stream().anyMatch(call -> call.name().equals("getBoolean") && call.descriptor().equals("(Ljava/lang/String;Z)Z")));
            assertTrue(register.calls().stream().anyMatch(call -> call.name().equals("afterEnableTaskRegistry")));
            assertTrue(register.handles().contains(SMOKE + ".run()V"));
            assertTrue(named(smoke, "checkOnEnable").strings().contains("-missing-after-enable"));
        }
    }

    @Test void realPublisherAndWindowAreExercisedThroughTheIntentionalRollbackWithIndependentWitnesses() throws Exception {
        try (var jar = probe()) {
            var methods = read(jar, SMOKE); Body run = named(methods, "run");
            assertTrue(run.calls().contains(new Call(WORLDGEN + "VillageColdStartWindow", "open", "(Ljava/lang/String;)L" + WORLDGEN + "VillageColdStartWindow;")));
            assertTrue(run.calls().stream().anyMatch(call -> call.owner().equals(WORLDGEN + "VillagePublisher") && call.name().equals("stage")
                    && call.descriptor().contains("Ljava/lang/Runnable;")), "Use the production final cross-validation path");
            assertTrue(run.calls().contains(new Call(WORLDGEN + "VillagePublicationTransaction", "publish", "()L" + WORLDGEN + "VillagePublicationTransaction$Receipt;")));
            assertTrue(run.calls().stream().anyMatch(call -> call.name().equals("getCause")));
            assertTrue(run.calls().stream().anyMatch(call -> call.name().equals("rollbackFailures")));
            assertTrue(run.calls().stream().anyMatch(call -> call.name().equals("unconfirmedSlots")));
            assertTrue(run.calls().stream().anyMatch(call -> call.name().equals("witness")));
            assertTrue(run.calls().stream().anyMatch(call -> call.name().equals("checkRestored")));
            assertTrue(methods.entrySet().stream().filter(entry -> entry.getKey().startsWith("lambda$run$"))
                    .anyMatch(entry -> entry.getValue().opcodes().contains(Opcodes.ATHROW)
                            && entry.getValue().calls().stream().anyMatch(call -> call.name().equals("witness"))),
                    "The read-only witness callback must reject before any commit receipt");
            assertTrue(run.strings().containsAll(Set.of("scopeClosed", "writerAvailableAfterClose", "fullOriginalsRestored")));
        }
    }

    @Test void reportPublicationIsNewAndAtomicAndCannotBeConfusedWithProductionActivation() throws Exception {
        try (var jar = probe(); var production = new JarFile(Path.of(System.getProperty("cookery.pluginJar")).toFile())) {
            assertNull(production.getJarEntry(SMOKE + ".class"), "Acceptance code must not be distributed as a production activation hook");
            var methods = read(jar, SMOKE); Body write = named(methods, "write");
            assertTrue(write.calls().stream().anyMatch(call -> call.owner().equals("com/google/gson/GsonBuilder") && call.name().equals("serializeNulls")),
                    "A successful report must retain the explicit failure:null field");
            assertTrue(write.calls().stream().anyMatch(call -> call.owner().equals("java/nio/file/Files") && call.name().equals("createLink")));
            assertTrue(write.calls().stream().anyMatch(call -> call.owner().equals("java/nio/channels/FileChannel") && call.name().equals("force")));
            assertTrue(write.calls().stream().noneMatch(call -> call.name().equals("move") || call.name().equals("newOutputStream")));
            var all = new ArrayList<Call>(); methods.values().forEach(body -> all.addAll(body.calls()));
            assertTrue(all.stream().noneMatch(call -> call.owner().equals("java/lang/System") && Set.of("exit", "setProperty").contains(call.name())
                    || call.name().equals("shutdown") || call.name().equals("stopServer") || call.name().equals("safeShutdown")),
                    "This probe cannot claim an unproven startup abort by changing server lifecycle");
            assertTrue(named(methods, "base").strings().containsAll(Set.of("NOT_ACTIVATED", "NATIVE_COLD_START_PUBLICATION_ROLLBACK_ONLY",
                    "UNPROVEN_IF_UNEXPECTED_PARTIAL_STATE")));
        }
    }
}
