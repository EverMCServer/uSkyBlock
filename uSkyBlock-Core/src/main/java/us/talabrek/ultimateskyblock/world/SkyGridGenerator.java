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

    private final static List<Material> needDirt = Arrays.asList(
            Material.ACACIA_SAPLING,
            Material.ALLIUM,
            Material.AZURE_BLUET,
            Material.BEETROOTS,
            Material.BEETROOTS,
            Material.BIRCH_SAPLING,
            Material.BLUE_ORCHID,
            Material.BROWN_MUSHROOM,
            Material.DANDELION,
            Material.DARK_OAK_SAPLING,
            Material.DEAD_BUSH,
            Material.FERN,
            Material.GRASS,
            Material.JUNGLE_SAPLING,
            Material.LARGE_FERN,
            Material.LILAC,
            Material.OAK_SAPLING,
            Material.ORANGE_TULIP,
            Material.OXEYE_DAISY,
            Material.PEONY,
            Material.PINK_TULIP,
            Material.POPPY,
            Material.RED_MUSHROOM,
            Material.RED_TULIP,
            Material.ROSE_BUSH,
            Material.SPRUCE_SAPLING,
            Material.SUGAR_CANE,
            Material.SUNFLOWER,
            Material.SUNFLOWER,
            Material.TALL_GRASS,
            Material.WHEAT,
            Material.WHITE_TULIP
            );

    @Override
    public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, ChunkGenerator.BiomeGrid biomeGrid) {
        
        ChunkData result = createChunkData(world);
        for (int x = 1; x < 16; x += 4) {
            for (int z = 1; z < 16; z += 4) {
                for (int y = 0; y <= 64; y += 4) {
                    setBlock(x, y, z, random, result);
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

    private void setBlock(int x, int y, int z, Random random, ChunkData result) {
        Material blockMat = getBlock(random);
        if (!blockMat.isBlock()) {
            result.setBlock( x, y, z, Material.BEDROCK);
            return;
        }
        if (y == 0 && (needDirt.contains(blockMat) || blockMat == Material.CACTUS || blockMat == Material.LAVA || blockMat == Material.WATER)) {
            result.setBlock( x, y, z, Material.BEDROCK);
            return;
        }
        if (needDirt.contains(blockMat)) {
            result.setBlock( x, y, z, Material.DIRT);
            result.setBlock( x, y+1, z, blockMat);
            if (blockMat.equals(Material.SUGAR_CANE)) {
                result.setBlock(x+1, y, z, Material.WATER);
            }
        } else {
            switch (blockMat) {
            case CACTUS:
                result.setBlock( x, y, z, Material.SAND);
                result.setBlock( x, y-1, z, Material.SANDSTONE);
                result.setBlock( x, y+1, z, blockMat);
                break;
            case NETHER_WART:
                result.setBlock( x, y, z, Material.SOUL_SAND);
                result.setBlock( x, y+1, z, blockMat);
                break;
            case END_ROD:
                result.setBlock( x, y, z, Material.END_STONE);
                result.setBlock( x, y+1, z, blockMat);
                break;
            case CHORUS_PLANT:
                result.setBlock( x, y, z, Material.END_STONE);
                result.setBlock( x, y+1, z, blockMat);
                break;
            default:
                result.setBlock( x, y, z, blockMat);
            }
        }
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