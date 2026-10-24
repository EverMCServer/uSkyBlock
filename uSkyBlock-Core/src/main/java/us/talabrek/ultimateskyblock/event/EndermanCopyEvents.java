package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Enderman;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.uSkyBlock;

/**
 * End-game mechanic: in the skyblock end world an enderman picks up a <em>copy</em> of the block it targets, the
 * original block stays where it is.
 *
 * <p>Vanilla fires {@link EntityChangeBlockEvent} for a pickup inside {@code EndermanTakeBlockGoal.tick()}, and
 * only removes the block (and stores the carried block state on the enderman) when that event is not cancelled.
 * Cancelling it therefore keeps the original block in place - we only have to hand the copy to the enderman
 * ourselves, because vanilla never reaches its {@code setCarriedBlock} call.</p>
 *
 * <p>Afterwards the enderman behaves like vanilla again: after the usual random delay it puts the copy down
 * somewhere (which consumes it), picks up the next copy, and so on. Since every pickup leaves the source block
 * behind, one source block can be copied indefinitely.</p>
 *
 * <p>Only the end world is affected; the overworld and the nether keep vanilla behaviour. By default only blocks
 * inside an island region are copied, so the shared end "main city" around the origin (not an island) keeps
 * vanilla behaviour as well.</p>
 *
 * @see <a href="https://zh.minecraft.wiki/w/%E6%9C%AB%E5%BD%B1%E4%BA%BA">Enderman - block carrying behaviour</a>
 */
@Singleton
public class EndermanCopyEvents implements Listener {

    private final uSkyBlock plugin;
    private final boolean enabled;
    private final boolean onlyOnIslands;

    @Inject
    public EndermanCopyEvents(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("end.enderman-copy.enabled", true);
        this.onlyOnIslands = plugin.getConfig().getBoolean("end.enderman-copy.only-on-islands", true);
    }

    /**
     * Turns an enderman pickup into a copy: the source block stays and the enderman still carries the block.
     *
     * <p>{@link EventPriority#LOWEST} so the copy is made before protection plugins (WorldGuard cancels enderman
     * block changes for its {@code enderman-grief} flag at its own priority) get a chance to cancel the pickup.</p>
     *
     * @param event the block change event fired when an enderman picks a block up.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEndermanPickUpBlock(@NotNull EntityChangeBlockEvent event) {
        if (!enabled
            || event.getTo() != Material.AIR // "the block becomes air" is a pickup, anything else is a placement
            || !(event.getEntity() instanceof Enderman enderman)) {
            return;
        }
        Block block = event.getBlock();
        if (!plugin.getWorldManager().isSkyEnd(block.getWorld())) {
            return;
        }
        if (onlyOnIslands && plugin.getIslandInfo(block.getLocation()) == null) {
            return;
        }
        // Read the block before cancelling. Vanilla normalises the carried state to the block's default state,
        // keeping the full block state is closer to "a copy of the block".
        BlockData copy = block.getBlockData();
        event.setCancelled(true); // the original block stays
        if (enderman.getCarriedBlock() == null) {
            enderman.setCarriedBlock(copy);
        }
    }
}
