package net.kaleidoscope.cookery.nms.v1_21_R7;

import net.kaleidoscope.cookery.worldgen.VillageColdStartWindow;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.momirealms.craftengine.bukkit.world.BukkitWorldManager;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
import net.momirealms.craftengine.core.plugin.compatibility.PluginTaskRegistry;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.world.chunk.ChunkGenerationStage;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Pinned, dormant cold-start window. It installs no task and never mutates a world or native registry.
 * Normal server initial-world creation is excluded by the exact synchronous startup stack, actual
 * server thread and empty level set; CE resource writers are excluded by our own unexposed lease.
 * This does not claim a universal barrier against unsupported third-party startup world creation or
 * bytecode instrumentation/redefinition. Class resource pins do not certify transformed loaded code.
 * This window has not yet been exercised on a target server. CE task execution catches Throwable;
 * merely throwing from a future task would not establish a controlled startup failure boundary.
 */
public final class VillageColdStartWindowNative implements VillageColdStartWindow {
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final int MAX_CLASS_BYTES = 4_194_304;
    private static final String PAPER_SERVER = "69d5494e603f2f671afbc368d227d8ff037ba3f23ce7ff482f8c8ef8006dd862";
    private static final String PAPER_DEDICATED = "d4915561d13e05bf928f0621355ccc7ae87c4a859218e89733db8f4767bd800c";
    private static final String FOLIA_SERVER = "e467ce1cbb88373ea16d49a9255a1f417a6b120d18e7b87df58f2191ecb716ad";
    private static final String FOLIA_DEDICATED = "0c1b2033cf1a0b2922718c38544c7ca45661df615296c49d354184c104784823";

    private final MinecraftServer server;
    private final RegistryAccess registry;
    private final StructureTemplateManager templates;
    private final CraftEngine engine;
    private final ResourceOperationCoordinator operations;
    private final BukkitWorldManager worlds;
    private final LeaseOwnership ownership;

    private VillageColdStartWindowNative(MinecraftServer server, CraftEngine engine, BukkitWorldManager worlds) {
        this.server = server; this.engine = engine; this.worlds = worlds;
        this.registry = server.registryAccess(); this.templates = server.getStructureManager();
        this.operations = engine.resourceOperations();
        require(registry != null && templates != null && operations != null, "Initial native/CE roots are unavailable");
        validateRoots();
        this.ownership = new LeaseOwnership(operations);
        try { check(); }
        catch (RuntimeException | Error problem) {
            try { ownership.close(); } catch (RuntimeException | Error closing) { problem.addSuppressed(closing); }
            throw problem;
        }
    }

    public static VillageColdStartWindowNative open() {
        verifyPinnedClasses();
        MinecraftServer server = MinecraftServer.getServer();
        CraftEngine engine = CraftEngine.instance();
        BukkitWorldManager worlds = BukkitWorldManager.instance();
        require(server != null && server.getClass() == DedicatedServer.class && worlds != null,
                "Pinned initial dedicated-server roots are unavailable");
        return new VillageColdStartWindowNative(server, engine, worlds);
    }

    @Override public void check() {
        ownership.check();
        try { validateRoots(); }
        catch (RuntimeException | Error problem) { ownership.poison(problem); throw problem; }
    }

    @Override public Object nativeRegistryAccess() { check(); return registry; }
    @Override public Object nativeStructureTemplateManager() { check(); return templates; }
    @Override public void close() { ownership.close(); }

    private void validateRoots() {
        require(Thread.currentThread() == server.getRunningThread(), "Initial publication requires the actual native server thread");
        require(MinecraftServer.getServer() == server && server.registryAccess() == registry && server.getStructureManager() == templates,
                "Initial native server roots changed");
        require(CraftEngine.instance() == engine && engine.resourceOperations() == operations
                        && engine.worldManager() == worlds && BukkitWorldManager.instance() == worlds,
                "Initial CE roots changed");
        require(!Config.delayConfigurationLoad() && engine.isEnabling() && !engine.isReloading()
                        && !engine.isStopping() && !engine.isDisabled() && worlds.initialized() && ChunkGenerationStage.FEATURE.enabled(),
                "CE is outside its supported synchronous initial resource-ready phase");
        require(server.isRunning() && !server.hasStopped() && !server.isStopped()
                        && !server.getAllLevels().iterator().hasNext() && Bukkit.getWorlds().isEmpty(),
                "Initial publication requires no native levels and a running startup");
        require(initialAfterEnableStack(), "Publication is outside the pinned initial CE after-enable task invocation");
    }

    private static boolean initialAfterEnableStack() {
        return WALKER.walk(frames -> {
            List<StackWalker.StackFrame> selected = frames.limit(256).toList();
            int tasks = -1, enable = -1, initial = -1;
            for (int index = 0; index < selected.size(); index++) {
                StackWalker.StackFrame frame = selected.get(index);
                if (frame.getDeclaringClass() == PluginTaskRegistry.class && frame.getMethodName().equals("executeTasks")
                        && frame.getDescriptor().equals("()V") && frame.getByteCodeIndex() == 23) {
                    if (tasks != -1) return false;
                    tasks = index;
                }
                if (frame.getDeclaringClass() == CraftEngine.class && frame.getMethodName().equals("onPluginEnable")
                        && frame.getDescriptor().equals("()V")) {
                    if (enable != -1 || frame.getByteCodeIndex() != 264) return false;
                    enable = index;
                }
                if (frame.getDeclaringClass() == DedicatedServer.class && frame.getMethodName().equals("initServer")
                        && frame.getDescriptor().equals("()Z")) {
                    if (initial != -1) return false;
                    initial = index;
                }
            }
            return tasks >= 0 && enable == tasks + 1 && initial > enable;
        });
    }

    /** Hash actual defining-class resources, bounded before allocation; filenames are not version proof. */
    private static void verifyPinnedClasses() {
        String server = classDigest(MinecraftServer.class), dedicated = classDigest(DedicatedServer.class);
        require((server.equals(PAPER_SERVER) && dedicated.equals(PAPER_DEDICATED))
                        || (server.equals(FOLIA_SERVER) && dedicated.equals(FOLIA_DEDICATED)),
                "Unverified Paper/Folia server startup bytecode");
        pinned(CraftEngine.class, "dba6ce66ba3605dee91927aeb70f370a83efc3aa992ee48beb5fb9916795104a");
        pinned(PluginTaskRegistry.class, "89dcc2c1461b63e4aa2f78072be19da08bb837213d9c12d8e3d3300626fee096");
        pinned(Config.class, "eb259ddf80e3f5e882e6b8018f6085d9498a11ded3d59093a7b327b968c8e9ed");
        pinned(BukkitWorldManager.class, "1327cb9271c94ab8ad4407255d064803553efb3ba8283afd4b53153f1d8071cd");
        pinned(ChunkGenerationStage.class, "9f95694f71967b87e9804001d3748f2a1aac63ce51855d648eea98c4adb2be4f");
        pinned(ResourceOperationCoordinator.class, "82ab4c903cd74be04fd201c7edaffbc597eaf3f29258657ec75e727936123da5");
        pinned(ResourceOperationCoordinator.Lease.class, "b4291b2217fada04fb0982f49fed7b47f80d53f7b61d6aeffa70e24f00dffc23");
    }

    private static void pinned(Class<?> type, String sha256) {
        require(classDigest(type).equals(sha256), "Unverified CE cold-start bytecode: " + type.getName());
    }

    private static String classDigest(Class<?> type) {
        String path = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(path)) {
            require(input != null, "Missing cold-start defining-class resource: " + type.getName());
            byte[] bytes = input.readNBytes(MAX_CLASS_BYTES + 1);
            require(bytes.length > 0 && bytes.length <= MAX_CLASS_BYTES, "Cold-start class resource exceeds its bound");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot verify cold-start defining-class resource: " + type.getName(), failure);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
