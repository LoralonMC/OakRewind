package dev.oakheart.oakrewind.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.oakheart.oakrewind.OakRewind;
import dev.oakheart.oakrewind.message.MessageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;

@SuppressWarnings("UnstableApiUsage")
public class OakRewindCommand {

    private final OakRewind plugin;

    public OakRewindCommand(OakRewind plugin) {
        this.plugin = plugin;
    }

    private MessageManager messages() {
        return plugin.getMessageManager();
    }

    private void send(CommandSender sender, Optional<Component> message) {
        message.ifPresent(sender::sendMessage);
    }

    public void register() {
        LifecycleEventManager<Plugin> manager = plugin.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register(buildCommand(), "Rewind explosions with rebuilding animations", List.of("or", "rewind"));
        });
    }

    private LiteralCommandNode<CommandSourceStack> buildCommand() {
        return Commands.literal("oakrewind")
                .executes(ctx -> {
                    send(ctx.getSource().getSender(), messages().usage());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("reload")
                        .requires(src -> src.getSender().hasPermission("oakrewind.reload"))
                        .executes(ctx -> {
                            plugin.reloadCustomConfig();
                            send(ctx.getSource().getSender(), messages().reloadSuccess());
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
