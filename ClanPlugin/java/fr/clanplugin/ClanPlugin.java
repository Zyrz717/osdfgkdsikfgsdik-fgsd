package fr.clanplugin;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.projectiles.ProjectileSource;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Systeme de clans. A mettre dans : SuperShop/java/fr/clanplugin/ClanPlugin.java
 *
 * /clan                 menu du clan
 * /clan create <nom>    cree un clan (max 5 membres)
 * /clan invite <joueur> invite un joueur
 * /clan accept [nom] / deny [nom] / leave
 * /clan pvp             active/desactive le PvP entre membres (chef)
 * /clan dissoudre <nom> (staff clan.staff)
 * /clan info <joueur|clan> (staff clan.staff)
 */
public class ClanPlugin extends LoadedPlugin implements Listener {

    static final int MAX_MEMBERS = 5;

    private final Map<String, Clan> clans = new HashMap<>();
    private final Map<UUID, String> playerClan = new HashMap<>();
    private final Map<UUID, Set<String>> invites = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Set<String> myCommands = new HashSet<>();
    private File file;

    // ===================== CYCLE DE VIE =====================

    @Override
    public void onEnable() {
        this.file = new File(getHost().getDataFolder(), "clans.yml");
        load();
        registerListener(this);
        registerClanCommand();
        syncCommands();
        getLogger().info("[ClanPlugin] active.");
    }

    @Override
    public void onDisable() {
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
                    if (h instanceof ClanMenuHolder || h instanceof MemberMenuHolder) p.closeInventory();
                } catch (Throwable ignored) {}
            }
            try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}

            CommandMap map = getCommandMap();
            Map<String, Command> known = (map != null) ? knownCommands(map) : null;
            if (known != null) {
                for (String label : myCommands) {
                    Command c = known.remove(label);
                    if (c != null) { try { c.unregister(map); } catch (Throwable ignored) {} }
                    known.remove("clanplugin:" + label);
                }
            }
            myCommands.clear();
            syncCommands();
        } catch (Throwable t) {
            getLogger().warning("[ClanPlugin] erreur onDisable : " + t);
        }
        save();
        getLogger().info("[ClanPlugin] desactive.");
    }

    // ===================== ENREGISTREMENT COMMANDE =====================

    private CommandMap getCommandMap() {
        try {
            Object server = Bukkit.getServer();
            Method m = server.getClass().getMethod("getCommandMap");
            return (CommandMap) m.invoke(server);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> knownCommands(CommandMap map) {
        Class<?> c = map.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField("knownCommands");
                f.setAccessible(true);
                return (Map<String, Command>) f.get(map);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** Label principal + alias : tous forces dans la command map (voir CORRECTIF ci-dessous). */
    private static final List<String> LABELS = List.of("clan", "clans", "guilde");

    private void registerClanCommand() {
        CommandMap map = getCommandMap();
        if (map == null) { getLogger().warning("[ClanPlugin] CommandMap introuvable."); return; }
        Map<String, Command> known = knownCommands(map);

        // CORRECTIF : apres un rechargement du PluginLoader, seul "clan" etait remplace ;
        // "clans" et "guilde" restaient branches sur l'ANCIENNE instance (dont le listener
        // n'existait plus), donc /clans ouvrait un menu non protege ou on pouvait prendre
        // les items. On retire d'abord toute ancienne entree, puis on force les 3 labels.
        if (known != null) {
            for (String l : LABELS) {
                known.remove(l);
                known.remove("clanplugin:" + l);
            }
        }
        Command cmd = new Command("clan", "Systeme de clans", "/clan", List.of("clans", "guilde")) {
            @Override public boolean execute(CommandSender s, String l, String[] a) { return handleCommand(s, a); }
            @Override public List<String> tabComplete(CommandSender s, String alias, String[] a) { return handleTab(s, a); }
        };
        map.register("clanplugin", cmd);
        if (known != null) {
            for (String l : LABELS) {
                known.put(l, cmd);
                known.put("clanplugin:" + l, cmd);
            }
        }
        myCommands.addAll(LABELS);
    }

    private void syncCommands() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { p.updateCommands(); } catch (Throwable ignored) {}
        }
    }

    // ===================== MESSAGES =====================

    static final Component PREFIX = Component.text("Clans ", NamedTextColor.GOLD, TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_GRAY));

    static void send(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(PREFIX.append(Component.text(text, color)));
    }

    void broadcast(Clan clan, String text, NamedTextColor color) {
        for (UUID u : clan.getMembers()) {
            Player o = Bukkit.getPlayer(u);
            if (o != null) o.sendMessage(PREFIX.append(Component.text(text, color)));
        }
    }

    // ===================== LOGIQUE CLAN =====================

    Clan getClan(String name) { return name == null ? null : clans.get(name.toLowerCase(Locale.ROOT)); }
    Clan getClanOf(UUID u) { String k = playerClan.get(u); return k == null ? null : clans.get(k); }
    boolean hasClan(UUID u) { return playerClan.containsKey(u); }
    Collection<Clan> getClans() { return clans.values(); }
    Set<String> getInvites(UUID u) { return invites.getOrDefault(u, Collections.emptySet()); }

    String pname(UUID u) {
        Player o = Bukkit.getPlayer(u);
        if (o != null) return o.getName();
        return names.getOrDefault(u, u.toString().substring(0, 8));
    }

    UUID uuidByName(String name) {
        Player o = Bukkit.getPlayerExact(name);
        if (o != null) return o.getUniqueId();
        for (Map.Entry<UUID, String> e : names.entrySet())
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    boolean validName(String n) { return n != null && n.matches("[A-Za-z0-9_]{3,16}"); }

    // ===================== PVP ENTRE MEMBRES =====================

    @EventHandler
    public void onClanDamage(EntityDamageByEntityEvent e) {
        if (e.isCancelled()) return;
        if (!(e.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (e.getDamager() instanceof Player p) {
            attacker = p;
        } else if (e.getDamager() instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) attacker = p;
        }
        if (attacker == null || attacker.equals(victim)) return;

        Clan clan = getClanOf(attacker.getUniqueId());
        if (clan == null) return;
        // Meme clan et PvP interne desactive -> on annule les degats.
        if (clan.isMember(victim.getUniqueId()) && !clan.isPvp()) {
            e.setCancelled(true);
            attacker.sendMessage(PREFIX.append(Component.text(
                    "PvP desactive entre membres du clan. (/clan pvp pour activer)", NamedTextColor.RED)));
        }
    }

    // ===================== COMMANDE =====================

    private boolean handleCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) { send(sender, "Joueurs uniquement.", NamedTextColor.RED); return true; }
            Clan clan = getClanOf(p.getUniqueId());
            if (clan == null) { send(p, "Tu n'es dans aucun clan. /clan create <nom>", NamedTextColor.RED); return true; }
            openMain(p, clan);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create": case "creer": return cmdCreate(sender, args);
            case "invite": case "inviter": return cmdInvite(sender, args);
            case "accept": case "accepter": return cmdAccept(sender, args);
            case "deny": case "refuser": return cmdDeny(sender, args);
            case "leave": case "quitter": return cmdLeave(sender);
            case "pvp": return cmdPvp(sender);
            case "sethome": case "definirhome": return cmdSetHome(sender);
            case "home": return cmdHome(sender);
            case "delhome": return cmdDelHome(sender);
            case "dissoudre": case "disband": return cmdDisband(sender, args);
            case "info": return cmdInfo(sender, args);
            default: cmdHelp(sender); return true;
        }
    }

    private boolean cmdCreate(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { send(s, "Joueurs uniquement.", NamedTextColor.RED); return true; }
        if (a.length < 2) { send(p, "Usage: /clan create <nom>", NamedTextColor.RED); return true; }
        String name = a[1];
        if (hasClan(p.getUniqueId())) { send(p, "Tu es deja dans un clan.", NamedTextColor.RED); return true; }
        if (!validName(name)) { send(p, "Nom invalide (3-16 : lettres, chiffres, _).", NamedTextColor.RED); return true; }
        if (clans.containsKey(name.toLowerCase(Locale.ROOT))) { send(p, "Nom deja pris.", NamedTextColor.RED); return true; }
        Clan clan = new Clan(name, p.getUniqueId());
        clans.put(name.toLowerCase(Locale.ROOT), clan);
        playerClan.put(p.getUniqueId(), name.toLowerCase(Locale.ROOT));
        names.put(p.getUniqueId(), p.getName());
        save();
        send(p, "Clan " + name + " cree ! Tu en es le chef.", NamedTextColor.GREEN);
        return true;
    }

    private boolean cmdInvite(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { send(s, "Joueurs uniquement.", NamedTextColor.RED); return true; }
        if (a.length < 2) { send(p, "Usage: /clan invite <joueur>", NamedTextColor.RED); return true; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { send(p, "Joueur non connecte.", NamedTextColor.RED); return true; }
        Clan clan = getClanOf(p.getUniqueId());
        if (clan == null) { send(p, "Tu n'es dans aucun clan.", NamedTextColor.RED); return true; }
        if (!clan.canInvite(p.getUniqueId())) { send(p, "Seul le chef ou un bras droit peut inviter.", NamedTextColor.RED); return true; }
        if (p.getUniqueId().equals(t.getUniqueId())) { send(p, "Tu ne peux pas t'inviter.", NamedTextColor.RED); return true; }
        if (clan.size() >= MAX_MEMBERS) { send(p, "Clan plein (" + MAX_MEMBERS + " max).", NamedTextColor.RED); return true; }
        if (hasClan(t.getUniqueId())) { send(p, "Ce joueur est deja dans un clan.", NamedTextColor.RED); return true; }
        Set<String> set = invites.computeIfAbsent(t.getUniqueId(), k -> new HashSet<>());
        if (!set.add(clan.getName().toLowerCase(Locale.ROOT))) { send(p, "Invitation deja en attente.", NamedTextColor.RED); return true; }
        names.put(p.getUniqueId(), p.getName());
        names.put(t.getUniqueId(), t.getName());
        send(p, "Tu as invite " + t.getName() + " dans le clan.", NamedTextColor.GREEN);
        send(t, "Invitation dans le clan " + clan.getName() + " !", NamedTextColor.YELLOW);
        send(t, "/clan accept " + clan.getName() + "  ou  /clan deny " + clan.getName(), NamedTextColor.GRAY);
        return true;
    }

    private boolean cmdAccept(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { send(s, "Joueurs uniquement.", NamedTextColor.RED); return true; }
        if (hasClan(p.getUniqueId())) { send(p, "Tu es deja dans un clan.", NamedTextColor.RED); return true; }
        Set<String> pend = invites.get(p.getUniqueId());
        if (pend == null || pend.isEmpty()) { send(p, "Aucune invitation.", NamedTextColor.RED); return true; }
        String name = a.length >= 2 ? a[1] : null;
        String key;
        if (name == null) {
            if (pend.size() == 1) key = pend.iterator().next();
            else { send(p, "Plusieurs invitations : /clan accept <nom>", NamedTextColor.RED);
                   send(p, String.join(", ", pend), NamedTextColor.GRAY); return true; }
        } else {
            key = name.toLowerCase(Locale.ROOT);
            if (!pend.contains(key)) { send(p, "Aucune invitation pour ce clan.", NamedTextColor.RED); return true; }
        }
        Clan clan = clans.get(key);
        if (clan == null) { pend.remove(key); send(p, "Ce clan n'existe plus.", NamedTextColor.RED); return true; }
        if (clan.size() >= MAX_MEMBERS) { send(p, "Ce clan est plein.", NamedTextColor.RED); return true; }
        clan.addMember(p.getUniqueId());
        playerClan.put(p.getUniqueId(), key);
        invites.remove(p.getUniqueId());
        names.put(p.getUniqueId(), p.getName());
        save();
        send(p, "Tu as rejoint le clan " + clan.getName() + " !", NamedTextColor.GREEN);
        broadcast(clan, p.getName() + " a rejoint le clan !", NamedTextColor.GREEN);
        return true;
    }

    private boolean cmdDeny(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { send(s, "Joueurs uniquement.", NamedTextColor.RED); return true; }
        Set<String> set = invites.get(p.getUniqueId());
        if (set == null || set.isEmpty()) { send(p, "Aucune invitation.", NamedTextColor.RED); return true; }
        if (a.length >= 2) set.remove(a[1].toLowerCase(Locale.ROOT)); else invites.remove(p.getUniqueId());
        if (set.isEmpty()) invites.remove(p.getUniqueId());
        send(p, "Invitation refusee.", NamedTextColor.GRAY);
        return true;
    }

    private boolean cmdLeave(CommandSender s) {
        if (!(s instanceof Player p)) { send(s, "Joueurs uniquement.", NamedTextColor.RED); return true; }
        Clan clan = getClanOf(p.getUniqueId());
        if (clan == null) { send(p, "Tu n'es dans aucun clan.", NamedTextColor.RED); return true; }
        if (clan.isLeader(p.getUniqueId())) { send(p, "Tu es le chef. /clan dissoudre " + clan.getName(), NamedTextColor.RED); return true; }
        clan.removeMember(p.getUniqueId());
        playerClan.remove(p.getUniqueId());
        save();
        send(p, "Tu as quitte le clan.", NamedTextColor.GRAY);
        broadcast(clan, p.getName() + " a quitte le clan.", NamedTextColor.GRAY);
        return true;
    }

    private boolean cmdPvp(CommandSender s) {
        if (!(s instanceof Player p)) { send(s, "Joueurs uniquement.", NamedTextColor.RED); return true; }
        Clan clan = getClanOf(p.getUniqueId());
        if (clan == null) { send(p, "Tu n'es dans aucun clan.", NamedTextColor.RED); return true; }
        if (!clan.isLeader(p.getUniqueId())) { send(p, "Seul le chef peut changer le PvP du clan.", NamedTextColor.RED); return true; }
        clan.setPvp(!clan.isPvp());
        save();
        if (clan.isPvp()) {
            broadcast(clan, "Le PvP entre membres est maintenant ACTIVE.", NamedTextColor.RED);
        } else {
            broadcast(clan, "Le PvP entre membres est maintenant DESACTIVE.", NamedTextColor.GREEN);
        }
        return true;
    }

    private boolean cmdSetHome(CommandSender s) {
        if (!(s instanceof Player p)) { send(s, "Joueurs uniquement.", NamedTextColor.RED); return true; }
        Clan clan = getClanOf(p.getUniqueId());
        if (clan == null) { send(p, "Tu n'es dans aucun clan.", NamedTextColor.RED); return true; }
        if (!clan.isLeader(p.getUniqueId())) { send(p, "Seul le chef peut definir le home du clan.", NamedTextColor.RED); return true; }
        Location loc = p.getLocation();
        if (loc.getWorld() == null) { send(p, "Position invalide.", NamedTextColor.RED); return true; }
        clan.setHome(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        save();
        send(p, "Home du clan defini a ta position actuelle.", NamedTextColor.GREEN);
        return true;
    }

    private boolean cmdHome(CommandSender s) {
        if (!(s instanceof Player p)) { send(s, "Joueurs uniquement.", NamedTextColor.RED); return true; }
        Clan clan = getClanOf(p.getUniqueId());
        if (clan == null) { send(p, "Tu n'es dans aucun clan.", NamedTextColor.RED); return true; }
        if (!clan.hasHome()) { send(p, "Le home du clan n'est pas encore defini.", NamedTextColor.RED); return true; }
        if (!clan.canUseHome(p.getUniqueId())) {
            send(p, "Tu n'as pas l'autorisation d'aller au home du clan.", NamedTextColor.RED); return true;
        }
        Location loc = clan.getHome();
        if (loc == null) { send(p, "Le monde du home est introuvable (non charge ?).", NamedTextColor.RED); return true; }
        p.teleport(loc);
        send(p, "Teleporte au home du clan.", NamedTextColor.GREEN);
        return true;
    }

    private boolean cmdDelHome(CommandSender s) {
        if (!(s instanceof Player p)) { send(s, "Joueurs uniquement.", NamedTextColor.RED); return true; }
        Clan clan = getClanOf(p.getUniqueId());
        if (clan == null) { send(p, "Tu n'es dans aucun clan.", NamedTextColor.RED); return true; }
        if (!clan.isLeader(p.getUniqueId())) { send(p, "Seul le chef peut supprimer le home du clan.", NamedTextColor.RED); return true; }
        if (!clan.hasHome()) { send(p, "Aucun home defini.", NamedTextColor.RED); return true; }
        clan.clearHome();
        save();
        send(p, "Home du clan supprime.", NamedTextColor.GRAY);
        return true;
    }

    private boolean cmdDisband(CommandSender s, String[] a) {
        boolean staff = s.hasPermission("clan.staff");
        Clan target;
        if (a.length >= 2) {
            target = getClan(a[1]);
            if (target == null) { send(s, "Aucun clan nomme " + a[1] + ".", NamedTextColor.RED); return true; }
            if (!staff && (!(s instanceof Player p) || !target.isLeader(p.getUniqueId()))) {
                send(s, "Pas la permission.", NamedTextColor.RED); return true;
            }
        } else {
            if (!(s instanceof Player p)) { send(s, "Usage: /clan dissoudre <nom>", NamedTextColor.RED); return true; }
            target = getClanOf(p.getUniqueId());
            if (target == null || !target.isLeader(p.getUniqueId())) { send(s, "Usage: /clan dissoudre <nom>", NamedTextColor.RED); return true; }
        }
        String name = target.getName();
        broadcast(target, "Le clan " + name + " a ete dissous.", NamedTextColor.RED);
        String key = name.toLowerCase(Locale.ROOT);
        for (UUID u : target.getMembers()) playerClan.remove(u);
        clans.remove(key);
        for (Set<String> set : invites.values()) set.remove(key);
        save();
        send(s, "Clan " + name + " dissous.", NamedTextColor.GREEN);
        return true;
    }

    private boolean cmdInfo(CommandSender s, String[] a) {
        if (!s.hasPermission("clan.staff")) { send(s, "Permission clan.staff requise.", NamedTextColor.RED); return true; }
        if (a.length < 2) { send(s, "Usage: /clan info <joueur|clan>", NamedTextColor.RED); return true; }
        Clan clan = getClan(a[1]);
        if (clan != null) { clanInfo(s, clan); return true; }
        UUID u = uuidByName(a[1]);
        if (u == null) { send(s, "Aucun clan ni joueur nomme " + a[1] + ".", NamedTextColor.RED); return true; }
        Clan pc = getClanOf(u);
        if (pc == null) { send(s, pname(u) + " n'est dans aucun clan.", NamedTextColor.GRAY); return true; }
        send(s, pname(u) + " -> clan " + pc.getName(), NamedTextColor.YELLOW);
        clanInfo(s, pc);
        return true;
    }

    private void clanInfo(CommandSender s, Clan clan) {
        s.sendMessage(Component.text("Clan: " + clan.getName() + " (" + clan.size() + "/" + MAX_MEMBERS + ")", NamedTextColor.GOLD));
        s.sendMessage(Component.text("Chef: " + pname(clan.getLeader()), NamedTextColor.GRAY));
        s.sendMessage(Component.text("PvP interne: " + (clan.isPvp() ? "active" : "desactive"), NamedTextColor.GRAY));
        List<String> off = new ArrayList<>();
        for (UUID u : clan.getOfficers()) off.add(pname(u));
        s.sendMessage(Component.text("Bras droits: " + (off.isEmpty() ? "aucun" : String.join(", ", off)), NamedTextColor.GRAY));
        List<String> mem = new ArrayList<>();
        for (UUID u : clan.getMembers()) if (!clan.isLeader(u) && !clan.isOfficer(u)) mem.add(pname(u));
        s.sendMessage(Component.text("Membres: " + (mem.isEmpty() ? "aucun" : String.join(", ", mem)), NamedTextColor.GRAY));
    }

    private void cmdHelp(CommandSender s) {
        s.sendMessage(Component.text("Clans - Aide", NamedTextColor.GOLD, TextDecoration.BOLD));
        s.sendMessage(Component.text("/clan - Menu du clan", NamedTextColor.YELLOW));
        s.sendMessage(Component.text("/clan create <nom>", NamedTextColor.YELLOW));
        s.sendMessage(Component.text("/clan invite <joueur> (max " + MAX_MEMBERS + ")", NamedTextColor.YELLOW));
        s.sendMessage(Component.text("/clan accept [nom]  |  /clan deny [nom]", NamedTextColor.YELLOW));
        s.sendMessage(Component.text("/clan pvp - active/desactive le PvP entre membres (chef)", NamedTextColor.YELLOW));
        s.sendMessage(Component.text("/clan sethome - definit le home du clan (chef)", NamedTextColor.YELLOW));
        s.sendMessage(Component.text("/clan home - se teleporte au home (si autorise)", NamedTextColor.YELLOW));
        s.sendMessage(Component.text("/clan leave", NamedTextColor.YELLOW));
        if (s.hasPermission("clan.staff")) {
            s.sendMessage(Component.text("/clan dissoudre <nom> (staff)", NamedTextColor.RED));
            s.sendMessage(Component.text("/clan info <joueur|clan> (staff)", NamedTextColor.RED));
        }
    }

    private List<String> handleTab(CommandSender s, String[] a) {
        if (a.length == 1) {
            List<String> subs = new ArrayList<>(List.of("create", "invite", "accept", "deny", "leave", "pvp", "sethome", "home", "delhome", "help"));
            if (s.hasPermission("clan.staff")) { subs.add("dissoudre"); subs.add("info"); }
            else if (s instanceof Player p) {
                Clan cl = getClanOf(p.getUniqueId());
                if (cl != null && cl.isLeader(p.getUniqueId())) subs.add("dissoudre");
            }
            return filter(subs, a[0]);
        }
        if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "invite": {
                    List<String> l = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName());
                    return filter(l, a[1]);
                }
                case "accept": case "deny":
                    if (s instanceof Player p) return filter(new ArrayList<>(getInvites(p.getUniqueId())), a[1]);
                    return Collections.emptyList();
                case "dissoudre": case "info":
                    if (s.hasPermission("clan.staff")) {
                        List<String> l = new ArrayList<>();
                        for (Clan cl : getClans()) l.add(cl.getName());
                        return filter(l, a[1]);
                    }
                    return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> opts, String start) {
        String x = start.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.toLowerCase(Locale.ROOT).startsWith(x)) out.add(o);
        return out;
    }

    // ===================== GUI =====================

    private static final int INFO_SLOT = 4;
    private static final int[] MEMBER_SLOTS = {19, 20, 21, 22, 23};
    private static final int HEAD_SLOT = 4, KICK_SLOT = 11, ROLE_SLOT = 15, BACK_SLOT = 22;
    private static final int HOME_ACCESS_SLOT = 13;
    private static final int PVP_SLOT = 8;

    private void openMain(Player viewer, Clan clan) {
        ClanMenuHolder holder = new ClanMenuHolder(clan.getName());
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Clan: " + clan.getName(), NamedTextColor.GOLD));
        holder.inv = inv;
        fill(inv);

        List<Component> info = new ArrayList<>();
        info.add(line("Chef: " + pname(clan.getLeader()), NamedTextColor.GRAY));
        info.add(line("Membres: " + clan.size() + "/" + MAX_MEMBERS, NamedTextColor.GRAY));
        info.add(line("Bras droits: " + clan.getOfficers().size(), NamedTextColor.GRAY));
        inv.setItem(INFO_SLOT, item(Material.OAK_SIGN, "Clan " + clan.getName(), NamedTextColor.GOLD, info));

        // Bouton PvP (visible par tous, cliquable seulement par le chef)
        boolean pvpOn = clan.isPvp();
        List<Component> pvpLore = new ArrayList<>();
        pvpLore.add(line("Etat: " + (pvpOn ? "ACTIVE" : "DESACTIVE"), pvpOn ? NamedTextColor.RED : NamedTextColor.GREEN));
        if (clan.isLeader(viewer.getUniqueId())) {
            pvpLore.add(Component.empty());
            pvpLore.add(line("Clic pour " + (pvpOn ? "desactiver" : "activer"), NamedTextColor.YELLOW));
        }
        inv.setItem(PVP_SLOT, item(pvpOn ? Material.DIAMOND_SWORD : Material.SHIELD,
                "PvP entre membres", pvpOn ? NamedTextColor.RED : NamedTextColor.GREEN, pvpLore));

        boolean canManage = clan.isLeader(viewer.getUniqueId()) || clan.isOfficer(viewer.getUniqueId());
        List<UUID> ordered = new ArrayList<>();
        ordered.add(clan.getLeader());
        for (UUID u : clan.getOfficers()) if (!ordered.contains(u)) ordered.add(u);
        for (UUID u : clan.getMembers()) if (!ordered.contains(u)) ordered.add(u);

        int i = 0;
        for (UUID u : ordered) {
            if (i >= MEMBER_SLOTS.length) break;
            int slot = MEMBER_SLOTS[i];
            List<Component> lore = new ArrayList<>();
            lore.add(line("Grade: ", NamedTextColor.GRAY).append(roleComp(clan.getRole(u))));
            if (canManage && canManage(clan, viewer.getUniqueId(), u)) {
                lore.add(Component.empty());
                lore.add(line("Clic pour gerer ce membre", NamedTextColor.YELLOW));
            }
            inv.setItem(slot, head(pname(u), clan.getRole(u), lore, u));
            holder.slotToMember.put(slot, u);
            i++;
        }
        viewer.openInventory(inv);
    }

    private void openMember(Player viewer, Clan clan, UUID target) {
        MemberMenuHolder holder = new MemberMenuHolder(clan.getName(), target);
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Gerer: " + pname(target), NamedTextColor.GOLD));
        holder.inv = inv;
        fill(inv);

        Clan.Role tr = clan.getRole(target);
        boolean leader = clan.isLeader(viewer.getUniqueId());
        boolean officer = clan.isOfficer(viewer.getUniqueId());

        List<Component> hl = new ArrayList<>();
        hl.add(line("Grade: ", NamedTextColor.GRAY).append(roleComp(tr)));
        inv.setItem(HEAD_SLOT, head(pname(target), tr, hl, target));

        boolean canKick = (leader && tr != Clan.Role.LEADER) || (officer && tr == Clan.Role.MEMBER);
        if (canKick) inv.setItem(KICK_SLOT, item(Material.BARRIER, "Exclure", NamedTextColor.RED,
                List.of(line("Retire ce joueur du clan.", NamedTextColor.GRAY))));

        if (leader) {
            if (tr == Clan.Role.MEMBER)
                inv.setItem(ROLE_SLOT, item(Material.EMERALD, "Promouvoir bras droit", NamedTextColor.GREEN,
                        List.of(line("Peut inviter et exclure des membres.", NamedTextColor.GRAY))));
            else if (tr == Clan.Role.OFFICER)
                inv.setItem(ROLE_SLOT, item(Material.GRAY_DYE, "Repasser membre", NamedTextColor.YELLOW,
                        List.of(line("Retire le grade de bras droit.", NamedTextColor.GRAY))));
        }

        // Bouton d'acces au /clan home (chef uniquement, pour les autres membres)
        if (leader && tr != Clan.Role.LEADER) {
            boolean allowed = clan.canUseHome(target);
            List<Component> hlore = new ArrayList<>();
            hlore.add(line("Etat: " + (allowed ? "AUTORISE" : "REFUSE"),
                    allowed ? NamedTextColor.GREEN : NamedTextColor.RED));
            hlore.add(Component.empty());
            hlore.add(line("Clic pour " + (allowed ? "retirer" : "donner") + " l'acces a /clan home",
                    NamedTextColor.YELLOW));
            inv.setItem(HOME_ACCESS_SLOT, item(allowed ? Material.LIME_DYE : Material.RED_DYE,
                    "Acces au /clan home", allowed ? NamedTextColor.GREEN : NamedTextColor.RED, hlore));
        }

        inv.setItem(BACK_SLOT, item(Material.ARROW, "Retour", NamedTextColor.GRAY, List.of()));
        viewer.openInventory(inv);
    }

    private static boolean canManage(Clan clan, UUID viewer, UUID target) {
        if (viewer.equals(target)) return false;
        Clan.Role vr = clan.getRole(viewer), tr = clan.getRole(target);
        if (vr == null || tr == null || tr == Clan.Role.LEADER) return false;
        if (vr == Clan.Role.LEADER) return true;
        if (vr == Clan.Role.OFFICER) return tr == Clan.Role.MEMBER;
        return false;
    }

    // ---- events GUI ----

    /** Menu de clan ouvert par une ANCIENNE instance du plugin (classes differentes apres rechargement). */
    private boolean isStaleClanMenu(InventoryHolder h) {
        if (h == null || h instanceof ClanMenuHolder || h instanceof MemberMenuHolder) return false;
        String cn = h.getClass().getName();
        return cn.endsWith("ClanPlugin$ClanMenuHolder") || cn.endsWith("ClanPlugin$MemberMenuHolder");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (h instanceof ClanMenuHolder || h instanceof MemberMenuHolder || isStaleClanMenu(h)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (isStaleClanMenu(h)) {
            // On bloque le clic et on rouvre le menu a jour.
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) {
                p.closeInventory();
                Bukkit.getScheduler().runTask(getHost(), () -> p.performCommand("clan"));
            }
            return;
        }
        if (!(h instanceof ClanMenuHolder) && !(h instanceof MemberMenuHolder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType().isAir() || it.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

        if (h instanceof ClanMenuHolder holder) {
            Clan clan = getClan(holder.clanName);
            if (clan == null) { p.closeInventory(); return; }

            // Clic sur le bouton PvP
            if (e.getSlot() == PVP_SLOT) {
                if (!clan.isLeader(p.getUniqueId())) { send(p, "Seul le chef peut changer le PvP.", NamedTextColor.RED); return; }
                clan.setPvp(!clan.isPvp());
                save();
                if (clan.isPvp()) broadcast(clan, "Le PvP entre membres est maintenant ACTIVE.", NamedTextColor.RED);
                else broadcast(clan, "Le PvP entre membres est maintenant DESACTIVE.", NamedTextColor.GREEN);
                openMain(p, clan);
                return;
            }

            UUID target = holder.slotToMember.get(e.getSlot());
            if (target == null || !canManage(clan, p.getUniqueId(), target)) return;
            openMember(p, clan, target);
        } else {
            MemberMenuHolder holder = (MemberMenuHolder) h;
            Clan clan = getClan(holder.clanName);
            if (clan == null) { p.closeInventory(); return; }
            UUID target = holder.target;
            int slot = e.getSlot();

            if (slot == BACK_SLOT) { openMain(p, clan); return; }

            if (slot == HOME_ACCESS_SLOT) {
                if (!clan.isLeader(p.getUniqueId())) { send(p, "Seul le chef peut gerer cet acces.", NamedTextColor.RED); return; }
                if (clan.isLeader(target)) return; // le chef a toujours l'acces
                boolean now = !clan.canUseHome(target);
                clan.setHomeAccess(target, now);
                save();
                Player t = Bukkit.getPlayer(target);
                if (now) {
                    send(p, "Tu as autorise " + pname(target) + " a utiliser /clan home.", NamedTextColor.GREEN);
                    if (t != null) send(t, "Tu peux maintenant utiliser /clan home !", NamedTextColor.GREEN);
                } else {
                    send(p, "Tu as retire l'acces a /clan home a " + pname(target) + ".", NamedTextColor.YELLOW);
                    if (t != null) send(t, "Tu ne peux plus utiliser /clan home.", NamedTextColor.GRAY);
                }
                openMember(p, clan, target);
                return;
            }

            if (slot == KICK_SLOT) {
                if (doKick(clan, p.getUniqueId(), target)) {
                    String tn = pname(target);
                    send(p, "Tu as exclu " + tn + " du clan.", NamedTextColor.GREEN);
                    Player k = Bukkit.getPlayer(target);
                    if (k != null) send(k, "Tu as ete exclu du clan " + clan.getName() + ".", NamedTextColor.RED);
                    broadcast(clan, tn + " a ete exclu du clan.", NamedTextColor.GRAY);
                    openMain(p, clan);
                } else send(p, "Impossible d'exclure ce membre.", NamedTextColor.RED);
                return;
            }

            if (slot == ROLE_SLOT) {
                Clan.Role r = clan.getRole(target);
                if (r == Clan.Role.MEMBER && clan.isLeader(p.getUniqueId())) {
                    clan.promote(target); save();
                    send(p, "Tu as promu " + pname(target) + " bras droit.", NamedTextColor.GREEN);
                    Player t = Bukkit.getPlayer(target);
                    if (t != null) send(t, "Tu es maintenant bras droit du clan !", NamedTextColor.GREEN);
                } else if (r == Clan.Role.OFFICER && clan.isLeader(p.getUniqueId())) {
                    clan.demote(target); save();
                    send(p, "Tu as repasse " + pname(target) + " membre simple.", NamedTextColor.YELLOW);
                    Player t = Bukkit.getPlayer(target);
                    if (t != null) send(t, "Tu es de nouveau membre du clan.", NamedTextColor.GRAY);
                }
                openMember(p, clan, target);
            }
        }
    }

    private boolean doKick(Clan clan, UUID actor, UUID target) {
        if (!clan.isMember(target) || clan.isLeader(target) || target.equals(actor)) return false;
        if (clan.isLeader(actor)) { /* ok */ }
        else if (clan.isOfficer(actor)) { if (clan.isOfficer(target)) return false; }
        else return false;
        clan.removeMember(target);
        playerClan.remove(target);
        save();
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        names.put(e.getPlayer().getUniqueId(), e.getPlayer().getName());
    }

    // ---- helpers items ----

    private void fill(Inventory inv) {
        ItemStack f = item(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_GRAY, List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, f);
    }

    private ItemStack item(Material mat, String name, NamedTextColor color, List<Component> lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(line(name, color));
            if (!lore.isEmpty()) meta.lore(lore);
            is.setItemMeta(meta);
        }
        return is;
    }

    private ItemStack head(String name, Clan.Role role, List<Component> lore, UUID owner) {
        ItemStack is = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) is.getItemMeta();
        if (meta != null) {
            try { meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner)); } catch (Throwable ignored) {}
            meta.displayName(line(name + " - ", NamedTextColor.YELLOW).append(roleComp(role)));
            if (!lore.isEmpty()) meta.lore(lore);
            is.setItemMeta(meta);
        }
        return is;
    }

    private static Component line(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    private static Component roleComp(Clan.Role r) {
        if (r == null) return Component.text("?", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
        switch (r) {
            case LEADER: return Component.text("Chef", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false);
            case OFFICER: return Component.text("Bras droit", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false);
            default: return Component.text("Membre", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false);
        }
    }

    // ===================== SAUVEGARDE =====================

    private void load() {
        clans.clear(); playerClan.clear(); names.clear();
        if (file == null || !file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection cs = cfg.getConfigurationSection("clans");
        if (cs != null) {
            for (String name : cs.getKeys(false)) {
                String path = "clans." + name;
                String ls = cfg.getString(path + ".leader");
                if (ls == null) continue;
                try {
                    Clan clan = new Clan(name, UUID.fromString(ls));
                    for (String mm : cfg.getStringList(path + ".members")) clan.addMember(UUID.fromString(mm));
                    for (String oo : cfg.getStringList(path + ".officers")) clan.getOfficers().add(UUID.fromString(oo));
                    clan.setPvp(cfg.getBoolean(path + ".pvp", false));
                    for (String hh : cfg.getStringList(path + ".homeAccess")) {
                        try { clan.setHomeAccess(UUID.fromString(hh), true); } catch (IllegalArgumentException ignored) {}
                    }
                    if (cfg.isConfigurationSection(path + ".home")) {
                        String hw = cfg.getString(path + ".home.world");
                        if (hw != null) clan.setHome(hw,
                                cfg.getDouble(path + ".home.x"),
                                cfg.getDouble(path + ".home.y"),
                                cfg.getDouble(path + ".home.z"),
                                (float) cfg.getDouble(path + ".home.yaw"),
                                (float) cfg.getDouble(path + ".home.pitch"));
                    }
                    clans.put(name.toLowerCase(Locale.ROOT), clan);
                    for (UUID u : clan.getMembers()) playerClan.put(u, name.toLowerCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    getLogger().warning("[ClanPlugin] clan invalide ignore : " + name);
                }
            }
        }
        ConfigurationSection ns = cfg.getConfigurationSection("names");
        if (ns != null) for (String k : ns.getKeys(false)) {
            try { names.put(UUID.fromString(k), ns.getString(k)); } catch (IllegalArgumentException ignored) {}
        }
    }

    private void save() {
        if (file == null) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Clan clan : clans.values()) {
            String path = "clans." + clan.getName();
            cfg.set(path + ".leader", clan.getLeader().toString());
            List<String> mem = new ArrayList<>();
            for (UUID u : clan.getMembers()) mem.add(u.toString());
            cfg.set(path + ".members", mem);
            List<String> off = new ArrayList<>();
            for (UUID u : clan.getOfficers()) off.add(u.toString());
            cfg.set(path + ".officers", off);
            cfg.set(path + ".pvp", clan.isPvp());
            List<String> ha = new ArrayList<>();
            for (UUID u : clan.getHomeAccess()) ha.add(u.toString());
            cfg.set(path + ".homeAccess", ha);
            if (clan.hasHome()) {
                cfg.set(path + ".home.world", clan.getHomeWorld());
                cfg.set(path + ".home.x", clan.getHomeX());
                cfg.set(path + ".home.y", clan.getHomeY());
                cfg.set(path + ".home.z", clan.getHomeZ());
                cfg.set(path + ".home.yaw", (double) clan.getHomeYaw());
                cfg.set(path + ".home.pitch", (double) clan.getHomePitch());
            }
        }
        for (Map.Entry<UUID, String> e : names.entrySet()) cfg.set("names." + e.getKey(), e.getValue());
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            cfg.save(file);
        } catch (IOException e) {
            getLogger().warning("[ClanPlugin] sauvegarde KO : " + e.getMessage());
        }
    }

    // ===================== CLASSES INTERNES =====================

    static class Clan {
        enum Role { LEADER, OFFICER, MEMBER }
        private final String name;
        private final UUID leader;
        private final Set<UUID> members = new LinkedHashSet<>();
        private final Set<UUID> officers = new LinkedHashSet<>();
        private final Set<UUID> homeAccess = new LinkedHashSet<>(); // membres (hors chef) autorises a /clan home
        private boolean pvp = false; // PvP entre membres desactive par defaut
        // Home du clan (le chef y a toujours acces)
        private boolean hasHome = false;
        private String homeWorld;
        private double homeX, homeY, homeZ;
        private float homeYaw, homePitch;
        Clan(String name, UUID leader) { this.name = name; this.leader = leader; this.members.add(leader); }
        String getName() { return name; }
        UUID getLeader() { return leader; }
        Set<UUID> getMembers() { return members; }
        Set<UUID> getOfficers() { return officers; }
        int size() { return members.size(); }
        boolean isMember(UUID u) { return members.contains(u); }
        boolean isLeader(UUID u) { return leader.equals(u); }
        boolean isOfficer(UUID u) { return officers.contains(u); }
        boolean isPvp() { return pvp; }
        void setPvp(boolean pvp) { this.pvp = pvp; }
        Role getRole(UUID u) {
            if (leader.equals(u)) return Role.LEADER;
            if (officers.contains(u)) return Role.OFFICER;
            if (members.contains(u)) return Role.MEMBER;
            return null;
        }
        void addMember(UUID u) { members.add(u); }
        void removeMember(UUID u) { members.remove(u); officers.remove(u); homeAccess.remove(u); }
        void promote(UUID u) { if (members.contains(u) && !leader.equals(u)) officers.add(u); }
        void demote(UUID u) { officers.remove(u); }
        boolean canInvite(UUID u) { return isLeader(u) || isOfficer(u); }

        // ---- Home ----
        boolean hasHome() { return hasHome; }
        void setHome(String world, double x, double y, double z, float yaw, float pitch) {
            if (world == null) return;
            this.homeWorld = world; this.homeX = x; this.homeY = y; this.homeZ = z;
            this.homeYaw = yaw; this.homePitch = pitch; this.hasHome = true;
        }
        void clearHome() {
            this.hasHome = false; this.homeWorld = null;
            this.homeX = this.homeY = this.homeZ = 0; this.homeYaw = this.homePitch = 0f;
        }
        Location getHome() {
            if (!hasHome) return null;
            World w = Bukkit.getWorld(homeWorld);
            if (w == null) return null;
            return new Location(w, homeX, homeY, homeZ, homeYaw, homePitch);
        }
        String getHomeWorld() { return homeWorld; }
        double getHomeX() { return homeX; }
        double getHomeY() { return homeY; }
        double getHomeZ() { return homeZ; }
        float getHomeYaw() { return homeYaw; }
        float getHomePitch() { return homePitch; }

        // ---- Acces au home ----
        Set<UUID> getHomeAccess() { return homeAccess; }
        boolean canUseHome(UUID u) { return isLeader(u) || homeAccess.contains(u); }
        void setHomeAccess(UUID u, boolean allow) {
            if (allow) homeAccess.add(u); else homeAccess.remove(u);
        }
    }

    static class ClanMenuHolder implements InventoryHolder {
        final String clanName;
        final Map<Integer, UUID> slotToMember = new HashMap<>();
        Inventory inv;
        ClanMenuHolder(String clanName) { this.clanName = clanName; }
        @Override public Inventory getInventory() { return inv; }
    }

    static class MemberMenuHolder implements InventoryHolder {
        final String clanName;
        final UUID target;
        Inventory inv;
        MemberMenuHolder(String clanName, UUID target) { this.clanName = clanName; this.target = target; }
        @Override public Inventory getInventory() { return inv; }
    }
}
