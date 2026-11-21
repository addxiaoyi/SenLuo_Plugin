import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Offline probe for the official, obfuscated Minecraft 1.21.11 client JAR. Run in Java 21 source-file mode. */
public class VanillaPack12111 {
    private static final azn CLIENT_RESOURCES = azn.a; // PackType.CLIENT_RESOURCES

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: VanillaPack12111.java <report.json> <hosted.zip> ...");
        w.a(); // SharedConstants.tryDetectVersion
        if (!w.b().b().equals("1.21.11")) throw new IllegalArgumentException("Expected Minecraft 1.21.11");
        amv.a(); // Bootstrap.bootStrap (built-in registries)
        get.a(); // ClientBootstrap.bootstrap (item model codecs)
        // Options registers the default key mappings used by the vanilla keybind_down codec.
        Path emptyOptions = Files.createTempDirectory("senluo-vanilla-options-");
        try {
            new gfo(null, emptyOptions.toFile());
        } finally {
            Files.deleteIfExists(emptyOptions);
        }
        ImageIO.setUseCache(false);
        var reports = new ArrayList<Map<String, Object>>();
        for (int i = 1; i < args.length; i++) reports.add(check(Path.of(args[i])));
        Files.writeString(Path.of(args[0]), new GsonBuilder().setPrettyPrinting().create().toJson(reports) + "\n");
    }

    private static Map<String, Object> check(Path path) throws Exception {
        // Pack.readPackMetadata selects overlays for this version; FileResourcesSupplier.openFull applies them.
        var supplier = new azi.a(path);
        var info = new azk("server-pack", yh.b("Server pack"), bal.f, Optional.empty());
        var metadata = bah.a(info, supplier, w.b().a(CLIENT_RESOURCES), CLIENT_RESOURCES);
        if (metadata == null || !metadata.b().a()) throw new IOException("Incompatible pack: " + path);
        var counts = new TreeMap<String, Integer>();
        var namespaces = new TreeSet<String>();
        var errors = new ArrayList<String>();
        int checked = 0;
        try (var zip = new ZipFile(path.toFile()); var resources = supplier.a(info, metadata)) {
            // Assemble the expected view independently from the ZIP. Check the bytes of each resource via the client API.
            var expected = new TreeMap<String, ZipEntry>();
            var prefixes = new ArrayList<>(List.of("assets/"));
            for (String overlay : metadata.d()) prefixes.add(overlay + "/assets/");
            for (String prefix : prefixes) {
                zip.stream().filter(e -> !e.isDirectory() && e.getName().startsWith(prefix))
                        .forEach(e -> expected.put(e.getName().substring(prefix.length()), e));
            }
            for (var entry : expected.entrySet()) {
                String[] parts = entry.getKey().split("/", 2);
                var id = amo.a(parts[0], parts[1]); // Identifier.fromNamespaceAndPath
                var resource = resources.a(CLIENT_RESOURCES, id);
                if (resource == null) throw new IOException("Client cannot open " + id);
                try (var in = resource.get(); var reference = zip.getInputStream(entry.getValue())) {
                    byte[] bytes = in.readAllBytes();
                    if (!Arrays.equals(bytes, reference.readAllBytes())) throw new IOException("Overlay content differs: " + id);
                    counts.merge(decode(parts[1], bytes), 1, Integer::sum);
                } catch (Exception ex) {
                    errors.add(id + ": " + ex.getMessage());
                }
                namespaces.add(parts[0]);
                checked++;
            }
            if (!namespaces.equals(resources.a(CLIENT_RESOURCES)) ||
                    !namespaces.containsAll(List.of("kaleidoscopecookery", "kaleidoscope_doll", "kaleidoscope_tavern", "kaleidoscope_deco", "musicplus"))) {
                throw new IOException("Missing or inconsistent pack namespaces: " + namespaces);
            }
        }
        if (!errors.isEmpty()) throw new IOException(errors.size() + " asset(s) failed: " + String.join("\n", errors));
        var result = new LinkedHashMap<String, Object>();
        result.put("packSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        result.put("client", w.b().b());
        result.put("resourceFormat", w.b().a(CLIENT_RESOURCES).toString());
        result.put("activeOverlays", metadata.d());
        result.put("namespaces", namespaces.size());
        result.put("assetsRead", checked);
        result.put("decoded", counts);
        return result;
    }

    private static String decode(String name, byte[] bytes) throws IOException {
        if (name.endsWith(".png")) {
            if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) throw new IOException("Invalid PNG");
            return "png";
        }
        if (!name.endsWith(".json") && !name.endsWith(".mcmeta")) return "otherBytes";
        var reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
        if (name.startsWith("models/")) {
            Objects.requireNonNull(hqe.a(reader)); // BlockModel.fromStream
            return "models";
        }
        var json = JsonParser.parseReader(reader);
        if (name.startsWith("items/")) {
            ihf.a.parse(JsonOps.INSTANCE, json).getOrThrow(); // ClientItem.CODEC
            return "itemDefinitions";
        }
        if (name.startsWith("blockstates/")) {
            hqf.a.parse(JsonOps.INSTANCE, json).getOrThrow(); // BlockModelDefinition.CODEC
            return "blockstates";
        }
        return "otherJson";
    }
}
