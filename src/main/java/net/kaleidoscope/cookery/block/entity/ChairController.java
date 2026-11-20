package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.util.CookeryBlockStateEnvelope;

import net.kaleidoscope.cookery.block.behavior.ChairBehavior;
import net.kaleidoscope.cookery.item.CarpetColors;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.element.FurnitureElement;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

// Source chairs consume one carpet even in creative; other interactions try the seat.
public final class ChairController extends FurnitureController {
    private static final String DATA_KEY = "kaleidoscopecookery:chair";
    private static final String K_CARPET = "carpet";

    private final ChairBehavior behavior;
    private final ChairElement element;
    private volatile Key carpet;
    private boolean interacting;
    private boolean removing;
    private CompoundTag rejectedState;

    public ChairController(Furniture furniture, ChairBehavior behavior) {
        super(furniture);
        this.behavior = behavior;
        this.element = new ChairElement(this, behavior);
    }

    // 当前坐垫的展示模型 没铺返回 null
    @Nullable
    public Key carpetModel() {
        Key current = this.carpet;
        return current == null ? null : CarpetColors.chairModel(current);
    }

    @Override
    public InteractionResult useOnFurniture(FurnitureHitBox hitBox, InteractEntityContext context) {
        Player player = context.getPlayer();
        if (player == null || context.getHand() != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (interacting || removing || rejectedState != null || !owned(player) || !furniture().isValid()
                || !InteractGuard.canInteract(player, furniture().position())) return InteractionResult.FAIL;
        interacting = true;
        try {
            Item inHand = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!ItemUtils.isEmpty(inHand) && CarpetColors.isCarpet(inHand.id()))
                return putCarpet(player, inHand, inHand.id());
            // The source also attempts seating while sneaking; CE's generic
            // fallback skips it, so use the configured seat directly.
            if (hitBox != null) for (var seat : hitBox.seats()) {
                if (!seat.isOccupied() && seat.spawnSeat(player, furniture().position()))
                    return InteractionResult.SUCCESS_AND_CANCEL;
            }
            return InteractionResult.SUCCESS_AND_CANCEL;
        } finally {
            interacting = false;
        }
    }

    private InteractionResult putCarpet(Player player, Item inHand, Key id) {
        Item expected = inHand.copy();
        Key previous = this.carpet;
        if (id.equals(previous)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        // Resolve the refund before changing anything. Missing content must not
        // silently erase an installed carpet.
        Item refund = previous == null ? null : InventoryUtils.createOrEmpty(previous);
        if (previous != null && ItemUtils.isEmpty(refund)) return InteractionResult.FAIL;
        if (!furniture().isValid() || !owned(player)) return InteractionResult.FAIL;
        Item held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (ItemUtils.isEmpty(held) || held.count() != expected.count() || !held.isSimilar(expected))
            return InteractionResult.FAIL;
        player.setItemInHand(InteractionHand.MAIN_HAND, held.copyWithCount(held.count() - 1));
        this.carpet = id;
        furniture().setUnsaved();
        try {
            if (previous != null) furniture().world().dropItemNaturally(furniture().position(), refund);
        } finally {
            refresh();
        }
        var bukkit = (org.bukkit.entity.Player) player.platformPlayer();
        bukkit.getWorld().playSound(new org.bukkit.Location((org.bukkit.World) furniture().world().platformWorld(),
                furniture().position().x, furniture().position().y, furniture().position().z),
                "minecraft:block.wool.place", 1f, 1f);
        player.swingHand(InteractionHand.MAIN_HAND);
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    private boolean owned(Player player) {
        var bukkit = (org.bukkit.entity.Player) player.platformPlayer();
        var pos = furniture().position();
        return bukkit != null && bukkit.getGameMode() != org.bukkit.GameMode.SPECTATOR
                && org.bukkit.Bukkit.isOwnedByCurrentRegion(bukkit)
                && org.bukkit.Bukkit.isOwnedByCurrentRegion((org.bukkit.World) pos.world().platformWorld(),
                        ((int) Math.floor(pos.x)) >> 4, ((int) Math.floor(pos.z)) >> 4);
    }

    // trackedBy 是列表快照 getTrackedBy 返回的是活的集合 别的 region 线程正在改它
    private void refresh() {
        if (!furniture().isValid()) return;
        this.element.rebuild();
        furniture().trackedBy().forEach(this.element::update);
        furniture().setUnsaved();
    }

    @Override
    public void gatherElements(Consumer<FurnitureElement> consumer) {
        consumer.accept(this.element);
    }

    @Override
    public void onLoad() {
        this.element.rebuild();
    }

    @Override
    public void onPlace(Player player) {
        this.element.rebuild();
    }

    @Override
    public void preRemove(Player player) {
        if (rejectedState != null) throw new IllegalStateException("Cannot remove chair with unreadable carpet state");
        if (removing) return;
        Key previous = this.carpet;
        if (previous == null) {
            return;
        }
        removing = true;
        try {
            Item drop = InventoryUtils.createOrEmpty(previous);
            if (ItemUtils.isEmpty(drop)) throw new IllegalStateException("Missing chair carpet item " + previous);
            this.carpet = null;
            furniture().setUnsaved();
            if (player == null || !player.canInstabuild())
                furniture().world().dropItemNaturally(furniture().position(), drop);
        } finally {
            removing = false;
        }
    }

    @Override
    public void saveCustomData(CompoundTag tag) {
        if (rejectedState != null) {
            for (String key : new String[]{DATA_KEY, CookeryBlockStateEnvelope.sidecar(DATA_KEY)}) {
                var original = rejectedState.get(key);
                if (original != null) tag.put(key, original.deepClone());
                else tag.remove(key);
            }
            return;
        }
        Key current = this.carpet;
        CompoundTag data = new CompoundTag();
        if (current != null) data.putString(K_CARPET, current.asString());
        CookeryBlockStateEnvelope.put(tag, DATA_KEY, data);
    }

    @Override
    public void loadCustomData(CompoundTag tag) {
        try {
            if (tag.containsKey(DATA_KEY) && !(tag.get(DATA_KEY) instanceof CompoundTag))
                throw new IllegalArgumentException("Invalid chair payload");
            CompoundTag data = CookeryBlockStateEnvelope.payload(tag, DATA_KEY);
            Key restored = null;
            if (data != null && data.containsKey(K_CARPET)) {
                restored = CarpetColors.parse(data.getString(K_CARPET, null));
                if (restored == null) throw new IllegalArgumentException("Invalid chair carpet");
            }
            this.carpet = restored;
            this.rejectedState = null;
            this.element.rebuild();
        } catch (IllegalArgumentException exception) {
            this.rejectedState = tag.deepClone();
            throw exception;
        }
    }
}
