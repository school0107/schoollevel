package me.schoollevel.schoollevel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.Ticks;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
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
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private NamespacedKey itemKey;
    private String menuTitleSerialized = "📊 BẢNG THÔNG TIN NGƯỜI CHƠI";

    private double healthPercentPerLevel = 0.01;
    private double damagePercentPerLevel = 0.01;
    private double speedPercentPerLevel = 0.005;

    // Biến lưu trữ hệ thống Kinh tế Vault
    private static Economy econ = null;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigData();
        createDataFile();

        // Kiểm tra và kết nối với Vault Economy
        if (!setupEconomy() ) {
            getLogger().severe(String.format("[%s] - Khởi thất bại do không tìm thấy plugin Vault hoặc plugin Kinh tế!", getDescription().getName()));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Đã kết nối thành công với hệ thống Kinh tế Vault! 💰");

        healthKey = new NamespacedKey(this, "schoollevel_health");
        damageKey = new NamespacedKey(this, "schoollevel_damage");
        speedKey = new NamespacedKey(this, "schoollevel_speed");
        itemKey = new NamespacedKey(this, "breakthrough_item");
        
        getServer().getPluginManager().registerEvents(this, this);
        
        if (getCommand("schoollevel") != null) getCommand("schoollevel").setExecutor(this);
        if (getCommand("profile") != null) getCommand("profile").setExecutor(this);
        
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new SchoolLevelPlaceholderExpansion(this).register();
            getLogger().info("Đã kết nối và kích hoạt toàn bộ PlaceholderAPI mở rộng! 🟢");
        }
        
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerAttributes(player);
            syncVanillaXpBar(player);
        }

        startActionBarTask();
        getLogger().info("SchoolLevel kích hoạt thành công trên nền tảng Paper/Purpur! 🚀");
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeAttributeModifiers(player);
        }
        saveAllData();
    }

    // Hàm thiết lập kết nối Vault
    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }

    private void loadConfigData() {
        reloadConfig();
        blockXpMap.clear();
        mobXpMap.clear();

        FileConfiguration config = getConfig();
        menuTitleSerialized = config.getString("menu.title", "📊 BẢNG THÔNG TIN NGƯỜI CHƠI");
        
        healthPercentPerLevel = config.getDouble("stats-per-level.health", 0.01);
        damagePercentPerLevel = config.getDouble("stats-per-level.damage", 0.01);
        speedPercentPerLevel = config.getDouble("stats-per-level.speed", 0.005);

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
            int blocks = dataConfig.getInt(uuid + ".blocks_broken", 0);
            return new PlayerData(lvl, xp, blocks);
        });
    }

    private void saveAllData() {
        dataCache.forEach((uuid, data) -> {
            dataConfig.set(uuid + ".level", data.level);
            dataConfig.set(uuid + ".xp", data.xp);
            dataConfig.set(uuid + ".blocks_broken", data.blocksBroken);
        });
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int getRequiredXp(int level) {
        int base = getConfig().getInt("settings.base-xp", 100);
        double mult = getConfig().getDouble("settings.xp-multiplier", 1.1);
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

    private void syncVanillaXpBar(Player player) {
        PlayerData data = getPlayerData(player.getUniqueId());
        player.setLevel(data.level);
        
        if (data.level >= 101) {
            player.setExp(1.0F);
        } else {
            int req = getRequiredXp(data.level);
            float progress = (float) data.xp / (float) req;
            player.setExp(Math.min(1.0F, Math.max(0.0F, progress)));
        }
    }

    private void updatePlayerAttributes(Player player) {
        PlayerData data = getPlayerData(player.getUniqueId());

        double healthBonus = data.level * healthPercentPerLevel;
        double damageBonus = data.level * damagePercentPerLevel;
        double speedBonus = data.level * speedPercentPerLevel;

        applyModifier(player, Attribute.GENERIC_MAX_HEALTH, healthKey, healthBonus);
        applyModifier(player, Attribute.GENERIC_ATTACK_DAMAGE, damageKey, damageBonus);
        applyModifier(player, Attribute.GENERIC_MOVEMENT_SPEED, speedKey, speedBonus);
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

    private void startActionBarTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                FileConfiguration config = getConfig();
                if (!config.getBoolean("actionbar.enabled", true)) return;

                String format = config.getString("actionbar.format", "");
                if (format.isEmpty()) return;

                for (Player player : Bukkit.getOnlinePlayers()) {
                    PlayerData data = getPlayerData(player.getUniqueId());
                    int nextXp = data.level >= 101 ? 0 : getRequiredXp(data.level);
                    
                    double health = player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null ? player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() : 20.0;
                    double damage = player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null ? player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).getValue() : 1.0;
                    double armor = player.getAttribute(Attribute.GENERIC_ARMOR) != null ? player.getAttribute(Attribute.GENERIC_ARMOR).getValue() : 0.0;
                    
                    String pb = "MAX";
                    if (data.level < 101 && nextXp > 0) {
                        int totalBars = 10;
                        int filledBars = (int) (((double) data.xp / nextXp) * totalBars);
                        filledBars = Math.min(totalBars, Math.max(0, filledBars));
                        pb = "■".repeat(filledBars) + "□".repeat(totalBars - filledBars);
                    }

                    String text = format
                            .replace("%level%", String.valueOf(data.level))
                            .replace("%xp%", String.valueOf(data.xp))
                            .replace("%next_xp%", data.level >= 101 ? "MAX" : String.valueOf(nextXp))
                            .replace("%progress_bar%", pb)
                            .replace("%health%", String.format("%.0f", player.getHealth()))
                            .replace("%max_health%", String.format("%.0f", health))
                            .replace("%damage%", String.format("%.1f", damage))
                            .replace("%armor%", String.format("%.1f", armor));

                    player.sendActionBar(mm.deserialize(text));
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private ItemStack createBreakthroughItem() {
        FileConfiguration config = getConfig();
        String path = "breakthrough-item";
        
        Material mat = Material.valueOf(config.getString(path + ".material", "NETHER_STAR").toUpperCase());
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        
        if (meta != null) {
            meta.displayName(mm.deserialize(config.getString(path + ".name", "<gradient:#f12711:#f5af19><b>🔥 ĐÁ ĐỘT PHÁ THẦN CẤP 🔥</b></gradient>")));
            List<String> rawLore = config.getStringList(path + ".lore");
            List<Component> lore = new ArrayList<>();
            for (String line : rawLore) {
                lore.add(mm.deserialize(line));
            }
            meta.lore(lore);
            meta.setCustomModelData(config.getInt(path + ".custom-model-data", 0));
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "true");
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;
        if (!item.hasItemMeta()) return;
        
        ItemMeta meta = item.getItemMeta();
        if (!meta.getPersistentDataContainer().has(itemKey, PersistentDataType.STRING)) return;
        
        event.setCancelled(true);
        PlayerData data = getPlayerData(player.getUniqueId());
        
        if (data.level < 100) {
            msg(player, "not-max");
            return;
        }
        if (data.level >= 101) {
            msg(player, "already-break");
            return;
        }
        
        item.setAmount(item.getAmount() - 1);
        data.level = 101;
        data.xp = 0;
        
        updatePlayerAttributes(player);
        syncVanillaXpBar(player);
        msg(player, "breakthrough-success");
        
        player.showTitle(Title.title(
                mm.deserialize("<gradient:#f12711:#f5af19><b>🔥 ĐỘT PHÁ THÀNH CÔNG 🔥</b></gradient>"),
                mm.deserialize("<#ffff00>Đã phá vỡ giới hạn để đạt cấp 101! 👑"),
                Title.Times.times(Ticks.duration(15), Ticks.duration(60), Ticks.duration(15))
        ));

        List<String> commands = getConfig().getStringList("breakthrough-item.commands-on-success");
        for (String cmd : commands) {
            String executable = cmd.replace("%player%", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), executable);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        updatePlayerAttributes(player);
        syncVanillaXpBar(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaExpChange(PlayerExpChangeEvent event) {
        event.setAmount(0);
        syncVanillaXpBar(event.getPlayer());
    }

    // EVENT ĐÀO BLOCK CHÍNH (Đã sửa đổi mức MONITOR và thêm tính năng cộng Money)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (p == null) return;

        // Kiểm tra xem block vỡ có nằm trong danh sách được nhận diện cấu hình không
        Material blockType = event.getBlock().getType();
        if (!blockXpMap.containsKey(blockType)) return;

        PlayerData data = getPlayerData(p.getUniqueId());
        data.blocksBroken++;

        // 1. Xử lý cộng XP Tu Vi
        int xpToAdd = blockXpMap.get(blockType);
        if (xpToAdd > 0) addXp(p, xpToAdd);

        // 2. Xử lý tính toán và cộng Money thông qua Vault
        // Công thức: Cấp 1 nhận 0.1$, Cấp 2 nhận 0.2$, ..., Cấp 100 nhận 10.0$
        double moneyToReward = 0.1 + (data.level - 1) * 0.1;
        if (moneyToReward > 0 && econ != null) {
            econ.depositPlayer(p, moneyToReward);
        }
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
        syncVanillaXpBar(player);
    }

    public void openProfileMenu(Player player) {
        FileConfiguration config = getConfig();
        int size = config.getInt("menu.size", 27);
        Component titleComponent = mm.deserialize(menuTitleSerialized);
        
        Inventory gui = Bukkit.createInventory(null, size, titleComponent);

        Material fillerMat = Material.valueOf(config.getString("menu.filler.material", "GRAY_STAINED_GLASS_PANE").toUpperCase());
        ItemStack fillerItem = new ItemStack(fillerMat);
        ItemMeta fillerMeta = fillerItem.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.displayName(mm.deserialize(config.getString("menu.filler.name", " ")));
            fillerItem.setItemMeta(fillerMeta);
        }
        List<Integer> fillerSlots = config.getIntegerList("menu.filler.slots");
        for (int slot : fillerSlots) {
            if (slot < size) gui.setItem(slot, fillerItem);
        }

        PlayerData data = getPlayerData(player.getUniqueId());
        int nextXp = data.level >= 101 ? 0 : getRequiredXp(data.level);
        
        double totalHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null ? player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() : 20.0;
        double totalDamage = player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null ? player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).getValue() : 1.0;
        double totalArmor = player.getAttribute(Attribute.GENERIC_ARMOR) != null ? player.getAttribute(Attribute.GENERIC_ARMOR).getValue() : 0.0;
        double totalSpeed = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null ? player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getValue() : 0.1;
        int minutesOnline = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60;

        setupMenuIcon(gui, player, "menu.stats.level", data.level, data.xp, nextXp, totalHealth, totalDamage, totalArmor, totalSpeed, data.blocksBroken, minutesOnline);
        setupMenuIcon(gui, player, "menu.stats.combat", data.level, data.xp, nextXp, totalHealth, totalDamage, totalArmor, totalSpeed, data.blocksBroken, minutesOnline);
        setupMenuIcon(gui, player, "menu.stats.activity", data.level, data.xp, nextXp, totalHealth, totalDamage, totalArmor, totalSpeed, data.blocksBroken, minutesOnline);

        String closePath = "menu.close_button";
        int closeSlot = config.getInt(closePath + ".slot", 22);
        Material closeMat = Material.valueOf(config.getString(closePath + ".material", "BARRIER").toUpperCase());
        ItemStack closeItem = new ItemStack(closeMat);
        ItemMeta closeMeta = closeItem.getItemMeta();
        if (closeMeta != null) {
            closeMeta.displayName(mm.deserialize(config.getString(closePath + ".name", "<red>Đóng</red>")));
            List<String> rawLore = config.getStringList(closePath + ".lore");
            List<Component> loreComponents = new ArrayList<>();
            for (String line : rawLore) loreComponents.add(mm.deserialize(line));
            closeMeta.lore(loreComponents);
            closeItem.setItemMeta(closeMeta);
        }
        gui.setItem(closeSlot, closeItem);

        player.openInventory(gui);
    }

    private void setupMenuIcon(Inventory gui, Player player, String path, int level, int xp, int nextXp, double health, double damage, double armor, double speed, int blocks, int minutes) {
        FileConfiguration config = getConfig();
        if (config.get(path) == null) return;

        int slot = config.getInt(path + ".slot");
        Material mat = Material.valueOf(config.getString(path + ".material", "STONE").toUpperCase());
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            String name = config.getString(path + ".name", "");
            meta.displayName(mm.deserialize(name));

            List<String> rawLore = config.getStringList(path + ".lore");
            List<Component> loreComponents = new ArrayList<>();

            for (String line : rawLore) {
                String replaced = line
                        .replace("%level%", String.valueOf(level))
                        .replace("%xp%", String.valueOf(xp))
                        .replace("%next_xp%", level >= 101 ? "MAX" : String.valueOf(nextXp))
                        .replace("%damage%", String.format("%.1f", damage))
                        .replace("%health%", String.format("%.1f", health))
                        .replace("%health_hearts%", String.valueOf((int)(health / 2)))
                        .replace("%armor%", String.format("%.1f", armor))
                        .replace("%speed%", String.format("%.3f", speed))
                        .replace("%blocks_broken%", String.valueOf(blocks))
                        .replace("%time_online%", String.valueOf(minutes));
                loreComponents.add(mm.deserialize(replaced));
            }
            meta.lore(loreComponents);
            item.setItemMeta(meta);
        }
        gui.setItem(slot, item);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().title().equals(mm.deserialize(menuTitleSerialized))) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            int closeSlot = getConfig().getInt("menu.close_button.slot", 22);
            if (slot == closeSlot) {
                event.getWhoClicked().closeInventory();
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("schoollevel") && args.length > 0 && args[0].equalsIgnoreCase("reload")) {
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

        if (command.getName().equalsIgnoreCase("schoollevel") && args.length > 1 && args[0].equalsIgnoreCase("giveitem")) {
            if (!sender.hasPermission("schoollevel.admin")) {
                msg(sender, "no-permission");
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage("❌ Không tìm thấy người chơi có tên " + args[1]);
                return true;
            }
            ItemStack bItem = createBreakthroughItem();
            target.getInventory().addItem(bItem);
            sender.sendMessage("🟢 Đã cấp 1 Vật phẩm Đột Phá cho người chơi " + target.getName());
            target.sendMessage(mm.deserialize(getConfig().getString("messages.prefix") + "<#00ffcc>Bạn nhận được Vật phẩm Đột Phá từ quản trị viên! 🎉"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Chỉ có người chơi mới có thể thực hiện lệnh này!");
            return true;
        }

        if (command.getName().equalsIgnoreCase("profile") || (args.length > 0 && args[0].equalsIgnoreCase("menu"))) {
            openProfileMenu(player);
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
        public int blocksBroken;

        PlayerData(int level, int xp, int blocksBroken) {
            this.level = level;
            this.xp = xp;
            this.blocksBroken = blocksBroken;
        }
    }

    public static class SchoolLevelPlaceholderExpansion extends me.clip.placeholderapi.expansion.PlaceholderExpansion {
        private final SchoolLevel plugin;
        private final MiniMessage mm = MiniMessage.miniMessage();

        public SchoolLevelPlaceholderExpansion(SchoolLevel plugin) {
            this.plugin = plugin;
        }

        @Override
        public String getIdentifier() { return "schoollevel"; }
        @Override
        public String getAuthor() { return "AI_Developer"; }
        @Override
        public String getVersion() { return "1.0.0"; }
        @Override
        public boolean persist() { return true; }

        @Override
        public String onPlaceholderRequest(Player player, String params) {
            if (player == null) return "";
            PlayerData data = plugin.getPlayerData(player.getUniqueId());

            switch (params.toLowerCase()) {
                case "level":
                    return String.valueOf(data.level);
                case "level_formatted":
                    String rawFormat = plugin.getConfig().getString("settings.papi-level-formatted", "⭐ %level%");
                    return LegacyComponentSerializer.legacySection().serialize(mm.deserialize(rawFormat.replace("%level%", String.valueOf(data.level))));
                case "xp":
                    return String.valueOf(data.xp);
                case "required_xp":
                    return data.level >= 101 ? "0" : String.valueOf(plugin.getRequiredXp(data.level));
                case "xp_progress":
                    return data.level >= 101 ? "MAX" : (data.xp + "/" + plugin.getRequiredXp(data.level));
                case "blocks_broken":
                    return String.valueOf(data.blocksBroken);
                case "minutes_online":
                    return String.valueOf(player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60);
                case "damage":
                    double damage = player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null ? player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).getValue() : 1.0;
                    return String.format("%.1f", damage);
                case "health":
                    double health = player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null ? player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() : 20.0;
                    return String.format("%.1f", health);
                case "health_hearts":
                    double h = player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null ? player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() : 20.0;
                    return String.valueOf((int)(h / 2));
                case "armor":
                    double armor = player.getAttribute(Attribute.GENERIC_ARMOR) != null ? player.getAttribute(Attribute.GENERIC_ARMOR).getValue() : 0.0;
                    return String.format("%.1f", armor);
                case "speed":
                    double speed = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null ? player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getValue() : 0.1;
                    return String.format("%.3f", speed);
                default:
                    return null;
            }
        }
    }
}
