package dev.oakheart.oakrewind.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.oakheart.command.CommandRegistrar;
import dev.oakheart.message.MessageManager;
import dev.oakheart.oakrewind.OakRewind;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public class OakRewindCommand {

    private final OakRewind plugin;

    public OakRewindCommand(OakRewind plugin) {
        this.plugin = plugin;
    }

    private MessageManager messages() {
        return plugin.getMessageManager();
    }

    public void register() {
        CommandRegistrar.register(plugin, buildCommand(),
                "Rewind explosions with rebuilding animations", List.of("or", "rewind"));
    }

    private LiteralCommandNode<CommandSourceStack> buildCommand() {
        return Commands.literal("oakrewind")
                .executes(ctx -> {
                    messages().sendCommand(ctx.getSource().getSender(), "usage");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("reload")
                        .requires(src -> src.getSender().hasPermission("oakrewind.reload"))
                        .executes(ctx -> {
                            plugin.reloadCustomConfig();
                            messages().sendCommand(ctx.getSource().getSender(), "reload-success");
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
