package us.talabrek.ultimateskyblock.command.admin;

import dk.lockfuglsang.minecraft.command.CompositeCommand;
import org.bukkit.command.CommandSender;

import us.talabrek.ultimateskyblock.uSkyBlock;
import us.talabrek.ultimateskyblock.island.task.GridRegenerateTask;
import java.util.Map;

public class GridCommand extends CompositeCommand {
    private final uSkyBlock plugin;

    public GridCommand(uSkyBlock plugin) {
        super("gridregen", "usb.admin.chunk", "regenerate skygrid world");
        this.plugin = plugin;
    }
    @Override
    public boolean execute(final CommandSender sender, String alias, Map<String, Object> data, String... args) {
        sender.sendMessage("Start Regenerating Chunks");
        GridRegenerateTask gt = new GridRegenerateTask(plugin);
        gt.GridRegen();
        return true;
    }
}