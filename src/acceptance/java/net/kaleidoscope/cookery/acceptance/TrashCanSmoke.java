package net.kaleidoscope.cookery.acceptance;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.kaleidoscope.cookery.block.entity.TrashCanController;
import net.kaleidoscope.cookery.block.entity.TrashCanElement;
import net.kaleidoscope.cookery.block.entity.TrashCanAnimation;
import net.kaleidoscope.cookery.util.*;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
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

/** Real redstone, item entities, persistence, and removal; no simulated player or client. */
final class TrashCanSmoke implements Listener {
    private static final int CHUNK_X = 22;
    private static final String DATA = "kaleidoscopecookery:trashcan";
    private final JavaPlugin plugin;
    private final World world;
    private final Path checkpoint;
    private final Map<String,Object> report = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private final List<ItemStack> spawned = new ArrayList<>();
    private boolean observing, cancelDrops;
    private int canceled;

    private TrashCanSmoke(JavaPlugin plugin, World world) {
        this.plugin = plugin; this.world = world;
        checkpoint = plugin.getDataFolder().toPath().resolve("trashcan-checkpoint.json");
    }

    static void start(JavaPlugin plugin) {
        World world = Bukkit.getWorld("acceptance-world");
        if (world == null) throw new IllegalStateException("Trash can requires acceptance-world");
        TrashCanSmoke probe = new TrashCanSmoke(plugin, world);
        Bukkit.getPluginManager().registerEvents(probe, plugin);
        world.getChunkAtAsync(CHUNK_X,0).whenComplete((chunk,error) -> {
            if (error != null) { probe.failures.add(error.toString()); probe.finish(); return; }
            Bukkit.getRegionScheduler().execute(plugin,world,CHUNK_X,0,() -> {
                try { probe.verify(); }
                catch (Exception | LinkageError failure) {
                    probe.failures.add(failure.toString()); plugin.getLogger().log(Level.SEVERE,"Trash can acceptance failure",failure);
                }
                probe.finish();
            });
        });
    }

    private Location at(int index) { return new Location(world,CHUNK_X*16+4.5+index*3,160,8.5); }
    private Furniture place(int index) {
        at(index).clone().subtract(0,1,0).getBlock().setType(Material.STONE);
        return CraftEngineFurniture.place(at(index),CraftEngineFurniture.byId(Key.of("kaleidoscopecookery:trash_can")),"ground",false);
    }
    private TrashCanController controller(Furniture furniture) {
        TrashCanController can = furniture.controller.get(TrashCanController.class,0);
        check(can != null,"Trash can behavior not attached");
        check(!InventoryUtils.createOrEmpty(Key.of("show:trashcan_4")).isEmpty(),"Independent source pupil model missing");
        int[] poses={0};
        can.gatherElements(element -> {
            if (!(element instanceof TrashCanElement trash)) return;
            for(var clip:TrashCanAnimation.Clip.values()) {
                int end=(int)Math.ceil(TrashCanAnimation.length(clip)*20);
                for(int tick=0;tick<=end;tick++) {
                    trash.applyPose(TrashCanAnimation.sample(clip,tick/20f),List.of(),1); poses[0]++;
                }
            }
            trash.applyPose(TrashCanAnimation.Pose.REST,List.of(),0);
        });
        check(poses[0]>100,"Source animation poses were not encoded");
        report.put("encodedSourceAnimationPoses",poses[0]);
        return can;
    }

    private void verify() throws Exception {
        world.addPluginChunkTicket(CHUNK_X,0,plugin);
        if (Files.isRegularFile(checkpoint)) { resume(); return; }
        Furniture furniture = place(0);
        TrashCanController can = controller(furniture);
        var definition=CraftEngineFurniture.byId(Key.of("kaleidoscopecookery:trash_can"));
        check(definition.settings().sounds().placeSound().id().equals(Key.of("minecraft:block.metal.place")),"Wrong place sound");
        check(definition.settings().sounds().breakSound().id().equals(Key.of("minecraft:block.metal.break")),"Wrong break sound");
        ItemStack first = stack(Material.APPLE,63,"first apple"), second = stack(Material.APPLE,2,"first apple"), third = stack(Material.BREAD,7,"last bread");
        seed(can,List.of(first,second,third));
        Location incomingAt = at(0).add(0,.94,0);
        org.bukkit.entity.Item matching = world.dropItem(incomingAt,stack(Material.APPLE,20,"first apple"));
        matching.setGravity(false); matching.setPickupDelay(Integer.MAX_VALUE);
        can.tickFilter(); check(matching.isValid() && contents(can).getFirst().getAmount()==63,"Unpowered can consumed an item");
        Location power = at(0).add(1,0,0); power.getBlock().setType(Material.REDSTONE_BLOCK);
        can.tickFilter();
        check(!matching.isValid(),"Powered can did not consume matching entity");
        check(contents(can).getFirst().getAmount()==64 && contents(can).get(1).getAmount()==2,"Filter did not stop at first matching slot");
        org.bukkit.entity.Item full = world.dropItem(incomingAt,stack(Material.APPLE,8,"first apple")); can.tickFilter();
        check(!full.isValid() && contents(can).get(1).getAmount()==2,"Full first slot overflowed into another filter slot");
        org.bukkit.entity.Item differentlyNamed = world.dropItem(incomingAt,stack(Material.APPLE,8,"other apple")); can.tickFilter();
        check(!differentlyNamed.isValid(),"Source type filter failed to destroy different metadata");
        check(contents(can).getFirst().equals(stack(Material.APPLE,64,"first apple")),"Filter overwrote existing metadata");
        org.bukkit.entity.Item unrelated = world.dropItem(incomingAt,new ItemStack(Material.DIAMOND)); can.tickFilter();
        check(unrelated.isValid(),"Filter destroyed an unlisted item type"); unrelated.remove();
        org.bukkit.entity.Item above = world.dropItem(at(0).add(0,1.5,0),stack(Material.APPLE,1,"first apple")); can.tickFilter();
        check(above.isValid(),"Filter consumed outside top 1/16 suction zone"); above.remove();
        power.getBlock().setType(Material.AIR);
        check(can.createFurnitureTicker()!=null,"Filter ticker missing");
        report.put("redstoneFilterChecks",6);
        CompoundTag saved = new CompoundTag(); can.saveCustomData(saved); can.loadCustomData(saved);
        check(contents(can).equals(List.of(stack(Material.APPLE,64,"first apple"),second,third)),"NBT round trip changed contents");
        furniture.setUnsaved(); world.save();
        write(checkpoint,Map.of("uuid",furniture.uuid().toString(),"items",contents(can).stream().map(i -> Base64.getEncoder().encodeToString(i.serializeAsBytes())).toList()));
        report.put("phase","THREE_STACKS_SAVED"); report.put("savedStacks",3);
    }

    private void resume() throws Exception {
        var saved = JsonParser.parseString(Files.readString(checkpoint,StandardCharsets.UTF_8)).getAsJsonObject();
        UUID uuid = UUID.fromString(saved.get("uuid").getAsString());
        var entity = Bukkit.getEntity(uuid);
        check(entity!=null,"Trash can metadata entity missing after restart");
        Furniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
        check(furniture!=null,"Trash can not restored by CE"); TrashCanController can = controller(furniture);
        List<ItemStack> expected = new ArrayList<>();
        for (var raw : saved.getAsJsonArray("items")) expected.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(raw.getAsString())));
        check(contents(can).equals(expected),"Restart changed slot order/metadata"); report.put("reloadedStacks",3);
        observing=true; furniture.destroy();
        check(spawned.equals(expected),"Removal did not return all three exact stacks");
        check(contents(can).isEmpty(),"Delivered stacks still stored"); int count=spawned.size(); can.postRemove(null);
        check(spawned.size()==count,"Repeated removal duplicated items"); report.put("breakReturnedStacks",count);
        Furniture canceledFurniture = place(1); TrashCanController canceledCan = controller(canceledFurniture);
        seed(canceledCan,List.of(expected.getFirst()));
        Path recovery = Bukkit.getPluginManager().getPlugin("KaleidoscopeCookeryPlugin").getDataFolder().toPath().resolve("recovery");
        Set<Path> before = new HashSet<>();
        if(Files.isDirectory(recovery)) try(var files=Files.list(recovery)){files.forEach(before::add);}
        spawned.clear(); cancelDrops=true; canceledFurniture.destroy(); cancelDrops=false;
        check(canceled==1 && spawned.isEmpty() && contents(canceledCan).equals(List.of(expected.getFirst())),"Canceled removal lost or duplicated contents");
        try(var files=Files.list(recovery)) {
            Path file=files.filter(p -> !before.contains(p) && p.getFileName().toString().startsWith("trash-can-")).findFirst().orElseThrow();
            CompoundTag root=net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData.fromBytes(Files.readAllBytes(file)).copyTag();
            CompoundTag data=CookeryBlockStateEnvelope.payload(root,DATA);
            Item restored=BlockEntityNbt.getItem(data.getList("items").getCompound(0),"item",BlockEntityNbt.dataVersion(data));
            check(InventoryUtils.bukkitCopy(restored).equals(expected.getFirst()),"Recovery record changed components");
        }
        report.put("canceledRemovalRecoveryChecks",1); observing=false;
        Files.move(checkpoint,checkpoint.resolveSibling("trashcan-completed-"+uuid+".json"));
        report.put("phase","RELOADED_REMOVED_RECOVERED");
    }
    private void seed(TrashCanController can,List<ItemStack> items) {
        Item[] slots=new Item[3]; Arrays.fill(slots,Item.empty());
        for(int i=0;i<items.size();i++)slots[i]=InventoryUtils.wrap(items.get(i).clone());
        CompoundTag data=new CompoundTag(); data.putInt("data_version",VersionHelper.WORLD_VERSION); data.put("items",BlockEntityNbt.saveSlots(slots));
        CompoundTag root=new CompoundTag(); CookeryBlockStateEnvelope.put(root,DATA,data); can.loadCustomData(root);
    }
    private List<ItemStack> contents(TrashCanController can) {
        return Arrays.stream(can.storage()).filter(i -> !i.isEmpty()).map(InventoryUtils::bukkitCopy).toList();
    }
    private ItemStack stack(Material material,int count,String name) {
        ItemStack stack=new ItemStack(material,count);
        stack.editMeta(meta -> {meta.displayName(net.kyori.adventure.text.Component.text(name));
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"trash_components"),PersistentDataType.STRING,"preserve exact data");});
        return stack;
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void cancel(ItemSpawnEvent event) {
        if(observing && event.getLocation().getWorld()==world && event.getLocation().getBlockX()>>4==CHUNK_X && cancelDrops) {
            event.setCancelled(true); canceled++;
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void observe(ItemSpawnEvent event) {
        if(observing && event.getLocation().getWorld()==world && event.getLocation().getBlockX()>>4==CHUNK_X)
            spawned.add(event.getEntity().getItemStack().clone());
    }
    private void finish() {
        HandlerList.unregisterAll(this); report.put("server",Bukkit.getVersion()); report.put("failures",failures);
        report.put("playerFlow","NOT_TESTED; contents seeded through the actual NBT API, redstone/entities/removal run in server");
        report.put("clientVisual","NOT_TESTED");
        try {write(plugin.getDataFolder().toPath().resolve("trashcan-smoke.json"),report);}
        catch(Exception error){plugin.getLogger().log(Level.SEVERE,"Cannot write trash can report",error);}
        plugin.getLogger().log(failures.isEmpty()?Level.INFO:Level.SEVERE,"COOKERY_TRASHCAN_SMOKE failures="+failures.size());
    }
    private static void write(Path path,Object report) throws java.io.IOException {
        Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report),StandardCharsets.UTF_8);
    }
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
