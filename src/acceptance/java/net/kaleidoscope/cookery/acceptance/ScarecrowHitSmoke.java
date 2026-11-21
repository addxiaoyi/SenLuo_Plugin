package net.kaleidoscope.cookery.acceptance;

import com.google.gson.GsonBuilder;
import net.kaleidoscope.cookery.block.entity.ScarecrowController;
import net.kaleidoscope.cookery.block.entity.ScarecrowElement;
import net.kaleidoscope.cookery.util.*;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;

/** Actual CE events with native, unregistered ServerPlayer fixtures. No network/login/client simulation. */
final class ScarecrowHitSmoke implements Listener {
    private static final int CHUNK_X = 26;
    private final JavaPlugin plugin;
    private final World world;
    private final Map<String,Object> report=new LinkedHashMap<>();
    private final List<String> failures=new ArrayList<>();
    private final List<BukkitFurniture> fixtures=new ArrayList<>();
    private final List<ItemStack> drops=new ArrayList<>();
    private Player first, second, creative;
    private BukkitFurniture active;
    private long firstTick;
    private boolean observe, suppressDrops, finished;

    private ScarecrowHitSmoke(JavaPlugin plugin, World world) { this.plugin=plugin;this.world=world; }
    static void start(JavaPlugin plugin) {
        World world=Bukkit.getWorld("acceptance-world");
        if(world==null)throw new IllegalStateException("Scarecrow hit fixture needs acceptance-world");
        var test=new ScarecrowHitSmoke(plugin,world);Bukkit.getPluginManager().registerEvents(test,plugin);
        world.getChunkAtAsync(CHUNK_X,0).whenComplete((chunk,error)->{
            if(error!=null){test.fail(error);return;}
            Bukkit.getRegionScheduler().execute(plugin,world,CHUNK_X,0,()->test.run(test::begin));
        });
    }

    private void begin() throws Exception {
        world.addPluginChunkTicket(CHUNK_X,0,plugin);
        first=nativeActor("ScarecrowProbeA",GameMode.SURVIVAL);
        second=nativeActor("ScarecrowProbeB",GameMode.SURVIVAL);
        creative=nativeActor("ScarecrowProbeC",GameMode.CREATIVE);
        check(!first.isOnline() && Bukkit.getPlayer(first.getUniqueId())==null,"Probe actor unexpectedly registered online");
        if (!Bukkit.isOwnedByCurrentRegion(first)) {
            // Folia owns only tracked players or their active connections on a region thread.
            // A live player is needed to exercise the hit sequence there.
            check(!first.isOnline() && !second.isOnline() && !creative.isOnline(), "Unexpected connected actor");
            report.put("playerHits", "NEEDS_CONNECTED_REGION_OWNED_PLAYER");
            finish();
            return;
        }
        var definition=CraftEngineFurniture.byId(Key.of("kaleidoscopecookery:scarecrow"));
        check(definition.settings().hitTimes()==1,"CE must dispatch every hit to source timing");
        report.put("perHitBreakEventConfigured",true);
        active=place(0);seed(active);
        var canceled=new FurnitureBreakEvent(first,active);canceled.setCancelled(true);Bukkit.getPluginManager().callEvent(canceled);
        check(canceled.isCancelled() && active.isValid(),"Protected event removed furniture");
        check(hit(active,first).isCancelled(),"First accepted hit after cancellation must wobble");
        firstTick=world.getGameTime();report.put("earlyCancellationChecks",1);
        later(1,()->{
            var controller=controller(active);
            controller.<ScarecrowController>createFurnitureTicker().tick(active,controller);
            check(Math.abs(controller.wobbleAngle())>1,"Source first-hit wobble did not advance on server ticker");
            int[] checked={0};
            controller.gatherElements(element->{if(element instanceof ScarecrowElement visual){
                for(float angle:new float[]{0,2.598076f,-2.598076f,0})visual.applyWobble(angle,List.of(),1);
                checked[0]+=4;
            }});
            check(checked[0]==4,"Wobble transforms not encoded");report.put("encodedWobbleFrames",4);
            later(4,this::secondAtBoundary);
        });
    }

    private void secondAtBoundary() throws Exception {
        check(world.getGameTime()-firstTick==5,"Five-tick boundary test drifted");
        var event=hit(active,second);
        check(!event.isCancelled(),"Different attacker within five ticks did not destroy");
        observe=true;drops.clear();remove(active,second,event.dropItems());observe=false;
        check(!active.isValid(),"Accepted source break did not remove furniture");
        check(drops.size()==2,"Survival break must return one tool and one scarecrow item");
        check(drops.stream().anyMatch(stack->stack.getType()==Material.IRON_PICKAXE),"Equipment missing on survival break");
        report.put("sharedFiveTickBoundary",true);report.put("survivalDropStacks",drops.size());
        active=place(0);check(hit(active,first).isCancelled(),"Fresh fixture first hit did not wobble");
        firstTick=world.getGameTime();later(6,this::expiredWindow);
    }

    private void expiredWindow() throws Exception {
        check(world.getGameTime()-firstTick==6,"Expired-window test drifted");
        check(hit(active,first).isCancelled() && active.isValid(),"Six-tick gap incorrectly destroyed scarecrow");
        var followup=hit(active,second);check(!followup.isCancelled(),"New source window did not accept second hit");
        remove(active,second,false);report.put("sixTickGapResetsWindow",true);
        active=place(0);seed(active);observe=true;drops.clear();
        var creativeBreak=hit(active,creative);check(!creativeBreak.isCancelled(),"Creative hit should destroy immediately");
        remove(active,creative,creativeBreak.dropItems());observe=false;
        check(!active.isValid() && drops.isEmpty(),"Creative break returned item/equipment");report.put("creativeImmediateNoDrops",true);
        active=place(0);seed(active);observe=true;drops.clear();
        check(hit(active,first).isCancelled(),"Suppression fixture first hit did not wobble");
        suppressDrops=true;var suppressed=hit(active,second);suppressDrops=false;
        check(!suppressed.isCancelled()&&!suppressed.dropItems(),"Drop suppression hook not honored");
        remove(active,second,suppressed.dropItems());observe=false;
        check(!active.isValid() && drops.isEmpty(),"Disabled drops still returned equipment");report.put("eventDropSuppression",true);
        finish();
    }

    private BukkitFurniture place(int index) {
        var at=new Location(world,CHUNK_X*16+4.5+index*3,160,8.5);
        at.clone().subtract(0,1,0).getBlock().setType(Material.STONE);
        var furniture=CraftEngineFurniture.place(at,Key.of("kaleidoscopecookery:scarecrow"),"ground",false);
        check(furniture!=null,"Cannot place hit fixture");fixtures.add(furniture);return furniture;
    }
    private ScarecrowController controller(BukkitFurniture furniture) {
        var controller=furniture.controller.get(ScarecrowController.class,0);check(controller!=null,"Missing hit controller");return controller;
    }
    private void seed(BukkitFurniture furniture) {
        Item[] items={Item.empty(),InventoryUtils.wrap(new ItemStack(Material.IRON_PICKAXE)),Item.empty()};
        CompoundTag data=new CompoundTag();data.putInt("data_version",VersionHelper.WORLD_VERSION);data.put("items",BlockEntityNbt.saveSlots(items));
        CompoundTag root=new CompoundTag();CookeryBlockStateEnvelope.put(root,"kaleidoscopecookery:scarecrow",data);controller(furniture).loadCustomData(root);
    }
    private FurnitureBreakEvent hit(BukkitFurniture furniture,Player actor) {
        var event=new FurnitureBreakEvent(actor,furniture);event.setDropItems(actor.getGameMode()!=GameMode.CREATIVE);
        Bukkit.getPluginManager().callEvent(event);return event;
    }
    private void remove(BukkitFurniture furniture,Player actor,boolean items) {
        // A minimal CE adapter is only used by remove/preRemove. Unexpected API use fails loudly.
        Class<net.momirealms.craftengine.core.entity.player.Player> api=net.momirealms.craftengine.core.entity.player.Player.class;
        var adapted=(net.momirealms.craftengine.core.entity.player.Player)Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(proxy,method,args)->switch(method.getName()) {
            case "platformPlayer" -> actor;
            case "canInstabuild","isCreativeMode" -> actor.getGameMode()==GameMode.CREATIVE;
            case "uuid" -> actor.getUniqueId();
            case "world" -> BukkitAdaptor.adapt(world);
            case "getItemInHand" -> Item.empty();
            case "luck" -> 0.0d; // Native fixture has the default zero-luck attribute.
            case "toString" -> "unregistered scarecrow acceptance actor";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy==args[0];
            default -> throw new UnsupportedOperationException("Unexpected fixture CE API: "+method);
        });
        check(CraftEngineFurniture.remove(furniture,adapted,items,true),"CE refused accepted removal");
    }

    /** Version-inspected native constructors; never joins, saves player data or bypasses online authentication. */
    @SuppressWarnings({"unchecked","rawtypes"}) private Player nativeActor(String name,GameMode mode) throws Exception {
        Class<?> server=Class.forName("net.minecraft.server.MinecraftServer"),level=Class.forName("net.minecraft.server.level.ServerLevel");
        Class<?> profile=Class.forName("com.mojang.authlib.GameProfile"),info=Class.forName("net.minecraft.server.level.ClientInformation");
        Object nativePlayer=Class.forName("net.minecraft.server.level.ServerPlayer").getConstructor(server,level,profile,info).newInstance(
                server.getMethod("getServer").invoke(null),world.getClass().getMethod("getHandle").invoke(world),
                profile.getConstructor(UUID.class,String.class).newInstance(UUID.randomUUID(),name),info.getMethod("createDefault").invoke(null));
        nativePlayer.getClass().getMethod("setPos",double.class,double.class,double.class).invoke(nativePlayer,CHUNK_X*16+3.5,160.,8.5);
        Object gameMode=nativePlayer.getClass().getField("gameMode").get(nativePlayer);
        Class<? extends Enum> gameType=(Class<? extends Enum>)Class.forName("net.minecraft.world.level.GameType");
        var setter=gameMode.getClass().getDeclaredMethod("setGameModeForPlayer",gameType,gameType);setter.setAccessible(true);
        setter.invoke(gameMode,Enum.valueOf(gameType,mode.name()),Enum.valueOf(gameType,"SURVIVAL"));
        Player actor=(Player)nativePlayer.getClass().getMethod("getBukkitEntity").invoke(nativePlayer);
        check(actor.getGameMode()==mode && actor.getWorld()==world,"Native actor fixture mode/world mismatch");return actor;
    }

    @EventHandler(priority=EventPriority.LOW) public void suppress(FurnitureBreakEvent event) {
        if(suppressDrops&&event.furniture()==active)event.setDropItems(false);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void observe(ItemSpawnEvent event) {
        if(observe&&event.getLocation().getWorld()==world&&event.getLocation().getBlockX()>>4==CHUNK_X)drops.add(event.getEntity().getItemStack().clone());
    }
    private void later(long ticks,Step step){Bukkit.getRegionScheduler().runDelayed(plugin,world,CHUNK_X,0,task->run(step),ticks);}
    private void run(Step step){try{step.run();}catch(Exception|LinkageError error){fail(error);}}
    private void fail(Throwable error){failures.add(error.toString());plugin.getLogger().log(Level.SEVERE,"Scarecrow hit acceptance failure",error);finish();}
    private void finish() {
        if(finished)return;finished=true;observe=false;HandlerList.unregisterAll(this);
        for(var furniture:fixtures)if(furniture.isValid())furniture.destroy();
        report.put("server",Bukkit.getVersion());report.put("failures",failures);
        report.put("scope","Native unregistered players + real CE break event dispatch/API removal; no login/network attack/client rendering");
        try{Files.writeString(plugin.getDataFolder().toPath().resolve("scarecrow-hit-smoke.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report),StandardCharsets.UTF_8);}
        catch(Exception error){plugin.getLogger().log(Level.SEVERE,"Cannot write scarecrow hit report",error);}
        plugin.getLogger().log(failures.isEmpty()?Level.INFO:Level.SEVERE,"COOKERY_SCARECROW_HIT_SMOKE failures="+failures.size());
    }
    @FunctionalInterface private interface Step {void run() throws Exception;}
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
