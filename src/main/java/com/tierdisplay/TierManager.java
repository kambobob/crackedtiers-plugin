package com.tierdisplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches tiers from the website and shows them via a per-player scoreboard team.
 * A team prefix shows both in the tab list and above the player's head.
 * Written for Java 8 / Minecraft 1.12 through the newest versions.
 */
public class TierManager {

    private final TierDisplay plugin;
    private final int maxPrefix;

    /** Last known tier per online player (absent = unranked). */
    private final Map<UUID, String> tiers = new ConcurrentHashMap<UUID, String>();

    public TierManager(TierDisplay plugin) {
        this.plugin = plugin;
        this.maxPrefix = detectMaxPrefix();
    }

    /** Team prefixes are limited to 16 chars on 1.12 and below, 64 on 1.13+. */
    private static int detectMaxPrefix() {
        try {
            String v = Bukkit.getBukkitVersion().split("-")[0]; // e.g. 1.12.2 / 1.21.4 / 26.1
            String[] p = v.split("\\.");
            int major = Integer.parseInt(p[0]);
            if (major == 1 && p.length > 1 && Integer.parseInt(p[1]) < 13) return 16;
        } catch (Exception ignored) {
            // fall through to the modern limit
        }
        return 64;
    }

    /** Fetch tiers for the given players off-thread, then apply on the main thread. */
    public void refreshPlayers(Collection<? extends Player> players) {
        final Map<UUID, String> names = new HashMap<UUID, String>();
        for (Player p : players) names.put(p.getUniqueId(), p.getName());
        if (names.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final Map<UUID, String> results = new HashMap<UUID, String>();
                boolean warned = false;
                for (Map.Entry<UUID, String> e : names.entrySet()) {
                    try {
                        results.put(e.getKey(), fetchTier(e.getValue())); // null = unranked
                    } catch (Exception ex) {
                        // keep whatever the player already had on failure
                        if (!warned) {
                            plugin.getLogger().warning("Could not fetch tier for " + e.getValue()
                                    + ": " + ex.getMessage());
                            warned = true;
                        }
                    }
                }
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        for (Map.Entry<UUID, String> r : results.entrySet()) {
                            Player p = Bukkit.getPlayer(r.getKey());
                            if (p == null || !p.isOnline()) continue;
                            if (r.getValue() == null) tiers.remove(r.getKey());
                            else tiers.put(r.getKey(), r.getValue());
                            apply(p, r.getValue());
                        }
                    }
                });
            }
        });
    }

    /** Runs off the main thread. Returns null if the player has no tier. */
    private String fetchTier(String playerName) throws Exception {
        FileConfiguration cfg = plugin.getConfig();
        String url = cfg.getString("api.url", "")
                .replace("{player}", URLEncoder.encode(playerName, "UTF-8"));
        int timeoutMs = Math.max(1, cfg.getInt("api.timeout-seconds", 5)) * 1000;

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "TierDisplay");

            String key = cfg.getString("api.key", "");
            if (!key.isEmpty()) {
                conn.setRequestProperty(cfg.getString("api.key-header", "Authorization"), key);
            }

            int code = conn.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IllegalStateException("HTTP " + code);

            StringBuilder body = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            } finally {
                reader.close();
            }

            JsonElement root = new JsonParser().parse(body.toString());
            if (!root.isJsonObject()) return null;
            JsonObject obj = root.getAsJsonObject();

            String field = cfg.getString("api.tier-field", "tier");
            if (!obj.has(field) || obj.get(field).isJsonNull()) return null;

            String tier = obj.get(field).getAsString().trim();
            return tier.isEmpty() ? null : tier;
        } finally {
            conn.disconnect();
        }
    }

    /** Main thread only. */
    public void apply(Player player, String tier) {
        FileConfiguration cfg = plugin.getConfig();
        String format;
        if (tier == null) {
            format = cfg.getString("unranked-format", "");
        } else {
            format = cfg.getString("format", "&8[{color}{tier}&8] &r");
        }

        if (format == null || format.isEmpty()) {
            removeTeam(player);
            return;
        }

        String color = "&f";
        if (tier != null) {
            color = cfg.getString("tier-colors." + tier.toUpperCase(),
                    cfg.getString("tier-colors.default", "&f"));
        }

        String prefix = ChatColor.translateAlternateColorCodes('&',
                format.replace("{color}", color).replace("{tier}", tier == null ? "" : tier));
        if (prefix.length() > maxPrefix) {
            prefix = prefix.substring(0, maxPrefix);
            // don't leave a dangling colour-code character at the end
            if (prefix.endsWith(String.valueOf(ChatColor.COLOR_CHAR))) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
        }

        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = teamName(player);
        Team team = board.getTeam(teamName);
        if (team == null) team = board.registerNewTeam(teamName);

        team.setPrefix(prefix);
        if (!team.hasEntry(player.getName())) team.addEntry(player.getName());
    }

    public void remove(Player player) {
        tiers.remove(player.getUniqueId());
        removeTeam(player);
    }

    public void removeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) removeTeam(p);
        tiers.clear();
    }

    private void removeTeam(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(teamName(player));
        if (team != null) team.unregister();
    }

    /** 16 chars: "td" + first 14 hex chars of the UUID. */
    private String teamName(Player player) {
        return "td" + player.getUniqueId().toString().replace("-", "").substring(0, 14);
    }
}
