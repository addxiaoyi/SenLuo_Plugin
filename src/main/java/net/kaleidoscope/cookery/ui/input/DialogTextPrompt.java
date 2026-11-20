package net.kaleidoscope.cookery.ui.input;

import org.bukkit.entity.Player;
import java.util.function.Consumer;

final class DialogTextPrompt {
    private DialogTextPrompt() {}

    static void open(Player player, String title, String label, String initial,
                     Consumer<String> onConfirm, Runnable onCancel) {
        DialogBridge.open("ModernDialogTextPrompt",
                new Class<?>[]{Player.class, String.class, String.class, String.class, Consumer.class, Runnable.class},
                player, title, label, initial, onConfirm, onCancel);
    }
}
