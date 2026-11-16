package net.kaleidoscope.cookery.item;

import net.kaleidoscope.cookery.catalog.ConsumeEffectDefinition;
import net.kaleidoscope.cookery.catalog.PlacedMealDefinition.BiteFood;
import net.kaleidoscope.cookery.catalog.PlacedMealDefinition.Effect;
import net.kaleidoscope.cookery.effect.CookeryEffects;
import net.kaleidoscope.cookery.effect.EffectKind;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/** Consumes a placed bite without creating an item-consumption event or touching the player's inventory. */
public final class PlacedMealConsumption {
    private static final int MAX_FOOD_LEVEL = 20;
    private final CookeryEffects effects;
    private final VanillaEffects vanilla;
    private final DoubleSupplier chance;

    public PlacedMealConsumption(CookeryEffects effects) {
        this(effects, PlacedMealConsumption::prepareVanilla, () -> ThreadLocalRandom.current().nextDouble());
    }

    PlacedMealConsumption(CookeryEffects effects, VanillaEffects vanilla, DoubleSupplier chance) {
        this.effects = Objects.requireNonNull(effects, "effects");
        this.vanilla = Objects.requireNonNull(vanilla, "vanilla");
        this.chance = Objects.requireNonNull(chance, "chance");
    }

    /** The caller holds its transfer lock; commit rejects a meal replaced during the food event. */
    public boolean consume(Player player, BiteFood food, String source, BooleanSupplier commit) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(food, "food");
        Objects.requireNonNull(commit, "commit");
        if (source == null || NamespacedKey.fromString(source) == null) {
            throw new IllegalArgumentException("Invalid placed meal source: " + source);
        }
        requireOwner(player);
        if (!canEat(player, food)) return false;
        var world = player.getWorld();
        List<PreparedEffect> prepared = prepareEffects(player, food, source);
        int proposed = Math.min(MAX_FOOD_LEVEL, player.getFoodLevel() + food.nutrition());
        // Like cake consumption, this event has no handheld item: nothing is removed from an inventory.
        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, proposed);
        if (!event.callEvent()) return false;
        requireOwner(player);
        if (player.getWorld() != world || !canEat(player, food)) return false;
        int level = Math.clamp(event.getFoodLevel(), 0, MAX_FOOD_LEVEL);
        float saturation = Math.min(level, Math.max(0, player.getSaturation()) + food.saturation());
        if (!commit.getAsBoolean()) return false;
        player.setFoodLevel(level);
        player.setSaturation(saturation);
        for (PreparedEffect effect : prepared) {
            if (chance.getAsDouble() < effect.probability()) effect.apply().run();
        }
        return true;
    }

    private List<PreparedEffect> prepareEffects(Player player, BiteFood food, String source) {
        List<PreparedEffect> prepared = new ArrayList<>(food.effects().size());
        for (Effect effect : food.effects()) {
            Runnable apply;
            if (effect.id().startsWith("minecraft:")) {
                apply = vanilla.prepare(player, effect);
            } else {
                EffectKind.fromId(effect.id());
                if (effect.duration() == 0) {
                    // Kitchen effects have no instantaneous handlers and must not gain an invented tick.
                    apply = () -> {};
                } else {
                    var custom = new ConsumeEffectDefinition(effect.id(), effect.duration(), effect.amplifier(), effect.probability());
                    apply = () -> effects.apply(player, custom, source);
                }
            }
            prepared.add(new PreparedEffect(effect.probability(), Objects.requireNonNull(apply, "prepared effect")));
        }
        return prepared;
    }

    private static boolean canEat(Player player, BiteFood food) {
        if (!player.isOnline() || player.isDead() || !(player.getHealth() > 0) || player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        return food.alwaysEat() || player.getGameMode() == GameMode.CREATIVE || player.getFoodLevel() < MAX_FOOD_LEVEL;
    }

    private static void requireOwner(Player player) {
        if (!Bukkit.isOwnedByCurrentRegion(player)) {
            throw new IllegalStateException("Placed meal consumption requires player region ownership");
        }
    }

    private static Runnable prepareVanilla(Player player, Effect effect) {
        FoodEffectBridge bridge = FoodEffectBridge.BINDINGS.get(player.getClass());
        var registry = Objects.requireNonNull(Bukkit.getRegistry(PotionEffectType.class), "Potion effect registry");
        PotionEffectType type = registry.get(Objects.requireNonNull(NamespacedKey.fromString(effect.id())));
        if (type == null) throw new IllegalArgumentException("Unknown placed meal potion effect: " + effect.id());
        PotionEffect potion = new PotionEffect(type, effect.duration(), effect.amplifier(), false, true, true);
        return bridge.prepare(player, potion);
    }

    private record PreparedEffect(double probability, Runnable apply) {}

    @FunctionalInterface
    interface VanillaEffects {
        Runnable prepare(Player player, Effect effect);
    }

    private record FoodEffectBridge(MethodHandle handle, MethodHandle convert, MethodHandle add) {
        private static final ClassValue<FoodEffectBridge> BINDINGS = new ClassValue<>() {
            @Override protected FoodEffectBridge computeValue(Class<?> playerType) {
                try {
                    Class<?> craftType = playerType;
                    while (craftType != null && !craftType.getName().startsWith("org.bukkit.craftbukkit.")) {
                        craftType = craftType.getSuperclass();
                    }
                    if (craftType == null) throw new NoSuchMethodException("No CraftPlayer superclass");
                    String name = craftType.getName();
                    int entityPackage = name.indexOf(".entity.");
                    if (entityPackage < 0) throw new NoSuchMethodException("Unknown CraftPlayer package " + name);
                    Class<?> potionUtil = Class.forName(name.substring(0, entityPackage) + ".potion.CraftPotionUtil",
                            false, craftType.getClassLoader());
                    var handle = craftType.getMethod("getHandle");
                    var convert = potionUtil.getMethod("fromBukkit", PotionEffect.class);
                    var add = handle.getReturnType().getMethod("addEffect", convert.getReturnType(), EntityPotionEffectEvent.Cause.class);
                    if (add.getReturnType() != boolean.class) throw new NoSuchMethodException("Food effect result is not boolean");
                    var lookup = MethodHandles.publicLookup();
                    return new FoodEffectBridge(lookup.unreflect(handle), lookup.unreflect(convert), lookup.unreflect(add));
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Cannot bind FOOD potion effects for " + playerType.getName(), failure);
                }
            }
        };

        Runnable prepare(Player player, PotionEffect potion) {
            final Object entity;
            final Object instance;
            try {
                entity = Objects.requireNonNull(handle.invoke(player), "Player handle");
                instance = Objects.requireNonNull(convert.invoke(potion), "Converted food effect");
            } catch (Throwable failure) {
                throw bridgeFailure("prepare", player, failure);
            }
            return () -> {
                try {
                    // False is a normal cancelled or weaker potion; the consumed bite remains committed.
                    add.invoke(entity, instance, EntityPotionEffectEvent.Cause.FOOD);
                } catch (Throwable failure) {
                    throw bridgeFailure("apply", player, failure);
                }
            };
        }

        private static RuntimeException bridgeFailure(String action, Player player, Throwable failure) {
            if (failure instanceof Error error) throw error;
            return new IllegalStateException("Cannot " + action + " FOOD potion effect for " + player.getUniqueId(), failure);
        }
    }
}
