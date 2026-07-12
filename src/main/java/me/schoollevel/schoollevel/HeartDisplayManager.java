package me.schoollevel.schoollevel;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class HeartDisplayManager {
    
    private final SchoolLevelPlugin plugin;
    private boolean hideExtraHearts = true;
    private int visibleHearts = 10; // 10 tim = 20 máu
    
    public HeartDisplayManager(SchoolLevelPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }
    
    public void loadConfig() {
        hideExtraHearts = plugin.getConfig().getBoolean("settings.hide-extra-hearts", true);
        visibleHearts = plugin.getConfig().getInt("settings.visible-hearts", 10);
    }
    
    // Cập nhật hiển thị tim cho người chơi
    public void updateHeartDisplay(Player player) {
        if (!hideExtraHearts) {
            player.setHealthScaled(false);
            return;
        }
        
        double maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        double visibleHealth = visibleHearts * 2.0; // 10 tim = 20 máu
        
        if (maxHealth > visibleHealth) {
            player.setHealthScale(visibleHealth);
            player.setHealthScaled(true);
        } else {
            player.setHealthScaled(false);
        }
    }
    
    // Cập nhật cho tất cả người chơi online
    public void updateAllPlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            updateHeartDisplay(player);
        }
    }
    
    // Lấy số tim hiển thị
    public int getVisibleHearts() {
        return visibleHearts;
    }
    
    // Lấy máu hiển thị
    public double getVisibleHealth() {
        return visibleHearts * 2.0;
    }
    
    // Kiểm tra xem có đang ẩn tim thừa không
    public boolean isHidingExtraHearts() {
        return hideExtraHearts;
    }
    
    // Bắt đầu task cập nhật tim tự động
    public void startHeartUpdateTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                updateAllPlayers();
            }
        }.runTaskTimer(plugin, 0, 20); // Cập nhật mỗi giây
    }
}