package com.tierdisplay;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI placeholders:
 *   %tierdisplay_tier%           -> HT1 (empty if unranked)
 *   %tierdisplay_kit%            -> Mace (empty if unranked)
 *   %tierdisplay_prefix%         -> before-name text, & colour codes
 *   %tierdisplay_prefix_legacy%  -> same, section-sign colour codes
 *   %tierdisplay_prefix_mm%      -> same, as MiniMessage tags
 *   %tierdisplay_suffix%         -> after-name text ("HT1 Mace"), & colour codes
 *   %tierdisplay_suffix_legacy%  -> same, section-sign colour codes
 *   %tierdisplay_suffix_mm%      -> same, as MiniMessage tags
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
        String kit = plugin.getKit(player.getUniqueId());

        switch (params.toLowerCase()) {
            case "tier":
                return tier == null ? "" : tier;
            case "kit":
                return kit == null ? "" : kit;
            case "prefix":
                return plugin.rawPrefix(tier, kit);
            case "prefix_legacy":
                return ChatColor.translateAlternateColorCodes('&', plugin.rawPrefix(tier, kit));
            case "prefix_mm":
                return toMiniMessage(plugin.rawPrefix(tier, kit));
            case "suffix":
                return plugin.rawSuffix(tier, kit);
            case "suffix_legacy":
                return ChatColor.translateAlternateColorCodes('&', plugin.rawSuffix(tier, kit));
            case "suffix_mm":
                return toMiniMessage(plugin.rawSuffix(tier, kit));
            default:
                return null;
        }
    }

    /** Converts &-colour codes (e.g. &8[&6HT1&8] &r) into MiniMessage tags. */
    private static String toMiniMessage(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length()) {
                String tag = tagFor(Character.toLowerCase(text.charAt(i + 1)));
                if (tag != null) {
                    out.append(tag);
                    i++; // skip the code character
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String tagFor(char code) {
        switch (code) {
            case '0': return "<black>";
            case '1': return "<dark_blue>";
            case '2': return "<dark_green>";
            case '3': return "<dark_aqua>";
            case '4': return "<dark_red>";
            case '5': return "<dark_purple>";
            case '6': return "<gold>";
            case '7': return "<gray>";
            case '8': return "<dark_gray>";
            case '9': return "<blue>";
            case 'a': return "<green>";
            case 'b': return "<aqua>";
            case 'c': return "<red>";
            case 'd': return "<light_purple>";
            case 'e': return "<yellow>";
            case 'f': return "<white>";
            case 'k': return "<obfuscated>";
            case 'l': return "<bold>";
            case 'm': return "<strikethrough>";
            case 'n': return "<underlined>";
            case 'o': return "<italic>";
            case 'r': return "<reset>";
            default: return null;
        }
    }
}
