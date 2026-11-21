package net.kaleidoscope.cookery.nms;

import net.kaleidoscope.cookery.worldgen.VillageStructureSources;
import net.kaleidoscope.cookery.worldgen.VillageTemplatePlan;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Source parsing is real NMS; codec/storage/placement of the prepared template still requires server acceptance. */
class VillageTemplateNmsArtifactTest {
    private static final String OWNER = "net/kaleidoscope/cookery/nms/v1_21_R7/VillageTemplateMigration";
    private Path pluginJar() { return Path.of(System.getProperty("cookery.pluginJar")); }
    private URLClassLoader loader() throws IOException {
        return new URLClassLoader(new java.net.URL[]{pluginJar().toUri().toURL()}, getClass().getClassLoader());
    }

    @Test void nativeBoundedReaderChecksAllFiveExactSourceLedgersWithoutAWorldOrCeRegistry() throws Exception {
        try (var loader = loader()) {
            var type = Class.forName(OWNER.replace('/', '.'), true, loader);
            var inspect = type.getMethod("inspectSource", VillageStructureSources.Template.class);
            for (var source : VillageStructureSources.bundled().templates()) {
                var actual = (VillageTemplatePlan) inspect.invoke(null, source);
                var expected = VillageTemplatePlan.from(source);
                assertEquals(expected.location(), actual.location());
                assertEquals(expected.sourcePalette(), actual.sourcePalette());
                assertEquals(expected.customBlocks(), actual.customBlocks());
                assertEquals(expected.lootTables(), actual.lootTables());
            }
        }
    }

    @Test void nativeReaderRejectsCompressedOverflowMalformedGzipAndInflatedAllocationBeforePublication() throws Exception {
        try (var loader = loader()) {
            var type = Class.forName(OWNER.replace('/', '.'), true, loader);
            var decode = type.getDeclaredMethod("decodeBounded", byte[].class); decode.setAccessible(true);
            var overflow = assertThrows(InvocationTargetException.class, () -> decode.invoke(null, (Object) new byte[1_048_577]));
            assertInstanceOf(IllegalArgumentException.class, overflow.getCause());
            var malformed = assertThrows(InvocationTargetException.class, () -> decode.invoke(null, (Object) new byte[]{1, 2, 3}));
            assertInstanceOf(IOException.class, malformed.getCause());
            var compressed = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(new GZIPOutputStream(compressed))) {
                output.writeByte(10); output.writeUTF(""); // Unnamed root compound.
                output.writeByte(7); output.writeUTF("large"); output.writeInt(9_437_184);
                // Above our 8 MiB quota, below the native format's exclusive 16 MiB cap.
                // This must reach the allocation quota, not fail its earlier length check.
                output.writeByte(0);
            }
            var inflation = assertThrows(InvocationTargetException.class, () -> decode.invoke(null, (Object) compressed.toByteArray()));
            assertTrue(inflation.getCause().getClass().getName().endsWith("NbtAccounterException"),
                    () -> "Expected native quota rejection, got " + inflation.getCause());
        }
    }

    @Test void shippedAdapterUsesNativeMigrationAndFreshPaletteAbiWithoutPublishingOrWorldMutation() throws Exception {
        Set<String> calls = new HashSet<>(); Set<String> strings = new HashSet<>();
        try (var jar = new JarFile(pluginJar().toFile())) {
            var entries = jar.stream().filter(entry -> entry.getName().startsWith(OWNER) && entry.getName().endsWith(".class")).toList();
            assertFalse(entries.isEmpty());
            for (var entry : entries) try (var stream = jar.getInputStream(entry)) {
                new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
                                calls.add(owner + "." + method + descriptor);
                                assertFalse((owner.startsWith("net/minecraft/") && Set.of("setBlock", "setBlockState", "rotate", "mirror").contains(method))
                                        || (owner.endsWith("CraftEngineBlocks") && Set.of("place", "remove").contains(method))
                                        || owner.endsWith("StructureTemplateManager"), "Preparation must not place or publish: " + owner + "." + method);
                            }
                            @Override public void visitLdcInsn(Object value) { if (value instanceof String text) strings.add(text); }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        String nbt = "net/minecraft/nbt/", nativeTemplate = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate";
        assertTrue(calls.contains(nbt + "NbtAccounter.create(J)L" + nbt + "NbtAccounter;"));
        assertTrue(calls.contains(nbt + "NbtIo.readCompressed(Ljava/io/InputStream;L" + nbt + "NbtAccounter;)L" + nbt + "CompoundTag;"));
        assertTrue(calls.contains(nbt + "NbtUtils.readBlockState(Lnet/minecraft/core/HolderGetter;L" + nbt + "CompoundTag;)Lnet/minecraft/world/level/block/state/BlockState;"));
        assertTrue(calls.contains(nbt + "NbtUtils.writeBlockState(Lnet/minecraft/world/level/block/state/BlockState;)L" + nbt + "CompoundTag;"));
        assertTrue(calls.contains(nativeTemplate + ".load(Lnet/minecraft/core/HolderGetter;L" + nbt + "CompoundTag;)V"));
        assertTrue(calls.contains(nativeTemplate + "$StructureBlockInfo.<init>(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;L" + nbt + "CompoundTag;)V"));
        assertTrue(strings.containsAll(Set.of("ca.spottedleaf.dataconverter.minecraft.MCDataConverter", "convertTag", "STRUCTURE",
                "getCurrentVersion", "blockLookup", "palettes", "addToLists", "buildInfoList")));
        assertFalse(strings.stream().anyMatch(Set.of("structureRepository", "rawTemplates", "maxSize", "registerStructure")::contains));
    }
}
