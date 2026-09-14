package com.siberanka.twiopsec.update;

import com.siberanka.twiopsec.config.SecuritySettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class UpdateNotice {
    private final Map<UUID, String> deliveredVersions = new ConcurrentHashMap<>();

    public boolean claim(Player player, SecuritySettings settings, UpdateChecker.Result result) {
        if (result.status() != UpdateChecker.Status.AVAILABLE || result.releaseUri() == null
                || !player.isOnline() || !player.isOp()
                || !settings.isTrustedOperator(player.getUniqueId())) {
            return false;
        }
        String previous = deliveredVersions.put(player.getUniqueId(), result.latestVersion());
        return !result.latestVersion().equals(previous);
    }

    public Component message(UpdateChecker.Result result) {
        String release = result.releaseUri().toASCIIString();
        Component link = Component.text(release, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(release));
        return Component.text("TwiOpSec " + result.latestVersion()
                        + " is available (" + result.source().name().toLowerCase(Locale.ROOT)
                        + "). Download: ", NamedTextColor.GOLD)
                .append(link);
    }

    public void clear(UUID uuid) {
        deliveredVersions.remove(uuid);
    }

    public void clearAll() {
        deliveredVersions.clear();
    }
}
