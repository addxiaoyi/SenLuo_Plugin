package net.kaleidoscope.cookery.ui.input;

import net.kaleidoscope.cookery.ui.MenuTasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.momirealms.craftengine.core.util.VersionHelper;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class DialogChoicePrompt {
    private DialogChoicePrompt() {}

    public record Choice(String label, String translationKey, String value) {
        public Choice(String label, String value) { this(label, null, value); }
        public static Choice translated(String key, String value) { return new Choice(key, key, value); }
    }

    public static void open(Player player, String title, String description, List<Choice> choices,
                            Consumer<String> onPick, Runnable onCustom, Runnable onCancel) {
        if (VersionHelper.isOrAbove1_21_6) {
            DialogBridge.open("ModernDialogChoicePrompt",
                    new Class<?>[]{Player.class, String.class, String.class, List.class, Consumer.class, Runnable.class, Runnable.class},
                    player, title, description, choices, onPick, onCustom, onCancel);
            return;
        }
        // Older clients can select every preset; custom input uses the existing anvil prompt.
        player.closeInventory();
        player.sendMessage(Component.text(title + " · " + description));
        AtomicBoolean selected = new AtomicBoolean();
        for (Choice choice : choices) {
            Component label = choice.translationKey() == null ? Component.text(choice.label()) : Component.translatable(choice.translationKey());
            button(player, label, selected, () -> onPick.accept(choice.value()));
        }
        button(player, Component.text("自定义"), selected, onCustom);
        button(player, Component.text("取消"), selected, onCancel);
    }

    private static void button(Player player, Component label, AtomicBoolean selected, Runnable action) {
        player.sendMessage(Component.text("[ ").append(label).append(Component.text(" ]")).clickEvent(ClickEvent.callback(audience -> {
            if (audience instanceof Player visitor && visitor.getUniqueId().equals(player.getUniqueId())
                    && selected.compareAndSet(false, true)) MenuTasks.runFor(player, action);
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build())));
    }
}
