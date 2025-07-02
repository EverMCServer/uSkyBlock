package us.talabrek.ultimateskyblock.world;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.bukkit.util.noise.SimplexNoiseGenerator;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

public class AcidBiomeProvider extends BiomeProvider {

    public final static SimplexNoiseGenerator noise = new SimplexNoiseGenerator(0);

    public static final Biome[] tempToBiome = {Biome.FROZEN_OCEAN, Biome.COLD_OCEAN, Biome.OCEAN, Biome.LUKEWARM_OCEAN, Biome.WARM_OCEAN};

    public static int getTemperature(int x, int z) {
        double res = noise.noise(0.09 * x, 0.09 * z);
        int ret;
        if (res <= -0.5) ret = 1;
        else if (res <= 0) ret = 2;
        else if (res <= 0.6) ret = 3;
        else if (res < 1) ret = 4;
        else ret = 2;
        return ret;
    }

    @Override
    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        return tempToBiome[getTemperature(x, z)];
    }

    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        return Arrays.stream(tempToBiome).toList();
    }
}
