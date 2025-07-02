package us.talabrek.ultimateskyblock.world;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.Settings;

import java.util.List;
import java.util.Random;

public class AcidNetherChunkGenerator extends ChunkGenerator {

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        Random r = new Random();
        for (int i = 0; i < 4; i ++) {
            int x = r.nextInt(16);
            int z = r.nextInt(16);
            for (int j = 0; j < 30; j ++) {
                if (r.nextInt(2) != 0) continue;
                int tx = x + (int)(r.nextGaussian() * 2);
                int tz = z + (int)(r.nextGaussian() * 2);
                if (i % 2 == 0) {
                    chunkData.setBlock(tx, 128, tz, Material.BROWN_MUSHROOM);
                } else {
                    chunkData.setBlock(tx, 128, tz, Material.RED_MUSHROOM);
                }
            }
        }
    }

    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        chunkData.setRegion(0, 1, 0, 16, 32, 16, Material.LAVA);
    }

    @Override
    public void generateBedrock(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        chunkData.setRegion(0, 0, 0, 16, 1, 16, Material.BEDROCK);
        chunkData.setRegion(0, 127, 0, 16, 128, 16, Material.BEDROCK);
    }

    @Override
    public @NotNull List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return List.of();
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return new SingleBiomeProvider(Settings.general_defaultNetherBiome);
    }

    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(world, 0, Settings.nether_height, 0);
    }
}
