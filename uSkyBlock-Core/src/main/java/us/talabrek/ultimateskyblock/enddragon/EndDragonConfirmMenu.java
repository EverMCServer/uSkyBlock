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
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * Second confirmation for a challenge, because a level 8 attempt consumes 512 end crystals.
 */
public class EndDragonConfirmMenu extends InventoryGui {
    private static final int SIZE = 27;

    private final uSkyBlock plugin;
    private final DragonFightManager manager;
    private final EndDragonConfig config;
    private final GuiManager guiManager;
    private final int level;

    public EndDragonConfirmMenu(@NotNull uSkyBlock plugin, @NotNull DragonFightManager manager,
                                @NotNull EndDragonConfig config, @NotNull GuiManager guiManager, int level) {
        super(Bukkit.createInventory(null, SIZE, "§8确认挑战 · 难度 " + level));
        this.plugin = plugin;
        this.manager = manager;
        this.config = config;
        this.guiManager = guiManager;
        this.level = level;
    }

    @Override
    public void decorate(@NotNull Player player) {
        DragonDifficulty difficulty = config.difficulty(level);
        if (difficulty == null) {
            player.closeInventory();
            return;
        }
        List<String> lore = new ArrayList<>();
        lore.add("§e难度：§f" + level + " §7(" + difficulty.displayName() + ")");
        lore.add("§e消耗：§f" + difficulty.cost() + " 个末影水晶");
        lore.add("§e当前持有：§f" + countCrystals(player) + " 个");
        lore.add("§e血量：§f" + format(difficulty.maxHealth()));
        lore.add("§e伤害倍率：§f" + format(difficulty.damageMultiplier()) + "x");
        lore.add("§e限时：§f" + difficulty.timeoutSeconds() + "s 无伤害失败");
        lore.add("");
        lore.add("§c开始后会立即扣除水晶，失败不退还。");
        lore.add("§a确认后原版复活序列会开始。");
        addButton(13, new InventoryButton()
            .creator(p -> item(Material.END_CRYSTAL, "§b难度 " + level + " · " + difficulty.displayName(), lore))
            .consumer((p, e) -> p.sendMessage("§e[末影龙] 请点击下方确认或取消。")));

        addButton(11, new InventoryButton()
            .creator(p -> item(Material.LIME_CONCRETE, "§a确认挑战", List.of("§7扣除 " + difficulty.cost() + " 个末影水晶", "§7开始原版复活序列")))
            .consumer((p, e) -> {
                if (manager.start(p, level)) {
                    p.closeInventory();
                } else {
                    guiManager.openGui(new EndDragonMenu(plugin, manager, config, guiManager), p);
                }
            }));

        addButton(15, new InventoryButton()
            .creator(p -> item(Material.RED_CONCRETE, "§c取消", List.of("§7返回难度菜单")))
            .consumer((p, e) -> guiManager.openGui(new EndDragonMenu(plugin, manager, config, guiManager), p)));

        super.decorate(player);
    }

    private static int countCrystals(Player player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == Material.END_CRYSTAL) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private static String format(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
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
