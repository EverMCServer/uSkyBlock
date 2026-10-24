package us.talabrek.ultimateskyblock.command;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.handbook.HandbookLogic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /handbook} - opens a written book configured in {@code handbook.yml}.
 *
 * <p>Opening the book directly (instead of handing the item out) keeps it out of the player's inventory, so a player
 * who throws the handbook away can simply run the command again.
 */
@Singleton
public class HandbookCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "usb.handbook";
    private static final String ADMIN_PERMISSION = "usb.admin.handbook";
    private static final List<String> SUB_COMMANDS = List.of("list", "give", "check", "reload", "help");

    private final HandbookLogic handbook;

    @Inject
    public HandbookCommand(@NotNull HandbookLogic handbook) {
        this.handbook = handbook;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Component.text("你没有权限使用 /" + label + "。", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                printUsage(sender, label);
                return true;
            }
            open(player, handbook.defaultBookId());
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> printUsage(sender, label);
            case "list" -> list(sender, label);
            case "reload" -> reload(sender);
            case "check" -> check(sender);
            case "give" -> give(sender, label, args);
            default -> {
                if (!(sender instanceof Player player)) {
                    printUsage(sender, label);
                    return true;
                }
                open(player, sub);
            }
        }
        return true;
    }

    private void open(Player player, String id) {
        String bookId = handbook.hasBook(id) ? id.toLowerCase(Locale.ROOT) : null;
        if (bookId == null) {
            if (handbook.bookIds().isEmpty()) {
                player.sendMessage(Component.text("handbook.yml 里还没有配置任何书。", NamedTextColor.YELLOW));
                return;
            }
            player.sendMessage(Component.text("没有找到这本手册" + (id == null ? "" : "：" + id) + "。"
                + "可用的有：" + String.join("、", handbook.bookIds()), NamedTextColor.RED));
            return;
        }
        ItemStack item = handbook.createItem(bookId);
        if (item == null) {
            player.sendMessage(Component.text("这本手册是空的。", NamedTextColor.RED));
            return;
        }
        player.openBook(item);
    }

    private void list(CommandSender sender, String label) {
        List<String> ids = handbook.bookIds();
        if (ids.isEmpty()) {
            sender.sendMessage(Component.text("handbook.yml 里还没有配置任何书。", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("可用手册：", NamedTextColor.GOLD));
        for (String id : ids) {
            sender.sendMessage(Component.text(" • ", NamedTextColor.DARK_GRAY)
                .append(Component.text(handbook.titleOf(id), NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/" + label + " " + id))
                    .hoverEvent(HoverEvent.showText(Component.text("点击打开", NamedTextColor.GRAY))))
                .append(Component.text(" (" + id + "，共 " + handbook.pageCount(id) + " 页)", NamedTextColor.GRAY)));
        }
    }

    private void reload(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return;
        }
        handbook.reload();
        sender.sendMessage(Component.text("handbook.yml 已重新读取，共 " + handbook.bookIds().size() + " 本书。",
            NamedTextColor.GREEN));
    }

    private void check(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return;
        }
        for (String line : handbook.check()) {
            sender.sendMessage(Component.text(line, line.startsWith("  ") ? NamedTextColor.YELLOW
                : NamedTextColor.GRAY));
        }
    }

    private void give(CommandSender sender, String label, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法: /" + label + " give <玩家> [手册 id]", NamedTextColor.RED));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("玩家 " + args[1] + " 不在线。", NamedTextColor.RED));
            return;
        }
        String id = args.length >= 3 ? args[2] : handbook.defaultBookId();
        if (!handbook.hasBook(id)) {
            sender.sendMessage(Component.text(id == null ? "handbook.yml 里还没有配置任何书。"
                : "没有找到这本手册：" + id, NamedTextColor.RED));
            return;
        }
        ItemStack item = handbook.createItem(id);
        if (item == null) {
            sender.sendMessage(Component.text("这本手册是空的。", NamedTextColor.RED));
            return;
        }
        Map<Integer, ItemStack> leftovers = target.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            for (ItemStack leftover : leftovers.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), leftover);
            }
            sender.sendMessage(Component.text("背包已满，剩余的放到了 " + target.getName() + " 脚下。",
                NamedTextColor.YELLOW));
        }
        sender.sendMessage(Component.text("已把《" + handbook.titleOf(id) + "》交给 " + target.getName() + "。",
            NamedTextColor.GREEN));
        if (!target.equals(sender)) {
            target.sendMessage(Component.text("你收到了一本《" + handbook.titleOf(id) + "》，打开看看吧。",
                NamedTextColor.GOLD));
        }
    }

    private boolean requireAdmin(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("你没有权限使用这个子命令。", NamedTextColor.RED));
            return false;
        }
        return true;
    }

    private void printUsage(CommandSender sender, String label) {
        sender.sendMessage(Component.text("海岛手册：", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(" /" + label + " [手册 id] - 打开手册（不填则打开默认那本）",
            NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" /" + label + " list - 列出所有手册", NamedTextColor.GRAY));
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text(" /" + label + " give <玩家> [手册 id] - 把手册塞进玩家背包",
                NamedTextColor.GRAY));
            sender.sendMessage(Component.text(" /" + label + " check - 检查 handbook.yml 的跳转与分页",
                NamedTextColor.GRAY));
            sender.sendMessage(Component.text(" /" + label + " reload - 改完 handbook.yml 后重新读取",
                NamedTextColor.GRAY));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(SUB_COMMANDS);
            options.addAll(handbook.bookIds());
            return filter(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            Collection<? extends Player> online = Bukkit.getOnlinePlayers();
            List<String> names = new ArrayList<>(online.size());
            for (Player player : online) {
                names.add(player.getName());
            }
            return filter(names, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return filter(handbook.bookIds(), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
            return filter(handbook.bookIds(), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
