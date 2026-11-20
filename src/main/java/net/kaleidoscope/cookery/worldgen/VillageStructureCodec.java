package net.kaleidoscope.cookery.worldgen;

import java.util.List;
import java.util.Map;

/**
 * Dormant, read-only native acceptance check. It must run inside an already held real publication
 * scope after the publisher's complete native preflight, while all five candidate templates are
 * published. This check relies on that preflight for complete vanilla/NBT/processor authenticity.
 * It neither places a block nor activates
 * natural generation. Class-file/ABI checks alone do not prove an actual codec round-trip.
 */
public interface VillageStructureCodec {
    record Piece(String biome, String template, String processor, String sourceCommit, String sourceSha256, String rotation, int elementBytes,
                 String elementSha256, int pieceBytes, String pieceSha256, List<Integer> boundingBox) {
        public Piece { boundingBox = List.copyOf(boundingBox); }
    }
    record Transform(String biome, List<Integer> position, String block, String mirror, String rotation,
                     Map<String, String> before, Map<String, String> after) {
        public Transform { position = List.copyOf(position); before = Map.copyOf(before); after = Map.copyOf(after); }
    }
    record Report(String scope, int templates, int elementRoundTrips, int pieceRoundTrips, int transforms,
                  List<Piece> pieces, List<Transform> states) {
        public Report { pieces = List.copyOf(pieces); states = List.copyOf(states); }
    }

    Report inspect(Object nativeRegistryAccess, Object nativeStructureTemplateManager,
                   List<VillageTemplateMigrator.Prepared> templates, VillagePoolPreparer.Prepared pools,
                   VillagePublicationTransaction.ExclusionScope scope);

    static VillageStructureCodec forMinecraft(String version) {
        if (!"1.21.11".equals(version)) throw new UnsupportedOperationException("Village native codec inspection supports Paper/Folia 1.21.11 only");
        try {
            return (VillageStructureCodec) Class.forName("net.kaleidoscope.cookery.nms.v1_21_R7.VillageStructureCodecNative")
                    .getConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot load 1.21.11 village native codec inspector", failure);
        }
    }
}
