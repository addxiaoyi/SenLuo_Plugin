package net.kaleidoscope.cookery.acceptance;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.kaleidoscope.cookery.block.entity.ScarecrowController;
import net.kaleidoscope.cookery.util.*;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.core.entity.furniture.*;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.libraries.nbt.*;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;

/** Installed source-model construction, equipment persistence, farmland index and real drop cancellation. */
final class ScarecrowSmoke implements Listener {
    private static final int CHUNK_X=24;
    private static final String DATA="kaleidoscopecookery:scarecrow";
    private final JavaPlugin plugin;
    private final World world;
    private final Path checkpoint;
    private final Map<String,Object> report=new LinkedHashMap<>();
    private final List<String> failures=new ArrayList<>();
    private final List<ItemStack> spawned=new ArrayList<>();
    private boolean observing,cancelDrops;
    private int canceled;

    private ScarecrowSmoke(JavaPlugin plugin,World world){this.plugin=plugin;this.world=world;checkpoint=plugin.getDataFolder().toPath().resolve("scarecrow-checkpoint.json");}
    static void start(JavaPlugin plugin) {
        World world=Bukkit.getWorld("acceptance-world");if(world==null)throw new IllegalStateException("Scarecrow needs acceptance-world");
        var probe=new ScarecrowSmoke(plugin,world);Bukkit.getPluginManager().registerEvents(probe,plugin);
        world.getChunkAtAsync(CHUNK_X,0).whenComplete((chunk,error)->{
            if(error!=null){probe.failures.add(error.toString());probe.finish();return;}
            Bukkit.getRegionScheduler().execute(plugin,world,CHUNK_X,0,()->{
                try{probe.verify();}catch(Exception|LinkageError failure){probe.failures.add(failure.toString());plugin.getLogger().log(Level.SEVERE,"Scarecrow acceptance failure",failure);}
                probe.finish();
            });
        });
    }
    private Location at(int index){return new Location(world,CHUNK_X*16+4.5+index*3,160,8.5);}
    private Furniture place(int index) {
        at(index).clone().subtract(0,1,0).getBlock().setType(Material.STONE);
        return CraftEngineFurniture.place(at(index),CraftEngineFurniture.byId(Key.of("kaleidoscopecookery:scarecrow")),"ground",false);
    }
    private ScarecrowController controller(Furniture furniture) {
        var controller=furniture.controller.get(ScarecrowController.class,0);check(controller!=null,"Scarecrow behavior missing");return controller;
    }
    private void verify() throws Exception {
        world.addPluginChunkTicket(CHUNK_X,0,plugin);
        for(String part:List.of("body","headless","hat"))check(!InventoryUtils.createOrEmpty(Key.of("show:scarecrow_"+part)).isEmpty(),"Missing scarecrow "+part);
        report.put("sourceDisplayParts",3);
        if(Files.isRegularFile(checkpoint)){resume();return;}
        Furniture furniture=place(0);ScarecrowController controller=controller(furniture);
        var definition=CraftEngineFurniture.byId(Key.of("kaleidoscopecookery:scarecrow"));
        check(definition.settings().sounds().placeSound().id().equals(Key.of("minecraft:entity.armor_stand.place")),"Scarecrow place sound mismatch");
        var here=at(0);
        check(ScarecrowController.protects(world,here.getBlockX()+16,here.getBlockY()+18,here.getBlockZ()+16),"Source cuboid diagonal farmland not protected");
        check(!ScarecrowController.protects(world,here.getBlockX()+18,here.getBlockY(),here.getBlockZ()),"Protection extends beyond source boundary");
        report.put("farmlandBoundaryChecks",2);
        List<ItemStack> expected=List.of(stack(Material.SKELETON_SKULL,"named head"),stack(Material.IRON_PICKAXE,"first hand"),stack(Material.BOW,"second hand"));
        seed(controller,expected);CompoundTag saved=new CompoundTag();controller.saveCustomData(saved);controller.loadCustomData(saved);
        check(contents(controller).equals(expected),"Equipment NBT roundtrip lost components/order");
        furniture.setUnsaved();world.save();
        write(checkpoint,Map.of("uuid",furniture.uuid().toString(),"items",expected.stream().map(i->Base64.getEncoder().encodeToString(i.serializeAsBytes())).toList()));
        report.put("phase","THREE_EQUIPMENT_SAVED");report.put("savedEquipment",3);
    }
    private void resume() throws Exception {
        var saved=JsonParser.parseString(Files.readString(checkpoint,StandardCharsets.UTF_8)).getAsJsonObject();
        UUID uuid=UUID.fromString(saved.get("uuid").getAsString());var entity=Bukkit.getEntity(uuid);check(entity!=null,"Scarecrow metadata missing after restart");
        Furniture furniture=CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);check(furniture!=null,"Scarecrow not restored by CE");
        var controller=controller(furniture);List<ItemStack> expected=new ArrayList<>();
        for(var raw:saved.getAsJsonArray("items"))expected.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(raw.getAsString())));
        check(contents(controller).equals(expected),"Restart lost head/hand components");report.put("reloadedEquipment",3);
        observing=true;furniture.destroy();check(spawned.equals(expected),"Removal lost/changed equipment");
        check(contents(controller).isEmpty(),"Delivered equipment still stored");int count=spawned.size();controller.postRemove(null);check(count==spawned.size(),"Duplicate removal duplicated equipment");
        check(!ScarecrowController.protects(world,at(0).getBlockX(),160,8),"Removed scarecrow protection remained");report.put("returnedEquipment",count);
        Furniture canceledFurniture=place(1);var canceledController=controller(canceledFurniture);seed(canceledController,List.of(expected.getFirst()));
        Path recovery=Bukkit.getPluginManager().getPlugin("KaleidoscopeCookeryPlugin").getDataFolder().toPath().resolve("recovery");Set<Path> before=new HashSet<>();
        if(Files.isDirectory(recovery))try(var files=Files.list(recovery)){files.forEach(before::add);}
        spawned.clear();cancelDrops=true;canceledFurniture.destroy();cancelDrops=false;
        check(canceled==1&&spawned.isEmpty()&&contents(canceledController).equals(List.of(expected.getFirst())),"Canceled removal lost contents");
        try(var files=Files.list(recovery)) {
            Path file=files.filter(p->!before.contains(p)&&p.getFileName().toString().startsWith("scarecrow-")).findFirst().orElseThrow();
            var root=FurniturePersistentData.fromBytes(Files.readAllBytes(file)).copyTag();var data=CookeryBlockStateEnvelope.payload(root,DATA);
            Item restored=BlockEntityNbt.getItem(data.getList("items").getCompound(0),"item",BlockEntityNbt.dataVersion(data));
            check(InventoryUtils.bukkitCopy(restored).equals(expected.getFirst()),"Recovery equipment differs");
        }
        report.put("canceledRemovalRecoveryChecks",1);observing=false;
        Files.move(checkpoint,checkpoint.resolveSibling("scarecrow-completed-"+uuid+".json"));report.put("phase","RELOADED_REMOVED_RECOVERED");
    }
    private void seed(ScarecrowController controller,List<ItemStack> items) {
        Item[] slots=new Item[3];Arrays.fill(slots,Item.empty());for(int i=0;i<items.size();i++)slots[i]=InventoryUtils.wrap(items.get(i).clone());
        CompoundTag data=new CompoundTag();data.putInt("data_version",VersionHelper.WORLD_VERSION);data.put("items",BlockEntityNbt.saveSlots(slots));
        CompoundTag root=new CompoundTag();CookeryBlockStateEnvelope.put(root,DATA,data);controller.loadCustomData(root);
    }
    private List<ItemStack> contents(ScarecrowController controller) {
        List<ItemStack> items=new ArrayList<>();for(int i=0;i<3;i++)if(!controller.item(i).isEmpty())items.add(InventoryUtils.bukkitCopy(controller.item(i)));return items;
    }
    private ItemStack stack(Material material,String name) {
        ItemStack stack=new ItemStack(material);stack.editMeta(meta->{meta.displayName(net.kyori.adventure.text.Component.text(name));
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"scarecrow_components"),PersistentDataType.BYTE_ARRAY,new byte[]{4,8,12});
            if(meta instanceof org.bukkit.inventory.meta.Damageable damaged)damaged.setDamage(17);});return stack;
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void cancel(ItemSpawnEvent event) {
        if(observing&&event.getLocation().getWorld()==world&&event.getLocation().getBlockX()>>4==CHUNK_X&&cancelDrops){event.setCancelled(true);canceled++;}
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void observe(ItemSpawnEvent event) {
        if(observing&&event.getLocation().getWorld()==world&&event.getLocation().getBlockX()>>4==CHUNK_X)spawned.add(event.getEntity().getItemStack().clone());
    }
    private void finish() {
        HandlerList.unregisterAll(this);report.put("server",Bukkit.getVersion());report.put("failures",failures);
        report.put("playerFlow","NOT_TESTED; NBT-seeded equipment, actual furniture/protection/drop events");report.put("clientVisual","NOT_TESTED");
        try{write(plugin.getDataFolder().toPath().resolve("scarecrow-smoke.json"),report);}catch(Exception error){plugin.getLogger().log(Level.SEVERE,"Cannot write scarecrow report",error);}
        plugin.getLogger().log(failures.isEmpty()?Level.INFO:Level.SEVERE,"COOKERY_SCARECROW_SMOKE failures="+failures.size());
    }
    private static void write(Path path,Object value)throws java.io.IOException{Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(value),StandardCharsets.UTF_8);}
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
