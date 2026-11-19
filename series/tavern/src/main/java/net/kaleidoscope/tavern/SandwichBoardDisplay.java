package net.kaleidoscope.tavern;

import net.momirealms.craftengine.bukkit.entity.furniture.element.TextDisplayFurnitureElementConfig;
import net.momirealms.craftengine.core.entity.furniture.element.FurnitureElement;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/** Managed by CE tracking: no persistent world entity or orphan cleanup task. */
final class SandwichBoardDisplay implements FurnitureElement {
    private final SandwichBoardController controller;
    private List<FurnitureElement> displays = List.of();
    private boolean activated;

    SandwichBoardDisplay(SandwichBoardController controller) { this.controller = controller; }

    static String literalText(SandwichBoardState state, String text) {
        // Escape every opening tag, including CE extension tags absent from standard MiniMessage.
        String literal = text.replace("\\", "\\\\").replace("<", "\\<");
        return String.format(java.util.Locale.ROOT, "<#%06x><bold>%s</bold>", state.textRgb(), literal);
    }

    static Map<String, Object> configuration(SandwichBoardState state, String text, int line) {
        Map<String, Object> config = new HashMap<>();
        config.put("text", literalText(state, text));
        double x = switch (state.alignment()) {
            case LEFT -> -(SandwichBoardText.MAX_WIDTH - SandwichBoardText.width(text)) * .005 - .01;
            case CENTER -> -.01;
            case RIGHT -> (SandwichBoardText.MAX_WIDTH - SandwichBoardText.width(text)) * .005 - .01;
        };
        // A separate display per line keeps the first line fixed and bounds all text to eight rows.
        double y = 1.06 + (.1 - line * .1) * Math.cos(Math.toRadians(22.5));
        double z = -.06 + (.1 - line * .1) * Math.sin(Math.toRadians(22.5));
        config.put("position", String.format(java.util.Locale.ROOT, "%.5f,%.5f,%.5f", x, y, z));
        config.put("scale", "0.4,0.4,0.4");
        config.put("pitch", -22.5f);
        config.put("yaw", 180f);
        config.put("line_width", 1024);
        config.put("background_color", 0);
        config.put("has_shadow", false);
        config.put("is_see_through", false);
        config.put("use_default_background_color", false);
        config.put("alignment", state.alignment().name());
        config.put("view_range", 0.75f);
        if (state.glowing()) config.put("brightness", Map.of("block_light", 15, "sky_light", 15));
        return config;
    }

    synchronized void rebuild() {
        var lines = SandwichBoardText.lines(controller.state().text());
        List<FurnitureElement> replacements = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            var config = TextDisplayFurnitureElementConfig.FACTORY.create(ConfigSection.of("tavern.sandwich.text",
                    configuration(controller.state(), lines.get(i), i)));
            replacements.add(config.create(controller.furniture()));
        }
        List<FurnitureElement> previous = displays;
        displays = List.copyOf(replacements);
        for (Player viewer : controller.furniture().trackedBy()) {
            previous.forEach(element -> element.hide(viewer));
            displays.forEach(element -> element.show(viewer));
        }
        activated = true;
    }

    @Override public synchronized void activate() { if (!activated) rebuild(); }
    @Override public synchronized void show(Player player) { displays.forEach(element -> element.show(player)); }
    @Override public synchronized void update(Player player) { displays.forEach(element -> element.update(player)); }
    @Override public synchronized void hide(Player player) { displays.forEach(element -> element.hide(player)); }
    @Override public void gatherInteractableEntityId(IntConsumer ids) { }
}
