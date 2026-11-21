package net.kaleidoscope.cookery.worldgen;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VillageStructureSourcesTest {
    private Map<String, byte[]> resources() throws IOException {
        var resources = new LinkedHashMap<String, byte[]>();
        for (String path : List.of("/village-loot.json", "/village/source/plains_kitchen.nbt",
                "/village/source/snowy_kitchen.nbt", "/village/source/savanna_kitchen.nbt",
                "/village/source/desert_kitchen.nbt", "/village/source/taiga_kitchen.nbt")) {
            try (InputStream stream = getClass().getResourceAsStream(path)) {
                assertNotNull(stream, path);
                resources.put(path, stream.readAllBytes());
            }
        }
        return resources;
    }

    private VillageStructureSources load(Map<String, byte[]> resources) {
        return VillageStructureSources.load(path -> {
            byte[] bytes = resources.get(path);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        });
    }

    @Test void allFiveBundledTemplatesMatchThePinnedSourceAndCompleteMetadata() throws Exception {
        var sources = VillageStructureSources.bundled();
        var resources = resources();
        var manifest = JsonParser.parseString(new String(resources.get("/village-loot.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(VillageStructureSources.BIOMES, sources.templates().stream().map(VillageStructureSources.Template::biome).toList());
        for (var source : sources.templates()) {
            byte[] raw = source.compressedNbt();
            String filename = source.biome() + "_kitchen.nbt";
            String path = VillageStructureSources.SOURCE_ROOT + filename;
            assertEquals(VillageLootSpec.SOURCE_COMMIT, source.sourceCommit());
            assertEquals(path, source.sourcePath());
            assertEquals(manifest.getAsJsonObject("sourceFiles").get(path).getAsString(), source.sourceSha256());
            assertEquals(source.sourceSha256(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
            assertArrayEquals(resources.get(VillageStructureSources.RESOURCE_ROOT + filename), raw);
            assertEquals(manifest.getAsJsonObject("structures").getAsJsonObject(source.biome()), source.sourceMetadata());
            assertEquals(3465, source.sourceMetadata().get("dataVersion").getAsInt());
            assertEquals(0x1f, raw[0] & 0xff); assertEquals(0x8b, raw[1] & 0xff);
        }
        assertThrows(IllegalArgumentException.class, () -> sources.template("../plains"));
    }

    @Test void returnedBytesMetadataAndCollectionCannotMutateTheArchive() throws Exception {
        var inputs = resources();
        var sources = load(inputs);
        var plains = sources.template("plains");
        byte[] expected = plains.compressedNbt();
        byte[] returned = plains.compressedNbt(); returned[0] ^= 1;
        inputs.get("/village/source/plains_kitchen.nbt")[1] ^= 1;
        var metadata = plains.sourceMetadata(); metadata.addProperty("dataVersion", -1);
        metadata.getAsJsonArray("customBlocks").remove(0);
        assertArrayEquals(expected, plains.compressedNbt());
        assertNotSame(expected, plains.compressedNbt());
        assertEquals(3465, plains.sourceMetadata().get("dataVersion").getAsInt());
        assertNotEquals(metadata, plains.sourceMetadata());
        assertThrows(UnsupportedOperationException.class, () -> sources.templates().clear());
    }

    @Test void everyMissingOrMutatedTemplateRejectsTheWholeArchive() throws Exception {
        var originals = resources();
        for (String biome : VillageStructureSources.BIOMES) {
            String path = VillageStructureSources.RESOURCE_ROOT + biome + "_kitchen.nbt";
            var missing = new LinkedHashMap<>(originals); missing.remove(path);
            assertThrows(IllegalStateException.class, () -> load(missing), biome);
            for (byte[] changed : List.of(new byte[0], Arrays.copyOf(originals.get(path), originals.get(path).length - 1),
                    Arrays.copyOf(originals.get(path), originals.get(path).length + 1))) {
                var corrupt = new LinkedHashMap<>(originals); corrupt.put(path, changed);
                assertThrows(IllegalArgumentException.class, () -> load(corrupt), biome);
            }
            var flipped = originals.get(path).clone(); flipped[flipped.length / 2] ^= 1;
            var corrupt = new LinkedHashMap<>(originals); corrupt.put(path, flipped);
            assertThrows(IllegalArgumentException.class, () -> load(corrupt), biome);
        }
    }

    @Test void pinnedProvenanceCannotBeChanged() throws Exception {
        var changed = resources();
        String spec = new String(changed.get("/village-loot.json"), StandardCharsets.UTF_8);
        assertTrue(spec.contains(VillageLootSpec.SOURCE_COMMIT));
        changed.put("/village-loot.json", spec.replace(VillageLootSpec.SOURCE_COMMIT, "0".repeat(40)).getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> load(changed));
        changed.remove("/village-loot.json");
        assertThrows(IllegalStateException.class, () -> load(changed));
    }

    @Test void changingAStoredTemplateAndItsManifestHashCannotRepinTheSource() throws Exception {
        var changed = resources();
        String path = "/village/source/plains_kitchen.nbt";
        byte[] raw = changed.get(path).clone(); raw[raw.length / 2] ^= 1;
        String newHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        String originalHash = VillageStructureSources.bundled().template("plains").sourceSha256();
        String spec = new String(changed.get("/village-loot.json"), StandardCharsets.UTF_8);
        assertTrue(spec.contains(originalHash));
        changed.put(path, raw);
        changed.put("/village-loot.json", spec.replace(originalHash, newHash).getBytes(StandardCharsets.UTF_8));
        var failure = assertThrows(IllegalArgumentException.class, () -> load(changed));
        assertTrue(failure.getMessage().contains("specification digest"));
    }

    @Test void oversizedStreamsAreBoundedAndClosedBeforeAnyArchiveIsPublished() throws Exception {
        var originals = resources();
        for (String target : List.of("/village-loot.json", "/village/source/taiga_kitchen.nbt")) {
            var oversized = new InputStream() {
                int reads; boolean closed;
                @Override public int read() { reads++; return 0; }
                @Override public void close() { closed = true; }
            };
            var failure = assertThrows(IllegalArgumentException.class, () -> VillageStructureSources.load(path ->
                    path.equals(target) ? oversized : new ByteArrayInputStream(originals.get(path))));
            assertTrue(failure.getMessage().contains("byte limit"));
            int limit = target.endsWith(".json") ? VillageStructureSources.MAX_SPEC_BYTES : VillageStructureSources.MAX_TEMPLATE_BYTES;
            assertEquals(limit + 1, oversized.reads);
            assertTrue(oversized.closed);
        }
    }

    @Test void ioFailureClosesTheStreamAndDoesNotReturnAPartialArchive() {
        var broken = new InputStream() {
            boolean closed;
            @Override public int read() throws IOException { throw new IOException("fixture failure"); }
            @Override public void close() { closed = true; }
        };
        var failure = assertThrows(IllegalStateException.class, () -> VillageStructureSources.load(path -> broken));
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(broken.closed);
    }
}
