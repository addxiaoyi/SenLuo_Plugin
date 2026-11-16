package net.kaleidoscope.cookery.nms;

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

/** The JVM must dispatch the real native overrides on every retained server ABI. */
class RiceBowlNmsArtifactTest {
    private static final String NMS = "net/minecraft/";
    private static final String INPUT = "L" + NMS + "world/item/crafting/CraftingInput;";
    private static final String STACK = "L" + NMS + "world/item/ItemStack;";
    private static final String SHAPELESS = NMS + "world/item/crafting/ShapelessRecipe";
    private static final String CUSTOM = "net/kaleidoscope/cookery/recipe/RiceBowlRecipe";

    @ParameterizedTest
    @ValueSource(strings = {"v1_21_R1", "v1_21_R2", "v1_21_R3", "v1_21_R4", "v1_21_R5",
            "v1_21_R6", "v1_21_R7", "v26_1_R1", "v26_2_R1"})
    void nativeRecipeDispatchesMatchingOutputAndRemaindersWithoutVanillaIngredientCountGate(String version) throws IOException {
        String owner = "net/kaleidoscope/cookery/nms/" + version + "/RiceBowlNativeRecipe";
        Map<String, Set<String>> calls = new HashMap<>();
        Set<String> methods = new HashSet<>();
        try (JarFile jar = new JarFile(Path.of(System.getProperty("cookery.pluginJar")).toFile())) {
            var entry = jar.getJarEntry(owner + ".class");
            assertNotNull(entry, "Missing native rice recipe for " + version);
            try (var stream = jar.getInputStream(entry)) {
                new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public void visit(int format, int access, String name, String signature, String superName, String[] interfaces) {
                        assertEquals(SHAPELESS, superName);
                    }

                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        methods.add(name + descriptor);
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String target, String method, String desc, boolean isInterface) {
                                calls.computeIfAbsent(name, ignored -> new HashSet<>()).add(target + "." + method + desc);
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        assertTrue(methods.contains("matches(" + INPUT + "L" + NMS + "world/level/Level;)Z"));
        String lookup = version.startsWith("v26_") ? "" : "L" + NMS + "core/HolderLookup$Provider;";
        assertTrue(methods.contains("assemble(" + INPUT + lookup + ")" + STACK), "Native assemble ABI changed for " + version);
        assertTrue(methods.contains("getRemainingItems(" + INPUT + ")L" + NMS + "core/NonNullList;"));
        assertTrue(calls.get("matches").contains(CUSTOM + ".matches(Lnet/momirealms/craftengine/core/item/recipe/input/RecipeInput;)Z"));
        assertTrue(calls.get("matches").stream().noneMatch(call -> call.startsWith(SHAPELESS + ".matches")),
                "Companion shapeless matching would reject allowed extra occupied cells");
        assertTrue(calls.get("assemble").contains(CUSTOM + ".assemble(Lnet/momirealms/craftengine/core/item/recipe/input/RecipeInput;"
                + "Lnet/momirealms/craftengine/core/item/ItemBuildContext;)Lnet/momirealms/craftengine/core/item/Item;"));
        String delegateInput = version.equals("v1_21_R1")
                ? "L" + NMS + "world/item/crafting/RecipeInput;" : INPUT;
        String remainingDescriptor = "(" + delegateInput + ")L" + NMS + "core/NonNullList;";
        assertTrue(calls.get("getRemainingItems").contains(SHAPELESS + ".getRemainingItems" + remainingDescriptor),
                "Extra grid items must retain their native/CE crafting remainders");
        assertTrue(calls.get("getRemainingItems").contains(CUSTOM + ".isRice(Lnet/momirealms/craftengine/core/item/Item;)Z"));
        assertTrue(calls.values().stream().flatMap(Set::stream).noneMatch(call -> call.contains(".takeInput(")),
                "Native result slots already consume each occupied grid cell");
        String constructor = version.startsWith("v26_")
                ? "(L" + NMS + "world/item/crafting/Recipe$CommonInfo;L" + NMS + "world/item/crafting/CraftingRecipe$CraftingBookInfo;"
                    + "L" + NMS + "world/item/ItemStackTemplate;Ljava/util/List;)V"
                : "(Ljava/lang/String;L" + NMS + "world/item/crafting/CraftingBookCategory;" + STACK
                    + (version.equals("v1_21_R1") ? "L" + NMS + "core/NonNullList;" : "Ljava/util/List;") + ")V";
        assertTrue(calls.get("<init>").contains(SHAPELESS + ".<init>" + constructor), "Native constructor ABI changed for " + version);
    }
}
