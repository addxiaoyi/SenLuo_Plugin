package net.kaleidoscope.deco;

import net.kaleidoscope.series.ItemIds;
import java.util.List;

public record DecoRule(List<String> inputs, boolean wand, int sneak, String target, List<String> sounds,
                       boolean pending, boolean consume, List<String> drops) {
    public DecoRule {
        inputs = List.copyOf(inputs); sounds = List.copyOf(sounds);
        drops = drops == null ? List.of() : List.copyOf(drops);
        if (sneak < -1 || sneak > 1 || target == null) throw new IllegalArgumentException("Invalid Deco rule");
        if ((consume || !drops.isEmpty()) && (pending || target.isEmpty() || inputs.isEmpty()
                || wand || consume && !drops.isEmpty())) {
            throw new IllegalArgumentException("Invalid Deco material transition");
        }
        if (!target.isEmpty()) ItemIds.require(target);
        for (String input : inputs) if (!input.equals("*")) ItemIds.require(input.startsWith("#") ? input.substring(1) : input);
        sounds.forEach(ItemIds::require);
        drops.forEach(ItemIds::require);
    }

    DecoRule(List<String> inputs, boolean wand, int sneak, String target, List<String> sounds, boolean pending) {
        this(inputs, wand, sneak, target, sounds, pending, false, List.of());
    }

    boolean matches(String held, boolean sneaking, java.util.function.Predicate<String> tag) {
        if (sneak != -1 && (sneak == 1) != sneaking) return false;
        if (wand && !held.equals("kaleidoscope_deco:state_tool")) return false;
        return inputs.isEmpty() || inputs.stream().anyMatch(input -> input.equals(held)
                || input.equals("*") && !held.equals("minecraft:air") || input.startsWith("#") && tag.test(input.substring(1)));
    }
}
