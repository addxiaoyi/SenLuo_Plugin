package net.kaleidoscope.cookery.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kaleidoscope.cookery.util.FoliaUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Chest;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VillageLootPendingTest {
    @TempDir Path directory;

    @BeforeAll static void initializeMaterialRegistries() {
        VillageTestRegistries.initializeMaterials();
    }

    private ItemStack stack(int count, int max) {
        ItemStack item = mock(ItemStack.class); var amount = new AtomicInteger(count);
        when(item.getType()).thenReturn(Material.PAPER); when(item.getAmount()).thenAnswer(ignored -> amount.get());
        doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(item).setAmount(anyInt());
        when(item.getMaxStackSize()).thenReturn(max); when(item.clone()).thenAnswer(ignored -> stack(amount.get(), max));
        when(item.isSimilar(any())).thenReturn(true);
        return item;
    }

    @Test void recoveryPlanPreservesCountsAndExistingStacksAndNeverMutatesItsInput() {
        ItemStack existing = stack(2, 3), incoming = stack(5, 3);
        ItemStack[] planned = VillageLootPending.fill(new ItemStack[]{existing, null, null}, List.of(incoming));
        assertEquals(2, existing.getAmount()); assertEquals(5, incoming.getAmount());
        assertEquals(List.of(3, 3, 1), java.util.Arrays.stream(planned).map(ItemStack::getAmount).toList());
        assertNotSame(existing, planned[0]); assertNotSame(incoming, planned[1]);
    }

    @Test void insufficientCapacityRefusesTheWholePlan() {
        ItemStack existing = stack(3, 3);
        assertThrows(IllegalArgumentException.class, () -> VillageLootPending.fill(new ItemStack[]{existing}, List.of(stack(1, 3))));
        assertEquals(3, existing.getAmount());
    }

    @Test void sameSavedDrawRecoversAfterDependencyRepairAndASecondOpenDoesNotMintAnotherCopy() throws Exception {
        var fixture = fixture(); ItemStack token = stack(2, 64), built = stack(2, 64);
        when(token.serializeAsBytes()).thenReturn(new byte[]{1, 2, 3});
        try (var bukkit = mockStatic(Bukkit.class); var items = mockStatic(ItemStack.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            items.when(() -> ItemStack.deserializeBytes(new byte[]{1, 2, 3})).thenReturn(token);
            AtomicInteger conversions = new AtomicInteger();
            fixture.pending.converter((table, draw) -> {
                assertEquals(VillageLootSpec.NORMAL_TABLE, table); assertEquals(List.of(token), draw);
                if (conversions.incrementAndGet() == 1) throw new IllegalStateException("dependency still unavailable");
                return List.of(built);
            });
            fixture.pending.save(fixture.event, List.of(token), new IllegalStateException("CE temporarily unavailable"));
            assertNotNull(fixture.data.get(VillageLootPending.PENDING));
            try (var files = Files.list(directory.resolve("village-loot-pending"))) { assertEquals(1, files.count()); }
            // Bukkit callers may retain a pre-escrow snapshot; only the fresh block-state anchor is authoritative.
            Container stale = mock(Container.class); when(stale.getWorld()).thenReturn(fixture.world);
            when(stale.getX()).thenReturn(-1); when(stale.getZ()).thenReturn(32);
            Block originalBlock = fixture.container.getBlock();
            PersistentDataContainer staleData = mock(PersistentDataContainer.class);
            when(stale.getBlock()).thenReturn(originalBlock);
            when(stale.getPersistentDataContainer()).thenReturn(staleData);
            assertFalse(fixture.pending.recover(stale)); assertEquals(1, conversions.get());
            assertNotNull(fixture.data.get(VillageLootPending.PENDING)); assertNull(fixture.contents.get()[0]);
            assertTrue(fixture.pending.recover(fixture.container)); assertEquals(2, conversions.get());
            assertNull(fixture.data.get(VillageLootPending.PENDING)); assertNotNull(fixture.data.get(VillageLootPending.RECEIPT));
            assertEquals(2, fixture.contents.get()[0].getAmount());
            assertTrue(fixture.pending.recover(fixture.container)); assertEquals(2, conversions.get());
        }
    }

    @Test void changedOriginalInventoryIsHeldWithoutConversionOrOverwrite() {
        var fixture = fixture(); ItemStack token = stack(1, 64); when(token.serializeAsBytes()).thenReturn(new byte[]{1});
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            fixture.pending.save(fixture.event, List.of(token), new IllegalStateException("CE unavailable"));
            ItemStack changed = stack(5, 64); fixture.contents.set(new ItemStack[]{changed, null, null});
            fixture.pending.converter((table, draw) -> fail("changed container must not be converted"));
            assertFalse(fixture.pending.recover(fixture.container));
            assertNotNull(fixture.data.get(VillageLootPending.PENDING)); assertSame(changed, fixture.contents.get()[0]);
        }
    }

    @Test void externalItemFactoryCannotOverwriteAChangedLiveContainerOrReenterTheSameDraw() {
        var fixture = fixture(); ItemStack token = stack(1, 64); when(token.serializeAsBytes()).thenReturn(new byte[]{9});
        try (var bukkit = mockStatic(Bukkit.class); var items = mockStatic(ItemStack.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            items.when(() -> ItemStack.deserializeBytes(new byte[]{9})).thenReturn(token);
            fixture.pending.save(fixture.event, List.of(token), new IllegalStateException("CE unavailable"));
            ItemStack replacement = stack(5, 64);
            fixture.pending.converter((table, draw) -> {
                assertFalse(fixture.pending.recover(fixture.container), "reentrant callback must not consume the same draw");
                fixture.contents.set(new ItemStack[]{replacement, null, null});
                return List.of(stack(1, 64));
            });
            assertFalse(fixture.pending.recover(fixture.container));
            assertSame(replacement, fixture.contents.get()[0]);
            assertNotNull(fixture.data.get(VillageLootPending.PENDING)); assertNull(fixture.data.get(VillageLootPending.RECEIPT));
        }
    }

    @Test void destructionUnpacksTheIndividualKitchenChestThroughANativeSlotRead() {
        var fixture = fixture(); Chest chest = (Chest) fixture.container;
        Inventory individual = mock(Inventory.class); when(chest.getBlockInventory()).thenReturn(individual);
        LootTable originalTable = fixture.event.getLootTable();
        Block originalBlock = chest.getBlock();
        when(chest.getLootTable()).thenReturn(originalTable);
        BlockBreakEvent event = mock(BlockBreakEvent.class); when(event.getBlock()).thenReturn(originalBlock);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            fixture.pending.onBreak(event);
            verify(individual).getItem(0); verify(chest, never()).getInventory();
            verify(event, never()).setCancelled(true);
        }
    }

    @Test void wrongPrimitiveAnchorHoldsOpenMoveAndBreakWithoutReadingItAsAStringOrCommitting() {
        var fixture = fixture(); var data = fixture.container.getPersistentDataContainer();
        when(data.has(VillageLootPending.PENDING)).thenReturn(true);
        when(data.has(VillageLootPending.PENDING, PersistentDataType.STRING)).thenReturn(false);
        doThrow(new IllegalArgumentException("wrong primitive type")).when(data).get(VillageLootPending.PENDING, PersistentDataType.STRING);
        fixture.pending.converter((table, draw) -> fail("malformed anchors must never be converted"));
        Inventory inventory = mock(Inventory.class); when(inventory.getHolder()).thenReturn(fixture.container);
        InventoryOpenEvent open = mock(InventoryOpenEvent.class); when(open.getInventory()).thenReturn(inventory);
        InventoryMoveItemEvent move = mock(InventoryMoveItemEvent.class); when(move.getSource()).thenReturn(inventory);
        Block originalBlock = fixture.container.getBlock();
        BlockBreakEvent destroy = mock(BlockBreakEvent.class); when(destroy.getBlock()).thenReturn(originalBlock);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            assertDoesNotThrow(() -> fixture.pending.onOpen(open));
            assertDoesNotThrow(() -> fixture.pending.onMove(move));
            assertDoesNotThrow(() -> fixture.pending.onBreak(destroy));
            verify(open).setCancelled(true); verify(move).setCancelled(true); verify(destroy).setCancelled(true);
            verify(data, never()).get(VillageLootPending.PENDING, PersistentDataType.STRING);
            verify(data, never()).remove(any()); verify(fixture.container, never()).update(anyBoolean(), anyBoolean());
        }
    }

    @Test void malformedPendingAnchorBlocksDestructionBeforeAnArmedNativeLootTableCanRoll() {
        var fixture = fixture(); Chest chest = (Chest) fixture.container;
        var data = chest.getPersistentDataContainer(); LootTable originalTable = fixture.event.getLootTable();
        when(data.has(VillageLootPending.PENDING)).thenReturn(true);
        when(data.has(VillageLootPending.PENDING, PersistentDataType.STRING)).thenReturn(false);
        when(chest.getLootTable()).thenReturn(originalTable);
        fixture.pending.converter((table, draw) -> fail("blocked escrow must not roll or convert"));
        Block originalBlock = chest.getBlock();
        BlockBreakEvent event = mock(BlockBreakEvent.class); when(event.getBlock()).thenReturn(originalBlock);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            assertDoesNotThrow(() -> fixture.pending.onBreak(event));
            verify(event).setCancelled(true);
            verify(chest, never()).getBlockInventory(); verify(chest, never()).getInventory();
            verify(chest, never()).update(anyBoolean(), anyBoolean());
            verify(data, never()).get(VillageLootPending.PENDING, PersistentDataType.STRING);
        }
    }

    @Test void destructionRecoversAnExistingDrawExactlyOnceBeforeInspectingTheFreshState() {
        var fixture = fixture(); Chest chest = (Chest) fixture.container;
        ItemStack token = stack(1, 64), built = stack(1, 64); when(token.serializeAsBytes()).thenReturn(new byte[]{1});
        AtomicInteger conversions = new AtomicInteger();
        fixture.pending.converter((table, draw) -> { conversions.incrementAndGet(); return List.of(built); });
        Block originalBlock = chest.getBlock();
        BlockBreakEvent event = mock(BlockBreakEvent.class); when(event.getBlock()).thenReturn(originalBlock);
        try (var bukkit = mockStatic(Bukkit.class); var items = mockStatic(ItemStack.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            items.when(() -> ItemStack.deserializeBytes(new byte[]{1})).thenReturn(token);
            fixture.pending.save(fixture.event, List.of(token), new IllegalStateException("CE unavailable"));
            fixture.pending.onBreak(event); fixture.pending.onBreak(event);
            assertEquals(1, conversions.get()); assertEquals(1, fixture.contents.get()[0].getAmount());
            verify(chest, never()).getBlockInventory(); verify(chest, never()).getInventory();
            verify(event, never()).setCancelled(true);
        }
    }

    @Test void validPendingAndStillArmedSourceTableAreHeldWithoutRerollDisarmOrConversion() throws Exception {
        var fixture = fixture(); Chest chest = (Chest) fixture.container;
        ItemStack token = stack(1, 64), built = stack(1, 64); when(token.serializeAsBytes()).thenReturn(new byte[]{1});
        AtomicInteger conversions = new AtomicInteger();
        fixture.pending.converter((table, draw) -> { conversions.incrementAndGet(); return List.of(built); });
        LootTable originalTable = fixture.event.getLootTable();
        Inventory inventory = mock(Inventory.class); when(inventory.getHolder()).thenReturn(chest);
        InventoryOpenEvent open = mock(InventoryOpenEvent.class); when(open.getInventory()).thenReturn(inventory);
        InventoryMoveItemEvent move = mock(InventoryMoveItemEvent.class); when(move.getSource()).thenReturn(inventory);
        Block originalBlock = chest.getBlock();
        BlockBreakEvent destroy = mock(BlockBreakEvent.class); when(destroy.getBlock()).thenReturn(originalBlock);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            fixture.pending.save(fixture.event, List.of(token), new IllegalStateException("CE unavailable"));
            String anchor = fixture.data.get(VillageLootPending.PENDING);
            Path journal = journal(fixture); byte[] originalJournal = Files.readAllBytes(journal);
            when(chest.getLootTable()).thenReturn(originalTable);
            fixture.pending.onOpen(open); fixture.pending.onMove(move); fixture.pending.onBreak(destroy);
            verify(open).setCancelled(true); verify(move).setCancelled(true); verify(destroy).setCancelled(true);
            assertEquals(0, conversions.get()); assertNull(fixture.contents.get()[0]);
            assertEquals(anchor, fixture.data.get(VillageLootPending.PENDING));
            assertArrayEquals(originalJournal, Files.readAllBytes(journal)); assertSame(originalTable, chest.getLootTable());
            verify(chest, never()).setLootTable(any());
            verify(chest, never()).getBlockInventory(); verify(chest, never()).getInventory();
            verify(chest, times(1)).update(false, false); // Only the original escrow anchor write.
        }
    }

    @Test void aSourceTableRearmedDuringConversionCannotBeCommittedAlongsideTheSavedDraw() {
        var fixture = fixture(); Chest chest = (Chest) fixture.container;
        ItemStack token = stack(1, 64), built = stack(1, 64); when(token.serializeAsBytes()).thenReturn(new byte[]{1});
        LootTable originalTable = fixture.event.getLootTable(); AtomicInteger conversions = new AtomicInteger();
        fixture.pending.converter((table, draw) -> {
            conversions.incrementAndGet(); when(chest.getLootTable()).thenReturn(originalTable); return List.of(built);
        });
        try (var bukkit = mockStatic(Bukkit.class); var items = mockStatic(ItemStack.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            items.when(() -> ItemStack.deserializeBytes(new byte[]{1})).thenReturn(token);
            fixture.pending.save(fixture.event, List.of(token), new IllegalStateException("CE unavailable"));
            String anchor = fixture.data.get(VillageLootPending.PENDING);
            assertFalse(fixture.pending.recover(chest)); assertEquals(1, conversions.get());
            assertEquals(anchor, fixture.data.get(VillageLootPending.PENDING)); assertNull(fixture.contents.get()[0]);
            assertSame(originalTable, chest.getLootTable()); verify(chest, never()).setLootTable(any());
            verify(chest, times(1)).update(false, false);
        }
    }

    @Test void typedAnchorReadFailureIsHeldRatherThanEscapingTheEventGuard() {
        var fixture = fixture(); var data = fixture.container.getPersistentDataContainer();
        when(data.has(VillageLootPending.PENDING)).thenReturn(true);
        when(data.has(VillageLootPending.PENDING, PersistentDataType.STRING)).thenReturn(true);
        doThrow(new IllegalArgumentException("unreadable primitive")).when(data).get(VillageLootPending.PENDING, PersistentDataType.STRING);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            assertFalse(fixture.pending.recover(fixture.container));
            verify(fixture.container, never()).update(anyBoolean(), anyBoolean());
        }
    }

    @Test void explosionsLeaveForeignRegionBlocksAloneWithoutReadingThem() {
        var fixture = fixture();
        Block foreign = mock(Block.class);
        when(foreign.getWorld()).thenReturn(fixture.world);
        when(foreign.getX()).thenReturn(16);
        when(foreign.getZ()).thenReturn(32);
        List<Block> affected = new java.util.ArrayList<>(List.of(foreign));
        BlockExplodeEvent event = mock(BlockExplodeEvent.class);
        when(event.blockList()).thenReturn(affected);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, 1, 2)).thenReturn(false);
            fixture.pending.onBlockExplosion(event);
        }

        assertEquals(List.of(foreign), affected);
        verify(foreign, never()).getState();
    }

    @Test void entityExplosionProtectsOnlyOwnedPendingContainers() {
        var fixture = fixture();
        Block foreign = mock(Block.class);
        when(foreign.getWorld()).thenReturn(fixture.world);
        when(foreign.getX()).thenReturn(16);
        when(foreign.getZ()).thenReturn(32);
        Block localContainer = fixture.container.getBlock();
        List<Block> affected = new java.util.ArrayList<>(List.of(foreign, localContainer));
        EntityExplodeEvent event = mock(EntityExplodeEvent.class);
        when(event.blockList()).thenReturn(affected);
        fixture.data.put(VillageLootPending.PENDING, "{broken json");

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, 1, 2)).thenReturn(false);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            bukkit.when(() -> fixture.world.isChunkLoaded(-1, 2)).thenReturn(true);
            fixture.pending.onEntityExplosion(event);
        }

        assertEquals(List.of(foreign), affected);
        verify(foreign, never()).getState();
    }

    @Test void savingAnotherDrawNeverOverwritesAnExistingWrongTypeAnchor() {
        var fixture = fixture(); var data = fixture.container.getPersistentDataContainer();
        when(data.has(VillageLootPending.PENDING)).thenReturn(true);
        ItemStack token = stack(1, 64); when(token.serializeAsBytes()).thenReturn(new byte[]{1});
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            fixture.pending.save(fixture.event, List.of(token), new IllegalStateException("CE unavailable"));
            verify(data, never()).set(eq(VillageLootPending.PENDING), eq(PersistentDataType.STRING), anyString());
            verify(fixture.container, never()).update(anyBoolean(), anyBoolean());
            assertFalse(Files.exists(directory.resolve("village-loot-pending")));
        }
    }

    @Test void largeExactStackLivesInHashedJournalWhileTheNbtAnchorRemainsSmallAndRecoverable() throws Exception {
        var fixture = fixture(); ItemStack token = stack(2, 64), built = stack(2, 64);
        byte[] serialized = new byte[70_000]; serialized[serialized.length - 1] = 7;
        when(token.serializeAsBytes()).thenReturn(serialized);
        try (var bukkit = mockStatic(Bukkit.class); var items = mockStatic(ItemStack.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            items.when(() -> ItemStack.deserializeBytes(serialized)).thenReturn(token);
            AtomicInteger conversions = new AtomicInteger();
            fixture.pending.converter((table, draw) -> {
                conversions.incrementAndGet(); assertEquals(List.of(token), draw); return List.of(built);
            });
            fixture.pending.save(fixture.event, List.of(token), new IllegalStateException("CE unavailable"));
            String anchor = fixture.data.get(VillageLootPending.PENDING); JsonObject pointer = JsonParser.parseString(anchor).getAsJsonObject();
            assertEquals(3, pointer.get("schemaVersion").getAsInt()); assertTrue(anchor.length() < 512);
            try (var output = new DataOutputStream(new ByteArrayOutputStream())) { output.writeUTF(anchor); }
            Path journal = journal(fixture); assertTrue(Files.size(journal) > 65_535);
            assertEquals(sha256(Files.readAllBytes(journal)), pointer.get("journalSha256").getAsString());
            assertTrue(fixture.pending.recover(fixture.container)); assertEquals(1, conversions.get());
            assertEquals(2, fixture.contents.get()[0].getAmount());
            assertTrue(fixture.pending.recover(fixture.container)); assertEquals(1, conversions.get());
        }
    }

    @Test void missingCorruptOrMismatchedJournalAndTamperedPointerNeverConvertOrCommit() throws Exception {
        for (int mutation = 0; mutation < 6; mutation++) {
            var fixture = fixture(); ItemStack token = stack(1, 64); when(token.serializeAsBytes()).thenReturn(new byte[]{1});
            fixture.pending.converter((table, draw) -> fail("untrusted journal must never be converted"));
            try (var bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
                fixture.pending.save(fixture.event, List.of(token), new IllegalStateException("CE unavailable"));
                Path journal = journal(fixture); JsonObject pointer = JsonParser.parseString(fixture.data.get(VillageLootPending.PENDING)).getAsJsonObject();
                if (mutation == 0) pointer.addProperty("journalSha256", "0".repeat(64));
                else if (mutation == 1) Files.delete(journal);
                else if (mutation == 2) Files.writeString(journal, Files.readString(journal) + "\n");
                else {
                    JsonObject record = JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
                    if (mutation == 3) record.addProperty("id", UUID.randomUUID().toString());
                    else if (mutation == 4) record.addProperty("sourceCommit", "unexpected source");
                    else record.addProperty("table", "minecraft:chests/village/village_plains_house");
                    Files.writeString(journal, record.toString(), StandardCharsets.UTF_8);
                    pointer.addProperty("journalSha256", sha256(Files.readAllBytes(journal)));
                }
                String anchor = pointer.toString(); fixture.data.put(VillageLootPending.PENDING, anchor);
                assertFalse(fixture.pending.recover(fixture.container), "mutation " + mutation);
                assertEquals(anchor, fixture.data.get(VillageLootPending.PENDING)); assertNull(fixture.contents.get()[0]);
                verify(fixture.container, times(1)).update(false, false); // Only the original anchor write.
            }
        }
    }

    @Test void conversionCannotCommitAfterItsExactAnchorPointerWasReplaced() throws Exception {
        var fixture = fixture(); ItemStack token = stack(1, 64); when(token.serializeAsBytes()).thenReturn(new byte[]{1});
        try (var bukkit = mockStatic(Bukkit.class); var items = mockStatic(ItemStack.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            items.when(() -> ItemStack.deserializeBytes(new byte[]{1})).thenReturn(token);
            fixture.pending.save(fixture.event, List.of(token), new IllegalStateException("CE unavailable"));
            String original = fixture.data.get(VillageLootPending.PENDING);
            fixture.pending.converter((table, draw) -> {
                fixture.data.put(VillageLootPending.PENDING, original + " "); return List.of(stack(1, 64));
            });
            assertFalse(fixture.pending.recover(fixture.container)); assertNull(fixture.contents.get()[0]);
            assertNull(fixture.data.get(VillageLootPending.RECEIPT));
        }
    }

    @Test void validLegacyFullJsonAnchorStillRecoversItsOriginalDraw() throws Exception {
        var fixture = fixture(); ItemStack token = stack(1, 64), built = stack(1, 64); when(token.serializeAsBytes()).thenReturn(new byte[]{1});
        try (var bukkit = mockStatic(Bukkit.class); var items = mockStatic(ItemStack.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            items.when(() -> ItemStack.deserializeBytes(new byte[]{1})).thenReturn(token);
            fixture.pending.converter((table, draw) -> List.of(built));
            fixture.pending.save(fixture.event, List.of(token), new IllegalStateException("CE unavailable"));
            fixture.data.put(VillageLootPending.PENDING, Files.readString(journal(fixture), StandardCharsets.UTF_8));
            assertTrue(fixture.pending.recover(fixture.container)); assertEquals(1, fixture.contents.get()[0].getAmount());
        }
    }

    @Test void malformedAnchorInScheduledCommandRecoveryIsReportedWithoutEscapingOrDelivering() throws Exception {
        var fixture = fixture(); ItemStack token = stack(1, 64); when(token.serializeAsBytes()).thenReturn(new byte[]{1});
        fixture.pending.converter((table, draw) -> fail("malformed command anchor must not be converted"));
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        CommandSender sender = mock(CommandSender.class); when(sender.hasPermission("kaleidoscopecookery.villageloot.recover")).thenReturn(true);
        Block originalBlock = fixture.container.getBlock(); when(fixture.world.getBlockAt(-1, 64, 32)).thenReturn(originalBlock);
        when(fixture.world.getBlockAt(any(Location.class))).thenReturn(originalBlock);
        PersistentDataContainer data = fixture.container.getPersistentDataContainer();
        UUID worldId = fixture.world.getUID();
        try (var bukkit = mockStatic(Bukkit.class); var folia = mockStatic(FoliaUtil.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(fixture.world);
            folia.when(() -> FoliaUtil.run(any(Runnable.class), any(Location.class))).thenAnswer(call -> { scheduled.set(call.getArgument(0)); return null; });
            fixture.pending.save(fixture.event, List.of(token), new IllegalStateException("CE unavailable"));
            String id = JsonParser.parseString(fixture.data.get(VillageLootPending.PENDING)).getAsJsonObject().get("id").getAsString();
            assertTrue(fixture.pending.onCommand(sender, mock(Command.class), "kcvillageloot", new String[]{"recover", id}));
            assertNotNull(scheduled.get()); fixture.data.put(VillageLootPending.PENDING, "{broken json");
            assertDoesNotThrow(() -> scheduled.get().run()); assertNull(fixture.contents.get()[0]);
            verify(data).get(VillageLootPending.PENDING, PersistentDataType.STRING);
            verify(fixture.logger).log(eq(java.util.logging.Level.WARNING), contains("invalid original pending anchor; no replay"), any(Throwable.class));
        }
    }

    @Test void stackBeyondTheBoundedJournalBudgetCannotCreateAnUnrecoverableAnchor() {
        var fixture = fixture(); ItemStack token = stack(1, 64); when(token.serializeAsBytes()).thenReturn(new byte[524_289]);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(fixture.world, -1, 2)).thenReturn(true);
            fixture.pending.save(fixture.event, List.of(token), new IllegalStateException("CE unavailable"));
            assertNull(fixture.data.get(VillageLootPending.PENDING));
            assertFalse(Files.exists(directory.resolve("village-loot-pending")));
            verify(fixture.container, never()).update(anyBoolean(), anyBoolean());
            verify(fixture.logger).log(eq(java.util.logging.Level.SEVERE), contains("manual reconciliation"), any(IllegalArgumentException.class));
        }
    }

    private Path journal(Fixture fixture) {
        String id = JsonParser.parseString(fixture.data.get(VillageLootPending.PENDING)).getAsJsonObject().get("id").getAsString();
        return directory.resolve("village-loot-pending").resolve(id + ".json");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Fixture(VillageLootPending pending, LootGenerateEvent event, World world, Container container,
                           HashMap<org.bukkit.NamespacedKey, String> data, AtomicReference<ItemStack[]> contents, Logger logger) {}

    private Fixture fixture() {
        JavaPlugin plugin = mock(JavaPlugin.class); when(plugin.getDataFolder()).thenReturn(directory.toFile());
        Logger logger = mock(Logger.class); when(plugin.getLogger()).thenReturn(logger);
        World world = mock(World.class); when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.isChunkLoaded(-1, 2)).thenReturn(true);
        Container container = mock(Chest.class); Block block = mock(Block.class); Inventory inventory = mock(Inventory.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class); var data = new HashMap<org.bukkit.NamespacedKey, String>();
        when(pdc.has(any(org.bukkit.NamespacedKey.class))).thenAnswer(call -> data.containsKey(call.getArgument(0)));
        when(pdc.has(any(), eq(PersistentDataType.STRING))).thenAnswer(call -> data.containsKey(call.getArgument(0)));
        when(pdc.get(any(), eq(PersistentDataType.STRING))).thenAnswer(call -> data.get(call.getArgument(0)));
        doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(2)); return null; }).when(pdc).set(any(), eq(PersistentDataType.STRING), anyString());
        doAnswer(call -> { data.remove(call.getArgument(0)); return null; }).when(pdc).remove(any());
        when(container.getPersistentDataContainer()).thenReturn(pdc); when(container.getWorld()).thenReturn(world);
        when(container.getX()).thenReturn(-1); when(container.getY()).thenReturn(64); when(container.getZ()).thenReturn(32);
        when(container.getType()).thenReturn(Material.CHEST); when(container.getBlock()).thenReturn(block); when(block.getState()).thenReturn(container);
        when(block.getWorld()).thenReturn(world); when(block.getX()).thenReturn(-1); when(block.getZ()).thenReturn(32);
        when(container.update(false, false)).thenReturn(true); when(container.getSnapshotInventory()).thenReturn(inventory);
        when(inventory.getMaxStackSize()).thenReturn(64);
        var contents = new AtomicReference<>(new ItemStack[3]); when(inventory.getContents()).thenAnswer(ignored -> contents.get());
        doAnswer(call -> { contents.set(call.getArgument(0)); return null; }).when(inventory).setContents(any(ItemStack[].class));
        LootGenerateEvent event = mock(LootGenerateEvent.class); LootTable table = mock(LootTable.class);
        when(table.getKey()).thenReturn(org.bukkit.NamespacedKey.fromString(VillageLootSpec.NORMAL_TABLE));
        when(event.getLootTable()).thenReturn(table); when(event.getInventoryHolder()).thenReturn(container);
        return new Fixture(new VillageLootPending(plugin, VillageLootSpec.bundled()), event, world, container, data, contents, logger);
    }
}
