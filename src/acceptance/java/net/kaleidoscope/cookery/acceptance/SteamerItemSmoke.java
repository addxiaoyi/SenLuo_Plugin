package net.kaleidoscope.cookery.acceptance;

import com.google.gson.GsonBuilder;
import net.kaleidoscope.cookery.block.entity.SteamerInventory;
import net.kaleidoscope.cookery.catalog.CookeryCatalog;
import net.kaleidoscope.cookery.item.SteamerItemContents;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.item.recipe.BukkitRecipeManager;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.recipe.CustomShapelessRecipe;
import net.momirealms.craftengine.core.item.recipe.UniqueIdItem;
import net.momirealms.craftengine.core.item.recipe.input.CraftingInput;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;

/** Opt-in registered recipe and real native item-codec checks; no world or player mutations. */
final class SteamerItemSmoke {
    private static final NamespacedKey OWNER=new NamespacedKey("senluo_acceptance","steamer_owner");
    private SteamerItemSmoke() {}
    static void verify(JavaPlugin plugin) {
        Map<String,Object> report=new LinkedHashMap<>();List<String> failures=new ArrayList<>();
        int assembled=0,reloaded=0,split=0;
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            for(String name:List.of("raw_dough","stuffed_dough_food")) {
                Key ingredient=Key.of("kaleidoscopecookery:"+name);
                String id="kaleidoscopecookery:upstream/kaleidoscope_cookery/"+name+"_steamer";
                var definition=BukkitRecipeManager.instance().recipeById(Key.of(id)).orElseThrow();
                check(definition instanceof CustomShapelessRecipe,"Wrong CE filled-steamer recipe type");
                check(Bukkit.getRecipe(NamespacedKey.fromString(id)) instanceof ShapelessRecipe,"Native shapeless registration missing");
                var recipe=(CustomShapelessRecipe)definition;
                List<Item> raw=new ArrayList<>();raw.add(InventoryUtils.createOrEmpty(SteamerItemContents.ID));
                for(int i=0;i<4;i++)raw.add(InventoryUtils.createOrEmpty(ingredient).copyWithCount(3));
                while(raw.size()<9)raw.add(Item.empty());
                var input=CraftingInput.of(3,3,raw.stream().map(UniqueIdItem::of).toList());
                check(recipe.matches(input),"Filled-steamer ingredients do not match");
                Item output=recipe.assemble(input,ItemBuildContext.empty());assertRecipeProduct(output,ingredient);assembled++;
                for(int i=1;i<=4;i++)check(raw.get(i).count()==3,"Recipe preview debited input");
                ItemStack serialized=InventoryUtils.bukkitCopy(output);
                assertRecipeProduct(InventoryUtils.wrap(ItemStack.deserializeBytes(serialized.serializeAsBytes())),ingredient);
                var path=plugin.getDataFolder().toPath().resolve("steamer-"+name+".bin");
                if(Files.isRegularFile(path)){assertRecipeProduct(InventoryUtils.wrap(ItemStack.deserializeBytes(Files.readAllBytes(path))),ingredient);reloaded++;}
                Files.write(path,serialized.serializeAsBytes());
                var top=SteamerItemContents.read(output);var lower=new ArrayList<>(Collections.<SteamerInventory.Slot>nCopies(8,null));
                ItemStack bread=new ItemStack(org.bukkit.Material.BREAD,3);var meta=bread.getItemMeta();
                meta.getPersistentDataContainer().set(OWNER,PersistentDataType.STRING,"Alice");bread.setItemMeta(meta);
                lower.set(1,new SteamerInventory.Slot(InventoryUtils.wrap(bread),37,-1,null,false));
                var both=SteamerItemContents.merge(lower,top);var drops=SteamerItemContents.split(both,8,false);
                check(drops.size()==2,"Double steamer did not split into two");assertRecipeProduct(drops.get(1),ingredient);
                var lowerAgain=SteamerItemContents.read(InventoryUtils.wrap(ItemStack.deserializeBytes(InventoryUtils.bukkitCopy(drops.get(0)).serializeAsBytes())));
                var slot=lowerAgain.get(1);check(slot.item().count()==3 && slot.progress()==37 && slot.time()==-1,"Lower state changed");
                check(lowerAgain.get(0)==null && lowerAgain.get(2)==null && lowerAgain.get(3)==null,"Sparse slots compacted");
                check("Alice".equals(InventoryUtils.bukkitCopy(slot.item()).getItemMeta().getPersistentDataContainer().get(OWNER,PersistentDataType.STRING)),"Nested item PDC lost");
                split++;
            }
        } catch(Exception | LinkageError exception){failures.add(exception.toString());}
        report.put("server",Bukkit.getVersion());report.put("sourceCommit",CookeryCatalog.SOURCE_COMMIT);
        report.put("assembledRecipes",assembled);report.put("priorDiskProductsReloaded",reloaded);report.put("splitRoundTrips",split);
        report.put("failures",failures);report.put("playerPlacementBreakCrafting","NOT_TESTED");report.put("worldCookingAndFalling","NOT_TESTED");
        try {Files.writeString(plugin.getDataFolder().toPath().resolve("steamer-item-smoke.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report),StandardCharsets.UTF_8);}
        catch(java.io.IOException exception){plugin.getLogger().log(Level.SEVERE,"Cannot save steamer smoke report",exception);}
    }
    private static void assertRecipeProduct(Item item,Key ingredient){
        check(SteamerItemContents.ID.equals(item.id()) && item.count()==1 && item.maxStackSize()==1,"Wrong steamer identity/stack limit");
        for(var slot:SteamerItemContents.read(item))check(slot!=null && ingredient.equals(slot.item().id()) && slot.item().count()==1
                && slot.progress()==0 && slot.time()==1200,"Wrong recipe contents or cooking time");
    }
    private static void check(boolean condition,String failure){if(!condition)throw new IllegalStateException(failure);}
}
