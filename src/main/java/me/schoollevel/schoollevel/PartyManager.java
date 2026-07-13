package me.schoollevel.schoollevel;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PartyManager {
    
    private final SchoolLevelPlugin plugin;
    private final Map<UUID, Party> playerParties = new ConcurrentHashMap<>();
    private final Map<UUID, PartyInvite> invites = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastShareMessage = new ConcurrentHashMap<>();
    
    private static final int MAX_PARTY_SIZE = 5;
    private static final int SHARE_COOLDOWN = 3000;
    
    public PartyManager(SchoolLevelPlugin plugin) {
        this.plugin = plugin;
    }
    
    public class Party {
        private final UUID leader;
        private final Set<UUID> members;
        private final long createdAt;
        
        public Party(Player leader) {
            this.leader = leader.getUniqueId();
            this.members = Collections.synchronizedSet(new HashSet<>());
            this.members.add(leader.getUniqueId());
            this.createdAt = System.currentTimeMillis();
        }
        
        public UUID getLeader() { return leader; }
        public Set<UUID> getMembers() { return members; }
        public long getCreatedAt() { return createdAt; }
        public int getSize() { return members.size(); }
        public boolean isFull() { return members.size() >= MAX_PARTY_SIZE; }
        
        public boolean isLeader(Player player) {
            return leader.equals(player.getUniqueId());
        }
        
        public boolean isMember(Player player) {
            return members.contains(player.getUniqueId());
        }
        
        public void addMember(Player player) {
            members.add(player.getUniqueId());
        }
        
        public void removeMember(Player player) {
            members.remove(player.getUniqueId());
        }
        
        public List<Player> getOnlineMembers() {
            List<Player> online = new ArrayList<>();
            for (UUID uuid : members) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    online.add(p);
                }
            }
            return online;
        }
        
        public void broadcast(String message) {
            for (Player member : getOnlineMembers()) {
                member.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            }
        }
        
        public void broadcastActionBar(String message) {
            for (Player member : getOnlineMembers()) {
                member.sendActionBar(net.kyori.adventure.text.Component.text(
                    ChatColor.translateAlternateColorCodes('&', message)
                ));
            }
        }
    }
    
    public class PartyInvite {
        private final UUID inviter;
        private final UUID invitee;
        private final UUID partyLeader;
        private final long createdAt;
        
        public PartyInvite(Player inviter, Player invitee, UUID partyLeader) {
            this.inviter = inviter.getUniqueId();
            this.invitee = invitee.getUniqueId();
            this.partyLeader = partyLeader;
            this.createdAt = System.currentTimeMillis();
        }
        
        public UUID getInviter() { return inviter; }
        public UUID getInvitee() { return invitee; }
        public UUID getPartyLeader() { return partyLeader; }
        public boolean isExpired() { return System.currentTimeMillis() - createdAt > 30000; }
    }
    
    public void createParty(Player player) {
        if (hasParty(player)) {
            player.sendMessage(plugin.color("&c❌ Bạn đã ở trong một party!"));
            return;
        }
        
        Party party = new Party(player);
        playerParties.put(player.getUniqueId(), party);
        player.sendMessage(plugin.color("&a✅ Bạn đã tạo party thành công!"));
        player.sendMessage(plugin.color("&e📢 &fGõ &6/party invite <tên> &fđể mời người chơi!"));
    }
    
    public void disbandParty(Player player) {
        Party party = getParty(player);
        if (party == null) {
            player.sendMessage(plugin.color("&c❌ Bạn không ở trong party nào!"));
            return;
        }
        
        if (!party.isLeader(player)) {
            player.sendMessage(plugin.color("&c❌ Chỉ chủ party mới có thể giải tán!"));
            return;
        }
        
        for (UUID uuid : party.getMembers()) {
            playerParties.remove(uuid);
        }
        
        party.broadcast("&c⛔ Party đã bị giải tán bởi &6" + player.getName());
    }
    
    public void invitePlayer(Player inviter, String targetName) {
        Party party = getParty(inviter);
        if (party == null) {
            inviter.sendMessage(plugin.color("&c❌ Bạn không ở trong party nào!"));
            return;
        }
        
        if (!party.isLeader(inviter)) {
            inviter.sendMessage(plugin.color("&c❌ Chỉ chủ party mới có thể mời người chơi!"));
            return;
        }
        
        if (party.isFull()) {
            inviter.sendMessage(plugin.color("&c❌ Party đã đầy! (Tối đa " + MAX_PARTY_SIZE + " người)"));
            return;
        }
        
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            inviter.sendMessage(plugin.color("&c❌ Không tìm thấy người chơi!"));
            return;
        }
        
        if (target.equals(inviter)) {
            inviter.sendMessage(plugin.color("&c❌ Bạn không thể tự mời mình!"));
            return;
        }
        
        if (hasParty(target)) {
            inviter.sendMessage(plugin.color("&c❌ Người chơi này đã ở trong một party khác!"));
            return;
        }
        
        invites.put(target.getUniqueId(), new PartyInvite(inviter, target, party.getLeader()));
        
        target.sendMessage(plugin.color("&e&l📨 &fBạn nhận được lời mời vào party từ &6" + inviter.getName()));
        target.sendMessage(plugin.color("&e📢 &fGõ &6/party accept &fđể chấp nhận!"));
        inviter.sendMessage(plugin.color("&a✅ Đã gửi lời mời đến &6" + target.getName()));
        
        new BukkitRunnable() {
            @Override
            public void run() {
                PartyInvite invite = invites.get(target.getUniqueId());
                if (invite != null && invite.getInviter().equals(inviter.getUniqueId())) {
                    invites.remove(target.getUniqueId());
                    target.sendMessage(plugin.color("&e⏰ Lời mời vào party đã hết hạn!"));
                }
            }
        }.runTaskLater(plugin, 600);
    }
    
    public void acceptInvite(Player player) {
        PartyInvite invite = invites.get(player.getUniqueId());
        if (invite == null) {
            player.sendMessage(plugin.color("&c❌ Bạn không có lời mời nào!"));
            return;
        }
        
        if (invite.isExpired()) {
            invites.remove(player.getUniqueId());
            player.sendMessage(plugin.color("&c❌ Lời mời đã hết hạn!"));
            return;
        }
        
        Party party = getPartyByLeader(invite.getPartyLeader());
        if (party == null) {
            player.sendMessage(plugin.color("&c❌ Party đã bị giải tán!"));
            invites.remove(player.getUniqueId());
            return;
        }
        
        if (party.isFull()) {
            player.sendMessage(plugin.color("&c❌ Party đã đầy!"));
            invites.remove(player.getUniqueId());
            return;
        }
        
        party.addMember(player);
        playerParties.put(player.getUniqueId(), party);
        invites.remove(player.getUniqueId());
        
        party.broadcast("&a✅ &6" + player.getName() + " &ađã tham gia party!");
    }
    
    public void leaveParty(Player player) {
        Party party = getParty(player);
        if (party == null) {
            player.sendMessage(plugin.color("&c❌ Bạn không ở trong party nào!"));
            return;
        }
        
        if (party.isLeader(player)) {
            player.sendMessage(plugin.color("&c❌ Bạn là chủ party, hãy dùng &6/party disband &cđể giải tán!"));
            return;
        }
        
        party.removeMember(player);
        playerParties.remove(player.getUniqueId());
        party.broadcast("&e&l👋 &6" + player.getName() + " &eđã rời khỏi party!");
        player.sendMessage(plugin.color("&e✅ Bạn đã rời khỏi party!"));
    }
    
    public void kickMember(Player player, String targetName) {
        Party party = getParty(player);
        if (party == null) {
            player.sendMessage(plugin.color("&c❌ Bạn không ở trong party nào!"));
            return;
        }
        
        if (!party.isLeader(player)) {
            player.sendMessage(plugin.color("&c❌ Chỉ chủ party mới có thể kick!"));
            return;
        }
        
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            player.sendMessage(plugin.color("&c❌ Không tìm thấy người chơi!"));
            return;
        }
        
        if (target.equals(player)) {
            player.sendMessage(plugin.color("&c❌ Bạn không thể tự kick mình!"));
            return;
        }
        
        if (!party.isMember(target)) {
            player.sendMessage(plugin.color("&c❌ Người chơi này không ở trong party!"));
            return;
        }
        
        party.removeMember(target);
        playerParties.remove(target.getUniqueId());
        
        target.sendMessage(plugin.color("&c⛔ Bạn đã bị kick khỏi party bởi &6" + player.getName()));
        party.broadcast("&c⛔ &6" + target.getName() + " &cđã bị kick khỏi party!");
    }
    
    public void listParty(Player player) {
        Party party = getParty(player);
        if (party == null) {
            player.sendMessage(plugin.color("&c❌ Bạn không ở trong party nào!"));
            return;
        }
        
        player.sendMessage(plugin.color("&6&l✦ &fThông tin Party &6✦"));
        player.sendMessage(plugin.color("&7Chủ party: &6" + Bukkit.getOfflinePlayer(party.getLeader()).getName()));
        player.sendMessage(plugin.color("&7Thành viên: &e" + party.getSize() + "/" + MAX_PARTY_SIZE));
        
        for (UUID uuid : party.getMembers()) {
            Player member = Bukkit.getPlayer(uuid);
            if (member != null) {
                String status = uuid.equals(party.getLeader()) ? " &6(Chủ)" : "";
                player.sendMessage(plugin.color("  &8- &f" + member.getName() + status));
            }
        }
    }
    
    public void shareXP(Player player, double amount) {
        Party party = getParty(player);
        if (party == null || party.getSize() <= 1) return;
        
        long now = System.currentTimeMillis();
        Long last = lastShareMessage.get(player.getUniqueId());
        if (last != null && now - last < SHARE_COOLDOWN) {
            return;
        }
        lastShareMessage.put(player.getUniqueId(), now);
        
        boolean isLeader = party.isLeader(player);
        double sharePercent = isLeader ? 0.20 : 0.10;
        double sharedXP = amount * sharePercent;
        double playerXP = amount * (1 - sharePercent);
        
        plugin.getLevelManager().addXP(player, playerXP);
        
        List<Player> members = party.getOnlineMembers();
        if (members.size() <= 1) return;
        
        double xpPerMember = sharedXP / (members.size() - 1);
        String senderName = player.getName();
        
        for (Player member : members) {
            if (member.equals(player)) continue;
            
            if (xpPerMember > 0) {
                String message = "&6&l✦ &f" + senderName + " &eđã chia sẻ &a" + 
                    SchoolLevelPlugin.DF.format(xpPerMember) + " XP &fcho bạn!";
                member.sendMessage(plugin.color(message));
                plugin.getLevelManager().addXP(member, xpPerMember);
            }
        }
        
        String sendMsg = "&6&l✦ &fBạn đã chia sẻ &a" + SchoolLevelPlugin.DF.format(sharedXP) + 
            " XP &fcho &e" + (members.size() - 1) + " &fthành viên!";
        player.sendMessage(plugin.color(sendMsg));
    }
    
    public boolean hasParty(Player player) {
        return playerParties.containsKey(player.getUniqueId());
    }
    
    public Party getParty(Player player) {
        return playerParties.get(player.getUniqueId());
    }
    
    public Party getPartyByLeader(UUID leader) {
        for (Party party : playerParties.values()) {
            if (party.getLeader().equals(leader)) {
                return party;
            }
        }
        return null;
    }
    
    public boolean isInParty(Player player) {
        return playerParties.containsKey(player.getUniqueId());
    }
    
    public int getPartySize(Player player) {
        Party party = getParty(player);
        return party != null ? party.getSize() : 0;
    }
    
    public boolean isPartyLeader(Player player) {
        Party party = getParty(player);
        return party != null && party.isLeader(player);
    }
}