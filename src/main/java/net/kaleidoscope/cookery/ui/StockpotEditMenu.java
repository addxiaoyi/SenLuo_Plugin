package net.kaleidoscope.cookery.ui;

import net.kaleidoscope.cookery.api.ui.MenuButton;
import net.kaleidoscope.cookery.recipe.ApplianceType;
import net.kaleidoscope.cookery.recipe.StockpotRecipe;
import net.kaleidoscope.cookery.recipe.StockpotVisuals;
import net.kaleidoscope.cookery.recipe.edit.RecipeEditService;
import net.kaleidoscope.cookery.recipe.edit.StockpotRecipeDraft;
import net.kaleidoscope.cookery.ui.input.MenuInput;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.gui.BasicGuiImpl;
import net.momirealms.craftengine.core.plugin.gui.GuiElement;
import net.momirealms.craftengine.core.plugin.gui.GuiLayout;
import net.momirealms.craftengine.core.plugin.gui.Ingredient;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.adventure.text.format.NamedTextColor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/** Editor for imported stockpot recipes; every save emits the complete strict schema. */
public final class StockpotEditMenu {
    private StockpotEditMenu() {}

    public static void open(org.bukkit.entity.Player player, StockpotRecipeDraft draft) {
        Player viewer = RecipeMenus.adapt(player);
        if (viewer == null) return;
        GuiLayout layout = new GuiLayout("#########", "#I#R#M#T#", "#B#C#Q#V#", "#W#F#L#D#", "A###S###X");
        layout.addIngredient('#', Ingredient.simple(MenuIcons.filler(viewer)));
        layout.addIngredient('I', textButton(player, viewer, draft, "原料", ingredientsText(draft), raw -> {
            if (!parseIngredients(draft, raw)) {
                RecipeMenus.message(player, "原料格式无效：按槽位用分号分隔，每槽备选物品用逗号分隔");
            }
        }));
        layout.addIngredient('R', keyButton(player, viewer, draft, "成品", keyText(draft.result()), key -> draft.result(key)));
        layout.addIngredient('M', MenuIcons.button(MenuIcons.icon(MenuButton.MODE, viewer,
                MenuIcons.text("模式：" + draft.mode(), NamedTextColor.AQUA)), () -> {
            draft.mode(draft.mode() == StockpotRecipe.Mode.EXACT ? StockpotRecipe.Mode.DISTINCT : StockpotRecipe.Mode.EXACT);
            open(player, draft);
        }));
        layout.addIngredient('T', keyButton(player, viewer, draft, "食谱 id", draft.id().asString(), draft::id));
        layout.addIngredient('B', keyButton(player, viewer, draft, "汤底", keyText(draft.soupBase()), draft::soupBase));
        layout.addIngredient('C', intButton(player, viewer, draft, "时间 tick", draft.time(), 1, Integer.MAX_VALUE, draft::time));
        layout.addIngredient('Q', intButton(player, viewer, draft, "产出数量", draft.resultCount(), 1, 9, draft::resultCount));
        layout.addIngredient('V', keyButton(player, viewer, draft, "容器", keyText(draft.carrier()), key ->
                draft.carrier(key != null && !key.asString().equals("minecraft:air") ? key : null)));
        layout.addIngredient('W', keyButton(player, viewer, draft, "烹饪模型", draft.visuals().cookingModel().asString(), key -> {
            var v = draft.visuals();
            draft.visuals(new StockpotVisuals(key, v.finishedModel(), v.cookingColor(), v.finishedColor()));
        }));
        layout.addIngredient('F', keyButton(player, viewer, draft, "完成模型", draft.visuals().finishedModel().asString(), key -> {
            var v = draft.visuals();
            draft.visuals(new StockpotVisuals(v.cookingModel(), key, v.cookingColor(), v.finishedColor()));
        }));
        layout.addIngredient('L', textButton(player, viewer, draft, "烹饪颜色", String.valueOf(draft.visuals().cookingColor()),
                raw -> updateColor(player, draft, raw, true)));
        layout.addIngredient('D', textButton(player, viewer, draft, "完成颜色", String.valueOf(draft.visuals().finishedColor()),
                raw -> updateColor(player, draft, raw, false)));
        layout.addIngredient('A', MenuIcons.back(viewer, () -> RecipeListMenu.open(player, ApplianceType.STOCKPOT, true)));
        layout.addIngredient('S', MenuIcons.button(MenuIcons.icon(MenuButton.SAVE, viewer,
                MenuIcons.text("保存", NamedTextColor.GREEN)), () -> {
            var future = RecipeEditSaves.save(draft, () -> RecipeEditService.saveStockpot(draft));
            if (future == null) return;
            future.thenAccept(error -> MenuTasks.runFor(player, () -> {
                if (error != null) {
                    RecipeMenus.message(player, error);
                    open(player, draft);
                } else {
                    RecipeListMenu.open(player, ApplianceType.STOCKPOT, true);
                }
            }));
        }));
        layout.addIngredient('X', draft.isNew() ? MenuIcons.filler(viewer) : MenuIcons.button(
                MenuIcons.icon(MenuButton.DELETE, viewer, MenuIcons.text("删除", NamedTextColor.RED)),
                () -> ConfirmMenu.open(player, "删除高汤锅食谱", List.of(draft.originalId().asString()),
                        () -> RecipeEditService.deleteStockpot(draft.originalRecipe()).thenAccept(error -> MenuTasks.runFor(player, () -> {
                            RecipeMenus.message(player, error == null ? "已删除 " + draft.originalId().asString() : error);
                            if (error == null) RecipeListMenu.open(player, ApplianceType.STOCKPOT, true);
                            else open(player, draft);
                        })), () -> open(player, draft))));
        BasicGuiImpl.builder().layout(layout).inventoryClickConsumer(RecipeMenus.inventoryGuard()).build()
                .title(MenuIcons.text(draft.isNew() ? "新建高汤锅食谱" : "编辑高汤锅食谱", NamedTextColor.DARK_GRAY))
                .refresh().open(viewer);
    }

    private static String keyText(Key key) { return key == null ? "minecraft:air" : key.asString(); }

    private static String ingredientsText(StockpotRecipeDraft draft) {
        return draft.ingredients().stream()
                .map(slot -> slot.stream().map(Key::asString).sorted().reduce((a, b) -> a + "," + b).orElse(""))
                .reduce((a, b) -> a + ";" + b).orElse("");
    }

    private static boolean parseIngredients(StockpotRecipeDraft draft, String raw) {
        List<java.util.Set<Key>> slots = new ArrayList<>();
        String[] values = raw.split(";", -1);
        if (values.length < 1 || values.length > 9) return false;
        for (String value : values) {
            LinkedHashSet<Key> choices = new LinkedHashSet<>();
            for (String item : value.split(",", -1)) {
                Key key = RecipeMenus.parseKey(item.trim());
                if (key == null) return false;
                choices.add(key);
            }
            if (choices.isEmpty() || choices.size() > 128) return false;
            slots.add(choices);
        }
        draft.ingredients(slots);
        return true;
    }

    private static GuiElement keyButton(org.bukkit.entity.Player player, Player viewer, StockpotRecipeDraft draft,
                                        String title, String initial, Consumer<Key> save) {
        return textButton(player, viewer, draft, title, initial, raw -> {
            Key key = RecipeMenus.parseKey(raw);
            if (key == null) {
                RecipeMenus.message(player, "请输入有效的命名空间:名称 id");
            } else {
                save.accept(key);
            }
        });
    }

    private static GuiElement textButton(org.bukkit.entity.Player player, Player viewer, StockpotRecipeDraft draft,
                                         String title, String initial, Consumer<String> save) {
        return MenuIcons.button(MenuIcons.icon(MenuButton.MODE, viewer, MenuIcons.text(title, NamedTextColor.GOLD), MenuIcons.lore(initial)),
                () -> MenuInput.requestText(player, title, "值", initial, raw -> {
                    save.accept(raw.trim());
                    open(player, draft);
                }, () -> open(player, draft)));
    }

    private static GuiElement intButton(org.bukkit.entity.Player player, Player viewer, StockpotRecipeDraft draft,
                                        String title, int initial, int min, int max, java.util.function.IntConsumer save) {
        return MenuIcons.button(MenuIcons.icon(MenuButton.COUNT, viewer,
                        MenuIcons.text(title + "：" + initial, NamedTextColor.GOLD)),
                () -> MenuInput.requestInt(player, title, title, initial, min, max, value -> {
                    save.accept(value);
                    open(player, draft);
                }, () -> open(player, draft)));
    }

    private static void updateColor(org.bukkit.entity.Player player, StockpotRecipeDraft draft, String raw, boolean cooking) {
        try {
            int color = Integer.parseInt(raw.trim());
            if (color < 0 || color > 0xFFFFFF) {
                RecipeMenus.message(player, "颜色必须是 0 到 16777215 的 RGB 整数");
                return;
            }
            var visuals = draft.visuals();
            draft.visuals(new StockpotVisuals(visuals.cookingModel(), visuals.finishedModel(),
                    cooking ? color : visuals.cookingColor(), cooking ? visuals.finishedColor() : color));
        } catch (RuntimeException ignored) {
            RecipeMenus.message(player, "颜色必须是十进制 RGB 整数");
        }
    }
}
