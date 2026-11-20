package net.kaleidoscope.cookery.worldgen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VillagePoolPlanTest {
    private record Element(List<String> locations, boolean canonical) {}
    private Element unrelated(String id) { return new Element(List.of(id), false); }
    private Element kitchen(String location) { return new Element(List.of(location), true); }
    private VillagePoolPlan.Inspection inspect(Element element) { return new VillagePoolPlan.Inspection(element.locations(), element.canonical()); }

    private Map<String, VillagePoolPlan.Effective<Element, Object>> effective() {
        var result = new LinkedHashMap<String, VillagePoolPlan.Effective<Element, Object>>();
        for (String key : VillagePoolPlan.POOL_KEYS) {
            Element shared = unrelated("otherpack:shared_house");
            result.put(key, new VillagePoolPlan.Effective<>(new Object(), List.of(
                    new VillagePoolPlan.Entry<>(unrelated("minecraft:ordinary_house"), 2),
                    new VillagePoolPlan.Entry<>(shared, 17), new VillagePoolPlan.Entry<>(shared, 0),
                    new VillagePoolPlan.Entry<>(unrelated("thirdparty:nested_or_opaque"), 151))));
        }
        return result;
    }

    private VillagePoolPlan<Element, Object> prepare(Map<String, VillagePoolPlan.Effective<Element, Object>> pools) {
        return VillagePoolPlan.prepare(pools, this::inspect, (key, location) -> kitchen(location));
    }

    @Test void allFiveEffectivePoolsKeepEveryExistingObjectOrderWeightAndFallbackAndAppendOneWeightFourKitchen() {
        var effective = effective();
        var plan = prepare(effective);
        assertEquals(VillagePoolPlan.POOL_KEYS, new ArrayList<>(plan.pools().keySet()));
        for (var pool : plan.pools().values()) {
            var before = effective.get(pool.key());
            assertSame(before, pool.before()); assertSame(before.fallback(), pool.fallback());
            assertTrue(pool.appended()); assertEquals(before.entries().size() + 1, pool.entries().size());
            for (int index = 0; index < before.entries().size(); index++) {
                assertSame(before.entries().get(index), pool.entries().get(index));
                assertSame(before.entries().get(index).element(), pool.entries().get(index).element());
                assertEquals(before.entries().get(index).weight(), pool.entries().get(index).weight());
            }
            var added = pool.entries().getLast();
            assertEquals(4, added.weight()); assertEquals(List.of(pool.location()), added.element().locations());
            assertEquals(174, pool.totalWeight());
        }
        assertEquals("kaleidoscopecookery:crop_replace", VillagePoolPlan.PROCESSOR);
    }

    @Test void aSecondPreparationReusesTheExactKitchenElementAndAllocatesNothing() {
        var first = prepare(effective());
        var merged = new LinkedHashMap<String, VillagePoolPlan.Effective<Element, Object>>();
        first.pools().forEach((key, pool) -> merged.put(key, new VillagePoolPlan.Effective<>(pool.fallback(), pool.entries())));
        var second = VillagePoolPlan.prepare(merged, this::inspect, (key, location) -> fail("Idempotence must not allocate an element"));
        for (String key : VillagePoolPlan.POOL_KEYS) {
            var before = first.pools().get(key); var after = second.pools().get(key);
            assertFalse(after.appended()); assertSame(before.fallback(), after.fallback());
            assertEquals(before.entries().size(), after.entries().size());
            for (int index = 0; index < before.entries().size(); index++) assertSame(before.entries().get(index), after.entries().get(index));
        }
    }

    @Test void exactKitchenInAnExistingNonFinalPositionStaysInThatPosition() {
        var pools = effective(); String key = VillagePoolPlan.POOL_KEYS.getFirst();
        var original = pools.get(key); var entries = new ArrayList<>(original.entries());
        var kitchen = new VillagePoolPlan.Entry<>(kitchen(VillagePoolPlan.location("plains")), 4);
        entries.add(1, kitchen); pools.put(key, new VillagePoolPlan.Effective<>(original.fallback(), entries));
        var plan = prepare(pools);
        assertFalse(plan.pools().get(key).appended()); assertSame(kitchen, plan.pools().get(key).entries().get(1));
        assertEquals(4, plan.pools().values().stream().filter(VillagePoolPlan.Pool::appended).count());
    }

    @Test void wrongWeightProjectionProcessorTypeNestedLocationOtherBiomeAndSourceAliasAllFailClosed() {
        String location = VillagePoolPlan.location("taiga");
        List<VillagePoolPlan.Entry<Element>> conflicts = List.of(
                new VillagePoolPlan.Entry<>(kitchen(location), 3),
                new VillagePoolPlan.Entry<>(new Element(List.of(location), false), 4),
                new VillagePoolPlan.Entry<>(new Element(List.of(location, "otherpack:extra_house"), true), 4),
                new VillagePoolPlan.Entry<>(kitchen(VillagePoolPlan.location("plains")), 4),
                new VillagePoolPlan.Entry<>(kitchen("kaleidoscope_cookery:village/houses/taiga_kitchen"), 4));
        for (var conflict : conflicts) {
            var pools = effective(); String key = VillagePoolPlan.POOL_KEYS.getLast(); var before = pools.get(key);
            var entries = new ArrayList<>(before.entries()); entries.add(conflict);
            pools.put(key, new VillagePoolPlan.Effective<>(before.fallback(), entries));
            var allocations = new AtomicInteger();
            assertThrows(IllegalArgumentException.class, () -> VillagePoolPlan.prepare(pools, this::inspect, (pool, target) -> {
                allocations.incrementAndGet(); return kitchen(target);
            }));
            assertEquals(0, allocations.get(), "Every pool must be inspected before any additions");
            assertEquals(4, before.entries().size()); assertEquals(5, entries.size());
        }
    }

    @Test void twoExactEntriesAndRepeatedLocationsInsideOneElementAreBothDuplicates() {
        for (boolean nested : List.of(false, true)) {
            var pools = effective(); String key = VillagePoolPlan.POOL_KEYS.getLast(); var before = pools.get(key);
            String location = VillagePoolPlan.location("taiga"); var entries = new ArrayList<>(before.entries());
            if (nested) entries.add(new VillagePoolPlan.Entry<>(new Element(List.of(location, location), true), 4));
            else {
                var same = new VillagePoolPlan.Entry<>(kitchen(location), 4);
                entries.add(same); entries.add(same);
            }
            pools.put(key, new VillagePoolPlan.Effective<>(before.fallback(), entries));
            assertThrows(IllegalArgumentException.class, () -> prepare(pools));
        }
    }

    @Test void defensiveLedgersRetainOpaqueElementsButCannotBeChangedThroughCollections() {
        var mutable = new ArrayList<VillagePoolPlan.Entry<Element>>();
        var opaque = new VillagePoolPlan.Entry<>(new Element(List.of(), false), 8); mutable.add(opaque);
        var source = new VillagePoolPlan.Effective<>(new Object(), mutable); mutable.clear();
        assertSame(opaque, source.entries().getFirst());
        var pools = effective(); pools.put(VillagePoolPlan.POOL_KEYS.getFirst(), source);
        var plan = prepare(pools); pools.clear();
        assertEquals(5, plan.pools().size());
        assertSame(opaque, plan.pools().get(VillagePoolPlan.POOL_KEYS.getFirst()).entries().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> source.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.pools().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.pools().values().iterator().next().entries().clear());
        var names = new ArrayList<>(List.of("otherpack:house"));
        var inspection = new VillagePoolPlan.Inspection(names, false); names.clear();
        assertEquals(List.of("otherpack:house"), inspection.locations());
        assertThrows(UnsupportedOperationException.class, () -> inspection.locations().clear());
    }

    @Test void missingExtraUnknownAndInvalidFactoryInputsCannotProduceAPartialPlan() {
        var missing = effective(); missing.remove(VillagePoolPlan.POOL_KEYS.getLast());
        assertThrows(IllegalArgumentException.class, () -> prepare(missing));
        var extra = effective(); extra.put("minecraft:other", extra.values().iterator().next());
        assertThrows(IllegalArgumentException.class, () -> prepare(extra));
        assertThrows(IllegalArgumentException.class, () -> VillagePoolPlan.location("jungle"));
        assertThrows(IllegalArgumentException.class, () -> new VillagePoolPlan.Entry<>(unrelated("otherpack:house"), -1));
        assertThrows(IllegalArgumentException.class, () -> new VillagePoolPlan.Effective<>(new Object(),
                List.of(new VillagePoolPlan.Entry<>(unrelated("otherpack:house"), Integer.MAX_VALUE))));
        assertThrows(IllegalArgumentException.class, () -> VillagePoolPlan.prepare(effective(), this::inspect,
                (key, location) -> unrelated("otherpack:wrong")));
        assertThrows(UnsupportedOperationException.class, () -> VillagePoolPreparer.forMinecraft("1.21.10"));
        assertThrows(UnsupportedOperationException.class, () -> VillagePoolPreparer.forMinecraft(null));
    }

    @Test void preparedBundleRequiresAllFiveCandidatesAndKeepsExpectedObjectsAndMapsImmutable() {
        var plan = prepare(effective()); Object holder = new Object(), processor = new Object(), staged = new Object();
        var expected = new LinkedHashMap<String, Object>(); var candidates = new LinkedHashMap<String, Object>();
        VillagePoolPlan.POOL_KEYS.forEach(key -> { expected.put(key, new Object()); candidates.put(key, new Object()); });
        Object first = expected.values().iterator().next();
        var prepared = new VillagePoolPreparer.Prepared(VillagePoolPlan.PROCESSOR, holder, processor, staged, plan, expected, candidates);
        expected.clear(); candidates.clear();
        assertTrue(prepared.processorChangeRequired()); assertSame(holder, prepared.namedProcessorHolder());
        assertSame(first, prepared.expectedPoolValues().values().iterator().next());
        assertEquals(5, prepared.stagedPoolValues().size());
        assertThrows(UnsupportedOperationException.class, () -> prepared.expectedPoolValues().clear());
        assertThrows(UnsupportedOperationException.class, () -> prepared.stagedPoolValues().clear());
        assertThrows(IllegalArgumentException.class, () -> new VillagePoolPreparer.Prepared("otherpack:processor", holder, processor, staged,
                plan, prepared.expectedPoolValues(), prepared.stagedPoolValues()));
        assertThrows(IllegalArgumentException.class, () -> new VillagePoolPreparer.Prepared(VillagePoolPlan.PROCESSOR, holder, processor, staged,
                plan, Map.of(), prepared.stagedPoolValues()));
        var unchanged = new VillagePoolPreparer.Prepared(VillagePoolPlan.PROCESSOR, holder, processor, processor,
                plan, prepared.expectedPoolValues(), prepared.stagedPoolValues());
        assertFalse(unchanged.processorChangeRequired());
    }

    @Test void nativeExpansionAndRawEntryCountsAreBoundedBeforeAnyStagingAllocation() {
        var element = unrelated("otherpack:house");
        assertDoesNotThrow(() -> new VillagePoolPlan.Effective<>(new Object(), List.of(
                new VillagePoolPlan.Entry<>(element, VillagePoolPlan.MAX_EXPANDED_WEIGHT))));
        assertThrows(IllegalArgumentException.class, () -> new VillagePoolPlan.Effective<>(new Object(), List.of(
                new VillagePoolPlan.Entry<>(element, VillagePoolPlan.MAX_EXPANDED_WEIGHT + 1))));
        assertThrows(IllegalArgumentException.class, () -> new VillagePoolPlan.Effective<>(new Object(),
                java.util.Collections.nCopies(VillagePoolPlan.MAX_POOL_ENTRIES + 1, new VillagePoolPlan.Entry<>(element, 0))));

        for (var full : List.of(
                new VillagePoolPlan.Effective<>(new Object(), List.of(new VillagePoolPlan.Entry<>(element,
                        VillagePoolPlan.MAX_EXPANDED_WEIGHT - VillagePoolPlan.KITCHEN_WEIGHT + 1))),
                new VillagePoolPlan.Effective<>(new Object(), java.util.Collections.nCopies(
                        VillagePoolPlan.MAX_POOL_ENTRIES, new VillagePoolPlan.Entry<>(element, 0))))) {
            var pools = effective(); pools.put(VillagePoolPlan.POOL_KEYS.getLast(), full);
            var allocations = new AtomicInteger();
            assertThrows(IllegalArgumentException.class, () -> VillagePoolPlan.prepare(pools, this::inspect, (key, target) -> {
                allocations.incrementAndGet(); return kitchen(target);
            }));
            assertEquals(0, allocations.get(), "A full last pool must reject before any of the five kitchens are allocated");
        }
    }

    @Test void aPlanAtBothStagingLimitsRemainsExactlyIdempotent() {
        var pools = effective(); String key = VillagePoolPlan.POOL_KEYS.getFirst();
        var entries = new ArrayList<>(java.util.Collections.nCopies(VillagePoolPlan.MAX_POOL_ENTRIES - 1,
                new VillagePoolPlan.Entry<>(unrelated("otherpack:zero_weight"), 0)));
        entries.set(0, new VillagePoolPlan.Entry<>(unrelated("otherpack:weighted_house"),
                VillagePoolPlan.MAX_EXPANDED_WEIGHT - VillagePoolPlan.KITCHEN_WEIGHT));
        Object fallback = new Object(); pools.put(key, new VillagePoolPlan.Effective<>(fallback, entries));
        var first = prepare(pools);
        var capped = first.pools().get(key);
        assertEquals(VillagePoolPlan.MAX_POOL_ENTRIES, capped.entries().size());
        assertEquals(VillagePoolPlan.MAX_EXPANDED_WEIGHT, capped.totalWeight());
        first.pools().forEach((poolKey, pool) -> pools.put(poolKey, new VillagePoolPlan.Effective<>(pool.fallback(), pool.entries())));
        var second = VillagePoolPlan.prepare(pools, this::inspect, (poolKey, target) -> fail("A complete capped pool must not allocate again"));
        assertFalse(second.pools().get(key).appended());
        assertSame(fallback, second.pools().get(key).fallback());
        for (int index = 0; index < capped.entries().size(); index++)
            assertSame(capped.entries().get(index), second.pools().get(key).entries().get(index));
    }

    @Test void expandedNativeSelectionMustRetainExactObjectsOrderAndMultiplicityIncludingZeroWeights() {
        var first = unrelated("otherpack:one"); var second = unrelated("otherpack:two");
        var zero = unrelated("otherpack:zero"); var equalButDifferent = unrelated("otherpack:one");
        assertEquals(first, equalButDifferent); assertNotSame(first, equalButDifferent);
        var entries = List.of(new VillagePoolPlan.Entry<>(first, 2), new VillagePoolPlan.Entry<>(zero, 0),
                new VillagePoolPlan.Entry<>(second, 1), new VillagePoolPlan.Entry<>(first, 1));
        assertDoesNotThrow(() -> VillagePoolPlan.requireExpandedMatches(entries, List.of(first, first, second, first)));
        for (var divergent : List.of(List.of(first, second, first, first), List.of(first, first, first, first),
                List.of(equalButDifferent, first, second, first), List.of(first, first, second),
                List.of(first, first, second, first, zero)))
            assertThrows(IllegalArgumentException.class, () -> VillagePoolPlan.requireExpandedMatches(entries, divergent));
        assertDoesNotThrow(() -> VillagePoolPlan.requireExpandedMatches(List.of(new VillagePoolPlan.Entry<>(zero, 0)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> VillagePoolPlan.requireExpandedMatches(
                List.of(new VillagePoolPlan.Entry<>(first, VillagePoolPlan.MAX_EXPANDED_WEIGHT + 1)), List.of()));
    }
}
