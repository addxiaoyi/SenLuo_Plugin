package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.EightImmortalsTableBehavior;
import net.kaleidoscope.cookery.item.behavior.EightImmortalsTableItemBehavior;
import net.kaleidoscope.cookery.util.FoliaUtil;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.*;
import net.momirealms.craftengine.core.block.entity.*;
import net.momirealms.craftengine.core.util.*;
import net.momirealms.craftengine.core.world.*;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EightImmortalsTableControllerTest {
    private final BlockPos anchor = new BlockPos(8,64,8);
    private final org.bukkit.World world = mock(org.bukkit.World.class);
    private final World level = mock(World.class);
    private final CEWorld storage = mock(CEWorld.class);
    private final EightImmortalsTableBehavior behavior = mock(EightImmortalsTableBehavior.class);
    private final Map<BlockPos, BlockEntity> entities = new HashMap<>();
    private final Map<String, ImmutableBlockState> states = new HashMap<>();
    private final List<BlockPos> cleared = new ArrayList<>();
    private final List<Runnable> queued = new ArrayList<>();
    private final UUID group = UUID.randomUUID();
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<CraftEngineBlocks> blocks;
    private MockedStatic<FoliaUtil> scheduler;
    private MockedStatic<EightImmortalsTableItemBehavior> placement;
    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class); blocks = mockStatic(CraftEngineBlocks.class); scheduler = mockStatic(FoliaUtil.class); placement = mockStatic(EightImmortalsTableItemBehavior.class);
        when(storage.world()).thenReturn(level); when(level.platformWorld()).thenReturn(world);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        bukkit.when(()->Bukkit.isOwnedByCurrentRegion(eq(world),anyInt(),anyInt())).thenReturn(true);
        when(storage.getBlockEntityAtIfLoaded(any())).thenAnswer(c->entities.get(c.getArgument(0)));
        var definition = mock(BlockDefinition.class); when(definition.id()).thenReturn(Key.of("kaleidoscopecookery:eight_immortals_table")); when(behavior.block()).thenReturn(definition);
        when(behavior.facing(any())).thenReturn(Direction.NORTH); when(behavior.anchor(any(),any())).thenReturn(anchor);
        when(behavior.createBlockEntityController(any())).thenAnswer(c->new EightImmortalsTableController(c.getArgument(0),behavior));
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(c->{
            var block=mock(Block.class);when(block.getX()).thenReturn(c.getArgument(0));when(block.getY()).thenReturn(c.getArgument(1));when(block.getZ()).thenReturn(c.getArgument(2));return block;
        });
        blocks.when(()->CraftEngineBlocks.remove(any(Block.class),eq(false))).thenAnswer(c->{
            Block block=c.getArgument(0);var pos=new BlockPos(block.getX(),block.getY(),block.getZ());
            cleared.add(pos);var old=entities.remove(pos);if(old!=null)old.controller.onRemove();return true;
        });
        scheduler.when(()->FoliaUtil.run(any(Runnable.class),eq(level),anyInt(),anyInt())).thenAnswer(c->{queued.add(c.getArgument(0));return null;});
        for(String part:EightImmortalsTableBehavior.PARTS){
            var state=mock(ImmutableBlockState.class);when(state.behavior()).thenReturn(behavior);when(behavior.part(state)).thenReturn(part);states.put(part,state);
            add(part,group);
        }
    }
    @AfterEach void close(){placement.close();scheduler.close();blocks.close();bukkit.close();}
    private BlockPos pos(String part){return EightImmortalsTableBehavior.cell(anchor,Direction.NORTH,part);}
    private EightImmortalsTableController add(String part,UUID group){
        var entity=new BlockEntity(pos(part),states.get(part));entity.world=storage;entity.setValid(true);entities.put(entity.pos,entity);
        var controller=entity.controller.get(EightImmortalsTableController.class,0);controller.assemble(group);return controller;
    }
    private EightImmortalsTableController controller(String part){return entities.get(pos(part)).controller.get(EightImmortalsTableController.class,0);}
    private void remove(String part){var old=entities.remove(pos(part));old.controller.onRemove();}
    @Test void removingAnyPartClearsOtherThreeWithoutAdditionalLootAndReentry(){
        remove("left_top");assertTrue(entities.isEmpty());assertEquals(3,cleared.size());assertEquals(3,new HashSet<>(cleared).size());
    }
    @Test void anotherGenerationAtSamePositionIsPreserved(){
        add("left_bottom",UUID.randomUUID());remove("right_top");assertEquals(Set.of(pos("left_bottom")),entities.keySet());assertEquals(2,cleared.size());
    }
    @Test void changedFacingAndPartDoNotMatchTheRemovedTable(){
        when(behavior.facing(states.get("left_top"))).thenReturn(Direction.SOUTH);
        when(behavior.part(states.get("left_bottom"))).thenReturn("right_top");
        remove("right_bottom");assertEquals(Set.of(pos("left_top"),pos("left_bottom")),entities.keySet());
    }
    @Test void rollbackRemovalDoesNotCascadeToOtherCells(){
        placement.when(()->EightImmortalsTableItemBehavior.placing(any())).thenReturn(true);
        remove("right_bottom");assertEquals(3,entities.size());assertTrue(cleared.isEmpty());
    }
    @Test void deferredRegionCleanupRechecksGeneration(){
        var old=entities.get(anchor);
        bukkit.when(()->Bukkit.isOwnedByCurrentRegion(eq(world),anyInt(),anyInt())).thenReturn(false);
        remove("right_bottom");assertEquals(3,queued.size());assertTrue(cleared.isEmpty());
        old.world=null;
        bukkit.when(()->Bukkit.isOwnedByCurrentRegion(eq(world),anyInt(),anyInt())).thenReturn(true);
        add("left_bottom",UUID.randomUUID());queued.forEach(Runnable::run);
        assertEquals(Set.of(pos("left_bottom")),entities.keySet());
    }
    @Test void unloadedNeighborIsNotReadAndReconcilesWhenAllChunksReturn(){
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(false);remove("right_bottom");queued.forEach(Runnable::run);assertEquals(3,entities.size());
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        var remaining=entities.values().stream().map(e->e.controller.get(EightImmortalsTableController.class,0)).toList();remaining.forEach(EightImmortalsTableController::reconcile);
        assertTrue(entities.isEmpty());
    }
    @Test void completeTableIsNotRemovedAndFailedRemovalCanRetry(){
        controller("right_bottom").reconcile();assertTrue(cleared.isEmpty());
        blocks.when(()->CraftEngineBlocks.remove(any(Block.class),eq(false))).thenReturn(true);
        var member=controller("right_top");member.removeWithoutDrop();assertFalse(member.removed());assertEquals(4,entities.size());
    }
    @Test void nativeRemovalMayDetachTheEntityBeforeReturning(){
        var member=controller("right_top");
        blocks.when(()->CraftEngineBlocks.remove(any(Block.class),eq(false))).thenAnswer(c->{
            entities.remove(member.blockEntity().pos);member.blockEntity().world=null;return true;
        });
        assertDoesNotThrow(member::removeWithoutDrop);assertTrue(member.removed());assertEquals(3,entities.size());
        assertDoesNotThrow(member::removeWithoutDrop);
    }
    @Test void groupPersistsAndUnsupportedDataFails(){
        var member=controller("left_top");var saved=new CompoundTag();member.saveCustomData(saved);
        var restored=add("left_top",UUID.randomUUID());restored.loadCustomData(saved);assertEquals(group,restored.group());
        var invalid=new CompoundTag();var data=new CompoundTag();data.putInt("schema",2);invalid.put("kaleidoscopecookery:eight_immortals_table",data);
        assertThrows(IllegalArgumentException.class,()->restored.loadCustomData(invalid));
        var preserved=new CompoundTag();restored.saveCustomData(preserved);assertEquals(invalid,preserved);
        assertThrows(IllegalStateException.class,()->restored.assemble(UUID.randomUUID()));
    }
}
