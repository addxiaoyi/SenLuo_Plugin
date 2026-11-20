package net.kaleidoscope.cookery.recipe;

import net.kaleidoscope.cookery.item.SteamerItemContents;
import net.kaleidoscope.cookery.item.SteamerItemContentsTest;
import net.kaleidoscope.cookery.util.BlockEntityNbt;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.core.item.*;
import net.momirealms.craftengine.core.item.recipe.result.*;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FilledSteamerResultTest {
    private static final Key RAW=Key.of("kaleidoscopecookery:raw_dough");
    @Test void exactConfigAllowsBothSourceRecipesAndRejectsUnknownData(){
        for(String name:new String[]{"raw_dough","stuffed_dough_food"})
            assertEquals(Key.of("kaleidoscopecookery:"+name),FilledSteamerResult.fromConfig(ConfigSection.ofRoot(
                Map.of("type",FilledSteamerResult.TYPE.asString(),"ingredient","kaleidoscopecookery:"+name))).ingredient());
        for(var data:new Map[]{Map.of("type","wrong","ingredient",RAW.asString()),
                Map.of("type",FilledSteamerResult.TYPE.asString()),
                Map.of("type",FilledSteamerResult.TYPE.asString(),"ingredient",RAW.asString(),"extra",true),
                Map.of("type",FilledSteamerResult.TYPE.asString(),"ingredient","minecraft:stone")})
            assertThrows(IllegalArgumentException.class,()->FilledSteamerResult.fromConfig(ConfigSection.ofRoot(data)));
    }
    @Test void realCeResultPipelineReturnsDetachedFilledItemAndLeavesTemplateUntouched(){
        Item empty=SteamerItemContentsTest.item("minecraft:air",0),base=SteamerItemContentsTest.item(SteamerItemContents.ID.asString(),1);
        try(var items=mockStatic(Item.class);var nbt=mockStatic(BlockEntityNbt.class);var inv=mockStatic(InventoryUtils.class)){
            items.when(Item::empty).thenReturn(empty);SteamerItemContentsTest.codec(nbt);
            inv.when(()->InventoryUtils.createOrEmpty(RAW)).thenAnswer(c->SteamerItemContentsTest.item(RAW.asString(),1));
            var builder=mock(BuildableItem.class);var context=mock(ItemBuildContext.class);
            when(builder.buildItem(context,1)).thenReturn(base);
            var recipe=new CustomRecipeResult(builder,1,new PostProcessor[]{new FilledSteamerResult(RAW)});
            var output=recipe.buildItem(context);assertNotSame(base,output);
            var contents=SteamerItemContents.read(output);assertEquals(4,contents.size());
            for(var slot:contents){assertEquals(RAW,slot.item().id());assertEquals(1,slot.item().count());assertEquals(1200,slot.time());}
            assertNull(base.getComponentAsSparrowTag(SteamerItemContents.CUSTOM_DATA));assertEquals(1,base.count());
        }
    }
    @Test void missingIngredientFailsBeforeOutputMutation(){
        Item base=SteamerItemContentsTest.item(SteamerItemContents.ID.asString(),1);
        try(var inv=mockStatic(InventoryUtils.class)){
            inv.when(()->InventoryUtils.createOrEmpty(RAW)).thenReturn(null);
            assertThrows(IllegalStateException.class,()->new FilledSteamerResult(RAW).process(base,null));
            verify(base,never()).setSparrowTagComponent(any(),any());
        }
    }
}
