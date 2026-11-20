package net.kaleidoscope.cookery.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

class CatalogJsonCompatibilityTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"x\":TRUE}", "{\"x\":False}", "{\"x\":NULL}", "{\"x\":\"a\nb\"}",
            "{\"x\":\"a\tb\"}", "{\"x\":\"\\'\"}", "{\"x\":\"\\q\"}", "{\"x\":\"\\uZZZZ\"}",
            "{\"x\":01}", "{\"x\":+1}", "{\"x\":.5}", "{\"x\":1.}", "{\"x\":NaN}",
            "{\"x\":Infinity}", "{x:1}", "{'x':1}", "{\"x\":1,}", "{\"x\":[1,]}",
            "{\"x\":1/*comment*/}", "{\"x\":1}//comment", "{\"x\":1}{}", "{\"x\":1,\"x\":2}",
            "{\"x\":true false}", "{\"x\":\"unterminated}", "{\"x\":1\u000b}", "[]"
    })
    void rejectsInvalidJsonAcrossGsonVersions(String source) {
        assertThrows(Exception.class, () -> CatalogJson.read(new StringReader(source)));
    }

    @Test void acceptsJsonEscapesLiteralsAndExponentNumbers() throws Exception {
        var parsed = CatalogJson.read(new StringReader(" {\"text\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u4e2d\","
                + "\"values\":[true,false,null,-0,1.25e+2,-2E-1]}\r\n"));
        assertEquals("\"\\/\b\f\n\r\t中", parsed.get("text").getAsString());
        assertEquals(6, parsed.getAsJsonArray("values").size());
        assertEquals(125, parsed.getAsJsonArray("values").get(4).getAsDouble());
    }
}
