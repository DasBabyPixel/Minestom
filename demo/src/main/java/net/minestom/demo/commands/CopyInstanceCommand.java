package net.minestom.demo.commands;

import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.InstanceContainer;

public class CopyInstanceCommand extends Command {
    public CopyInstanceCommand() {
        super("copyinstance");
        setDefaultExecutor((sender, _) -> {
            if (!(sender instanceof Player player)) return;
            var instance = player.getInstance();
            if (instance == null) return;
            if (!(instance instanceof InstanceContainer container)) return;
            var copy = container.copy();
//            var _ = List.copyOf(copy.getChunks());
            MinecraftServer.getInstanceManager().registerInstance(copy);
            player.setInstance(copy).join();
        });
    }
}
