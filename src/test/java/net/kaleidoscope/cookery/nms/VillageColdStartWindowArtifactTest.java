package net.kaleidoscope.cookery.nms;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Static artifact checks, not proof that the pinned startup scope has run on a server. */
class VillageColdStartWindowArtifactTest {
    private static final String OWNER = "net/kaleidoscope/cookery/nms/v1_21_R7/VillageColdStartWindowNative";
    private static final String OWNERSHIP = "net/kaleidoscope/cookery/worldgen/VillageColdStartWindow$LeaseOwnership";
    private static final String CE = "net/momirealms/craftengine/";
    private static final String NATIVE = "net/minecraft/";

    private record Method(Set<String> calls, Set<String> strings, Set<Integer> integers, int access) {}

    private static Map<String, Method> methods() throws IOException {
        var result = new HashMap<String, Method>();
        try (var jar = new JarFile(Path.of(System.getProperty("cookery.pluginJar")).toFile());
             var input = jar.getInputStream(jar.getJarEntry(OWNER + ".class"))) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    var method = new Method(new HashSet<>(), new HashSet<>(), new HashSet<>(), access); result.put(name, method);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            method.calls.add(owner + "." + name + desc);
                            assertFalse(name.equals("isSameThread") || name.equals("getTickCount") || name.equals("isReady"),
                                    "Folia permissive/unsupported flags cannot certify initial server-thread ownership");
                            assertFalse(Set.of("registerTask", "bindValue", "setBlock", "setBlockState", "getBlockAt", "createWorld", "place")
                                            .contains(name), "The dormant scope must not register, activate, or mutate worlds");
                        }
                        @Override public void visitLdcInsn(Object value) {
                            if (value instanceof String text) method.strings.add(text);
                            if (value instanceof Integer number) method.integers.add(number);
                        }
                        @Override public void visitIntInsn(int opcode, int operand) { method.integers.add(operand); }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return result;
    }

    @Test void shippedColdWindowHasNoPublicConstructorAndUsesLiveGlobalRootsActualThreadAndGenuinePhaseChecks() throws IOException {
        Map<String, Method> methods = methods();
        assertEquals(Opcodes.ACC_PRIVATE, methods.get("<init>").access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED));
        Set<String> roots = methods.get("validateRoots").calls;
        assertTrue(roots.containsAll(Set.of(
                "java/lang/Thread.currentThread()Ljava/lang/Thread;",
                NATIVE + "server/MinecraftServer.getRunningThread()Ljava/lang/Thread;",
                NATIVE + "server/MinecraftServer.getServer()L" + NATIVE + "server/MinecraftServer;",
                NATIVE + "server/MinecraftServer.registryAccess()L" + NATIVE + "core/RegistryAccess$Frozen;",
                NATIVE + "server/MinecraftServer.getStructureManager()L" + NATIVE + "world/level/levelgen/structure/templatesystem/StructureTemplateManager;",
                NATIVE + "server/MinecraftServer.getAllLevels()Ljava/lang/Iterable;",
                NATIVE + "server/MinecraftServer.isRunning()Z", "org/bukkit/Bukkit.getWorlds()Ljava/util/List;",
                CE + "core/plugin/CraftEngine.instance()L" + CE + "core/plugin/CraftEngine;",
                CE + "core/plugin/CraftEngine.resourceOperations()L" + CE + "core/plugin/ResourceOperationCoordinator;",
                CE + "core/plugin/CraftEngine.worldManager()L" + CE + "core/world/WorldManager;",
                CE + "bukkit/world/BukkitWorldManager.instance()L" + CE + "bukkit/world/BukkitWorldManager;",
                CE + "bukkit/world/BukkitWorldManager.initialized()Z",
                CE + "core/plugin/config/Config.delayConfigurationLoad()Z",
                CE + "core/plugin/CraftEngine.isEnabling()Z", CE + "core/plugin/CraftEngine.isReloading()Z",
                CE + "core/plugin/CraftEngine.isStopping()Z", CE + "core/plugin/CraftEngine.isDisabled()Z",
                CE + "core/world/chunk/ChunkGenerationStage.enabled()Z",
                OWNER + ".initialAfterEnableStack()Z")));
        assertTrue(methods.get("check").calls.contains(OWNERSHIP + ".check()V"));
        assertTrue(methods.get("check").calls.contains(OWNER + ".validateRoots()V"));
        assertTrue(methods.get("check").calls.stream().anyMatch(call -> call.startsWith(OWNERSHIP + ".poison(")));
        assertEquals(Set.of(OWNERSHIP + ".close()V"), methods.get("close").calls);
        assertTrue(methods.get("nativeRegistryAccess").calls.contains(OWNER + ".check()V"));
        assertTrue(methods.get("nativeStructureTemplateManager").calls.contains(OWNER + ".check()V"));
    }

    @Test void actualClassResourcesAndExactAfterEnableInvocationAreBoundedAndPinnedWithoutAnOffsetFallback() throws IOException {
        var methods = methods();
        Set<String> strings = new HashSet<>(); Set<Integer> integers = new HashSet<>(); Set<String> calls = new HashSet<>();
        methods.values().forEach(method -> { strings.addAll(method.strings); integers.addAll(method.integers); calls.addAll(method.calls); });
        assertTrue(strings.containsAll(Set.of(
                "dba6ce66ba3605dee91927aeb70f370a83efc3aa992ee48beb5fb9916795104a",
                "89dcc2c1461b63e4aa2f78072be19da08bb837213d9c12d8e3d3300626fee096",
                "69d5494e603f2f671afbc368d227d8ff037ba3f23ce7ff482f8c8ef8006dd862",
                "d4915561d13e05bf928f0621355ccc7ae87c4a859218e89733db8f4767bd800c",
                "e467ce1cbb88373ea16d49a9255a1f417a6b120d18e7b87df58f2191ecb716ad",
                "0c1b2033cf1a0b2922718c38544c7ca45661df615296c49d354184c104784823",
                "executeTasks", "onPluginEnable", "initServer", "()V", "()Z")));
        assertTrue(integers.containsAll(Set.of(23, 264, 4_194_305)));
        assertFalse(integers.contains(150), "The earlier before-enable task site must never be accepted");
        assertTrue(calls.contains("java/lang/StackWalker$StackFrame.getDeclaringClass()Ljava/lang/Class;"));
        assertTrue(calls.contains("java/lang/StackWalker$StackFrame.getDescriptor()Ljava/lang/String;"));
        assertTrue(calls.contains("java/lang/StackWalker$StackFrame.getByteCodeIndex()I"));
        assertTrue(calls.contains("java/lang/Class.getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;"));
        assertTrue(calls.contains("java/io/InputStream.readNBytes(I)[B"));
        assertTrue(calls.contains("java/security/MessageDigest.digest([B)[B"));
    }

    // Uses a genuine StackWalker call in a small compiled fixture. The suspended caller's bytecode
    // index must point at the invoke instruction itself, not the following instruction/return site.
    private static final class SuspendedCallerFixture {
        static int invokeWalker() { return captureCaller(); }
        private static int captureCaller() {
            return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                    .filter(frame -> frame.getDeclaringClass() == SuspendedCallerFixture.class && frame.getMethodName().equals("invokeWalker"))
                    .findFirst().orElseThrow().getByteCodeIndex());
        }
    }

    @Test void actualStackWalkerReportsTheSuspendedInvokeInstructionUsedByThePinnedCePhasePredicate() throws IOException {
        int actual = SuspendedCallerFixture.invokeWalker();
        List<Integer> opcodes = new ArrayList<>();
        try (var input = getClass().getResourceAsStream("VillageColdStartWindowArtifactTest$SuspendedCallerFixture.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("invokeWalker")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            assertEquals("captureCaller", name); opcodes.add(opcode);
                        }
                        @Override public void visitInsn(int opcode) { opcodes.add(opcode); }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(List.of(Opcodes.INVOKESTATIC, Opcodes.IRETURN), opcodes);
        assertEquals(0, actual, "The sole first instruction is the suspended invocation; no +3/+5 fallback is valid");
    }
}
