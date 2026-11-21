package net.kaleidoscope.cookery.recipe.edit;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.scanner.ScannerImpl;
import org.yaml.snakeyaml.tokens.ScalarToken;
import org.yaml.snakeyaml.tokens.Token;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Bukkit paths cannot represent CE's empty block-variant key. Quote it reversibly at the YAML boundary. */
final class RecipeYamlConfiguration extends YamlConfiguration {
    private static final String PREFIX = "__cookery_yaml_key__";
    private boolean encoded;

    static RecipeYamlConfiguration read(File file) throws IOException {
        var config = new RecipeYamlConfiguration();
        if (Files.notExists(file.toPath())) return config;
        try {
            config.load(file);
        } catch (InvalidConfigurationException | IllegalArgumentException error) {
            throw new IOException("原配置文件格式错误，未写入任何修改：" + file, error);
        }
        return config;
    }

    @Override public void loadFromString(String text) throws InvalidConfigurationException {
        encoded = text.contains("''") || text.contains("\"\"");
        super.loadFromString(encoded ? translateKeys(text, true) : text);
    }

    @Override public String saveToString() {
        String text = super.saveToString();
        return encoded ? translateKeys(text, false) : text;
    }

    // Only change key tokens. Comments, quoted values, explicit keys, flow maps and supplementary
    // characters must survive; SnakeYAML marks count Unicode code points, not Java chars.
    private static String translateKeys(String text, boolean encode) {
        var options = new LoaderOptions();
        options.setCodePointLimit(16 * 1024 * 1024);
        var scanner = new ScannerImpl(new StreamReader(text), options);
        StringBuilder out = new StringBuilder(text.length());
        boolean key = false;
        int offset = 0, point = 0;
        while (!scanner.checkToken(Token.ID.StreamEnd)) {
            Token token = scanner.getToken();
            if (key && token instanceof ScalarToken scalar) {
                String value = scalar.getValue();
                if ((encode && value.isEmpty()) || value.startsWith(PREFIX)) {
                    String replacement = encode ? PREFIX + value : value.substring(PREFIX.length());
                    int start = text.offsetByCodePoints(offset, token.getStartMark().getIndex() - point);
                    point = token.getEndMark().getIndex();
                    out.append(text, offset, start).append('\'').append(replacement.replace("'", "''")).append('\'');
                    offset = text.offsetByCodePoints(start, point - token.getStartMark().getIndex());
                }
            }
            if (token.getTokenId() != Token.ID.Tag && token.getTokenId() != Token.ID.Anchor)
                key = token.getTokenId() == Token.ID.Key;
        }
        return out.append(text, offset, text.length()).toString();
    }
}
