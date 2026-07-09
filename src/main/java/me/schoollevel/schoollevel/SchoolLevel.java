package me.schoollevel.schoollevel;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SchoolLevel extends JavaPlugin implements Listener, CommandExecutor {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, PlayerData> dataCache = new HashMap<>();
    private final Map<Material, Integer> blockXpMap = new HashMap<>();
    private final Map<EntityType, Integer> mobXpMap = new HashMap<>();
    private File dataFile;
    private FileConfiguration dataConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigData();
        createDataFile();
        
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("schoollevel").setExecutor(this);
        
        getLogger().info("SchoolLevel kích hoạt thành công trên nền tảng Paper/Purpur/Leaf! 🚀");
    }

    @Override
    public void onDisable() {
        saveAllData();
    }

    private void loadConfigData() {
        reloadConfig();
        blockXpMap.clear();
        mobXpMap.clear();

        FileConfiguration config = getConfig();
        
        if (config.getConfigurationSection("blocks") != null) {
            for (String key : config.getConfigurationSection("blocks").getKeys(false)) {
                try {
                    blockXpMap.put(Material.valueOf(key.toUpperCase()), config.getInt("blocks." + key));
                } catch (IllegalArgumentException ignored) {}
            }
        }

        if (config.getConfigurationSection("mobs") != null) {
            for (String key : config.getConfigurationSection("mobs").getKeys(false)) {
                try {
                    mobXpMap.put(EntityType.valueOf(key.toUpperCase()), config.getInt("mobs." + key));
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    private void createDataFile() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private PlayerData getPlayerData(UUID uuid) {
        return dataCache.computeIfAbsent(uuid, k -> {
            int lvl = dataConfig.getInt(uuid + ".level", 1);
            int xp = dataConfig.getInt(uuid + ".xp", 0);
            return new PlayerData(lvl, xp);
        });
    }

    private void saveAllData() {
        dataCache.forEach((uuid, data) -> {
            dataConfig.set(uuid + ".level", data.level);
            dataConfig.set(uuid + ".xp", data.xp);
        });
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int getRequiredXp(int level) {
        int base = getConfig().getInt("settings.base-xp", 100);
        double mult = getConfig().getDouble("settings.xp-multiplier", 1.5);
        return (int) (base * Math.pow(mult, level - 1));
    }

    private void msg(CommandSender sender, String path, String... placeholders) {
        String message = getConfig().getString("messages." + path, "");
        for (int i = 0; i < placeholders.length; i += 2) {
            message = message.replace(placeholders[i], placeholders[i + 1]);
        }
        String prefix = getConfig().getString("messages.prefix", "");
        sender.sendMessage(mm.deserialize(prefix + message));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        int xpToAdd = blockXpMap.getOrDefault(event.getBlock().getType(), 0);
        if (xpToAdd > 0) addXp(p, xpToAdd);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        int xpToAdd = mobXpMap.getOrDefault(event.getEntityType(), 0);
        if (xpToAdd > 0) addXp(killer, xpToAdd);
    }

    private void addXp(Player player, int amount) {
        PlayerData data = getPlayerData(player.getUniqueId());
        if (data.level >= 100) {
            if (data.level == 100) {
                msg(player, "breakthrough-needed");
            }
            return;
        }

        data.xp += amount;
        int req = getRequiredXp(data.level);

        while (data.xp >= req && data.level < 100) {
            data.xp -= req;
            data.level++;
            msg(player, "level-up", "%level%", String.valueOf(data.level));
            req = getRequiredXp(data.level);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("schoollevel.admin")) {
                msg(sender, "no-permission");
                return true;
            }
            loadConfigData();
            msg(sender, "reload");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Chi co nguoi choi moi dung duoc lenh nay!");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("break")) {
            PlayerData data = getPlayerData(player.getUniqueId());
            if (data.level < 100) {
                msg(player, "not-max");
                return true;
            }
            if (data.level >= 101) {
                msg(player, "already-break");
                return true;
            }
            data.level = 101;
            data.xp = 0;
            msg(player, "breakthrough-success");
            return true;
        }

        // Lệnh xem trạng thái mặc định (/sl)
        PlayerData data = getPlayerData(player.getUniqueId());
        int nextXp = data.level >= 101 ? 0 : getRequiredXp(data.level);
        msg(player, "status", 
                "%level%", String.valueOf(data.level), 
                "%xp%", String.valueOf(data.xp), 
                "%next_xp%", String.valueOf(nextXp));
        return true;
    }

    private static class PlayerData {
        int level;
        int xp;

        PlayerData(int level, int xp) {
            this.level = level;
            this.xp = xp;
        }
    }
}
