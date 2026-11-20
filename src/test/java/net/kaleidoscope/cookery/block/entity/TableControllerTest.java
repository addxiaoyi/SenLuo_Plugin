package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.TableBehavior;
import net.kaleidoscope.cookery.util.*;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.*;
import net.momirealms.craftengine.core.entity.player.*;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.*;
import net.momirealms.craftengine.libraries.nbt.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TableControllerTest {
    private static final String DATA = "kaleidoscopecookery:table";
    private static final Key RED = Key.of("minecraft:red_carpet"), BLUE = Key.of("minecraft:blue_carpet");
    private final Player player = mock(Player.class);
    private final org.bukkit.entity.Player bukkitPlayer = mock(org.bukkit.entity.Player.class);
    private final org.bukkit.World bukkitWorld = mock(org.bukkit.World.class);
    private final World world = mock(World.class);
    private final CEWorld storage = mock(CEWorld.class);
    private final AtomicReference<Item> held = new AtomicReference<>();
    private MockedConstruction<TableElement> visuals;
    private MockedStatic<BlockEntityNbt> nbt;
    private MockedStatic<InventoryUtils> inventory;
    private MockedStatic<DropUtils> drops;
    private MockedStatic<InteractGuard> protection;
    private MockedStatic<PlacementGuard> placement;
    private MockedStatic<Bukkit> bukkit;
    private BlockEntity entity;
    private TableBehavior behavior;
    private TableController table;

    private Item item(String id, int count) { return item(Key.of(id), count); }
    private Item item(Key id, int count) {
        Item result = mock(Item.class);
        when(result.id()).thenReturn(id);
        when(result.count()).thenReturn(count);
        when(result.isEmpty()).thenReturn(count == 0);
        when(result.copy()).thenAnswer(call -> item(id, count));
        when(result.copyWithCount(anyInt())).thenAnswer(call -> item(id, call.getArgument(0)));
        when(result.isSimilar(any())).thenAnswer(call -> id.equals(((Item) call.getArgument(0)).id()));
        return result;
    }

    @BeforeEach void setup() {
        visuals = mockConstruction(TableElement.class);
        nbt = mockStatic(BlockEntityNbt.class);
        inventory = mockStatic(InventoryUtils.class);
        drops = mockStatic(DropUtils.class);
        protection = mockStatic(InteractGuard.class);
        placement = mockStatic(PlacementGuard.class);
        bukkit = mockStatic(Bukkit.class);
        behavior = mock(TableBehavior.class);
        when(behavior.createBlockEntityController(any())).thenReturn(mock(BlockEntityController.class));
        ImmutableBlockState state = mock(ImmutableBlockState.class);
        when(state.behavior()).thenReturn(behavior);
        entity = new BlockEntity(new BlockPos(8, 64, 8), state);
        entity.world = storage; entity.setValid(true);
        when(storage.world()).thenReturn(world);
        when(storage.getBlockEntityAtIfLoaded(entity.pos)).thenReturn(entity);
        when(world.platformWorld()).thenReturn(bukkitWorld);
        when(player.platformPlayer()).thenReturn(bukkitPlayer);
        when(bukkitPlayer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(bukkitPlayer)).thenReturn(true);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(bukkitWorld, 0, 0)).thenReturn(true);
        protection.when(() -> InteractGuard.canInteract(player, world, entity.pos)).thenReturn(true);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenAnswer(call -> held.get());
        doAnswer(call -> { held.set(call.getArgument(1)); return null; }).when(player).setItemInHand(any(), any());
        inventory.when(() -> InventoryUtils.createOrEmpty(any(Key.class))).thenAnswer(call -> item((Key) call.getArgument(0), 1));
        nbt.when(BlockEntityNbt::newData).thenAnswer(call -> new CompoundTag());
        nbt.when(() -> BlockEntityNbt.itemTag(any())).thenAnswer(call -> {
            Item item = call.getArgument(0); CompoundTag data = new CompoundTag();
            data.putString("id", item.id().asString()); data.putInt("count", item.count()); return data;
        });
        nbt.when(() -> BlockEntityNbt.getItem(any(), eq("item"), anyInt())).thenAnswer(call -> {
            CompoundTag tag = ((CompoundTag) call.getArgument(0)).getCompound("item");
            return item(tag.getString("id"), tag.getInt("count"));
        });
        table = new TableController(entity, behavior);
        held.set(item("minecraft:apple", 8));
    }
    @AfterEach void close() {
        bukkit.close(); placement.close(); protection.close(); drops.close(); inventory.close(); nbt.close(); visuals.close();
    }
    private void emptyHand() { held.set(item("minecraft:air", 0)); }
    private void put(String id) { held.set(item(id, 8)); assertEquals(InteractionResult.SUCCESS_AND_CANCEL, table.interact(player)); }
    private CompoundTag saved() { CompoundTag tag = new CompoundTag(); table.saveCustomData(tag); return tag; }

    @Test void fourIndividualSlotsConsumeEvenInCreativeAndFullTablePasses() {
        when(player.canInstabuild()).thenReturn(true);
        when(bukkitPlayer.getGameMode()).thenReturn(GameMode.CREATIVE);
        for (int i = 0; i < 4; i++) assertEquals(InteractionResult.SUCCESS_AND_CANCEL, table.interact(player));
        assertEquals(4, held.get().count());
        assertEquals(4, table.displayedItems().size());
        assertTrue(table.displayedItems().stream().allMatch(i -> i.count() == 1));
        assertEquals(InteractionResult.PASS, table.interact(player));
        assertEquals(4, held.get().count());
    }
    @Test void emptyHandDropsLastItemAndNeverRemovesCarpet() {
        put(RED.asString()); put("minecraft:apple"); put("minecraft:diamond"); emptyHand();
        table.interact(player);
        drops.verify(() -> DropUtils.dropAtHeight(eq(entity), argThat(i -> i.id().equals(Key.of("minecraft:diamond"))), eq(.75)));
        assertEquals(Key.of("minecraft:apple"), table.displayedItems().getFirst().id());
        table.interact(player);
        assertEquals(InteractionResult.PASS, table.interact(player));
        assertEquals(RED, table.carpet());
    }
    @Test void carpetSwapReturnsOldInWorldAndSameColorPassesWithoutConsumption() {
        put(RED.asString());
        assertEquals(7, held.get().count());
        assertEquals(InteractionResult.PASS, table.interact(player));
        assertEquals(7, held.get().count());
        put(BLUE.asString());
        drops.verify(() -> DropUtils.dropAtHeight(eq(entity), argThat(i -> i.id().equals(RED) && i.count()==1), eq(.75)));
        assertEquals(BLUE, table.carpet());
        assertTrue(table.displayedItems().isEmpty());
    }
    @Test void savedItemsAndCarpetRoundTripAndEmptyLoadClearsBoth() {
        put(RED.asString()); put("minecraft:apple"); put("minecraft:diamond");
        TableController loaded = new TableController(entity, behavior); loaded.loadCustomData(saved());
        assertEquals(RED, loaded.carpet());
        assertEquals(table.displayedItems().stream().map(Item::id).toList(), loaded.displayedItems().stream().map(Item::id).toList());
        loaded.loadCustomData(new CompoundTag());
        assertNull(loaded.carpet()); assertTrue(loaded.displayedItems().isEmpty());
    }
    @Test void legacyCarpetOnlySaveStillLoads() {
        CompoundTag root = new CompoundTag(), payload = new CompoundTag();
        payload.putString("carpet", RED.asString()); root.put(DATA, payload);
        table.loadCustomData(root); assertEquals(RED, table.carpet()); assertTrue(table.displayedItems().isEmpty());
    }
    @Test void unsupportedAndUnparseableSavedItemsArePreservedAndBlockMutation() {
        put("minecraft:apple"); CompoundTag root = saved();
        CookeryBlockStateEnvelope.payload(root, DATA).getList("items").getCompound(0).getCompound("item").putInt("count", 64);
        assertThrows(IllegalArgumentException.class, () -> table.loadCustomData(root));
        assertEquals(root, saved()); assertEquals(InteractionResult.FAIL, table.interact(player));
        assertThrows(IllegalStateException.class, table::onRemove);
        drops.verifyNoInteractions();
    }
    @Test void protectionSpectatorInvalidAndForeignRegionCannotMoveItems() {
        protection.when(() -> InteractGuard.canInteract(player, world, entity.pos)).thenReturn(false);
        assertEquals(InteractionResult.FAIL, table.interact(player));
        protection.when(() -> InteractGuard.canInteract(player, world, entity.pos)).thenReturn(true);
        when(bukkitPlayer.getGameMode()).thenReturn(GameMode.SPECTATOR);
        assertEquals(InteractionResult.FAIL, table.interact(player));
        when(bukkitPlayer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        entity.setValid(false); assertEquals(InteractionResult.FAIL, table.interact(player)); entity.setValid(true);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(bukkitWorld, 0, 0)).thenReturn(false);
        assertEquals(InteractionResult.FAIL, table.interact(player));
        assertEquals(8, held.get().count()); assertTrue(table.displayedItems().isEmpty());
    }
    @Test void staleEntityAfterProtectionCallbackCannotConsume() {
        protection.when(() -> InteractGuard.canInteract(player, world, entity.pos)).thenAnswer(call -> {
            when(storage.getBlockEntityAtIfLoaded(entity.pos)).thenReturn(null); return true;
        });
        assertEquals(InteractionResult.FAIL, table.interact(player)); assertEquals(8, held.get().count());
    }
    @Test void callbackCannotReenterAndDropTheNextItem() {
        put("minecraft:apple"); put("minecraft:diamond"); emptyHand();
        drops.when(() -> DropUtils.dropAtHeight(eq(entity), any(), eq(.75))).thenAnswer(call -> {
            assertEquals(InteractionResult.FAIL, table.interact(player));
            assertEquals(1, table.displayedItems().size()); return null;
        });
        table.interact(player); assertEquals(1, table.displayedItems().size());
        drops.verify(() -> DropUtils.dropAtHeight(eq(entity), any(), eq(.75)), times(1));
    }
    @Test void repeatedOrReentrantRemovalReturnsContentsAndCarpetOnce() {
        put(RED.asString()); put("minecraft:apple");
        drops.when(() -> DropUtils.dropOnRemove(eq(entity), any())).thenAnswer(call -> {
            table.onRemove(); assertEquals(InteractionResult.FAIL, table.interact(player)); return null;
        });
        table.onRemove(); table.onRemove();
        drops.verify(() -> DropUtils.dropOnRemove(eq(entity), any()), times(2));
        assertTrue(table.displayedItems().isEmpty()); assertNull(table.carpet());
    }
    @Test void creativeRemovalReturnsContentsButNotCarpet() {
        put(RED.asString()); put("minecraft:apple"); table.markCreativeBreak(); table.onRemove();
        drops.verify(() -> DropUtils.dropOnRemove(eq(entity), argThat(i -> i.id().equals(Key.of("minecraft:apple")))), times(1));
        drops.verifyNoMoreInteractions();
    }
    @Test void placementRollbackDoesNotGenerateDrops() {
        put(RED.asString()); put("minecraft:apple");
        placement.when(() -> PlacementGuard.isPlacing(entity)).thenReturn(true);
        table.onRemove(); drops.verifyNoInteractions();
    }
    @Test void missingCarpetRefundOrChangedHandCannotConsumeReplacement() {
        put(RED.asString()); held.set(item(BLUE, 3)); Item missing = item(RED, 0);
        inventory.when(() -> InventoryUtils.createOrEmpty(RED)).thenReturn(missing);
        assertEquals(InteractionResult.FAIL, table.interact(player)); assertEquals(3, held.get().count()); assertEquals(RED, table.carpet());
        inventory.when(() -> InventoryUtils.createOrEmpty(RED)).thenAnswer(call -> { held.set(item("minecraft:diamond", 1)); return item(RED, 1); });
        assertEquals(InteractionResult.FAIL, table.interact(player)); assertEquals(RED, table.carpet()); drops.verifyNoInteractions();
    }
    @Test void corruptRawStateCannotInheritAnOlderEnvelopeDuringSave() {
        CompoundTag target = saved(), raw = new CompoundTag(); raw.putString(DATA, "broken");
        assertThrows(IllegalArgumentException.class, () -> table.loadCustomData(raw));
        table.saveCustomData(target); assertEquals(raw, target);
    }
    @Test void invalidSchemaMissingItemsAndOversizeInventoryAreRejected() {
        for (int kind = 0; kind < 4; kind++) {
            CompoundTag root = new CompoundTag(), data = new CompoundTag(); data.putInt("schema", 1);
            ListTag list = new ListTag(); data.put("items", list);
            if (kind == 0) data.putInt("schema", 2);
            if (kind == 1) data.remove("items");
            if (kind == 2) data.putString("items", "bad");
            if (kind == 3) for (int i = 0; i < 5; i++) list.add(new CompoundTag());
            root.put(DATA, data);
            assertThrows(IllegalArgumentException.class, () -> table.loadCustomData(root));
            assertEquals(root, saved());
        }
    }
    @Test void failedSerializationNeverOverwritesPreviousSaveWithPartialItems() {
        put("minecraft:apple"); CompoundTag target = saved(), before = target.deepClone();
        nbt.when(() -> BlockEntityNbt.itemTag(any())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> table.saveCustomData(target)); assertEquals(before, target);
    }
}
