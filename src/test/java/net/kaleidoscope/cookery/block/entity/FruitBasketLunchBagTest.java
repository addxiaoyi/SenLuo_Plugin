package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.FruitBasketBehavior;
import net.kaleidoscope.cookery.block.entity.render.TrackedPlayers;
import net.kaleidoscope.cookery.item.LunchBagContents;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.item.Item;
import org.junit.jupiter.api.*;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FruitBasketLunchBagTest {
    private final Item empty = mock(Item.class);
    private final Item bag = mock(Item.class);
    private final Item staged = mock(Item.class);
    private final List<Item> bagItems = new ArrayList<>();
    private MockedStatic<Item> itemFactory;
    private MockedStatic<LunchBagContents> storage;
    private MockedStatic<TrackedPlayers> tracking;
    private MockedConstruction<FruitBasketElement> displays;
    private FruitBasketController basket;
    private List<Item> written;

    private Item item(String identity, int count) {
        Item result = mock(Item.class, identity);
        when(result.count()).thenReturn(count);
        when(result.maxStackSize()).thenReturn(64);
        when(result.isEmpty()).thenReturn(count == 0);
        when(result.isSimilar(any())).thenAnswer(call -> identity.equals(mockingDetails(call.getArgument(0)).getMockCreationSettings().getMockName().toString()));
        when(result.copy()).thenAnswer(call -> item(identity, count));
        when(result.copyWithCount(anyInt())).thenAnswer(call -> item(identity, call.getArgument(0)));
        return result;
    }

    @BeforeEach void setup() {
        when(empty.isEmpty()).thenReturn(true);
        when(bag.copy()).thenReturn(staged);
        itemFactory = mockStatic(Item.class); itemFactory.when(Item::empty).thenReturn(empty);
        displays = mockConstruction(FruitBasketElement.class);
        tracking = mockStatic(TrackedPlayers.class);
        storage = mockStatic(LunchBagContents.class);
        storage.when(() -> LunchBagContents.read(bag)).thenAnswer(call -> new ArrayList<>(bagItems));
        storage.when(() -> LunchBagContents.canAdd(any())).thenReturn(true);
        storage.when(() -> LunchBagContents.write(eq(staged), anyList())).thenAnswer(call -> {
            written = List.copyOf(call.<List<Item>>getArgument(1)); return null;
        });
        basket = new FruitBasketController(mock(BlockEntity.class), mock(FruitBasketBehavior.class));
    }

    @AfterEach void close() { storage.close(); tracking.close(); displays.close(); itemFactory.close(); }

    @Test void emptyBasketTakesEightStacksAndBagKeepsTheOtherSixteen() {
        for (int i = 0; i < 24; i++) bagItems.add(item("food-" + i, 64));
        assertSame(staged, basket.exchangeLunchBag(bag));
        assertEquals(16, written.size());
        assertEquals(512, Arrays.stream(basket.slotItems()).mapToInt(Item::count).sum());
        assertEquals(1024, written.stream().mapToInt(Item::count).sum());
        assertEquals(24, bagItems.size());
    }

    @Test void fullBagOnlyAcceptsMatchingComponentsWithAvailableRoom() {
        for (int i = 0; i < 24; i++) bagItems.add(item("food-" + i, i == 0 ? 60 : 64));
        basket.slotItems()[0] = item("food-0", 10);
        basket.slotItems()[1] = item("food-0-premium", 7);
        assertSame(staged, basket.exchangeLunchBag(bag));
        assertEquals(64, written.getFirst().count());
        assertEquals(6, basket.slotItems()[0].count());
        assertEquals(7, basket.slotItems()[1].count());
    }

    @Test void serializationFailureCannotConsumeBasketItems() {
        Item food = item("food", 6);
        basket.slotItems()[0] = food;
        storage.when(() -> LunchBagContents.write(eq(staged), anyList())).thenThrow(new IllegalArgumentException("bad serialization"));
        assertThrows(IllegalArgumentException.class, () -> basket.exchangeLunchBag(bag));
        assertSame(food, basket.slotItems()[0]);
        assertEquals(6, food.count());
    }
}
