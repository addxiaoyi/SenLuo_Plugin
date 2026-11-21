package net.kaleidoscope.cookery.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Exact source archives from KaleidoscopeCookery; this loader never places or migrates blocks. */
public final class VillageStructureSources {
    static final List<String> BIOMES = List.of("plains", "snowy", "savanna", "desert", "taiga");
    static final String RESOURCE_ROOT = "/village/source/";
    static final String SOURCE_ROOT = "src/main/resources/data/kaleidoscope_cookery/structures/village/houses/";
    static final int MAX_SPEC_BYTES = 1_048_576;
    static final int MAX_TEMPLATE_BYTES = 1_048_576;

    @FunctionalInterface
    interface Resources { InputStream open(String absolutePath) throws IOException; }

    /** Provenance and the complete pinned manifest entry accompany each unmodified gzip NBT. */
    public static final class Template {
        private final String biome;
        private final String sourceCommit;
        private final String sourcePath;
        private final String sourceSha256;
        private final JsonObject sourceMetadata;
        private final byte[] compressedNbt;

        private Template(String biome, String sourcePath, String sourceSha256, JsonObject metadata, byte[] bytes) {
            this.biome = biome;
            this.sourceCommit = VillageLootSpec.SOURCE_COMMIT;
            this.sourcePath = sourcePath;
            this.sourceSha256 = sourceSha256;
            this.sourceMetadata = metadata.deepCopy();
            this.compressedNbt = bytes.clone();
        }

        public String biome() { return biome; }
        public String sourceCommit() { return sourceCommit; }
        public String sourcePath() { return sourcePath; }
        public String sourceSha256() { return sourceSha256; }
        public JsonObject sourceMetadata() { return sourceMetadata.deepCopy(); }
        public byte[] compressedNbt() { return compressedNbt.clone(); }
    }

    private final List<Template> templates;

    private VillageStructureSources(List<Template> templates) { this.templates = List.copyOf(templates); }

    public List<Template> templates() { return templates; }

    public Template template(String biome) {
        return templates.stream().filter(source -> source.biome().equals(biome)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown village source biome: " + biome));
    }

    public static VillageStructureSources bundled() {
        return load(VillageStructureSources.class::getResourceAsStream);
    }

    static VillageStructureSources load(Resources resources) {
        byte[] specBytes = readBounded(resources, "/village-loot.json", MAX_SPEC_BYTES);
        String specJson = new String(specBytes, StandardCharsets.UTF_8);
        // Reuse the whole-resource digest and source-commit pin before trusting any JSON hash.
        VillageLootSpec.parse(specJson);
        JsonObject root = JsonParser.parseString(specJson).getAsJsonObject();
        JsonObject structures = root.getAsJsonObject("structures");
        JsonObject sourceFiles = root.getAsJsonObject("sourceFiles");
        VillageLootSpec.require(structures.keySet().equals(Set.copyOf(BIOMES)), "Invalid village source biome set");
        var templates = new ArrayList<Template>();
        for (String biome : BIOMES) {
            String filename = biome + "_kitchen.nbt";
            String sourcePath = SOURCE_ROOT + filename;
            JsonObject metadata = structures.getAsJsonObject(biome);
            String expected = sourceFiles.get(sourcePath).getAsString();
            VillageLootSpec.require(expected.equals(metadata.get("sourceSha256").getAsString()),
                    "Village source metadata digest differs: " + biome);
            byte[] bytes = readBounded(resources, RESOURCE_ROOT + filename, MAX_TEMPLATE_BYTES);
            VillageLootSpec.require(expected.equals(sha256(bytes)), "Unexpected village source template digest: " + biome);
            templates.add(new Template(biome, sourcePath, expected, metadata, bytes));
        }
        return new VillageStructureSources(templates);
    }

    private static byte[] readBounded(Resources resources, String path, int limit) {
        try (InputStream stream = resources.open(path)) {
            if (stream == null) throw new IllegalStateException("Missing village source resource: " + path);
            byte[] bytes = stream.readNBytes(limit + 1);
            VillageLootSpec.require(bytes.length <= limit, "Village source resource exceeds byte limit: " + path);
            return bytes;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read village source resource: " + path, failure);
        }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }
}
