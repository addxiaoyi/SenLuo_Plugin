package net.kaleidoscope.tavern;

import net.kaleidoscope.series.InventoryTrade;
import net.kaleidoscope.series.InventoryTradeEvent;
import net.kaleidoscope.series.Items;
import net.kaleidoscope.series.SavedItems;
import net.kaleidoscope.series.SavedState;
import net.momirealms.antigrieflib.Flag;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Placed bottle plus the Molotov-only path through the source's four storage furniture types. */
final class MolotovController extends FurnitureController {
    private static final String SAVE_KEY = "kaleidoscope_tavern:molotov_storage_v1";
    private final TavernPlugin plugin;
    private final Supplier<Molotov> service;
    private final MolotovPhysics.Storage storage;
    private final Location anchor;
    private final ItemStack[] slots;
    private final List<ItemDisplay> displays = new ArrayList<>();
    private final AtomicBoolean interacting = new AtomicBoolean();
    private UUID operator;
    private Tag rejected;
    private boolean blocked, loaded, removed, powered;
    private FurnitureBreakEvent removalEvent;

    MolotovController(Furniture furniture, TavernPlugin plugin, Supplier<Molotov> service) {
        super(furniture);
        this.plugin = plugin; this.service = service;
        storage = MolotovPhysics.Storage.of(furniture.id().asString());
        var at = furniture.position();
        anchor = new Location((World) at.world().platformWorld(), Math.floor(at.x), Math.floor(at.y), Math.floor(at.z), at.yRot, 0);
        slots = new ItemStack[storage.slots];
    }

    Location location() { return anchor.clone(); }
    boolean ground() { return storage == MolotovPhysics.Storage.GROUND; }
    boolean busy() { return interacting.get(); }
    boolean blocked() { return blocked; }
    boolean hasContents() { return Arrays.stream(slots).anyMatch(Objects::nonNull); }
    boolean usable() { return !removed && !blocked && plugin.isEnabled() && furniture().isValid() && Molotov.owned(anchor); }
    boolean blocksBreak(FurnitureBreakEvent event) { return blocked || hasContents() || busy() && event != removalEvent; }
    Entity bukkitEntity() { return furniture() instanceof BukkitFurniture bukkit ? bukkit.baseEntity() : null; }

    @Override public InteractionResult useOnFurniture(FurnitureHitBox hitbox, InteractEntityContext context) {
        if (context.getHand() != InteractionHand.MAIN_HAND || context.getPlayer() == null) return InteractionResult.PASS;
        Player player = (Player) context.getPlayer().platformPlayer();
        var point = context.getClickLocation();
        BlockFace face = BlockFace.valueOf(context.getClickedFace().name());
        int slot = MolotovPhysics.clickedSlot(storage, MolotovPhysics.facing(anchor.getYaw()), face,
                point.x() - anchor.getX(), point.y() - anchor.getY(), point.z() - anchor.getZ());
        if (slot < 0 || !accessible(player)) return InteractionResult.FAIL;
        interact(player, slot);
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    private boolean accessible(Player player) {
        return usable() && player.isOnline() && !player.isDead() && Bukkit.isOwnedByCurrentRegion(player)
                && plugin.mayUse(player, anchor);
    }

    void interact(Player player, int slot) {
        if (!interacting.compareAndSet(false, true)) return;
        try {
            if (!accessible(player) || slot < 0 || slot >= Math.max(1, slots.length)) return;
            ItemStack held = player.getInventory().getItemInMainHand();
            if (ground()) {
                if (!empty(held) || !plugin.allows(player, Flag.BREAK, anchor) || !approveRemoval(player)) return;
                ItemStack bottle = sourceBottle();
                if (recoverGround(player, held, bottle)) {
                    sound(Sound.BLOCK_STONE_PLACE, 1, 1);
                }
                return;
            }
            if (empty(held)) {
                ItemStack bottle = slots[slot];
                if (bottle == null) return;
                if (tradeHand(player, held, bottle, () -> {
                    if (!accessible(player) || slots[slot] != bottle) return false;
                    slots[slot] = null; operator = player.getUniqueId(); furniture().setUnsaved(); return true;
                })) { refreshDisplays(); sound(Sound.ENTITY_ITEM_FRAME_REMOVE_ITEM, 1, 1); }
            } else if (MolotovPhysics.ITEM.equals(Items.id(held)) && slots[slot] == null) {
                ItemStack bottle = held.asQuantity(1);
                ItemStack remaining = held.getAmount() == 1 ? null : held.asQuantity(held.getAmount() - 1);
                if (tradeHand(player, held, remaining, () -> {
                    if (!accessible(player) || slots[slot] != null) return false;
                    slots[slot] = bottle; operator = player.getUniqueId(); furniture().setUnsaved(); return true;
                })) { refreshDisplays(); sound(Sound.BLOCK_STONE_PLACE, 1, 1); }
            }
        } finally { interacting.set(false); }
    }

    /** Direct held-slot exchange: never debit an earlier identical stack elsewhere in the inventory. */
    private boolean tradeHand(Player player, ItemStack expected, ItemStack replacement, BooleanSupplier commit) {
        var inventory = player.getInventory();
        int selected = inventory.getHeldItemSlot();
        ItemStack[] original = snapshot(inventory.getContents());
        int cost = empty(expected) ? 0 : 1;
        List<ItemStack> rewards = cost == 0 && !empty(replacement) ? List.of(replacement.clone()) : List.of();
        var event = new InventoryTradeEvent(player, cost, rewards);
        if (!event.callEvent() || !accessible(player) || inventory.getHeldItemSlot() != selected
                || !sameContents(original, inventory.getContents()) || !same(expected, inventory.getItemInMainHand())
                || !commit.getAsBoolean()) return false;
        inventory.setItemInMainHand(replacement == null ? null : replacement.clone());
        return true;
    }

    /** CE destroy removes native entities synchronously, so its listeners can change any player slot. */
    private boolean recoverGround(Player player, ItemStack expected, ItemStack bottle) {
        var inventory = player.getInventory();
        int selected = inventory.getHeldItemSlot();
        if (selected < 0 || selected > 8) return false;
        ItemStack[] original = snapshot(inventory.getContents());
        var event = new InventoryTradeEvent(player, 0, List.of(bottle));
        if (!event.callEvent() || !accessible(player) || inventory.getHeldItemSlot() != selected
                || !sameContents(original, inventory.getContents()) || !same(expected, inventory.getItemInMainHand())) return false;

        // Preserve the exact source before the irreversible native destroy. These records are never auto-issued:
        // a crash or I/O failure between delivery and cleanup always requires checking the world and inventory.
        GroundPickup pickup = new GroundPickup(player, bottle);
        pickup.phase("prepared_before_remove_verify_world_and_inventory");
        try {
            if (!removeGround()) {
                if (furniture().isValid()) pickup.discard("Removal was refused; the original furniture remains");
                else pickup.retain("Removal returned false after the original furniture became invalid");
                return false;
            }
        } catch (RuntimeException | LinkageError failure) {
            pickup.retain("Native removal failed; verify whether CE destroyed the original furniture");
            throw failure;
        }

        boolean delivered = false;
        try {
            if (deliveryAvailable(player)) {
                pickup.phase("delivery_started_verify_inventory_before_manual_recovery");
                if (same(original[selected], inventory.getItem(selected))) {
                    // A listener may select another hotbar slot. Never follow its new selection.
                    inventory.setItem(selected, bottle.clone());
                    delivered = true;
                } else {
                    // Re-plan against the callback's current inventory, including cancellation/drift guards.
                    delivered = InventoryTrade.exchange(inventory, item -> false, 0, List.of(bottle),
                            () -> deliveryAvailable(player));
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            pickup.retain("Delivery failed after native removal; verify the player's inventory before manual recovery");
            throw failure;
        }
        if (delivered) pickup.discard("The bottle was delivered; do not issue this snapshot again");
        else pickup.retain("The original was removed; delivery did not complete; manual verification is required");
        return delivered;
    }

    private boolean deliveryAvailable(Player player) {
        return plugin.isEnabled() && Bukkit.isOwnedByCurrentRegion(player) && player.isOnline() && !player.isDead();
    }

    private final class GroundPickup {
        private final Path file;
        private final CompoundTag saved = new CompoundTag();

        GroundPickup(Player player, ItemStack bottle) {
            String token = UUID.randomUUID().toString();
            file = plugin.getDataFolder().toPath().resolve("recovery").resolve("molotov-pickup-" + token + ".nbt");
            saved.putString("recovery_kind", "ground_molotov_pickup_manual_verification_only");
            saved.putString("recovery_token", token);
            saved.putString("recovery_furniture", furniture().id().asString());
            saved.putString("recovery_furniture_uuid", furniture().uuid().toString());
            saved.putString("recovery_player", player.getUniqueId().toString());
            saved.putString("recovery_world", anchor.getWorld().getUID().toString());
            saved.putString("recovery_position", anchor.getX() + "," + anchor.getY() + "," + anchor.getZ());
            saved.putString("recovery_rule", "Never auto-issue. Verify the original furniture and player inventory before restoring this exact item once.");
            SavedItems.put(saved, "source_item", bottle);
        }

        void phase(String phase) {
            saved.putString("recovery_phase", phase);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                Files.createDirectories(file.getParent());
                byte[] bytes = net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData.of(saved).toBytes();
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
                }
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot preserve Molotov pickup phase " + phase + ": " + file, failure);
            } finally {
                try { Files.deleteIfExists(temporary); }
                catch (IOException failure) { plugin.getLogger().warning("Molotov pickup temporary snapshot retained: " + temporary); }
            }
        }

        void retain(String reason) {
            plugin.getLogger().warning(reason + "; exact item snapshot retained at " + file
                    + ". This is a manual-verification record, not an automatic refund.");
        }

        void discard(String reason) {
            try { Files.deleteIfExists(file); }
            catch (IOException failure) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, reason + "; cannot clear manual-verification snapshot " + file, failure);
            }
        }
    }

    static ItemStack[] snapshot(ItemStack[] items) {
        ItemStack[] result = items.clone();
        for (int i = 0; i < result.length; i++) if (result[i] != null) result[i] = result[i].clone();
        return result;
    }
    static boolean sameContents(ItemStack[] before, ItemStack[] after) {
        if (before.length != after.length) return false;
        for (int i = 0; i < before.length; i++) if (!same(before[i], after[i])) return false;
        return true;
    }
    private static boolean empty(ItemStack item) { return item == null || item.isEmpty(); }
    private static boolean same(ItemStack a, ItemStack b) { return empty(a) ? empty(b) : !empty(b) && a.equals(b); }

    void redstone() {
        if (ground() || !interacting.compareAndSet(false, true)) return;
        try {
            if (!usable()) return;
            Player player = operator == null ? null : Bukkit.getPlayer(operator);
            // Preserve the bottle when a stored operator cannot safely be checked in this region.
            if (player == null || !Bukkit.isOwnedByCurrentRegion(player) || !player.isOnline() || player.isDead()
                    || !plugin.allows(player, Flag.INTERACT, anchor)) return;
            List<Integer> available = new ArrayList<>();
            for (int i = 0; i < slots.length; i++) if (slots[i] != null) available.add(i);
            if (available.isEmpty()) return;
            int chosen = available.get(ThreadLocalRandom.current().nextInt(available.size()));
            ItemStack bottle = slots[chosen];
            var facing = MolotovPhysics.facing(anchor.getYaw());
            Location at = MolotovPhysics.shootPosition(storage, anchor, facing);
            if (!Molotov.owned(at) || !plugin.allows(player, Flag.PLACE, at)) return;
            Molotov molotov = service.get();
            if (molotov != null && molotov.launch(player, false, at,
                    MolotovPhysics.rackVelocity(storage, facing, ThreadLocalRandom.current().nextDouble()), bottle, () -> {
                        if (!usable() || slots[chosen] != bottle || !player.isOnline() || player.isDead()
                                || !Bukkit.isOwnedByCurrentRegion(player) || !plugin.allows(player, Flag.INTERACT, anchor)
                                || !plugin.allows(player, Flag.PLACE, at)) return false;
                        slots[chosen] = null; furniture().setUnsaved(); return true;
                    })) refreshDisplays();
            // The source's Molotov branch deliberately has no HOLDER_POP sound.
        } finally { interacting.set(false); }
    }

    void refreshPower() {
        if (ground() || !usable()) return;
        var block = anchor.getBlock();
        boolean next = block.isBlockPowered() || block.isBlockIndirectlyPowered();
        if (next == powered) return;
        powered = next; furniture().setUnsaved();
        if (next) redstone();
    }

    boolean struck(Projectile projectile) {
        if (!ground() || !interacting.compareAndSet(false, true)) return false;
        try {
            if (!usable() || !projectile.isValid()) return false;
            Molotov molotov = service.get();
            if (molotov == null) return false;
            var shooter = projectile.getShooter();
            Player player = molotov.responsibleFor(projectile);
            if (player != null && (!Bukkit.isOwnedByCurrentRegion(player) || !player.isOnline() || player.isDead()
                    || !plugin.allows(player, Flag.BREAK, anchor) || !approveRemoval(player))) return false;
            if (player == null && shooter instanceof Entity
                    && !Boolean.TRUE.equals(anchor.getWorld().getGameRuleValue(GameRules.MOB_GRIEFING))) return false;
            boolean launched = molotov.launch(player, false,
                    MolotovPhysics.shootPosition(storage, anchor, MolotovPhysics.facing(anchor.getYaw())),
                    new org.bukkit.util.Vector(), sourceBottle(), () -> usable() && (player == null
                    || Bukkit.isOwnedByCurrentRegion(player) && player.isOnline() && !player.isDead()
                    && plugin.allows(player, net.momirealms.antigrieflib.Flag.BREAK, anchor)) && removeGround());
            if (launched) anchor.getWorld().playEffect(anchor, Effect.STEP_SOUND, Material.GLASS);
            return launched;
        } finally { interacting.set(false); }
    }

    private boolean approveRemoval(Player player) {
        if (!(furniture() instanceof BukkitFurniture bukkit)) return false;
        FurnitureBreakEvent event = new FurnitureBreakEvent(player, bukkit);
        event.setDropItems(false);
        removalEvent = event;
        try { return event.callEvent() && usable() && player.isOnline() && !player.isDead()
                && Bukkit.isOwnedByCurrentRegion(player) && plugin.allows(player, Flag.BREAK, anchor); }
        finally { removalEvent = null; }
    }

    private boolean removeGround() {
        if (!usable()) return false;
        boolean success = CraftEngineFurniture.remove(furniture(), (net.momirealms.craftengine.core.entity.player.Player) null, false, false);
        if (success) { removed = true; Molotov molotov = service.get(); if (molotov != null) molotov.unloaded(this); }
        return success;
    }

    private ItemStack sourceBottle() {
        var source = furniture().sourceItem();
        ItemStack item = source == null ? Items.create(MolotovPhysics.ITEM) : ItemStackUtils.getBukkitStack(source).asQuantity(1);
        if (!MolotovPhysics.ITEM.equals(Items.id(item))) throw new IllegalStateException("Placed Molotov has another source item");
        return item.clone();
    }

    private void sound(Sound sound, float volume, float pitch) { anchor.getWorld().playSound(anchor, sound, SoundCategory.BLOCKS, volume, pitch); }
    @Override public void onLoad() { loaded = true; removed = false; Molotov molotov = service.get(); if (molotov != null) molotov.loaded(this); refreshDisplays(); }
    @Override public void onPlace(net.momirealms.craftengine.core.entity.player.Player player) {
        if (player != null) operator = ((Player) player.platformPlayer()).getUniqueId();
        var block = anchor.getBlock();
        powered = block.isBlockPowered() || block.isBlockIndirectlyPowered();
        furniture().setUnsaved(); onLoad();
    }
    @Override public void onUnload() { loaded = false; Molotov molotov = service.get(); if (molotov != null) molotov.unloaded(this); clearDisplays(); }
    void shutdown() { loaded = false; clearDisplays(); }
    @Override public void postRemove(net.momirealms.craftengine.core.entity.player.Player player) { removed = true; onUnload(); }

    @Override public void saveCustomData(CompoundTag root) {
        if (blocked) { if (rejected != null) root.put(SAVE_KEY, rejected.deepClone()); return; }
        CompoundTag saved = new CompoundTag();
        saved.putString("storage", storage.name());
        saved.putInt("slots", slots.length);
        saved.putBoolean("powered", powered);
        if (operator != null) saved.putString("operator", operator.toString());
        for (int i = 0; i < slots.length; i++) SavedItems.put(saved, "slot_" + i, slots[i]);
        root.put(SAVE_KEY, SavedState.envelope(SavedState.namespace(SAVE_KEY), 1, SavedState.SERIES_SOURCE, saved));
    }

    @Override public void loadCustomData(CompoundTag root) {
        try {
            if (!root.containsKey(SAVE_KEY)) return;
            if (!(root.get(SAVE_KEY) instanceof CompoundTag envelope)) throw new IllegalArgumentException("Invalid Molotov save type");
            CompoundTag saved = SavedState.payload(envelope, SavedState.namespace(SAVE_KEY), SavedState.SERIES_SOURCE, 1, null);
            if (!storage.name().equals(SavedState.text(saved, "storage"))) throw new IllegalArgumentException("Foreign Molotov storage");
            if (SavedState.integer(saved, "slots") != slots.length) throw new IllegalArgumentException("Foreign Molotov slot count");
            Set<String> allowed = new HashSet<>(List.of("storage", "slots", "operator", "powered"));
            for (int i = 0; i < slots.length; i++) allowed.add("slot_" + i);
            for (String key : saved.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown Molotov save field: " + key);
            ItemStack[] restored = new ItemStack[slots.length];
            for (int i = 0; i < slots.length; i++) {
                restored[i] = SavedItems.get(saved, "slot_" + i, 1);
                if (restored[i] != null && !MolotovPhysics.ITEM.equals(Items.id(restored[i]))) throw new IllegalArgumentException("Foreign stored bottle");
            }
            String owner = SavedState.optionalText(saved, "operator");
            UUID restoredOperator = owner == null ? null : UUID.fromString(owner);
            boolean restoredPower = SavedState.flag(saved, "powered");
            System.arraycopy(restored, 0, slots, 0, slots.length); operator = restoredOperator; powered = restoredPower;
            refreshDisplays();
        } catch (RuntimeException failure) {
            blocked = true; rejected = root.get(SAVE_KEY) == null ? null : root.get(SAVE_KEY).deepClone();
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Molotov furniture save retained after failed restore: " + furniture().uuid(), failure);
        }
    }

    @Override public void preRemove(net.momirealms.craftengine.core.entity.player.Player player) {
        if (!hasContents() && !blocked) return;
        CompoundTag saved = new CompoundTag();
        byte[] encoded = null;
        String recoveryFurnitureUuid = "unavailable", recoveryWorld = "unavailable";
        String recoveryFurniture = "unavailable", recoveryPosition = "unavailable";
        try {
            recoveryFurnitureUuid = furniture().uuid().toString();
            saved.putString("recovery_furniture_uuid", recoveryFurnitureUuid);
            recoveryWorld = anchor.getWorld().getUID().toString();
            saved.putString("recovery_world", recoveryWorld);
            recoveryFurniture = furniture().id().asString();
            saved.putString("recovery_furniture", recoveryFurniture);
            recoveryPosition = anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
            saved.putString("recovery_position", recoveryPosition);
            saveCustomData(saved);
            encoded = net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData.of(saved).toBytes();
            var folder = plugin.getDataFolder().toPath().resolve("recovery"); Files.createDirectories(folder);
            var file = folder.resolve(furniture().uuid() + "-" + UUID.randomUUID() + ".nbt");
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            plugin.getLogger().warning("Removed Molotov storage retained for manual recovery: " + file);
        } catch (Exception | LinkageError failure) {
            // CE's finally still destroys the furniture when this callback throws, and then skips base loot.
            // Preserve the best available state in the log; recovery records must never auto-replay items.
            String emergency;
            try { emergency = emergencyRemovalState(saved, encoded); }
            catch (Exception | LinkageError fallbackFailure) {
                if (fallbackFailure != failure) failure.addSuppressed(fallbackFailure);
                // Rendering the same damaged tag again could escape this callback and skip CE's base loot.
                emergency = "BEST_EFFORT_INCOMPLETE_SNBT; recovery_furniture_uuid=" + recoveryFurnitureUuid
                        + "; recovery_world=" + recoveryWorld + "; recovery_furniture=" + recoveryFurniture
                        + "; recovery_position=" + recoveryPosition;
            }
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "MOLOTOV STORAGE RECOVERY FAILED; never auto replay; verify world and inventory before manual recovery; manual state="
                            + emergency, failure);
        }
    }

    private String emergencyRemovalState(CompoundTag saved, byte[] encoded) {
        if (encoded != null) return "base64_nbt=" + Base64.getEncoder().encodeToString(encoded);
        if (!saved.containsKey(SAVE_KEY)) {
            // The envelope's size check can fail even when each original item still serializes.
            // Retain those exact bytes first; Paper CODEC JSON is the next complete recovery form.
            if (rejected != null) saved.putString("recovery_rejected_snbt", rejected.toString());
            else {
                saved.putString("recovery_storage", storage.name());
                saved.putInt("recovery_slots", slots.length);
                saved.putBoolean("recovery_powered", powered);
                if (operator != null) saved.putString("recovery_operator", operator.toString());
                for (int slot = 0; slot < slots.length; slot++) if (slots[slot] != null) {
                    ItemStack item = slots[slot];
                    CompoundTag source = new CompoundTag();
                    saved.put("recovery_slot_" + slot, source);
                    // Even isEmpty() may fail on a damaged item; never let one slot hide later originals.
                    try {
                        emergencyRemovalItemState(source, item);
                    } catch (Exception | LinkageError slotFailure) {
                        incompleteRemovalItemField(source, "recovery_slot_failure", slotFailure);
                    }
                }
            }
        }
        return "snbt=" + saved;
    }

    private static void emergencyRemovalItemState(CompoundTag source, ItemStack item) {
        Throwable byteEncodingFailure;
        try {
            byte[] bytes = item.serializeAsBytes();
            if (bytes == null || bytes.length == 0) throw new IllegalStateException("Empty item byte serialization");
            source.putByteArray("item_bytes", bytes);
            source.putString("format", "BUKKIT_ITEM_STACK_BYTES");
            return;
        } catch (Exception | LinkageError bytesFailure) {
            byteEncodingFailure = bytesFailure;
        }
        try {
            var json = Bukkit.getUnsafe().serializeItemAsJson(item);
            if (json == null || !json.has("DataVersion") || !json.has("id"))
                throw new IllegalStateException("Incomplete Paper item JSON");
            String jsonText = json.toString();
            source.putString("item_json", jsonText);
            source.putString("format", "PAPER_ITEM_STACK_JSON");
            return;
        } catch (Exception | LinkageError jsonFailure) {
            source.putString("recovery_bytes_failure", recoveryFailureDescription(byteEncodingFailure));
            source.putString("recovery_json_failure", recoveryFailureDescription(jsonFailure));
        }
        // ItemMeta explicitly does not promise a complete serializable component value.
        source.putString("recovery_quality", "BEST_EFFORT_NOT_GUARANTEED_COMPLETE");
        String type = null, components = null;
        try { type = item.getType().getKey().toString(); source.putString("item_type", type); }
        catch (Exception | LinkageError typeFailure) { incompleteRemovalItemField(source, "recovery_type_failure", typeFailure); }
        try {
            var meta = item.getItemMeta();
            components = meta == null ? "[]" : meta.getAsComponentString();
            source.putString("item_components", components);
        } catch (Exception | LinkageError metaFailure) { incompleteRemovalItemField(source, "recovery_components_failure", metaFailure); }
        if (type != null && components != null) source.putString("item", type + components);
        try { source.putInt("count", item.getAmount()); }
        catch (Exception | LinkageError countFailure) { incompleteRemovalItemField(source, "recovery_count_failure", countFailure); }
    }

    private static void incompleteRemovalItemField(CompoundTag source, String field, Throwable failure) {
        source.putBoolean("recovery_incomplete", true);
        source.putString(field, recoveryFailureDescription(failure));
    }

    private static String recoveryFailureDescription(Throwable failure) {
        try { return failure.toString(); }
        catch (Exception | LinkageError descriptionFailure) { return failure.getClass().getName(); }
    }

    private void clearDisplays() { for (ItemDisplay display : displays) if (display.isValid()) display.remove(); displays.clear(); }

    private void refreshDisplays() {
        if (!loaded || !Molotov.owned(anchor)) return;
        clearDisplays();
        if (!usable()) return;
        for (int slot = 0; slot < slots.length; slot++) {
            ItemStack item = slots[slot]; if (item == null) continue;
            // Vanilla fixed ItemDisplay is the supported platform replacement for source block-entity rendering.
            Matrix4f matrix = displayTransform(slot);
            ItemDisplay display = anchor.getWorld().spawn(anchor.clone().add(0.5, 0, 0.5), ItemDisplay.class, entity -> {
                entity.setPersistent(false); entity.setInvulnerable(true); entity.setGravity(false);
                entity.setItemStack(item.clone()); entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                entity.setTransformationMatrix(matrix); entity.setDisplayWidth(1); entity.setDisplayHeight(1);
            });
            if (display.isValid() && loaded && usable()) displays.add(display);
            else display.remove();
        }
    }

    private Matrix4f displayTransform(int slot) {
        Matrix4f m = new Matrix4f().rotateY((float) Math.toRadians(-anchor.getYaw()));
        double x = 0.5, y = 0.125, z = 0.75, yaw = 0, roll = -45; float scale = 0.95f;
        if (storage == MolotovPhysics.Storage.CIRCULAR) {
            double[] xs = {0.5,0.875,0.875,0.5,0.125,0.125}, zs = {0.125,0.3125,0.6875,0.875,0.6875,0.3125};
            double[] angles = {0,22.5,-22.5,180,157.5,-157.5};
            x = xs[slot]; z = zs[slot]; yaw = angles[slot]; roll = 0; scale = 0.82f;
        } else if (storage == MolotovPhysics.Storage.CELLAR) {
            x = 0.825 - slot % 3 * 0.325; y = 0.78 - slot / 3 * 0.29; z = 0.875; roll = -90; scale = 1;
        } else if (storage == MolotovPhysics.Storage.TILTED) {
            x = 0.9 * (0.425 - 0.375 * slot + 0.5); y = 0.9 * 0.3125; z = 0.9 * (0.02 + (slot - 1) * 0.005 + 0.5);
            roll = 0; scale = 0.9f;
        }
        m.translate((float) (x - 0.5), (float) y, (float) (z - 0.5));
        if (storage == MolotovPhysics.Storage.TILTED) m.rotateX((float) Math.toRadians(22.5));
        return m.rotateY((float) Math.toRadians(yaw)).rotateZ((float) Math.toRadians(roll)).scale(scale);
    }
}
