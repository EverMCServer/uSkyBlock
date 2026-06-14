package us.talabrek.ultimateskyblock.command.admin;

import com.google.inject.Inject;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.command.island.RequirePlayerCommand;
import us.talabrek.ultimateskyblock.event.ToxicEvents;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.Map;

import static dk.lockfuglsang.minecraft.po.I18nUtil.marktr;
import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

/**
 * /usb toxic — marks all blocks in the WorldEdit selection as toxic water.
 */
public class ToxicCommand extends RequirePlayerCommand {
    private static final long MAX_BLOCKS = 1_000_000;
    private static final int BATCH_SIZE = 50_000;

    private final uSkyBlock plugin;
    private final ToxicEvents toxicEvents;

    @Inject
    public ToxicCommand(@NotNull uSkyBlock plugin, @NotNull ToxicEvents toxicEvents) {
        super("toxic", "usb.admin.toxic", marktr("mark WE selection as toxic water"));
        this.plugin = plugin;
        this.toxicEvents = toxicEvents;
    }

    @Override
    protected boolean doExecute(String alias, Player player, Map<String, Object> data, String... args) {
        LocalSession session;
        try {
            session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
        } catch (Exception e) {
            player.sendMessage(tr("§cWorldEdit is not available."));
            return true;
        }

        Region selection;
        try {
            selection = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
        } catch (Exception e) {
            player.sendMessage(tr("§cFailed to get WorldEdit selection."));
            return true;
        }

        if (selection == null || selection.getVolume() <= 0) {
            player.sendMessage(tr("§cYou don't have a WorldEdit selection. Use §e//wand§c to select an area first."));
            return true;
        }

        long volume = selection.getVolume();
        if (volume > MAX_BLOCKS) {
            player.sendMessage(tr("§cSelection too large: §e{0}§c blocks. Maximum is §e{1}§c blocks.", volume, MAX_BLOCKS));
            return true;
        }

        BlockVector3 min = selection.getMinimumPoint();
        BlockVector3 max = selection.getMaximumPoint();
        int minX = min.getBlockX(), minY = min.getBlockY(), minZ = min.getBlockZ();
        int maxX = max.getBlockX(), maxY = max.getBlockY(), maxZ = max.getBlockZ();
        int sizeX = maxX - minX + 1;
        int sizeZ = maxZ - minZ + 1;
        long total = (long) sizeX * (maxY - minY + 1) * sizeZ;

        World world = player.getWorld();
        player.sendMessage(tr("§aMarking §e{0}§a blocks as toxic water...", total));

        new BukkitRunnable() {
            long processed = 0;

            @Override
            public void run() {
                long end = Math.min(processed + BATCH_SIZE, total);
                for (long i = processed; i < end; i++) {
                    int x = minX + (int) (i % sizeX);
                    int z = minZ + (int) ((i / sizeX) % sizeZ);
                    int y = minY + (int) (i / (sizeX * sizeZ));
                    Block b = world.getBlockAt(x, y, z);
                    toxicEvents.setToxic(b, true);
                }
                processed = end;
                if (processed >= total) {
                    player.sendMessage(tr("§aDone! §e{0}§a blocks marked as toxic water.", total));
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return true;
    }
}
