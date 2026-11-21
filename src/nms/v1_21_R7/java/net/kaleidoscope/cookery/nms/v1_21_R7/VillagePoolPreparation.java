package net.kaleidoscope.cookery.nms.v1_21_R7;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import net.kaleidoscope.cookery.block.behavior.SourceCropBehavior;
import net.kaleidoscope.cookery.worldgen.VillagePoolPlan;
import net.kaleidoscope.cookery.worldgen.VillagePoolPreparer;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.pools.LegacySinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.PosAlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProcessorRule;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.DelegatingBlockState;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Constructs unpublished 1.21.11 candidates from the actual resolved registries. All reflective field
 * access is read-only. No registry holder, existing pool/processor, template cache, or world is changed.
 * Prepared objects are unusable until a separately enforced startup publication lifecycle exists.
 */
public final class VillagePoolPreparation implements VillagePoolPreparer {
    @Override public Prepared prepare(Object rawAccess) {
        require(rawAccess instanceof RegistryAccess, "Village pools require native registry access");
        RegistryAccess registryAccess = (RegistryAccess) rawAccess;
        Registry<StructureTemplatePool> pools = registryAccess.lookupOrThrow(Registries.TEMPLATE_POOL);
        Registry<StructureProcessorList> processors = registryAccess.lookupOrThrow(Registries.PROCESSOR_LIST);
        ResourceKey<StructureProcessorList> processorKey = ResourceKey.create(Registries.PROCESSOR_LIST, Identifier.parse(VillagePoolPlan.PROCESSOR));
        Holder.Reference<StructureProcessorList> named = processors.getOrThrow(processorKey);
        require(named.isBound() && named.unwrapKey().orElseThrow().equals(processorKey), "Unbound or unnamed village processor");
        StructureProcessorList originalProcessor = named.value();
        require(processors.getValue(processorKey) == originalProcessor, "Village processor is not the registry's resolved value");
        Access access = new Access();
        var crops = List.of(resolve("tomato"), resolve("chili"), resolve("lettuce"));
        require(crops.get(0).nativeState != crops.get(1).nativeState && crops.get(0).nativeState != crops.get(2).nativeState
                && crops.get(1).nativeState != crops.get(2).nativeState, "Village crops share a native server slot");
        boolean currentProcessor = access.matches(originalProcessor, crops);
        // An initially registered empty list may be staged, but is never bound or published here.
        require(currentProcessor || originalProcessor.list().isEmpty(), "Conflicting named village processor");
        StructureProcessorList stagedProcessor = currentProcessor ? originalProcessor
                : processor(crops.get(0).nativeState, crops.get(1).nativeState, crops.get(2).nativeState);
        var originals = new LinkedHashMap<String, Object>();
        var effective = new LinkedHashMap<String, VillagePoolPlan.Effective<StructurePoolElement, Holder<StructureTemplatePool>>>();
        for (String key : VillagePoolPlan.POOL_KEYS) {
            ResourceKey<StructureTemplatePool> poolKey = ResourceKey.create(Registries.TEMPLATE_POOL, Identifier.parse(key));
            Holder.Reference<StructureTemplatePool> holder = pools.getOrThrow(poolKey);
            require(holder.isBound(), "Unbound effective village pool: " + key);
            StructureTemplatePool pool = holder.value();
            require(pools.getValue(poolKey) == pool && pool.getFallback().isBound(), "Unresolved village pool/fallback: " + key);
            originals.put(key, pool);
            var snapshot = snapshot(pool);
            access.checkExpanded(snapshot.entries(), pool);
            effective.put(key, snapshot);
        }
        VillagePoolPlan<StructurePoolElement, Holder<StructureTemplatePool>> plan = VillagePoolPlan.prepare(effective,
                element -> access.inspect(element, named),
                (key, location) -> StructurePoolElement.legacy(location, named).apply(StructureTemplatePool.Projection.RIGID));
        require(currentProcessor || plan.pools().values().stream().allMatch(VillagePoolPlan.Pool::appended),
                "An existing kitchen references an unprepared empty processor");
        var staged = new LinkedHashMap<String, Object>();
        for (var pool : plan.pools().values()) {
            var entries = pool.entries().stream().map(entry -> Pair.of(entry.element(), entry.weight())).toList();
            StructureTemplatePool candidate = new StructureTemplatePool(pool.fallback(), entries);
            require(candidate.getFallback() == pool.fallback() && candidate.size() == pool.totalWeight(), "Native village pool construction changed fallback/weight");
            sameEntries(pool.entries(), candidate.getTemplates());
            access.checkExpanded(pool.entries(), candidate);
            staged.put(pool.key(), candidate);
        }
        // Detect changed inputs during construction. These checks do not establish a generation/reload barrier.
        require(processors.getOrThrow(processorKey) == named && named.value() == originalProcessor, "Village processor changed during preparation");
        require(currentProcessor ? access.matches(originalProcessor, crops) : originalProcessor.list().isEmpty(),
                "Village processor contents changed during preparation");
        for (var pool : plan.pools().values()) {
            ResourceKey<StructureTemplatePool> key = ResourceKey.create(Registries.TEMPLATE_POOL, Identifier.parse(pool.key()));
            require(pools.getValue(key) == originals.get(pool.key()), "Effective village pool changed during preparation");
            StructureTemplatePool original = (StructureTemplatePool) originals.get(pool.key());
            require(original.getFallback() == pool.before().fallback(), "Effective village fallback changed during preparation");
            sameEntries(pool.before().entries(), original.getTemplates());
            access.checkExpanded(pool.before().entries(), original);
        }
        crops.forEach(Resolved::checkCurrent);
        return new Prepared(VillagePoolPlan.PROCESSOR, named, originalProcessor, stagedProcessor, plan, originals, staged);
    }

    /** One native processor preserves the native per-position RNG and first matching rule semantics. */
    private static StructureProcessorList processor(BlockState tomato, BlockState chili, BlockState lettuce) {
        return new StructureProcessorList(List.of(new RuleProcessor(List.of(
                new ProcessorRule(new RandomBlockStateMatchTest(tomato, 0.33F), AlwaysTrueTest.INSTANCE, chili),
                new ProcessorRule(new RandomBlockStateMatchTest(tomato, 0.33F), AlwaysTrueTest.INSTANCE, lettuce)))));
    }

    private static VillagePoolPlan.Effective<StructurePoolElement, Holder<StructureTemplatePool>> snapshot(StructureTemplatePool pool) {
        List<Pair<StructurePoolElement, Integer>> nativeEntries = pool.getTemplates();
        require(nativeEntries.size() <= VillagePoolPlan.MAX_POOL_ENTRIES, "Effective village pool has too many entries to snapshot");
        var entries = new ArrayList<VillagePoolPlan.Entry<StructurePoolElement>>(nativeEntries.size());
        for (Pair<StructurePoolElement, Integer> entry : nativeEntries)
            entries.add(new VillagePoolPlan.Entry<>(entry.getFirst(), entry.getSecond()));
        return new VillagePoolPlan.Effective<>(pool.getFallback(), entries);
    }

    private static void sameEntries(List<VillagePoolPlan.Entry<StructurePoolElement>> expected,
                                    List<Pair<StructurePoolElement, Integer>> actual) {
        require(expected.size() == actual.size(), "Native village pool entry count changed");
        for (int index = 0; index < expected.size(); index++)
            require(expected.get(index).element() == actual.get(index).getFirst()
                    && expected.get(index).weight() == actual.get(index).getSecond(), "Native village pool element/order/weight changed");
    }

    private record Resolved(String id, ImmutableBlockState semantic, BlockState nativeState) {
        void checkCurrent() {
            require(nativeState instanceof DelegatingBlockState && ((DelegatingBlockState) nativeState).blockState() == semantic
                    && semantic.customBlockState() != null && semantic.customBlockState().minecraftState() == nativeState
                    && CraftEngineBlocks.byId(Key.of(id)) == semantic.owner().value() && properties(semantic).equals(Map.of("age", "7")),
                    "CE crop mapping changed during village processor preparation");
        }
    }

    private static Resolved resolve(String crop) {
        String id = "kaleidoscopecookery:" + crop + "_crop";
        var definition = CraftEngineBlocks.byId(Key.of(id));
        require(definition != null, "Missing village CE crop: " + id);
        Property<?> age = definition.getProperty("age");
        require(age != null, "Missing village CE crop age: " + id);
        Object mature = age.valueByName("7");
        require(mature != null, "Missing mature village CE crop state: " + id);
        ImmutableBlockState semantic = definition.defaultState();
        require(semantic != null && !semantic.isEmpty(), "Unready village CE crop: " + id);
        semantic = ImmutableBlockState.with(semantic, age, mature);
        require(semantic.owner().value() == definition && properties(semantic).equals(Map.of("age", "7"))
                && semantic.behavior().getFirst(SourceCropBehavior.class) != null && semantic.customBlockState() != null
                && semantic.customBlockState().minecraftState() instanceof BlockState, "Village CE crop behavior/server state unavailable: " + id);
        var result = new Resolved(id, semantic, (BlockState) semantic.customBlockState().minecraftState());
        result.checkCurrent();
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, String> properties(ImmutableBlockState state) {
        var result = new LinkedHashMap<String, String>();
        state.propertyEntries().forEach((property, value) -> result.put(property.name(), ((Property) property).valueName((Comparable) value)));
        return result;
    }

    /** Shared Paper/Spigot 1.21.11 field ABI; only reads, including protected/final members. */
    private static final class Access {
        private int remainingInspections = VillagePoolPlan.MAX_EXPANDED_WEIGHT;
        final Field expanded = field(StructureTemplatePool.class, "templates", List.class);
        final Field template = field(SinglePoolElement.class, "template", Either.class);
        final Field processors = field(SinglePoolElement.class, "processors", Holder.class);
        final Field liquid = field(SinglePoolElement.class, "overrideLiquidSettings", Optional.class);
        final Field elements = field(ListPoolElement.class, "elements", List.class);
        final Field rules = field(RuleProcessor.class, "rules", List.class);
        final Field input = field(ProcessorRule.class, "inputPredicate", net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest.class);
        final Field location = field(ProcessorRule.class, "locPredicate", net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest.class);
        final Field position = field(ProcessorRule.class, "posPredicate", net.minecraft.world.level.levelgen.structure.templatesystem.PosRuleTest.class);
        final Field modifier = field(ProcessorRule.class, "blockEntityModifier", net.minecraft.world.level.levelgen.structure.templatesystem.rule.blockentity.RuleBlockEntityModifier.class);
        final Field state = field(RandomBlockStateMatchTest.class, "blockState", BlockState.class);
        final Field probability = field(RandomBlockStateMatchTest.class, "probability", float.class);

        void checkExpanded(List<VillagePoolPlan.Entry<StructurePoolElement>> entries, StructureTemplatePool pool) {
            VillagePoolPlan.requireExpandedMatches(entries, (List<?>) get(expanded, pool));
        }

        VillagePoolPlan.Inspection inspect(StructurePoolElement element, Holder<StructureProcessorList> named) {
            var locations = new ArrayList<String>();
            inspectLocations(element, locations, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            boolean canonical = element.getClass() == LegacySinglePoolElement.class && locations.size() == 1
                    && element.getProjection() == StructureTemplatePool.Projection.RIGID
                    && get(processors, element) == named && ((Optional<?>) get(liquid, element)).isEmpty();
            return new VillagePoolPlan.Inspection(locations, canonical);
        }

        private void inspectLocations(StructurePoolElement element, List<String> locations, Set<StructurePoolElement> path, int depth) {
            require(remainingInspections-- > 0, "Effective village pool nested element inspection limit exceeded");
            require(depth <= 64 && path.add(element), "Cyclic or overdeep effective village pool element");
            if (element instanceof SinglePoolElement) {
                Either<?, ?> value = (Either<?, ?>) get(template, element);
                value.left().ifPresent(key -> {
                    require(key instanceof Identifier, "Unexpected named village template location");
                    locations.add(key.toString());
                });
            } else if (element instanceof ListPoolElement) {
                for (Object child : (List<?>) get(elements, element)) {
                    require(child instanceof StructurePoolElement, "Unexpected native village list element");
                    inspectLocations((StructurePoolElement) child, locations, path, depth + 1);
                }
            }
            path.remove(element);
        }

        boolean matches(StructureProcessorList value, List<Resolved> crops) {
            if (value.list().size() != 1 || value.list().getFirst().getClass() != RuleProcessor.class) return false;
            List<?> values = (List<?>) get(rules, value.list().getFirst());
            if (values.size() != 2) return false;
            for (int index = 0; index < 2; index++) {
                if (!(values.get(index) instanceof ProcessorRule rule)) return false;
                Object predicate = get(input, rule);
                if (predicate.getClass() != RandomBlockStateMatchTest.class || get(state, predicate) != crops.getFirst().nativeState
                        || Float.compare((Float) get(probability, predicate), 0.33F) != 0
                        || get(location, rule) != AlwaysTrueTest.INSTANCE || get(position, rule) != PosAlwaysTrueTest.INSTANCE
                        || get(modifier, rule) != ProcessorRule.DEFAULT_BLOCK_ENTITY_MODIFIER
                        || rule.getOutputState() != crops.get(index + 1).nativeState) return false;
            }
            return true;
        }

        private static Field field(Class<?> owner, String name, Class<?> type) {
            try {
                Field field = owner.getDeclaredField(name);
                require(type.isAssignableFrom(field.getType()), "Unexpected native village ABI: " + owner.getSimpleName() + "." + name);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native 1.21.11 village pool ABI unavailable: " + name, failure);
            }
        }

        private static Object get(Field field, Object owner) {
            try { return field.get(owner); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot inspect native village pool/processor", failure); }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
