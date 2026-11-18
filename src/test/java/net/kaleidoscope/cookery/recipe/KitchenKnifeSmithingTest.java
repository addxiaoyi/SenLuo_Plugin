package net.kaleidoscope.cookery.recipe;

import net.kaleidoscope.cookery.item.ItemKeys;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.recipe.*;
import net.momirealms.craftengine.core.item.recipe.input.SmithingInput;
import net.momirealms.craftengine.core.item.recipe.result.CustomRecipeResult;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.nbt.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KitchenKnifeSmithingTest {
    private final Item base = mock(Item.class), target = mock(Item.class), output = mock(Item.class);
    private final KitchenKnifeSmithing processor = new KitchenKnifeSmithing();
    private final CompoundTag source = new CompoundTag(), destination = new CompoundTag();
    @BeforeEach void setup() {
        when(base.id()).thenReturn(ItemKeys.DIAMOND_KITCHEN_KNIFE);
        when(target.id()).thenReturn(ItemKeys.NETHERITE_KITCHEN_KNIFE); when(output.id()).thenReturn(ItemKeys.NETHERITE_KITCHEN_KNIFE);
        when(target.count()).thenReturn(1); when(output.count()).thenReturn(1);
        when(base.getComponentAsSparrowTag(KitchenKnifeSmithing.CUSTOM_DATA)).thenReturn(source);
        when(target.getComponentAsSparrowTag(KitchenKnifeSmithing.CUSTOM_DATA)).thenReturn(destination);
        source.putString("craftengine:id",ItemKeys.DIAMOND_KITCHEN_KNIFE.asString());
        destination.putString("craftengine:id",ItemKeys.NETHERITE_KITCHEN_KNIFE.asString());
    }
    private CompoundTag result() {
        var capture=ArgumentCaptor.forClass(Tag.class);
        verify(output).setSparrowTagComponent(eq(KitchenKnifeSmithing.CUSTOM_DATA),capture.capture());
        return (CompoundTag)capture.getValue();
    }
    @Test void upgradePreservesNestedUserValuesAndTargetIdentityWithoutMutatingInputs() {
        var originalPdc=new CompoundTag();originalPdc.putString("other:owner","Alice");originalPdc.putInt("other:level",7);
        source.put("PublicBukkitValues",originalPdc); source.putIntArray("opaque",new int[]{2,4,8});
        source.putString("craftengine:old_argument","diamond");
        var newPdc=new CompoundTag();newPdc.putInt("other:level",1);newPdc.putBoolean("other:new",true);destination.put("PublicBukkitValues",newPdc);
        var beforeSource=source.deepClone();var beforeTarget=destination.deepClone();
        processor.accept(base,target,output);var merged=result();
        assertEquals("Alice",merged.getCompound("PublicBukkitValues").getString("other:owner"));
        assertEquals(7,merged.getCompound("PublicBukkitValues").getInt("other:level"));
        assertTrue(merged.getCompound("PublicBukkitValues").getBoolean("other:new"));
        assertEquals(ItemKeys.NETHERITE_KITCHEN_KNIFE.asString(),merged.getString("craftengine:id"));
        assertFalse(merged.containsKey("craftengine:old_argument"));
        assertArrayEquals(new int[]{2,4,8},merged.getIntArray("opaque"));
        verify(output).customId(ItemKeys.NETHERITE_KITCHEN_KNIFE);
        merged.getCompound("PublicBukkitValues").putInt("other:level",99);merged.getIntArray("opaque")[0]=100;
        assertEquals(beforeSource,source);assertEquals(beforeTarget,destination);
    }
    @Test void nestedCraftengineNamedUserKeyIsPreservedAndBaseTypeWins() {
        var nested=new CompoundTag();nested.putString("craftengine:user_note","retained");source.put("custom",nested);
        var targetNested=new CompoundTag();targetNested.putString("fallback","retained");destination.put("custom",targetNested);
        source.putString("changed_type","source");destination.put("changed_type",new CompoundTag());
        processor.accept(base,target,output);var merged=result();
        assertEquals("retained",merged.getCompound("custom").getString("craftengine:user_note"));
        assertEquals("retained",merged.getCompound("custom").getString("fallback"));
        assertEquals("source",merged.getString("changed_type"));
    }
    @Test void missingSourceDataRetainsTargetDefaultsAndAlwaysRestoresIdentity() {
        when(base.getComponentAsSparrowTag(any())).thenReturn(null);
        processor.accept(base,target,output);assertEquals(destination,result());verify(output).customId(ItemKeys.NETHERITE_KITCHEN_KNIFE);
    }
    @Test void malformedCustomDataFailsBeforeOutputMutation() {
        source.putString("bad","value");when(base.getComponentAsSparrowTag(any())).thenReturn(source.get("bad"));
        assertThrows(IllegalArgumentException.class,()->processor.accept(base,target,output));
        verify(output,never()).setSparrowTagComponent(any(),any());verify(output,never()).customId(any());
    }
    @Test void wrongSourceTargetOrCountCannotProduceAnUpgrade() {
        when(base.id()).thenReturn(Key.of("minecraft:diamond_sword"));assertThrows(IllegalArgumentException.class,()->processor.accept(base,target,output));
        when(base.id()).thenReturn(ItemKeys.DIAMOND_KITCHEN_KNIFE);when(output.id()).thenReturn(ItemKeys.DIAMOND_KITCHEN_KNIFE);
        assertThrows(IllegalArgumentException.class,()->processor.accept(base,target,output));
        when(output.id()).thenReturn(ItemKeys.NETHERITE_KITCHEN_KNIFE);when(output.count()).thenReturn(2);
        assertThrows(IllegalArgumentException.class,()->processor.accept(base,target,output));
        verify(output,never()).setSparrowTagComponent(any(),any());
    }
    @Test void sharedOrEmptyOutputIsRejectedBeforeMutation() {
        assertThrows(IllegalArgumentException.class,()->processor.accept(base,target,target));
        assertThrows(IllegalArgumentException.class,()->processor.accept(base,target,base));
        when(output.isEmpty()).thenReturn(true);assertThrows(IllegalArgumentException.class,()->processor.accept(base,target,output));
        verify(base,never()).setSparrowTagComponent(any(),any());verify(target,never()).setSparrowTagComponent(any(),any());
    }
    @Test void configOnlyAcceptsThePinnedProcessor() {
        assertNotNull(KitchenKnifeSmithing.FACTORY.create(ConfigSection.ofRoot(Map.of("type",KitchenKnifeSmithing.TYPE.asString()))));
        assertThrows(IllegalArgumentException.class,()->KitchenKnifeSmithing.FACTORY.create(ConfigSection.ofRoot(Map.of("type","wrong"))));
        assertThrows(IllegalArgumentException.class,()->KitchenKnifeSmithing.FACTORY.create(ConfigSection.ofRoot(Map.of("type",KitchenKnifeSmithing.TYPE.asString(),"unknown",true))));
    }
    @Test void realCeAssemblyMergesBeforeProcessingAndDoesNotDebitAnyInput() {
        var templateRole=mock(Ingredient.class);var baseRole=mock(Ingredient.class);var additionRole=mock(Ingredient.class);
        var template=mock(UniqueIdItem.class);var wrappedBase=mock(UniqueIdItem.class);var addition=mock(UniqueIdItem.class);
        when(wrappedBase.item()).thenReturn(base);
        when(templateRole.test(template)).thenReturn(true);when(baseRole.test(wrappedBase)).thenReturn(true);when(additionRole.test(addition)).thenReturn(true);
        var builder=mock(CustomRecipeResult.class);var context=mock(ItemBuildContext.class);when(builder.buildItem(context)).thenReturn(target);
        when(base.mergeCopy(target)).thenReturn(output);source.putString("owner","Alice");
        var recipe=new CustomSmithingTransformRecipe(Key.of("test:knife"),true,templateRole,baseRole,additionRole,builder,null,List.of(processor),true,null,null,false);
        var input=new SmithingInput(wrappedBase,template,addition);
        assertTrue(recipe.matches(input));assertTrue(recipe.mergeComponents());assertFalse(recipe.ingredientCountSupport());
        assertSame(output,recipe.assemble(input,context));assertEquals("Alice",result().getString("owner"));
        verify(base).mergeCopy(target);verify(base,never()).count(anyInt());verify(target,never()).count(anyInt());
        when(template.isEmpty()).thenReturn(true);assertFalse(recipe.matches(input));
        when(template.isEmpty()).thenReturn(false);when(baseRole.test(wrappedBase)).thenReturn(false);assertFalse(recipe.matches(input));
        when(baseRole.test(wrappedBase)).thenReturn(true);when(additionRole.test(addition)).thenReturn(false);assertFalse(recipe.matches(input));
    }
}
