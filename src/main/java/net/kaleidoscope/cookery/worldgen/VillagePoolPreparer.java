package net.kaleidoscope.cookery.worldgen;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Dormant native construction primitive, independent of the general NMS bridges. No caller currently
 * activates it. A separate cold-start lifecycle must register the stable processor key, establish
 * stable CE resources and no generation readers, then publish templates, processor and all five pools
 * consistently. This interface provides neither publication nor an online reload barrier.
 */
public interface VillagePoolPreparer {
    record Prepared(String processorKey, Object namedProcessorHolder, Object expectedProcessorValue,
                    Object stagedProcessorValue, VillagePoolPlan<?, ?> plan,
                    Map<String, Object> expectedPoolValues, Map<String, Object> stagedPoolValues) {
        public Prepared {
            if (!VillagePoolPlan.PROCESSOR.equals(processorKey)) throw new IllegalArgumentException("Unstable village processor key");
            Objects.requireNonNull(namedProcessorHolder, "Named village processor holder");
            Objects.requireNonNull(expectedProcessorValue, "Existing village processor");
            Objects.requireNonNull(stagedProcessorValue, "Staged village processor");
            Objects.requireNonNull(plan, "Village pool plan");
            if (!expectedPoolValues.keySet().equals(plan.pools().keySet()) || !stagedPoolValues.keySet().equals(plan.pools().keySet()))
                throw new IllegalArgumentException("Incomplete staged village pools");
            expectedPoolValues = immutable(expectedPoolValues);
            stagedPoolValues = immutable(stagedPoolValues);
        }
        public boolean processorChangeRequired() { return expectedProcessorValue != stagedProcessorValue; }
        private static Map<String, Object> immutable(Map<String, Object> values) {
            values.values().forEach(Objects::requireNonNull);
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    Prepared prepare(Object nativeRegistryAccess);

    static VillagePoolPreparer forMinecraft(String version) {
        if (!"1.21.11".equals(version)) throw new UnsupportedOperationException("Village pool preparation supports Paper/Folia 1.21.11 only");
        try {
            return (VillagePoolPreparer) Class.forName("net.kaleidoscope.cookery.nms.v1_21_R7.VillagePoolPreparation")
                    .getConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot load 1.21.11 village pool preparer", failure);
        }
    }
}
