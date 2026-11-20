package net.kaleidoscope.cookery.nms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class NativeSickleAccessProviderTest {
    @Test void onlyTheVerifiedTargetUsesTheIndependentCompanion() {
        assertEquals("net.kaleidoscope.cookery.nms.v1_21_R7.SourceSickleNative",
                NativeSickleAccessProvider.implementationClassName("1.21.11-R0.1-SNAPSHOT"));
        assertEquals("net.kaleidoscope.cookery.nms.v1_21_R7.SourceSickleNative",
                NativeSickleAccessProvider.implementationClassName("1.21.11"));
    }

    @ParameterizedTest @ValueSource(strings = {"1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.8", "1.21.10", "26.1.2", "26.2"})
    void otherExistingBridgeVersionsCannotSilentlyFallBackToTheWrongNativeCategory(String version) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> NativeSickleAccessProvider.implementationClassName(version));
        assertTrue(failure.getMessage().contains("legacy-ageable"));
    }

    @Test void missingVersionFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> NativeSickleAccessProvider.implementationClassName(null));
    }
}
