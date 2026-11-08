package us.talabrek.ultimateskyblock.enddragon;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;

/**
 * Per-island dragon progression, stored in the persistent data container of the main-world chunk that contains the
 * island center. Chunk PDC is saved with the chunk. Island reset/delete goes through {@code ChunkRegenerator}, which
 * removes all {@code uskyblock}-namespace PDC keys in the regenerated chunks, so these values reset with the island
 * without an extra explicit clear path.
 */
public final class DragonIslandData {
    private static final int MAX_LEVEL = 8;

    private DragonIslandData() {
    }

    public static int getMaxLevel(@NotNull IslandInfo island) {
        return Math.max(1, Math.min(MAX_LEVEL, getInt(chunk(island), maxLevelKey(), 1)));
    }

    public static void setMaxLevel(@NotNull IslandInfo island, int level) {
        setInt(chunk(island), maxLevelKey(), Math.max(1, Math.min(MAX_LEVEL, level)));
    }

    public static int getRewardLevel(@NotNull IslandInfo island) {
        return Math.max(0, Math.min(MAX_LEVEL, getInt(chunk(island), rewardLevelKey(), 0)));
    }

    public static void setRewardLevel(@NotNull IslandInfo island, int level) {
        setInt(chunk(island), rewardLevelKey(), Math.max(0, Math.min(MAX_LEVEL, level)));
    }

    @Nullable
    private static Chunk chunk(@NotNull IslandInfo island) {
        Location location = island.getIslandLocation();
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return location.getWorld().getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private static int getInt(@Nullable Chunk chunk, NamespacedKey key, int defaultValue) {
        if (chunk == null) {
            return defaultValue;
        }
        Integer value = chunk.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return value != null ? value : defaultValue;
    }

    private static void setInt(@Nullable Chunk chunk, NamespacedKey key, int value) {
        if (chunk == null) {
            return;
        }
        chunk.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, value);
    }

    private static NamespacedKey maxLevelKey() {
        return new NamespacedKey(uSkyBlock.getInstance(), "_max_dragon_level");
    }

    private static NamespacedKey rewardLevelKey() {
        return new NamespacedKey(uSkyBlock.getInstance(), "_reward_dragon_level");
    }
}
