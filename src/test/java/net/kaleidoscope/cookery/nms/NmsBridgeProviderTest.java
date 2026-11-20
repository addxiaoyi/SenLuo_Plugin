package net.kaleidoscope.cookery.nms;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class NmsBridgeProviderTest {
    @ParameterizedTest
    @CsvSource({"1.21.1,v1_21_R1", "1.21.2,v1_21_R2", "1.21.3,v1_21_R2",
            "1.21.4,v1_21_R3", "1.21.5,v1_21_R4", "1.21.6,v1_21_R5",
            "1.21.7,v1_21_R5", "1.21.8,v1_21_R5", "1.21.9,v1_21_R6",
            "1.21.10,v1_21_R6", "1.21.11,v1_21_R7", "26.1.1,v26_1_R1",
            "26.1.2,v26_1_R1", "26.2,v26_2_R1", "26.3,v26_3_R1"})
    void selectsDedicatedCompilerTarget(String version, String target) {
        assertEquals("net.kaleidoscope.cookery.nms." + target + ".NmsV" + target.substring(1),
                NmsBridgeProvider.implementationClassName(version));
    }

    @ParameterizedTest
    @ValueSource(strings = {"26.10", "26.20", "26.30", "26.3-rc-3", "26.4", "1.21.12"})
    void rejectsUnknownVersionsInsteadOfMatchingPrefixes(String version) {
        assertThrows(IllegalStateException.class, () -> NmsBridgeProvider.implementationClassName(version));
    }
}
