package net.kaleidoscope.cookery.recipe.edit;

import net.kaleidoscope.cookery.recipe.StockpotRecipe;
import net.kaleidoscope.cookery.recipe.StockpotVisuals;
import net.momirealms.craftengine.core.util.Key;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Mutable editor state for an imported stockpot recipe. */
public final class StockpotRecipeDraft {
    private final StockpotRecipe original;
    private Key id;
    private StockpotRecipe.Mode mode;
    private List<Set<Key>> ingredients;
    private Key soupBase, result, carrier;
    private int resultCount, time;
    private StockpotVisuals visuals;

    private StockpotRecipeDraft(Key id, StockpotRecipe original) {
        this.id = id; this.original = original;
        this.mode = StockpotRecipe.Mode.EXACT;
        this.ingredients = new ArrayList<>();
        this.resultCount = 1; this.time = 200;
        this.visuals = new StockpotVisuals(Key.of("minecraft:air"), Key.of("minecraft:air"), 0xFFFFFF, 0xFFFFFF);
    }
    public static StockpotRecipeDraft creating(Key id) { return new StockpotRecipeDraft(id, null); }
    public static StockpotRecipeDraft editing(StockpotRecipe value) {
        StockpotRecipeDraft d = new StockpotRecipeDraft(value.id(), value);
        d.mode=value.mode(); d.ingredients=new ArrayList<>(value.ingredients().stream().map(LinkedHashSet::new).toList());
        d.soupBase=value.soupBase(); d.result=value.result(); d.carrier=value.carrier(); d.resultCount=value.resultCount(); d.time=value.time(); d.visuals=value.visuals();
        return d;
    }
    public boolean isNew(){ return original==null; } public Key originalId(){ return original==null?null:original.id(); }
    public StockpotRecipe originalRecipe(){ return original; } public Key id(){return id;} public void id(Key v){id=v;}
    public StockpotRecipe.Mode mode(){return mode;} public void mode(StockpotRecipe.Mode v){mode=v;}
    public List<Set<Key>> ingredients(){return List.copyOf(ingredients);} public void ingredients(List<Set<Key>> v){ingredients=new ArrayList<>(v.stream().map(Set::copyOf).toList());}
    public Key soupBase(){return soupBase;} public void soupBase(Key v){soupBase=v;} public Key result(){return result;} public void result(Key v){result=v;}
    public Key carrier(){return carrier;} public void carrier(Key v){carrier=v;} public int resultCount(){return resultCount;} public void resultCount(int v){resultCount=v;} public int time(){return time;} public void time(int v){time=v;}
    public StockpotVisuals visuals(){return visuals;} public void visuals(StockpotVisuals v){visuals=v;}
    public StockpotRecipe toRecipe(){return new StockpotRecipe(id, mode, ingredients, soupBase, result, resultCount, time, carrier, visuals);}
}
