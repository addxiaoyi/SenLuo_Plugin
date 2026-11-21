package net.kaleidoscope.cookery.worldgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Immutable merge ledger for the five resolved houses pools. It never replaces an existing element,
 * expands a weight, changes a fallback, or publishes anything. Native objects are retained by identity.
 */
public final class VillagePoolPlan<E, F> {
    public static final int KITCHEN_WEIGHT = 4;
    public static final int MAX_POOL_ENTRIES = 65_536;
    public static final int MAX_EXPANDED_WEIGHT = 1_048_576;
    public static final String PROCESSOR = "kaleidoscopecookery:crop_replace";
    public static final List<String> BIOMES = List.of("plains", "snowy", "savanna", "desert", "taiga");
    public static final List<String> POOL_KEYS = BIOMES.stream().map(biome -> "minecraft:village/" + biome + "/houses").toList();
    private static final Set<String> RESERVED_LOCATIONS = Set.copyOf(BIOMES.stream()
            .flatMap(biome -> java.util.stream.Stream.of(location(biome), "kaleidoscope_cookery:village/houses/" + biome + "_kitchen"))
            .toList());

    public record Entry<E>(E element, int weight) {
        public Entry {
            Objects.requireNonNull(element, "Village pool element");
            require(weight >= 0, "Negative effective village pool weight");
        }
    }

    public record Effective<E, F>(F fallback, List<Entry<E>> entries) {
        public Effective {
            Objects.requireNonNull(fallback, "Village pool fallback");
            require(entries.size() <= MAX_POOL_ENTRIES, "Effective village pool has too many entries to stage");
            entries = List.copyOf(entries);
            long total = entries.stream().mapToLong(Entry::weight).sum();
            require(total <= MAX_EXPANDED_WEIGHT,
                    "Effective village pool expanded weight exceeds staging limit");
        }
    }

    /** All named locations, including nested lists; canonical means one direct named legacy rigid element. */
    public record Inspection(List<String> locations, boolean canonical) {
        public Inspection { locations = List.copyOf(locations); }
    }

    @FunctionalInterface public interface Inspector<E> { Inspection inspect(E element); }

    public record Pool<E, F>(String key, String location, Effective<E, F> before,
                             List<Entry<E>> entries, boolean appended) {
        public Pool { entries = List.copyOf(entries); }
        public F fallback() { return before.fallback(); }
        public int totalWeight() { return Math.toIntExact(entries.stream().mapToLong(Entry::weight).sum()); }
    }

    private final Map<String, Pool<E, F>> pools;

    private VillagePoolPlan(Map<String, Pool<E, F>> pools) {
        this.pools = Collections.unmodifiableMap(new LinkedHashMap<>(pools));
    }

    public Map<String, Pool<E, F>> pools() { return pools; }

    /** Reject divergent runtime selection data instead of silently rebuilding it from encoded pairs. */
    public static <E> void requireExpandedMatches(List<Entry<E>> entries, List<?> expanded) {
        require(entries.size() <= MAX_POOL_ENTRIES, "Effective village pool has too many entries to inspect");
        long total = entries.stream().mapToLong(Entry::weight).sum();
        require(total <= MAX_EXPANDED_WEIGHT && expanded.size() == total,
                "Effective native village pool expanded size differs from its encoded weights");
        int position = 0;
        for (Entry<E> entry : entries)
            for (int repetition = 0; repetition < entry.weight(); repetition++)
                require(expanded.get(position++) == entry.element(),
                        "Effective native village pool selection differs from its encoded element/order/weight");
    }

    public static String location(String biome) {
        require(BIOMES.contains(biome), "Unknown village kitchen biome");
        return "kaleidoscopecookery:village/houses/" + biome + "_kitchen";
    }

    /**
     * Inspect all five pools before constructing any new kitchen element. Existing exact entries are
     * idempotent; duplicates, nested references, upstream aliases, and changed kitchen semantics fail.
     */
    public static <E, F> VillagePoolPlan<E, F> prepare(Map<String, Effective<E, F>> effective,
                                                     Inspector<E> inspector,
                                                     BiFunction<String, String, E> newKitchen) {
        require(effective.keySet().equals(Set.copyOf(POOL_KEYS)), "Expected all five effective village houses pools");
        Objects.requireNonNull(inspector, "Village pool inspector");
        Objects.requireNonNull(newKitchen, "Village kitchen factory");
        var present = new LinkedHashMap<String, Boolean>();
        var snapshots = new LinkedHashMap<String, Effective<E, F>>();
        for (int index = 0; index < BIOMES.size(); index++) {
            String key = POOL_KEYS.get(index), location = location(BIOMES.get(index));
            Effective<E, F> pool = Objects.requireNonNull(effective.get(key), "Missing effective village pool");
            snapshots.put(key, pool);
            int found = 0;
            for (Entry<E> entry : pool.entries()) {
                Inspection inspection = Objects.requireNonNull(inspector.inspect(entry.element()), "Village element inspection");
                for (String reference : inspection.locations()) {
                    if (!RESERVED_LOCATIONS.contains(reference)) continue;
                    require(reference.equals(location) && inspection.canonical() && inspection.locations().equals(List.of(location))
                                    && entry.weight() == KITCHEN_WEIGHT,
                            "Conflicting village kitchen element in " + key);
                    require(++found == 1, "Duplicate village kitchen element in " + key);
                }
            }
            if (found == 0) {
                require(pool.entries().size() < MAX_POOL_ENTRIES, "Effective village pool has no room for a kitchen entry");
                require(pool.entries().stream().mapToLong(Entry::weight).sum() <= MAX_EXPANDED_WEIGHT - KITCHEN_WEIGHT,
                        "Effective village pool has no room for kitchen weight");
            }
            present.put(key, found == 1);
        }
        var result = new LinkedHashMap<String, Pool<E, F>>();
        for (int index = 0; index < BIOMES.size(); index++) {
            String key = POOL_KEYS.get(index), location = location(BIOMES.get(index));
            Effective<E, F> before = snapshots.get(key);
            var entries = new ArrayList<>(before.entries());
            if (!present.get(key)) {
                E element = Objects.requireNonNull(newKitchen.apply(key, location), "New village kitchen element");
                Inspection inspection = inspector.inspect(element);
                require(inspection.canonical() && inspection.locations().equals(List.of(location)), "Kitchen factory returned conflicting element");
                entries.add(new Entry<>(element, KITCHEN_WEIGHT));
            }
            result.put(key, new Pool<>(key, location, before, entries, !present.get(key)));
        }
        return new VillagePoolPlan<>(result);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
