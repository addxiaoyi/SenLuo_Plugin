package net.kaleidoscope.cookery.worldgen;

/** Prepares an in-memory template only. Publishing requires a separately enforced lifecycle barrier. */
public interface VillageTemplateMigrator {
    record Prepared(String location, Object nativeTemplate, VillageTemplatePlan sidecar, int targetDataVersion) {
        public Prepared {
            if (nativeTemplate == null || sidecar == null || !sidecar.location().equals(location) || targetDataVersion != 4671)
                throw new IllegalArgumentException("Invalid unpublished village template");
        }
    }

    Prepared prepare(VillageStructureSources.Template source, Object nativeStructureTemplateManager);

    static VillageTemplateMigrator forMinecraft(String version) {
        if (!"1.21.11".equals(version)) throw new UnsupportedOperationException("Village migration supports Paper/Folia 1.21.11 only");
        try {
            return (VillageTemplateMigrator) Class.forName("net.kaleidoscope.cookery.nms.v1_21_R7.VillageTemplateMigration")
                    .getConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot load 1.21.11 village template migrator", failure);
        }
    }
}
