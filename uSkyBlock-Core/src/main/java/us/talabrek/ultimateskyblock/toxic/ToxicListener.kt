package us.talabrek.ultimateskyblock.toxic

import com.google.inject.Inject
import com.google.inject.Singleton
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerBucketFillEvent
import us.talabrek.ultimateskyblock.uSkyBlock

@Singleton
class ToxicListener @Inject constructor(
    val plugin: uSkyBlock,
) : Listener {
    @EventHandler
    fun onPlayerBucketFillEvent(event: PlayerBucketFillEvent) {
        if (event.bucket == Material.WATER_BUCKET) {
            plugin.logger.info("Bucket getting toxic")
        }
    }
}
