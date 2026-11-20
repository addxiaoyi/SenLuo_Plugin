package net.kaleidoscope.cookery.nms.v1_21_R7;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import net.kaleidoscope.cookery.worldgen.VillagePoolPlan;
import net.kaleidoscope.cookery.worldgen.VillagePoolPreparer;
import net.kaleidoscope.cookery.worldgen.VillagePublicationTransaction;
import net.kaleidoscope.cookery.worldgen.VillagePublisher;
import net.kaleidoscope.cookery.worldgen.VillageTemplateMigrator;
import net.kaleidoscope.cookery.worldgen.VillageTemplatePlan;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.pools.LegacySinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.PosAlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProcessorRule;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.DelegatingBlockState;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static net.kaleidoscope.cookery.worldgen.VillagePublicationTransaction.*;

/**
 * Narrow, dormant 1.21.11 transplant transaction. Registered objects and holder identities are kept;
 * the supplied real exclusion scope, not this adapter, must prevent all concurrent native readers
 * and lifecycle writers. Actual final-field reflection still needs target-server/JDK acceptance.
 */
public final class VillagePublication implements VillagePublisher {
    @Override public VillagePublicationTransaction stage(Object rawRegistry, Object rawManager,
                                                         List<VillageTemplateMigrator.Prepared> templates,
                                                         VillagePoolPreparer.Prepared prepared,
                                                         ExclusionScope scope) {
        Objects.requireNonNull(scope, "A real caller-owned publication exclusion is required").check();
        require(rawRegistry instanceof RegistryAccess && rawManager instanceof StructureTemplateManager,
                "Village publication requires native registry access and template manager");
        Objects.requireNonNull(templates); Objects.requireNonNull(prepared);
        require(templates.size() == 5, "Expected all five prepared village templates");
        RegistryAccess roots = (RegistryAccess) rawRegistry;
        StructureTemplateManager manager = (StructureTemplateManager) rawManager;
        Access access = new Access();
        Map<Identifier, Optional<StructureTemplate>> cache = access.cache(manager);
        require(cache.getClass() == java.util.concurrent.ConcurrentHashMap.class, "Unexpected native village template cache implementation");
        Registry<StructureTemplatePool> poolRegistry = roots.lookupOrThrow(Registries.TEMPLATE_POOL);
        Registry<StructureProcessorList> processorRegistry = roots.lookupOrThrow(Registries.PROCESSOR_LIST);
        // Re-prepare from the actual effective registries: public Prepared records are not a trust boundary.
        VillagePoolPreparer.Prepared fresh = new VillagePoolPreparation().prepare(roots);
        scope.check();
        require(prepared.namedProcessorHolder() == fresh.namedProcessorHolder()
                && prepared.expectedProcessorValue() == fresh.expectedProcessorValue(), "Prepared village processor is stale");
        require(prepared.processorChangeRequired() == fresh.processorChangeRequired(), "Prepared village processor transition changed");
        require(prepared.expectedProcessorValue() instanceof StructureProcessorList
                && prepared.stagedProcessorValue() instanceof StructureProcessorList, "Unexpected prepared native processor type");
        StructureProcessorList processor = (StructureProcessorList) prepared.expectedProcessorValue();
        StructureProcessorList stagedProcessor = (StructureProcessorList) prepared.stagedProcessorValue();
        List<?> oldRules = (List<?>) access.get(access.processorList, processor);
        List<?> nextRules = (List<?>) access.get(access.processorList, stagedProcessor);
        access.sameProcessor(stagedProcessor, (StructureProcessorList) fresh.stagedProcessorValue());
        ProcessorSnapshot processorSnapshot = access.processorSnapshot(stagedProcessor);
        var edits = new ArrayList<Edit<?>>();
        var seen = new IdentityHashMap<Object, Boolean>();
        unique(seen, processor); unique(seen, manager); unique(seen, cache);
        var templateSnapshots = new LinkedHashMap<String, TemplateSnapshot>();
        var cacheSlots = new LinkedHashMap<Identifier, MapEntry>();
        var publishedCache = new LinkedHashMap<Identifier, MapEntry>();
        for (VillageTemplateMigrator.Prepared template : List.copyOf(templates)) {
            scope.check();
            require(template != null && template.nativeTemplate() instanceof StructureTemplate, "Unexpected prepared native template type");
            String biome = template.sidecar().source().biome();
            require(VillagePoolPlan.BIOMES.contains(biome) && VillagePoolPlan.location(biome).equals(template.location()),
                    "Unexpected prepared village template role");
            require(!templateSnapshots.containsKey(template.location()), "Duplicate prepared village template");
            unique(seen, template.nativeTemplate());
            // Independently reproduce full migration, including vanilla states and NBT, before trusting a mutable candidate.
            var authentic = new VillageTemplateMigration().prepare(template.sidecar().source(), manager);
            TemplateSnapshot snapshot = access.template(template, (StructureTemplate) authentic.nativeTemplate());
            scope.check();
            templateSnapshots.put(template.location(), snapshot);
            Identifier key = Identifier.parse(template.location());
            MapEntry before = cacheValue(cache, key);
            require(!before.present() || before.value() instanceof Optional<?>, "Invalid village template cache entry");
            if (before.present()) {
                Optional<?> existing = (Optional<?>) before.value();
                require(existing.isEmpty() || existing.orElseThrow() == template.nativeTemplate(), "Conflicting populated village template cache entry");
            }
            MapEntry after = before.present() && ((Optional<?>) before.value()).orElse(null) == template.nativeTemplate()
                    ? before : new MapEntry(true, Optional.of((StructureTemplate) template.nativeTemplate()));
            cacheSlots.put(key, before); publishedCache.put(key, after);
            edits.add(new Edit<>("template:" + key, new Slot<MapEntry>() {
                public MapEntry read() { return cacheValue(cache, key); }
                @SuppressWarnings("unchecked") public void write(MapEntry value) {
                    if (value.present()) cache.put(key, (Optional<StructureTemplate>) value.value()); else cache.remove(key);
                }
            }, before, after));
        }
        require(templateSnapshots.keySet().equals(Set.copyOf(VillagePoolPlan.BIOMES.stream().map(VillagePoolPlan::location).toList())),
                "Incomplete prepared village templates");
        edits.add(referenceEdit("processor:list", access, access.processorList, processor, oldRules, nextRules));
        var pools = new ArrayList<PoolSnapshot>();
        var holders = new LinkedHashMap<String, Holder.Reference<StructureTemplatePool>>();
        require(prepared.plan().pools().keySet().equals(Set.copyOf(VillagePoolPlan.POOL_KEYS)), "Incomplete prepared village pool plan");
        for (String key : VillagePoolPlan.POOL_KEYS) {
            scope.check();
            require(prepared.expectedPoolValues().get(key) == fresh.expectedPoolValues().get(key), "Prepared effective village pool is stale");
            require(prepared.expectedPoolValues().get(key) instanceof StructureTemplatePool
                    && prepared.stagedPoolValues().get(key) instanceof StructureTemplatePool, "Unexpected prepared village pool type");
            StructureTemplatePool original = (StructureTemplatePool) prepared.expectedPoolValues().get(key);
            StructureTemplatePool candidate = (StructureTemplatePool) prepared.stagedPoolValues().get(key);
            unique(seen, original); unique(seen, candidate);
            var supplied = prepared.plan().pools().get(key);
            var canonical = fresh.plan().pools().get(key);
            require(supplied.appended() == canonical.appended() && supplied.location().equals(canonical.location()), "Prepared kitchen append changed");
            access.sameEntries(supplied.before().entries(), original.getTemplates());
            access.sameEntries(canonical.before().entries(), original.getTemplates());
            access.sameEntries(supplied.entries(), candidate.getTemplates());
            access.samePrefix(supplied, canonical, prepared.namedProcessorHolder());
            require(original.getFallback() == supplied.fallback() && candidate.getFallback() == original.getFallback(), "Prepared fallback changed");
            List<?> beforeRaw = (List<?>) access.get(access.raw, original), beforeExpanded = (List<?>) access.get(access.expanded, original);
            List<?> afterRaw = supplied.appended() ? (List<?>) access.get(access.raw, candidate) : beforeRaw;
            List<?> afterExpanded = supplied.appended() ? (List<?>) access.get(access.expanded, candidate) : beforeExpanded;
            int beforeMax = (Integer) access.get(access.maximum, original), afterMax = supplied.appended() ? Integer.MIN_VALUE : beforeMax;
            require(afterExpanded.getClass() == access.expanded.getType(), "Unexpected native expanded village pool implementation");
            var snapshot = new PoolSnapshot(key, original, candidate, supplied, beforeRaw, beforeExpanded, afterRaw, afterExpanded,
                    beforeMax, afterMax, original.getFallback(), access.get(access.raw, candidate), access.get(access.expanded, candidate),
                    prepared.namedProcessorHolder(), access.kitchenEntry(supplied.entries(), supplied.location(), prepared.namedProcessorHolder()));
            snapshot.check(access, false); pools.add(snapshot);
            Holder.Reference<StructureTemplatePool> holder = poolRegistry.getOrThrow(poolKey(key));
            require(holder.value() == original, "Effective village pool holder changed"); holders.put(key, holder);
            edits.add(referenceEdit("pool:" + key + ":raw", access, access.raw, original, beforeRaw, afterRaw));
            edits.add(referenceEdit("pool:" + key + ":expanded", access, access.expanded, original, beforeExpanded, afterExpanded));
            edits.add(new Edit<>("pool:" + key + ":maximum", new Slot<IntValue>() {
                public IntValue read() { return new IntValue((Integer) access.get(access.maximum, original)); }
                public void write(IntValue value) { access.set(access.maximum, original, value.value()); }
            }, new IntValue(beforeMax), new IntValue(afterMax)));
        }
        Runnable rootsCurrent = () -> {
            require(roots.lookupOrThrow(Registries.TEMPLATE_POOL) == poolRegistry
                    && roots.lookupOrThrow(Registries.PROCESSOR_LIST) == processorRegistry && access.cache(manager) == cache,
                    "Native village publication roots changed");
            var processorHolder = processorRegistry.getOrThrow(processorKey());
            require(processorHolder == prepared.namedProcessorHolder() && processorHolder.value() == processor
                    && processorRegistry.getValue(processorKey()) == processor, "Native village processor binding changed");
            for (String key : VillagePoolPlan.POOL_KEYS)
                require(poolRegistry.getOrThrow(poolKey(key)) == holders.get(key)
                        && holders.get(key).value() == prepared.expectedPoolValues().get(key)
                        && poolRegistry.getValue(poolKey(key)) == prepared.expectedPoolValues().get(key), "Effective village pool binding changed");
        };
        Runnable shared = () -> {
            rootsCurrent.run();
            templateSnapshots.values().forEach(snapshot -> snapshot.check(access));
            require(access.get(access.processorList, stagedProcessor) == nextRules, "Staged village processor list replaced");
            processorSnapshot.check(access);
        };
        Runnable preflight = () -> {
            shared.run();
            require(access.get(access.processorList, processor) == oldRules, "Village processor list changed before publication");
            if (prepared.processorChangeRequired()) require(oldRules.isEmpty(), "Empty village processor placeholder changed before publication");
            cacheSlots.forEach((key, value) -> require(sameCache(cacheValue(cache, key), value), "Village template cache changed before publication"));
            pools.forEach(pool -> pool.check(access, false));
        };
        Runnable verifyPublished = () -> {
            // Re-preparation is intentionally omitted here: newly appended pools now reference the real updated processor.
            rootsCurrent.run(); templateSnapshots.values().forEach(snapshot -> snapshot.check(access));
            require(access.get(access.processorList, processor) == nextRules && access.get(access.processorList, stagedProcessor) == nextRules,
                    "Published village processor list differs from staged list");
            processorSnapshot.check(access);
            publishedCache.forEach((key, value) -> require(sameCache(cacheValue(cache, key), value), "Published village template cache changed"));
            pools.forEach(pool -> pool.check(access, true));
        };
        scope.check(); preflight.run(); scope.check();
        return new VillagePublicationTransaction(edits, scope, preflight, verifyPublished);
    }

    private static Edit<Reference> referenceEdit(String name, Access access, Field field, Object owner, Object before, Object after) {
        return new Edit<>(name, new Slot<Reference>() {
            public Reference read() { return new Reference(access.get(field, owner)); }
            public void write(Reference value) { access.set(field, owner, value.value()); }
        }, new Reference(before), new Reference(after));
    }
    private static MapEntry cacheValue(Map<Identifier, Optional<StructureTemplate>> cache, Identifier key) {
        return new MapEntry(cache.containsKey(key), cache.get(key));
    }
    private static boolean sameCache(MapEntry first, MapEntry second) { return first.present() == second.present() && first.value() == second.value(); }
    private static void unique(IdentityHashMap<Object, Boolean> seen, Object value) {
        require(seen.put(value, Boolean.TRUE) == null, "Aliased native village publication roles");
    }
    private static ResourceKey<StructureTemplatePool> poolKey(String key) { return ResourceKey.create(Registries.TEMPLATE_POOL, Identifier.parse(key)); }
    private static ResourceKey<StructureProcessorList> processorKey() { return ResourceKey.create(Registries.PROCESSOR_LIST, Identifier.parse(VillagePoolPlan.PROCESSOR)); }

    private record PoolSnapshot(String key, StructureTemplatePool original, StructureTemplatePool candidate,
                                VillagePoolPlan.Pool<?, ?> plan, List<?> oldRaw, List<?> oldExpanded,
                                List<?> nextRaw, List<?> nextExpanded, int oldMax, int nextMax, Object fallback,
                                Object candidateRaw, Object candidateExpanded, Object namedProcessorHolder,
                                VillagePoolPlan.Entry<?> kitchen) {
        void check(Access access, boolean published) {
            require(original.getFallback() == fallback && candidate.getFallback() == fallback, "Village fallback drift");
            require(access.get(access.raw, candidate) == candidateRaw && access.get(access.expanded, candidate) == candidateExpanded,
                    "Prepared village pool list identity changed");
            require(access.get(access.raw, original) == (published ? nextRaw : oldRaw)
                    && access.get(access.expanded, original) == (published ? nextExpanded : oldExpanded)
                    && (Integer) access.get(access.maximum, original) == (published ? nextMax : oldMax), "Village pool field identity drift");
            access.sameEntries(published ? plan.entries() : plan.before().entries(), original.getTemplates());
            access.expanded(published ? plan.entries() : plan.before().entries(), (List<?>) access.get(access.expanded, original));
            access.sameEntries(plan.entries(), candidate.getTemplates());
            access.expanded(plan.entries(), (List<?>) access.get(access.expanded, candidate));
            access.kitchen(kitchen, plan.location(), namedProcessorHolder);
        }
    }

    private record BlockSnapshot(StructureBlockInfo info, VillageTemplatePlan.Position position, BlockState state, CompoundTag nbt,
                                 VillageTemplatePlan.State semantic, ImmutableBlockState resolved) {}
    private record TemplateSnapshot(StructureTemplate template, VillageTemplatePlan plan, List<?> palettes, Object palette,
                                    List<?> blocks, List<BlockSnapshot> captured, Object entities, Object data, QuerySnapshot query, String author) {
        void check(Access access) {
            require(access.get(access.palettes, template) == palettes && palettes.size() == 1 && palettes.getFirst() == palette
                    && access.blocks(palette) == blocks && blocks.size() == captured.size(), "Native village template palette changed");
            require(access.get(access.entities, template) == entities && ((List<?>) entities).isEmpty()
                    && access.get(access.data, template) == data && ((org.bukkit.persistence.PersistentDataContainer) data).isEmpty()
                    && Objects.equals(author, access.get(access.author, template)),
                    "Native village template entity/data/query state changed");
            query.check(access, palette, blocks);
            require(template.getSize().getX() == plan.size().get(0) && template.getSize().getY() == plan.size().get(1)
                    && template.getSize().getZ() == plan.size().get(2), "Native village template size changed");
            for (int index = 0; index < captured.size(); index++) {
                BlockSnapshot snapshot = captured.get(index);
                StructureBlockInfo info = (StructureBlockInfo) blocks.get(index);
                require(info == snapshot.info() && position(info).equals(snapshot.position()) && info.state() == snapshot.state()
                        && Objects.equals(info.nbt(), snapshot.nbt()), "Native village template block/NBT changed");
                if (snapshot.semantic() != null) {
                    require(((DelegatingBlockState) info.state()).blockState() == snapshot.resolved(), "Village template CE generation changed");
                    current(info.state(), snapshot.semantic());
                }
            }
        }
    }
    private record CachedBlocks(Object key, Object list, List<?> contents) {}
    private record QuerySnapshot(Object cache, List<CachedBlocks> cached, Object jigsaws, List<?> jigsawEntries) {
        void check(Access access, Object palette, List<?> blocks) {
            require(access.get(access.paletteCache, palette) == cache && ((Map<?, ?>) cache).size() == cached.size()
                    && access.get(access.cachedJigsaws, palette) == jigsaws, "Native palette query cache identity changed");
            Map<?, ?> values = (Map<?, ?>) cache;
            for (var entry : cached) {
                require(values.get(entry.key()) == entry.list(), "Native palette block query cache list changed");
                sameObjects((List<?>) entry.list(), entry.contents());
                List<?> expected = blocks.stream().map(value -> (StructureBlockInfo) value).filter(info -> info.state().is((Block) entry.key())).toList();
                sameObjects(expected, entry.contents());
            }
            if (jigsaws != null) {
                sameObjects((List<?>) jigsaws, jigsawEntries);
                var expected = blocks.stream().map(value -> (StructureBlockInfo) value).filter(info -> info.state().is(Blocks.JIGSAW))
                        .map(StructureTemplate.JigsawBlockInfo::of).toList();
                require(expected.equals(jigsawEntries), "Native palette jigsaw cache differs from full block state/NBT");
            }
        }
        private static void sameObjects(List<?> observed, List<?> expected) {
            require(observed.size() == expected.size(), "Native palette query cache count changed");
            for (int index = 0; index < expected.size(); index++) require(observed.get(index) == expected.get(index), "Native palette query cache entry changed");
        }
    }
    private static VillageTemplatePlan.Position position(StructureBlockInfo info) {
        return new VillageTemplatePlan.Position(info.pos().getX(), info.pos().getY(), info.pos().getZ());
    }
    private static void current(BlockState nativeState, VillageTemplatePlan.State expected) {
        current(nativeState, expected.targetId(), expected.properties());
    }
    private static void current(BlockState nativeState, String id, Map<String, String> expectedProperties) {
        require(nativeState instanceof DelegatingBlockState, "Expected native CE village state");
        ImmutableBlockState semantic = ((DelegatingBlockState) nativeState).blockState();
        require(semantic != null && !semantic.isEmpty() && semantic.customBlockState() != null
                && semantic.customBlockState().minecraftState() == nativeState
                && CraftEngineBlocks.byId(Key.of(id)) == semantic.owner().value()
                && properties(semantic).equals(expectedProperties), "Current CE village semantic/native mapping changed");
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static Map<String, String> properties(ImmutableBlockState state) {
        var result = new LinkedHashMap<String, String>();
        state.propertyEntries().forEach((property, value) -> result.put(property.name(), ((Property) property).valueName((Comparable) value)));
        return result;
    }

    private static final class Access {
        final Field repository = field(StructureTemplateManager.class, "structureRepository", Map.class, true);
        final Field processorList = field(StructureProcessorList.class, "list", List.class, true);
        final Field raw = field(StructureTemplatePool.class, "rawTemplates", List.class, true);
        final Field expanded = fieldByName(StructureTemplatePool.class, "templates", "it.unimi.dsi.fastutil.objects.ObjectArrayList", true);
        final Field maximum = field(StructureTemplatePool.class, "maxSize", int.class, false);
        final Field palettes = field(StructureTemplate.class, "palettes", List.class, true);
        final Field entities = field(StructureTemplate.class, "entityInfoList", List.class, true);
        final Field data = persistentDataField();
        final Field author = field(StructureTemplate.class, "author", String.class, false);
        final Field paletteCache;
        final Field cachedJigsaws;
        final Field namedTemplate = field(SinglePoolElement.class, "template", Either.class, true);
        final Field namedProcessors = field(SinglePoolElement.class, "processors", Holder.class, true);
        final Field liquid = field(SinglePoolElement.class, "overrideLiquidSettings", Optional.class, true);
        final Field rules = fieldByName(RuleProcessor.class, "rules", "com.google.common.collect.ImmutableList", true);
        final Field input = field(ProcessorRule.class, "inputPredicate", net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest.class, true);
        final Field location = field(ProcessorRule.class, "locPredicate", net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest.class, true);
        final Field pos = field(ProcessorRule.class, "posPredicate", net.minecraft.world.level.levelgen.structure.templatesystem.PosRuleTest.class, true);
        final Field modifier = field(ProcessorRule.class, "blockEntityModifier", net.minecraft.world.level.levelgen.structure.templatesystem.rule.blockentity.RuleBlockEntityModifier.class, true);
        final Field matchState = field(RandomBlockStateMatchTest.class, "blockState", BlockState.class, true);
        final Field chance = field(RandomBlockStateMatchTest.class, "probability", float.class, true);
        final Method blocks;
        Access() {
            try {
                Class<?> palette = Class.forName(StructureTemplate.class.getName() + "$Palette");
                blocks = palette.getDeclaredMethod("blocks");
                require(blocks.getReturnType() == List.class, "Unexpected native palette blocks ABI"); blocks.setAccessible(true);
                paletteCache = field(palette, "cache", Map.class, true);
                cachedJigsaws = field(palette, "cachedJigsaws", List.class, false);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot inspect native village palette", failure); }
        }
        @SuppressWarnings("unchecked") Map<Identifier, Optional<StructureTemplate>> cache(StructureTemplateManager manager) {
            return (Map<Identifier, Optional<StructureTemplate>>) get(repository, manager);
        }
        List<?> blocks(Object palette) {
            try { return (List<?>) blocks.invoke(palette); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot read native village palette", failure); }
        }
        TemplateSnapshot template(VillageTemplateMigrator.Prepared supplied, StructureTemplate authentic) {
            StructureTemplate value = (StructureTemplate) supplied.nativeTemplate();
            VillageTemplatePlan plan = VillageTemplatePlan.from(supplied.sidecar().source());
            require(value.getSize().equals(authentic.getSize()), "Prepared native village template dimensions differ from pinned migration");
            List<?> paletteValues = (List<?>) get(palettes, value), actual = (List<?>) get(palettes, authentic);
            require(paletteValues.size() == 1 && actual.size() == 1, "Unexpected native village palette count");
            Object palette = paletteValues.getFirst(); List<?> values = blocks(palette), required = blocks(actual.getFirst());
            require(values.size() == plan.volume() && values.size() == required.size(), "Incomplete native village template volume");
            Object entityValues = get(entities, value), dataValues = get(data, value);
            String authorValue = (String) get(author, value);
            require(((List<?>) entityValues).isEmpty() && ((List<?>) get(entities, authentic)).isEmpty()
                    && dataValues instanceof org.bukkit.persistence.PersistentDataContainer
                    && ((org.bukkit.persistence.PersistentDataContainer) dataValues).isEmpty()
                    && ((org.bukkit.persistence.PersistentDataContainer) get(data, authentic)).isEmpty()
                    && Objects.equals(authorValue, get(author, authentic)), "Prepared native village entities/data/query state differs from fresh migration");
            var snapshots = new ArrayList<BlockSnapshot>(values.size());
            for (int index = 0; index < values.size(); index++) {
                require(values.get(index) instanceof StructureBlockInfo && required.get(index) instanceof StructureBlockInfo, "Unexpected native village block entry");
                var info = (StructureBlockInfo) values.get(index); var baseline = (StructureBlockInfo) required.get(index);
                require(info.pos().equals(baseline.pos()) && info.state() == baseline.state() && Objects.equals(info.nbt(), baseline.nbt()),
                        "Prepared native village block/NBT differs from pinned migration");
                var semantic = plan.customBlocks().get(position(info));
                if (semantic != null) current(info.state(), semantic);
                snapshots.add(new BlockSnapshot(info, position(info), info.state(), info.nbt() == null ? null : info.nbt().copy(), semantic,
                        semantic == null ? null : ((DelegatingBlockState) info.state()).blockState()));
            }
            QuerySnapshot query = querySnapshot(palette, values);
            return new TemplateSnapshot(value, plan, paletteValues, palette, values, List.copyOf(snapshots), entityValues, dataValues, query, authorValue);
        }
        QuerySnapshot querySnapshot(Object palette, List<?> blocks) {
            Map<?, ?> cache = (Map<?, ?>) get(paletteCache, palette);
            require(cache.getClass() == java.util.concurrent.ConcurrentHashMap.class && cache.size() <= VillagePoolPlan.MAX_POOL_ENTRIES
                    && (long) cache.size() * blocks.size() <= VillagePoolPlan.MAX_EXPANDED_WEIGHT,
                    "Unexpected or oversized native palette query cache");
            var entries = new ArrayList<CachedBlocks>(cache.size());
            long total = 0;
            for (var entry : cache.entrySet()) {
                require(entry.getKey() instanceof Block && entry.getValue() instanceof List<?>, "Unexpected native palette query cache value");
                List<?> values = (List<?>) entry.getValue(); total += values.size();
                require(values.size() <= blocks.size() && total <= VillagePoolPlan.MAX_EXPANDED_WEIGHT, "Native palette query cache exceeds capture bound");
                entries.add(new CachedBlocks(entry.getKey(), values, List.copyOf(values)));
            }
            Object jigsaws = get(cachedJigsaws, palette);
            require(jigsaws == null || jigsaws instanceof List<?> && ((List<?>) jigsaws).size() <= blocks.size(), "Unexpected native palette jigsaw cache");
            var result = new QuerySnapshot(cache, List.copyOf(entries), jigsaws, jigsaws == null ? List.of() : List.copyOf((List<?>) jigsaws));
            result.check(this, palette, blocks); return result;
        }
        void sameEntries(List<? extends VillagePoolPlan.Entry<?>> expected, List<Pair<StructurePoolElement, Integer>> actual) {
            require(expected.size() == actual.size(), "Native village raw entry count changed");
            for (int index = 0; index < expected.size(); index++)
                require(expected.get(index).element() == actual.get(index).getFirst() && expected.get(index).weight() == actual.get(index).getSecond(),
                        "Native village element/order/weight changed");
        }
        @SuppressWarnings({"unchecked", "rawtypes"}) void expanded(List<? extends VillagePoolPlan.Entry<?>> entries, List<?> values) {
            VillagePoolPlan.requireExpandedMatches((List) entries, values);
        }
        void samePrefix(VillagePoolPlan.Pool<?, ?> supplied, VillagePoolPlan.Pool<?, ?> fresh, Object processorHolder) {
            require(supplied.entries().size() == fresh.entries().size() && supplied.before().fallback() == fresh.before().fallback(), "Prepared village pool shape changed");
            int prefix = fresh.before().entries().size();
            for (int index = 0; index < prefix; index++)
                require(supplied.entries().get(index).element() == fresh.before().entries().get(index).element()
                        && supplied.entries().get(index).weight() == fresh.before().entries().get(index).weight(), "Prepared village pool prefix changed");
            if (supplied.appended()) {
                kitchen(supplied.entries().getLast(), supplied.location(), processorHolder);
            }
        }
        void kitchen(VillagePoolPlan.Entry<?> entry, String location, Object processorHolder) {
            require(entry.weight() == 4 && entry.element().getClass() == LegacySinglePoolElement.class, "Invalid prepared village kitchen element");
            var element = (StructurePoolElement) entry.element();
            Either<?, ?> name = (Either<?, ?>) get(namedTemplate, element);
            require(name.left().map(Object::toString).orElse("").equals(location)
                    && get(namedProcessors, element) == processorHolder && ((Optional<?>) get(liquid, element)).isEmpty()
                    && element.getProjection() == StructureTemplatePool.Projection.RIGID, "Prepared kitchen semantic binding changed");
        }
        VillagePoolPlan.Entry<?> kitchenEntry(List<? extends VillagePoolPlan.Entry<?>> entries, String location, Object holder) {
            VillagePoolPlan.Entry<?> found = null;
            for (var entry : entries) {
                if (entry.element().getClass() != LegacySinglePoolElement.class) continue;
                Either<?, ?> name = (Either<?, ?>) get(namedTemplate, entry.element());
                if (!name.left().map(Object::toString).orElse("").equals(location)) continue;
                require(found == null, "Duplicate prepared kitchen semantic entry"); kitchen(entry, location, holder); found = entry;
            }
            require(found != null, "Missing prepared kitchen semantic entry"); return found;
        }
        void sameProcessor(StructureProcessorList supplied, StructureProcessorList expected) {
            checkCurrentProcessor(supplied); checkCurrentProcessor(expected);
        }
        void checkCurrentProcessor(StructureProcessorList value) {
            require(value.list().size() == 1 && value.list().getFirst().getClass() == RuleProcessor.class, "Unexpected village rule processor shape");
            List<?> values = (List<?>) get(rules, value.list().getFirst());
            require(values.size() == 2, "Unexpected village processor rule count");
            String[] outputs = {"chili", "lettuce"};
            for (int index = 0; index < 2; index++) {
                require(values.get(index) instanceof ProcessorRule, "Unexpected village processor rule type");
                var rule = (ProcessorRule) values.get(index); Object predicate = get(input, rule);
                require(predicate.getClass() == RandomBlockStateMatchTest.class && Float.compare((Float) get(chance, predicate), 0.33F) == 0
                        && get(location, rule) == AlwaysTrueTest.INSTANCE && get(pos, rule) == PosAlwaysTrueTest.INSTANCE
                        && get(modifier, rule) == ProcessorRule.DEFAULT_BLOCK_ENTITY_MODIFIER, "Changed native village first-match rule");
                current((BlockState) get(matchState, predicate), "kaleidoscopecookery:tomato_crop", Map.of("age", "7"));
                current(rule.getOutputState(), "kaleidoscopecookery:" + outputs[index] + "_crop", Map.of("age", "7"));
            }
        }
        ProcessorSnapshot processorSnapshot(StructureProcessorList value) {
            Object processor = value.list().getFirst(); List<?> ruleValues = (List<?>) get(rules, processor);
            var entries = new ArrayList<RuleSnapshot>();
            for (Object entry : ruleValues) {
                ProcessorRule rule = (ProcessorRule) entry; Object predicate = get(input, rule);
                BlockState from = (BlockState) get(matchState, predicate), to = rule.getOutputState();
                entries.add(new RuleSnapshot(rule, predicate, from, to, ((DelegatingBlockState) from).blockState(), ((DelegatingBlockState) to).blockState()));
            }
            return new ProcessorSnapshot(value, value.list(), processor, ruleValues, List.copyOf(entries));
        }
        static Field field(Class<?> owner, String name, Class<?> type, boolean isFinal) {
            Field field = fieldByName(owner, name, type.getName(), isFinal);
            require(field.getType() == type, "Unexpected native village field classloader/type"); return field;
        }
        private static Field persistentDataField() {
            try {
                Field field = StructureTemplate.class.getDeclaredField("persistentDataContainer");
                require(field.getType().getName().endsWith(".persistence.CraftPersistentDataContainer")
                        && org.bukkit.persistence.PersistentDataContainer.class.isAssignableFrom(field.getType())
                        && !Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()), "Unexpected native village persistent data ABI");
                field.setAccessible(true); return field;
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot inspect native village persistent data", failure); }
        }
        static Field fieldByName(Class<?> owner, String name, String type, boolean isFinal) {
            try {
                Field field = owner.getDeclaredField(name);
                require(field.getType().getName().equals(type) && !Modifier.isStatic(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers()) == isFinal, "Unexpected native village field ABI: " + name);
                field.setAccessible(true); return field;
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native village field unavailable: " + name, failure); }
        }
        Object get(Field field, Object owner) {
            try { return field.get(owner); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot read native village field: " + field.getName(), failure); }
        }
        void set(Field field, Object owner, Object value) {
            try { field.set(owner, value); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot write native village field: " + field.getName(), failure); }
        }
    }
    private record RuleSnapshot(ProcessorRule rule, Object predicate, BlockState input, BlockState output,
                                ImmutableBlockState inputSemantic, ImmutableBlockState outputSemantic) {}
    private record ProcessorSnapshot(StructureProcessorList value, List<?> processors, Object processor, List<?> rules,
                                     List<RuleSnapshot> entries) {
        void check(Access access) {
            require(value.list() == processors && processors.size() == 1 && processors.getFirst() == processor
                    && access.get(access.rules, processor) == rules && rules.size() == entries.size(), "Staged processor identity changed");
            access.checkCurrentProcessor(value);
            for (int index = 0; index < entries.size(); index++) {
                RuleSnapshot entry = entries.get(index);
                require(rules.get(index) == entry.rule() && access.get(access.input, entry.rule()) == entry.predicate()
                        && access.get(access.matchState, entry.predicate()) == entry.input() && entry.rule().getOutputState() == entry.output()
                        && ((DelegatingBlockState) entry.input()).blockState() == entry.inputSemantic()
                        && ((DelegatingBlockState) entry.output()).blockState() == entry.outputSemantic(), "Village processor CE generation or rule changed");
            }
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
