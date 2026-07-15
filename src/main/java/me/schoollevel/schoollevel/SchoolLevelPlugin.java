package me.schoollevel.schoollevel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Statistic;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class SchoolLevelPlugin extends JavaPlugin implements Listener {

    public static final int XP_BAR_UPDATE_INTERVAL = 20;
    public static final int ACTION_BAR_INTERVAL = 20;
    public static final DecimalFormat DF = new DecimalFormat("#.##");
    public static final DecimalFormat DF_MONEY = new DecimalFormat("#,###.##");

    private static SchoolLevelPlugin instance;
    private net.milkbowl.vault.economy.Economy economy;

    private DataManager dataManager;
    private LevelManager levelManager;
    private AttributeManager attributeManager;
    private XPManager xpManager;
    private BreakthroughManager breakthroughManager;
    private ConfigManager configManager;
    private PermissionManager permissionManager;
    private HeartDisplayManager heartDisplayManager;
    private PartyManager partyManager;
    private PlaceholderHook placeholderHook;

    public final int[] BREAKTHROUGH_LEVELS = {100, 200, 300, 400, 500};

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();
        instance = this;

        if (!setupEconomy()) {
            getLogger().warning("Vault Economy not found! Money features will be disabled.");
        }

        saveDefaultConfig();
        configManager = new ConfigManager();

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        dataManager = new DataManager();
        levelManager = new LevelManager();
        attributeManager = new AttributeManager();
        attributeManager.reloadConfig();
        xpManager = new XPManager();
        breakthroughManager = new BreakthroughManager();
        permissionManager = new PermissionManager();
        heartDisplayManager = new HeartDisplayManager(this);
        partyManager = new PartyManager(this);

        registerCommands();
        getServer().getPluginManager().registerEvents(this, this);
        registerPlaceholderAPI();
        startScheduledTasks();

        getLogger().info("§a✅ SchoolLevel Plugin enabled! (Took " + (System.currentTimeMillis() - startTime) + "ms)");
    }

    @Override
    public void onDisable() {
        if (dataManager != null) {
            dataManager.saveAllData();
        }
        getLogger().info("§c❌ SchoolLevel Plugin disabled!");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp =
            getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    private void registerCommands() {
        if (getCommand("profile") != null) {
            getCommand("profile").setExecutor(new ProfileCommand());
        }
        if (getCommand("schoollevel") != null) {
            getCommand("schoollevel").setExecutor(new SchoolLevelCommand());
        }
        if (getCommand("schoollevelgivebreakthrough") != null) {
            getCommand("schoollevelgivebreakthrough").setExecutor(new GiveBreakthroughCommand());
        }
        if (getCommand("party") != null) {
            getCommand("party").setExecutor(new PartyCommand());
        }
    }

    private void registerPlaceholderAPI() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholderHook = new PlaceholderHook();
            placeholderHook.register();
            placeholderHook.loadLevelColors();
            getLogger().info("✅ PlaceholderAPI registered successfully!");
        }
    }

    private void startScheduledTasks() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!Bukkit.getOnlinePlayers().isEmpty()) {
                    updateAllActionBars();
                }
            }
        }.runTaskTimer(this, 0, ACTION_BAR_INTERVAL);
        
        heartDisplayManager.startHeartUpdateTask();
        
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    updateVanillaXPBar(player);
                }
            }
        }.runTaskTimer(this, 0, XP_BAR_UPDATE_INTERVAL);
    }

    private void updateVanillaXPBar(Player player) {
        DataManager.PlayerData data = dataManager.getPlayerData(player);
        int level = data.getLevel();
        double xp = data.getXp();
        double required = levelManager.getRequiredXP(level);

        player.setLevel(level);
        player.setExp((float) Math.min(xp / required, 1.0));
    }

    private void updateAllActionBars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerActionBar(player);
        }
    }

    private void updatePlayerActionBar(Player player) {
        DataManager.PlayerData data = dataManager.getPlayerData(player);
        int level = data.getLevel();
        double xp = data.getXp();
        double required = levelManager.getRequiredXP(level);

        int progress = Math.min((int) ((xp / required) * 20), 20);
        String bar = "■".repeat(Math.max(0, progress)) + "□".repeat(Math.max(0, 20 - progress));

        double currentHealth = player.getHealth();
        double maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        double damage = player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).getValue();

        double totalMoney = configManager.useVaultEconomy() ? economy.getBalance(player) : data.getMoney();
        String moneyDisplay = configManager.formatMoney(totalMoney);

        String actionBarText = configManager.getActionBarFormat()
            .replace("{level}", String.valueOf(level))
            .replace("{bar}", bar)
            .replace("{xp}", formatNumber(xp))
            .replace("{required}", formatNumber(required))
            .replace("{health}", DF.format(currentHealth) + "/" + DF.format(maxHealth))
            .replace("{maxhealth}", DF.format(maxHealth))
            .replace("{damage}", DF.format(damage))
            .replace("{money}", moneyDisplay);

        player.sendActionBar(Component.text(color(actionBarText)));
    }

    private String formatNumber(double number) {
        if (number >= 1000000) {
            return String.format("%.1f", number / 1000000) + "m";
        } else if (number >= 1000) {
            return String.format("%.1f", number / 1000) + "k";
        } else {
            return String.format("%.0f", number);
        }
    }

    public static SchoolLevelPlugin getInstance() { return instance; }
    public DataManager getDataManager() { return dataManager; }
    public LevelManager getLevelManager() { return levelManager; }
    public AttributeManager getAttributeManager() { return attributeManager; }
    public XPManager getXpManager() { return xpManager; }
    public BreakthroughManager getBreakthroughManager() { return breakthroughManager; }
    public ConfigManager getConfigManager() { return configManager; }
    public PermissionManager getPermissionManager() { return permissionManager; }
    public HeartDisplayManager getHeartDisplayManager() { return heartDisplayManager; }
    public PartyManager getPartyManager() { return partyManager; }
    public PlaceholderHook getPlaceholderHook() { return placeholderHook; }
    public net.milkbowl.vault.economy.Economy getEconomy() { return economy; }
    public boolean hasEconomy() { return economy != null; }

    public String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    // ==================== PERMISSION MANAGER ====================
    public class PermissionManager {
        private final Map<String, Double> xpMultipliers = new HashMap<>();
        private final Map<String, Double> moneyMultipliers = new HashMap<>();
        
        public PermissionManager() {
            loadConfig();
        }
        
        public void loadConfig() {
            xpMultipliers.clear();
            moneyMultipliers.clear();
            
            FileConfiguration config = getConfig();
            
            xpMultipliers.put("schoollevel.xp.2", 2.0);
            xpMultipliers.put("schoollevel.xp.3", 3.0);
            xpMultipliers.put("schoollevel.xp.4", 4.0);
            xpMultipliers.put("schoollevel.xp.5", 5.0);
            moneyMultipliers.put("schoollevel.money.2", 2.0);
            moneyMultipliers.put("schoollevel.money.3", 3.0);
            moneyMultipliers.put("schoollevel.money.4", 4.0);
            moneyMultipliers.put("schoollevel.money.5", 5.0);
            
            if (config.contains("permission-multipliers.xp")) {
                for (String key : config.getConfigurationSection("permission-multipliers.xp").getKeys(false)) {
                    xpMultipliers.put(key, config.getDouble("permission-multipliers.xp." + key));
                }
            }
            if (config.contains("permission-multipliers.money")) {
                for (String key : config.getConfigurationSection("permission-multipliers.money").getKeys(false)) {
                    moneyMultipliers.put(key, config.getDouble("permission-multipliers.money." + key));
                }
            }
            
            getLogger().info("✅ Loaded " + xpMultipliers.size() + " XP multipliers");
            getLogger().info("✅ Loaded " + moneyMultipliers.size() + " Money multipliers");
        }
        
        public double getXPMultiplier(Player player) {
            double highest = 1.0;
            for (Map.Entry<String, Double> entry : xpMultipliers.entrySet()) {
                if (player.hasPermission(entry.getKey())) {
                    if (entry.getValue() > highest) {
                        highest = entry.getValue();
                    }
                }
            }
            return highest;
        }
        
        public double getMoneyMultiplier(Player player) {
            double highest = 1.0;
            for (Map.Entry<String, Double> entry : moneyMultipliers.entrySet()) {
                if (player.hasPermission(entry.getKey())) {
                    if (entry.getValue() > highest) {
                        highest = entry.getValue();
                    }
                }
            }
            return highest;
        }
    }

    // ==================== PLACEHOLDER API HOOK ====================
    public class PlaceholderHook extends me.clip.placeholderapi.expansion.PlaceholderExpansion {
        private final Map<Integer, LevelColor> levelColors = new HashMap<>();
        
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
        public boolean canRegister() {
            return true;
        }
        
        public void loadLevelColors() {
            levelColors.clear();
            FileConfiguration config = getConfig();
            
            if (config.contains("level-colors")) {
                for (String key : config.getConfigurationSection("level-colors").getKeys(false)) {
                    try {
                        int level = Integer.parseInt(key);
                        String rgb = config.getString("level-colors." + key + ".color", "&#FFFFFF");
                        String displayName = config.getString("level-colors." + key + ".name", "&7&l");
                        String symbol = config.getString("level-colors." + key + ".symbol", "✦");
                        levelColors.put(level, new LevelColor(rgb, displayName, symbol));
                    } catch (NumberFormatException ignored) {}
                }
            }
            
            if (levelColors.isEmpty()) {
                levelColors.put(500, new LevelColor("&#FFD700", "&6&lHuyền Thoại", "✦"));
                levelColors.put(400, new LevelColor("&#9B59B6", "&d&lThần Thánh", "✦"));
                levelColors.put(300, new LevelColor("&#E74C3C", "&c&lAnh Hùng", "✦"));
                levelColors.put(200, new LevelColor("&#3498DB", "&b&lThạc Sĩ", "✦"));
                levelColors.put(100, new LevelColor("&#2ECC71", "&a&lCử Nhân", "✦"));
                levelColors.put(50, new LevelColor("&#F1C40F", "&e&lHọc Viên", "✦"));
                levelColors.put(0, new LevelColor("&#95A5A6", "&7&lTân Binh", "✦"));
            }
        }

        @Override
        public String onPlaceholderRequest(Player player, String params) {
            if (player == null) return "";
            
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            
            double money;
            if (configManager.useVaultEconomy()) {
                money = economy.getBalance(player);
            } else {
                money = data.getMoney();
            }
            
            String param = params.toLowerCase();
            
            switch (param) {
                case "level": 
                    return String.valueOf(data.getLevel());
                    
                case "level_formatted": 
                    return getColoredLevel(data.getLevel());
                    
                case "level_color":
                    return getLevelColor(data.getLevel());
                    
                case "level_rgb":
                    return getLevelRGB(data.getLevel());
                    
                case "level_name":
                    return getLevelName(data.getLevel());
                    
                case "level_symbol":
                    return getLevelSymbol(data.getLevel());
                    
                case "level_full":
                    return getLevelFullDisplay(data.getLevel());
                    
                case "xp": 
                    return DF.format(data.getXp());
                    
                case "required_xp": 
                    return DF.format(levelManager.getRequiredXP(data.getLevel()));
                    
                case "xp_progress": 
                    return DF.format((data.getXp() / levelManager.getRequiredXP(data.getLevel())) * 100);
                    
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
                    return DF_MONEY.format(money);
                    
                case "money_formatted": 
                    return configManager.formatMoney(money);
                    
                default: 
                    return "";
            }
        }
        
        private LevelColor getLevelColorData(int level) {
            LevelColor result = levelColors.get(0);
            for (Map.Entry<Integer, LevelColor> entry : levelColors.entrySet()) {
                if (level >= entry.getKey() && entry.getKey() > 0) {
                    result = entry.getValue();
                }
            }
            return result;
        }
        
        private String getLevelColor(int level) {
            return getLevelColorData(level).getColor();
        }
        
        private String getLevelRGB(int level) {
            return getLevelColorData(level).getRgb();
        }
        
        private String getLevelName(int level) {
            return getLevelColorData(level).getName();
        }
        
        private String getLevelSymbol(int level) {
            return getLevelColorData(level).getSymbol();
        }
        
        private String getColoredLevel(int level) {
            LevelColor lc = getLevelColorData(level);
            return lc.getColor() + lc.getSymbol() + level;
        }
        
        private String getLevelFullDisplay(int level) {
            LevelColor lc = getLevelColorData(level);
            return lc.getColor() + lc.getSymbol() + " " + lc.getName() + " Cấp " + level;
        }
        
        public class LevelColor {
            private final String rgb;
            private final String color;
            private final String name;
            private final String symbol;
            
            public LevelColor(String rgb, String displayName, String symbol) {
                this.rgb = rgb;
                this.color = displayName;
                this.name = ChatColor.stripColor(org.bukkit.ChatColor.translateAlternateColorCodes('&', displayName));
                this.symbol = symbol;
            }
            
            public String getRgb() { return rgb; }
            public String getColor() { return color; }
            public String getName() { return name; }
            public String getSymbol() { return symbol; }
        }
    }

    // ==================== CONFIG MANAGER ====================
    public class ConfigManager {
        private final Map<Material, Double> blockXP = new ConcurrentHashMap<>();
        private final Map<EntityType, Double> mobXP = new ConcurrentHashMap<>();
        private final Map<Material, Double> blockMoney = new ConcurrentHashMap<>();
        private final Map<EntityType, Double> mobMoney = new ConcurrentHashMap<>();

        private String currencySymbol = "💰 ";
        private String currencyName = "Xu";
        private boolean showMoneyMessage = true;
        private boolean useVaultEconomy = true;
        private String moneyFormat = "{symbol}{amount}";
        private String actionBarFormat = "&e⚡ &fCấp &6{level} &7| &a{bar} &fXP: &b{xp}/{required} &7| &c❤ {health} &7| &6⚔ {damage} &7| &e💰 {money}";
        private int moneyMessageDuration = 60;
        private double baseBlockMoney = 0.1;
        private double levelMoneyBonus = 0.05;

        public ConfigManager() { reload(); }

        public void reload() {
            reloadConfig();
            loadXPConfig();
            loadMoneyConfig();
            loadSettings();
        }

        private void loadXPConfig() {
            blockXP.clear();
            FileConfiguration config = getConfig();
            if (config.contains("xp.blocks")) {
                for (String key : config.getConfigurationSection("xp.blocks").getKeys(false)) {
                    try {
                        Material mat = Material.getMaterial(key.toUpperCase());
                        if (mat != null) blockXP.put(mat, config.getDouble("xp.blocks." + key));
                    } catch (Exception ignored) {}
                }
            }
            mobXP.clear();
            if (config.contains("xp.mobs")) {
                for (String key : config.getConfigurationSection("xp.mobs").getKeys(false)) {
                    try {
                        EntityType type = EntityType.valueOf(key.toUpperCase());
                        mobXP.put(type, config.getDouble("xp.mobs." + key));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }

        private void loadMoneyConfig() {
            blockMoney.clear();
            FileConfiguration config = getConfig();
            if (config.contains("money.blocks")) {
                for (String key : config.getConfigurationSection("money.blocks").getKeys(false)) {
                    try {
                        Material mat = Material.getMaterial(key.toUpperCase());
                        if (mat != null) blockMoney.put(mat, config.getDouble("money.blocks." + key));
                    } catch (Exception ignored) {}
                }
            }
            mobMoney.clear();
            if (config.contains("money.mobs")) {
                for (String key : config.getConfigurationSection("money.mobs").getKeys(false)) {
                    try {
                        EntityType type = EntityType.valueOf(key.toUpperCase());
                        mobMoney.put(type, config.getDouble("money.mobs." + key));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }

        private void loadSettings() {
            FileConfiguration config = getConfig();
            currencySymbol = config.getString("settings.currency.symbol", "💰 ");
            currencyName = config.getString("settings.currency.name", "Xu");
            moneyFormat = config.getString("settings.currency.format", "{symbol}{amount}");
            showMoneyMessage = config.getBoolean("settings.show-money-message", true);
            useVaultEconomy = config.getBoolean("settings.use-vault-economy", true);
            actionBarFormat = config.getString("settings.actionbar-format",
                "&e⚡ &fCấp &6{level} &7| &a{bar} &fXP: &b{xp}/{required} &7| &c❤ {health} &7| &6⚔ {damage} &7| &e💰 {money}");
            moneyMessageDuration = config.getInt("settings.money-message-duration", 60);
            baseBlockMoney = config.getDouble("settings.base-block-money", 0.1);
            levelMoneyBonus = config.getDouble("settings.level-money-bonus", 0.05);
        }

        public double getBlockXP(Material material) { return blockXP.getOrDefault(material, 0.0); }
        public double getMobXP(EntityType type) { return mobXP.getOrDefault(type, 0.0); }
        public double getBlockMoney(Material material) { return blockMoney.getOrDefault(material, -1.0); }
        public double getMobMoney(EntityType type) { return mobMoney.getOrDefault(type, -1.0); }
        public String getCurrencySymbol() { return currencySymbol; }
        public String getCurrencyName() { return currencyName; }
        public String getMoneyFormat() { return moneyFormat; }
        public boolean showMoneyMessage() { return showMoneyMessage; }
        public boolean useVaultEconomy() { return useVaultEconomy && hasEconomy(); }
        public String getActionBarFormat() { return actionBarFormat; }
        public int getMoneyMessageDuration() { return moneyMessageDuration; }
        public double getBaseBlockMoney() { return baseBlockMoney; }
        public double getLevelMoneyBonus() { return levelMoneyBonus; }

        public String formatMoney(double amount) {
            return moneyFormat.replace("{symbol}", currencySymbol)
                .replace("{name}", currencyName)
                .replace("{amount}", DF_MONEY.format(amount));
        }

        public double getCalculatedBlockMoney(Player player, Material material) {
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            int level = data.getLevel();
            double baseMoney = getBlockMoney(material);
            if (baseMoney < 0) baseMoney = getBaseBlockMoney();
            return baseMoney + (level * getLevelMoneyBonus());
        }
    }

    // ==================== DATA MANAGER ====================
    public class DataManager {
        private final Map<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();
        private final File dataFile;
        private FileConfiguration dataConfig;

        public DataManager() {
            this.dataFile = new File(getDataFolder(), "data.yml");
            if (!dataFile.exists()) {
                try {
                    dataFile.createNewFile();
                    dataConfig = YamlConfiguration.loadConfiguration(dataFile);
                    dataConfig.save(dataFile);
                } catch (IOException e) {
                    getLogger().log(Level.SEVERE, "Could not create data.yml", e);
                    dataConfig = new YamlConfiguration();
                }
            } else {
                dataConfig = YamlConfiguration.loadConfiguration(dataFile);
            }
            loadAllData();
        }

        public PlayerData getPlayerData(Player player) {
            return playerDataMap.computeIfAbsent(player.getUniqueId(), PlayerData::new);
        }

        public void savePlayerData(Player player) { savePlayerData(player.getUniqueId()); }

        private void savePlayerData(UUID uuid) {
            PlayerData data = playerDataMap.get(uuid);
            if (data == null) return;
            String path = uuid.toString();
            dataConfig.set(path + ".level", data.getLevel());
            dataConfig.set(path + ".xp", data.getXp());
            dataConfig.set(path + ".blocksBroken", data.getBlocksBroken());
            dataConfig.set(path + ".money", data.getMoney());
            
            List<Integer> brokenLevels = new ArrayList<>(data.getBrokenThroughLevels());
            dataConfig.set(path + ".brokenThroughLevels", brokenLevels);
            
            saveData();
        }

        public void saveAllData() {
            for (UUID uuid : playerDataMap.keySet()) savePlayerData(uuid);
        }

        private void loadAllData() {
            if (dataConfig == null || dataConfig.getKeys(false).isEmpty()) return;
            for (String uuidStr : dataConfig.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    PlayerData data = new PlayerData(uuid);
                    data.setLevel(dataConfig.getInt(uuidStr + ".level", 1));
                    data.setXp(dataConfig.getDouble(uuidStr + ".xp", 0));
                    data.setBlocksBroken(dataConfig.getInt(uuidStr + ".blocksBroken", 0));
                    data.setMoney(dataConfig.getDouble(uuidStr + ".money", 0));
                    
                    List<Integer> brokenLevels = dataConfig.getIntegerList(uuidStr + ".brokenThroughLevels");
                    data.setBrokenThroughLevels(new HashSet<>(brokenLevels));
                    
                    playerDataMap.put(uuid, data);
                } catch (IllegalArgumentException ignored) {}
            }
        }

        private void saveData() {
            try { dataConfig.save(dataFile); } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to save data", e);
            }
        }

        public void reload() {
            dataConfig = YamlConfiguration.loadConfiguration(dataFile);
            playerDataMap.clear();
            loadAllData();
        }

        public class PlayerData {
            private final UUID uuid;
            private int level = 1;
            private double xp = 0;
            private int blocksBroken = 0;
            private Set<Integer> brokenThroughLevels = new HashSet<>();
            private double money = 0;
            private int lastBreakthroughNotify = -1;

            public PlayerData(UUID uuid) { this.uuid = uuid; }

            public UUID getUuid() { return uuid; }
            public int getLevel() { return level; }
            public void setLevel(int level) { this.level = level; }
            public double getXp() { return xp; }
            public void setXp(double xp) { this.xp = xp; }
            public void addXp(double amount) { this.xp += amount; }
            public int getBlocksBroken() { return blocksBroken; }
            public void setBlocksBroken(int blocks) { this.blocksBroken = blocks; }
            public void incrementBlocksBroken() { this.blocksBroken++; }
            public double getMoney() { return money; }
            public void setMoney(double money) { this.money = money; }
            public void addMoney(double amount) { this.money += amount; }
            
            public boolean hasBrokenThrough(int level) {
                return brokenThroughLevels.contains(level);
            }
            
            public void addBrokenThrough(int level) {
                brokenThroughLevels.add(level);
            }
            
            public Set<Integer> getBrokenThroughLevels() {
                return brokenThroughLevels;
            }
            
            public void setBrokenThroughLevels(Set<Integer> levels) {
                this.brokenThroughLevels = levels != null ? levels : new HashSet<>();
            }
            
            public int getLastBreakthroughNotify() {
                return lastBreakthroughNotify;
            }
            
            public void setLastBreakthroughNotify(int level) {
                this.lastBreakthroughNotify = level;
            }
        }
    }

    // ==================== LEVEL MANAGER ====================
    public class LevelManager {
        private double xpMultiplier = 1.1;
        private int maxLevel = 500;
        
        public void reloadConfig() {
            xpMultiplier = getConfig().getDouble("settings.level-xp-multiplier", 1.1);
            maxLevel = getConfig().getInt("settings.max-level", 500);
        }
        
        public double getRequiredXP(int level) {
            return 100 * Math.pow(level, 1.5) * Math.pow(xpMultiplier, level);
        }

        public int getMaxLevel() { return maxLevel; }
        
        public int getNextBreakthroughLevel(int currentLevel) {
            for (int level : BREAKTHROUGH_LEVELS) {
                if (currentLevel < level) {
                    return level;
                }
            }
            return -1;
        }

        public void addXP(Player player, double amount) {
            if (amount <= 0) return;
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            int currentLevel = data.getLevel();
            
            int nextBreakthrough = getNextBreakthroughLevel(currentLevel);
            
            if (nextBreakthrough > 0 && currentLevel == nextBreakthrough - 1) {
                if (!data.hasBrokenThrough(nextBreakthrough)) {
                    if (data.getLastBreakthroughNotify() != nextBreakthrough) {
                        breakthroughManager.notifyBreakthrough(player, nextBreakthrough);
                        data.setLastBreakthroughNotify(nextBreakthrough);
                        dataManager.savePlayerData(player);
                    }
                    return;
                }
            }
            
            if (currentLevel >= maxLevel) {
                return;
            }

            data.addXp(amount);
            
            int maxAllowedLevel = getMaxAllowedLevel(data);
            
            while (data.getXp() >= getRequiredXP(data.getLevel()) && data.getLevel() < maxAllowedLevel) {
                data.setXp(data.getXp() - getRequiredXP(data.getLevel()));
                levelUp(player);
            }

            attributeManager.updateAttributes(player);
            dataManager.savePlayerData(player);
            updateVanillaXPBar(player);
        }
        
        private int getMaxAllowedLevel(DataManager.PlayerData data) {
            int currentLevel = data.getLevel();
            int nextBreakthrough = getNextBreakthroughLevel(currentLevel);
            
            if (nextBreakthrough > 0) {
                if (data.hasBrokenThrough(nextBreakthrough)) {
                    for (int level : BREAKTHROUGH_LEVELS) {
                        if (level > nextBreakthrough && !data.hasBrokenThrough(level)) {
                            return level - 1;
                        }
                    }
                    return maxLevel;
                }
                return nextBreakthrough - 1;
            }
            
            return maxLevel;
        }

        private void levelUp(Player player) {
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            int newLevel = data.getLevel() + 1;
            data.setLevel(newLevel);
            attributeManager.updateAttributes(player);

            String title = getConfig().getString("messages.level-up-title", "&6&l⬆ LEVEL UP!");
            String subtitle = getConfig().getString("messages.level-up-subtitle", "&fBạn đã đạt &6Cấp %level%");
            title = color(title);
            subtitle = color(subtitle.replace("%level%", String.valueOf(newLevel)));

            player.showTitle(Title.title(
                Component.text(title),
                Component.text(subtitle),
                Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(2000), Duration.ofMillis(500))
            ));
        }
    }

    // ==================== ATTRIBUTE MANAGER ====================
    public class AttributeManager {
        private static final String HEALTH_MODIFIER = "schoollevel_health";
        private static final String DAMAGE_MODIFIER = "schoollevel_damage";
        private static final String SPEED_MODIFIER = "schoollevel_speed";
        
        private final Map<UUID, Double> lastAppliedLevel = new HashMap<>();
        
        private double healthMultiplier = 0.01;
        private double damageMultiplier = 0.01;
        private double speedMultiplier = 0.001;
        
        public void reloadConfig() {
            FileConfiguration config = getConfig();
            healthMultiplier = config.getDouble("settings.attributes.health-multiplier", 0.01);
            damageMultiplier = config.getDouble("settings.attributes.damage-multiplier", 0.01);
            speedMultiplier = config.getDouble("settings.attributes.speed-multiplier", 0.001);
            
            getLogger().info("✅ Loaded attribute multipliers:");
            getLogger().info("  - Health: " + healthMultiplier + " per level");
            getLogger().info("  - Damage: " + damageMultiplier + " per level");
            getLogger().info("  - Speed: " + speedMultiplier + " per level");
        }

        public void updateAttributes(Player player) {
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            int level = data.getLevel();
            UUID uuid = player.getUniqueId();
            
            Double previousLevel = lastAppliedLevel.get(uuid);
            
            if (previousLevel != null && level <= previousLevel) {
                heartDisplayManager.updateHeartDisplay(player);
                return;
            }
            
            removeAllModifiers(player);
            
            double healthBonus = level * healthMultiplier * 20.0;
            double damageBonus = level * damageMultiplier;
            double speedBonus = level * speedMultiplier;
            
            applyModifier(player, Attribute.GENERIC_MAX_HEALTH, HEALTH_MODIFIER, healthBonus);
            applyModifier(player, Attribute.GENERIC_ATTACK_DAMAGE, DAMAGE_MODIFIER, damageBonus);
            applyModifier(player, Attribute.GENERIC_MOVEMENT_SPEED, SPEED_MODIFIER, speedBonus);
            
            lastAppliedLevel.put(uuid, (double) level);
            heartDisplayManager.updateHeartDisplay(player);
        }
        
        private void removeAllModifiers(Player player) {
            removeModifier(player, Attribute.GENERIC_MAX_HEALTH, HEALTH_MODIFIER);
            removeModifier(player, Attribute.GENERIC_ATTACK_DAMAGE, DAMAGE_MODIFIER);
            removeModifier(player, Attribute.GENERIC_MOVEMENT_SPEED, SPEED_MODIFIER);
        }
        
        private void removeModifier(Player player, Attribute attribute, String modifierName) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) return;
            
            instance.getModifiers().stream()
                .filter(mod -> mod.getKey() != null && mod.getKey().getKey().equals(modifierName))
                .forEach(instance::removeModifier);
        }
        
        private void applyModifier(Player player, Attribute attribute, String modifierName, double amount) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) return;
            
            if (amount > 0.001) {
                NamespacedKey key = new NamespacedKey(SchoolLevelPlugin.this, modifierName);
                instance.addModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER));
            }
        }
        
        public double getHealthBonusPercent(int level) {
            return level * healthMultiplier * 100;
        }
        
        public double getDamageBonusPercent(int level) {
            return level * damageMultiplier * 100;
        }
        
        public double getSpeedBonusPercent(int level) {
            return level * speedMultiplier * 100;
        }
        
        public double getHealthMultiplier() { return healthMultiplier; }
        public double getDamageMultiplier() { return damageMultiplier; }
        public double getSpeedMultiplier() { return speedMultiplier; }
    }

    // ==================== XP MANAGER ====================
    public class XPManager {
        public void handleBlockBreak(Player player, Material material) {
            double xp = configManager.getBlockXP(material);
            if (xp > 0) {
                double multiplier = permissionManager.getXPMultiplier(player);
                xp = xp * multiplier;
                
                if (partyManager.isInParty(player)) {
                    partyManager.shareXP(player, xp);
                } else {
                    levelManager.addXP(player, xp);
                }
                
                dataManager.getPlayerData(player).incrementBlocksBroken();
            }
            
            double moneyEarned = configManager.getCalculatedBlockMoney(player, material);
            if (moneyEarned > 0) {
                double multiplier = permissionManager.getMoneyMultiplier(player);
                moneyEarned = moneyEarned * multiplier;
                addMoney(player, moneyEarned);
            }
        }

        public void handleMobKill(Player player, EntityType entityType) {
            double xp = configManager.getMobXP(entityType);
            if (xp > 0) {
                double multiplier = permissionManager.getXPMultiplier(player);
                xp = xp * multiplier;
                
                if (partyManager.isInParty(player)) {
                    partyManager.shareXP(player, xp);
                } else {
                    levelManager.addXP(player, xp);
                }
            }
            
            double moneyEarned = configManager.getMobMoney(entityType);
            if (moneyEarned > 0) {
                double multiplier = permissionManager.getMoneyMultiplier(player);
                moneyEarned = moneyEarned * multiplier;
                addMoney(player, moneyEarned);
            }
        }

        private void addMoney(Player player, double amount) {
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            if (configManager.useVaultEconomy()) {
                economy.depositPlayer(player, amount);
            } else {
                data.addMoney(amount);
            }
            dataManager.savePlayerData(player);
        }
    }

    // ==================== BREAKTHROUGH MANAGER ====================
    public class BreakthroughManager {
        private final NamespacedKey BREAKTHROUGH_KEY = new NamespacedKey(SchoolLevelPlugin.this, "breakthrough_item");
        private final Map<Integer, BreakthroughLevel> breakthroughLevels = new HashMap<>();
        
        public BreakthroughManager() {
            loadBreakthroughConfig();
        }
        
        public void loadBreakthroughConfig() {
            breakthroughLevels.clear();
            FileConfiguration config = getConfig();
            
            if (config.contains("breakthrough.levels")) {
                for (String key : config.getConfigurationSection("breakthrough.levels").getKeys(false)) {
                    try {
                        int level = Integer.parseInt(key);
                        double requiredMoney = config.getDouble("breakthrough.levels." + key + ".required-money", 0);
                        List<String> commands = config.getStringList("breakthrough.levels." + key + ".commands");
                        
                        BreakthroughLevel btLevel = new BreakthroughLevel(level, requiredMoney, commands);
                        breakthroughLevels.put(level, btLevel);
                        getLogger().info("✅ Loaded breakthrough level " + level + " (Required: " + requiredMoney + " coins)");
                    } catch (NumberFormatException ignored) {}
                }
            }
            
            if (breakthroughLevels.isEmpty()) {
                breakthroughLevels.put(100, new BreakthroughLevel(100, 0, Arrays.asList()));
                breakthroughLevels.put(200, new BreakthroughLevel(200, 10000, Arrays.asList()));
                breakthroughLevels.put(300, new BreakthroughLevel(300, 50000, Arrays.asList()));
                breakthroughLevels.put(400, new BreakthroughLevel(400, 200000, Arrays.asList()));
                breakthroughLevels.put(500, new BreakthroughLevel(500, 500000, Arrays.asList()));
                getLogger().info("✅ Using default breakthrough levels");
            }
        }
        
        public ItemStack createBreakthroughItem(int targetLevel) {
            ItemStack item = new ItemStack(Material.NETHER_STAR);
            ItemMeta meta = item.getItemMeta();
            BreakthroughLevel bt = breakthroughLevels.get(targetLevel);
            double requiredMoney = bt != null ? bt.getRequiredMoney() : 0;
            
            meta.setDisplayName(color("&6&l✦ Đá Đột Phá Cấp " + targetLevel + " ✦"));
            meta.setLore(Arrays.asList(
                color("&7&oMở khóa giới hạn cấp " + targetLevel),
                "",
                color("&e&l⚠ &fClick chuột phải để đột phá!"),
                color("&c&l✦ &fYêu cầu: &6Cấp " + (targetLevel - 1) + " &7và &6" + DF_MONEY.format(requiredMoney) + " coins")
            ));
            meta.getPersistentDataContainer().set(BREAKTHROUGH_KEY, PersistentDataType.INTEGER, targetLevel);
            item.setItemMeta(meta);
            return item;
        }
        
        public int getBreakthroughTarget(ItemStack item) {
            if (item == null || !item.hasItemMeta()) return -1;
            return item.getItemMeta().getPersistentDataContainer().getOrDefault(BREAKTHROUGH_KEY, PersistentDataType.INTEGER, -1);
        }
        
        public boolean isBreakthroughItem(ItemStack item) {
            return getBreakthroughTarget(item) > 0;
        }

        public void performBreakthrough(Player player) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (!isBreakthroughItem(item)) {
                player.sendMessage(color("&c❌ Bạn không cầm đá đột phá!"));
                return;
            }
            
            int targetLevel = getBreakthroughTarget(item);
            BreakthroughLevel bt = breakthroughLevels.get(targetLevel);
            if (bt == null) {
                player.sendMessage(color("&c❌ Cấp đột phá không hợp lệ!"));
                return;
            }
            
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            int currentLevel = data.getLevel();
            
            if (data.hasBrokenThrough(targetLevel)) {
                player.sendMessage(color("&c❌ Bạn đã đột phá cấp " + targetLevel + " rồi!"));
                return;
            }
            
            if (currentLevel != targetLevel - 1) {
                player.sendMessage(color("&c❌ Bạn cần đạt &6Cấp " + (targetLevel - 1) + " &cđể sử dụng!"));
                return;
            }
            
            double requiredMoney = bt.getRequiredMoney();
            if (configManager.useVaultEconomy()) {
                if (economy.getBalance(player) < requiredMoney) {
                    player.sendMessage(color("&c❌ Bạn cần &6" + DF_MONEY.format(requiredMoney) + " &ccoins để đột phá!"));
                    return;
                }
                economy.withdrawPlayer(player, requiredMoney);
            } else {
                if (data.getMoney() < requiredMoney) {
                    player.sendMessage(color("&c❌ Bạn cần &6" + DF_MONEY.format(requiredMoney) + " &ccoins để đột phá!"));
                    return;
                }
                data.setMoney(data.getMoney() - requiredMoney);
            }

            data.addBrokenThrough(targetLevel);
            data.setLevel(targetLevel);
            data.setLastBreakthroughNotify(-1);
            
            attributeManager.updateAttributes(player);
            dataManager.savePlayerData(player);
            
            item.setAmount(item.getAmount() - 1);
            
            player.sendMessage(color("&6&l🎉 &fBạn đã đột phá lên &6Cấp " + targetLevel + "&f!"));
            
            String broadcast = getConfig().getString("messages.breakthrough",
                "&6&l✦ &f%player% &6&lĐÃ ĐỘT PHÁ LÊN CẤP %level% HUYỀN THOẠI! ✦")
                .replace("%player%", player.getName())
                .replace("%level%", String.valueOf(targetLevel));
            Bukkit.broadcastMessage(color(broadcast));
            
            for (String cmd : bt.getCommands()) {
                if (!cmd.isEmpty()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), 
                        cmd.replace("%player%", player.getName()).replace("%level%", String.valueOf(targetLevel)));
                }
            }
            
            player.getWorld().strikeLightningEffect(player.getLocation());
            for (int i = 0; i < 20; i++) {
                player.getWorld().spawnParticle(org.bukkit.Particle.FLAME,
                    player.getLocation().add(0, 1, 0), 50, 1, 2, 1, 0.1);
            }
        }

        public void notifyBreakthrough(Player player, int targetLevel) {
            BreakthroughLevel bt = breakthroughLevels.get(targetLevel);
            if (bt == null) return;
            
            double requiredMoney = bt.getRequiredMoney();
            
            player.sendMessage(color("&e&l✦ &fBạn đã đạt &6Cấp " + (targetLevel - 1) + "&f!"));
            player.sendMessage(color("&e&l✦ &fSử dụng &6Đá Đột Phá Cấp " + targetLevel + " &fđể lên &6Cấp " + targetLevel));
            if (requiredMoney > 0) {
                player.sendMessage(color("&e&l✦ &fYêu cầu: &6" + DF_MONEY.format(requiredMoney) + " coins"));
            } else {
                player.sendMessage(color("&e&l✦ &fYêu cầu: &6Miễn phí"));
            }
        }

        public class BreakthroughLevel {
            private final int level;
            private final double requiredMoney;
            private final List<String> commands;
            
            public BreakthroughLevel(int level, double requiredMoney, List<String> commands) {
                this.level = level;
                this.requiredMoney = requiredMoney;
                this.commands = commands;
            }
            
            public int getLevel() { return level; }
            public double getRequiredMoney() { return requiredMoney; }
            public List<String> getCommands() { return commands; }
        }
    }

    // ==================== EVENTS ====================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!event.isCancelled()) {
            xpManager.handleBlockBreak(event.getPlayer(), event.getBlock().getType());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) xpManager.handleMobKill(killer, event.getEntity().getType());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        DataManager.PlayerData data = dataManager.getPlayerData(player);
        
        attributeManager.lastAppliedLevel.remove(player.getUniqueId());
        attributeManager.updateAttributes(player);
        heartDisplayManager.updateHeartDisplay(player);
        updateVanillaXPBar(player);
        
        data.setLastBreakthroughNotify(-1);
    }

    @EventHandler
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        event.setAmount(0);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack item = event.getItem();
            if (item != null && breakthroughManager.isBreakthroughItem(item)) {
                event.setCancelled(true);
                breakthroughManager.performBreakthrough(event.getPlayer());
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getView().getTitle().equals(color("&6&l✦ Thông Tin Học Sinh ✦"))) {
            event.setCancelled(true);
        }
    }

    // ==================== COMMANDS ====================
    public class ProfileCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command!");
                return true;
            }
            new ProfileGUI((Player) sender).open();
            return true;
        }
    }

    public class SchoolLevelCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length == 0) {
                sendHelp(sender);
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "reload": return handleReload(sender);
                case "givecoins": return handleGiveCoins(sender, args);
                case "multiplier": return handleMultiplier(sender);
                case "setlevel": return handleSetLevel(sender, args);
                default: sendHelp(sender); return true;
            }
        }

        private void sendHelp(CommandSender sender) {
            sender.sendMessage(color("&6&lSchoolLevel &7- &fRPG Level System"));
            sender.sendMessage(color("&e/schoollevel reload &7- &fReload config"));
            sender.sendMessage(color("&e/schoollevel multiplier &7- &fCheck your multipliers"));
            sender.sendMessage(color("&e/schoollevel setlevel <player> <level> &7- &fSet player level"));
            sender.sendMessage(color("&e/schoollevel givecoins <player> <amount> &7- &fGive coins"));
            sender.sendMessage(color("&e/schoollevelgivebreakthrough <player> <level> &7- &fGive breakthrough item"));
            sender.sendMessage(color("&e/profile &7- &fOpen profile menu"));
            sender.sendMessage(color("&e/party <create|invite|accept|leave|disband|kick|list> &7- &fParty system"));
        }

        private boolean handleMultiplier(CommandSender sender) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command!");
                return true;
            }
            Player player = (Player) sender;
            double xpMult = permissionManager.getXPMultiplier(player);
            double moneyMult = permissionManager.getMoneyMultiplier(player);
            
            player.sendMessage(color("&6&l✦ &fXP Multiplier: &a" + String.format("%.1f", xpMult) + "x"));
            player.sendMessage(color("&6&l✦ &fMoney Multiplier: &a" + String.format("%.1f", moneyMult) + "x"));
            return true;
        }

        private boolean handleReload(CommandSender sender) {
            if (!sender.hasPermission("schoollevel.admin")) {
                sender.sendMessage(color("&cYou don't have permission!"));
                return true;
            }
            configManager.reload();
            dataManager.reload();
            permissionManager.loadConfig();
            levelManager.reloadConfig();
            breakthroughManager.loadBreakthroughConfig();
            heartDisplayManager.loadConfig();
            attributeManager.reloadConfig();
            
            if (placeholderHook != null) {
                placeholderHook.loadLevelColors();
            }
            
            for (Player player : Bukkit.getOnlinePlayers()) {
                DataManager.PlayerData data = dataManager.getPlayerData(player);
                data.setLastBreakthroughNotify(-1);
                
                attributeManager.lastAppliedLevel.remove(player.getUniqueId());
                attributeManager.updateAttributes(player);
                heartDisplayManager.updateHeartDisplay(player);
                updateVanillaXPBar(player);
            }
            sender.sendMessage(color("&a✅ Config reloaded successfully!"));
            return true;
        }

        private boolean handleGiveCoins(CommandSender sender, String[] args) {
            if (!sender.hasPermission("schoollevel.admin")) {
                sender.sendMessage(color("&cYou don't have permission!"));
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(color("&cUsage: /schoollevel givecoins <player> <amount>"));
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(color("&cPlayer not found!"));
                return true;
            }
            try {
                double amount = Double.parseDouble(args[2]);
                if (configManager.useVaultEconomy()) {
                    economy.depositPlayer(target, amount);
                } else {
                    dataManager.getPlayerData(target).addMoney(amount);
                }
                sender.sendMessage(color("&a✅ Gave " + DF_MONEY.format(amount) + " coins to " + target.getName()));
                target.sendMessage(color("&6&l💰 &fBạn nhận được &6" + DF_MONEY.format(amount) + " &fcoins!"));
            } catch (NumberFormatException e) {
                sender.sendMessage(color("&cInvalid amount!"));
            }
            return true;
        }

        private boolean handleSetLevel(CommandSender sender, String[] args) {
            if (!sender.hasPermission("schoollevel.admin")) {
                sender.sendMessage(color("&cYou don't have permission!"));
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(color("&cUsage: /schoollevel setlevel <player> <level>"));
                return true;
            }
            
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(color("&cPlayer not found!"));
                return true;
            }
            
            try {
                int level = Integer.parseInt(args[2]);
                int maxLevel = levelManager.getMaxLevel();
                if (level < 1 || level > maxLevel) {
                    sender.sendMessage(color("&cLevel must be between 1 and " + maxLevel));
                    return true;
                }
                
                DataManager.PlayerData data = dataManager.getPlayerData(target);
                data.setLevel(level);
                data.setXp(0);
                data.setLastBreakthroughNotify(-1);
                
                for (int btLevel : BREAKTHROUGH_LEVELS) {
                    if (level >= btLevel && !data.hasBrokenThrough(btLevel)) {
                        data.addBrokenThrough(btLevel);
                    }
                }
                
                attributeManager.lastAppliedLevel.remove(target.getUniqueId());
                attributeManager.updateAttributes(target);
                dataManager.savePlayerData(target);
                updateVanillaXPBar(target);
                
                sender.sendMessage(color("&a✅ Set " + target.getName() + "'s level to " + level));
                target.sendMessage(color("&6&l✦ &fYour level has been set to &6" + level));
            } catch (NumberFormatException e) {
                sender.sendMessage(color("&cInvalid level!"));
            }
            return true;
        }
    }

    public class GiveBreakthroughCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length < 2) {
                sender.sendMessage(color("&cUsage: /schoollevelgivebreakthrough <player> <level>"));
                return true;
            }
            
            if (!sender.hasPermission("schoollevel.admin")) {
                sender.sendMessage(color("&cYou don't have permission!"));
                return true;
            }
            
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(color("&cPlayer not found!"));
                return true;
            }
            
            try {
                int level = Integer.parseInt(args[1]);
                if (!breakthroughManager.breakthroughLevels.containsKey(level)) {
                    sender.sendMessage(color("&cInvalid breakthrough level! Available: " + breakthroughManager.breakthroughLevels.keySet()));
                    return true;
                }
                target.getInventory().addItem(breakthroughManager.createBreakthroughItem(level));
                sender.sendMessage(color("&a✅ Gave breakthrough item level " + level + " to " + target.getName()));
                target.sendMessage(color("&6&l✦ &fYou received a &6Breakthrough Stone Level " + level + "&f!"));
            } catch (NumberFormatException e) {
                sender.sendMessage(color("&cInvalid level!"));
            }
            return true;
        }
    }

    // ==================== PARTY COMMAND ====================
    public class PartyCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command!");
                return true;
            }
            
            Player player = (Player) sender;
            
            if (args.length == 0) {
                sendHelp(player);
                return true;
            }
            
            switch (args[0].toLowerCase()) {
                case "create":
                    partyManager.createParty(player);
                    break;
                case "invite":
                    if (args.length < 2) {
                        player.sendMessage(color("&cUsage: /party invite <player>"));
                        return true;
                    }
                    partyManager.invitePlayer(player, args[1]);
                    break;
                case "accept":
                    partyManager.acceptInvite(player);
                    break;
                case "leave":
                    partyManager.leaveParty(player);
                    break;
                case "disband":
                    partyManager.disbandParty(player);
                    break;
                case "kick":
                    if (args.length < 2) {
                        player.sendMessage(color("&cUsage: /party kick <player>"));
                        return true;
                    }
                    partyManager.kickMember(player, args[1]);
                    break;
                case "list":
                case "info":
                    partyManager.listParty(player);
                    break;
                default:
                    sendHelp(player);
                    break;
            }
            return true;
        }
        
        private void sendHelp(Player player) {
            player.sendMessage(color("&6&l✦ &fParty Commands &6✦"));
            player.sendMessage(color("&e/party create &7- &fTạo party mới"));
            player.sendMessage(color("&e/party invite <player> &7- &fMời người chơi"));
            player.sendMessage(color("&e/party accept &7- &fChấp nhận lời mời"));
            player.sendMessage(color("&e/party leave &7- &fRời khỏi party"));
            player.sendMessage(color("&e/party disband &7- &fGiải tán party (Chủ party)"));
            player.sendMessage(color("&e/party kick <player> &7- &fĐuổi thành viên (Chủ party)"));
            player.sendMessage(color("&e/party list &7- &fXem danh sách party"));
        }
    }

    // ==================== GUI ====================
    public class ProfileGUI {
        private final Player player;
        private final Inventory inventory;

        public ProfileGUI(Player player) {
            this.player = player;
            this.inventory = Bukkit.createInventory(null, 54, color("&6&l✦ Thông Tin Học Sinh ✦"));
        }

        public void open() {
            DataManager.PlayerData data = dataManager.getPlayerData(player);

            ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta glassMeta = glass.getItemMeta();
            glassMeta.setDisplayName(" ");
            glass.setItemMeta(glassMeta);
            for (int i = 0; i < 54; i++) inventory.setItem(i, glass);

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta headMeta = (SkullMeta) head.getItemMeta();
            headMeta.setOwningPlayer(player);
            headMeta.setDisplayName(color("&6&l" + player.getName()));
            headMeta.setLore(Arrays.asList(color("&7Thông tin chi tiết của bạn")));
            head.setItemMeta(headMeta);
            inventory.setItem(4, head);

            double health = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
            double damage = player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).getValue();
            double speed = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getValue();
            int level = data.getLevel();
            double xp = data.getXp();
            double required = levelManager.getRequiredXP(level);
            double money = data.getMoney();
            
            double healthBonus = attributeManager.getHealthBonusPercent(level);
            double damageBonus = attributeManager.getDamageBonusPercent(level);
            double speedBonus = attributeManager.getSpeedBonusPercent(level);

            inventory.setItem(20, createInfoItem(Material.EXPERIENCE_BOTTLE,
                "&6&l⚡ Cấp độ",
                Arrays.asList(
                    "&7Hiện tại: &6" + level,
                    "&7Kinh nghiệm: &b" + DF.format(xp) + " &7/ &b" + DF.format(required),
                    "&7Tiến độ: &a" + DF.format((xp / required) * 100) + "%"
                )));

            inventory.setItem(22, createInfoItem(Material.GOLDEN_APPLE,
                "&c&l❤ Máu tối đa",
                Arrays.asList(
                    "&7Lượng máu: &c" + DF.format(health),
                    "&7Trái tim: &c" + DF.format(health / 2) + " &c❤",
                    "&7Đã tăng: &a+" + DF.format(healthBonus) + "%"
                )));

            inventory.setItem(24, createInfoItem(Material.DIAMOND_SWORD,
                "&6&l⚔ Sát thương",
                Arrays.asList(
                    "&7Sát thương: &6" + DF.format(damage),
                    "&7Đã tăng: &a+" + DF.format(damageBonus) + "%"
                )));

            inventory.setItem(29, createInfoItem(Material.FEATHER,
                "&b&l✦ Tốc độ",
                Arrays.asList(
                    "&7Tốc độ: &b" + DF.format(speed * 1000) + " &b%",
                    "&7Đã tăng: &a+" + DF.format(speedBonus) + "%"
                )));

            inventory.setItem(31, createInfoItem(Material.DIAMOND_PICKAXE,
                "&a&l⛏ Block đã đào",
                Arrays.asList("&7Số block: &a" + data.getBlocksBroken())));

            inventory.setItem(33, createInfoItem(Material.GOLD_INGOT,
                "&e&l💰 Coins",
                Arrays.asList("&7Số coins: &e" + DF_MONEY.format(money))));

            int minutes = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60;
            inventory.setItem(40, createInfoItem(Material.CLOCK,
                "&e&l⌛ Thời gian online",
                Arrays.asList("&7Tổng thời gian: &e" + minutes + " &ephút")));

            String breakthroughStatus = "&c❌ Chưa đột phá";
            int nextBT = levelManager.getNextBreakthroughLevel(level);
            
            if (data.hasBrokenThrough(500)) {
                breakthroughStatus = "&6✦ Cấp 500 (Tối đa)";
            } else if (data.hasBrokenThrough(400)) {
                breakthroughStatus = "&6✦ Đã đột phá cấp 400, mục tiêu tiếp theo: &6Cấp 500";
            } else if (data.hasBrokenThrough(300)) {
                breakthroughStatus = "&6✦ Đã đột phá cấp 300, mục tiêu tiếp theo: &6Cấp 400";
            } else if (data.hasBrokenThrough(200)) {
                breakthroughStatus = "&6✦ Đã đột phá cấp 200, mục tiêu tiếp theo: &6Cấp 300";
            } else if (data.hasBrokenThrough(100)) {
                breakthroughStatus = "&6✦ Đã đột phá cấp 100, mục tiêu tiếp theo: &6Cấp 200";
            } else if (level >= 99 && nextBT > 0) {
                breakthroughStatus = "&e⚠ Cần đột phá lên &6Cấp " + nextBT;
            }
            
            inventory.setItem(49, createInfoItem(Material.NETHER_STAR,
                "&6&l✦ Trạng thái đột phá",
                Arrays.asList(
                    "&7" + breakthroughStatus,
                    "&7Cấp hiện tại: &6" + level
                )));

            player.openInventory(inventory);
        }

        private ItemStack createInfoItem(Material material, String name, List<String> lore) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(color(name));
            meta.setLore(lore.stream().map(line -> color(line)).toList());
            item.setItemMeta(meta);
            return item;
        }
    }
}