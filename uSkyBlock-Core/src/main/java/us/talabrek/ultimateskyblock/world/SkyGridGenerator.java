package us.talabrek.ultimateskyblock.world;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import us.talabrek.ultimateskyblock.Settings;


public class SkyGridGenerator extends ChunkGenerator {
    private final static List<Material> expensive = Arrays.asList(
            Material.LAPIS_ORE ,
            Material.DIAMOND_ORE,
            Material.EMERALD_ORE,
            Material.DIAMOND_BLOCK,
            Material.EMERALD_BLOCK,
            Material.SPAWNER,
            Material.END_STONE,
            Material.PURPUR_BLOCK,
            Material.BEACON,
            Material.CONDUIT,
            Material.IRON_BLOCK,
            Material.COAL_BLOCK,
            Material.CREEPER_HEAD,
            Material.WITHER_SKELETON_SKULL,
            Material.SKELETON_SKULL,
            Material.ZOMBIE_HEAD,
            Material.NETHER_QUARTZ_ORE
            );


	@Override
    public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, ChunkGenerator.BiomeGrid biomeGrid) {
        ChunkData result = createChunkData(world);
        for (int x = 1; x < 16; x += 4) {
            for (int z = 1; z < 16; z += 4) {
                for (int y = 0; y <= 64; y += 4) {
                    setBlock(x, y, z, random, result, Math.abs(chunkX)<14&&Math.abs(chunkZ)<14);
                }
            }
        }
        for (int x = 0; x <= 15; x++) {
            for (int z = 0; z <= 15; z++) {
                biomeGrid.setBiome(x, z, Biome.OCEAN);
            }
        }

        return result;
    }

    private void setBlock(int x, int y, int z, Random random, ChunkData result, boolean exp) {
        Material blockMat = getBlock(random);
        if (!blockMat.isBlock()) {
            result.setBlock( x, y, z, Material.BEDROCK);
            return;
        }
        if (exp){
            if(expensive.contains(blockMat)){
                result.setBlock( x, y, z, Material.STONE);
                return;
            }
        }
        result.setBlock( x, y, z, blockMat);
    }
    private Material getBlock(Random random){
        int a = random.nextInt(Settings.Skygrid_prob);        
        Material temp = Settings.Skygrid_blocks.get(a);
        if (temp == null) {
            temp = Settings.Skygrid_blocks.ceilingEntry(a).getValue();
        }
        if (temp == null) {
            temp = Settings.Skygrid_blocks.firstEntry().getValue();
        }
        return temp;
    }
    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return Collections.emptyList();
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5d, Settings.island_height, 0.5d);
    }
}