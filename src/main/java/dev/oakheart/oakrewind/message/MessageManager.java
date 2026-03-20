package dev.oakheart.oakrewind.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.Optional;

/**
 * Manages all player-facing messages with MiniMessage formatting.
 */
public class MessageManager {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private String reloadSuccess;
    private String noPermission;
    private String usage;

    /**
     * Loads all messages from the configuration.
     *
     * @param config The plugin configuration node
     */
    public void load(ConfigurationNode config) {
        reloadSuccess = config.node("messages", "reload-success").getString(
                "<gradient:green:aqua>OakRewind</gradient> <gray>»</gray> <green>Configuration reloaded!");
        noPermission = config.node("messages", "no-permission").getString(
                "<gradient:green:aqua>OakRewind</gradient> <gray>»</gray> <red>You don't have permission to use this command.");
        usage = config.node("messages", "usage").getString(
                "<gradient:green:aqua>OakRewind</gradient> <gray>»</gray> <yellow>Rewind explosions with beautiful animations.<newline><white>/oakrewind reload</white> <gray>-</gray> Reloads the configuration.");
    }

    public Optional<Component> reloadSuccess() {
        return parse(reloadSuccess);
    }

    public Optional<Component> noPermission() {
        return parse(noPermission);
    }

    public Optional<Component> usage() {
        return parse(usage);
    }

    private Optional<Component> parse(String message) {
        if (message == null || message.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(miniMessage.deserialize(message));
    }
}
