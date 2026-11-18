package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.api.KaleidoscopeAdvancements;
import net.kaleidoscope.cookery.block.behavior.ScarecrowBehavior;
import net.kaleidoscope.cookery.util.*;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.entity.furniture.*;
import net.momirealms.craftengine.core.entity.player.*;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.world.*;
import net.momirealms.craftengine.libraries.nbt.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static net.kaleidoscope.cookery.block.entity.ScarecrowEquipmentTest.equipment;

class ScarecrowControllerTest {
    private static final String DATA="kaleidoscopecookery:scarecrow";
    private final Furniture furniture=mock(Furniture.class);
    private final World world=mock(World.class);
    private final org.bukkit.World bukkitWorld=mock(org.bukkit.World.class);
    private final org.bukkit.entity.Player actor=mock(org.bukkit.entity.Player.class);
    private final Player player=mock(Player.class);
    private final WorldPosition pos=new WorldPosition(world,8.5,64,8.5);
    private final AtomicReference<Item> held=new AtomicReference<>();
    private final List<AutoCloseable> mocks=new ArrayList<>();
    private MockedStatic<BlockEntityNbt> nbt;
    private MockedStatic<InteractGuard> protection;
    private MockedStatic<FurnitureRecovery> recovery;
    private ScarecrowController scarecrow;
    private long tick;

    private <T extends AutoCloseable>T keep(T mock){mocks.add(mock);return mock;}
    @BeforeEach void setup() throws Exception {
        Item air=equipment("air","",0,0);var empty=keep(mockStatic(Item.class));empty.when(Item::empty).thenReturn(air);
        keep(mockConstruction(ScarecrowElement.class));var bukkit=keep(mockStatic(Bukkit.class));
        keep(mockStatic(KaleidoscopeAdvancements.class));
        keep(mockStatic(ScarecrowHitEffects.class));
        recovery=keep(mockStatic(FurnitureRecovery.class));
        nbt=keep(mockStatic(BlockEntityNbt.class));protection=keep(mockStatic(InteractGuard.class));
        var data=keep(mockStatic(FurniturePersistentData.class));var persisted=mock(FurniturePersistentData.class);
        data.when(()->FurniturePersistentData.of(any())).thenReturn(persisted);
        nbt.when(BlockEntityNbt::newData).thenAnswer(call->new CompoundTag());
        nbt.when(()->BlockEntityNbt.itemTag(any())).thenAnswer(call->{CompoundTag tag=new CompoundTag();tag.putString("id",((Item)call.getArgument(0)).id().asString());return tag;});
        nbt.when(()->BlockEntityNbt.getItem(any(),eq("item"),anyInt())).thenAnswer(call->equipment("bow","restored",1,384));
        when(furniture.position()).thenReturn(pos);when(furniture.world()).thenReturn(world);when(furniture.isValid()).thenReturn(true);
        when(furniture.trackedBy()).thenReturn(List.of());when(world.platformWorld()).thenReturn(bukkitWorld);
        when(bukkitWorld.getGameTime()).thenAnswer(call->tick);
        when(player.platformPlayer()).thenReturn(actor);when(actor.getGameMode()).thenReturn(GameMode.SURVIVAL);when(actor.getWorld()).thenReturn(bukkitWorld);
        when(actor.getUniqueId()).thenReturn(UUID.randomUUID());
        when(actor.getInventory()).thenReturn(mock(org.bukkit.inventory.PlayerInventory.class));
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenAnswer(call->held.get());
        doAnswer(call->{held.set(call.getArgument(1));return null;}).when(player).setItemInHand(eq(InteractionHand.MAIN_HAND),any());
        bukkit.when(()->Bukkit.isOwnedByCurrentRegion(actor)).thenReturn(true);
        bukkit.when(()->Bukkit.isOwnedByCurrentRegion(bukkitWorld,0,0)).thenReturn(true);
        protection.when(()->InteractGuard.canInteract(player,pos)).thenReturn(true);
        protection.when(()->InteractGuard.canBreak(eq(actor),any(org.bukkit.Location.class))).thenReturn(true);
        ScarecrowBehavior behavior=mock(ScarecrowBehavior.class);
        var cooldown=ScarecrowBehavior.class.getDeclaredField("interactCooldownTicks");cooldown.setAccessible(true);cooldown.setInt(behavior,5);
        scarecrow=new ScarecrowController(furniture,behavior);held.set(equipment("iron_pickaxe","first",1,250));
    }
    @AfterEach void close() throws Exception {Collections.reverse(mocks);for(var mock:mocks)mock.close();}

    @Test void twoHandsAndFailedInputCooldownFollowGameTicks() {
        held.set(equipment("lantern","",1,0));assertEquals(InteractionResult.PASS,scarecrow.exchange(player,false));
        held.set(equipment("iron_pickaxe","first",1,250));tick=4;assertEquals(InteractionResult.PASS,scarecrow.exchange(player,false));
        tick=5;assertEquals(InteractionResult.SUCCESS_AND_CANCEL,scarecrow.exchange(player,false));assertTrue(held.get().isEmpty());
        held.set(equipment("bow","second",1,384));tick=10;assertEquals(InteractionResult.SUCCESS_AND_CANCEL,scarecrow.exchange(player,false));
        assertEquals("first",scarecrow.item(1).customNameJson().orElseThrow().getAsString());
        assertEquals("second",scarecrow.item(2).customNameJson().orElseThrow().getAsString());
        held.set(equipment("bow","third",1,384));tick=15;assertEquals(InteractionResult.PASS,scarecrow.exchange(player,false));assertEquals(1,held.get().count());
    }
    @Test void creativeHeadSwapsOccupiedSingleItemButDoesNotDeleteStack() {
        when(player.canInstabuild()).thenReturn(true);held.set(equipment("player_head","first",1,0));
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL,scarecrow.exchange(player,true));assertEquals(1,held.get().count());
        tick=5;held.set(equipment("skeleton_skull","second",1,0));assertEquals(InteractionResult.SUCCESS_AND_CANCEL,scarecrow.exchange(player,true));
        assertEquals("first",held.get().customNameJson().orElseThrow().getAsString());
        tick=10;held.set(equipment("player_head","stack",3,0));assertEquals(InteractionResult.PASS,scarecrow.exchange(player,true));assertEquals(3,held.get().count());
    }
    @Test void serializationChangesToHeldItemAbortBeforeEquipmentOwnershipMoves() {
        nbt.when(()->BlockEntityNbt.itemTag(any())).thenAnswer(call->{held.set(equipment("diamond","replacement",4,0));return new CompoundTag();});
        assertEquals(InteractionResult.FAIL,scarecrow.exchange(player,false));assertTrue(scarecrow.item(1).isEmpty());assertEquals(4,held.get().count());
    }
    @Test void mutatedThenThrowingHandWriteRollsBackAndNestedExchangeIsRejected() {
        doAnswer(call->{
            assertEquals(InteractionResult.FAIL,scarecrow.exchange(player,false));
            Item next=call.getArgument(1);held.set(next);
            if(next.isEmpty())throw new IllegalStateException("after write");return null;
        }).when(player).setItemInHand(eq(InteractionHand.MAIN_HAND),any());
        assertThrows(IllegalStateException.class,()->scarecrow.exchange(player,false));
        assertEquals(1,held.get().count());assertTrue(scarecrow.item(1).isEmpty());
    }
    @Test void protectedStaleAndSpectatorInteractionsCannotConsume() {
        protection.when(()->InteractGuard.canInteract(player,pos)).thenReturn(false);assertEquals(InteractionResult.FAIL,scarecrow.exchange(player,false));
        protection.when(()->InteractGuard.canInteract(player,pos)).thenReturn(true);when(furniture.isValid()).thenReturn(false);
        assertEquals(InteractionResult.FAIL,scarecrow.exchange(player,false));when(furniture.isValid()).thenReturn(true);
        when(actor.getGameMode()).thenReturn(GameMode.SPECTATOR);assertEquals(InteractionResult.FAIL,scarecrow.exchange(player,false));
        verify(player,never()).setItemInHand(any(),any());
    }
    @Test void malformedSaveIsPreservedAndBlocksFurtherEquipmentChanges() {
        CompoundTag bad=new CompoundTag();bad.putString(DATA,"unreadable");
        assertThrows(IllegalArgumentException.class,()->scarecrow.loadCustomData(bad));
        assertEquals(InteractionResult.FAIL,scarecrow.exchange(player,false));
        CompoundTag saved=new CompoundTag();scarecrow.saveCustomData(saved);assertEquals(bad,saved);
    }
    @Test void creativeRemovalDiscardsEquipmentAndRepeatedCallbackDoesNotRecover() {
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL,scarecrow.exchange(player,false));
        when(player.canInstabuild()).thenReturn(true);scarecrow.preRemove(player);scarecrow.postRemove(player);scarecrow.postRemove(player);
        assertTrue(scarecrow.item(1).isEmpty());recovery.verifyNoInteractions();verify(bukkitWorld,never()).dropItemNaturally(any(),any());
    }

    @Test void canceledRemovalKeepsExactEquipmentAndCreatesOneRecoveryRecord() {
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL,scarecrow.exchange(player,false));
        var inventory=keep(mockStatic(InventoryUtils.class));ItemStack expected=mock(ItemStack.class);
        when(expected.clone()).thenReturn(expected);inventory.when(()->InventoryUtils.bukkitCopy(any())).thenReturn(expected);
        var dropped=mock(org.bukkit.entity.Item.class);when(dropped.isValid()).thenReturn(false);
        when(bukkitWorld.dropItemNaturally(any(),any())).thenReturn(dropped);
        scarecrow.preRemove(player);scarecrow.postRemove(player);scarecrow.postRemove(player);
        assertEquals("first",scarecrow.item(1).customNameJson().orElseThrow().getAsString());
        recovery.verify(()->FurnitureRecovery.save(scarecrow,"scarecrow"),times(1));
    }

    @Test void sourceStrikeTimesAreSharedAndProtectedOrAdventureHitsDoNotPrimeWindow() {
        tick=100;protection.when(()->InteractGuard.canBreak(eq(actor),any(org.bukkit.Location.class))).thenReturn(false);
        assertFalse(scarecrow.playerStrike(actor));
        protection.when(()->InteractGuard.canBreak(eq(actor),any(org.bukkit.Location.class))).thenReturn(true);
        tick=101;assertFalse(scarecrow.playerStrike(actor));tick=106;assertTrue(scarecrow.playerStrike(actor));
        tick=107;assertFalse(scarecrow.playerStrike(actor));
        when(actor.getGameMode()).thenReturn(GameMode.ADVENTURE);tick=113;assertFalse(scarecrow.playerStrike(actor));
        when(actor.getGameMode()).thenReturn(GameMode.SURVIVAL);tick=114;assertFalse(scarecrow.playerStrike(actor));
        when(actor.getGameMode()).thenReturn(GameMode.CREATIVE);tick=120;assertTrue(scarecrow.playerStrike(actor));
    }

    @Test void lowPriorityHitCallbackDoesNotReleaseShoulderOrPrimeWindow() {
        tick=100;assertEquals(InteractionResult.PASS,scarecrow.onPlayerHit(player,null));
        tick=101;assertFalse(scarecrow.playerStrike(actor));
    }

    @Test void acceptedDropSuppressionAppliesOnlyToSamePlayerAndTick() {
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL,scarecrow.exchange(player,false));
        var event=mock(net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent.class);
        when(event.getPlayer()).thenReturn(actor);when(event.dropItems()).thenReturn(false);
        tick=100;scarecrow.acceptPlayerBreak(event);scarecrow.preRemove(player);scarecrow.postRemove(player);
        assertTrue(scarecrow.item(1).isEmpty());verify(bukkitWorld,never()).dropItemNaturally(any(),any());
        recovery.verifyNoInteractions();
    }

    @Test void lateCanceledBreakCannotSuppressDropsOnExternalRemovalInSameTick() {
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL,scarecrow.exchange(player,false));
        var event=mock(net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent.class);
        when(event.getPlayer()).thenReturn(actor);when(event.dropItems()).thenReturn(false);
        tick=100;scarecrow.acceptPlayerBreak(event);when(event.isCancelled()).thenReturn(true);
        var inventory=keep(mockStatic(InventoryUtils.class));ItemStack expected=mock(ItemStack.class);
        when(expected.clone()).thenReturn(expected);inventory.when(()->InventoryUtils.bukkitCopy(any())).thenReturn(expected);
        var dropped=mock(org.bukkit.entity.Item.class);when(dropped.isValid()).thenReturn(true);when(dropped.getItemStack()).thenReturn(expected);
        when(bukkitWorld.dropItemNaturally(any(),any())).thenReturn(dropped);
        scarecrow.preRemove(player);scarecrow.postRemove(player);
        verify(bukkitWorld).dropItemNaturally(any(),eq(expected));assertTrue(scarecrow.item(1).isEmpty());
        recovery.verifyNoInteractions();
    }

    @Test void wobbleTickerRunsWithoutParrotsAndReturnsToRest() {
        tick=100;assertFalse(scarecrow.playerStrike(actor));
        var ticker=scarecrow.<ScarecrowController>createFurnitureTicker();assertNotNull(ticker);
        tick=101;ticker.tick(furniture,scarecrow);assertEquals(2.598076f,scarecrow.wobbleAngle(),.00001);
        tick=105;ticker.tick(furniture,scarecrow);assertEquals(0,scarecrow.wobbleAngle());
        scarecrow.onUnload();tick=107;ticker.tick(furniture,scarecrow);assertEquals(0,scarecrow.wobbleAngle());
    }
}
