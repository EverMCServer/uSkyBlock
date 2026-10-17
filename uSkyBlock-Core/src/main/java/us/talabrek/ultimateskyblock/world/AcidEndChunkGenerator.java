package us.talabrek.ultimateskyblock.world;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.Settings;

import java.util.List;
import java.util.Random;

/**
 * Chunk generator for the skyblock end world.
 * <p>
 * The world is completely empty (pure void), mirroring the skyblock approach used by the nether. There are no
 * bottom layers and no structures. Every island gets its own patch of void, connected to the overworld island by
 * the end portal redirection (see {@code EndPortalEvents}).
 */
public class AcidEndChunkGenerator extends ChunkGenerator {

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        // Pure void - nothing to generate.
    }

    @Override
    public @NotNull List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return List.of();
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return new SingleBiomeProvider(Settings.general_defaultEndBiome);
    }

    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        // The end spawn is the shared "main city" around the origin (config end.fallback.x/z, Y = end.height).
        // The plugin places no blocks there; the city is built and maintained by the server team.
        return new Location(world, Settings.end_fallbackX, Settings.end_height, Settings.end_fallbackZ);
    }
}
