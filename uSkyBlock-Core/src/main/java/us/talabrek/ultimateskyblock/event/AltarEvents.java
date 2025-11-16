package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.hook.economy.EconomyHook;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

@Singleton
public class AltarEvents implements Listener {
    private final uSkyBlock plugin;
    private static final Random RANDOM = new Random();

    enum AltarType {
        NONE,
        HARVEST,
        WAR,
        WEALTH,
        SOUL,
        EXPLORATION
    }
    @Inject
    public AltarEvents(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
    }
    static public ItemStack stoneOfPeace() {
        ItemStack item = new ItemStack(Material.LAPIS_LAZULI);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(tr("\u00a7l\u00a79和平之石"));
        meta.addEnchant(Enchantment.PROTECTION, 10, true);
        List<String> lore = new ArrayList<>();
        lore.add("\u00a7l\u00a79和平之石");
        lore.add("\u00a7l\u00a7e消耗品，为副手物品加一级\u00a7l\u00a79保护");
        lore.add("\u00a7l\u00a7e消耗数等于目标保护等级");
        lore.add("\u00a7l\u00a7e最多升级到保护10");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
    static public ItemStack stoneOfEternity() {
        ItemStack item = new ItemStack(Material.DIAMOND);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(tr("\u00a7l\u00a7b永久之石"));
        meta.addEnchant(Enchantment.UNBREAKING, 10, true);
        List<String> lore = new ArrayList<>();
        lore.add("\u00a7l\u00a7b永久之石");
        lore.add("\u00a7l\u00a7e消耗品，为副手物品加一级\u00a7l\u00a7b耐久");
        lore.add("\u00a7l\u00a7e消耗数等于目标等级");
        lore.add("\u00a7l\u00a7e最多升级到耐久10");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
    static public ItemStack stoneOfWealth() {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(tr("\u00a7l\u00a76财富之石"));
        meta.addEnchant(Enchantment.FORTUNE, 1, true);
        List<String> lore = new ArrayList<>();
        lore.add("\u00a7l\u00a76财富之石");
        lore.add("\u00a7l\u00a7e消耗品，为副手物品加一级\u00a7l\u00a76时运");
        lore.add("\u00a7l\u00a7e消耗数等于目标等级的\u00a7l\u00a76平方");
        lore.add("\u00a7l\u00a7e最多升级到时运8");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
    private NamespacedKey getKeyAltarType(Block b) {
        return new NamespacedKey(plugin, String.format("altar_type_%d_%d_%d", b.getX(), b.getY(), b.getZ()));
    }

    private NamespacedKey getKeyAltarCounter(Block b) {
        return new NamespacedKey(plugin, String.format("altar_counter_%d_%d_%d", b.getX(), b.getY(), b.getZ()));
    }

    private void buildAltar(Block block, AltarType type) {
        block.setType(Material.REINFORCED_DEEPSLATE);
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
        pdc.set(getKeyAltarType(block), PersistentDataType.INTEGER, type.ordinal());
        pdc.set(getKeyAltarCounter(block), PersistentDataType.LONG, 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAltarDestroyed(final BlockBreakEvent event) {
        // 当祭坛被破坏时，移除其持久化数据
        if (!plugin.getWorldManager().isSkyWorld(event.getBlock().getWorld())) {
            return;
        }
        Block block = event.getBlock();
        if (block.getType() != Material.REINFORCED_DEEPSLATE) {
            return;
        }
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
        pdc.remove(getKeyAltarType(block));
        pdc.remove(getKeyAltarCounter(block));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpecialItemsUsed(final PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand.hasItemMeta() && itemInHand.getItemMeta().hasLore()) {
            List<String> lore = itemInHand.getItemMeta().getLore();
            if (lore == null || lore.isEmpty()) {
                return;
            }
            String firstLore = itemInHand.getItemMeta().getLore().getFirst();
            plugin.getLogger().info(String.format("Item lore first line: %s", firstLore));
            plugin.getLogger().info(String.format("Event action: %s", event.getAction().name()));
            if (firstLore.equals("\u00a7l\u00a79和平之石")) {
                tryUseStoneOfPeace(player, itemInHand, event);
            } else if (firstLore.equals("\u00a7l\u00a7b永久之石")) {
                tryUseStoneOfEternity(player, itemInHand, event);
            } else if (firstLore.equals("\u00a7l\u00a76财富之石")) {
                tryUseStoneOfWealth(player, itemInHand, event);
            }
        }
    }

    private void tryUseStoneOfPeace(Player player, ItemStack itemInHand, PlayerInteractEvent event) {
        // 只在右键空气时响应
        if (event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        event.setCancelled(true);
        // 只能对副手物品使用
        ItemStack offHandItem = player.getInventory().getItemInOffHand();
        if (offHandItem.getType() == Material.AIR) {
            player.sendMessage(tr("\u00a7c你必须在副手持有一个物品。"));
            return;
        }
        ItemMeta offHandMeta = offHandItem.getItemMeta();
        if (offHandMeta == null) {
            // This should never happen
            return;
        }
        if (!offHandMeta.hasEnchant(Enchantment.PROTECTION)) {
            player.sendMessage(tr("\u00a7c你的副手物品没有保护附魔。"));
            return;
        }
        int currentProtectionLevel = offHandMeta.getEnchantLevel(Enchantment.PROTECTION);
        if (currentProtectionLevel >= 10) {
            player.sendMessage(tr("\u00a7c你的副手物品的等级已经达到最高。"));
            return;
        }
        // 消耗对应数量的和平之石
        int stonesNeeded = currentProtectionLevel + 1;
        if (itemInHand.getAmount() < stonesNeeded) {
            player.sendMessage(String.format("\u00a7c你需要 %d 个和平之石来提升副手物品的保护等级。", stonesNeeded));
            return;
        }
        // 提升保护等级
        offHandMeta.addEnchant(Enchantment.PROTECTION, currentProtectionLevel + 1, true);
        offHandItem.setItemMeta(offHandMeta);
        // 消耗和平之石
        itemInHand.setAmount(itemInHand.getAmount() - stonesNeeded);
        plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a使用\u00a79和平之石 \u00a7a，将 %s 的保护等级提升到了 \u00a79%d\u00a7a！",
            player.getName(), offHandMeta.getDisplayName(), currentProtectionLevel + 1));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
    }

    private void tryUseStoneOfEternity(Player player, ItemStack itemInHand, PlayerInteractEvent event) {
        // 只在右键空气时响应
        if (event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        event.setCancelled(true);
        // 只能对副手物品使用
        ItemStack offHandItem = player.getInventory().getItemInOffHand();
        if (offHandItem.getType() == Material.AIR) {
            player.sendMessage(tr("\u00a7c你必须在副手持有一个物品。"));
            return;
        }
        ItemMeta offHandMeta = offHandItem.getItemMeta();
        if (offHandMeta == null) {
            // This should never happen
            return;
        }
        if (!offHandMeta.hasEnchant(Enchantment.UNBREAKING)) {
            player.sendMessage(tr("\u00a7c你的副手物品没有耐久附魔。"));
            return;
        }
        int currentUnbreakingLevel = offHandMeta.getEnchantLevel(Enchantment.UNBREAKING);
        if (currentUnbreakingLevel >= 10) {
            player.sendMessage(tr("\u00a7c你的副手物品的耐久等级已经达到最高。"));
            return;
        }
        // 消耗对应数量的永久之石
        int stonesNeeded = currentUnbreakingLevel + 1;
        if (itemInHand.getAmount() < stonesNeeded) {
            player.sendMessage(String.format("\u00a7c你需要 %d 个永久之石来提升副手物品的耐久等级。", stonesNeeded));
            return;
        }
        // 提升耐久等级
        offHandMeta.addEnchant(Enchantment.UNBREAKING, currentUnbreakingLevel + 1, true);
        offHandItem.setItemMeta(offHandMeta);
        // 消耗永久之石
        itemInHand.setAmount(itemInHand.getAmount() - stonesNeeded);
        plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a使用\u00a7b永久之石 \u00a7a，将 %s 的耐久等级提升到了 \u00a7b%d\u00a7a！",
            player.getName(), offHandMeta.getDisplayName(), currentUnbreakingLevel + 1));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
    }

    public void tryUseStoneOfWealth(Player player, ItemStack itemInHand, PlayerInteractEvent event) {
        // 只在右键空气时响应
        if (event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        event.setCancelled(true);
        // 只能对副手物品使用
        ItemStack offHandItem = player.getInventory().getItemInOffHand();
        if (offHandItem.getType() == Material.AIR) {
            player.sendMessage(tr("\u00a7c你必须在副手持有一个物品。"));
            return;
        }
        ItemMeta offHandMeta = offHandItem.getItemMeta();
        if (offHandMeta == null) {
            // This should never happen
            return;
        }
        if (!offHandMeta.hasEnchant(Enchantment.FORTUNE)) {
            player.sendMessage(tr("\u00a7c你的副手物品没有时运附魔。"));
            return;
        }
        int currentFortuneLevel = offHandMeta.getEnchantLevel(Enchantment.FORTUNE);
        if (currentFortuneLevel >= 8) {
            player.sendMessage(tr("\u00a7c你的副手物品的时运等级已经达到最高。"));
            return;
        }
        // 消耗对应数量的财富之石
        int stonesNeeded = (currentFortuneLevel + 1) * (currentFortuneLevel + 1);
        if (itemInHand.getAmount() < stonesNeeded) {
            player.sendMessage(String.format("\u00a7c你需要 %d 个财富之石来提升副手物品的时运等级。", stonesNeeded));
            return;
        }
        // 提升时运等级
        offHandMeta.addEnchant(Enchantment.FORTUNE, currentFortuneLevel + 1, true);
        offHandItem.setItemMeta(offHandMeta);
        // 消耗财富之石
        itemInHand.setAmount(itemInHand.getAmount() - stonesNeeded);
        plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a使用\u00a76财富之石 \u00a7a，将 %s 的时运等级提升到了 \u00a76%d\u00a7a！",
            player.getName(), offHandMeta.getDisplayName(), currentFortuneLevel + 1));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAltarInteract(final PlayerInteractEvent event) {
        // 当玩家右键点击祭坛时，提示其类型和计数器
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (!plugin.getWorldManager().isSkyWorld(player.getWorld())) {
            return;
        }
        if (block != null &&
            block.getType() == Material.REINFORCED_DEEPSLATE &&
            event.getAction() == Action.RIGHT_CLICK_BLOCK) {

            PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
            Integer altarTypeOrdinal = pdc.get(getKeyAltarType(block), PersistentDataType.INTEGER);
            Long altarCounter = pdc.get(getKeyAltarCounter(block), PersistentDataType.LONG);
            if (altarTypeOrdinal != null && altarCounter != null) {
                event.setCancelled(true);
                // 如果此方块不是玩家的岛屿，则提示
                IslandInfo islandInfo = plugin.getIslandInfo(block.getLocation());
                if (islandInfo == null || !islandInfo.isMember(player)) {
                    player.sendMessage(tr("\u00a7c你只能在自己的岛屿上使用祭坛。"));
                    return;
                }
                AltarType altarType = AltarType.values()[altarTypeOrdinal];
                // 检查上面是信标
                Block up = block.getRelative(0, 1, 0);
                if (up.getType() != Material.BEACON) {
                    player.sendMessage(tr("\u00a7c祭坛上方必须有一个信标才能使用。"));
                    return;
                }

                switch (altarType) {
                    case HARVEST -> {
                        // player.sendMessage(tr("\u00a7a这是一个 \u00a7l\u00a72收获之祭坛\u00a7a，已被使用了 \u00a7l\u00a73%d \u00a7a次。", altarCounter));
                    }
                    case WAR -> {
                        // player.sendMessage(tr("\u00a7a这是一个 \u00a7l\u00a74战争之祭坛\u00a7a，已被使用了 \u00a7l\u00a73%d \u00a7a次。", altarCounter));
                    }
                    case WEALTH -> {
                        player.sendMessage(String.format("\u00a7a这是一个 \u00a7l\u00a76富饶之祭坛\u00a7a，共奉献了 \u00a7l\u00a73%d \u00a7a金币。", altarCounter));
                        // 玩家可奉献1个钻石+1000金币
                        ItemStack itemInHand = player.getInventory().getItemInMainHand();
                        if (itemInHand.getType() == Material.DIAMOND) {
                            EconomyHook hook = plugin.getHookManager().getEconomyHook().get();
                            double money = hook.getBalance(player);
                            if (money >= 1000) {
                                // 扣除物品和金币
                                itemInHand.setAmount(itemInHand.getAmount() - 1);
                                hook.withdrawPlayer(player, 1000);
                                // 增加祭坛计数器
                                altarCounter += 1000;
                                pdc.set(getKeyAltarCounter(block), PersistentDataType.LONG, altarCounter);
                                player.sendMessage(tr("\u00a7a你向富饶之祭坛奉献了 \u00a7l\u00a73一颗钻石 \u00a7a和 \u00a7l\u00a73 1000g\u00a7a。"));
                                player.getWorld().playSound(block.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.0f);
                                wealthReward(player);
                            } else {
                                player.sendMessage(tr("\u00a7c奉献需要一颗钻石和1000g。"));
                                return;
                            }
                        } else {
                            // 否则，提示玩家需要的物品
                            player.sendMessage(tr("\u00a7c奉献需要一颗钻石和1000g。"));
                        }
                    }
                    case SOUL -> {
                        // player.sendMessage(tr("\u00a7a这是一个 \u00a7l\u00a75灵魂之祭坛\u00a7a，已被使用了 \u00a7l\u00a73%d \u00a7a次。", altarCounter));
                    }
                    case EXPLORATION -> {
                        // player.sendMessage(tr("\u00a7a这是一个 \u00a7l\u00a79探索之祭坛\u00a7a，已被使用了 \u00a7l\u00a73%d \u00a7a次。", altarCounter));
                    }
                    default -> {
                        return;
                    }
                }
            } else {
                return;
            }
        }
    }

    private void wealthReward(Player player) {
        double roll = RANDOM.nextDouble();
        // 3% 和平之石，5% 永久之石，2% 财富之石
        // 钻石 蓝冰 绿宝石块 金块 各10%
        // 钻石块 远古残骸 下界合金 各1%
        // 下界合金块 0.1%
        // 青金石块 红石块 平分其余
        if (roll < 0.03) {
            player.getWorld().dropItemNaturally(player.getLocation(), stoneOfPeace());
        } else if (roll < 0.08) {
            player.getWorld().dropItemNaturally(player.getLocation(), stoneOfEternity());
        } else if (roll < 0.10) {
            player.getWorld().dropItemNaturally(player.getLocation(), stoneOfWealth());
        } else if (roll < 0.20) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.DIAMOND, 1));
        } else if (roll < 0.30) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.BLUE_ICE, 1));
        } else if (roll < 0.40) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.EMERALD_BLOCK, 1));
        } else if (roll < 0.50) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.GOLD_BLOCK, 1));
        } else if (roll < 0.51) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.DIAMOND_BLOCK, 1));
        } else if (roll < 0.52) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.ANCIENT_DEBRIS, 1));
        } else if (roll < 0.53) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.NETHERITE_INGOT, 1));
        } else if (roll < 0.531) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.NETHERITE_BLOCK, 1));
        } else if (roll < 0.7655) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.LAPIS_BLOCK, 1));
        } else {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.REDSTONE_BLOCK, 1));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBuildAltar(final PlayerInteractEvent event) {
        // 当右键点击一个下界合金块时，检查是否允许建造祭坛
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (!plugin.getWorldManager().isSkyWorld(player.getWorld())) {
            return;
        }
        if (block != null &&
            block.getType() == org.bukkit.Material.NETHERITE_BLOCK &&
            event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            // 如果上方的方块不是信标，则忽略
            if (block.getRelative(0, 1, 0).getType() != org.bukkit.Material.BEACON) {
                return;
            }
            // 如果此方块不是玩家的岛屿，则提示
            IslandInfo islandInfo = plugin.getIslandInfo(block.getLocation());
            if (islandInfo == null || !islandInfo.isMember(player)) {
                player.sendMessage(tr("\u00a7c你只能在自己的岛屿上建造祭坛。"));
                return;
            }
            PlayerInfo playerInfo = plugin.getPlayerInfo(player);
            // 如果玩家未完成进度，则提示
            if (plugin.getChallengeLogic().checkChallenge(playerInfo, "advanced") == 0) {
                player.sendMessage(tr("\u00a7c完成'海岛进阶'挑战后，才能建造祭坛。"));
                return;
            }
            // 检查玩家主手的物品
            ItemStack itemInHand = player.getInventory().getItemInMainHand();
            // 如果是满足要求的物品，则成功建造祭坛
            if (itemInHand.getType() == Material.DIAMOND) {
                // 尝试消耗5个钻石+50000金币
                if (itemInHand.getAmount() >= 5) {
                    plugin.getHookManager().getEconomyHook().ifPresent((hook) -> {
                        double money = hook.getBalance(player);
                        if (money >= 50000) {
                            // 扣除物品和金币
                            itemInHand.setAmount(itemInHand.getAmount() - 5);
                            hook.withdrawPlayer(player, 50000);
                            plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a在他们的岛屿上建造了 富饶之祭坛!", player.getName()));
                            buildAltar(block, AltarType.WEALTH);
                            // 产生音效
                            player.getWorld().playSound(block.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f);
                            // 在玩家脚下掉落三种石头
                            block.getWorld().dropItemNaturally(player.getLocation(), stoneOfPeace());
                            block.getWorld().dropItemNaturally(player.getLocation(), stoneOfEternity());
                            block.getWorld().dropItemNaturally(player.getLocation(), stoneOfWealth());
                        } else {
                            player.sendMessage(tr("\u00a7c需要5个钻石和50000g来建造 富饶之祭坛。"));
                            return;
                        }
                    });
                } else {
                    player.sendMessage(tr("\u00a7c需要5个钻石和50000g来建造 富饶之祭坛。"));
                    return;
                }
            } else {
                // 否则，提示玩家需要的物品
                player.sendMessage(tr("\u00a7c需要5个钻石和50000g来建造 富饶之祭坛。"));
            }
        }
    }
}
