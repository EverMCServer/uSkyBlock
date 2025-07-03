package us.talabrek.ultimateskyblock.toxic

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Waterlogged
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import java.util.*

object ToxicItems {
    val toxicBucketLore: Component = Component.text("从污染水源里面舀到的水，有剧毒", NamedTextColor.AQUA).decoration(
        TextDecoration.ITALIC, false
    )
    val antiToxicLore: Component = Component.text("毒性免疫", TextColor.fromHexString("#3DFFB5")).decoration(
        TextDecoration.ITALIC, false
    )
    val toxicCoalLore: Component = Component.text("从污染水源里面挖到的煤，有剧毒", NamedTextColor.AQUA).decoration(
        TextDecoration.ITALIC, false
    )

    fun isAntiToxicLore(item: ItemStack): Boolean {
        val lores = item.lore() ?: return false
        if (lores.size == 0) {
            return false
        }
        if (lores.contains(antiToxicLore)) {
            return true
        }
        // fix old lore
        val lore: String = (antiToxicLore as TextComponent).content()
        val antiLore = lores
            .stream()
            .filter { it.children().size == 1 }
            .filter { it.children()[0] is TextComponent }
            .filter { (it.children()[0] as TextComponent).content() == lore }
            .toList()
        if (antiLore.size == 0) {
            return false
        }
        lores.removeAll(antiLore)
        lores.add(antiToxicLore)
        item.lore(lores)
        return true
    }

    fun isWater(block: Block): Boolean {
        if (block.type == Material.WATER || block is Waterlogged) {
            return (block.blockData as Waterlogged).isWaterlogged
        }
        return false
    }

    fun isToxicBucket(item: ItemStack): Boolean {
        return item.type == Material.WATER_BUCKET && item.itemMeta.hasLore() && item.itemMeta.lore()!!
            .contains(toxicBucketLore)
    }

    fun isToxicCoalBlock(item: ItemStack): Boolean {
        return item.type == Material.COAL_BLOCK && item.itemMeta.hasLore() && item.itemMeta.lore()!!
            .contains(toxicCoalLore)
    }

    fun setBucketToxic(item: ItemStack) {
        val meta = item.itemMeta
        meta.displayName(Component.text("有毒的水桶"))
        meta.lore(Arrays.asList(toxicBucketLore))
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        meta.addEnchant(Enchantment.UNBREAKING, 1, true)
        item.setItemMeta(meta)
    }

    fun getCoalBlockToxic(): ItemStack {
        val ret = ItemStack(Material.COAL_BLOCK, 1)
        val meta = ret.itemMeta
        meta.displayName(Component.text("有毒的煤炭块"))
        meta.lore(Arrays.asList(toxicCoalLore))
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        meta.addEnchant(Enchantment.UNBREAKING, 1, true)
        ret.setItemMeta(meta)
        return ret
    }

    private fun canAntiToxic(item: ItemStack?): Boolean {
        if (item == null || !item.hasItemMeta() || !item.itemMeta.hasLore() || item.itemMeta !is Damageable) {
            return false
        }
        if (isAntiToxicLore(item)) {
            if (item.type == Material.ELYTRA) {
                val d: Damageable = item.itemMeta as Damageable
                if (d.damage < item.type.maxDurability - 1) {
                    return true
                }
            } else {
                return true
            }
        }
        return false
    }
}
