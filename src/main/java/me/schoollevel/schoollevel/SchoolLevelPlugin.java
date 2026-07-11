package me.schoollevel.schoollevel;

import net.kyori.adventure.text.Component;
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
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Statistic;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class SchoolLevelPlugin extends JavaPlugin implements Listener {

    // ==================== CONSTANTS ====================
    private static final int MAX_LEVEL = 100;
    private static final int LEGENDARY_LEVEL = 101;
    private static final int XP_BAR_UPDATE_INTERVAL = 20; // ticks (1 second)
    private static final int ACTION_BAR_INTERVAL = 20; // ticks (1 second)
    private static final DecimalFormat DF = new DecimalFormat("#.##");
    private static final DecimalFormat DF_MONEY = new DecimalFormat("#.00");

    // ==================== SINGLETON ====================
    private static SchoolLevelPlugin instance;

    // ==================== MANAGERS ====================
    private DataManager dataManager;
    private LevelManager levelManager;
    private AttributeManager attributeManager;
    private XPManager xpManager;
    private BreakthroughManager breakthroughManager;
    private ActionBarManager actionBarManager;
    private MoneyManager moneyManager;
    private ConfigManager configManager;

    // ==================== PLUGIN LIFECYCLE ====================
    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();
        instance = this;
        
        // Load config
        saveDefaultConfig();
        configManager = new ConfigManager();
        
        // Initialize data folder
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        
        // Initialize managers
        dataManager = new DataManager();
        levelManager = new LevelManager();
        attributeManager = new AttributeManager();
        xpManager = new XPManager();
        breakthroughManager = new BreakthroughManager();
        actionBarManager = new ActionBarManager();
        moneyManager = new MoneyManager();
        
        // Register commands
        registerCommands();
        
        // Register events
        getServer().getPluginManager().registerEvents(this, this);
        
        // Register PlaceholderAPI
        registerPlaceholderAPI();
        
        // Start scheduled tasks
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

    private void registerCommands() {
        Objects.requireNonNull(getCommand("profile")).setExecutor(new ProfileCommand());
        Objects.requireNonNull(getCommand("schoollevel")).setExecutor(new SchoolLevelCommand());
        Objects.requireNonNull(getCommand("schoollevelgiveitem")).setExecutor(new GiveItemCommand());
        Objects.requireNonNull(getCommand("schoollevelmoney")).setExecutor(new MoneyCommand());
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

    // ==================== UPDATE VANILLA XP BAR ====================
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
    public MoneyManager getMoneyManager() { return moneyManager; }
    public ConfigManager getConfigManager() { return configManager; }

    // ==================== CONFIG MANAGER ====================
    public class ConfigManager {
        private final Map<Material, Double> blockXP = new ConcurrentHashMap<>();
        private final Map<EntityType, Double> mobXP = new ConcurrentHashMap<>();
        private boolean showMoneyMessage;

        public ConfigManager() {
            reload();
        }

        public void reload() {
            reloadConfig();
            loadXPConfig();
            showMoneyMessage = getConfig().getBoolean("settings.show-money-message", true);
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

        public double getBlockXP(Material material) {
            return blockXP.getOrDefault(material, 0.0);
        }

        public double getMobXP(EntityType type) {
            return mobXP.getOrDefault(type, 0.0);
        }

        public boolean showMoneyMessage() { return showMoneyMessage; }
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
        }
    }

    // ==================== MONEY MANAGER ====================
    public class MoneyManager {
        public double getMoney(Player player) {
            return dataManager.getPlayerData(player).getMoney();
        }

        public void addMoney(Player player, double amount) {
            if (amount <= 0) return;
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            data.addMoney(amount);
            dataManager.savePlayerData(player);
        }

        public void removeMoney(Player player, double amount) {
            if (amount <= 0) return;
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            data.setMoney(Math.max(0, data.getMoney() - amount));
            dataManager.savePlayerData(player);
        }

        public String getFormattedMoney(Player player) {
            return DF_MONEY.format(getMoney(player));
        }

        public double getBlockMoney(Player player) {
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            return 0.1 + (data.getLevel() * 0.1);
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
            
            // Send level up message
            String message = getConfig().getString("messages.level-up", 
                "&6&l⬆ &fBạn đã lên &6Cấp %level%&f! &e✦")
                .replace("%level%", String.valueOf(newLevel));
            player.sendMessage(color(message));
            
            // Bonus money on level up
            double bonusMoney = newLevel * 10.0;
            moneyManager.addMoney(player, bonusMoney);
            player.sendMessage(color("&e&l💰 &fBạn nhận được &6" + DF.format(bonusMoney) + " &fcoins!"));
            
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
            
            // Remove old modifier
            instance.getModifiers().stream()
                .filter(mod -> mod.getKey() != null && mod.getKey().getKey().equals(modifierName))
                .forEach(instance::removeModifier);
            
            // Add new modifier if valid
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
                
                // Add money for block break
                double moneyEarned = moneyManager.getBlockMoney(player);
                moneyManager.addMoney(player, moneyEarned);
                
                // Show money message
                if (configManager.showMoneyMessage()) {
                    player.sendActionBar(Component.text(
                        color("&e💰 +" + DF.format(moneyEarned) + " coins")
                    ));
                }
            }
        }

        public void handleMobKill(Player player, EntityType entityType) {
            double xp = configManager.getMobXP(entityType);
            if (xp > 0) {
                levelManager.addXP(player, xp);
                // Bonus money for mob kill
                moneyManager.addMoney(player, xp * 0.5);
            }
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
            moneyManager.addMoney(player, 10000);
            player.sendMessage(color("&6&l💰 &fBạn nhận được &610,000 &fcoins khi đột phá!"));
            
            // Broadcast
            String broadcast = getConfig().getString("messages.breakthrough", 
                "&6&l✦ &f%player% &6&lĐÃ ĐỘT PHÁ LÊN CẤP 101 HUYỀN THOẠI! ✦")
                .replace("%player%", player.getName());
            Bukkit.broadcastMessage(color(broadcast));
            
            // Execute commands
            for (String cmd : getConfig().getStringList("commands.breakthrough")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), 
                    cmd.replace("%player%", player.getName()));
            }
            
            // Visual effects
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
            double money = data.getMoney();
            
            String text = String.format(
                "&e⚡ &fCấp &6%d &7| &a%s &fXP: &b%.0f/%.0f &7| &c❤ %.1f &7| &6⚔ %.1f &7| &e💰 %.2f",
                level, bar, xp, required, health, damage, money
            );
            
            player.sendActionBar(Component.text(color(text)));
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

    public class MoneyCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command!");
                return true;
            }
            
            Player player = (Player) sender;
            double money = moneyManager.getMoney(player);
            player.sendMessage(color("&e&l💰 &fSố coins của bạn: &6" + DF_MONEY.format(money)));
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
                case "money":
                    return handleMoney(sender);
                case "givecoins":
                    return handleGiveCoins(sender, args);
                default:
                    sendHelp(sender);
                    return true;
            }
        }

        private void sendHelp(CommandSender sender) {
            sender.sendMessage(color("&6&lSchoolLevel &7- &fRPG Level System"));
            sender.sendMessage(color("&e/schoollevel reload &7- &fReload config"));
            sender.sendMessage(color("&e/schoollevel money &7- &fView your money"));
            sender.sendMessage(color("&e/schoollevel giveitem <player> &7- &fGive breakthrough item"));
            sender.sendMessage(color("&e/schoollevel givecoins <player> <amount> &7- &fGive coins"));
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

        private boolean handleMoney(CommandSender sender) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command!");
                return true;
            }
            Player player = (Player) sender;
            player.sendMessage(color("&e&l💰 &fSố coins của bạn: &6" + DF_MONEY.format(moneyManager.getMoney(player))));
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
                moneyManager.addMoney(target, amount);
                sender.sendMessage(color("&a✅ Gave " + DF_MONEY.format(amount) + " coins to " + target.getName()));
                target.sendMessage(color("&6&l💰 &fBạn nhận được &6" + DF_MONEY.format(amount) + " &fcoins!"));
            } catch (NumberFormatException e) {
                sender.sendMessage(color("&cInvalid amount!"));
            }
            return true;
        }
    }

    public class GiveItemCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length < 1) {
                sender.sendMessage(color("&cUsage: /schoollevel giveitem <player>"));
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
            
            target.getInventory().addItem(breakthroughManager.createBreakthroughItem());
            sender.sendMessage(color("&a✅ Gave breakthrough item to " + target.getName()));
            target.sendMessage(color("&6&l✦ &fYou received a &6Breakthrough Stone&f!"));
            return true;
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
            
            // Fill background
            ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta glassMeta = glass.getItemMeta();
            glassMeta.setDisplayName(" ");
            glass.setItemMeta(glassMeta);
            for (int i = 0; i < 54; i++) inventory.setItem(i, glass);
            
            // Player head
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta headMeta = (SkullMeta) head.getItemMeta();
            headMeta.setOwningPlayer(player);
            headMeta.setDisplayName(color("&6&l" + player.getName()));
            headMeta.setLore(Arrays.asList(color("&7Thông tin chi tiết của bạn")));
            head.setItemMeta(headMeta);
            inventory.setItem(4, head);
            
            // Stats
            double health = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
            double damage = player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).getValue();
            double speed = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getValue();
            int level = data.getLevel();
            double xp = data.getXp();
            double required = levelManager.getRequiredXP(level);
            double money = data.getMoney();
            
            inventory.setItem(20, createInfoItem(Material.EXPERIENCE_BOTTLE,
                "&6&l⚡ Cấp độ",
                Arrays.asList("&7Hiện tại: &6" + level, 
                    "&7Kinh nghiệm: &b" + DF.format(xp) + " &7/ &b" + DF.format(required),
                    "&7Tiến độ: &a" + DF.format((xp / required) * 100) + "%")));
            
            inventory.setItem(22, createInfoItem(Material.GOLDEN_APPLE,
                "&c&l❤ Máu tối đa",
                Arrays.asList("&7Lượng máu: &c" + DF.format(health), 
                    "&7Trái tim: &c" + DF.format(health / 2) + " &c❤")));
            
            inventory.setItem(24, createInfoItem(Material.DIAMOND_SWORD,
                "&6&l⚔ Sát thương",
                Arrays.asList("&7Sát thương: &6" + DF.format(damage))));
            
            inventory.setItem(29, createInfoItem(Material.FEATHER,
                "&b&l✦ Tốc độ",
                Arrays.asList("&7Tốc độ: &b" + DF.format(speed * 1000) + " &b%")));
            
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
            
            if (data.hasBrokenThrough()) {
                inventory.setItem(49, createInfoItem(Material.NETHER_STAR,
                    "&6&l✦ Đã Đột Phá",
                    Arrays.asList("&7Cấp độ: &6" + level, "&7Trạng thái: &a&l✦ Huyền Thoại ✦")));
            } else if (level >= 100) {
                inventory.setItem(49, createInfoItem(Material.RED_STAINED_GLASS_PANE,
                    "&e&l⚠ Cần Đột Phá",
                    Arrays.asList("&7Bạn đã đạt &6Cấp 100", "&7Sử dụng &6Đá Đột Phá &7để lên Cấp 101")));
            }
            
            player.openInventory(inventory);
        }

        private ItemStack createInfoItem(Material material, String name, List<String> lore) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(color(name));
            meta.setLore(lore.stream().map(SchoolLevelPlugin.this::color).toList());
            item.setItemMeta(meta);
            return item;
        }
    }

    // ==================== PLACEHOLDERAPI EXPANSION ====================
    public class SchoolLevelExpansion extends me.clip.placeholderapi.expansion.PlaceholderExpansion {
        @Override public String getIdentifier() { return "schoollevel"; }
        @Override public String getAuthor() { return "SchoolLevel"; }
        @Override public String getVersion() { return "1.0"; }

        @Override
        public String onPlaceholderRequest(Player player, String params) {
            if (player == null) return "";
            
            DataManager.PlayerData data = dataManager.getPlayerData(player);
            
            switch (params.toLowerCase()) {
                case "level": return String.valueOf(data.getLevel());
                case "level_formatted": return color("&6✦ Cấp " + data.getLevel());
                case "xp": return DF.format(data.getXp());
                case "required_xp": return DF.format(levelManager.getRequiredXP(data.getLevel()));
                case "xp_progress": return DF.format((data.getXp() / levelManager.getRequiredXP(data.getLevel())) * 100);
                case "blocks_broken": return String.valueOf(data.getBlocksBroken());
                case "minutes_online": 
                    return String.valueOf(player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60);
                case "health": 
                    return DF.format(player.getAttribute(Attribute.GENERIC