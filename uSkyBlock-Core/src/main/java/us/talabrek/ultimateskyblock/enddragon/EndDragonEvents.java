package us.talabrek.ultimateskyblock.enddragon;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EnderDragonPart;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runtime rules for the dragon challenge: damage gating, dragon death handling and arena protection.
 */
@Singleton
public class EndDragonEvents implements Listener {
    private final EndDragonConfig config;
    private final DragonFightManager manager;

    @Inject
    public EndDragonEvents(@NotNull EndDragonConfig config, @NotNull DragonFightManager manager) {
        this.config = config;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!config.enabled() || !manager.hasActive()) {
            return;
        }
        Entity entity = event.getEntity();
        EnderDragon dragon = dragonOf(entity);
        if (dragon != null) {
            Player damager = playerDamager(event);
            if (!manager.shouldAllowDragonDamage(event, dragon, damager)) {
                event.setCancelled(true);
            }
            return;
        }
        if (entity instanceof EnderCrystal crystal) {
            Player damager = playerDamager(event);
            if (manager.shouldProtectCrystal(crystal, damager)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDragonAttack(EntityDamageByEntityEvent event) {
        if (!config.enabled() || !(event.getEntity() instanceof Player)) {
            return;
        }
        DragonFightData active = manager.getActive();
        if (active == null || active.dragonUuid() == null) {
            return;
        }
        EnderDragon dragon = dragonOf(event.getDamager());
        if (dragon == null) {
            return;
        }
        if (active.dragonUuid() != null && !active.dragonUuid().equals(dragon.getUniqueId())) {
            return;
        }
        double multiplier = manager.getActiveDamageMultiplier();
        if (multiplier != 1.0) {
            event.setDamage(event.getDamage() * multiplier);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDeath(EntityDeathEvent event) {
        if (!config.enabled()) {
            return;
        }
        if (event.getEntity() instanceof EnderDragon dragon) {
            manager.onDragonDeath(dragon, event);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCrystalExplode(EntityExplodeEvent event) {
        if (!config.enabled() || !config.protectArena() || !manager.hasActive()) {
            return;
        }
        if (event.getEntity() instanceof EnderCrystal
            && manager.isInArenaForProtection(event.getLocation())) {
            event.blockList().clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDragonChangeBlock(EntityChangeBlockEvent event) {
        if (!config.enabled() || !config.protectArena() || !manager.hasActive()) {
            return;
        }
        if (event.getEntity() instanceof EnderDragon
            && manager.isInArenaForProtection(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @Nullable
    private static EnderDragon dragonOf(@Nullable Entity entity) {
        if (entity instanceof EnderDragon dragon) {
            return dragon;
        }
        if (entity instanceof EnderDragonPart part) {
            return part.getParent();
        }
        return null;
    }

    @Nullable
    private static Player playerDamager(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            Object shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }
}
