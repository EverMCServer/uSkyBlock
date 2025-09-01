package us.talabrek.ultimateskyblock.world;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.Settings;

import java.util.List;
import java.util.Random;

public class AcidIslandChunkGenerator extends ChunkGenerator {
    private final int worldYMin = -64;

    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        chunkData.setRegion(0, worldYMin +1, 0, 16, Settings.island_height - 1, 16, Bukkit.createBlockData("water"));
    }

    @Override
    public void generateBedrock(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        chunkData.setRegion(0, worldYMin, 0, 16, -63, 16, Material.BARRIER);
    }

    @Override
    public @NotNull List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return List.of();
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return new AcidBiomeProvider();
    }

    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(world, 0, Settings.island_height, 0);
    }
}
