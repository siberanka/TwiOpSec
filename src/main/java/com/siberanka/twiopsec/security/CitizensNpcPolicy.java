package com.siberanka.twiopsec.security;

import com.siberanka.twiopsec.config.SecuritySettings;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/** Narrow compatibility policy for genuine Citizens player NPC entities. */
@SuppressWarnings("deprecation") // Citizens' documented dependency-free NPC marker uses Bukkit entity metadata.
public final class CitizensNpcPolicy {
    private static final String CITIZENS_PLUGIN_NAME = "Citizens";
    private static final String NPC_METADATA_KEY = "NPC";
    private final Plugin citizens;

    private CitizensNpcPolicy(Plugin citizens) {
        this.citizens = citizens;
    }

    public static CitizensNpcPolicy discover(Server server) {
        Objects.requireNonNull(server, "server");
        return new CitizensNpcPolicy(server.getPluginManager().getPlugin(CITIZENS_PLUGIN_NAME));
    }

    static CitizensNpcPolicy forTesting(Plugin citizens) {
        return new CitizensNpcPolicy(citizens);
    }

    public boolean mayRunServerCommand(Player player, String rawCommand, SecuritySettings settings) {
        if (!settings.citizensServerCommandNpcBypass() || citizens == null || !citizens.isEnabled()) {
            return false;
        }
        CommandParser.ParsedCommand command = CommandParser.parse(rawCommand);
        if (command.status() != CommandParser.ParseStatus.VALID || !command.label().equals("server")
                || command.arguments().size() != 1
                || !command.arguments().getFirst().matches("[A-Za-z0-9_.:-]{1,64}")) {
            return false;
        }
        try {
            for (MetadataValue value : player.getMetadata(NPC_METADATA_KEY)) {
                if (value.getOwningPlugin() == citizens && value.asBoolean()) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // A broken or stale metadata value must fail closed.
        }
        return false;
    }

    public boolean isCitizensAvailable() {
        return citizens != null && citizens.isEnabled();
    }
}
