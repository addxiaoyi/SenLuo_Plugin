package net.kaleidoscope.deco;

import net.kaleidoscope.series.InventoryTrade;
import net.kaleidoscope.series.Items;
import net.kaleidoscope.series.SavedState;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Chair cushions are furniture state; breaking returns an unpadded chair and the separate carpet. */
final class WoodenSeatController extends FurnitureController {
    static final String SAVE_KEY = "kaleidoscope_deco:wooden_seat_v1";
    private final DecoPlugin plugin;
    private final WoodenSeats.Seat seat;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean removalStarted = new AtomicBoolean();
    private String carpet = WoodenSeats.EMPTY;
    private net.momirealms.craftengine.libraries.nbt.Tag rejected;
    private FurnitureBreakEvent approvedBreak;
    private long approvedBreakTick;

    WoodenSeatController(Furniture furniture, DecoPlugin plugin) {
        super(furniture);
        this.plugin = plugin;
        seat = WoodenSeats.parse(furniture.id().asString());
    }

    boolean blocksRemoval() { return busy.get() || rejected != null || removalStarted.get(); }
    String carpet() { return carpet; }

    private Location location() {
        var position = furniture().position();
        return new Location((org.bukkit.World) position.world().platformWorld(), position.x, position.y, position.z);
    }

    private boolean accessible(Player player) {
        return plugin.isEnabled() && rejected == null && !removalStarted.get() && furniture().isValid()
                && player.isOnline() && !player.isDead()
                && player.getGameMode() != GameMode.SPECTATOR && Bukkit.isOwnedByCurrentRegion(player)
                && Bukkit.isOwnedByCurrentRegion(location()) && plugin.mayUse(player, location());
    }

    @Override public InteractionResult useOnFurniture(FurnitureHitBox hitbox, InteractEntityContext context) {
        if (context.getHand() != InteractionHand.MAIN_HAND || context.getPlayer() == null) return InteractionResult.PASS;
        // The fixed source's cook-stool has no sit or cushion event.
        if (!seat.chair()) return InteractionResult.PASS;
        Player player = (Player) context.getPlayer().platformPlayer();
        if (!accessible(player) || !busy.compareAndSet(false, true)) return InteractionResult.FAIL;
        try {
            String id = Items.id(player.getInventory().getItemInMainHand());
            String next = WoodenSeats.carpetColor(id);
            if (next != null || id.equals("minecraft:shears")) {
                String desired = next == null ? WoodenSeats.EMPTY : next;
                plugin.attempt(player, () -> changeCarpet(player, desired, next != null));
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
            // Explicit native seat dispatch also honors the source fallback while sneaking.
            if (hitbox != null) for (var nativeSeat : hitbox.seats()) {
                if (!nativeSeat.isOccupied() && nativeSeat.spawnSeat(context.getPlayer(), furniture().position())) break;
            }
            return InteractionResult.SUCCESS_AND_CANCEL;
        } finally {
            busy.set(false);
        }
    }

    private void changeCarpet(Player player, String next, boolean placing) {
        String previous = carpet;
        if (previous.equals(next)) return;
        String nextVariant = WoodenSeats.variant(next);
        if (!furniture().config().variants().containsKey(nextVariant))
            throw new IllegalStateException("Missing Deco cushion variant: " + nextVariant);
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        ItemStack refund = creative || previous.equals(WoodenSeats.EMPTY) ? null
                : Items.create("minecraft:" + previous + "_carpet");
        var inventory = player.getInventory();
        int heldSlot = inventory.getHeldItemSlot();
        ItemStack expected = inventory.getItemInMainHand().clone();
        GameMode expectedMode = player.getGameMode();
        ItemStack[] expectedInventory = inventory.getStorageContents().clone();
        for (int i = 0; i < expectedInventory.length; i++)
            if (expectedInventory[i] != null) expectedInventory[i] = expectedInventory[i].clone();
        Item[] provisional = {null};
        boolean[] visualChanged = {false};
        boolean committed = false;
        try {
            committed = InventoryTrade.exchangeHeld(inventory, expected::isSimilar, placing && !creative ? 1 : 0,
                    List.of(), () -> {
                        if (!unchanged(player, heldSlot, expected, previous, expectedMode, expectedInventory)) return false;
                        if (refund != null) {
                            Location at = location().add(0, 1, 0);
                            provisional[0] = at.getWorld().dropItem(at, refund.clone(), item -> item.setPickupDelay(10));
                            if (!validDrop(provisional[0], refund)
                                    || !unchanged(player, heldSlot, expected, previous, expectedMode, expectedInventory))
                                return false;
                        }
                        // Every cushion variant has the same native hitboxes; this changes decoration, not placement.
                        visualChanged[0] = furniture().setVariant(nextVariant, true);
                        return visualChanged[0]
                                && unchanged(player, heldSlot, expected, previous, expectedMode, expectedInventory);
                    });
            if (committed) {
                carpet = next;
                furniture().setUnsaved();
                location().getWorld().playSound(location(), "minecraft:entity.llama.swag", 1, 1);
            }
        } finally {
            if (!committed) {
                if (provisional[0] != null && provisional[0].isValid()) provisional[0].remove();
                if (visualChanged[0] && furniture().isValid()) furniture().setVariant(WoodenSeats.variant(previous), true);
            }
        }
    }

    private boolean unchanged(Player player, int slot, ItemStack expected, String previous,
                              GameMode mode, ItemStack[] contents) {
        ItemStack current = player.getInventory().getItemInMainHand();
        return accessible(player) && carpet.equals(previous) && player.getInventory().getHeldItemSlot() == slot
                && player.getGameMode() == mode && current != null && current.getAmount() == expected.getAmount()
                && expected.isSimilar(current) && sameInventory(contents, player.getInventory().getStorageContents());
    }

    private static boolean sameInventory(ItemStack[] expected, ItemStack[] actual) {
        if (expected.length != actual.length) return false;
        for (int i = 0; i < expected.length; i++) {
            ItemStack left = expected[i], right = actual[i];
            if (left == null || left.isEmpty()) {
                if (right != null && !right.isEmpty()) return false;
            } else if (right == null || left.getAmount() != right.getAmount() || !left.isSimilar(right)) return false;
        }
        return true;
    }

    private static boolean validDrop(Item drop, ItemStack expected) {
        if (drop == null || !drop.isValid()) return false;
        ItemStack actual = drop.getItemStack();
        return actual != null && expected.getAmount() == actual.getAmount() && expected.isSimilar(actual);
    }

    @Override public void onLoad() {
        if (rejected != null) return;
        String variant = WoodenSeats.variant(carpet);
        var current = furniture().currentVariant();
        // CE returns false for an already active variant; load/place callbacks are intentionally idempotent.
        if (current != null && variant.equals(current.name())) return;
        // Cushion variants share the same native collision geometry; restoration must not re-test support blocks.
        if (!furniture().setVariant(variant, true)) throw new IllegalStateException("Cannot restore Deco cushion variant");
    }

    @Override public void onPlace(net.momirealms.craftengine.core.entity.player.Player player) { onLoad(); }

    void acceptPlayerBreak(FurnitureBreakEvent event) {
        if (event.isCancelled() || blocksRemoval()) return;
        approvedBreak = event;
        approvedBreakTick = location().getWorld().getGameTime();
    }

    @Override public void preRemove(net.momirealms.craftengine.core.entity.player.Player player) {
        // CE destroys metadata, colliders and seats in a finally block even if this callback throws.
        // Player vetoes belong to FurnitureBreakEvent; an API-forced removal must preserve unresolved state.
        if (!removalStarted.compareAndSet(false, true)) return;
        Item[] provisional = {null};
        try {
            if (rejected != null) { recoverRemoval("unreadable cushion state"); return; }
            if (busy.get()) { recoverRemoval("forced removal during an interaction"); return; }
            var actor = player == null ? null : (Player) player.platformPlayer();
            boolean noDrops = actor != null && approvedBreak != null && !approvedBreak.isCancelled()
                    && actor.getUniqueId().equals(approvedBreak.player().getUniqueId())
                    && approvedBreakTick == location().getWorld().getGameTime() && !approvedBreak.dropItems();
            approvedBreak = null;
            if (carpet.equals(WoodenSeats.EMPTY)) return;
            if (!Bukkit.isOwnedByCurrentRegion(location())) { recoverRemoval("forced removal outside its owning region"); return; }
            boolean creative = player != null && player.canInstabuild();
            if (!creative && !noDrops) {
                ItemStack refund = Items.create("minecraft:" + carpet + "_carpet");
                Location at = location().add(0, 1, 0);
                Item drop = at.getWorld().dropItem(at, refund.clone(), item -> {
                    provisional[0] = item;
                    item.setPickupDelay(10);
                });
                provisional[0] = drop;
                if (!validDrop(drop, refund)) {
                    if (drop != null && drop.isValid()) drop.remove();
                    provisional[0] = null;
                    recoverRemoval("carpet refund was cancelled or changed");
                    return;
                }
            }
            carpet = WoodenSeats.EMPTY;
        } catch (RuntimeException | LinkageError failure) {
            if (provisional[0] != null) {
                try { if (provisional[0].isValid()) provisional[0].remove(); }
                catch (RuntimeException | LinkageError cleanup) { failure.addSuppressed(cleanup); }
            }
            plugin.getLogger().log(Level.SEVERE, "Deco seat removal callback failed; preserving its unresolved cushion", failure);
            recoverRemoval("removal callback failed: " + failure);
        }
    }

    private void recoverRemoval(String reason) {
        CompoundTag saved = new CompoundTag();
        byte[] encoded = null;
        try {
            saveCustomData(saved);
            Location at = location();
            saved.putString("recovery_world", at.getWorld().getUID().toString());
            saved.putString("recovery_furniture", seat.id());
            saved.putString("recovery_position", at.getX() + "," + at.getY() + "," + at.getZ());
            saved.putString("recovery_reason", reason);
            saved.putString("recovery_scope", "unresolved cushion state only; native base-furniture loot is separate");
            encoded = FurniturePersistentData.of(saved).toBytes();
            var folder = plugin.getDataFolder().toPath().resolve("recovery");
            Files.createDirectories(folder);
            var file = folder.resolve(furniture().uuid() + "-" + UUID.randomUUID() + ".nbt");
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            plugin.getLogger().warning("Forced Deco seat removal retained unresolved cushion state for manual recovery: " + file);
        } catch (Exception | LinkageError failure) {
            // A throw cannot cancel CE destruction and would additionally skip its base-furniture loot.
            String emergency = encoded == null ? saved.toString() : Base64.getEncoder().encodeToString(encoded);
            plugin.getLogger().log(Level.SEVERE, "DECO SEAT RECOVERY WRITE FAILED; manual state=" + emergency, failure);
        }
    }

    @Override public void saveCustomData(CompoundTag root) {
        if (rejected != null) { root.put(SAVE_KEY, rejected.deepClone()); return; }
        CompoundTag payload = new CompoundTag();
        payload.putString("seat", seat.id());
        payload.putString("carpet", carpet);
        root.put(SAVE_KEY, SavedState.envelope(SavedState.namespace(SAVE_KEY), 1, SavedState.SERIES_SOURCE, payload));
    }

    @Override public void loadCustomData(CompoundTag root) {
        if (!root.containsKey(SAVE_KEY)) return;
        try {
            CompoundTag payload = SavedState.payload(root.getCompound(SAVE_KEY), SavedState.namespace(SAVE_KEY),
                    SavedState.SERIES_SOURCE, 1, null);
            if (payload == null || !seat.id().equals(SavedState.text(payload, "seat")))
                throw new IllegalArgumentException("Deco cushion belongs to a different seat");
            String next = SavedState.text(payload, "carpet");
            WoodenSeats.variant(next);
            if (!seat.chair() && !next.equals(WoodenSeats.EMPTY))
                throw new IllegalArgumentException("The fixed cook-stool has no cushion model");
            carpet = next;
        } catch (RuntimeException failure) {
            rejected = root.get(SAVE_KEY).deepClone();
            plugin.getLogger().log(Level.SEVERE, "Deco seat save preserved and paused at " + location(), failure);
        }
    }
}
