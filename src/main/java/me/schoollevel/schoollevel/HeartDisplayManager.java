package me.schoollevel.schoollevel;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class HeartDisplayManager {
    
    private final SchoolLevelPlugin plugin;
    private boolean hideExtraHearts = true;
    private int visibleHearts = 10;
    
    public HeartDisplayManager(SchoolLevelPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }
    
    public void loadConfig() {
        hideExtraHearts = plugin.getConfig().getBoolean("settings.hide-extra-hearts", true);
        visibleHearts = plugin.getConfig().getInt("settings.visible-hearts", 10);
    }
    
    public void updateHeartDisplay(Player player) {
        if (!hideExtraHearts) {
            player.setHealthScaled(false);
            return;
        }
        
        double maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        double visibleHealth = visibleHearts * 2.0;
        
        if (maxHealth > visibleHealth) {
            player.setHealthScale(visibleHealth);
            player.setHealthScaled(true);
        } else {
            player.setHealthScaled(false);
        }
    }
    
    public void updateAllPlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            updateHeartDisplay(player);
        }
    }
    
    public int getVisibleHearts() {
        return visibleHearts;
    }
    
    public double getVisibleHealth() {
        return visibleHearts * 2.0;
    }
    
    public boolean isHidingExtraHearts() {
        return hideExtraHearts;
    }
    
    public void startHeartUpdateTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                updateAllPlayers();
            }
        }.runTaskTimer(plugin, 0, 20);
    }
}