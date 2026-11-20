package net.kaleidoscope.cookery.nms;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.net.JarURLConnection;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Reads real compiler/server ABI and shipped companion bytecode without initializing NMS classes. */
class NativeSickleMiningArtifactTest {
    private static final String NMS = "net/minecraft/";
    private static final String ITEM = NMS + "world/item/Item";
    private static final String SHEARS = NMS + "world/item/ShearsItem";
    private static final String POLICY = "net/kaleidoscope/cookery/nms/NativeMiningImplementation";
    private static final String MINING = "(L" + NMS + "world/item/ItemStack;L" + NMS + "world/level/Level;L"
            + NMS + "world/level/block/state/BlockState;L" + NMS + "core/BlockPos;L" + NMS + "world/entity/LivingEntity;)Z";

    private record Body(Set<String> calls, Set<String> fields, Set<String> types, Set<String> handles) {}
    private static Map<String, Body> read(ClassReader reader) {
        Map<String, Body> methods = new HashMap<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                Body body = new Body(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
                methods.put(name + descriptor, body);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
                        body.calls().add(owner + "." + name + desc);
                    }
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        body.fields().add(owner + "." + name);
                    }
                    @Override public void visitLdcInsn(Object value) {
                        if (value instanceof Type type) body.types().add(type.getInternalName());
                    }
                    @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
                        for (Object argument : arguments) if (argument instanceof Handle handle)
                            body.handles().add(handle.getOwner() + "." + handle.getName() + handle.getDesc());
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return methods;
    }

    private static Body named(Map<String, Body> methods, String name) {
        return methods.entrySet().stream().filter(entry -> entry.getKey().startsWith(name + "("))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
    }

    @Test void theRealR7ItemAbiHasOnlyTheTwoVerifiedMiningImplementations() throws IOException {
        var url = getClass().getClassLoader().getResource(ITEM + ".class");
        assertNotNull(url, "The real R7 compiler/server ABI must be on the test runtime path");
        assertInstanceOf(JarURLConnection.class, url.openConnection());
        var connection = (JarURLConnection) url.openConnection(); connection.setUseCaches(false);
        Set<String> implementations = new HashSet<>();
        try (JarFile jar = connection.getJarFile()) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().startsWith(NMS + "world/item/") || !entry.getName().endsWith(".class")) continue;
                try (var stream = jar.getInputStream(entry)) {
                    var reader = new ClassReader(stream);
                    reader.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                            if (name.equals("mineBlock") && descriptor.equals(MINING)) implementations.add(reader.getClassName());
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
            assertEquals(Set.of(ITEM, SHEARS), implementations);
            Map<String, Body> ordinary;
            try (var stream = jar.getInputStream(jar.getJarEntry(ITEM + ".class"))) { ordinary = read(new ClassReader(stream)); }
            Map<String, Body> shears;
            try (var stream = jar.getInputStream(jar.getJarEntry(SHEARS + ".class"))) {
                var reader = new ClassReader(stream); assertEquals(ITEM, reader.getSuperName()); shears = read(reader);
            }
            Body standardMining = ordinary.get("mineBlock" + MINING), shearsMining = shears.get("mineBlock" + MINING);
            assertNotNull(standardMining); assertNotNull(shearsMining);
            assertTrue(standardMining.calls().stream().anyMatch(call -> call.contains("BlockState.getDestroySpeed(")));
            assertTrue(shearsMining.fields().contains(NMS + "tags/BlockTags.FIRE"));
            assertTrue(shearsMining.calls().stream().anyMatch(call -> call.contains("ItemStack.hurtAndBreak(")));
            assertTrue(shearsMining.calls().stream().noneMatch(call -> call.contains(".getDestroySpeed(") || call.equals(ITEM + ".mineBlock" + MINING)),
                    "Shears damage zero-hardness non-FIRE blocks and do not enter Item.mineBlock");
        }
    }

    @Test void theShippedCompanionUsesTheCapturedImplementationForBaseWearAndBothEventKinds() throws IOException {
        Map<String, Body> methods;
        String owner = "net/kaleidoscope/cookery/nms/v1_21_R7/SourceSickleNative";
        try (JarFile jar = new JarFile(Path.of(System.getProperty("cookery.pluginJar")).toFile())) {
            var entry = jar.getJarEntry(owner + ".class"); assertNotNull(entry);
            try (var stream = jar.getInputStream(entry)) { methods = read(new ClassReader(stream)); }
        }
        Body capture = named(methods, "captureTool");
        assertTrue(capture.types().containsAll(Set.of(ITEM, SHEARS)));
        assertTrue(capture.calls().stream().anyMatch(call -> call.startsWith(POLICY + ".inspect(")));
        assertTrue(capture.calls().contains(POLICY + ".baseWear(ZIFZ)I"));
        assertTrue(capture.fields().contains(NMS + "tags/BlockTags.FIRE"));
        Body walkerCallback = named(methods, "miningFrame").handles().stream()
                .filter(handle -> handle.startsWith(owner + ".lambda$miningFrame$")
                        && handle.contains("(L" + POLICY + ";"))
                .map(handle -> methods.get(handle.substring(owner.length() + 1)))
                .findFirst().orElseThrow();
        assertTrue(walkerCallback.handles().contains(POLICY + ".matches(Ljava/lang/StackWalker$StackFrame;)Z"));
        long attributedCallbacks = methods.entrySet().stream().filter(entry -> entry.getKey().startsWith("lambda$destroyVegetation$"))
                .filter(entry -> entry.getValue().calls().contains(owner + ".miningFrame(L" + POLICY + ";)Z")).count();
        assertEquals(2, attributedCallbacks, "Damage and break events must both belong to the captured native implementation");
        Body certify = named(methods, "certifyTool");
        assertTrue(certify.calls().stream().anyMatch(call -> call.contains("ItemStack.isSameItemSameComponents(")),
                "Supporting shears must retain complete native component conservation");
        assertTrue(certify.calls().stream().anyMatch(call -> call.contains("Decision.observedBreak(")),
                "Supporting shears must retain the real break-event requirement");
    }

    private record Branch(int opcode, Label target) {}
    private record Invocation(String owner, String name) {}

    @Test void aRejectedNativeBreakReturnsBeforeSlotOrComponentCertificationButExceptionsRemainPartial() throws IOException {
        List<Object> flow = new ArrayList<>(); Set<String> handlers = new HashSet<>();
        String owner = "net/kaleidoscope/cookery/nms/v1_21_R7/SourceSickleNative";
        try (JarFile jar = new JarFile(Path.of(System.getProperty("cookery.pluginJar")).toFile())) {
            var entry = jar.getJarEntry(owner + ".class"); assertNotNull(entry);
            try (var stream = jar.getInputStream(entry)) {
                new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        if (!name.equals("destroyVegetation")) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitJumpInsn(int opcode, Label label) { flow.add(new Branch(opcode, label)); }
                            @Override public void visitLabel(Label label) { flow.add(label); }
                            @Override public void visitMethodInsn(int opcode, String target, String name, String desc, boolean isInterface) {
                                flow.add(new Invocation(target, name));
                            }
                            @Override public void visitInsn(int opcode) { flow.add(opcode); }
                            @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                                if (type != null) handlers.add(type);
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        int rejected = flow.indexOf(new Invocation(POLICY, "rejectedDestruction"));
        int certified = flow.indexOf(new Invocation(owner, "certifyTool"));
        assertTrue(rejected >= 0); assertTrue(certified > rejected);
        Branch branch = null;
        for (int i = rejected - 1; i >= 0; i--) if (flow.get(i) instanceof Branch found) { branch = found; break; }
        assertNotNull(branch); assertEquals(Opcodes.IFNE, branch.opcode(), "Only a successful native result reaches certification");
        int continuation = flow.indexOf(branch.target());
        int returned = -1;
        for (int i = rejected + 1; i < flow.size(); i++) if (Integer.valueOf(Opcodes.ARETURN).equals(flow.get(i))) { returned = i; break; }
        assertTrue(returned > rejected && returned < continuation && continuation < certified,
                "The false result must return its commit decision before any selected-slot/component checks");
        assertTrue(handlers.containsAll(Set.of("java/lang/RuntimeException", "java/lang/LinkageError")));
        assertTrue(flow.contains(new Invocation("net/kaleidoscope/cookery/nms/NativeSickleAccess$Outcome", "failed")));
    }
}
