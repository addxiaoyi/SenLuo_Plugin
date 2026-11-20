package net.kaleidoscope.compat;

import org.bukkit.GameRule;

/** Game rule constants moved to GameRules in newer Bukkit APIs. */
public final class Rules {
    public static final GameRule<Integer> RANDOM_TICK_SPEED = rule("RANDOM_TICK_SPEED", "RANDOM_TICK_SPEED");
    public static final GameRule<Boolean> DO_TILE_DROPS = rule("DO_TILE_DROPS", "BLOCK_DROPS");
    private Rules() {}

    @SuppressWarnings("unchecked")
    private static <T> GameRule<T> rule(String legacy, String modern) {
        try {
            try { return (GameRule<T>) Class.forName("org.bukkit.GameRules").getField(modern).get(null); }
            catch (ClassNotFoundException ignored) { return (GameRule<T>) GameRule.class.getField(legacy).get(null); }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Missing game rule " + legacy + "/" + modern, exception);
        }
    }
}
