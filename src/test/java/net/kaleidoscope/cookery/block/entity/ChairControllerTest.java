package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.ChairBehavior;
import net.kaleidoscope.cookery.util.CookeryBlockStateEnvelope;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.player.*;
import net.momirealms.craftengine.core.entity.seat.Seat;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChairControllerTest {
    private static final String DATA = "kaleidoscopecookery:chair";
    private static final Key RED = Key.of("minecraft:red_carpet"), BLUE = Key.of("minecraft:blue_carpet");
    private final Furniture furniture = mock(Furniture.class);
    private final World world = mock(World.class);
    private final org.bukkit.World bukkitWorld = mock(org.bukkit.World.class);
    private final org.bukkit.entity.Player bukkitPlayer = mock(org.bukkit.entity.Player.class);
    private final Player player = mock(Player.class);
    private final InteractEntityContext context = mock(InteractEntityContext.class);
    private final FurnitureHitBox hitbox = mock(FurnitureHitBox.class);
    private final AtomicReference<Item> held = new AtomicReference<>();
    private final WorldPosition position = new WorldPosition(world, .5, 64, .5);
    private MockedConstruction<ChairElement> visuals;
    private MockedStatic<InventoryUtils> inventory;
    private MockedStatic<InteractGuard> protection;
    private MockedStatic<Bukkit> bukkit;
    private ChairController chair;

    private Item item(Key key, int amount) {
        Item result = mock(Item.class);
        when(result.id()).thenReturn(key);
        when(result.count()).thenReturn(amount);
        when(result.isEmpty()).thenReturn(amount == 0);
        when(result.copy()).thenAnswer(call -> item(key, amount));
        when(result.copyWithCount(anyInt())).thenAnswer(call -> item(key, call.getArgument(0)));
        when(result.isSimilar(any())).thenAnswer(call -> key.equals(((Item) call.getArgument(0)).id()));
        return result;
    }

    @BeforeEach void setup() {
        visuals = mockConstruction(ChairElement.class);
        inventory = mockStatic(InventoryUtils.class);
        protection = mockStatic(InteractGuard.class);
        bukkit = mockStatic(Bukkit.class);
        when(furniture.position()).thenReturn(position);
        when(furniture.world()).thenReturn(world);
        when(furniture.isValid()).thenReturn(true);
        when(furniture.trackedBy()).thenReturn(List.of());
        when(world.platformWorld()).thenReturn(bukkitWorld);
        when(player.platformPlayer()).thenReturn(bukkitPlayer);
        when(bukkitPlayer.getWorld()).thenReturn(bukkitWorld);
        when(bukkitPlayer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(context.getPlayer()).thenReturn(player);
        when(context.getHand()).thenReturn(InteractionHand.MAIN_HAND);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenAnswer(call -> held.get());
        doAnswer(call -> { held.set(call.getArgument(1)); return null; })
                .when(player).setItemInHand(eq(InteractionHand.MAIN_HAND), any());
        when(hitbox.seats()).thenReturn(new Seat[0]);
        protection.when(() -> InteractGuard.canInteract(player, position)).thenReturn(true);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(bukkitPlayer)).thenReturn(true);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(bukkitWorld, 0, 0)).thenReturn(true);
        inventory.when(() -> InventoryUtils.createOrEmpty(any(Key.class)))
                .thenAnswer(call -> item(call.getArgument(0), 1));
        chair = new ChairController(furniture, mock(ChairBehavior.class));
        held.set(item(RED, 3));
    }

    @AfterEach void close() { bukkit.close(); protection.close(); inventory.close(); visuals.close(); }

    private void load(Key carpet) {
        CompoundTag root = new CompoundTag(), payload = new CompoundTag();
        if (carpet != null) payload.putString("carpet", carpet.asString());
        CookeryBlockStateEnvelope.put(root, DATA, payload);
        chair.loadCustomData(root);
    }

    @Test void insertAndSwapConsumeOneEvenInCreativeAndReturnThePreviousCarpet() {
        when(player.canInstabuild()).thenReturn(true);
        when(bukkitPlayer.getGameMode()).thenReturn(GameMode.CREATIVE);
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL, chair.useOnFurniture(hitbox, context));
        assertEquals(2, held.get().count());
        assertEquals(Key.of("show:chair_carpet_red"), chair.carpetModel());
        held.set(item(BLUE, 3));
        chair.useOnFurniture(hitbox, context);
        assertEquals(2, held.get().count());
        ArgumentCaptor<Item> refund = ArgumentCaptor.forClass(Item.class);
        verify(world).dropItemNaturally(eq(position), refund.capture());
        assertEquals(RED, refund.getValue().id());
        assertEquals(1, refund.getValue().count());
        assertEquals(Key.of("show:chair_carpet_blue"), chair.carpetModel());
    }

    @Test void sameColorConsumesNothingAndDoesNotFallbackToSeating() {
        load(RED);
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL, chair.useOnFurniture(hitbox, context));
        assertEquals(3, held.get().count());
        verify(player, never()).setItemInHand(any(), any());
        verify(hitbox, never()).seats();
    }

    @Test void sneakingEmptyHandSeatsInsteadOfRemovingTheCarpet() {
        load(RED);
        held.set(item(RED, 0));
        when(player.isSecondaryUseActive()).thenReturn(true);
        Seat seat = mock(Seat.class);
        when(hitbox.seats()).thenReturn(new Seat[]{seat});
        when(seat.spawnSeat(player, position)).thenReturn(true);
        chair.useOnFurniture(hitbox, context);
        verify(seat).spawnSeat(player, position);
        assertEquals(Key.of("show:chair_carpet_red"), chair.carpetModel());
        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test void occupiedSeatDoesNotEvictItsRider() {
        held.set(item(RED, 0));
        Seat seat = mock(Seat.class);
        when(hitbox.seats()).thenReturn(new Seat[]{seat});
        when(seat.isOccupied()).thenReturn(true);
        chair.useOnFurniture(hitbox, context);
        verify(seat, never()).spawnSeat(any(), any());
    }

    @Test void deniedStaleSpectatorAndCrossRegionInteractionsCannotChangeInventory() {
        protection.when(() -> InteractGuard.canInteract(player, position)).thenReturn(false);
        assertEquals(InteractionResult.FAIL, chair.useOnFurniture(hitbox, context));
        protection.when(() -> InteractGuard.canInteract(player, position)).thenReturn(true);
        when(furniture.isValid()).thenReturn(false);
        assertEquals(InteractionResult.FAIL, chair.useOnFurniture(hitbox, context));
        when(furniture.isValid()).thenReturn(true);
        when(bukkitPlayer.getGameMode()).thenReturn(GameMode.SPECTATOR);
        assertEquals(InteractionResult.FAIL, chair.useOnFurniture(hitbox, context));
        when(bukkitPlayer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(bukkitWorld, 0, 0)).thenReturn(false);
        assertEquals(InteractionResult.FAIL, chair.useOnFurniture(hitbox, context));
        verify(player, never()).setItemInHand(any(), any());
        assertNull(chair.carpetModel());
    }

    @Test void changedHandDuringItemConstructionIsPreserved() {
        load(BLUE);
        Item replacement = item(Key.of("minecraft:diamond"), 4);
        inventory.when(() -> InventoryUtils.createOrEmpty(BLUE)).thenAnswer(call -> {
            held.set(replacement); return item(BLUE, 1);
        });
        assertEquals(InteractionResult.FAIL, chair.useOnFurniture(hitbox, context));
        assertSame(replacement, held.get());
        assertEquals(Key.of("show:chair_carpet_blue"), chair.carpetModel());
    }

    @Test void refundCallbackCannotRecursivelyExchangeOrRepeatTheOldRefund() {
        load(BLUE);
        doAnswer(call -> {
            assertEquals(InteractionResult.FAIL, chair.useOnFurniture(hitbox, context));
            return null;
        }).when(world).dropItemNaturally(eq(position), any(Item.class));
        chair.useOnFurniture(hitbox, context);
        assertEquals(2, held.get().count());
        verify(world).dropItemNaturally(eq(position), any(Item.class));
        assertEquals(Key.of("show:chair_carpet_red"), chair.carpetModel());
    }

    @Test void repeatedRemovalCannotDuplicateCarpetAndCreativeRemovalDropsNone() {
        load(RED);
        chair.preRemove(player); chair.preRemove(player);
        verify(world).dropItemNaturally(eq(position), any(Item.class));
        load(BLUE);
        when(player.canInstabuild()).thenReturn(true);
        chair.preRemove(player);
        verify(world, times(1)).dropItemNaturally(eq(position), any(Item.class));
    }

    @Test void savesRoundTripAndLoadingEmptyClearsTheOldCarpet() {
        load(RED);
        CompoundTag saved = new CompoundTag(); chair.saveCustomData(saved);
        load(BLUE); chair.loadCustomData(saved);
        assertEquals(Key.of("show:chair_carpet_red"), chair.carpetModel());
        chair.loadCustomData(new CompoundTag());
        chair.saveCustomData(saved);
        assertNull(chair.carpetModel());
        assertFalse(CookeryBlockStateEnvelope.payload(saved, DATA).containsKey("carpet"));
    }

    @Test void corruptCarpetStateIsPreservedAndCannotBeSpentOrDestroyed() {
        CompoundTag saved = new CompoundTag(); chair.saveCustomData(saved);
        CompoundTag root = new CompoundTag(), invalid = new CompoundTag();
        invalid.putString("carpet", "minecraft:diamond"); root.put(DATA, invalid);
        assertThrows(IllegalArgumentException.class, () -> chair.loadCustomData(root));
        assertEquals(InteractionResult.FAIL, chair.useOnFurniture(hitbox, context));
        assertThrows(IllegalStateException.class, () -> chair.preRemove(player));
        chair.saveCustomData(saved);
        assertEquals(root, saved);
        verify(player, never()).setItemInHand(any(), any());
    }

    @Test void missingRefundCannotConsumeInputOrEraseAnInstalledCarpet() {
        load(BLUE);
        Item absent = item(BLUE, 0);
        inventory.when(() -> InventoryUtils.createOrEmpty(BLUE)).thenReturn(absent);
        assertEquals(InteractionResult.FAIL, chair.useOnFurniture(hitbox, context));
        assertEquals(3, held.get().count());
        assertEquals(Key.of("show:chair_carpet_blue"), chair.carpetModel());
        assertThrows(IllegalStateException.class, () -> chair.preRemove(player));
        assertEquals(Key.of("show:chair_carpet_blue"), chair.carpetModel());
    }

    @Test void removalReentryCannotDuplicateItsCarpet() {
        load(BLUE);
        inventory.when(() -> InventoryUtils.createOrEmpty(BLUE)).thenAnswer(call -> {
            chair.preRemove(player);
            assertEquals(InteractionResult.FAIL, chair.useOnFurniture(hitbox, context));
            return item(BLUE, 1);
        });
        chair.preRemove(player);
        verify(world).dropItemNaturally(eq(position), any(Item.class));
        assertNull(chair.carpetModel());
        assertEquals(3, held.get().count());
    }
}
