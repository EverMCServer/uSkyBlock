package us.talabrek.ultimateskyblock.enddragon;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.gui.GuiManager;
import us.talabrek.ultimateskyblock.gui.InventoryButton;
import us.talabrek.ultimateskyblock.gui.InventoryGui;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The 27 slot {@code /is enddragon} menu: one explanation item, eight difficulty buttons, fight status, reward claim
 * and close.
 */
public class EndDragonMenu extends InventoryGui {
    private static final int SIZE = 27;
    private static final int INFO_SLOT = 4;
    private static final int FIRST_DIFFICULTY_SLOT = 10;
    private static final int STATUS_SLOT = 18;
    private static final int CLAIM_SLOT = 22;
    private static final int CLOSE_SLOT = 26;

    private final uSkyBlock plugin;
    private final DragonFightManager manager;
    private final EndDragonConfig config;
    private final GuiManager guiManager;

    public EndDragonMenu(@NotNull uSkyBlock plugin, @NotNull DragonFightManager manager,
                         @NotNull EndDragonConfig config, @NotNull GuiManager guiManager) {
        super(createInventory());
        this.plugin = plugin;
        this.manager = manager;
        this.config = config;
        this.guiManager = guiManager;
    }

    private static Inventory createInventory() {
        return Bukkit.createInventory(null, SIZE, "§8末影龙挑战");
    }

    @Override
    public void decorate(@NotNull Player player) {
        decorateInfo();
        decorateDifficulties(player);
        decorateStatus(player);
        decorateClaim(player);
        decorateClose();
        super.decorate(player);
    }

    private void decorateInfo() {
        List<String> lore = List.of(
            "§7挑战原版末影龙，击败后领取奖励。",
            "",
            "§e消耗：§f1 级 4 个末影水晶，每级翻倍",
            "§8   2级 8 / 3级 16 / 4级 32 / 5级 64",
            "§8   6级 128 / 7级 256 / 8级 512",
            "",
            "§e规则：",
            "§7- 只有发起者和其岛屿成员能伤害末影龙",
            "§7- 龙持续 3600s 没有受到有效伤害则失败",
            "§7- 失败不退还消耗；胜利后解锁下一难度",
            "§7- 有未领取奖励时必须先领取，才能开新挑战",
            "§7- 背包满时奖励会保留，清理后再领取",
            "",
            "§e开始挑战需要站在 §bdragon_altar §e区域内。"
        );
        addButton(INFO_SLOT, new InventoryButton()
            .creator(p -> item(Material.DRAGON_HEAD, "§d§l末影龙挑战", lore))
            .consumer((p, e) -> p.sendMessage("§e[末影龙] 请选择下方难度；开始挑战必须在祭坛区域内。")));
    }

    private void decorateDifficulties(Player player) {
        DragonFightData active = manager.getActive();
        IslandInfo island = plugin.getIslandInfo(player);
        boolean inAltar = manager.isInAltar(player);
        boolean hasIsland = island != null && island.exists() && !island.getLeader().isEmpty();
        int owned = countCrystals(player);
        int maxLevel = manager.getMaxLevel(player);
        int rewardLevel = manager.getRewardLevel(player);

        for (int level = 1; level <= 8; level++) {
            final int difficultyLevel = level;
            DragonDifficulty difficulty = config.difficulty(level);
            if (difficulty == null) {
                continue;
            }
            int slot = FIRST_DIFFICULTY_SLOT + level - 1;
            List<String> lore = new ArrayList<>();
            lore.add("§e消耗：§f" + difficulty.cost() + " 个末影水晶");
            lore.add("§e当前持有：§f" + owned + " 个");
            lore.add("§e血量：§f" + format(difficulty.maxHealth()));
            lore.add("§e伤害倍率：§f" + format(difficulty.damageMultiplier()) + "x");
            lore.add("§e限时：§f" + difficulty.timeoutSeconds() + "s 无伤害失败");
            lore.add("§e奖励：§f" + formatReward(difficulty));
            lore.add("");
            Material material;
            if (level > maxLevel) {
                material = Material.BARRIER;
                lore.add("§c未解锁：先战胜难度 §e" + (level - 1) + "§c。");
            } else if (active != null) {
                material = Material.GRAY_DYE;
                lore.add("§c当前已有挑战进行中。");
            } else if (rewardLevel > 0) {
                material = Material.GRAY_DYE;
                lore.add("§c请先领取难度 §e" + rewardLevel + "§c 的奖励。");
            } else if (!inAltar) {
                material = Material.GRAY_DYE;
                lore.add("§c你需要站在 dragon_altar 区域内。");
            } else if (!hasIsland) {
                material = Material.GRAY_DYE;
                lore.add("§c你需要先拥有一个岛屿。");
            } else if (owned < difficulty.cost()) {
                material = Material.GRAY_DYE;
                lore.add("§c水晶不足，还差 §e" + (difficulty.cost() - owned) + "§c 个。");
            } else {
                material = Material.END_CRYSTAL;
                lore.add("§a点击挑战（会打开确认界面）。");
            }
            ItemStack icon = item(material, "§b难度 " + level + " §7· §f" + difficulty.displayName(), lore);
            icon.setAmount(Math.min(64, Math.max(1, difficulty.cost())));
            addButton(slot, new InventoryButton()
                .creator(p -> icon)
                .consumer((p, e) -> onDifficultyClick(p, difficultyLevel, difficulty, active, inAltar,
                    hasIsland, owned, maxLevel, rewardLevel)));
        }
    }

    private void onDifficultyClick(Player player, int level, DragonDifficulty difficulty,
                                   DragonFightData active, boolean inAltar, boolean hasIsland,
                                   int owned, int maxLevel, int rewardLevel) {
        if (level > maxLevel) {
            player.sendMessage("§c[末影龙] 该难度尚未解锁。当前最高难度：§e" + maxLevel + "§c。");
            return;
        }
        if (active != null) {
            player.sendMessage("§c[末影龙] 当前已有挑战正在进行。");
            return;
        }
        if (rewardLevel > 0) {
            player.sendMessage("§c[末影龙] 你还有未领取的难度 §e" + rewardLevel + "§c 奖励，请先领取。");
            return;
        }
        if (!inAltar) {
            player.sendMessage("§c[末影龙] 你需要站在 dragon_altar 祭坛区域内才能开始挑战。");
            return;
        }
        if (!hasIsland) {
            player.sendMessage("§c[末影龙] 你需要先拥有一个岛屿。");
            return;
        }
        if (owned < difficulty.cost()) {
            player.sendMessage("§c[末影龙] 末影水晶不足：需要 §e" + difficulty.cost() + "§c 个，当前只有 §e" + owned + "§c 个。");
            return;
        }
        guiManager.openGui(new EndDragonConfirmMenu(plugin, manager, config, guiManager, level), player);
    }

    private void decorateStatus(Player player) {
        DragonFightData active = manager.getActive();
        List<String> lore = new ArrayList<>();
        Material material;
        String name;
        if (active == null) {
            material = Material.CLOCK;
            name = "§7当前没有进行中的挑战";
            lore.add("§7点击下方难度开始挑战。");
            lore.add("§e最高已解锁难度：§f" + manager.getMaxLevel(player));
        } else {
            material = Material.DRAGON_HEAD;
            name = "§d挑战进行中";
            lore.add("§e状态：§f" + (active.dragonUuid() == null ? "召唤中" : "战斗中"));
            lore.add("§e难度：§f" + active.difficulty());
            lore.add("§e剩余时间：§f" + manager.getRemainingSeconds() + "s");
            Double health = manager.getDragonHealth();
            if (health != null) {
                lore.add("§e龙血量：§f" + format(health));
            }
            if (active.initiator().equals(player.getUniqueId())) {
                lore.add("§e你是本场发起者。");
            }
        }
        addButton(STATUS_SLOT, new InventoryButton()
            .creator(p -> item(material, name, lore))
            .consumer((p, e) -> p.sendMessage("§e[末影龙] " + (active == null ? "当前没有挑战。" : "挑战进行中。"))));
    }

    private void decorateClaim(Player player) {
        int rewardLevel = manager.getRewardLevel(player);
        List<String> lore = new ArrayList<>();
        Material material;
        String name;
        if (rewardLevel <= 0) {
            material = Material.CHEST;
            name = "§7没有待领取的奖励";
            lore.add("§7挑战成功后奖励会出现在这里。");
        } else {
            material = Material.DRAGON_EGG;
            name = "§a领取难度 " + rewardLevel + " 奖励";
            DragonDifficulty difficulty = config.difficulty(rewardLevel);
            lore.add("§e奖励：§f" + (difficulty != null ? formatReward(difficulty) : "配置缺失"));
            lore.add("");
            lore.add("§a点击领取。背包空间不足时会保留。");
        }
        addButton(CLAIM_SLOT, new InventoryButton()
            .creator(p -> item(material, name, lore))
            .consumer((p, e) -> {
                manager.claim(p);
                decorate(p);
                p.updateInventory();
            }));
    }

    private void decorateClose() {
        addButton(CLOSE_SLOT, new InventoryButton()
            .creator(p -> item(Material.BARRIER, "§c关闭", List.of("§7关闭菜单")))
            .consumer((p, e) -> p.closeInventory()));
    }

    private int countCrystals(Player player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == Material.END_CRYSTAL) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private static String formatReward(DragonDifficulty difficulty) {
        List<String> parts = new ArrayList<>();
        for (ItemStack item : difficulty.itemRewards()) {
            parts.add(item.getType().name().toLowerCase(Locale.ROOT) + " x" + item.getAmount());
        }
        if (difficulty.currency() > 0) {
            parts.add("货币 " + difficulty.currency());
        }
        if (difficulty.xp() > 0) {
            parts.add("经验 " + difficulty.xp());
        }
        return parts.isEmpty() ? "无" : String.join(" / ", parts);
    }

    private static String format(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
