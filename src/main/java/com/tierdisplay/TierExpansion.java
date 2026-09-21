package com.tierdisplay;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI placeholders:
 *   %tierdisplay_tier%           -> HT1 (empty if unranked)
 *   %tierdisplay_prefix%         -> formatted prefix with & colour codes
 *   %tierdisplay_prefix_legacy%  -> formatted prefix with section-sign colour codes
 */
public class TierExpansion extends PlaceholderExpansion {

    private final TierDisplay plugin;

    public TierExpansion(TierDisplay plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "tierdisplay";
    }

    @Override
    public String getAuthor() {
        return "TierDisplay";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    /** Lets PlaceholderAPI drop these placeholders when TierDisplay is disabled. */
    @Override
    public String getRequiredPlugin() {
        return "TierDisplay";
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        String tier = plugin.getTier(player.getUniqueId());

        switch (params.toLowerCase()) {
            case "tier":
                return tier == null ? "" : tier;
            case "prefix":
                return plugin.rawPrefix(tier);
            case "prefix_legacy":
                return ChatColor.translateAlternateColorCodes('&', plugin.rawPrefix(tier));
            default:
                return null;
        }
    }
}
