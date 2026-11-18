package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.SteamerBehavior;
import net.kaleidoscope.cookery.item.SteamerItemContents;
import net.kaleidoscope.cookery.item.behavior.SteamerItemBehavior;
import net.kaleidoscope.cookery.recipe.*;
import net.kaleidoscope.cookery.util.CookeryBlockStateEnvelope;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.property.type.SlabType;
import net.momirealms.craftengine.core.entity.player.*;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.*;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockBreakEvent;
import java.util.*;
import java.util.function.Supplier;

/** One click acts on the selected block, never on a different layer in the stack. */
public final class SteamerInteraction {
    private static final ThreadLocal<Action> ACTIVE=new ThreadLocal<>();
    private SteamerInteraction() {}
    private static final class Action {
        final UseOnContext context;final ImmutableBlockState state;final SteamerBehavior behavior;
        final SteamerController original;final CompoundTag saved;final Item source;final int count;
        final Block block;final BlockState before;
        SteamerController owned;boolean writing,written,committed,originalRemoved;
        Action(UseOnContext context,ImmutableBlockState state,SteamerBehavior behavior,SteamerController controller){
            this.context=context;this.state=state;this.behavior=behavior;original=controller;owned=controller;
            saved=snapshot(controller);Item held=context.getPlayer().getItemInHand(context.getHand());
            source=held.copy();count=held.count();var pos=context.getClickedPos();
            block=((org.bukkit.World)context.getLevel().platformWorld()).getBlockAt(pos.x(),pos.y(),pos.z());before=block.getState();
        }
    }
    public static boolean active(BlockEntity entity){
        var a=ACTIVE.get();return a!=null && matches(a,entity);
    }
    private static boolean matches(Action a,BlockEntity entity){
        return entity.pos.equals(a.context.getClickedPos()) && entity.world!=null
                && entity.world.world().platformWorld()==a.context.getLevel().platformWorld();
    }
    public static void created(SteamerController controller){
        var a=ACTIVE.get();if(a!=null && a.writing && controller.blockEntity().pos.equals(a.context.getClickedPos()))a.owned=controller;
    }
    public static boolean suppressRemoval(SteamerController controller){
        var a=ACTIVE.get();if(a==null || !a.writing || a.committed || !matches(a,controller.blockEntity()))return false;
        if(controller==a.original)a.originalRemoved=true;return true;
    }
    public static InteractionResult use(UseOnContext context,ImmutableBlockState state,SteamerBehavior behavior,Supplier<InteractionResult> stack){
        if(ACTIVE.get()!=null)return InteractionResult.FAIL;
        if(!validPlayer(context) || !SteamerWorld.accessible(context.getLevel(),context.getClickedPos()))return InteractionResult.PASS;
        var original=current(context,behavior);
        if(original==null || !original.hasValidData() || original.removed() || SteamerItemBehavior.placing(original.blockEntity()))return InteractionResult.FAIL;
        var a=new Action(context,state,behavior,original);ACTIVE.set(a);
        try{
            if(!unchanged(a) || !InteractGuard.canInteract(context.getPlayer(),context.getLevel(),context.getClickedPos()) || !unchanged(a))return InteractionResult.FAIL;
            var above=context.getClickedPos().above();
            if(!SteamerWorld.accessible(context.getLevel(),above))return InteractionResult.FAIL;
            Item held=context.getPlayer().getItemInHand(context.getHand());
            boolean aboveSteamer=SteamerWorld.sameBlock(context.getLevel(),above,behavior.block());
            if(held.isEmpty() && context.getPlayer().isSecondaryUseActive() && (original.hasLid() || !aboveSteamer)){
                if(!change(a,state.with(behavior.getHasLidProperty(),!original.hasLid())))return InteractionResult.FAIL;
                feedback(a);return InteractionResult.SUCCESS_AND_CANCEL;
            }
            if(!held.isEmpty() && behavior.block().id().equals(held.id()))return stack.get();
            if(SteamerWorld.faceSturdy(context.getLevel(),above,Direction.DOWN) || !unchanged(a))return InteractionResult.PASS;
            var slots=original.snapshotSlots();
            for(int i=original.capacity();i<8;i++)if(slots.get(i)!=null)return InteractionResult.FAIL;
            if(!held.isEmpty() && original.canSteam(held)){
                CookingPlan plan=FoodRecipeRegistry.instance().readSnapshot(()->FoodRecipeRegistry.instance()
                        .planAccurate(ApplianceType.STEAMER,held.id(),behavior.cookingTime));
                int added=0;
                if(plan.valid() && plan.matched() && plan.workRequired()>0)for(int i=0;i<original.capacity() && added<a.count;i++){
                    if(slots.get(i)==null){slots.set(i,new SteamerInventory.Slot(held.copyWithCount(1),0,plan.workRequired(),plan,false));added++;}
                }
                if(added>0){
                    CompoundTag next=withInventory(a,slots);if(!unchanged(a))return InteractionResult.FAIL;
                    original.loadCustomData(next);
                    // Source splits the held stack in both survival and creative mode.
                    held.count(a.count-added);a.committed=true;changed(a);feedback(a);return InteractionResult.SUCCESS_AND_CANCEL;
                }
            }
            List<Item> output=new ArrayList<>();
            for(int i=0;i<original.capacity();i++)if(slots.get(i)!=null)output.add(slots.get(i).item().copy());
            if(!output.isEmpty()){
                CompoundTag empty=withInventory(a,Collections.nCopies(8,null));if(!unchanged(a))return InteractionResult.FAIL;
                original.loadCustomData(empty);a.committed=true;changed(a);
                for(Item item:output)InventoryUtils.giveOrHold(context.getPlayer(),InteractionHand.MAIN_HAND,item);
                feedback(a);return InteractionResult.SUCCESS_AND_CANCEL;
            }
            if(original.hasLid() || aboveSteamer)return InteractionResult.PASS;
            Item container=SteamerItemContents.build(Collections.nCopies(4,null));
            BlockPos pos=context.getClickedPos();
            if(!InteractGuard.canBreak(context.getPlayer(),context.getLevel(),pos.x(),pos.y(),pos.z()) || !unchanged(a))return InteractionResult.FAIL;
            var event=new BlockBreakEvent(a.block,(org.bukkit.entity.Player)context.getPlayer().platformPlayer());event.setDropItems(false);
            // CE translates Bukkit's event; do not dispatch a second custom break event.
            if(!event.callEvent() || !unchanged(a))return InteractionResult.FAIL;
            ImmutableBlockState next=original.capacity()==8?state.with(behavior.getTypeProperty(),SlabType.BOTTOM):null;
            if(!change(a,next))return InteractionResult.FAIL;
            InventoryUtils.giveOrHold(context.getPlayer(),InteractionHand.MAIN_HAND,container);feedback(a);return InteractionResult.SUCCESS_AND_CANCEL;
        }finally{
            try{if(a.written && !a.committed)rollback(a);}finally{ACTIVE.remove();}
        }
    }
    private static boolean change(Action a,ImmutableBlockState next){
        if(!unchanged(a))return false;
        a.writing=true;a.written=true;
        boolean success;
        try{success=next==null?CraftEngineBlocks.remove(a.block,false):CraftEngineBlocks.place(a.block.getLocation(),next,UpdateFlags.UPDATE_ALL,false);}
        finally{a.writing=false;}
        if(!success || !validPlayer(a.context) || !SteamerWorld.accessible(a.context.getLevel(),a.context.getClickedPos()) || !sourceUnchanged(a))return false;
        var current=current(a.context,a.behavior);
        if(next==null){if(current!=null || !a.block.isEmpty())return false;}
        else{
            if(current==null || current!=a.owned || current.removed() || !current.hasValidData() || current.blockEntity().blockState!=next)return false;
            // A replacement controller starts empty; the same controller must retain every item.
            if(current==a.original && !inventory(a.saved).equals(inventory(snapshot(current)))
                    || current!=a.original && current.getItemCount()!=0)return false;
            current.loadCustomData(a.saved);
        }
        a.committed=true;if(next!=null)changed(a);return true;
    }
    private static void rollback(Action a){
        if(!SteamerWorld.accessible(a.context.getLevel(),a.context.getClickedPos()))throw new IllegalStateException("Steamer interaction rollback lost region/chunk");
        var current=current(a.context,a.behavior);
        if(current!=a.owned && !(current==null && a.block.isEmpty())){
            if(a.originalRemoved){
                var slots=SteamerInventory.read(CookeryBlockStateEnvelope.payload(a.saved,SteamerController.DATA_KEY),a.behavior.cookingTime);
                for(Item item:SteamerItemContents.split(slots,a.state.get(a.behavior.getTypeProperty())==SlabType.DOUBLE?8:4,false))
                    a.context.getLevel().dropItemNaturally(Vec3d.atCenterOf(a.context.getClickedPos()),item);
            }
            return;
        }
        a.writing=true;
        try{
            if(!a.before.update(true,false))throw new IllegalStateException("Steamer interaction block rollback failed");
            var restored=current(a.context,a.behavior);if(restored==null)throw new IllegalStateException("Steamer interaction controller missing after rollback");
            restored.loadCustomData(a.saved);a.owned=restored;changed(a);
        }finally{a.writing=false;}
    }
    private static CompoundTag inventory(CompoundTag root){
        return SteamerItemContents.payload(SteamerInventory.read(CookeryBlockStateEnvelope.payload(root,SteamerController.DATA_KEY),1200));
    }
    private static CompoundTag withInventory(Action a,List<SteamerInventory.Slot> slots){
        CompoundTag root=a.saved.deepClone(), data=CookeryBlockStateEnvelope.payload(root,SteamerController.DATA_KEY).deepClone();
        CompoundTag next=SteamerItemContents.payload(slots);for(String key:next.keySet())data.put(key,next.get(key));
        CookeryBlockStateEnvelope.put(root,SteamerController.DATA_KEY,data);return root;
    }
    private static void changed(Action a){
        a.context.getLevel().storageWorld().blockEntityChanged(a.context.getClickedPos());
        var current=current(a.context,a.behavior);if(current!=null)current.refreshElementState();
    }
    private static void feedback(Action a){a.context.getPlayer().swingHand(a.context.getHand());}
    private static CompoundTag snapshot(SteamerController c){var tag=new CompoundTag();c.saveCustomData(tag);return tag;}
    private static boolean unchanged(Action a){
        var current=current(a.context,a.behavior);
        return validPlayer(a.context) && current==a.original && !current.removed() && current.hasValidData() && current.blockEntity().isValid()
                && current.blockEntity().blockState==a.state && sourceUnchanged(a) && a.saved.equals(snapshot(current));
    }
    private static boolean sourceUnchanged(Action a){
        Item held=a.context.getPlayer().getItemInHand(a.context.getHand());
        return held!=null && held.count()==a.count && (a.source.isEmpty()?held.isEmpty():!held.isEmpty() && held.isSimilar(a.source));
    }
    private static boolean validPlayer(UseOnContext context){
        return context.getPlayer()!=null && context.getPlayer().platformPlayer() instanceof org.bukkit.entity.Player actor
                && Bukkit.isOwnedByCurrentRegion(actor) && actor.isOnline() && actor.isValid() && !actor.isDead()
                && actor.getGameMode()!=GameMode.SPECTATOR && actor.getWorld()==context.getLevel().platformWorld();
    }
    private static SteamerController current(UseOnContext context,SteamerBehavior behavior){
        if(!SteamerWorld.accessible(context.getLevel(),context.getClickedPos()))return null;
        var entity=context.getLevel().storageWorld().getBlockEntityAtIfLoaded(context.getClickedPos());
        return entity==null?null:entity.controller.get(SteamerController.class,behavior.getControllerId());
    }
}
