package net.kaleidoscope.compat;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.potion.PotionEffect;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

/** Isolates the consumable split introduced after Minecraft 1.21.1. */
public final class ItemComponents {
    public record Effect(PotionEffect effect, float probability) {}

    public interface Adapter {
        void food(ItemStack stack, float seconds, boolean drink, List<Effect> effects);
        void use(ItemStack stack, boolean enabled, boolean drink);
        boolean hasRemainder(ItemStack stack);
    }

    private static final class Holder {
        static final Adapter ADAPTER = create();
    }

    private ItemComponents() {}

    public static void food(ItemStack stack, float seconds, boolean drink, List<Effect> effects) {
        Holder.ADAPTER.food(stack, seconds, drink, effects);
    }

    public static void use(ItemStack stack, boolean enabled, boolean drink) {
        Holder.ADAPTER.use(stack, enabled, drink);
    }

    public static boolean hasRemainder(ItemStack stack) { return Holder.ADAPTER.hasRemainder(stack); }

    private static Adapter create() {
        try {
            Class.forName("io.papermc.paper.datacomponent.item.Consumable");
        } catch (ClassNotFoundException exception) {
            return new Legacy();
        }
        try {
            return (Adapter) Class.forName(ItemComponents.class.getPackageName() + ".ModernItemComponents")
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot load modern item component adapter", exception);
        }
    }

    private static final class Legacy implements Adapter {
        @Override public void food(ItemStack stack, float seconds, boolean drink, List<Effect> effects) {
            var meta = stack.getItemMeta();
            var food = meta.getFood();
            invoke(food, "setEatSeconds", new Class<?>[]{float.class}, seconds);
            invoke(food, "setEffects", new Class<?>[]{List.class}, List.of());
            for (Effect effect : effects) {
                invoke(food, "addEffect", new Class<?>[]{PotionEffect.class, float.class}, effect.effect(), effect.probability());
            }
            meta.setFood(food);
            stack.setItemMeta(meta);
        }

        @Override public void use(ItemStack stack, boolean enabled, boolean drink) {
            var meta = stack.getItemMeta();
            if (enabled) {
                var food = meta.getFood();
                food.setNutrition(0);
                food.setSaturation(0);
                food.setCanAlwaysEat(true);
                invoke(food, "setEatSeconds", new Class<?>[]{float.class}, 3600f);
                meta.setFood(food);
            } else meta.setFood(null);
            stack.setItemMeta(meta);
        }

        @Override public boolean hasRemainder(ItemStack stack) {
            var meta = stack.getItemMeta();
            return meta != null && meta.hasFood()
                    && invoke(meta.getFood(), "getUsingConvertsTo", new Class<?>[0]) != null;
        }

        private static Object invoke(FoodComponent food, String method, Class<?>[] types, Object... args) {
            try {
                return FoodComponent.class.getMethod(method, types).invoke(food, args);
            } catch (InvocationTargetException exception) {
                throw new IllegalStateException("Legacy food component rejected " + method, exception.getCause());
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unsupported legacy food component: " + method, exception);
            }
        }
    }
}
