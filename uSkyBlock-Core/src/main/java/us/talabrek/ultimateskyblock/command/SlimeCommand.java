package us.talabrek.ultimateskyblock.command;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dk.lockfuglsang.minecraft.command.BaseCommandExecutor;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dk.lockfuglsang.minecraft.po.I18nUtil.marktr;
import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

/**
 * /slime command — checks the 5×5 chunk area around the player
 * and reports which chunks are slime chunks.
 */
@Singleton
public class SlimeCommand extends BaseCommandExecutor {

    @Inject
    public SlimeCommand() {
        super("slime|slimechunk", "usb.island.slime", marktr("check slime chunks around you"));
    }

    @Override
    public boolean execute(CommandSender sender, String alias, Map<String, Object> data, String... args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(tr("§4This command can only be executed by a player"));
            return true;
        }

        Chunk center = player.getLocation().getChunk();
        int cx = center.getX();
        int cz = center.getZ();
        World world = player.getWorld();

        // Scan 5×5 chunk area (±2 from player chunk)
        List<String> slimeChunks = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if (world.getChunkAt(x, z).isSlimeChunk()) {
                    slimeChunks.add(x + "," + z);
                }
            }
        }

        boolean centerIsSlime = slimeChunks.contains(cx + "," + cz);

        player.sendMessage(tr("§6§l=== §aSlime Chunk Scan (5×5) §6§l==="));
        player.sendMessage(tr("§7Your chunk: §e[{0}, {1}]§7 — {2}",
            cx, cz,
            centerIsSlime ? tr("§a§lSLIME CHUNK §e§l✨")
                          : tr("§cnot a slime chunk")
        ));

        if (slimeChunks.isEmpty()) {
            player.sendMessage(tr("§cNo slime chunks found in the 5×5 area."));
        } else {
            StringBuilder sb = new StringBuilder(tr("§aSlime chunks: "));
            for (int i = 0; i < slimeChunks.size(); i++) {
                if (i > 0) sb.append("§7, ");
                sb.append("§e[").append(slimeChunks.get(i)).append("]");
            }
            player.sendMessage(sb.toString());
            player.sendMessage(tr("§7Found §a{0}§7 slime chunk(s) out of 25 scanned.", slimeChunks.size()));
        }

        return true;
    }
}
