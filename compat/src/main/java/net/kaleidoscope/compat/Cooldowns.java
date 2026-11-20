package net.kaleidoscope.compat;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.lang.reflect.Method;

/** Uses cooldown groups where available, retaining material cooldowns on 1.21.1. */
public final class Cooldowns {
    private static final Method HAS = method("hasCooldown", ItemStack.class);
    private static final Method SET = method("setCooldown", ItemStack.class, int.class);
    private Cooldowns() {}

    public static boolean has(Player player, ItemStack stack) {
        return HAS == null ? player.hasCooldown(stack.getType()) : (boolean) invoke(HAS, player, stack);
    }

    public static void set(Player player, ItemStack stack, int ticks) {
        if (SET == null) player.setCooldown(stack.getType(), ticks);
        else invoke(SET, player, stack, ticks);
    }

    private static Method method(String name, Class<?>... types) {
        try { return Player.class.getMethod(name, types); }
        catch (NoSuchMethodException ignored) { return null; }
    }

    private static Object invoke(Method method, Player player, Object... args) {
        try { return method.invoke(player, args); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException("Cannot update item cooldown", exception); }
    }
}
