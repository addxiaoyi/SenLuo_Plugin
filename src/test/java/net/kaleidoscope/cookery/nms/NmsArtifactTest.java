package net.kaleidoscope.cookery.nms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Check shipped bytecode: source tests cannot catch Paper remapping a mislabelled JAR. */
class NmsArtifactTest {
    private static final String NMS = "net/minecraft/";
    private static final String CRAFT = "org/bukkit/craftbukkit/";
    private static final String POS = "L" + NMS + "core/BlockPos;";
    private static final String LEVEL = "L" + NMS + "world/level/Level;";
    private static final String SERVER_LEVEL = "L" + NMS + "server/level/ServerLevel;";
    private static final String ENTITY = "L" + NMS + "world/entity/Entity;";
    private static final String ACTION = "L" + NMS + "network/protocol/game/ServerboundClientCommandPacket$Action;";
    private static final Map<String, String> PAPER_ABI = Map.ofEntries(
            Map.entry(NMS + "core/BlockPos.<init>", "(III)V"),
            Map.entry(NMS + "core/BlockPos.above", "()" + POS),
            Map.entry(NMS + "server/level/ServerLevel.isRainingAt", "(" + POS + ")Z"),
            Map.entry(NMS + "server/level/ServerLevel.isRaining", "()Z"),
            Map.entry(NMS + "server/level/ServerLevel.canSeeSky", "(" + POS + ")Z"),
            Map.entry(NMS + "world/level/block/PointedDripstoneBlock.findStalactiteTipAboveCauldron",
                    "(" + LEVEL + POS + ")" + POS),
            Map.entry(NMS + "world/level/block/PointedDripstoneBlock.getCauldronFillFluidType",
                    "(" + SERVER_LEVEL + POS + ")L" + NMS + "world/level/material/Fluid;"),
            Map.entry(NMS + "world/level/material/Fluids.WATER", "L" + NMS + "world/level/material/FlowingFluid;"),
            Map.entry(NMS + "world/level/block/state/properties/BlockStateProperties.LIT",
                    "L" + NMS + "world/level/block/state/properties/BooleanProperty;"),
            Map.entry(NMS + "world/entity/Entity.getBukkitEntity", "()L" + CRAFT + "entity/CraftEntity;"),
            Map.entry(CRAFT + "entity/CraftEntity.getHandle", "()" + ENTITY),
            Map.entry(NMS + "network/protocol/game/ServerboundClientCommandPacket.getAction", "()" + ACTION),
            Map.entry(NMS + "network/protocol/game/ServerboundClientCommandPacket$Action.PERFORM_RESPAWN", ACTION));

    @Test void declaresMojangMappingsBeforePaperLoadsThePlugin() throws IOException {
        try (JarFile jar = pluginJar()) {
            assertEquals("mojang", jar.getManifest().getMainAttributes().getValue("paperweight-mappings-namespace"),
                    "Paper otherwise remaps Fluid to FluidState even though the compiler used Mojang names");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1_21_R1", "v1_21_R2", "v1_21_R3", "v1_21_R4", "v1_21_R5",
            "v1_21_R6", "v1_21_R7", "v26_1_R1", "v26_2_R1"})
    void shippedBridgeUsesPaperDescriptors(String version) throws IOException {
        Map<String, String> members = new HashMap<>();
        Set<String> types = new HashSet<>();
        try (JarFile jar = pluginJar()) {
            String name = "net/kaleidoscope/cookery/nms/" + version + "/NmsV" + version.substring(1) + ".class";
            var entry = jar.getJarEntry(name);
            assertNotNull(entry, "Missing supported NMS bridge " + version);
            try (var stream = jar.getInputStream(entry)) {
                new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                               String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                                  String descriptor, boolean isInterface) {
                                remember(owner, name, descriptor);
                            }

                            @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                                remember(owner, name, descriptor);
                            }

                            @Override public void visitTypeInsn(int opcode, String type) {
                                types.add(type);
                            }

                            private void remember(String owner, String name, String descriptor) {
                                if (owner.startsWith(NMS) || owner.startsWith(CRAFT)) {
                                    members.put(owner + "." + name, descriptor);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        Map<String, String> expected = new HashMap<>(PAPER_ABI);
        expected.put(NMS + "world/level/Level.isOutsideBuildHeight", "(" + POS + ")Z");
        expected.put(NMS + "world/level/Level.getWorldBorder", "()L" + NMS + "world/level/border/WorldBorder;");
        expected.put(NMS + "world/level/border/WorldBorder.isWithinBounds", "(" + POS + ")Z");
        expected.put(NMS + "world/phys/shapes/Shapes.box", "(DDDDDD)L" + NMS + "world/phys/shapes/VoxelShape;");
        expected.put(NMS + "world/level/Level.isUnobstructed", "(" + ENTITY + "L" + NMS + "world/phys/shapes/VoxelShape;)Z");
        expected.put(NMS + "core/Direction.UP", "L" + NMS + "core/Direction;");
        expected.put(NMS + "world/entity/player/Player.mayUseItemAt", "(" + POS + "L" + NMS + "core/Direction;L" + NMS + "world/item/ItemStack;)Z");
        assertEquals(expected, members, "Shipped NMS member descriptors differ: " + version);
        assertTrue(types.stream().noneMatch(type -> type.startsWith(CRAFT + "v")),
                "Paper does not provide versioned CraftBukkit packages: " + types);
        assertTrue(types.contains(NMS + "network/protocol/game/ServerboundTeleportToEntityPacket"));
        if (version.equals("v26_1_R1")) {
            assertTrue(types.contains(NMS + "network/protocol/game/ServerboundSpectateEntityPacket"));
        } else if (version.equals("v26_2_R1")) {
            assertTrue(types.contains(NMS + "network/protocol/game/ServerboundSpectatorActionPacket"));
        }
    }

    private static JarFile pluginJar() throws IOException {
        return new JarFile(Path.of(System.getProperty("cookery.pluginJar")).toFile());
    }
}
