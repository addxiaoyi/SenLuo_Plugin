package net.kaleidoscope.cookery.acceptance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates CE-decoded native components; a YAML-only check cannot catch codec shape errors. */
final class SourceSickleComponentsSmoke {
    private SourceSickleComponentsSmoke() {}

    static Map<String, Object> verify(List<String> failures) {
        var report = new LinkedHashMap<String, Object>();
        report.put("status", "NOT_PRESENT");
        var definition = BukkitItemManager.instance().getBuildableItem(Key.of("kaleidoscopecookery:sickle"));
        if (definition.isEmpty()) return report; // Standalone food-only packs do not define tools.
        try {
            var item = definition.get().buildItem((net.momirealms.craftengine.core.entity.player.Player) null);
            report.put("status", "FAIL");
            report.put("material", ItemStackUtils.getBukkitStack(item.minecraftItem()).getType().name());
            require(ItemStackUtils.getBukkitStack(item.minecraftItem()).getType() == Material.IRON_SWORD, "material");
            require(item.maxDamage() == 2000, "max damage");
            var enchantable = item.getComponentAsJson(Key.of("minecraft:enchantable"));
            report.put("enchantable", enchantable);
            require(enchantable.getAsJsonObject().get("value").getAsInt() == 5, "enchantability");
            var repairable = item.getComponentAsJson(Key.of("minecraft:repairable"));
            report.put("repairable", repairable);
            var repair = repairable.getAsJsonObject().get("items");
            require(repair.isJsonArray() ? repair.getAsJsonArray().size() == 1
                    && repair.getAsJsonArray().get(0).getAsString().equals("minecraft:flint")
                    : repair.getAsString().equals("minecraft:flint"), "repair ingredient");
            var attributes = item.getComponentAsJson(Key.of("minecraft:attribute_modifiers"));
            report.put("attributes", attributes);
            require(attributes.isJsonArray() && attributes.getAsJsonArray().size() == 2, "attribute codec/list");
            for (String type : List.of("attack_damage", "attack_speed")) {
                JsonObject found = null;
                for (var value : attributes.getAsJsonArray()) if (value.getAsJsonObject().get("type").getAsString().equals("minecraft:" + type)) {
                    require(found == null, "duplicate attribute"); found = value.getAsJsonObject();
                }
                require(found != null && found.get("id").getAsString().equals("minecraft:base_" + type)
                        && found.get("operation").getAsString().equals("add_value")
                        && found.get("slot").getAsString().equals("mainhand"), "attribute identity/slot");
                require(Double.compare(found.get("amount").getAsDouble(), type.equals("attack_damage") ? 1.0 : (double) -2.4f) == 0,
                        "attribute amount");
            }
            var tool = item.getComponentAsJson(Key.of("minecraft:tool")).getAsJsonObject();
            report.put("tool", tool);
            require(tool.get("damage_per_block").getAsInt() == 2
                    && (!tool.has("default_mining_speed") || tool.get("default_mining_speed").getAsFloat() == 1f)
                    && (!tool.has("can_destroy_blocks_in_creative") || tool.get("can_destroy_blocks_in_creative").getAsBoolean()), "tool defaults/wear");
            var rules = tool.getAsJsonArray("rules");
            require(rules.size() == 2, "tool rule count");
            JsonObject cobweb = rules.get(0).getAsJsonObject(), efficient = rules.get(1).getAsJsonObject();
            JsonElement webBlocks = cobweb.get("blocks");
            require((webBlocks.isJsonArray() ? webBlocks.getAsJsonArray().size() == 1
                    && webBlocks.getAsJsonArray().get(0).getAsString().equals("minecraft:cobweb")
                    : webBlocks.getAsString().equals("minecraft:cobweb"))
                    && cobweb.get("speed").getAsFloat() == 15f && cobweb.get("correct_for_drops").getAsBoolean(), "cobweb rule");
            require(efficient.get("speed").getAsFloat() == 1.5f && !efficient.has("correct_for_drops"), "efficiency rule");
            var ids = new ArrayList<String>();
            for (var value : efficient.getAsJsonArray("blocks")) ids.add(value.getAsString());
            ids.sort(String::compareTo);
            require(ids.size() == 87 && ids.stream().distinct().count() == 87, "source efficiency membership count");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((String.join("\n", ids) + "\n").getBytes(StandardCharsets.UTF_8)));
            report.put("efficientMembersSha256", hash);
            require(hash.equals("c2689b278a108a7afad7cdcb70797472db64fcc2343c43f927682caa2851da08"), "source efficiency membership");
            var weapon = item.getComponentAsJson(Key.of("minecraft:weapon")).getAsJsonObject();
            report.put("weapon", weapon);
            require(!weapon.has("item_damage_per_attack") || weapon.get("item_damage_per_attack").getAsInt() == 1, "weapon wear");
            report.put("status", "PASS");
        } catch (Exception | LinkageError failure) {
            failures.add("source sickle components: " + failure);
        }
        return report;
    }

    private static void require(boolean value, String description) {
        if (!value) throw new IllegalStateException("Source sickle " + description + " differs");
    }
}
