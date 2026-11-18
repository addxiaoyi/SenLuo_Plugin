package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.TrashCanBehavior;
import net.kaleidoscope.cookery.block.entity.render.Particles;
import net.kaleidoscope.cookery.util.*;
import net.momirealms.craftengine.core.entity.furniture.*;
import net.momirealms.craftengine.core.entity.player.*;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.world.*;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.libraries.nbt.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static net.kaleidoscope.cookery.block.entity.TrashCanInventoryTest.item;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrashCanControllerTest {
    private static final String DATA = "kaleidoscopecookery:trashcan";
    private final Furniture furniture = mock(Furniture.class);
    private final World world = mock(World.class);
    private final org.bukkit.World bukkitWorld = mock(org.bukkit.World.class);
    private final org.bukkit.entity.Player bukkitPlayer = mock(org.bukkit.entity.Player.class);
    private final Player player = mock(Player.class);
    private final InteractEntityContext context = mock(InteractEntityContext.class);
    private final AtomicReference<Item> held = new AtomicReference<>();
    private final WorldPosition position = new WorldPosition(world, 8.5, 64, 8.5);
    private MockedConstruction<TrashCanElement> visuals;
    private MockedStatic<InteractGuard> protection;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<Item> empty;
    private MockedStatic<BlockEntityNbt> nbt;
    private MockedStatic<FurniturePersistentData> persistence;
    private MockedStatic<FoliaUtil> scheduling;
    private MockedStatic<Particles> particles;
    private TrashCanController can;

    @BeforeEach void setup() {
        Item air = item("air", "", 0); empty = mockStatic(Item.class); empty.when(Item::empty).thenReturn(air);
        visuals = mockConstruction(TrashCanElement.class); protection = mockStatic(InteractGuard.class);
        bukkit = mockStatic(Bukkit.class); nbt = mockStatic(BlockEntityNbt.class);
        persistence = mockStatic(FurniturePersistentData.class); scheduling = mockStatic(FoliaUtil.class); particles = mockStatic(Particles.class);
        persistence.when(() -> FurniturePersistentData.of(any())).thenReturn(mock(FurniturePersistentData.class));
        nbt.when(() -> BlockEntityNbt.itemTag(any())).thenReturn(new CompoundTag());
        when(furniture.position()).thenReturn(position); when(furniture.world()).thenReturn(world);
        when(furniture.isValid()).thenReturn(true); when(furniture.getTrackedBy()).thenReturn(java.util.Set.of());
        when(world.platformWorld()).thenReturn(bukkitWorld);
        when(player.platformPlayer()).thenReturn(bukkitPlayer); when(bukkitPlayer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(context.getPlayer()).thenReturn(player); when(context.getHand()).thenReturn(InteractionHand.MAIN_HAND);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenAnswer(call -> held.get());
        doAnswer(call -> { held.set(call.getArgument(1)); return null; }).when(player).setItemInHand(eq(InteractionHand.MAIN_HAND), any());
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(bukkitPlayer)).thenReturn(true);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(bukkitWorld, 0, 0)).thenReturn(true);
        protection.when(() -> InteractGuard.canInteract(player, position)).thenReturn(true);
        can = new TrashCanController(furniture, mock(TrashCanBehavior.class));
        held.set(item("apple", "named", 5));
    }
    @AfterEach void close() {
        particles.close(); scheduling.close(); persistence.close(); nbt.close(); bukkit.close(); protection.close(); visuals.close(); empty.close();
    }

    @Test void creativePaysAndSneakingEmptyHandReturnsLastWholeStack() {
        when(player.canInstabuild()).thenReturn(true); when(bukkitPlayer.getGameMode()).thenReturn(GameMode.CREATIVE);
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL, can.useOnFurniture(null, context)); assertTrue(held.get().isEmpty());
        held.set(item("bread", "last", 7)); can.useOnFurniture(null, context);
        assertEquals(InteractionResult.PASS, can.useOnFurniture(null, context));
        assertEquals(7, can.storage()[1].count());
        when(player.isSecondaryUseActive()).thenReturn(true);
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL, can.useOnFurniture(null, context));
        assertEquals(7, held.get().count()); assertEquals("last", held.get().customNameJson().orElseThrow().getAsString());
        assertEquals(5, can.storage()[0].count()); assertTrue(can.storage()[1].isEmpty());
        Item[] view = can.storage(); view[0] = Item.empty(); assertEquals(5, can.storage()[0].count());
    }
    @Test void offhandProtectedStaleSpectatorAndWrongRegionCannotMutate() {
        when(context.getHand()).thenReturn(InteractionHand.OFF_HAND);
        assertEquals(InteractionResult.PASS, can.useOnFurniture(null, context));
        when(context.getHand()).thenReturn(InteractionHand.MAIN_HAND);
        protection.when(() -> InteractGuard.canInteract(player, position)).thenReturn(false);
        assertFalse(can.insertItem(held.get(), player));
        protection.when(() -> InteractGuard.canInteract(player, position)).thenReturn(true);
        when(furniture.isValid()).thenReturn(false); assertFalse(can.insertItem(held.get(), player));
        when(furniture.isValid()).thenReturn(true); when(bukkitPlayer.getGameMode()).thenReturn(GameMode.SPECTATOR);
        assertFalse(can.insertItem(held.get(), player)); when(bukkitPlayer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(bukkitWorld, 0, 0)).thenReturn(false);
        assertFalse(can.insertItem(held.get(), player)); verify(player, never()).setItemInHand(any(), any());
        assertTrue(can.storage()[0].isEmpty());
    }
    @Test void failedPersistenceAndChangedHandDoNotChargeOrCommit() {
        nbt.when(() -> BlockEntityNbt.itemTag(any())).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> can.insertItem(held.get(), player));
        assertEquals(5, held.get().count()); assertTrue(can.storage()[0].isEmpty());
        nbt.when(() -> BlockEntityNbt.itemTag(any())).thenAnswer(call -> {
            held.set(item("diamond", "replacement", 1)); return new CompoundTag();
        });
        assertFalse(can.insertItem(held.get(), player)); assertEquals("replacement", held.get().customNameJson().orElseThrow().getAsString());
        assertTrue(can.storage()[0].isEmpty());
    }
    @Test void nestedInsertionAndWithdrawalAreRejectedDuringPayment() {
        doAnswer(call -> {
            assertFalse(can.insertItem(held.get(), player));
            assertFalse(can.withdrawItem(player, InteractionHand.MAIN_HAND));
            held.set(call.getArgument(1)); return null;
        }).when(player).setItemInHand(eq(InteractionHand.MAIN_HAND), any());
        assertTrue(can.insertItem(held.get(), player)); assertEquals(5, can.storage()[0].count());
        assertTrue(held.get().isEmpty());
    }
    @Test void malformedSaveIsPreservedAndCannotBeOverwrittenByInteractions() {
        CompoundTag bad = new CompoundTag(); bad.putString(DATA, "unreadable");
        assertThrows(IllegalArgumentException.class, () -> can.loadCustomData(bad));
        assertFalse(can.insertItem(held.get(), player));
        CompoundTag saved = new CompoundTag(); can.saveCustomData(saved); assertEquals(bad, saved);
        can.loadCustomData(new CompoundTag()); assertTrue(can.insertItem(held.get(), player));
    }

    @Test void tickerUsesWorldTimeAndCurrentTrackersAndStopsOnUnload() {
        when(bukkitWorld.getBlockAt(8,64,8)).thenReturn(mock(org.bukkit.block.Block.class));
        when(bukkitWorld.getGameTime()).thenReturn(100L);
        assertTrue(can.insertItem(held.get(),player));
        Player newViewer=mock(Player.class);
        when(furniture.trackedBy()).thenReturn(List.of(newViewer));
        when(bukkitWorld.getGameTime()).thenReturn(103L);
        var ticker=can.<TrashCanController>createFurnitureTicker();
        ticker.tick(furniture,can);
        var visual=visuals.constructed().getFirst();
        verify(visual).applyPose(eq(TrashCanAnimation.sample(TrashCanAnimation.Clip.PUT,.15f)),eq(List.of(newViewer)),eq(1));
        assertTrue(can.isAnimating());
        when(bukkitWorld.getGameTime()).thenReturn(112L);ticker.tick(furniture,can);assertFalse(can.isAnimating());
        can.onUnload();clearInvocations(visual);
        when(bukkitWorld.getGameTime()).thenReturn(120L);ticker.tick(furniture,can);
        verifyNoInteractions(visual);
    }
}
