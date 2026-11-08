package us.talabrek.ultimateskyblock.enddragon;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * One of the eight challenge difficulties. The first pass only uses the stat/reward values; future special mechanics
 * can be layered on top without changing the menu or the fight state machine.
 */
public record DragonDifficulty(
    int level,
    int cost,
    @NotNull String displayName,
    double maxHealth,
    double damageMultiplier,
    int timeoutSeconds,
    @NotNull List<ItemStack> itemRewards,
    int currency,
    int xp,
    @NotNull List<String> commands
) {
    public DragonDifficulty {
        itemRewards = itemRewards.stream().map(ItemStack::clone).toList();
        commands = List.copyOf(commands);
    }

    public List<ItemStack> createItemRewards() {
        return itemRewards.stream().map(ItemStack::clone).toList();
    }
}
