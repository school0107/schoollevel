package me.schoollevel.schoollevel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
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

    // ==================== CONSTANTS ====================
    private static final int MAX_LEVEL = 100;
    private static final int LEGENDARY_LEVEL = 101;
    private static final int XP_BAR_UPDATE_INTERVAL = 20;
    private static final int ACTION_BAR_INTERVAL = 20;
    private static final DecimalFormat DF = new DecimalFormat("#.##");
    private static final DecimalFormat DF_MONEY = new DecimalFormat("#,###.##");

    // ==================== SINGLETON ====================
    private static SchoolLevelPlugin instance;
    private net.milkbowl.vault.economy.Economy economy;

    // ==================== MANAGERS ====================
    private DataManager dataManager;
    private LevelManager levelManager;
    private AttributeManager attributeManager;
    private XPManager xpManager;
    private BreakthroughManager breakthroughManager;
    private ActionBarManager actionBarManager;
    private ConfigManager configManager;

    // ==================== PLUGIN LIFECYCLE ====================
    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();
        instance = this;
        
        // Setup Vault Economy
        if (!setupEconomy()) {
            getLogger().warning("Vault Economy not found! Money features will be disabled.");
        }
        
        // Load config
        saveDefaultConfig();
        configManager = new ConfigManager();
        
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        
        dataManager = new DataManager();
        levelManager = new LevelManager();
        attributeManager = new AttributeManager();
        xpManager = new XPManager();
        breakthroughManager = new BreakthroughManager();
        actionBarManager = new ActionBarManager();
        
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
        Objects.requireNonNull(getCommand("profile")).setExecutor(new ProfileCommand());
        Objects.requireNonNull(getCommand("schoollevel")).setExecutor(new SchoolLevelCommand());
        Objects.requireNonNull(getCommand("schoollevelgiveitem")).setExecutor(new GiveItemCommand());
    }

    private void registerPlaceholderAPI() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SchoolLevelExpansion().register();
            getLogger().info("✅ PlaceholderAPI registered!");
        }
    }

    private void startScheduledTasks() {
        // Action bar updater
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!Bukkit.getOnlinePlayers().isEmpty()) {
                    actionBarManager.updateAllPlayers();
                }
            }
        }.runTaskTimer(this, 0, ACTION_BAR_INTERVAL);
        
        // XP bar updater
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

    // ==================== GETTERS ====================
    public static SchoolLevelPlugin getInstance() { return instance; }
    public DataManager getDataManager() { return dataManager; }
    public LevelManager getLevelManager() { return levelManager; }
    public AttributeManager getAttributeManager() { return attributeManager; }
    public XPManager getXpManager() { return xpManager; }
    public BreakthroughManager getBreakthroughManager() { return breakthroughManager; }
    public ActionBarManager getActionBarManager() { return actionBarManager; }
    public ConfigManager getConfigManager() { return configManager; }
    public net.milkbowl.vault.economy.Economy getEconomy() { return economy; }
    public boolean hasEconomy() { return economy != null; }

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
        private String actionBarFormat = "&e⚡ &fCấp &6{level} &7| &a{bar} &fXP: &b{xp}/{required} &7| &c❤ {health} &7| &6⚔ {damage} &7| &e💰 {money}{pending}";
        private int moneyMessageDuration = 60;
        private double baseBlockMoney = 0.1;
        private double levelMoneyBonus = 0.05;
        
        public ConfigManager() {
            reload();
        }

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
                        if (mat != null) {
                            blockXP.put(mat, config.getDouble("xp.blocks." + key));
                        }
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
                        if (mat != null) {
                            blockMoney.put(mat, config.getDouble("money.blocks." + key));
                        }
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
                "&e⚡ &fCấp &6{level} &7| &a{bar} &fXP: &b{xp}/{required} &7| &c❤ {health} &7| &6⚔ {damage} &7| &e💰 {money}{pending}");
            moneyMessageDuration = config.getInt("settings.money-message-duration", 60);
            baseBlockMoney = config.getDouble("settings.base-block-money", 0.1);
            levelMoneyBonus = config.getDouble("settings.level-money-bonus", 0.05);
        }

        public double getBlockXP(Material material) {
            return blockXP.getOrDefault(material, 0.0);
        }

        public double getMobXP(EntityType type) {
            return mobXP.getOrDefault(type, 0.0);
        }

        public double getBlockMoney(Material material) {
            return blockMoney.getOrDefault(material, -1.0);
        }

        public double getMobMoney(EntityType type) {
            return mobMoney.getOrDefault(type, -1.0);
        }

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
            String formatted = moneyFormat
                .replace("{symbol}", currencySymbol)
                .replace("{name}", currencyName)
                .replace("{amount}", DF_MONEY.format(amount));
            return formatted;
        }
        
        public String getBlockMoneyDisplay(Player player, Material material) {
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            int level = data.getLevel();
            double baseMoney = getBlockMoney(material);
            if (baseMoney < 0) {
                baseMoney = getBaseBlockMoney();
            }
            double bonus = level * getLevelMoneyBonus();
            return formatMoney(baseMoney + bonus);
        }
        
        public double getCalculatedBlockMoney(Player player, Material material) {
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            int level = data.getLevel();
            double baseMoney = getBlockMoney(material);
            if (baseMoney < 0) {
                baseMoney = getBaseBlockMoney();
            }
            double bonus = level * getLevelMoneyBonus();
            return baseMoney + bonus;
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

        public void savePlayerData(Player player) {
            savePlayerData(player.getUniqueId());
        }

        private void savePlayerData(UUID uuid) {
            PlayerData data = playerDataMap.get(uuid);
            if (data == null) return;
            
            String path = uuid.toString();
            dataConfig.set(path + ".level", data.getLevel());
            dataConfig.set(path + ".xp", data.getXp());
            dataConfig.set(path + ".blocksBroken", data.getBlocksBroken());
            dataConfig.set(path + ".hasBrokenThrough", data.hasBrokenThrough());
            dataConfig.set(path + ".money", data.getMoney());
            saveData();
        }

        public void saveAllData() {
            for (UUID uuid : playerDataMap.keySet()) {
                savePlayerData(uuid);
            }
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
                    data.setHasBrokenThrough(dataConfig.getBoolean(uuidStr + ".hasBrokenThrough", false));
                    data.setMoney(dataConfig.getDouble(uuidStr + ".money", 0));
                    playerDataMap.put(uuid, data);
                } catch (IllegalArgumentException ignored) {}
            }
        }

        private void saveData() {
            try { 
                dataConfig.save(dataFile); 
            } catch (Exception e) { 
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
            private boolean hasBrokenThrough = false;
            private double money = 0;
            private double pendingMoney = 0;
            private int moneyMessageTicks = 0;

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
            public boolean hasBrokenThrough() { return hasBrokenThrough; }
            public void setHasBrokenThrough(boolean has) { this.hasBrokenThrough = has; }
            public double getMoney() { return money; }
            public void setMoney(double money) { this.money = money; }
            public void addMoney(double amount) { this.money += amount; }
            public double getPendingMoney() { return pendingMoney; }
            public void setPendingMoney(double pending) { this.pendingMoney = pending; }
            public void addPendingMoney(double amount) { this.pendingMoney += amount; }
            public int getMoneyMessageTicks() { return moneyMessageTicks; }
            public void setMoneyMessageTicks(int ticks) { this.moneyMessageTicks = ticks; }
            public void resetMoneyMessage() { 
                this.pendingMoney = 0; 
                this.moneyMessageTicks = 0; 
            }
        }
    }

    // ==================== LEVEL MANAGER ====================
    public class LevelManager {
        public double getRequiredXP(int level) {
            return 100 * Math.pow(level, 1.5);
        }

        public void addXP(Player player, double amount) {
            if (amount <= 0) return;
            
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            int currentLevel = data.getLevel();
            
            if (currentLevel >= MAX_LEVEL && !data.hasBrokenThrough()) {
                breakthroughManager.notifyBreakthrough(player);
                return;
            }
            if (currentLevel >= LEGENDARY_LEVEL) return;
            
            data.addXp(amount);
            
            while (data.getXp() >= getRequiredXP(data.getLevel()) && data.getLevel() < LEGENDARY_LEVEL) {
                data.setXp(data.getXp() - getRequiredXP(data.getLevel()));
                levelUp(player);
            }
            
            attributeManager.updateAttributes(player);
            dataManager.savePlayerData(player);
            updateVanillaXPBar(player);
        }

        private void levelUp(Player player) {
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            int newLevel = data.getLevel() + 1;
            data.setLevel(newLevel);
            
            attributeManager.updateAttributes(player);
            
            // Show Title with gradient color
            String title = getConfig().getString("messages.level-up-title", "&6&l⬆ LEVEL UP!");
            String subtitle = getConfig().getString("messages.level-up-subtitle", "&fBạn đã đạt &6Cấp %level%");
            title = color(title);
            subtitle = color(subtitle.replace("%level%", String.valueOf(newLevel)));
            
            player.showTitle(Title.title(
                Component.text(title),
                Component.text(subtitle),
                Title.Times.times(
                    Duration.ofMillis(500),
                    Duration.ofMillis(2000),
                    Duration.ofMillis(500)
                )
            ));
            
            // Send chat message with hex color
            String message = getConfig().getString("messages.level-up-chat", 
                "&6&l⬆ &fBạn đã lên &6Cấp %level%&f! &e✦")
                .replace("%level%", String.valueOf(newLevel));
            player.sendMessage(color(message));
            
            // Execute commands
            for (String cmd : getConfig().getStringList("commands.level-up")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), 
                    cmd.replace("%player%", player.getName()).replace("%level%", String.valueOf(newLevel)));
            }
        }
    }

    // ==================== ATTRIBUTE MANAGER ====================
    public class AttributeManager {
        private static final String HEALTH_MODIFIER = "schoollevel_health";
        private static final String DAMAGE_MODIFIER = "schoollevel_damage";
        private static final String SPEED_MODIFIER = "schoollevel_speed";

        public void updateAttributes(Player player) {
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            int level = data.getLevel();
            double bonus = level * 0.01;
            
            updateAttribute(player, Attribute.GENERIC_MAX_HEALTH, HEALTH_MODIFIER, 20.0 * bonus);
            updateAttribute(player, Attribute.GENERIC_ATTACK_DAMAGE, DAMAGE_MODIFIER, 1.0 * bonus);
            updateAttribute(player, Attribute.GENERIC_MOVEMENT_SPEED, SPEED_MODIFIER, 0.1 * bonus);
        }

        private void updateAttribute(Player player, Attribute attribute, String modifierName, double amount) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) return;
            
            instance.getModifiers().stream()
                .filter(mod -> mod.getKey() != null && mod.getKey().getKey().equals(modifierName))
                .forEach(instance::removeModifier);
            
            if (amount > 0.001) {
                NamespacedKey key = new NamespacedKey(SchoolLevelPlugin.this, modifierName);
                instance.addModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER));
            }
        }
    }

    // ==================== XP MANAGER ====================
    public class XPManager {
        public void handleBlockBreak(Player player, Material material) {
            double xp = configManager.getBlockXP(material);
            if (xp > 0) {
                levelManager.addXP(player, xp);
                DataManager.PlayerData data = dataManager.getPlayerData(player);
                data.incrementBlocksBroken();
            }
            
            // Handle money with level bonus
            double moneyEarned = configManager.getCalculatedBlockMoney(player, material);
            if (moneyEarned > 0) {
                addMoney(player, moneyEarned);
            }
        }

        public void handleMobKill(Player player, EntityType entityType) {
            double xp = configManager.getMobXP(entityType);
            if (xp > 0) {
                levelManager.addXP(player, xp);
            }
            
            // Handle money
            double moneyEarned = configManager.getMobMoney(entityType);
            if (moneyEarned > 0) {
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
            
            // Store pending money for action bar display
            if (configManager.showMoneyMessage()) {
                data.addPendingMoney(amount);
                data.setMoneyMessageTicks(configManager.getMoneyMessageDuration());
            }
            
            dataManager.savePlayerData(player);
        }
    }

    // ==================== BREAKTHROUGH MANAGER ====================
    public class BreakthroughManager {
        private final NamespacedKey BREAKTHROUGH_KEY = new NamespacedKey(SchoolLevelPlugin.this, "breakthrough_item");

        public ItemStack createBreakthroughItem() {
            ItemStack item = new ItemStack(Material.NETHER_STAR);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(color("&6&l✦ Đá Đột Phá Thần Cấp ✦"));
            meta.setLore(Arrays.asList(
                color("&7&oMở khóa sức mạnh tiềm ẩn..."),
                "",
                color("&e&l⚠ &fClick chuột phải để đột phá!"),
                color("&c&l✦ &fYêu cầu: &6Cấp 100")
            ));
            meta.getPersistentDataContainer().set(BREAKTHROUGH_KEY, PersistentDataType.BOOLEAN, true);
            item.setItemMeta(meta);
            return item;
        }

        public boolean isBreakthroughItem(ItemStack item) {
            if (item == null || !item.hasItemMeta()) return false;
            return item.getItemMeta().getPersistentDataContainer().has(BREAKTHROUGH_KEY, PersistentDataType.BOOLEAN);
        }

        public void performBreakthrough(Player player) {
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            
            if (data.getLevel() < 100) {
                player.sendMessage(color("&c❌ Bạn cần đạt &6Cấp 100 &cđể sử dụng!"));
                return;
            }
            if (data.hasBrokenThrough()) {
                player.sendMessage(color("&c❌ Bạn đã đột phá rồi!"));
                return;
            }
            
            data.setLevel(101);
            data.setHasBrokenThrough(true);
            attributeManager.updateAttributes(player);
            dataManager.savePlayerData(player);
            
            // Bonus money for breakthrough
            double bonusMoney = getConfig().getDouble("breakthrough.bonus-money", 10000);
            if (configManager.useVaultEconomy()) {
                economy.depositPlayer(player, bonusMoney);
            } else {
                data.addMoney(bonusMoney);
            }
            
            player.sendMessage(color("&6&l💰 &fBạn nhận được &6" + DF_MONEY.format(bonusMoney) + " &fcoins khi đột phá!"));
            
            String broadcast = getConfig().getString("messages.breakthrough", 
                "&6&l✦ &f%player% &6&lĐÃ ĐỘT PHÁ LÊN CẤP 101 HUYỀN THOẠI! ✦")
                .replace("%player%", player.getName());
            Bukkit.broadcastMessage(color(broadcast));
            
            for (String cmd : getConfig().getStringList("commands.breakthrough")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), 
                    cmd.replace("%player%", player.getName()));
            }
            
            player.getWorld().strikeLightningEffect(player.getLocation());
            for (int i = 0; i < 20; i++) {
                player.getWorld().spawnParticle(org.bukkit.Particle.FLAME, 
                    player.getLocation().add(0, 1, 0), 50, 1, 2, 1, 0.1);
            }
        }

        public void notifyBreakthrough(Player player) {
            player.sendMessage(color("&e&l✦ &fBạn đã đạt &6Cấp 100&f!"));
            player.sendMessage(color("&e&l✦ &fSử dụng &6Đá Đột Phá &fđể lên &6Cấp 101"));
            player.sendMessage(color("&e&l✦ &fGõ &6/schoollevel giveitem " + player.getName() + " &fđể nhận vật phẩm"));
        }
    }

    // ==================== ACTION BAR MANAGER ====================
    public class ActionBarManager {
        public void updateAllPlayers() {
            for (Player player : Bukkit.getOnlinePlayers()) {
                updatePlayerActionBar(player);
            }
        }

        public void updatePlayerActionBar(Player player) {
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            int level = data.getLevel();
            double xp = data.getXp();
            double required = levelManager.getRequiredXP(level);
            
            int progress = Math.min((int) ((xp / required) * 20), 20);
            String bar = "■".repeat(Math.max(0, progress)) + "□".repeat(Math.max(0, 20 - progress));
            
            double health = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
            double damage = player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).getValue();
            
            // Get money
            double money;
            if (configManager.useVaultEconomy()) {
                money = economy.getBalance(player);
            } else {
                money = data.getMoney();
            }
            
            // Build action bar with pending money message
            String actionBarText = buildActionBarText(player, level, bar, xp, required, health, damage, money);
            player.sendActionBar(Component.text(color(actionBarText)));
            
            // Update pending money timer
            if (data.getMoneyMessageTicks() > 0) {
                data.setMoneyMessageTicks(data.getMoneyMessageTicks() - 1);
                if (data.getMoneyMessageTicks() == 0) {
                    data.resetMoneyMessage();
                }
            }
        }

        private String buildActionBarText(Player player, int level, String bar, double xp, double required, 
                                          double health, double damage, double money) {
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            String format = configManager.getActionBarFormat();
            
            String moneyDisplay = configManager.formatMoney(money);
            
            // Check if there's pending money to show
            String pendingDisplay = "";
            if (data.getMoneyMessageTicks() > 0 && data.getPendingMoney() > 0) {
                pendingDisplay = " &a+ " + configManager.formatMoney(data.getPendingMoney());
            }
            
            String result = format
                .replace("{level}", String.valueOf(level))
                .replace("{bar}", bar)
                .replace("{xp}", DF.format(xp))
                .replace("{required}", DF.format(required))
                .replace("{health}", DF.format(health))
                .replace("{damage}", DF.format(damage))
                .replace("{money}", moneyDisplay)
                .replace("{pending}", pendingDisplay);
            
            return result;
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
        if (killer != null) {
            xpManager.handleMobKill(killer, event.getEntity().getType());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        dataManager.getPlayerData(player);
        attributeManager.updateAttributes(player);
        updateVanillaXPBar(player);
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
                item.setAmount(item.getAmount() - 1);
            }
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
                case "reload":
                    return handleReload(sender);
                case "givecoins":
                    return handleGiveCoins(sender, args);
                case "giveitem":
                    return handleGiveItem(sender, args);
                default:
                    sendHelp(sender);
                    return true;
            }
        }

        private void sendHelp(CommandSender sender) {
            sender.sendMessage(color("&6&lSchoolLevel &7- &fRPG Level System"));
            sender.sendMessage(color("&e/schoollevel reload &7- &fReload config"));
            sender.sendMessage(color("&e/schoollevel giveitem <player> &7- &fGive breakthrough item"));
            sender.sendMessage(color("&e/schoollevel givecoins <player> <amount> &7- &fGive coins"));
            sender.sendMessage(color("&e/profile &7- &fOpen profile menu"));
        }

        private boolean handleReload(CommandSender sender) {
            if (!sender.hasPermission("schoollevel.admin")) {
                sender.sendMessage(color("&cYou don't have permission!"));
                return true;
            }
            
            configManager.reload();
            dataManager.reload();
            
            for (Player player : Bukkit.getOnlinePlayers()) {
                attributeManager.updateAttributes(player);
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

        private boolean handleGiveItem(CommandSender sender, String[] args) {
            if (!sender.hasPermission("schoollevel.admin")) {
                sender.sendMessage(color("&cYou don't have permission!"));
                return true;
            }
            
            if (args.length < 2) {
                sender.sendMessage(color("&cUsage: /schoollevel giveitem <player>"));
                return true;
            }
            
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(color("&cPlayer not found!"));
                return true;
            }
            
            target.getInventory().addItem(breakthroughManager.createBreakthroughItem());
            sender.sendMessage(color("&a✅ Gave breakthrough item to " + target.getName()));
            target.sendMessage(color("&6&l✦ &fYou received a &6Breakthrough Stone&f!"));
            return true;
        }
    }

    public class GiveItemCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length < 1) {
                sender.sendMessage(color("&cUsage: /schoollevelgiveitem <player>"));