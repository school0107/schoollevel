package me.schoollevel.schoollevel;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.Statistic;

import java.text.DecimalFormat;

public class SchoolLevelExpansion extends PlaceholderExpansion {

    private static final DecimalFormat DF = new DecimalFormat("#.##");
    private static final DecimalFormat DF_MONEY = new DecimalFormat("#,###.##");

    @Override
    public String getIdentifier() {
        return "schoollevel";
    }

    @Override
    public String getAuthor() {
        return "SchoolLevel";
    }

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null) return "";
        
        SchoolLevelPlugin plugin = SchoolLevelPlugin.getInstance();
        SchoolLevelPlugin.DataManager.PlayerData data = plugin.getDataManager().getPlayerData(player);
        
        switch (params.toLowerCase()) {
            case "level":
                return String.valueOf(data.getLevel());
                
            case "level_formatted":
                return plugin.color("&6✦ Cấp " + data.getLevel());
                
            case "xp":
                return DF.format(data.getXp());
                
            case "required_xp":
                return DF.format(plugin.getLevelManager().getRequiredXP(data.getLevel()));
                
            case "xp_progress":
                return DF.format((data.getXp() / plugin.getLevelManager().getRequiredXP(data.getLevel())) * 100);
                
            case "blocks_broken":
                return String.valueOf(data.getBlocksBroken());
                
            case "minutes_online":
                return String.valueOf(player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60);
                
            case "health":
                return DF.format(player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
                
            case "damage":
                return DF.format(player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).getValue());
                
            case "armor":
                return DF.format(player.getAttribute(Attribute.GENERIC_ARMOR).getValue());
                
            case "speed":
                return DF.format(player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getValue() * 1000);
                
            case "money":
                return DF_MONEY.format(data.getMoney());
                
            case "money_formatted":
                return plugin.color("&e$" + DF_MONEY.format(data.getMoney()));
                
            default:
                return "";
        }
    }
}