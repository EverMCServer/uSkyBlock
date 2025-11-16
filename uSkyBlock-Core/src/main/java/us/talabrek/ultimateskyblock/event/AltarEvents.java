package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
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
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.ArrayList;
import java.util.List;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

@Singleton
public class AltarEvents implements Listener {
    private final uSkyBlock plugin;

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
        meta.setDisplayName(tr("和平之石"));
        meta.addEnchant(Enchantment.PROTECTION, 10, true);
        List<String> lore = new ArrayList<>();
        lore.add(tr("和平之石"));
        lore.add(tr("消耗品，为副手的护甲增加一级保护附魔"));
        lore.add(tr("消耗数量等于目标保护等级，最多升级到保护10"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
    static public ItemStack stoneOfEternity() {
        ItemStack item = new ItemStack(Material.DIAMOND);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(tr("永久之石"));
        meta.addEnchant(Enchantment.UNBREAKING, 10, true);
        List<String> lore = new ArrayList<>();
        lore.add(tr("永久之石"));
        lore.add(tr("消耗品，为副手的物品增加一级耐久附魔"));
        lore.add(tr("消耗数量等于目标耐久等级，最多升级到耐久10。"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
    static public ItemStack stoneOfWealth() {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(tr("财富之石"));
        meta.addEnchant(Enchantment.FORTUNE, 1, true);
        List<String> lore = new ArrayList<>();
        lore.add(tr("财富之石"));
        lore.add(tr("消耗品，为副手的物品增加一级时运附魔"));
        lore.add(tr("消耗数量等于目标时运等级的平方，最多升级到时运8。"));
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
            if (!plugin.getIslandInfo(block.getLocation()).isMember(player)) {
                player.sendMessage(tr("\u00a7cYou can only build altars on your own island."));
                return;
            }
            PlayerInfo playerInfo = plugin.getPlayerInfo(player);
            // 如果玩家未完成进度，则提示
            if (plugin.getChallengeLogic().checkChallenge(playerInfo, "advanced") == 0) {
                player.sendMessage(tr("\u00a7cYou can only build altars after completing the 'Advanced' challenge."));
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
                            player.sendMessage(tr("\u00a7aYou have successfully built Altar of Wealth!"));
                            buildAltar(block, AltarType.WEALTH);
                            // 产生音效
                            player.getWorld().playSound(block.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f);
                            // 在玩家脚下掉落三种石头
                            block.getWorld().dropItemNaturally(player.getLocation(), stoneOfPeace());
                            block.getWorld().dropItemNaturally(player.getLocation(), stoneOfEternity());
                            block.getWorld().dropItemNaturally(player.getLocation(), stoneOfWealth());
                        } else {
                            player.sendMessage(tr("\u00a7cYou need 5 diamonds and 50,000g to build Altar of Wealth."));
                            return;
                        }
                    });
                } else {
                    player.sendMessage(tr("\u00a7cYou need 5 diamonds and 50,000g to build Altar of Wealth."));
                    return;
                }
            } else {
                // 否则，提示玩家需要的物品
                player.sendMessage(tr("\u00a7c"));
            }
        }
    }
}
