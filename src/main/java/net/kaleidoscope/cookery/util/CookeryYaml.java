package net.kaleidoscope.cookery.util;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Reads CE documents, whose block variants can legitimately have an empty key. */
public final class CookeryYaml {
    private CookeryYaml() {}

    public static Map<?, ?> readMapping(Path file) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(16 * 1024 * 1024);
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object value = new Yaml(new SafeConstructor(options)).load(reader);
            if (!(value instanceof Map<?, ?> mapping)) throw new IOException("Expected YAML mapping: " + file);
            return mapping;
        }
    }
}
