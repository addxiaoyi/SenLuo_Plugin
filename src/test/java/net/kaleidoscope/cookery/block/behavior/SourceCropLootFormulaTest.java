package net.kaleidoscope.cookery.block.behavior;

import net.momirealms.craftengine.core.loot.function.formula.Formula;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceCropLootFormulaTest {
    private static Formula actualCropDrops(float probability) throws ReflectiveOperationException {
        // The API artifact exposes Formula; the pinned CE runtime JAR supplies CropDrops.
        // Invoke that implementation rather than reproducing its probability loop here.
        var type = Class.forName("net.momirealms.craftengine.core.loot.function.formula.CropDrops");
        var constructor = type.getDeclaredConstructor(int.class, float.class);
        constructor.setAccessible(true);
        return (Formula) constructor.newInstance(3, probability);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void zeroProbabilityPreservesTheInitialCount(int fortuneLevel) throws ReflectiveOperationException {
        var formula = actualCropDrops(0.0f);
        assertEquals(1, formula.apply(1, fortuneLevel));
        assertEquals(5, formula.apply(5, fortuneLevel));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void certainTrialsAddFortuneLevelAndThreeExtraTrials(int fortuneLevel) throws ReflectiveOperationException {
        var formula = actualCropDrops(1.0f);
        assertEquals(4 + fortuneLevel, formula.apply(1, fortuneLevel));
        assertEquals(8 + fortuneLevel, formula.apply(5, fortuneLevel));
    }
}
