package net.kaleidoscope.cookery.nms;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** The classified policy is opt-in and does not add requirements to the existing nine NMS bridges. */
public final class NativeSickleAccessProvider {
    private NativeSickleAccessProvider() {}

    private static final class Holder {
        private static final NativeSickleAccess ACCESS = create(Bukkit.getBukkitVersion());
    }

    public static NativeSickleAccess access() { return Holder.ACCESS; }

    public static void registerObserver(JavaPlugin plugin) {
        if (supportedVersion(Bukkit.getBukkitVersion())) NativeSickleDamageObserver.register(plugin);
    }

    static boolean supportedVersion(String version) { return version != null && version.split("-", 2)[0].equals("1.21.11"); }

    static NativeSickleAccess create(String version) {
        String implementation = implementationClassName(version);
        try {
            return (NativeSickleAccess) Class.forName(implementation).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("source-classified sickle requires the verified Paper/Folia 1.21.11 native companion", failure);
        }
    }

    static String implementationClassName(String version) {
        if (!supportedVersion(version))
            throw new IllegalArgumentException("source-classified sickle is supported only on Paper/Folia 1.21.11; "
                    + "use vanilla-policy: legacy-ageable for an existing explicit whitelist on " + version);
        return "net.kaleidoscope.cookery.nms.v1_21_R7.SourceSickleNative";
    }
}
