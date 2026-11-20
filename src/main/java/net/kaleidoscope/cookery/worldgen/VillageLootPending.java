package net.kaleidoscope.cookery.worldgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kaleidoscope.cookery.util.FoliaUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.logging.Level;

/** Escrows an already rolled draw. Contents and receipt commit through one container snapshot. */
final class VillageLootPending implements Listener, CommandExecutor {
    static final NamespacedKey PENDING = NamespacedKey.fromString("kaleidoscopecookery:village_loot_pending");
    static final NamespacedKey RECEIPT = NamespacedKey.fromString("kaleidoscopecookery:village_loot_receipt");
    private static final int MAX_BYTES = 524_288;
    private static final int MAX_STACK_BYTES = MAX_BYTES;
    private static final int MAX_ANCHOR_UTF_BYTES = 65_535;
    private static final Set<String> POINTER_FIELDS = Set.of("schemaVersion", "id", "journalSha256");
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private final JavaPlugin plugin;
    private final VillageLootSpec spec;
    private final Set<UUID> recovering = ConcurrentHashMap.newKeySet();
    private BiFunction<String, List<ItemStack>, List<ItemStack>> converter;

    VillageLootPending(JavaPlugin plugin, VillageLootSpec spec) { this.plugin = plugin; this.spec = spec; }
    void converter(BiFunction<String, List<ItemStack>, List<ItemStack>> value) { converter = value; }

    void save(LootGenerateEvent event, List<ItemStack> draw, Throwable failure) {
        UUID id = UUID.randomUUID(); JsonObject record = new JsonObject();
        record.addProperty("schemaVersion", 2); record.addProperty("id", id.toString());
        record.addProperty("sourceCommit", VillageLootSpec.SOURCE_COMMIT);
        record.addProperty("table", event.getLootTable().getKey().toString());
        record.addProperty("timeMillis", System.currentTimeMillis()); record.addProperty("failure", failure.toString());
        try {
            record.add("draw", JSON.toJsonTree(encode(draw.toArray(ItemStack[]::new))));
            Container container = event.getInventoryHolder() instanceof Container value ? fresh(value) : null;
            if (container != null) {
                VillageLootSpec.require(!container.getPersistentDataContainer().has(PENDING),
                        "Container already has a pending village draw");
                record.addProperty("world", container.getWorld().getUID().toString());
                record.addProperty("x", container.getX()); record.addProperty("y", container.getY()); record.addProperty("z", container.getZ());
                record.addProperty("block", container.getType().name());
                record.add("before", JSON.toJsonTree(encode(container.getSnapshotInventory().getContents())));
            }
            String payload = JSON.toJson(record);
            VillageLootSpec.require(payload.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES, "Village pending record too large");
            // Immutable journal survives a failed block-state update; never replay it without its original anchor.
            writeNew(path(id), payload);
            if (container != null) {
                // NBT StringTag writes modified UTF with a 65,535-byte limit. The
                // bounded immutable journal owns the full draw; the anchor stays small.
                container.getPersistentDataContainer().set(PENDING, PersistentDataType.STRING, pointer(id, payload));
                VillageLootSpec.require(container.update(false, false), "Cannot anchor pending village draw to its container");
            }
            plugin.getLogger().log(Level.SEVERE, "VILLAGE_LOOT_PENDING " + id + " anchored=" + (container != null)
                    + "; repair dependency, then reopen unchanged chest or use /kcvillageloot recover " + id, failure);
        } catch (Exception writeFailure) {
            plugin.getLogger().log(Level.SEVERE, "VILLAGE_LOOT_PENDING_LOG " + id + " utf8Base64="
                    + Base64.getEncoder().encodeToString(JSON.toJson(record).getBytes(StandardCharsets.UTF_8))
                    + "; no automatic delivery or reroll; original container requires manual reconciliation", writeFailure);
        }
    }

    /** Runs only on the original block owner. Conflicts retain escrow and deny interaction. */
    boolean recover(InventoryHolder holder) {
        if (holder instanceof DoubleChest chest) return recover(chest.getLeftSide()) && recover(chest.getRightSide());
        if (!(holder instanceof Container original)) return true;
        Container container;
        try { container = fresh(original); }
        catch (RuntimeException wrongOwner) { return false; }
        if (container == null) return false;
        UUID recoveryId = null;
        try {
            String anchor = pendingPayload(container);
            if (anchor == null) return true;
            // Native unpacking clears its table before publishing a draw.
            // Both present at once is inconsistent; never clear or reroll it.
            VillageLootSpec.require(!hasArmedSourceLoot(container), "Pending village container still has an armed source loot table");
            JsonObject record = readAnchor(anchor); UUID id = UUID.fromString(record.get("id").getAsString());
            VillageLootSpec.require(recovering.add(id), "Pending draw recovery is already in progress");
            recoveryId = id;
            VillageLootSpec.require(matches(record, container), "Pending container identity changed");
            ItemStack[] before = decode(record, "before");
            VillageLootSpec.require(Arrays.equals(before, normalize(container.getSnapshotInventory().getContents())),
                    "Pending contents changed; refusing to overwrite or duplicate");
            List<ItemStack> converted = converter.apply(record.get("table").getAsString(), new ArrayList<>(Arrays.asList(decode(record, "draw"))));
            // Item factories are external code: recheck the live anchor and baseline after all callbacks.
            Container latest = fresh(container);
            VillageLootSpec.require(latest != null && anchor.equals(pendingPayload(latest))
                    && matches(record, latest) && !hasArmedSourceLoot(latest)
                    && Arrays.equals(before, normalize(latest.getSnapshotInventory().getContents())),
                    "Pending container changed while converting; refusing stale snapshot commit");
            container = latest;
            ItemStack[] after = fill(before, converted, container.getSnapshotInventory().getMaxStackSize());
            // Contents and receipt enter the same block-entity snapshot; retry never appends another copy.
            container.getSnapshotInventory().setContents(after);
            container.getPersistentDataContainer().remove(PENDING);
            container.getPersistentDataContainer().set(RECEIPT, PersistentDataType.STRING, id.toString());
            VillageLootSpec.require(container.update(false, false), "Pending village container commit failed");
            Container check = fresh(container);
            VillageLootSpec.require(check != null && id.toString().equals(check.getPersistentDataContainer().get(RECEIPT, PersistentDataType.STRING))
                    && pendingPayload(check) == null
                    && Arrays.equals(after, normalize(check.getSnapshotInventory().getContents())), "Pending commit read-back failed");
            plugin.getLogger().info("VILLAGE_LOOT_RECOVERED " + id + "; exact saved draw; no loot-table invocation");
            return true;
        } catch (Throwable failure) {
            plugin.getLogger().log(Level.WARNING, "VILLAGE_LOOT_RECOVERY_HELD " + container.getLocation() + "; inspect original journal", failure);
            return false;
        } finally {
            if (recoveryId != null) recovering.remove(recoveryId);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!recover(event.getInventory().getHolder())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (!recover(event.getSource().getHolder()) || !recover(event.getDestination().getHolder())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!ownedLoaded(event.getBlock()) || event.getBlock().getState() instanceof Container container && !allowDestruction(container)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(this::blocksExplosion);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(this::blocksExplosion);
    }

    private boolean blocksExplosion(Block block) {
        if (!ownedLoaded(block)) return false;
        return block.getState() instanceof Container container && !allowDestruction(container);
    }

    private boolean allowDestruction(Container container) {
        // Resolve or hold an existing escrow before any native slot read can roll
        // another draw. Inspect a fresh state after a successful recovery commit.
        if (!recover(container)) return false;
        try {
            Container current = fresh(container);
            if (current == null) return false;
            if (hasArmedSourceLoot(current)) {
                // Unpack through the original native container before its block can disappear.
                // This preserves the native roll/context and lets a failed draw acquire its anchor.
                // CraftInventory.getContents reads the raw list and does not unpack loot.
                // A slot read reaches RandomizableContainerBlockEntity.getItem -> unpackLootTable(null).
                // Use the individual chest inventory so a joined neighbour cannot receive this read instead.
                if (current instanceof Chest chest) chest.getBlockInventory().getItem(0);
                else current.getInventory().getItem(0);
                current = fresh(current);
                return current != null && recover(current);
            }
            return true;
        } catch (Throwable failure) {
            plugin.getLogger().log(Level.WARNING, "VILLAGE_LOOT_DESTRUCTION_HELD; original kitchen chest retained", failure);
            return false;
        }
    }

    private boolean hasArmedSourceLoot(Container container) {
        if (!(container instanceof Lootable lootable)) return false;
        var table = lootable.getLootTable();
        return table != null && spec.tables().contains(table.getKey().toString());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("kaleidoscopecookery.villageloot.recover")) return true;
        if (args.length != 2 || !args[0].equals("recover")) return false;
        try {
            UUID id = UUID.fromString(args[1]);
            JsonObject record = readJournal(id, null);
            var world = Bukkit.getWorld(UUID.fromString(record.get("world").getAsString()));
            VillageLootSpec.require(world != null, "Pending world is not loaded");
            Location location = new Location(world, record.get("x").getAsInt(), record.get("y").getAsInt(), record.get("z").getAsInt());
            FoliaUtil.run(() -> {
                try {
                    if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                        plugin.getLogger().warning("VILLAGE_LOOT_RECOVERY_HELD " + id + "; original chunk unloaded"); return;
                    }
                    if (location.getBlock().getState() instanceof Container container && id.toString().equals(pendingId(container))) recover(container);
                    else plugin.getLogger().warning("VILLAGE_LOOT_RECOVERY_HELD " + id + "; original pending anchor missing; no replay");
                } catch (RuntimeException | IOException failure) {
                    plugin.getLogger().log(Level.WARNING, "VILLAGE_LOOT_RECOVERY_HELD " + id + "; invalid original pending anchor; no replay", failure);
                }
            }, location);
            sender.sendMessage("Recovery scheduled on container owner; see server log for result.");
        } catch (RuntimeException | IOException failure) {
            sender.sendMessage("Village recovery refused: " + failure.getMessage());
        }
        return true;
    }

    private String pendingId(Container container) throws IOException {
        String payload = pendingPayload(container);
        return payload == null ? null : readAnchor(payload).get("id").getAsString();
    }

    private static String pendingPayload(Container container) {
        var data = container.getPersistentDataContainer();
        if (!data.has(PENDING)) return null;
        VillageLootSpec.require(data.has(PENDING, PersistentDataType.STRING), "Pending village anchor has the wrong data type");
        String value = data.get(PENDING, PersistentDataType.STRING);
        VillageLootSpec.require(value != null, "Pending village anchor could not be read");
        return value;
    }

    private static String pointer(UUID id, String payload) {
        var pointer = new JsonObject();
        pointer.addProperty("schemaVersion", 3); pointer.addProperty("id", id.toString());
        pointer.addProperty("journalSha256", digest(payload.getBytes(StandardCharsets.UTF_8)));
        return JSON.toJson(pointer);
    }

    private JsonObject readAnchor(String anchor) throws IOException {
        VillageLootSpec.require(anchor.length() <= MAX_ANCHOR_UTF_BYTES, "Pending anchor is too large");
        int modifiedUtfBytes = 0;
        for (int i = 0; i < anchor.length(); i++) {
            char value = anchor.charAt(i);
            modifiedUtfBytes += value >= 1 && value <= 127 ? 1 : value <= 2047 ? 2 : 3;
            VillageLootSpec.require(modifiedUtfBytes <= MAX_ANCHOR_UTF_BYTES, "Pending anchor exceeds the NBT string limit");
        }
        JsonObject value = JsonParser.parseString(anchor).getAsJsonObject();
        if (value.get("schemaVersion").getAsInt() == 2) return read(anchor); // Existing full-JSON anchors.
        VillageLootSpec.require(value.get("schemaVersion").getAsInt() == 3 && value.keySet().equals(POINTER_FIELDS),
                "Unknown pending anchor schema");
        UUID id = UUID.fromString(value.get("id").getAsString());
        VillageLootSpec.require(id.toString().equals(value.get("id").getAsString()), "Noncanonical pending anchor UUID");
        String hash = value.get("journalSha256").getAsString();
        VillageLootSpec.require(hash.matches("[0-9a-f]{64}"), "Invalid pending journal digest");
        return readJournal(id, hash);
    }

    private JsonObject readJournal(UUID id, String expectedDigest) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(path(id))) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        VillageLootSpec.require(bytes.length <= MAX_BYTES, "Pending journal too large");
        if (expectedDigest != null) VillageLootSpec.require(expectedDigest.equals(digest(bytes)), "Pending journal digest changed");
        JsonObject record = read(new String(bytes, StandardCharsets.UTF_8));
        VillageLootSpec.require(id.toString().equals(record.get("id").getAsString()), "Pending journal UUID changed");
        return record;
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    private JsonObject read(String payload) {
        VillageLootSpec.require(payload.length() <= MAX_BYTES, "Pending journal too large");
        JsonObject record = JsonParser.parseString(payload).getAsJsonObject();
        VillageLootSpec.require(record.get("schemaVersion").getAsInt() == 2
                && VillageLootSpec.SOURCE_COMMIT.equals(record.get("sourceCommit").getAsString())
                && spec.tables().contains(record.get("table").getAsString()), "Unknown pending schema/source/table");
        UUID.fromString(record.get("id").getAsString());
        return record;
    }

    private static boolean matches(JsonObject record, Container container) {
        return record.get("world").getAsString().equals(container.getWorld().getUID().toString())
                && record.get("x").getAsInt() == container.getX() && record.get("y").getAsInt() == container.getY()
                && record.get("z").getAsInt() == container.getZ() && record.get("block").getAsString().equals(container.getType().name());
    }

    private static Container fresh(Container container) {
        VillageLootSpec.require(Bukkit.isOwnedByCurrentRegion(container.getWorld(), container.getX() >> 4, container.getZ() >> 4),
                "Pending container is not owned by this thread");
        VillageLootSpec.require(container.getWorld().isChunkLoaded(container.getX() >> 4, container.getZ() >> 4),
                "Pending container chunk is unloaded; refusing synchronous load");
        return container.getBlock().getState() instanceof Container value ? value : null;
    }

    private static boolean ownedLoaded(Block block) {
        int chunkX = block.getX() >> 4, chunkZ = block.getZ() >> 4;
        return Bukkit.isOwnedByCurrentRegion(block.getWorld(), chunkX, chunkZ) && block.getWorld().isChunkLoaded(chunkX, chunkZ);
    }

    static ItemStack[] fill(ItemStack[] before, List<ItemStack> draw) {
        return fill(before, draw, 99);
    }

    private static ItemStack[] fill(ItemStack[] before, List<ItemStack> draw, int inventoryLimit) {
        VillageLootSpec.require(inventoryLimit > 0 && inventoryLimit <= 99, "Invalid recovery container stack limit");
        ItemStack[] result = Arrays.stream(before).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
        for (ItemStack item : draw) {
            if (item == null || item.getType().isAir()) continue;
            int remaining = item.getAmount();
            for (ItemStack slot : result) if (slot != null && slot.isSimilar(item)) {
                int moved = Math.min(remaining, Math.max(0, Math.min(inventoryLimit, slot.getMaxStackSize()) - slot.getAmount()));
                slot.setAmount(slot.getAmount() + moved); remaining -= moved;
            }
            for (int i = 0; remaining > 0 && i < result.length; i++) {
                if (result[i] != null && !result[i].getType().isAir()) continue;
                result[i] = item.clone(); int moved = Math.min(remaining, Math.min(inventoryLimit, item.getMaxStackSize()));
                VillageLootSpec.require(moved > 0, "Invalid recovered stack limit");
                result[i].setAmount(moved); remaining -= moved;
            }
            VillageLootSpec.require(remaining == 0, "Original container cannot hold the entire saved draw");
        }
        return result;
    }

    private static List<String> encode(ItemStack[] items) {
        VillageLootSpec.require(items.length <= 4096, "Pending draw has too many items");
        var result = new ArrayList<String>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) { result.add(null); continue; }
            byte[] bytes = item.serializeAsBytes();
            VillageLootSpec.require(bytes.length <= MAX_STACK_BYTES, "Pending stack too large");
            result.add(Base64.getEncoder().encodeToString(bytes));
        }
        return result;
    }

    private static ItemStack[] decode(JsonObject record, String field) {
        var values = record.getAsJsonArray(field);
        VillageLootSpec.require(values.size() <= 4096, "Pending draw has too many items");
        ItemStack[] result = new ItemStack[values.size()];
        for (int i = 0; i < result.length; i++) if (!values.get(i).isJsonNull()) {
            byte[] bytes = Base64.getDecoder().decode(values.get(i).getAsString());
            VillageLootSpec.require(bytes.length <= MAX_STACK_BYTES, "Pending stack too large");
            result[i] = ItemStack.deserializeBytes(bytes);
        }
        return normalize(result);
    }

    private static ItemStack[] normalize(ItemStack[] items) {
        return Arrays.stream(items).map(item -> item == null || item.getType().isAir() ? null : item).toArray(ItemStack[]::new);
    }
    private Path path(UUID id) { return plugin.getDataFolder().toPath().resolve("village-loot-pending").resolve(id + ".json"); }
    private static void writeNew(Path path, String payload) throws IOException {
        Files.createDirectories(path.getParent());
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
}
