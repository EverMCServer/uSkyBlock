package us.talabrek.ultimateskyblock.progress;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Resolves <em>virtual</em> progress keys: keys whose value is computed on demand
 * instead of stored (e.g. the number of completed challenges in a rank).
 * <p>Virtual keys are read-only — they are never persisted, added to or consumed.
 */
public interface ProgressResolver {

    /**
     * Whether the given key is a virtual key handled by this resolver.
     *
     * @param key The progress key to check.
     * @return True if the key is virtual.
     */
    boolean isVirtual(@NotNull String key);

    /**
     * Computes the current value for a virtual key of the given player's island.
     *
     * @param playerUUID The player whose island the progress belongs to.
     * @param key        The virtual progress key.
     * @return The computed progress value.
     */
    double resolve(@NotNull UUID playerUUID, @NotNull String key);
}
