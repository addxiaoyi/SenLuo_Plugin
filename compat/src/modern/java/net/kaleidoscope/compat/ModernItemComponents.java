package net.kaleidoscope.compat;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Loaded only on servers exposing Paper's consumable data component API. */
public final class ModernItemComponents implements ItemComponents.Adapter {
    @Override public void food(ItemStack stack, float seconds, boolean drink, List<ItemComponents.Effect> definitions) {
        Consumable existing = stack.getData(DataComponentTypes.CONSUMABLE);
        Consumable.Builder builder = existing == null ? Consumable.consumable() : existing.toBuilder();
        List<ConsumeEffect> effects = new ArrayList<>();
        if (existing != null) existing.consumeEffects().stream()
                .filter(effect -> !(effect instanceof ConsumeEffect.ApplyStatusEffects)).forEach(effects::add);
        for (var effect : definitions) effects.add(ConsumeEffect.applyStatusEffects(List.of(effect.effect()), effect.probability()));
        stack.setData(DataComponentTypes.CONSUMABLE, builder.consumeSeconds(seconds).effects(effects)
                .animation(drink ? ItemUseAnimation.DRINK : ItemUseAnimation.EAT).build());
    }

    @Override public void use(ItemStack stack, boolean enabled, boolean drink) {
        if (enabled) stack.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable().consumeSeconds(3600)
                .animation(drink ? ItemUseAnimation.DRINK : ItemUseAnimation.BOW).hasConsumeParticles(false).build());
        else stack.unsetData(DataComponentTypes.CONSUMABLE);
    }

    @Override public boolean hasRemainder(ItemStack stack) { return stack.hasData(DataComponentTypes.USE_REMAINDER); }
}
