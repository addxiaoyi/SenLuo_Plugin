package net.kaleidoscope.cookery.worldgen;

import java.util.List;

/**
 * Dormant native publication adapter. It supplies no lifecycle hook or exclusion implementation.
 * The caller must already hold a real scope throughout staging, publication and any rollback. That
 * scope must exclude generation readers, CE/native reload and other cache/registry writers, and
 * certify that the supplied registry access and template manager remain the server's active roots.
 * A CE resource lease or a boolean readiness flag alone does not satisfy this contract.
 */
public interface VillagePublisher {
    VillagePublicationTransaction stage(Object nativeRegistryAccess, Object nativeStructureTemplateManager,
                                        List<VillageTemplateMigrator.Prepared> templates,
                                        VillagePoolPreparer.Prepared pools,
                                        VillagePublicationTransaction.ExclusionScope scope);

    static VillagePublisher forMinecraft(String version) {
        if (!"1.21.11".equals(version)) throw new UnsupportedOperationException("Village publication supports Paper/Folia 1.21.11 only");
        try {
            return (VillagePublisher) Class.forName("net.kaleidoscope.cookery.nms.v1_21_R7.VillagePublication")
                    .getConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot load 1.21.11 village publisher", failure);
        }
    }
}
