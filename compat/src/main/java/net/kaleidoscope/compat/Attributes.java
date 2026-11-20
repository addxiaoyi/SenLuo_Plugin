package net.kaleidoscope.compat;

import org.bukkit.attribute.Attribute;

/** Bukkit renamed attribute fields and registry keys after 1.21.1. */
public final class Attributes {
    public static final Attribute MAX_HEALTH = resolve("MAX_HEALTH", "GENERIC_MAX_HEALTH");
    public static final Attribute MOVEMENT_SPEED = resolve("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED");
    public static final Attribute STEP_HEIGHT = resolve("STEP_HEIGHT", "GENERIC_STEP_HEIGHT");
    public static final Attribute BLOCK_INTERACTION_RANGE = resolve("BLOCK_INTERACTION_RANGE", "PLAYER_BLOCK_INTERACTION_RANGE");
    public static final Attribute ENTITY_INTERACTION_RANGE = resolve("ENTITY_INTERACTION_RANGE", "PLAYER_ENTITY_INTERACTION_RANGE");

    private Attributes() {}

    private static Attribute resolve(String current, String legacy) {
        for (String name : new String[]{current, legacy}) {
            try {
                return (Attribute) Attribute.class.getField(name).get(null);
            } catch (NoSuchFieldException ignored) {
                // Try the field exposed by the older Bukkit API.
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Cannot access Bukkit attribute " + name, exception);
            }
        }
        throw new IllegalStateException("Missing Bukkit attribute " + current);
    }
}
