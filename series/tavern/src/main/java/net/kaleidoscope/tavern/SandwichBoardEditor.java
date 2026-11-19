package net.kaleidoscope.tavern;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Vanilla 1.21.11 dialog: never borrows or replaces a player's held book. */
final class SandwichBoardEditor {
    private SandwichBoardEditor() { }

    static void open(SandwichBoardController controller, TavernPlugin plugin, Player player, SandwichBoardState expected) {
        if (!controller.canEdit(player, expected)) return;
        var owner = player.getUniqueId();
        var alignments = Arrays.stream(SandwichBoardState.Alignment.values()).map(value ->
                SingleOptionDialogInput.OptionEntry.create(value.name(), Component.text(switch (value) {
                    case LEFT -> "左对齐"; case CENTER -> "居中"; case RIGHT -> "右对齐";
                }), value == expected.alignment())).toList();
        var save = DialogAction.customClick((response, audience) -> {
            if (!(audience instanceof Player sender) || !sender.getUniqueId().equals(owner) || !plugin.isEnabled()) return;
            String text = response.getText("text"), alignment = response.getText("alignment");
            plugin.onPlayer(sender, () -> plugin.attempt(sender, () -> {
                if (!controller.edit(sender, expected, text, alignment)) sender.sendMessage("展板已变动、上蜡或距离过远，请重新打开编辑器。");
            }));
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build());
        var base = DialogBase.builder(Component.text("酒馆展板"))
                        .canCloseWithEscape(true).pause(false)
                        .inputs(List.of(DialogInput.text("text", Component.text("文本（最多 320 字符）"))
                                        .width(300).initial(expected.text()).maxLength(SandwichBoardState.MAX_TEXT)
                                        .multiline(TextDialogInput.MultilineOptions.create(null, 120)).build(),
                                DialogInput.singleOption("alignment", 300, alignments, Component.text("对齐"), true))).build();
        var type = DialogType.confirmation(ActionButton.builder(Component.text("保存")).action(save).build(),
                ActionButton.builder(Component.text("取消")).build());
        player.showDialog(Dialog.create(factory -> factory.empty().base(base).type(type)));
    }
}
