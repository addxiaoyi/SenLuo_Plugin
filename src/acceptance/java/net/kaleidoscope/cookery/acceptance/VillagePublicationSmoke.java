package net.kaleidoscope.cookery.acceptance;

import com.google.gson.GsonBuilder;
import net.kaleidoscope.cookery.worldgen.VillageColdStartWindow;
import net.kaleidoscope.cookery.worldgen.VillagePoolPlan;
import net.kaleidoscope.cookery.worldgen.VillagePoolPreparer;
import net.kaleidoscope.cookery.worldgen.VillagePublicationTransaction;
import net.kaleidoscope.cookery.worldgen.VillagePublisher;
import net.kaleidoscope.cookery.worldgen.VillageStructureSources;
import net.kaleidoscope.cookery.worldgen.VillageStructureCodec;
import net.kaleidoscope.cookery.worldgen.VillageTemplateMigrator;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
import net.momirealms.craftengine.core.plugin.compatibility.PluginTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Opt-in isolated acceptance only: exercises the real initial-startup publisher and deliberately
 * rejects its final cross-validation, requiring a complete rollback before the first world exists.
 * It installs no natural generation. An unexpected indeterminate failure does not have a proven
 * pre-world abort boundary; the whole isolated candidate is failed and its evidence must be kept.
 */
final class VillagePublicationSmoke {
    private static final Map<JavaPlugin, VillagePublicationSmoke> REGISTERED = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final int MAX_REPORT_BYTES = 4_194_304;
    private static final int MAX_LIST_ENTRIES = 8_192;
    private static final int MAX_CAPTURED_LIST_ENTRIES = 16_384;
    private final JavaPlugin plugin;
    private final String runId;
    private final String registeredAt = Instant.now().toString();
    private final Map<String, Object> artifacts;
    private final Map<String, Class<?>> artifactClasses;
    private final java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean finished;

    private VillagePublicationSmoke(JavaPlugin plugin, String runId) {
        this.plugin = plugin; this.runId = runId;
        try {
            Class<?> server = Class.forName("net.minecraft.server.MinecraftServer", false, CraftEngine.class.getClassLoader());
            artifactClasses = Map.of("production", VillagePublisher.class, "probe", VillagePublicationSmoke.class,
                    "craftEngine", CraftEngine.class, "server", server);
            var values = new LinkedHashMap<String, Object>(); artifactClasses.forEach((name, type) -> values.put(name, artifact(type)));
            artifacts = Collections.unmodifiableMap(values);
        } catch (ClassNotFoundException failure) { throw new IllegalStateException("Cannot bind actual native server JAR", failure); }
    }

    static void register(JavaPlugin plugin) {
        if (!plugin.getConfig().getBoolean("village-publication-smoke.enabled", false)) return;
        String runId = plugin.getConfig().getString("village-publication-smoke.run-id", "");
        check(UUID.fromString(runId).toString().equals(runId), "A canonical unique village smoke run UUID is required");
        var smoke = new VillagePublicationSmoke(plugin, runId);
        synchronized (REGISTERED) { check(!REGISTERED.containsKey(plugin), "Village publication smoke registered twice"); REGISTERED.put(plugin, smoke); }
        Map<String, Object> receipt = smoke.base();
        receipt.put("status", "NOT_RUN"); receipt.put("passed", false);
        receipt.put("phase", "REGISTERED_DURING_ON_LOAD");
        smoke.write("-registration", receipt);
        CraftEngine.instance().afterEnableTaskRegistry().registerTask(PluginTask.create(smoke::run, plugin.getName()));
    }

    static void checkOnEnable(JavaPlugin plugin) {
        VillagePublicationSmoke smoke = REGISTERED.get(plugin);
        if (smoke == null || smoke.finished) return;
        Map<String, Object> report = smoke.base();
        report.put("status", "FAIL"); report.put("passed", false); report.put("completedAt", Instant.now().toString());
        report.put("failure", Map.of("type", "MissingInitialAfterEnableCallback", "message", "The registered cold-start task did not complete before probe onEnable"));
        smoke.write("-missing-after-enable", report);
        plugin.getLogger().severe("VILLAGE_PUBLICATION_SMOKE missing initial after-enable report for " + smoke.runId);
    }

    private void run() {
        if (!ran.compareAndSet(false, true)) {
            Map<String, Object> duplicate = base(); duplicate.put("status", "FAIL"); duplicate.put("passed", false);
            duplicate.put("completedAt", Instant.now().toString());
            duplicate.put("failure", Map.of("type", "DuplicateInitialCallback", "message", "The initial publication callback is one-shot"));
            write("-duplicate-callback-" + UUID.randomUUID(), duplicate);
            plugin.getLogger().severe("VILLAGE_PUBLICATION_SMOKE duplicate initial callback for " + runId); return;
        }
        Map<String, Object> report = base();
        report.put("phase", "CE_INITIAL_AFTER_ENABLE"); report.put("status", "FAIL"); report.put("passed", false);
        report.put("publishedWitnessObserved", false); report.put("sentinelObserved", false); report.put("cleanRollback", false);
        report.put("fullOriginalsRestored", false); report.put("scopeClosed", false); report.put("writerAvailableAfterClose", false);
        report.put("nativeWorldCountBefore", -1);
        var rows = new ArrayList<Map<String, Object>>(); report.put("slots", rows);
        VillageColdStartWindow window = null; Throwable failure = null;
        try {
            report.put("nativeWorldCountBefore", nativeWorldCount());
            check(((Integer) report.get("nativeWorldCountBefore")) == 0, "Cold-start smoke already has native worlds");
            checkArtifacts();
            check(Bukkit.getWorlds().isEmpty(), "Cold-start smoke already has Bukkit worlds");
            window = VillageColdStartWindow.open(Bukkit.getMinecraftVersion());
            final VillageColdStartWindow owned = window;
            window.check();
            try (var unexpected = CraftEngine.instance().resourceOperations().acquire()) {
                throw new IllegalStateException("Cold-start window failed to retain its actual CE lease");
            } catch (ResourceOperationCoordinator.BusyException expected) { report.put("writerExcludedInsideWindow", true); }
            Object registry = window.nativeRegistryAccess(), manager = window.nativeStructureTemplateManager();
            var sources = VillageStructureSources.bundled().templates();
            var migrated = new ArrayList<VillageTemplateMigrator.Prepared>();
            var sourceRows = new ArrayList<Map<String, Object>>();
            for (var source : sources) {
                window.check(); var value = VillageTemplateMigrator.forMinecraft(Bukkit.getMinecraftVersion()).prepare(source, manager);
                migrated.add(value);
                sourceRows.add(Map.of("biome", source.biome(), "location", value.location(), "sourceCommit", source.sourceCommit(),
                        "sourceSha256", source.sourceSha256(), "targetDataVersion", value.targetDataVersion()));
            }
            report.put("sourceTemplates", sourceRows);
            VillagePoolPreparer.Prepared pools = VillagePoolPreparer.forMinecraft(Bukkit.getMinecraftVersion()).prepare(registry);
            check(pools.processorChangeRequired() && pools.plan().pools().values().stream().allMatch(VillagePoolPlan.Pool::appended),
                    "Smoke requires a fresh empty native processor and all five missing kitchens");
            var captured = capture(manager, migrated, pools, rows);
            MapSnapshot wholeCache = MapSnapshot.capture(field(manager, "structureRepository"));
            report.put("templateCacheBefore", wholeCache.describe());
            check(captured.size() == 21, "Incomplete native publication observation set");
            int changed = (int) captured.stream().filter(Snapshot::changed).count();
            check(changed >= 16, "Fresh candidate does not exercise cache, processor and all five pool lists");
            report.put("expectedChangedSlots", changed); report.put("expectedNoOpSlots", 21 - changed);
            check(new GsonBuilder().serializeNulls().create().toJson(report).getBytes(StandardCharsets.UTF_8).length <= MAX_REPORT_BYTES / 4,
                    "Full pre-publication evidence leaves insufficient room for complete published/restored witnesses");
            var sentinel = new IntentionalRollback();
            var transaction = VillagePublisher.forMinecraft(Bukkit.getMinecraftVersion()).stage(registry, manager, migrated, pools, window, () -> {
                owned.check();
                for (Snapshot snapshot : captured) snapshot.witness(true);
                wholeCache.checkUnrelated(migrated, manager);
                report.put("structureCodec", VillageStructureCodec.forMinecraft(Bukkit.getMinecraftVersion())
                        .inspect(registry, manager, migrated, pools, owned));
                check(Bukkit.getWorlds().isEmpty(), "A world appeared during native publication");
                report.put("publishedWitnessObserved", true);
                throw sentinel;
            });
            try {
                transaction.publish();
                throw new IllegalStateException("Intentional rollback cross-validation was not invoked");
            } catch (VillagePublicationTransaction.PublicationFailure rolledBack) {
                report.put("transactionStatus", rolledBack.status().name());
                report.put("rollbackFailures", rolledBack.rollbackFailures().stream().map(row -> Map.of("edit", row.edit(), "phase", row.phase(),
                        "cause", bounded(row.cause().toString()))).toList());
                report.put("unconfirmedSlots", rolledBack.unconfirmedSlots());
                check(rolledBack.getCause() == sentinel, "Native publication failed before the intentional final rejection");
                report.put("sentinelObserved", true);
                check(rolledBack.status() == VillagePublicationTransaction.Status.ROLLED_BACK
                        && rolledBack.rollbackFailures().isEmpty() && rolledBack.unconfirmedSlots().isEmpty(), "Native publication did not roll back cleanly");
                report.put("cleanRollback", true);
            }
            for (Snapshot snapshot : captured) snapshot.witness(false);
            wholeCache.checkRestored(manager); report.put("templateCacheRestored", wholeCache.describe());
            window.check(); check(Bukkit.getWorlds().isEmpty(), "A world appeared before original-state verification");
            report.put("fullOriginalsRestored", true);
            checkArtifacts();
        } catch (Throwable problem) {
            failure = problem;
        } finally {
            if (window != null) {
                try {
                    window.close();
                    boolean rejected = false;
                    try { window.check(); } catch (IllegalStateException expectedClosed) { rejected = true; }
                    check(rejected, "Closed native cold-start window still admits checks"); report.put("scopeClosed", true);
                }
                catch (Throwable closing) { if (failure == null) failure = closing; else failure.addSuppressed(closing); }
            }
            try (var writer = CraftEngine.instance().resourceOperations().acquire()) {
                report.put("writerAvailableAfterClose", true);
            } catch (Throwable writerFailure) { if (failure == null) failure = writerFailure; else failure.addSuppressed(writerFailure); }
            int finalNativeWorlds = -1;
            try { finalNativeWorlds = nativeWorldCount(); }
            catch (Throwable observation) { if (failure == null) failure = observation; else failure.addSuppressed(observation); }
            report.put("nativeWorldCountAfter", finalNativeWorlds);
            if (!Bukkit.getWorlds().isEmpty() || finalNativeWorlds != 0) {
                var worldFailure = new IllegalStateException("Cold-start smoke completed after worlds became visible");
                if (failure == null) failure = worldFailure; else failure.addSuppressed(worldFailure);
            }
            if (failure == null) {
                report.put("failure", null); report.put("status", "PASS"); report.put("passed", true);
            } else {
                var problem = new LinkedHashMap<String, Object>(); problem.put("type", failure.getClass().getName());
                problem.put("message", bounded(failure.toString()));
                problem.put("suppressed", java.util.Arrays.stream(failure.getSuppressed()).limit(32).map(value -> bounded(value.toString())).toList());
                report.put("failure", problem);
                plugin.getLogger().log(Level.SEVERE, "VILLAGE_PUBLICATION_SMOKE failed; isolated candidate must be retained, no controlled startup abort is claimed", failure);
            }
            report.put("completedAt", Instant.now().toString());
            try { write("", report); finished = true; }
            catch (Throwable outputFailure) { plugin.getLogger().log(Level.SEVERE, "Cannot publish immutable village smoke report", outputFailure); }
            plugin.getLogger().info("VILLAGE_PUBLICATION_SMOKE runId=" + runId + " passed=" + report.get("passed"));
        }
    }

    private static List<Snapshot> capture(Object manager, List<VillageTemplateMigrator.Prepared> templates,
                                           VillagePoolPreparer.Prepared prepared, List<Map<String, Object>> rows) {
        var snapshots = new ArrayList<Snapshot>();
        var budget = new CaptureBudget();
        @SuppressWarnings("unchecked") Map<Object, Object> cache = (Map<Object, Object>) field(manager, "structureRepository");
        for (var template : templates) {
            Object key = invokeStatic(manager.getClass().getClassLoader(), "net.minecraft.resources.Identifier", "parse", String.class, template.location());
            State before = State.cache(cache.containsKey(key), cache.get(key));
            check(!before.present || before.reference instanceof Optional<?> && ((Optional<?>) before.reference).isEmpty(), "Smoke requires unpopulated kitchen template keys");
            Supplier<State> reader = () -> State.cache(cache.containsKey(key), cache.get(key));
            // Staged Optional identity is allocated privately by the publisher: require its exact native template, then capture that Optional for repeat verification.
            snapshots.add(new Snapshot("template:" + template.location(), "CACHE_ENTRY", reader, before,
                    State.cache(true, Optional.of(template.nativeTemplate())), template.nativeTemplate(), rows));
        }
        Object originalProcessor = prepared.expectedProcessorValue(), nextProcessor = prepared.stagedProcessorValue();
        budget.include(field(originalProcessor, "list")); budget.include(field(nextProcessor, "list"));
        snapshots.add(new Snapshot("processor:list", "REFERENCE", () -> State.reference(field(originalProcessor, "list")),
                State.reference(field(originalProcessor, "list")), State.reference(field(nextProcessor, "list")), null, rows));
        for (String key : VillagePoolPlan.POOL_KEYS) {
            Object original = prepared.expectedPoolValues().get(key), candidate = prepared.stagedPoolValues().get(key);
            for (String property : List.of("rawTemplates", "templates", "maxSize")) {
                boolean integer = property.equals("maxSize");
                if (!integer) { budget.include(field(original, property)); budget.include(field(candidate, property)); }
                Supplier<State> reader = () -> integer ? State.integer((Integer) field(original, property)) : State.reference(field(original, property));
                State next = integer ? State.integer(Integer.MIN_VALUE) : State.reference(field(candidate, property));
                String label = switch (property) { case "rawTemplates" -> "raw"; case "templates" -> "expanded"; default -> "maximum"; };
                snapshots.add(new Snapshot("pool:" + key + ":" + label, integer ? "INTEGER" : "REFERENCE", reader, reader.get(), next, null, rows));
            }
        }
        return List.copyOf(snapshots);
    }
    private static final class CaptureBudget {
        int entries;
        void include(Object value) {
            check(value instanceof List<?>, "Expected native list before observation capture");
            int size = ((List<?>) value).size();
            check(size <= MAX_LIST_ENTRIES && entries <= MAX_CAPTURED_LIST_ENTRIES - size, "Full native list observation exceeds its diagnostic budget");
            entries += size;
        }
    }

    private static final class Snapshot {
        final String name, kind;
        final Supplier<State> reader;
        final State before, staged;
        final Object expectedTemplate;
        final Map<String, Object> row = new LinkedHashMap<>();
        State witnessed;
        Snapshot(String name, String kind, Supplier<State> reader, State before, State staged, Object expectedTemplate,
                 List<Map<String, Object>> rows) {
            this.name = name; this.kind = kind; this.reader = reader; this.before = before; this.staged = staged; this.expectedTemplate = expectedTemplate;
            row.put("name", name); row.put("kind", kind); row.put("before", before.describe()); row.put("staged", staged.describe());
            row.put("changed", changed()); rows.add(row);
        }
        boolean changed() { return expectedTemplate != null || !before.same(staged); }
        void witness(boolean published) {
            State actual = reader.get();
            if (published && expectedTemplate != null) {
                check(actual.present && actual.reference instanceof Optional<?> && ((Optional<?>) actual.reference).orElse(null) == expectedTemplate,
                        "Published native template does not match staged identity: " + name);
                witnessed = actual;
                row.put("staged", actual.describe());
            } else {
                State expected = published ? staged : before;
                check(expected.same(actual), "Native publication slot did not " + (published ? "publish" : "restore") + ": " + name);
                actual.checkContents(expected);
            }
            row.put(published ? "publishedWitness" : "restoredWitness", actual.describe());
        }
    }
    private static final class State {
        final boolean present;
        final Object reference;
        final Integer integer;
        final List<Object> contents;
        private State(boolean present, Object reference, Integer integer) {
            this.present = present; this.reference = reference; this.integer = integer;
            if (reference instanceof List<?> list) {
                check(list.size() <= MAX_LIST_ENTRIES, "Observed native list exceeds its diagnostic bound");
                contents = new ArrayList<>(list);
            } else contents = null;
        }
        static State reference(Object value) { return new State(true, value, null); }
        static State cache(boolean present, Object value) { return new State(present, value, null); }
        static State integer(int value) { return new State(true, null, value); }
        boolean same(State other) { return integer != null ? integer.equals(other.integer) : other.integer == null && present == other.present && reference == other.reference; }
        void checkContents(State expected) {
            if (expected.contents == null) return;
            check(contents != null && contents.size() == expected.contents.size(), "Native list size changed despite original identity");
            for (int index = 0; index < contents.size(); index++) check(contents.get(index) == expected.contents.get(index), "Native list contents changed despite original identity");
        }
        Map<String, Object> describe() {
            if (integer != null) return Map.of("value", integer);
            var value = new LinkedHashMap<String, Object>(); value.put("present", present);
            value.put("type", present ? reference == null ? "null" : reference.getClass().getName() : "ABSENT"); value.put("identity", identity(reference));
            if (reference instanceof Optional<?> optional) value.put("templateIdentity", identity(optional.orElse(null)));
            if (contents != null) {
                value.put("size", contents.size()); value.put("contents", contents.stream().map(State::entry).toList());
                value.put("contentsSha256", sha256(String.join("\n", contents.stream().map(VillagePublicationSmoke::identity).toList()).getBytes(StandardCharsets.UTF_8)));
            }
            return value;
        }
        private static Object entry(Object value) {
            if (value != null && value.getClass().getName().equals("com.mojang.datafixers.util.Pair")) {
                try {
                    Object element = value.getClass().getMethod("getFirst").invoke(value), weight = value.getClass().getMethod("getSecond").invoke(value);
                    return Map.of("identity", identity(value), "elementIdentity", identity(element), "weight", weight);
                } catch (ReflectiveOperationException problem) { throw new IllegalStateException("Cannot observe native weighted pool pair", problem); }
            }
            return identity(value);
        }
    }

    private record MapSnapshot(Object identity, Map<Object, Object> entries) {
        static MapSnapshot capture(Object value) {
            check(value != null && value.getClass() == java.util.concurrent.ConcurrentHashMap.class, "Unexpected actual native template cache");
            Map<?, ?> map = (Map<?, ?>) value; check(map.size() <= 4_096, "Native template cache observation exceeds bound");
            var copy = new LinkedHashMap<Object, Object>(); map.forEach(copy::put); return new MapSnapshot(value, Collections.unmodifiableMap(copy));
        }
        void checkRestored(Object manager) {
            check(field(manager, "structureRepository") == identity, "Native template cache owner field was replaced");
            Map<?, ?> actual = (Map<?, ?>) identity; check(actual.size() == entries.size(), "Full native template cache size did not restore");
            entries.forEach((key, value) -> check(actual.containsKey(key) && actual.get(key) == value, "Full native template cache entry did not restore"));
        }
        void checkUnrelated(List<VillageTemplateMigrator.Prepared> templates, Object manager) {
            check(field(manager, "structureRepository") == identity, "Native template cache owner field was replaced during publication");
            Map<?, ?> actual = (Map<?, ?>) identity;
            long newKeys = templates.stream().filter(template -> entries.keySet().stream().noneMatch(key -> key.toString().equals(template.location()))).count();
            check(actual.size() == entries.size() + newKeys, "Native publication altered unrelated template cache size");
            entries.forEach((key, value) -> {
                if (templates.stream().noneMatch(template -> key.toString().equals(template.location())))
                    check(actual.get(key) == value, "Native publication altered an unrelated template cache entry");
            });
        }
        Map<String, Object> describe() {
            return Map.of("identity", VillagePublicationSmoke.identity(identity), "size", entries.size(), "entries", entries.entrySet().stream()
                    .map(entry -> Map.of("key", entry.getKey().toString(), "value", State.cache(true, entry.getValue()).describe())).toList());
        }
    }

    private Map<String, Object> base() {
        var report = new LinkedHashMap<String, Object>();
        report.put("schema", "village-publication-smoke-v1"); report.put("runId", runId); report.put("registeredAt", registeredAt);
        report.put("serverVersion", Bukkit.getVersion()); report.put("artifacts", artifacts);
        report.put("minecraftVersion", Bukkit.getMinecraftVersion()); report.put("platform", Bukkit.getName());
        report.put("naturalVillageIntegration", "NOT_ACTIVATED"); report.put("runtimeScope", "NATIVE_COLD_START_PUBLICATION_ROLLBACK_ONLY");
        report.put("packIdentity", "NOT_CERTIFIED_BY_THIS_PROBE");
        report.put("controlledFailureBoundary", "UNPROVEN_IF_UNEXPECTED_PARTIAL_STATE"); return report;
    }
    private void checkArtifacts() {
        artifactClasses.forEach((name, type) -> check(artifacts.get(name).equals(artifact(type)), "Actual defining JAR changed since registration: " + name));
    }
    private void write(String suffix, Map<String, Object> report) {
        Path directory = plugin.getDataFolder().toPath();
        Path output = directory.resolve("village-publication-smoke-" + runId + suffix + ".json");
        Path temporary = directory.resolve(output.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            Files.createDirectories(directory);
            byte[] bytes = new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report).getBytes(StandardCharsets.UTF_8);
            check(bytes.length <= MAX_REPORT_BYTES, "Village publication report exceeds its bound");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            // Atomic no-overwrite publication: unlike ATOMIC_MOVE, createLink must fail if output exists.
            Files.createLink(output, temporary);
            try { Files.delete(temporary); }
            catch (Exception cleanup) { plugin.getLogger().log(Level.WARNING, "Published village report; retained its identical temporary hard link " + temporary, cleanup); }
        } catch (Exception problem) { throw new IllegalStateException("Cannot atomically publish new village report " + output, problem); }
    }
    private static Map<String, Object> artifact(Class<?> type) {
        try {
            Path path = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            var before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            check(before.isRegularFile() && before.size() > 0 && before.size() <= 134_217_728, "Acceptance requires a bounded actual defining JAR: " + type.getName());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long read = 0;
            try (InputStream input = Files.newInputStream(path)) {
                byte[] block = new byte[65_536];
                for (int size; (size = input.read(block)) >= 0;) if (size > 0) {
                    check(read <= 134_217_728 - size, "Defining JAR grew beyond its hash bound"); read += size; digest.update(block, 0, size);
                }
            }
            var after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            check(after.isRegularFile() && read == before.size() && after.size() == before.size()
                    && Objects.equals(after.fileKey(), before.fileKey()) && after.lastModifiedTime().equals(before.lastModifiedTime()),
                    "Defining JAR changed during hash observation");
            return Map.of("path", path.toString(), "bytes", read, "sha256", HexFormat.of().formatHex(digest.digest()));
        } catch (Exception failure) { throw new IllegalStateException("Cannot bind actual defining JAR " + type.getName(), failure); }
    }
    private static Object field(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot observe native field " + name, failure); }
    }
    private static Object invokeStatic(ClassLoader loader, String owner, String name, Class<?> parameter, Object value) {
        try { Method method = Class.forName(owner, false, loader).getMethod(name, parameter); return method.invoke(null, value); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot resolve native template identity", failure); }
    }
    private static int nativeWorldCount() {
        try {
            Class<?> serverClass = Class.forName("net.minecraft.server.MinecraftServer", false, CraftEngine.class.getClassLoader());
            Object server = serverClass.getMethod("getServer").invoke(null);
            check(server != null, "Native server is unavailable for world observation");
            Object levels = serverClass.getMethod("getAllLevels").invoke(server);
            check(levels instanceof Iterable<?>, "Unexpected native level collection");
            int count = 0;
            for (Object ignored : (Iterable<?>) levels) { check(count < 65_536, "Native level observation exceeds bound"); count++; }
            return count;
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot observe actual native worlds", failure); }
    }
    private static String identity(Object value) { return value == null ? "null" : value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value)); }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private static String bounded(String value) { return value.length() <= 4_096 ? value : value.substring(0, 4_096); }
    private static void check(boolean condition, String failure) { if (!condition) throw new IllegalStateException(failure); }
    private static final class IntentionalRollback extends IllegalStateException {
        private IntentionalRollback() { super("Intentional read-only final cross-validation rejection; require complete native rollback"); }
    }
}
