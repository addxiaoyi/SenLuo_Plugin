package net.kaleidoscope.series;

import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorTemplate;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.tick.FurnitureTicker;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FurnitureTickersTest {
    @Test void anOldWrapperDispatchesOnlyToTheCurrentlyMappedController() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ticker.tick(fixture.previous, fixture.previousController);
            assertEquals(0, fixture.previousController.ticks);
            assertEquals(1, fixture.currentController.ticks);
        }
    }

    @Test void aNormallyMappedSameInstanceStillTicksOnce() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.current.metaDataEntity()).thenReturn(fixture.metadata);
            fixture.ticker.tick(fixture.current, fixture.currentController);
            assertEquals(0, fixture.previousController.ticks);
            assertEquals(1, fixture.currentController.ticks);
        }
    }

    @Test void anUnloadedFurnitureNeverFallsBackToTheOldController() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.api.when(() -> CraftEngineFurniture.getLoadedFurnitureByMetaEntity(fixture.entity)).thenReturn(null);
            fixture.ticker.tick(fixture.previous, fixture.previousController);
            assertEquals(0, fixture.previousController.ticks);
            assertEquals(0, fixture.currentController.ticks);
        }
    }

    @Test void anInvalidCurrentWrapperNeverTicksEitherController() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.current.isValid()).thenReturn(false);
            fixture.ticker.tick(fixture.previous, fixture.previousController);
            assertEquals(0, fixture.previousController.ticks);
            assertEquals(0, fixture.currentController.ticks);
        }
    }

    @Test void aReusedEntityIdCannotTickFurnitureWithAnotherUuid() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.current.uuid()).thenReturn(UUID.randomUUID());
            fixture.ticker.tick(fixture.previous, fixture.previousController);
            assertEquals(0, fixture.previousController.ticks);
            assertEquals(0, fixture.currentController.ticks);
        }
    }

    @Test void aCurrentWrapperWithoutTheRequestedControllerIsSkipped() throws Exception {
        try (var fixture = new Fixture()) {
            configureControllers(fixture.current, new OtherController(fixture.current));
            fixture.ticker.tick(fixture.previous, fixture.previousController);
            assertEquals(0, fixture.previousController.ticks);
            assertEquals(0, fixture.currentController.ticks);
        }
    }

    @Test void aDualControllerCanMoveTheCustomBehaviorFromTheSecondSlotToTheFirst() throws Exception {
        try (var fixture = new Fixture()) {
            configureControllers(fixture.previous, new OtherController(fixture.previous), fixture.previousController);
            configureControllers(fixture.current, fixture.currentController, new OtherController(fixture.current));
            FurnitureTicker<FurnitureController> retained = fixture.previous.controller.createFurnitureTicker();

            retained.tick(fixture.previous, fixture.previous.controller);

            assertEquals(0, fixture.previousController.ticks);
            assertEquals(1, fixture.currentController.ticks);
        }
    }

    @Test void aCompositeControllerDispatchesEachSameTypeOccurrenceExactlyOnceAfterReordering() throws Exception {
        try (var fixture = new Fixture()) {
            var previousSecond = new CountingController(fixture.previous);
            var currentSecond = new CountingController(fixture.current);
            configureControllers(fixture.previous, new OtherController(fixture.previous), fixture.previousController,
                    new OtherController(fixture.previous), previousSecond);
            configureControllers(fixture.current, fixture.currentController, new OtherController(fixture.current),
                    currentSecond, new OtherController(fixture.current));
            FurnitureTicker<FurnitureController> retained = fixture.previous.controller.createFurnitureTicker();

            retained.tick(fixture.previous, fixture.previous.controller);

            assertEquals(0, fixture.previousController.ticks);
            assertEquals(0, previousSecond.ticks);
            assertEquals(1, fixture.currentController.ticks);
            assertEquals(1, currentSecond.ticks);
        }
    }

    @Test void aRemovedSameTypeOccurrenceCannotFallBackAndDoubleTickTheRemainingController() throws Exception {
        try (var fixture = new Fixture()) {
            var removed = new CountingController(fixture.previous);
            configureControllers(fixture.previous, fixture.previousController, removed);
            FurnitureTicker<FurnitureController> retained = fixture.previous.controller.createFurnitureTicker();

            retained.tick(fixture.previous, fixture.previous.controller);

            assertEquals(0, fixture.previousController.ticks);
            assertEquals(0, removed.ticks);
            assertEquals(1, fixture.currentController.ticks);
        }
    }

    @Test void aControllerThatWasNotPartOfThePreviousWrapperCannotDispatch() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ticker.tick(fixture.previous, new CountingController(fixture.previous));

            assertEquals(0, fixture.previousController.ticks);
            assertEquals(0, fixture.currentController.ticks);
        }
    }

    private static final class CountingController extends FurnitureController {
        private int ticks;
        CountingController(Furniture furniture) { super(furniture); }
        void tick() { ticks++; }
        @Override public <T extends FurnitureController> FurnitureTicker<T> createFurnitureTicker() {
            return FurnitureTickers.current(CountingController.class, CountingController::tick);
        }
    }

    private static final class OtherController extends FurnitureController {
        OtherController(Furniture furniture) { super(furniture); }
    }

    private static void configureControllers(Furniture furniture, FurnitureController... controllers) throws Exception {
        var definition = mock(FurnitureDefinition.class);
        var behaviors = new ArrayList<FurnitureBehaviorTemplate>();
        for (var controller : controllers) {
            var behavior = mock(FurnitureBehaviorTemplate.class);
            when(behavior.createController(furniture)).thenReturn(controller);
            behaviors.add(behavior);
        }
        when(definition.behaviors()).thenReturn(behaviors);
        when(furniture.config()).thenReturn(definition);
        setField(furniture, "config", definition);
        setField(furniture, "controller", FurnitureController.createController(furniture));
    }

    private static void setField(Furniture furniture, String name, Object value) throws Exception {
        Field field = Furniture.class.getField(name);
        field.setAccessible(true);
        field.set(furniture, value);
    }

    private static final class Fixture implements AutoCloseable {
        final Furniture previous = mock(Furniture.class);
        final BukkitFurniture current = mock(BukkitFurniture.class);
        final net.momirealms.craftengine.core.entity.Entity metadata = mock(net.momirealms.craftengine.core.entity.Entity.class);
        final Entity entity = mock(Entity.class);
        final CountingController previousController = new CountingController(previous);
        final CountingController currentController = new CountingController(current);
        final MockedStatic<CraftEngineFurniture> api = mockStatic(CraftEngineFurniture.class);
        final FurnitureTicker<FurnitureController> ticker = FurnitureTickers.current(CountingController.class, CountingController::tick);

        Fixture() throws Exception {
            UUID uuid = UUID.randomUUID();
            when(previous.uuid()).thenReturn(uuid);
            when(current.uuid()).thenReturn(uuid);
            when(current.isValid()).thenReturn(true);
            when(previous.metaDataEntity()).thenReturn(metadata);
            when(metadata.platformEntity()).thenReturn(entity);
            configureControllers(previous, previousController);
            configureControllers(current, currentController);
            api.when(() -> CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity)).thenReturn(current);
        }

        @Override public void close() { api.close(); }
    }
}
