package us.talabrek.ultimateskyblock.command.island;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.Map;

import static dk.lockfuglsang.minecraft.po.I18nUtil.marktr;
import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

/**
 * /is slime — checks the 7×7 chunk area around the player
 * and reports which chunks are slime chunks.
 */
@Singleton
public class SlimeCommand extends RequirePlayerCommand {

    @Inject
    public SlimeCommand(uSkyBlock plugin) {
        super("slime", "usb.island.slime", marktr("check slime chunks around you"));
    }

    @Override
    protected boolean doExecute(String alias, Player player, Map<String, Object> data, String... args) {
        Chunk center = player.getLocation().getChunk();
        int cx = center.getX();
        int cz = center.getZ();
        World world = player.getWorld();

        // Scan 7×7 chunk area and count slime chunks
        int slimeCount = 0;
        boolean[][] grid = new boolean[7][7]; // [dz][dx], true = slime chunk
        for (int dz = -3; dz <= 3; dz++) {
            for (int dx = -3; dx <= 3; dx++) {
                int x = cx + dx;
                int z = cz + dz;
                if (world.getChunkAt(x, z).isSlimeChunk()) {
                    grid[dz + 3][dx + 3] = true;
                    slimeCount++;
                }
            }
        }

        boolean centerIsSlime = grid[3][3];

        player.sendMessage(tr("§6§l=== §aSlime Chunk Scan (7×7) §6§l==="));
        player.sendMessage(tr("§7Your chunk: §e[{0}, {1}]§7 — {2}",
            cx, cz,
            centerIsSlime ? tr("§a§lSLIME CHUNK §e§l✨")
                          : tr("§cnot a slime chunk")
        ));

        // ── 7×7 grid:   N embedded in top border, S in bottom, W/E on center row
        //   x=non-slime (dark gray),  o=slime (green),  @=player
        final NamedTextColor BORDER = NamedTextColor.GRAY;
        final NamedTextColor LABEL = NamedTextColor.YELLOW;

        // Top border: ----N----
        player.sendMessage(Component.empty()
            .append(Component.text("----", BORDER))
            .append(Component.text("N", LABEL))
            .append(Component.text("----", BORDER)));

        for (int dz = -3; dz <= 3; dz++) {
            int rowIdx = dz + 3;
            Component row = Component.empty();

            // Left border; center row has W embedded
            if (dz == 0) {
                row = row.append(Component.text("W", LABEL));
            }
            row = row.append(Component.text("|", BORDER));

            // 7 cells, no spacing
            for (int dx = -3; dx <= 3; dx++) {
                int colIdx = dx + 3;
                boolean isSlime = grid[rowIdx][colIdx];
                boolean isCenter = (dx == 0 && dz == 0);

                if (isCenter) {
                    row = row.append(Component.text("@", isSlime ? NamedTextColor.GREEN : LABEL));
                } else if (isSlime) {
                    row = row.append(Component.text("o", NamedTextColor.GREEN));
                } else {
                    row = row.append(Component.text("x", NamedTextColor.DARK_GRAY));
                }
            }

            // Right border; center row has E embedded
            row = row.append(Component.text("|", BORDER));
            if (dz == 0) {
                row = row.append(Component.text("E", LABEL));
            }

            player.sendMessage(row);
        }

        // Bottom border: ----S----
        player.sendMessage(Component.empty()
            .append(Component.text("----", BORDER))
            .append(Component.text("S", LABEL))
            .append(Component.text("----", BORDER)));

        player.sendMessage(tr("§7x§8=normal  §ao§7=slime  §a@§7=you(slime)  §e@§7=you"));
        player.sendMessage(tr("§7Found §a{0}§7 slime chunk(s) out of 49 scanned.", slimeCount));
        return true;
    }
}
