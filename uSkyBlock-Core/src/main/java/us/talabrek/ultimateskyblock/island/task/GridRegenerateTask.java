package us.talabrek.ultimateskyblock.island.task;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.ChunkGenerator.BiomeGrid;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import us.talabrek.ultimateskyblock.uSkyBlock;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import us.talabrek.ultimateskyblock.util.LogUtil;
import us.talabrek.ultimateskyblock.world.SkyGridGenerator;

import java.util.Random;
import java.util.logging.Level;

public class GridRegenerateTask{
    private final uSkyBlock plugin;
    private BukkitTask task;
    private ChunkGenerator cg;
    private World world;
    private int regen_count;
    public GridRegenerateTask(uSkyBlock plugin){
        this.plugin = plugin;
        cg = new SkyGridGenerator();
        world = plugin.getWorldManager().getGridWorld();
        regen_count = 0;
    }
    
    public void GridRegen(){
        if(plugin.getConfig().getInt("skygrid.regen",0)==1){
            LogUtil.log(Level.INFO,"Another regenerate task is ongoing");
            return;
        }
        plugin.getConfig().set("skygrid.regen",1);

        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        task = scheduler.runTaskTimer(plugin, () -> {
            if (regen_count<1024) {
                Random random = new Random();
                ChunkData chunkData = cg.generateChunkData(world, random, regen_count/32 - 16, regen_count%32 - 16, new GridBiomeGrid());

                for (int x = 1; x < 16; x+=4) {
                    for (int z = 1; z < 16; z+=4) {
                        for (int y = 0; y < 64; y+=4) {
                            world.getChunkAt(regen_count/32 - 16, regen_count%32 - 16).getBlock(x, y, z).setBlockData(chunkData.getBlockData(x, y, z));
                        }
                    }
                }
                if(regen_count%10==0)LogUtil.log(Level.INFO, "regen: "+regen_count);
                regen_count++;
            } else {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.sendMessage("空岛资源世界生成完毕");
                }
                plugin.getConfig().set("skygrid.regen",0);
                task.cancel();
            }
        }, 0L, 1L);
    }
    static class GridBiomeGrid implements BiomeGrid {
        private Biome defaultBiome = Biome.OCEAN;

        GridBiomeGrid(){
            defaultBiome = Biome.OCEAN;
        }
        @Override
        public Biome getBiome(int x, int z) {
            return defaultBiome;
        }

        @Override
        public void setBiome(int x, int z, Biome bio) {
            defaultBiome = bio;
        }
    }
}