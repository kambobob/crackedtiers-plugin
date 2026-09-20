package com.tierdisplay;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TierDisplay extends JavaPlugin {

    private TierManager manager;
    private BukkitTask refreshTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new TierManager(this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(manager), this);
        startRefreshTask();
        // Handles /reload and late loads
        manager.refreshPlayers(Bukkit.getOnlinePlayers());
        getLogger().info("TierDisplay enabled.");
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) refreshTask.cancel();
        if (manager != null) manager.removeAll();
    }

    private void startRefreshTask() {
        if (refreshTask != null) refreshTask.cancel();
        long minutes = Math.max(1, getConfig().getLong("refresh-minutes", 5));
        long ticks = minutes * 60L * 20L;
        refreshTask = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                manager.refreshPlayers(Bukkit.getOnlinePlayers());
            }
        }, ticks, ticks);
    }

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
                startRefreshTask();
                manager.refreshPlayers(Bukkit.getOnlinePlayers());
                sender.sendMessage("\u00A7aTierDisplay config reloaded, refreshing tiers...");
                break;
            case "refresh":
                if (args.length >= 2) {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage("\u00A7cThat player isn't online.");
                        return true;
                    }
                    manager.refreshPlayers(Collections.singletonList(target));
                    sender.sendMessage("\u00A7aRefreshing " + target.getName() + "...");
                } else {
                    manager.refreshPlayers(Bukkit.getOnlinePlayers());
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
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}
