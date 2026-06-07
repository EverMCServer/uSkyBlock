package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.Settings;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.*;

@Singleton
public class ToxicEvents implements Listener {
    private final uSkyBlock plugin;
    private final NamespacedKey[] key;
    private final long[] toxicData;
    private final long[] cleanData;
    public final Enchantment acidEnchant = Enchantment.getByKey(new NamespacedKey("acidwater", "acid"));
    public final Enchantment antiAcidEnchant = Enchantment.getByKey(new NamespacedKey("acidwater", "anti_acid"));
    private static final Set<Location> toSpread = new HashSet<>();
    //  uuid -> (#ticks to next damage, in toxic now?)
    private final Map<UUID, Pair<Integer, Boolean>> dmgTick = new HashMap<>();
    //  uuid -> #damage blocked since last coal taken
    private final Map<UUID, Integer> coalCount = new HashMap<>();


    @Inject
    public ToxicEvents(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey[384];
        for (int i = 0; i < 384; i++) {
            this.key[i] = new NamespacedKey(plugin, "T" + (i-64));
        }
        this.toxicData = new long[]{~0L, ~0L, ~0L, ~0L};
        this.cleanData = new long[]{0L, 0L, 0L, 0L};
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::doToxicSpreadTick, 20L, 1L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> doToxicDamageTick(plugin.getServer()), 20L, 1L);
    }

    public static boolean isWater(Block b) {
        return b.getType() == Material.WATER || b.getType() == Material.BUBBLE_COLUMN ||
               (b.getBlockData() instanceof Waterlogged wld && wld.isWaterlogged());
    }

    public boolean isToxic(Block b) {
        if (!plugin.getWorldManager().isSkyWorld(b.getWorld())) {
            return false;
        }
        if (!isWater(b)) {
            return false;
        }
        int cx = b.getX() & 0x0F;
        int cz = b.getZ() & 0x0F;
        int cy = b.getY() + 64;
        boolean is_sea_area = -64 < b.getY() && b.getY() < Settings.island_height;

        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        long[] data = pdc.get(key[cy], PersistentDataType.LONG_ARRAY);
        if (data == null) {
            return is_sea_area;
        }

        int index = cx >> 2;
        long bit = 1L << (((cx & 0x03) << 4) | cz);
        if ((data[index] & bit) != 0) {
            // plugin.getLogger().info("Block at " + b.getLocation() + " is toxic.");
            return true;
        } else {
            return false;
        }
        //return (data[index] & bit) != 0;
    }

    public void setToxic(Block b, Boolean toxic) {
        if (!plugin.getWorldManager().isSkyWorld(b.getWorld())) {
            return;
        }
        int cx = b.getX() & 0x0F;
        int cz = b.getZ() & 0x0F;
        int cy = b.getY() + 64;
        boolean is_sea_area = -64 < b.getY() && b.getY() < Settings.island_height;

        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        long[] data = pdc.get(key[cy], PersistentDataType.LONG_ARRAY);
        if (data == null) {
            if (toxic == is_sea_area) {
                // 与默认状态相同，无需设置
                return;
            }
            data = (is_sea_area ? toxicData : cleanData).clone();
        }

        int index = (cx >> 2);
        long bit = 1L << (((cx & 0x03) << 4) | cz);
        data[index] = toxic ? (data[index] | bit) : (data[index] & ~bit);
        pdc.set(key[cy], PersistentDataType.LONG_ARRAY, data);
    }

    private List<Pair<ItemStack, EquipmentSlot>> getAntiToxicArmors(Player player) {
        List<Pair<ItemStack, EquipmentSlot>> ret = new ArrayList<>();
        for (EquipmentSlot slot : new EquipmentSlot[] {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
            ItemStack armor = player.getInventory().getItem(slot);
            if (armor != null && armor.getEnchantmentLevel(antiAcidEnchant) > 0) {
                ret.add(Pair.of(armor, slot));
            }
        }
        return ret;
    }
    public void triggerToxicDamageTick(Player player) {
        if (player == null) return;
        dmgTick.compute(player.getUniqueId(), (k, entry) -> Pair.of(entry == null ? 10 : entry.getLeft(), true));
    }

    private void checkToxicRain(Server server) {
        server.getOnlinePlayers().forEach((player) -> {
            // TODO: 目前Bukkit API没有提供玩家是否在雨中这个信息，升级后再实现
            if (false /* player.isInRain() */ ) {
                triggerToxicDamageTick(player);
            }
        });
    }

    private void checkToxicWater(Server server) {
        server.getOnlinePlayers().forEach((player) -> {
           if (player.isInWater()) {
                Location loc = player.getLocation();
                Block b = loc.getBlock();
                if (isToxic(b)) {
                    triggerToxicDamageTick(player);
                    //plugin.getLogger().info("Player " + player.getName() + " is in toxic water at " + loc.toString());
                }
           }
        });
    }

    public static boolean isImmune(Player player) {
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) {
            return true;
        }
        return player.hasPotionEffect(PotionEffectType.CONDUIT_POWER);
    }

    private void doToxicDamageTick(Server server) {
        checkToxicRain(server);
        checkToxicWater(server);

        List<UUID> toRemove = new ArrayList<>();

        for (Map.Entry<UUID, Pair<Integer, Boolean>> entry : dmgTick.entrySet()) {
            UUID uuid = entry.getKey();
            Pair<Integer, Boolean> pair = entry.getValue();
            int ticks = pair.getLeft();
            boolean inToxic = pair.getRight();

            Player player = server.getPlayer(uuid);
            if (player == null) {
                toRemove.add(uuid);
                continue;
            }

            if (inToxic && !isImmune(player)) {
                if (ticks <= 1) {
                    List<Pair<ItemStack, EquipmentSlot>> antiToxicArmors = getAntiToxicArmors(player);
                    if (antiToxicArmors.isEmpty()) {
                        // 没有防酸装备，检查玩家背包是否有煤炭
                        ItemStack coalStack = null;
                        for (ItemStack item : player.getInventory().getContents()) {
                            if (item != null && item.getType() == Material.COAL) {
                                coalStack = item;
                                break;
                            }
                        }
                        if (coalStack == null) {
                            player.damage(1.0);
                        } else {
                            // 有煤炭，每60次消耗1个
                            Integer count = coalCount.get(uuid);
                            if (count == null) count = 1;
                            else count = count + 1;
                            coalCount.put(uuid, count);
                            if (count >= 60) {
                                coalCount.put(uuid, 0);
                                coalStack.setAmount(coalStack.getAmount() - 1);
                                if (coalStack.getAmount() <= 0) {
                                    player.getInventory().removeItem(coalStack);
                                }
                            }
                        }
                    } else {
                        int n = antiToxicArmors.size();
                        java.util.Random random = new java.util.Random();
                        if (random.nextInt(n * 2) == 0) {
                            // 随机损坏一件防酸装备
                            Pair<ItemStack, EquipmentSlot> armorPair = antiToxicArmors.get(random.nextInt(n));
                            ItemStack armor = armorPair.getLeft();
                            EquipmentSlot slot = armorPair.getRight();
                            int unbreaking = armor.getEnchantmentLevel(Enchantment.UNBREAKING);
                            if (random.nextInt(unbreaking + 1) == 0) {
                                Damageable meta = (Damageable)armor.getItemMeta();
                                if (meta != null) {
                                    meta.setDamage(meta.getDamage() + 1);
                                    // 如果装备损坏完毕，移除
                                    if (meta.getDamage() >= armor.getType().getMaxDurability()) {
                                        player.getInventory().setItem(slot, null);
                                    }
                                    armor.setItemMeta(meta);
                                }
                            }
                        }
                    }
                    dmgTick.put(uuid, Pair.of(10, false)); // false: to check if still in toxic next tick
                } else {
                    dmgTick.put(uuid, Pair.of(ticks - 1, false));
                }
            } else {
                toRemove.add(uuid);
            }
        }
        for (java.util.UUID uuid : toRemove) {
            dmgTick.remove(uuid);
            coalCount.remove(uuid);
        }
    }

    public boolean isGeneralWaterBucket(ItemStack item) {
        return switch (item.getType()) {
            case WATER_BUCKET, AXOLOTL_BUCKET, COD_BUCKET, PUFFERFISH_BUCKET, SALMON_BUCKET, TADPOLE_BUCKET, TROPICAL_FISH_BUCKET -> true;
            default -> false;
        };
    }

    public void setBucketToxic(ItemStack item) {
        item.addEnchantment(acidEnchant, 1);
    }

    public boolean isBucketToxic(ItemStack item) {
        return item.getEnchantmentLevel(acidEnchant) > 0;
    }

    @EventHandler (priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerBucketToxicWater(PlayerBucketFillEvent event) {
        Block b = event.getBlock();
        if (!plugin.getWorldManager().isSkyAssociatedWorld(b.getWorld())) {
            return;
        }

        var bucket = event.getItemStack();
        if (isGeneralWaterBucket(bucket)) {
            if (isToxic(event.getBlock())) {
                setBucketToxic(bucket);
                setToxic(b, false);
                event.setItemStack(bucket);
            }
        }
    }

    @EventHandler (priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerReleaseWater(PlayerBucketEmptyEvent event) {
        Block b = event.getBlock();

        if (!plugin.getWorldManager().isSkyAssociatedWorld(b.getWorld())) {
            return;
        }
        /* Event本身无法获得使用的ItemStack(只有Result ItemStack)，以下workaround */
        Player p = event.getPlayer();
        var hand = event.getHand();
        var bucket = p.getInventory().getItem(hand);
        if (bucket != null && isGeneralWaterBucket(bucket)) {
            if (isBucketToxic(bucket)) {
                setToxic(b, true);
                spread(b);
            } else {
                setToxic(b, false);
                checkPollute(b);
            }
        }
    }

    // 防止毒水生成石头
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled = true)
    public void preventAcidStone(BlockFromToEvent event) {
        Block from = event.getBlock();
        Block to = event.getToBlock();
        if (!plugin.getWorldManager().isSkyAssociatedWorld(from.getWorld()) || !isWater(from)) {
            return;
        }
        // 当岩浆流进水（只能是从上往下流进），会生成石头。我们取消这个事件，让它不能生成
        if (to.getType() == Material.WATER && from.getType() == Material.LAVA) {
            if (isToxic(to)) {
                event.setCancelled(true);
            }
        }
    }

    // 防止毒水中自然生成生物
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void preventAcidCreature(CreatureSpawnEvent event) {
        Entity e = event.getEntity();
        Block b = e.getLocation().getBlock();
        if (!plugin.getWorldManager().isSkyAssociatedWorld(b.getWorld())) {
            return;
        }
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL && isWater(b) && isToxic(b)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPurifyByBlockPlace(BlockPlaceEvent event) {
        Block b = event.getBlock();
        if (!plugin.getWorldManager().isSkyAssociatedWorld(b.getWorld())) {
            return;
        }

        if (!isWater(b)) {
            setToxic(b, false);
        }
    }

    @EventHandler (priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWaterFlow(BlockFromToEvent event) {
        Block from = event.getBlock();
        Block to = event.getToBlock();
        if (!plugin.getWorldManager().isSkyAssociatedWorld(from.getWorld()) || !isWater(from)) {
            return;
        }
        // plugin.getLogger().info("Water flowing from " + from.getLocation() + " to " + to.getLocation());
        if (to.getY() < -64) {
            return;
        }
        boolean toxic_from = isToxic(from);

        if (toxic_from) {
            setToxic(to, true);
            spread(to);
        } else {
            setToxic(to, false);
            checkPollute(to);
        }
    }

    public void checkPollute(Block b) {
        for (BlockFace face : new BlockFace[] {
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST,
            BlockFace.UP, BlockFace.DOWN }) {
            Block nb = b.getRelative(face);
            if (isToxic(nb)) {
                setToxic(b, true);
                b.getWorld().playEffect(b.getLocation(), Effect.LAVA_INTERACT, 0);
                spread(b);
                return;
            }
        }
    }

    public void spread(Block b) {
        toSpread.add(b.getLocation());
    }

    public void doToxicSpreadTick() {
        Set<Location> newToSpread = new HashSet<>();
        for (Location loc : toSpread) {
            Block b = loc.getBlock();
            if (!isToxic(b)) {
                continue;
            }
            for (BlockFace face : new BlockFace[] {
                    BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST,
                    BlockFace.UP, BlockFace.DOWN }) {
                Block nb = b.getRelative(face);
                if (isWater(nb) && !isToxic(nb) && !newToSpread.contains(nb.getLocation())) {
                    // plugin.getLogger().info("Water at " + nb.getLocation() + " became toxic.");
                    setToxic(nb, true);
                    nb.getWorld().playEffect(nb.getLocation(), Effect.LAVA_INTERACT, 0);
                    newToSpread.add(nb.getLocation());
                }
            }
        }
        toSpread.clear();
        toSpread.addAll(newToSpread);
    }

}
