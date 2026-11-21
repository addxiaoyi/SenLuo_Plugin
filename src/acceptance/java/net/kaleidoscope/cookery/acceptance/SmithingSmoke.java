package net.kaleidoscope.cookery.acceptance;

import com.google.gson.GsonBuilder;
import net.kaleidoscope.cookery.catalog.CookeryCatalog;
import net.kaleidoscope.cookery.item.ItemKeys;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.item.recipe.BukkitRecipeManager;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.recipe.CustomSmithingTransformRecipe;
import net.momirealms.craftengine.core.item.recipe.UniqueIdItem;
import net.momirealms.craftengine.core.item.recipe.input.SmithingInput;
import net.momirealms.craftengine.core.util.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;

/** Real registered CE smithing assembly and disk serialization, without a player or slot debits. */
final class SmithingSmoke {
    private static final String RECIPE = "kaleidoscopecookery:upstream/minecraft/netherite_kitchen_knife_smithing";
    private static final NamespacedKey OWNER = new NamespacedKey("senluo_acceptance", "smithing_owner");
    private static final Component NAME = Component.text("SenLuo preserved knife");
    private static final List<Component> LORE = List.of(Component.text("preserved source lore"));
    private SmithingSmoke() {}

    static void verify(JavaPlugin plugin) {
        Map<String,Object> report=new LinkedHashMap<>();List<String> failures=new ArrayList<>();
        int assembled=0,reloaded=0,rejected=0;
        try {
            var definition=BukkitRecipeManager.instance().recipeById(Key.of(RECIPE)).orElseThrow();
            check(definition instanceof CustomSmithingTransformRecipe,"Registered CE recipe has wrong type");
            var recipe=(CustomSmithingTransformRecipe)definition;
            check(Bukkit.getRecipe(NamespacedKey.fromString(RECIPE)) instanceof SmithingTransformRecipe,"Native smithing registration missing");
            ItemStack original=sourceKnife();
            Item base=InventoryUtils.wrap(original);
            Item template=InventoryUtils.wrap(new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE,3));
            Item addition=InventoryUtils.wrap(new ItemStack(Material.NETHERITE_INGOT,5));
            byte[] before=InventoryUtils.bukkitCopy(base).serializeAsBytes();
            var input=input(base,template,addition);
            check(recipe.matches(input),"Valid enchanted/damaged custom knife does not match");
            for(int attempt=0;attempt<3;attempt++) {
                Item output=recipe.assemble(input,ItemBuildContext.empty());assertProduct(InventoryUtils.bukkitCopy(output));assembled++;
                check(Arrays.equals(before,InventoryUtils.bukkitCopy(base).serializeAsBytes()),"Assembly changed source knife");
                check(template.count()==3 && addition.count()==5,"Preview assembly consumed ingredients");
                assertProduct(ItemStack.deserializeBytes(InventoryUtils.bukkitCopy(output).serializeAsBytes()));
                if(attempt==0){
                    var saved=plugin.getDataFolder().toPath().resolve("smithing-product.bin");
                    Files.createDirectories(saved.getParent());
                    if(Files.isRegularFile(saved)){assertProduct(ItemStack.deserializeBytes(Files.readAllBytes(saved)));reloaded++;}
                    Files.write(saved,InventoryUtils.bukkitCopy(output).serializeAsBytes());
                }
            }
            for(var wrong:List.of(input(InventoryUtils.wrap(new ItemStack(Material.DIAMOND_SWORD)),template,addition),
                    input(base,InventoryUtils.wrap(new ItemStack(Material.PAPER)),addition),
                    input(base,template,InventoryUtils.wrap(new ItemStack(Material.IRON_INGOT))))) {
                check(!recipe.matches(wrong),"Wrong smithing role matched");rejected++;
            }
        } catch(Exception | LinkageError exception){failures.add(exception.toString());}
        report.put("server",Bukkit.getVersion());report.put("sourceCommit",CookeryCatalog.SOURCE_COMMIT);
        report.put("assembled",assembled);report.put("reloadedProducts",reloaded);report.put("rejectedInputs",rejected);
        report.put("failures",failures);report.put("nativeSlotConsumption","NOT_TESTED");report.put("playerShiftCancellation","NOT_TESTED");
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            Files.writeString(plugin.getDataFolder().toPath().resolve("smithing-smoke.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report),StandardCharsets.UTF_8);
        } catch(java.io.IOException exception){plugin.getLogger().log(Level.SEVERE,"Cannot save smithing smoke report",exception);}
    }
    private static SmithingInput input(Item base,Item template,Item addition){return new SmithingInput(UniqueIdItem.of(base),UniqueIdItem.of(template),UniqueIdItem.of(addition));}
    static ItemStack sourceKnife() {
        ItemStack original=InventoryUtils.bukkitCopy(InventoryUtils.createOrEmpty(ItemKeys.DIAMOND_KITCHEN_KNIFE));
        var meta=(Damageable)original.getItemMeta();meta.setDamage(137);meta.displayName(NAME);meta.lore(LORE);
        ((org.bukkit.inventory.meta.Repairable)meta).setRepairCost(7);meta.getPersistentDataContainer().set(OWNER,PersistentDataType.STRING,"Alice");original.setItemMeta(meta);
        var sharpness=Objects.requireNonNull(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("sharpness")));
        original.addUnsafeEnchantment(sharpness,4);
        return original;
    }

    static void assertProduct(ItemStack stack){
        check(stack.getType()==Material.NETHERITE_SWORD && stack.getAmount()==1,"Wrong material or count");
        Item item=InventoryUtils.wrap(stack);check(ItemKeys.NETHERITE_KITCHEN_KNIFE.equals(item.id()),"Wrong CE identity");
        check(item.maxDamage()==2031,"Target durability was not upgraded");
        var meta=(Damageable)stack.getItemMeta();check(meta.getDamage()==137 && ((org.bukkit.inventory.meta.Repairable)meta).getRepairCost()==7,"Damage/repair cost changed");
        check(NAME.equals(meta.displayName()) && LORE.equals(meta.lore()),"Name or lore changed");
        check("Alice".equals(meta.getPersistentDataContainer().get(OWNER,PersistentDataType.STRING)),"User PDC lost");
        var sharpness=Objects.requireNonNull(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("sharpness")));
        check(stack.getEnchantmentLevel(sharpness)==4,"Enchantment changed");
        var expected=InventoryUtils.createOrEmpty(ItemKeys.NETHERITE_KITCHEN_KNIFE);
        check(item.itemNameJson().equals(expected.itemNameJson()),"Target item name lost");
    }
    private static void check(boolean condition,String failure){if(!condition)throw new IllegalStateException(failure);}
}
