package com.tierdisplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Shows a player's best tier + kit (fetched from the Cracked Tiers website) around their name.
 *
 * Paper / Spigot (1.12+): scoreboard team prefix/suffix -> tab list AND above the head.
 * Folia: the scoreboard API is unsupported there, so only the tab list name is set,
 *        using each player's entity scheduler.
 * Optional: PlaceholderAPI placeholders for use in other plugins' configs.
 *
 * Web requests run on the plugin's own threads, so the Bukkit scheduler is never
 * touched off the main thread (it does not exist on Folia).
 */
public class TierDisplay extends JavaPlugin implements Listener {

    /** Best tier + kit a player can have. */
    static final class TierInfo {
        final String tier;
        final String kit;

        TierInfo(String tier, String kit) {
            this.tier = tier;
            this.kit = kit;
        }
    }

    /** Online players, kept by join/quit events so other threads never call Bukkit for them. */
    private final Map<UUID, Player> online = new ConcurrentHashMap<UUID, Player>();

    /** Latest best tier/kit per online player (absent = unranked). Read by the placeholders. */
    private final Map<UUID, TierInfo> infos = new ConcurrentHashMap<UUID, TierInfo>();

    /** Lower-case Minecraft name -> Discord ID, built from the website's leaderboard. */
    private volatile Map<String, String> idsByName = new HashMap<String, String>();
    private volatile long indexLoadedAt = 0;
    private volatile long lastIndexAttempt = 0;
    private volatile boolean loggedIndexOnce = false;

    private ScheduledExecutorService pool;
    private ScheduledFuture<?> refreshFuture;

    private int maxAffix;
    private boolean folia;
    private Method playerGetScheduler;
    private Method entityRun;

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        maxAffix = detectMaxAffix();

        if (!initFolia()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        pool = Executors.newScheduledThreadPool(2, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "TierDisplay-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });

        getServer().getPluginManager().registerEvents(this, this);
        hookPlaceholders();
        for (Player p : Bukkit.getOnlinePlayers()) online.put(p.getUniqueId(), p);

        startTimer();
        refreshPlayers(new ArrayList<Player>(online.values()));
        getLogger().info("TierDisplay enabled" + (folia
                ? " [Folia: tab list only, scoreboard API is unsupported on Folia]." : "."));
    }

    @Override
    public void onDisable() {
        if (pool != null) pool.shutdownNow();
        if (!folia) {
            try {
                for (Player p : online.values()) removeTeam(p);
            } catch (Exception ignored) {
                // server is shutting down
            }
        }
        online.clear();
        infos.clear();
    }

    private void hookPlaceholders() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        try {
            new TierExpansion(this).register();
            getLogger().info("Registered PlaceholderAPI placeholders: %tierdisplay_tier%, %tierdisplay_kit%, "
                    + "%tierdisplay_prefix[_legacy|_mm]%, %tierdisplay_suffix[_legacy|_mm]%");
        } catch (Throwable t) {
            getLogger().warning("Could not register PlaceholderAPI placeholders: " + t);
        }
    }

    private void startTimer() {
        if (refreshFuture != null) refreshFuture.cancel(false);
        long minutes = Math.max(1, getConfig().getLong("refresh-minutes", 5));
        refreshFuture = pool.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                refreshPlayers(new ArrayList<Player>(online.values()));
            }
        }, minutes, minutes, TimeUnit.MINUTES);
    }

    // ------------------------------------------------------------------
    // Used by the PlaceholderAPI expansion
    // ------------------------------------------------------------------

    /** The player's best tier, or null if unranked / not fetched yet. */
    public String getTier(UUID id) {
        TierInfo info = infos.get(id);
        return info == null ? null : info.tier;
    }

    /** The kit that produced the player's best tier, or null. */
    public String getKit(UUID id) {
        TierInfo info = infos.get(id);
        return info == null ? null : info.kit;
    }

    /** The formatted prefix with & colour codes (empty if nothing should be shown). */
    public String rawPrefix(String tier, String kit) {
        FileConfiguration cfg = getConfig();
        String format = tier == null
                ? cfg.getString("unranked-format", "")
                : cfg.getString("format", "&8[{color}{tier}&8] &r");
        return fill(format, tier, kit);
    }

    /** The formatted suffix with & colour codes (empty if nothing should be shown). */
    public String rawSuffix(String tier, String kit) {
        FileConfiguration cfg = getConfig();
        String format = tier == null
                ? cfg.getString("unranked-suffix-format", "")
                : cfg.getString("suffix-format", " {color}{tier} &7{kit}");
        return fill(format, tier, kit);
    }

    private String fill(String format, String tier, String kit) {
        if (format == null || format.isEmpty()) return "";
        FileConfiguration cfg = getConfig();
        String color = tier == null
                ? "&f"
                : cfg.getString("tier-colors." + tier.toUpperCase(), cfg.getString("tier-colors.default", "&f"));
        return format.replace("{color}", color)
                .replace("{tier}", tier == null ? "" : tier)
                .replace("{kit}", kit == null ? "" : kit);
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        online.put(p.getUniqueId(), p);
        refreshPlayers(Collections.singletonList(p));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        online.remove(p.getUniqueId());
        infos.remove(p.getUniqueId());
        if (!folia) removeTeam(p); // Paper: main thread. On Folia the tab entry leaves with the player.
    }

    // ------------------------------------------------------------------
    // Fetching
    // ------------------------------------------------------------------

    /** Fetches tiers on a plugin thread, then applies each result on the right thread. */
    private void refreshPlayers(final Collection<Player> players) {
        if (players.isEmpty() || pool == null || pool.isShutdown()) return;
        final List<Player> list = new ArrayList<Player>(players);

        pool.execute(new Runnable() {
            @Override
            public void run() {
                boolean warned = false;
                for (final Player p : list) {
                    try {
                        final TierInfo info = fetchTier(p.getName()); // null = unranked
                        runForPlayer(p, new Runnable() {
                            @Override
                            public void run() {
                                if (online.containsKey(p.getUniqueId())) apply(p, info);
                            }
                        });
                    } catch (Exception ex) {
                        // keep whatever the player already has on failure
                        if (!warned) {
                            getLogger().warning("Could not fetch tier for " + p.getName() + ": " + ex.getMessage());
                            warned = true;
                        }
                    }
                }
            }
        });
    }

    /** Runs off the main thread. Returns the player's best tier + kit, or null if unranked. */
    private TierInfo fetchTier(String playerName) throws Exception {
        ensureIndex(playerName);

        String id = idsByName.get(playerName.toLowerCase());
        if (id == null) return null; // not on the leaderboard

        FileConfiguration cfg = getConfig();
        String url = baseUrl() + cfg.getString("api.player-path", "/api/player/{id}")
                .replace("{id}", URLEncoder.encode(id, "UTF-8"));

        String body = httpGet(url);
        if (body == null) return null;

        JsonElement root = new JsonParser().parse(body);
        if (!root.isJsonObject()) return null;
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("kits") || !obj.get("kits").isJsonArray()) return null;

        String bestTier = null;
        String bestKit = null;
        int bestScore = Integer.MAX_VALUE;
        for (JsonElement el : obj.getAsJsonArray("kits")) {
            if (!el.isJsonObject()) continue;
            JsonObject kitObj = el.getAsJsonObject();
            String t = str(kitObj, "tier");
            if (t == null) continue;
            int score = tierScore(t);
            if (bestTier == null || score < bestScore) {
                bestTier = t;
                bestScore = score;
                String label = str(kitObj, "label");
                if (label == null) label = str(kitObj, "kit");
                bestKit = label == null ? "" : capitalize(label);
            }
        }
        return bestTier == null ? null : new TierInfo(bestTier, bestKit);
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Lower = better: HT1, LT1, HT2, LT2 ... LT5. Unknown formats sort last. */
    private static int tierScore(String tier) {
        String t = tier.trim().toUpperCase();
        try {
            if ((t.startsWith("HT") || t.startsWith("LT")) && t.length() > 2) {
                int n = Integer.parseInt(t.substring(2));
                return n * 2 + (t.startsWith("LT") ? 1 : 0);
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return Integer.MAX_VALUE - 1;
    }

    // ------------------------------------------------------------------
    // Leaderboard index (Minecraft name -> Discord ID)
    // ------------------------------------------------------------------

    private synchronized void ensureIndex(String playerName) throws Exception {
        long now = System.currentTimeMillis();
        long maxAge = Math.max(1, getConfig().getLong("refresh-minutes", 5)) * 60000L;
        long age = now - indexLoadedAt;

        boolean stale = indexLoadedAt == 0 || age > maxAge - 10000L;
        boolean missing = indexLoadedAt != 0 && age > 60000L
                && !idsByName.containsKey(playerName.toLowerCase());

        if ((stale || missing) && now - lastIndexAttempt > 30000L) {
            lastIndexAttempt = now;
            loadIndex();
        }
        if (indexLoadedAt == 0) {
            throw new IllegalStateException("ranked player list not loaded yet");
        }
    }

    private void loadIndex() throws Exception {
        FileConfiguration cfg = getConfig();
        String overviewUrl = baseUrl() + cfg.getString("api.overview-path", "/api/leaderboard/overview");
        int pageSize = Math.max(1, cfg.getInt("api.page-size", 25));

        Map<String, String> map = new HashMap<String, String>();
        int offset = 0;

        for (int page = 0; page < 500; page++) {
            String body = httpGet(overviewUrl + "?limit=" + pageSize + "&offset=" + offset);
            if (body == null) throw new IllegalStateException("overview endpoint not found: " + overviewUrl);

            JsonArray rows = findPlayers(new JsonParser().parse(body), 0);
            if (rows == null || rows.size() == 0) break;

            int added = 0;
            for (JsonElement el : rows) {
                if (!el.isJsonObject()) continue;
                JsonObject row = el.getAsJsonObject();
                String ign = str(row, "ign");
                String id = str(row, "id");
                if (ign != null && id != null && map.put(ign.toLowerCase(), id) == null) added++;
            }
            offset += rows.size();
            if (added == 0) break; // nothing new (or no usable rows), stop paging
        }

        idsByName = map;
        indexLoadedAt = System.currentTimeMillis();

        if (map.isEmpty()) {
            getLogger().warning("The website's leaderboard returned no players with an 'ign' and 'id' "
                    + "field (" + overviewUrl + "). If that's wrong, the response format differs from what "
                    + "this plugin expects.");
        } else if (!loggedIndexOnce) {
            getLogger().info("Loaded " + map.size() + " ranked players from the website.");
        }
        loggedIndexOnce = true;
    }

    /** Finds the array of player rows (objects that have an "ign" field) anywhere in the response. */
    private static JsonArray findPlayers(JsonElement el, int depth) {
        if (el == null || depth > 3) return null;
        if (el.isJsonArray()) {
            JsonArray a = el.getAsJsonArray();
            if (a.size() > 0 && a.get(0).isJsonObject() && a.get(0).getAsJsonObject().has("ign")) return a;
            return null;
        }
        if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                JsonArray found = findPlayers(e.getValue(), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        try {
            String s = o.get(key).getAsString().trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private String baseUrl() {
        String base = getConfig().getString("api.base-url", "");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    /** Returns the response body, or null on 404. Throws on any other failure. */
    private String httpGet(String url) throws Exception {
        FileConfiguration cfg = getConfig();
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
            if (code != 200) throw new IllegalStateException("HTTP " + code + " for " + url);

            StringBuilder body = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            } finally {
                reader.close();
            }
            return body.toString();
        } finally {
            conn.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // Applying
    // ------------------------------------------------------------------

    /** Must run on the player's thread (main thread on Paper, entity scheduler on Folia). */
    private void apply(Player player, TierInfo info) {
        String tier = info == null ? null : info.tier;
        String kit = info == null ? null : info.kit;

        // Remember it for the placeholders, whatever display mode is used.
        if (info == null) infos.remove(player.getUniqueId());
        else infos.put(player.getUniqueId(), info);

        if (!getConfig().getBoolean("direct-display", false)) {
            // Placeholder-only mode: another plugin shows the tier, so don't touch names.
            if (!folia) removeTeam(player);
            return;
        }

        String prefix = trimAffix(ChatColor.translateAlternateColorCodes('&', rawPrefix(tier, kit)));
        String suffix = trimAffix(ChatColor.translateAlternateColorCodes('&', rawSuffix(tier, kit)));

        if (folia) {
            boolean nothing = prefix.isEmpty() && suffix.isEmpty();
            player.setPlayerListName(nothing ? null : prefix + player.getName() + suffix);
            return;
        }

        if (prefix.isEmpty() && suffix.isEmpty()) {
            removeTeam(player);
            return;
        }

        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = teamName(player);
        Team team = board.getTeam(teamName);
        if (team == null) team = board.registerNewTeam(teamName);

        team.setPrefix(prefix);
        team.setSuffix(suffix);
        if (!team.hasEntry(player.getName())) team.addEntry(player.getName());
    }

    private String trimAffix(String text) {
        if (text.length() <= maxAffix) return text;
        String cut = text.substring(0, maxAffix);
        // don't leave a dangling colour-code character at the end
        if (cut.endsWith(String.valueOf(ChatColor.COLOR_CHAR))) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut;
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

    /** Team prefix/suffix are limited to 16 chars on 1.12 and below, 64 on 1.13+. */
    private static int detectMaxAffix() {
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

    // ------------------------------------------------------------------
    // Paper / Folia scheduling
    // ------------------------------------------------------------------

    /** Returns false only if this is Folia and its scheduler could not be hooked. */
    private boolean initFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
        } catch (ClassNotFoundException e) {
            folia = false;
            return true; // normal Paper/Spigot
        }
        try {
            playerGetScheduler = Player.class.getMethod("getScheduler");
            Class<?> entityScheduler =
                    Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
            entityRun = entityScheduler.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
            folia = true;
            return true;
        } catch (Exception e) {
            getLogger().severe("Folia detected but its entity scheduler could not be hooked: " + e);
            return false;
        }
    }

    /** Runs the task where it is safe to touch this player. */
    private void runForPlayer(Player player, final Runnable task) {
        if (folia) {
            try {
                Object scheduler = playerGetScheduler.invoke(player);
                entityRun.invoke(scheduler, this, new Consumer<Object>() {
                    @Override
                    public void accept(Object scheduledTask) {
                        task.run();
                    }
                }, (Runnable) null);
            } catch (Exception e) {
                getLogger().warning("Could not schedule task on Folia: " + e);
            }
        } else {
            Bukkit.getScheduler().runTask(this, task);
        }
    }

    // ------------------------------------------------------------------
    // Command: /tierdisplay reload | refresh [player]
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("tierdisplay.admin")) {
            sender.sendMessage("\u00A7cYou don't have permission.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("\u00A7eUsage: /" + label + " <reload|refresh [player]>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload":
                reloadConfig();
                indexLoadedAt = 0;
                lastIndexAttempt = 0;
                startTimer();
                refreshPlayers(new ArrayList<Player>(online.values()));
                sender.sendMessage("\u00A7aTierDisplay config reloaded, refreshing tiers...");
                break;
            case "refresh":
                lastIndexAttempt = 0;
                indexLoadedAt = 0;
                if (args.length >= 2) {
                    Player target = null;
                    for (Player p : online.values()) {
                        if (p.getName().equalsIgnoreCase(args[1])) target = p;
                    }
                    if (target == null) {
                        sender.sendMessage("\u00A7cThat player isn't online.");
                        return true;
                    }
                    refreshPlayers(Collections.singletonList(target));
                    sender.sendMessage("\u00A7aRefreshing " + target.getName() + "...");
                } else {
                    refreshPlayers(new ArrayList<Player>(online.values()));
                    sender.sendMessage("\u00A7aRefreshing all online players...");
                }
                break;
            default:
                sender.sendMessage("\u00A7eUsage: /" + label + " <reload|refresh [player]>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (args.length == 1) {
            for (String s : Arrays.asList("reload", "refresh")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("refresh")) {
            for (Player p : online.values()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}
