package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SpawnEggMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import us.talabrek.ultimateskyblock.api.IslandInfo;
import us.talabrek.ultimateskyblock.handler.WorldGuardHandler;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;
import us.talabrek.ultimateskyblock.util.LogUtil;

import java.util.*;
import java.util.logging.Level;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;
import static us.talabrek.ultimateskyblock.util.LogUtil.log;

/**
 * Responsible for controlling spawns on uSkyBlock islands.
 */
@Singleton
public class SpawnEvents implements Listener {
    private static final Set<Action> RIGHT_CLICKS = Set.of(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK);

    private HashMap<String, Boolean> newIsland = new HashMap<>();
    private final uSkyBlock plugin;

    private boolean phantomsInOverworld;
    private boolean phantomsInNether;

    @Inject
    public SpawnEvents(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
        phantomsInOverworld = plugin.getConfig().getBoolean("options.spawning.phantoms.overworld", true);
        phantomsInNether = plugin.getConfig().getBoolean("options.spawning.phantoms.nether", false);
    }

    @EventHandler
    public void onSpawnEggEvent(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.useItemInHand() == Event.Result.DENY || !plugin.getWorldManager().isSkyWorld(player.getWorld())) {
            return; // Bail out, we don't care
        }
        if (player.hasPermission("usb.mod.bypassprotection") || player.isOp()) {
            return;
        }
        ItemStack item = event.getItem();
        if (RIGHT_CLICKS.contains(event.getAction()) && item != null && item.getItemMeta() instanceof SpawnEggMeta) {
            if (!plugin.playerIsOnIsland(player)) {
                event.setCancelled(true);
                plugin.notifyPlayer(player, tr("\u00a7eYou can only use spawn-eggs on your own island."));
                return;
            }

            checkLimits(event, getSpawnEggType(item), player.getLocation());
            if (event.useItemInHand() == Event.Result.DENY) {
                plugin.notifyPlayer(player, tr("\u00a7cYou have reached your spawn-limit for your island."));
                event.setUseItemInHand(Event.Result.DENY);
                event.setUseInteractedBlock(Event.Result.DENY);
            }
        }
    }

    private static @Nullable EntityType getSpawnEggType(@NotNull ItemStack itemStack) {
        if (itemStack.getItemMeta() instanceof SpawnEggMeta spawnEggMeta) {
            EntitySnapshot spawnedEntity = spawnEggMeta.getSpawnedEntity();
            if (spawnedEntity != null) {
                return spawnedEntity.getEntityType();
            } else {
                String key = itemStack.getType().getKey().toString();
                String entityKey = key.replace("_spawn_egg", "");
                NamespacedKey namespacedKey = Objects.requireNonNull(NamespacedKey.fromString(entityKey));
                return Registry.ENTITY_TYPE.get(namespacedKey);
            }
        } else {
            return null;
        }
    }

    private int fastpos(int pos){
        pos+=64;
        return (pos<0)?((pos+1)/128):(pos/128);
    }


    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event == null || !plugin.getWorldManager().isSkyAssociatedWorld(event.getLocation().getWorld())) {
            return; // Bail out, we don't care
        }
        if (event.getSpawnReason().equals(CreatureSpawnEvent.SpawnReason.SPAWNER_EGG)) {
            return; // Allow it, the above method would have blocked it if it should be blocked.
        }
        if (event.getEntity() instanceof Phantom) {
            String island = fastpos(event.getLocation().getBlockX()) + "," + fastpos(event.getLocation().getBlockZ());

            if(newIsland.get(island) == null){
                IslandInfo is = plugin.getIslandInfo(event.getLocation());
                if(is != null){
                    PlayerInfo pi = plugin.getPlayerInfo(is.getLeader());
                    if (pi != null && pi.checkChallenge("page1finished")==0){
                        // newbie protection
                        event.setCancelled(true);
                        pi = null; is = null;
                        newIsland.put(island, true);
                        log(Level.INFO, "Add inf Phantom Protection: "+island+" : protect");
                        return;
                    }
                    pi = null; is = null;
                    newIsland.put(island, false);
                    log(Level.INFO, "Add inf Phantom Protection: "+island+" : Not-protect");
                }
            }else{
                if (newIsland.get(island) == true){
                    event.setCancelled(true);
                    return;
                }
            }
        }
        if (event.getEntity() instanceof WaterMob) {
            Random r = new Random();
            if (r.nextInt(20) != 0){
                event.setCancelled(true);
                return;
            }
        }
        if (event.getEntity() instanceof ArmorStand){
            return;
        }
        checkLimits(event, event.getEntity().getType(), event.getLocation());
        if (event.getEntity() instanceof WaterMob) {
            Location loc = event.getLocation();
            if(doPrismarineRoof(loc))
                event.setCancelled(true);
        }
        if (!event.isCancelled() && event.getEntity() instanceof Enderman) {
            Location loc = event.getLocation();

            if(isPurpurFloor(loc)) {
                if (isEndBiome(loc)) {
                    Random r = new Random();
                    if (r.nextInt(10) == 0) {
                        loc.getWorld().spawnEntity(loc, EntityType.SHULKER);
                        event.setCancelled(true);
                    }
                } else {
                    loc.getWorld().spawnEntity(loc, EntityType.SHULKER);
                    event.setCancelled(true);
                }
            }
        }
    }

    private boolean isPurpurFloor(Location loc){
        List<Material> purpurBlocks = Arrays.asList(Material.PURPUR_BLOCK, Material.PURPUR_PILLAR, Material.PURPUR_SLAB);
        loc.setY(loc.getY()-1);
        boolean ret=purpurBlocks.contains(loc.getBlock().getType());
        loc.setY(loc.getY()+1);
        return ret;
    }

    private boolean doPrismarineRoof(Location loc) {
        List<Material> prismarineBlocks = Arrays.asList(Material.PRISMARINE, Material.PRISMARINE_BRICKS, Material.DARK_PRISMARINE);
        Location tloc = loc.clone();
        if(tloc.getBlockY()<47 || tloc.getBlockY()>64)
            return false;
        while(tloc.getBlockY()<=70){
            if (tloc.getBlock().getType() == Material.WATER){
                tloc.add(0,1,0);
            }else{
                if(prismarineBlocks.contains(tloc.getBlock().getType())){
                    Random r = new Random();
                    if (r.nextInt(5) == 0){
                        if(r.nextInt(1000) == 0){
                            if(r.nextInt(1000)==0){
                                Drowned drowned= (Drowned) loc.getWorld().spawnEntity(loc, EntityType.DROWNED);
                                drowned.getEquipment().setItemInMainHand(new ItemStack(Material.TRIDENT));
                                LogUtil.log(Level.INFO, java.time.Clock.systemUTC().instant()+" "+plugin.getIslandInfo(loc).getLeader()+" RANDOM TRIDENT");
                            }
                            else{
                                Drowned drowned= (Drowned) loc.getWorld().spawnEntity(loc, EntityType.DROWNED);
                                if(drowned.getEquipment().getItemInMainHand().equals(new ItemStack(Material.TRIDENT))){
                                    LogUtil.log(Level.INFO,java.time.Clock.systemUTC().instant()+" "+plugin.getIslandInfo(loc).getLeader()+" DROWNED TRIDENT");
                                }
                                else{
                                    LogUtil.log(Level.INFO,java.time.Clock.systemUTC().instant()+" "+plugin.getIslandInfo(loc).getLeader()+" DROWNED NO TRIDENT");
                                }
                            }
                        }
                        else{
                            loc.getWorld().spawnEntity(loc, EntityType.GUARDIAN);
                        }
                    }
                    return true;
                }else if(tloc.getBlock().getType() == Material.SEA_LANTERN){
                    Random r = new Random();
                    if (r.nextInt(50) == 0)
                        loc.getWorld().spawnEntity(loc, EntityType.ELDER_GUARDIAN);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private boolean isEndBiome(Location loc) {
        return loc.getWorld().getBiome(loc.getBlockX(), loc.getBlockZ())==Biome.THE_END;
    }

    private void checkLimits(Cancellable event, EntityType entityType, Location location) {
        if (entityType == null) {
            return; // Only happens on "other-plugins", i.e. EchoPet
        }
        String islandName = WorldGuardHandler.getIslandNameAt(location);
        if (islandName == null) {
            event.setCancelled(true); // Only allow spawning on active islands...
            return;
        }
        IslandInfo islandInfo = plugin.getIslandInfo(islandName);
        if (islandInfo == null) {
            // Disallow spawns on inactive islands
            event.setCancelled(true);
            return;
        }
        if (!plugin.getLimitLogic().canSpawn(entityType, islandInfo)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPhantomSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Phantom) ||
                event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }

        World spawnWorld = event.getEntity().getWorld();
        if (!phantomsInOverworld && plugin.getWorldManager().isSkyWorld(spawnWorld)) {
            event.setCancelled(true);
        }

        if (!phantomsInNether && plugin.getWorldManager().isSkyNether(spawnWorld)) {
            event.setCancelled(true);
        }
    }

    /**
     * Changes the setting that allows Phantoms to spawn in the overworld. Used for testing purposes.
     * @param state True/enabled means spawning is allowed, false disallowed.
     */
    void setPhantomsInOverworld(boolean state) {
        this.phantomsInOverworld = state;
    }

    /**
     * Changes the setting that allows Phantoms to spawn in the nether. Used for testing purposes.
     * @param state True/enabled means spawning is allowed, false disallowed.
     */
    void setPhantomsInNether(boolean state) {
        this.phantomsInNether = state;
    }
}
