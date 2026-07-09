package me.schoollevel.schoollevel;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.Ticks;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
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

    private NamespacedKey healthKey;
    private NamespacedKey damageKey;
    private NamespacedKey speedKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigData();
        createDataFile();

        healthKey = new NamespacedKey(this, "schoollevel_health");
        damageKey = new NamespacedKey(this, "schoollevel_damage");
        speedKey = new NamespacedKey(this, "schoollevel_speed");
        
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("schoollevel").setExecutor(this);
        
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new SchoolLevelPlaceholderExpansion(this).register();
            getLogger().info("Đã tích hợp và kết nối thành công với PlaceholderAPI! 🟢");
        }
        
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerAttributes(player);
            syncVanillaXpBar(player);
        }

        getLogger().info("SchoolLevel kích hoạt thành công trên nền tảng Paper/Purpur/Leaf! 🚀");
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeAttributeModifiers(player);
        }
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

    public PlayerData getPlayerData(UUID uuid) {
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

    public int getRequiredXp(int level) {
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

    private void sendLevelUpTitle(Player player, int level) {
        String titleText = getConfig().getString("messages.level-up-title", "<yellow><b>LÊN CẤP!</b></yellow>")
                .replace("%level%", String.valueOf(level));
        String subtitleText = getConfig().getString("messages.level-up-subtitle", "Bạn đã đạt cấp %level%")
                .replace("%level%", String.valueOf(level));

        Title title = Title.title(
                mm.deserialize(titleText),
                mm.deserialize(subtitleText),
                Title.Times.times(Ticks.duration(10), Ticks.duration(40), Ticks.duration(10))
        );
        player.showTitle(title);
    }

    /**
     * Đồng bộ dữ liệu SchoolLevel lên thanh cấp độ / kinh nghiệm mặc định của Minecraft (dưới chân màn hình)
     */
    private void syncVanillaXpBar(Player player) {
        PlayerData data = getPlayerData(player.getUniqueId());
        
        // Cài đặt số cấp hiển thị bằng số cấp của SchoolLevel
        player.setLevel(data.level);
        
        if (data.level >= 101) {
            player.setExp(1.0F); // Đột phá rồi thì đổ đầy thanh XP
        } else {
            int req = getRequiredXp(data.level);
            float progress = (float) data.xp / (float) req;
            // Đảm bảo giá trị luôn nằm trong khoảng từ 0.0 đến 1.0 của Minecraft gán cho thanh kinh nghiệm
            player.setExp(Math.min(1.0F, Math.max(0.0F, progress)));
        }
    }

    private void updatePlayerAttributes(Player player) {
        PlayerData data = getPlayerData(player.getUniqueId());
        double percentBonus = data.level * 0.01;

        applyModifier(player, Attribute.GENERIC_MAX_HEALTH, healthKey, percentBonus);
        applyModifier(player, Attribute.GENERIC_ATTACK_DAMAGE, damageKey, percentBonus);
        applyModifier(player, Attribute.GENERIC_MOVEMENT_SPEED, speedKey, percentBonus);
    }

    private void applyModifier(Player player, Attribute attribute, NamespacedKey key, double amount) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return;
        instance.removeModifier(key);
        if (amount > 0) {
            AttributeModifier modifier = new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_SCALAR);
            instance.addModifier(modifier);
        }
    }

    private void removeAttributeModifiers(Player player) {
        AttributeInstance health = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (health != null) health.removeModifier(healthKey);

        AttributeInstance damage = player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        if (damage != null) damage.removeModifier(damageKey);

        AttributeInstance speed = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(speedKey);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        updatePlayerAttributes(player);
        syncVanillaXpBar(player); // Đồng bộ khi người chơi vào server
    }

    /**
     * Chặn việc người chơi nhặt ngọc kinh nghiệm (Vanilla XP) làm lệch thanh hiển thị
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaExpChange(PlayerExpChangeEvent event) {
        // Đặt lượng XP mặc định nhận được về 0, để hệ thống SchoolLevel kiểm soát hoàn toàn thanh này
        event.setAmount(0);
        // Đồng bộ lại thanh XP để đảm bảo nó không bị lỗi hiển thị trực quan
        syncVanillaXpBar(event.getPlayer());
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
            syncVanillaXpBar(player);
            return;
        }

        data.xp += amount;
        int req = getRequiredXp(data.level);
        boolean leveledUp = false;

        while (data.xp >= req && data.level < 100) {
            data.xp -= req;
            data.level++;
            leveledUp = true;
            msg(player, "level-up", "%level%", String.valueOf(data.level));
            sendLevelUpTitle(player, data.level);
            req = getRequiredXp(data.level);
        }

        if (leveledUp) {
            updatePlayerAttributes(player);
        }
        
        // Đồng bộ lại thanh kinh nghiệm mặc định sau mỗi lần nhận thêm XP từ việc đập block/giết mob
        syncVanillaXpBar(player);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean isBreakCommand = label.equalsIgnoreCase("dotpha") || (args.length > 0 && (args[0].equalsIgnoreCase("break") || args[0].equalsIgnoreCase("dotpha")));

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("schoollevel.admin")) {
                msg(sender, "no-permission");
                return true;
            }
            loadConfigData();
            
            for (Player p : Bukkit.getOnlinePlayers()) {
                updatePlayerAttributes(p);
                syncVanillaXpBar(p);
            }
            
            msg(sender, "reload");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Chỉ có người chơi mới có thể thực hiện lệnh này!");
            return true;
        }

        if (isBreakCommand) {
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
            
            updatePlayerAttributes(player);
            syncVanillaXpBar(player); // Cập nhật lại thanh XP sau đột phá
            
            msg(player, "breakthrough-success");
            
            player.showTitle(Title.title(
                    mm.deserialize("<gradient:#f12711:#f5af19><b>🔥 ĐỘT PHÁ THÀNH CÔNG 🔥</b></gradient>"),
                    mm.deserialize("<#ffff00>Đã phá vỡ giới hạn để đạt cấp 101! 👑"),
                    Title.Times.times(Ticks.duration(15), Ticks.duration(60), Ticks.duration(15))
            ));
            return true;
        }

        PlayerData data = getPlayerData(player.getUniqueId());
        int nextXp = data.level >= 101 ? 0 : getRequiredXp(data.level);
        msg(player, "status", 
                "%level%", String.valueOf(data.level), 
                "%xp%", String.valueOf(data.xp), 
                "%next_xp%", String.valueOf(nextXp));
        return true;
    }

    public static class PlayerData {
        public int level;
        public int xp;

        PlayerData(int level, int xp) {
            this.level = level;
            this.xp = xp;
        }
    }

    public static class SchoolLevelPlaceholderExpansion extends me.clip.placeholderapi.expansion.PlaceholderExpansion {
        private final SchoolLevel plugin;
        private final MiniMessage mm = MiniMessage.miniMessage();

        public SchoolLevelPlaceholderExpansion(SchoolLevel plugin) {
            this.plugin = plugin;
        }

        @Override
        public String getIdentifier() {
            return "schoollevel";
        }

        @Override
        public String getAuthor() {
            return "AI_Developer";
        }

        @Override
        public String getVersion() {
            return "1.0.0";
        }

        @Override
        public boolean persist() {
            return true; 
        }

        @Override
        public String onPlaceholderRequest(Player player, String params) {
            if (player == null) return "";

            PlayerData data = plugin.getPlayerData(player.getUniqueId());

            switch (params.toLowerCase()) {
                case "level":
                    return String.valueOf(data.level);
                case "level_formatted":
                    String rawFormat = plugin.getConfig().getString("settings.papi-level-formatted", "⭐ <gradient:#ff5f6d:#ffc371>Level %level%</gradient>");
                    String withVal = rawFormat.replace("%level%", String.valueOf(data.level));
                    return LegacyComponentSerializer.legacySection().serialize(mm.deserialize(withVal));
                case "xp":
                    return String.valueOf(data.xp);
                case "required_xp":
                    return data.level >= 101 ? "0" : String.valueOf(plugin.getRequiredXp(data.level));
                case "xp_progress":
                    int reqXp = plugin.getRequiredXp(data.level);
                    return data.level >= 101 ? "MAX" : (data.xp + "/" + reqXp);
                default:
                    return null;
            }
        }
    }
}
