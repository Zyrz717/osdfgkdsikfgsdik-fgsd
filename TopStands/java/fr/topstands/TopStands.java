package fr.topstands;

import com.destroystokyo.paper.profile.PlayerProfile;
import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TopStands - classement d'argent sur des armor stands.
 *
 * Chaque armor stand lie a un rang recoit la tete du joueur classe a ce rang,
 * et un texte flottant au-dessus affiche "#rang Pseudo" et son solde.
 * Le solde vient de l'economie Vault (EssentialsX sur ce serveur).
 *
 * Commandes (OP, ou zyrx717 / Gusto77) :
 *   /topstands set <rang>     ajoute l'armor stand le plus proche (4 blocs) au rang
 *                             (un meme rang peut avoir plusieurs armor stands)
 *   /topstands remove         delie l'armor stand le plus proche
 *   /topstands remove <rang>  delie tous les armor stands du rang
 *   /topstands list           liste les rangs lies
 *   /topstands update         recalcule tout de suite (scan complet)
 *
 * Mise a jour :
 *   - instantanee : des qu'un solde change (evenement EssentialsX
 *     UserBalanceUpdateEvent, accroche par reflexion), a la connexion et a
 *     la deconnexion d'un joueur ;
 *   - toutes les secondes : relecture des soldes des joueurs en ligne
 *     (lecture memoire, quasi gratuite) - filet de securite si l'evenement
 *     n'est pas disponible ;
 *   - toutes les 5 minutes : scan complet de tous les joueurs (hors ligne
 *     inclus) pour rester coherent avec /baltop.
 *   Les textes ne sont reecrits que si le classement affiche change.
 */
public class TopStands extends LoadedPlugin implements Listener {

    private static final String[] STAFF = {"zyrx717", "Gusto77"};
    private static final long FULL_REFRESH_TICKS = 20L * 60L * 5L; // 5 minutes : scan complet
    private static final long FAST_TICKS = 20L;                     // 1 seconde : joueurs en ligne
    private static final double SEARCH_RADIUS = 4.0;
    private static final double TEXT_HEIGHT_NORMAL = 2.45;
    private static final double TEXT_HEIGHT_SMALL = 1.35;
    private static final String FILE_NAME = "topstands.yml";
    private static final String ESS_EVENT = "net.ess3.api.events.UserBalanceUpdateEvent";

    private final NamespacedKey rankKey = new NamespacedKey("topstands", "rank");

    private Plugin host;
    private BukkitTask fullTimer;
    private BukkitTask fastTimer;
    private File file;
    private YamlConfiguration cfg;

    private final Map<UUID, PlayerProfile> profileCache = new HashMap<UUID, PlayerProfile>();

    private Object economy;        // net.milkbowl.vault.economy.Economy (reflexion)
    private Method getBalanceMethod;

    // --- cache des soldes -------------------------------------------------
    /** Dernier solde connu de chaque joueur (en ligne ou non). */
    private final Map<UUID, Double> balances = new HashMap<UUID, Double>();
    /** Pseudo connu de chaque joueur (peut etre alimente depuis un autre thread). */
    private final Map<UUID, String> names = new ConcurrentHashMap<UUID, String>();
    /** Joueurs dont le solde doit etre relu au prochain tick (evenements). */
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean applyScheduled = new AtomicBoolean(false);
    private volatile boolean forceNextApply = false;
    private boolean snapshotReady = false;
    private boolean essentialsEventHooked = false;
    /** Signature du dernier classement applique (rang -> joueur + solde affiche). */
    private String lastSignature = null;
    /** Chunks contenant au moins un armor stand lie ("monde:cx:cz"). */
    private final Set<String> standChunks = new HashSet<String>();

    @Override
    public void onEnable() {
        host = Bukkit.getPluginManager().getPlugin("PluginLoader");
        File folder = host != null ? host.getDataFolder() : new File("plugins/PluginLoader");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        file = new File(folder, FILE_NAME);
        cfg = YamlConfiguration.loadConfiguration(file);
        rebuildStandChunks();

        hookEconomy();

        unregisterStaleListeners();
        registerListener(this);
        hookBalanceEvent();

        registerCommand("topstands", (sender, cmd, label, args) -> {
            handleCommand(sender, args);
            return true;
        });

        if (host != null) {
            // Scan complet (tous les joueurs) : au demarrage puis toutes les 5 minutes.
            fullTimer = Bukkit.getScheduler().runTaskTimer(host, new Runnable() {
                @Override
                public void run() {
                    fullRefresh(null);
                }
            }, 100L, FULL_REFRESH_TICKS);
            // Suivi rapide : soldes des joueurs en ligne + joueurs en attente, chaque seconde.
            fastTimer = Bukkit.getScheduler().runTaskTimer(host, new Runnable() {
                @Override
                public void run() {
                    fastTick();
                }
            }, 120L, FAST_TICKS);
        }
        getLogger().info("[TopStands] actif - " + boundRanks().size() + " rang(s) lie(s)"
                + (economy == null ? " (economie Vault introuvable !)" : "")
                + (essentialsEventHooked ? " - maj instantanee via EssentialsX" : " - maj par sondage (1 s)"));
    }

    @Override
    public void onDisable() {
        if (fullTimer != null) {
            fullTimer.cancel();
            fullTimer = null;
        }
        if (fastTimer != null) {
            fastTimer.cancel();
            fastTimer = null;
        }
        try {
            HandlerList.unregisterAll(this);
        } catch (Throwable ignored) {
        }
    }

    /** Apres un /plreload, retire les listeners d'une ancienne instance de cette classe. */
    private void unregisterStaleListeners() {
        try {
            String myClass = getClass().getName();
            for (HandlerList hl : HandlerList.getHandlerLists()) {
                for (RegisteredListener rl : hl.getRegisteredListeners()) {
                    Object l = rl.getListener();
                    if (l != null && l != this && l.getClass().getName().equals(myClass)) {
                        try {
                            hl.unregister(rl);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ eco

    private void hookEconomy() {
        economy = null;
        getBalanceMethod = null;
        try {
            Class<?> ecoClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(ecoClass);
            if (rsp == null) {
                getLogger().warning("[TopStands] Aucune economie enregistree dans Vault.");
                return;
            }
            economy = rsp.getProvider();
            getBalanceMethod = ecoClass.getMethod("getBalance", OfflinePlayer.class);
        } catch (Throwable t) {
            getLogger().warning("[TopStands] Vault indisponible : " + t);
        }
    }

    private double balanceOf(OfflinePlayer p) {
        if (economy == null || getBalanceMethod == null) {
            return 0.0;
        }
        try {
            Object v = getBalanceMethod.invoke(economy, p);
            return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /**
     * Accroche l'evenement EssentialsX UserBalanceUpdateEvent par reflexion
     * (la classe n'est pas forcement disponible a la compilation).
     * Des qu'un solde change, le joueur est marque "a relire" et le
     * classement est recalcule au tick suivant.
     */
    @SuppressWarnings("unchecked")
    private void hookBalanceEvent() {
        essentialsEventHooked = false;
        if (host == null) {
            return;
        }
        try {
            Class<?> evClass = null;
            try {
                evClass = Class.forName(ESS_EVENT);
            } catch (Throwable ignored) {
                Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
                if (ess != null) {
                    evClass = ess.getClass().getClassLoader().loadClass(ESS_EVENT);
                }
            }
            if (evClass == null || !Event.class.isAssignableFrom(evClass)) {
                getLogger().info("[TopStands] UserBalanceUpdateEvent introuvable, mise a jour par sondage uniquement.");
                return;
            }
            final Class<?> eventClass = evClass;
            final Method getPlayer = evClass.getMethod("getPlayer");
            EventExecutor executor = new EventExecutor() {
                @Override
                public void execute(Listener listener, Event event) {
                    if (!eventClass.isInstance(event)) {
                        return;
                    }
                    try {
                        Object p = getPlayer.invoke(event);
                        if (p instanceof OfflinePlayer) {
                            OfflinePlayer op = (OfflinePlayer) p;
                            markPending(op.getUniqueId(), op.getName());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) evClass, this,
                    EventPriority.MONITOR, executor, host, false);
            essentialsEventHooked = true;
        } catch (Throwable t) {
            getLogger().info("[TopStands] Evenement EssentialsX indisponible (" + t + "), mise a jour par sondage uniquement.");
        }
    }

    // --------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        markPending(e.getPlayer().getUniqueId(), e.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        markPending(e.getPlayer().getUniqueId(), e.getPlayer().getName());
    }

    /** Quand le chunk d'un armor stand (re)charge ses entites, on reapplique le texte. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        if (standChunks.isEmpty()) {
            return;
        }
        Chunk c = e.getChunk();
        if (standChunks.contains(chunkKey(c.getWorld().getName(), c.getX(), c.getZ()))) {
            forceNextApply = true;
            scheduleApply();
        }
    }

    /** Peut etre appele depuis n'importe quel thread. */
    private void markPending(UUID uuid, String name) {
        if (uuid == null) {
            return;
        }
        if (name != null) {
            names.put(uuid, name);
        }
        pending.add(uuid);
        scheduleApply();
    }

    /** Planifie un passage de fastTick() au prochain tick (une seule fois a la fois). */
    private void scheduleApply() {
        if (host == null) {
            return;
        }
        if (applyScheduled.compareAndSet(false, true)) {
            try {
                Bukkit.getScheduler().runTask(host, new Runnable() {
                    @Override
                    public void run() {
                        applyScheduled.set(false);
                        fastTick();
                    }
                });
            } catch (Throwable t) {
                applyScheduled.set(false);
            }
        }
    }

    // ------------------------------------------------------------- command

    private boolean allowed(CommandSender sender) {
        if (sender.isOp()) {
            return true;
        }
        for (String s : STAFF) {
            if (s.equalsIgnoreCase(sender.getName())) {
                return true;
            }
        }
        return false;
    }

    private void handleCommand(CommandSender sender, String[] args) {
        if (!allowed(sender)) {
            sender.sendMessage(Component.text("Demande à zyrx717", NamedTextColor.RED));
            return;
        }
        if (args.length == 0) {
            help(sender);
            return;
        }
        String sub = args[0].toLowerCase();

        if (sub.equals("set") || sub.equals("add")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Component.text("Commande en jeu uniquement.", NamedTextColor.RED));
                return;
            }
            int rank = parseRank(sender, args);
            if (rank < 1) {
                return;
            }
            Player p = (Player) sender;
            ArmorStand stand = nearestStand(p);
            if (stand == null) {
                sender.sendMessage(Component.text("Aucun armor stand a moins de 4 blocs de toi.", NamedTextColor.RED));
                return;
            }
            // si ce stand etait deja lie (a ce rang ou un autre), on le delie d'abord
            unbindStand(stand.getUniqueId().toString());

            Location l = stand.getLocation();
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("stand", stand.getUniqueId().toString());
            entry.put("world", l.getWorld().getName());
            entry.put("x", l.getX());
            entry.put("y", l.getY());
            entry.put("z", l.getZ());
            List<Map<String, Object>> list = entriesOf(rank);
            list.add(entry);
            writeEntries(rank, list);
            save();
            rebuildStandChunks();
            sender.sendMessage(Component.text("Armor stand ajoute au rang #" + rank + " (" + list.size() + " au total pour ce rang).", NamedTextColor.GREEN));
            if (snapshotReady) {
                apply(sender, true);
            } else {
                fullRefresh(sender);
            }
            return;
        }

        if (sub.equals("remove") || sub.equals("del") || sub.equals("delete") || sub.equals("unset")) {
            if (args.length < 2) {
                // sans rang : on delie l'armor stand le plus proche
                if (!(sender instanceof Player)) {
                    sender.sendMessage(Component.text("Depuis la console, precise un rang : /topstands remove <rang>", NamedTextColor.RED));
                    return;
                }
                ArmorStand stand = nearestStand((Player) sender);
                if (stand == null) {
                    sender.sendMessage(Component.text("Aucun armor stand a moins de 4 blocs de toi.", NamedTextColor.RED));
                    return;
                }
                int r = unbindStand(stand.getUniqueId().toString());
                if (r < 0) {
                    sender.sendMessage(Component.text("Cet armor stand n'est lie a aucun rang.", NamedTextColor.RED));
                    return;
                }
                save();
                rebuildStandChunks();
                lastSignature = null;
                sender.sendMessage(Component.text("Armor stand retire du rang #" + r + ".", NamedTextColor.GREEN));
                return;
            }
            int rank = parseRank(sender, args);
            if (rank < 1) {
                return;
            }
            List<Map<String, Object>> list = entriesOf(rank);
            if (list.isEmpty()) {
                sender.sendMessage(Component.text("Le rang #" + rank + " n'est lie a rien.", NamedTextColor.RED));
                return;
            }
            int n = list.size();
            removeRank(rank);
            save();
            rebuildStandChunks();
            lastSignature = null;
            sender.sendMessage(Component.text("Rang #" + rank + " delie (" + n + " armor stand(s)).", NamedTextColor.GREEN));
            return;
        }

        if (sub.equals("list")) {
            List<Integer> ranks = boundRanks();
            if (ranks.isEmpty()) {
                sender.sendMessage(Component.text("Aucun rang lie. Utilise /topstands set <rang> pres d'un armor stand.", NamedTextColor.YELLOW));
                return;
            }
            sender.sendMessage(Component.text("Rangs lies :", NamedTextColor.GOLD));
            for (Integer r : ranks) {
                for (Map<String, Object> e : entriesOf(r)) {
                    String where = e.get("world") + " "
                            + (int) toDouble(e.get("x")) + " "
                            + (int) toDouble(e.get("y")) + " "
                            + (int) toDouble(e.get("z"));
                    boolean alive = false;
                    try {
                        alive = Bukkit.getEntity(UUID.fromString(String.valueOf(e.get("stand")))) != null;
                    } catch (Throwable ignored) {
                    }
                    sender.sendMessage(Component.text(" #" + r + "  ", NamedTextColor.YELLOW)
                            .append(Component.text(where, NamedTextColor.WHITE))
                            .append(Component.text(alive ? "  (ok)" : "  (armor stand introuvable / chunk non charge)", alive ? NamedTextColor.GREEN : NamedTextColor.RED)));
                }
            }
            sender.sendMessage(Component.text("Mise a jour : "
                    + (essentialsEventHooked ? "instantanee (EssentialsX)" : "sondage toutes les secondes")
                    + ", scan complet toutes les 5 min, " + balances.size() + " joueur(s) en cache.", NamedTextColor.GRAY));
            return;
        }

        if (sub.equals("update") || sub.equals("refresh")) {
            hookEconomy();
            fullRefresh(sender);
            return;
        }

        help(sender);
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text("TopStands", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        sender.sendMessage(Component.text("/topstands set <rang>", NamedTextColor.YELLOW).append(Component.text("  ajoute l'armor stand le plus proche au rang", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/topstands remove", NamedTextColor.YELLOW).append(Component.text("  delie l'armor stand le plus proche", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/topstands remove <rang>", NamedTextColor.YELLOW).append(Component.text("  delie tous les stands du rang", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/topstands list", NamedTextColor.YELLOW).append(Component.text("  liste les rangs", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/topstands update", NamedTextColor.YELLOW).append(Component.text("  recalcule maintenant (scan complet)", NamedTextColor.GRAY)));
    }

    private int parseRank(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Indique un rang : /topstands " + args[0] + " <rang>", NamedTextColor.RED));
            return -1;
        }
        try {
            int r = Integer.parseInt(args[1]);
            if (r < 1 || r > 100) {
                sender.sendMessage(Component.text("Le rang doit etre entre 1 et 100.", NamedTextColor.RED));
                return -1;
            }
            return r;
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Rang invalide : " + args[1], NamedTextColor.RED));
            return -1;
        }
    }

    private ArmorStand nearestStand(Player p) {
        ArmorStand best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : p.getNearbyEntities(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS)) {
            if (!(e instanceof ArmorStand)) {
                continue;
            }
            double d = e.getLocation().distanceSquared(p.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = (ArmorStand) e;
            }
        }
        return best;
    }

    // -------------------------------------------------------------- storage

    private List<Integer> boundRanks() {
        List<Integer> out = new ArrayList<Integer>();
        ConfigurationSection sec = cfg.getConfigurationSection("stands");
        if (sec == null) {
            return out;
        }
        for (String k : sec.getKeys(false)) {
            try {
                out.add(Integer.parseInt(k));
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(out);
        return out;
    }

    private void save() {
        try {
            cfg.save(file);
        } catch (Throwable t) {
            getLogger().warning("[TopStands] Sauvegarde impossible : " + t);
        }
    }

    private static double toDouble(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    private static String chunkKey(String world, int cx, int cz) {
        return world + ":" + cx + ":" + cz;
    }

    /** Recalcule la liste des chunks contenant un armor stand lie. */
    private void rebuildStandChunks() {
        standChunks.clear();
        for (Integer r : boundRanks()) {
            for (Map<String, Object> e : entriesOf(r)) {
                Object w = e.get("world");
                if (w == null) {
                    continue;
                }
                int cx = (int) Math.floor(toDouble(e.get("x"))) >> 4;
                int cz = (int) Math.floor(toDouble(e.get("z"))) >> 4;
                standChunks.add(chunkKey(String.valueOf(w), cx, cz));
            }
        }
    }

    /** Toutes les entrees (armor stands) d'un rang. Migre l'ancien format mono-stand si besoin. */
    private List<Map<String, Object>> entriesOf(int rank) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String path = "stands." + rank;
        if (cfg.isConfigurationSection(path)) {
            // ancien format : une seule entree sous forme de section
            ConfigurationSection sec = cfg.getConfigurationSection(path);
            Map<String, Object> e = new LinkedHashMap<String, Object>();
            for (String k : sec.getKeys(false)) {
                e.put(k, sec.get(k));
            }
            if (e.containsKey("stand")) {
                out.add(e);
            }
            return out;
        }
        for (Map<?, ?> m : cfg.getMapList(path)) {
            Map<String, Object> e = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> en : m.entrySet()) {
                e.put(String.valueOf(en.getKey()), en.getValue());
            }
            if (e.containsKey("stand")) {
                out.add(e);
            }
        }
        return out;
    }

    private void writeEntries(int rank, List<Map<String, Object>> list) {
        if (list.isEmpty()) {
            cfg.set("stands." + rank, null);
        } else {
            cfg.set("stands." + rank, list);
        }
    }

    /** Retire le texte et la tete d'une entree. */
    private void cleanupEntry(Map<String, Object> e) {
        Object disp = e.get("display");
        if (disp != null) {
            try {
                Entity ent = Bukkit.getEntity(UUID.fromString(String.valueOf(disp)));
                if (ent != null) {
                    ent.remove();
                }
            } catch (Throwable ignored) {
            }
        }
        Object st = e.get("stand");
        if (st != null) {
            try {
                Entity ent = Bukkit.getEntity(UUID.fromString(String.valueOf(st)));
                if (ent instanceof ArmorStand) {
                    ((ArmorStand) ent).getEquipment().setHelmet(null);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Delie un armor stand (par UUID) de son rang. Renvoie le rang, ou -1 s'il n'etait pas lie. */
    private int unbindStand(String standId) {
        int found = -1;
        for (Integer r : boundRanks()) {
            List<Map<String, Object>> list = entriesOf(r);
            List<Map<String, Object>> keep = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> e : list) {
                if (standId.equals(String.valueOf(e.get("stand")))) {
                    cleanupEntry(e);
                    found = r;
                } else {
                    keep.add(e);
                }
            }
            if (keep.size() != list.size()) {
                writeEntries(r, keep);
            }
        }
        return found;
    }

    private void removeRank(int rank) {
        for (Map<String, Object> e : entriesOf(rank)) {
            cleanupEntry(e);
        }
        cfg.set("stands." + rank, null);
    }

    // ------------------------------------------------------------- balances

    /** Met a jour le cache pour un joueur. Renvoie true si le solde a change. */
    private boolean updateBalance(OfflinePlayer op) {
        if (op == null) {
            return false;
        }
        UUID id = op.getUniqueId();
        String name = op.getName();
        if (name != null) {
            names.put(id, name);
        }
        double bal = balanceOf(op);
        Double old = balances.put(id, bal);
        return old == null || old.doubleValue() != bal;
    }

    /**
     * Scan complet : relit le solde de tous les joueurs connus du serveur
     * (hors ligne inclus) puis reapplique le classement, meme s'il n'a pas change.
     */
    private void fullRefresh(CommandSender feedback) {
        if (economy == null) {
            hookEconomy();
        }
        Map<UUID, Double> fresh = new HashMap<UUID, Double>();

        // 1) Source principale : la liste des comptes d'EssentialsX, exactement
        //    comme /baltop. CORRECTIF : avant, seul Bukkit.getOfflinePlayers() etait
        //    utilise, et un joueur sans nom dans le cache du serveur (usercache.json,
        //    frequent avec AuthMe / FastLogin / Floodgate) etait ignore -> des rangs
        //    affiches "Libre" alors que /baltop montrait bien un joueur.
        Map<UUID, String> essUsers = essentialsUsers();
        for (Map.Entry<UUID, String> en : essUsers.entrySet()) {
            names.put(en.getKey(), en.getValue());
            fresh.put(en.getKey(), balanceOf(Bukkit.getOfflinePlayer(en.getKey())));
        }

        // 2) Complement : les joueurs connus de Bukkit (nom dans le cache serveur).
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op == null) {
                continue;
            }
            String name = op.getName() != null ? op.getName() : names.get(op.getUniqueId());
            if (name == null) {
                continue;
            }
            names.put(op.getUniqueId(), name);
            if (!fresh.containsKey(op.getUniqueId())) {
                fresh.put(op.getUniqueId(), balanceOf(op));
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.put(p.getUniqueId(), p.getName());
            fresh.put(p.getUniqueId(), balanceOf(p));
        }
        balances.clear();
        balances.putAll(fresh);
        pending.clear();
        snapshotReady = true;
        apply(feedback, true);
    }

    /**
     * Tous les comptes connus d'EssentialsX (uuid -> dernier pseudo), par reflexion
     * pour ne pas dependre d'EssentialsX a la compilation. Compatible avec
     * Essentials#getUsers().getAllUserUUIDs() (2.20+) et l'ancien
     * Essentials#getUserMap().getAllUniqueUsers().
     */
    private Map<UUID, String> essentialsUsers() {
        Map<UUID, String> out = new HashMap<UUID, String>();
        try {
            Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
            if (ess == null) {
                return out;
            }
            Object users = null;
            try {
                users = ess.getClass().getMethod("getUsers").invoke(ess);
            } catch (NoSuchMethodException e) {
                users = ess.getClass().getMethod("getUserMap").invoke(ess);
            }
            if (users == null) {
                return out;
            }
            Object ids = null;
            for (String m : new String[]{"getAllUserUUIDs", "getAllUniqueUsers"}) {
                try {
                    ids = users.getClass().getMethod(m).invoke(users);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (!(ids instanceof Iterable)) {
                return out;
            }
            Method getUser = ess.getClass().getMethod("getUser", UUID.class);
            for (Object o : (Iterable<?>) ids) {
                if (!(o instanceof UUID)) {
                    continue;
                }
                UUID id = (UUID) o;
                String name = null;
                try {
                    Object user = getUser.invoke(ess, id);
                    if (user != null) {
                        // Comptes "NPC" d'EssentialsX (boutiques, panneaux) : exclus comme dans /baltop.
                        try {
                            Object npc = user.getClass().getMethod("isNPC").invoke(user);
                            if (Boolean.TRUE.equals(npc)) {
                                continue;
                            }
                        } catch (NoSuchMethodException ignored) {
                        }
                        for (String m : new String[]{"getLastAccountName", "getName"}) {
                            try {
                                Object n = user.getClass().getMethod(m).invoke(user);
                                if (n != null && !n.toString().isEmpty()) {
                                    name = n.toString();
                                    break;
                                }
                            } catch (NoSuchMethodException ignored) {
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (name != null) {
                    out.put(id, name);
                }
            }
        } catch (Throwable t) {
            getLogger().info("[TopStands] liste des comptes EssentialsX indisponible (" + t + "), repli sur Bukkit.");
        }
        return out;
    }

    /**
     * Passage rapide (chaque seconde + apres chaque evenement) : relit les
     * soldes des joueurs en ligne et des joueurs en attente, puis reapplique
     * le classement uniquement s'il a change.
     */
    private void fastTick() {
        if (!snapshotReady) {
            return; // le premier scan complet n'a pas encore eu lieu
        }
        if (economy == null) {
            hookEconomy();
            if (economy == null) {
                return;
            }
        }
        boolean changed = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (updateBalance(p)) {
                changed = true;
            }
        }
        if (!pending.isEmpty()) {
            List<UUID> ids = new ArrayList<UUID>(pending);
            pending.removeAll(ids);
            for (UUID id : ids) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(id);
                if (op.getName() == null && !names.containsKey(id)) {
                    continue; // joueur inconnu, rien a afficher
                }
                if (!op.isOnline() && !op.hasPlayedBefore() && !names.containsKey(id)) {
                    // pas un vrai joueur (ex : compte "NPC" d'EssentialsX) -> on l'ignore
                    balances.remove(id);
                    continue;
                }
                if (updateBalance(op)) {
                    changed = true;
                }
            }
        }
        boolean force = forceNextApply;
        forceNextApply = false;
        if (changed || force) {
            apply(null, force);
        }
    }

    // -------------------------------------------------------------- apply

    /**
     * Calcule le classement a partir du cache et met a jour stands + textes
     * (thread principal). Si force est faux, ne reecrit rien quand l'affichage
     * n'a pas change depuis la derniere fois.
     */
    private void apply(final CommandSender feedback, boolean force) {
        final List<Integer> ranks = boundRanks();
        if (ranks.isEmpty()) {
            if (feedback != null) {
                feedback.sendMessage(Component.text("Aucun rang lie, rien a mettre a jour.", NamedTextColor.YELLOW));
            }
            return;
        }

        // 1) classement
        List<Map.Entry<UUID, Double>> top = new ArrayList<Map.Entry<UUID, Double>>();
        for (Map.Entry<UUID, Double> en : balances.entrySet()) {
            if (en.getValue() == null || en.getValue() <= 0.0) {
                continue;
            }
            if (!names.containsKey(en.getKey())) {
                continue;
            }
            top.add(en);
        }
        Collections.sort(top, (a, b) -> {
            int c = Double.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });

        // 2) signature de ce qui serait affiche
        StringBuilder sig = new StringBuilder();
        for (Integer rank : ranks) {
            sig.append(rank).append('=');
            if (rank <= top.size()) {
                Map.Entry<UUID, Double> e = top.get(rank - 1);
                sig.append(e.getKey()).append('/').append(names.get(e.getKey())).append('/').append(formatMoney(e.getValue()));
            } else {
                sig.append("libre");
            }
            sig.append(';');
        }
        String signature = sig.toString();
        if (!force && signature.equals(lastSignature)) {
            return;
        }

        // 3) application
        int applied = 0;
        int total = 0;
        for (Integer rank : ranks) {
            UUID id = rank <= top.size() ? top.get(rank - 1).getKey() : null;
            double bal = rank <= top.size() ? top.get(rank - 1).getValue() : 0.0;
            OfflinePlayer op = id != null ? Bukkit.getOfflinePlayer(id) : null;
            String name = id != null ? names.get(id) : null;
            total += entriesOf(rank).size();
            applied += applyRank(rank, op, name, bal);
        }
        // Si des stands n'etaient pas charges, on retentera des que leur chunk revient (EntitiesLoadEvent).
        lastSignature = signature;
        save();
        if (feedback != null) {
            feedback.sendMessage(Component.text("Classement mis a jour : " + applied + "/" + total + " armor stand(s) sur "
                    + ranks.size() + " rang(s), " + top.size() + " joueur(s) classes.", NamedTextColor.GREEN));
        }
    }

    /** Applique le classement a tous les armor stands du rang. Renvoie le nombre de stands mis a jour. */
    private int applyRank(int rank, OfflinePlayer op, String name, double bal) {
        List<Map<String, Object>> list = entriesOf(rank);
        int done = 0;
        boolean changed = false;
        ItemStack head = op != null ? headOf(op, name) : null;
        Component text = buildText(rank, name, bal);
        for (Map<String, Object> e : list) {
            Entity standEnt = null;
            try {
                standEnt = Bukkit.getEntity(UUID.fromString(String.valueOf(e.get("stand"))));
            } catch (Throwable ignored) {
            }
            if (!(standEnt instanceof ArmorStand)) {
                continue; // chunk non charge ou stand detruit
            }
            ArmorStand stand = (ArmorStand) standEnt;
            stand.getEquipment().setHelmet(head == null ? null : head.clone());

            TextDisplay td = null;
            Object dispId = e.get("display");
            if (dispId != null) {
                try {
                    Entity d = Bukkit.getEntity(UUID.fromString(String.valueOf(dispId)));
                    if (d instanceof TextDisplay) {
                        td = (TextDisplay) d;
                    }
                } catch (Throwable ignored) {
                }
            }
            Location target = stand.getLocation().clone().add(0.0, stand.isSmall() ? TEXT_HEIGHT_SMALL : TEXT_HEIGHT_NORMAL, 0.0);
            if (td == null) {
                td = spawnDisplay(target, rank);
                e.put("display", td.getUniqueId().toString());
                changed = true;
            } else if (td.getLocation().distanceSquared(target) > 0.01) {
                td.teleport(target);
            }
            td.text(text);
            done++;
        }
        if (changed) {
            writeEntries(rank, list);
        }
        return done;
    }

    private TextDisplay spawnDisplay(Location loc, int rank) {
        World w = loc.getWorld();
        TextDisplay td = w.spawn(loc, TextDisplay.class);
        td.setBillboard(Display.Billboard.CENTER);
        td.setAlignment(TextDisplay.TextAlignment.CENTER);
        td.setBackgroundColor(Color.fromARGB(190, 17, 19, 26));
        td.setSeeThrough(false);
        td.setShadowed(true);
        td.setLineWidth(220);
        td.setViewRange(1.0f);
        td.setPersistent(true);
        td.getPersistentDataContainer().set(rankKey, PersistentDataType.INTEGER, rank);
        return td;
    }

    private Component buildText(int rank, String name, double bal) {
        TextColor rankColor;
        String medal;
        if (rank == 1) {
            rankColor = TextColor.color(0xFFD700);
            medal = "★ ";
        } else if (rank == 2) {
            rankColor = TextColor.color(0xC0C0C0);
            medal = "★ ";
        } else if (rank == 3) {
            rankColor = TextColor.color(0xCD7F32);
            medal = "★ ";
        } else {
            rankColor = NamedTextColor.WHITE;
            medal = "";
        }
        if (name == null) {
            return Component.text(medal + "#" + rank, rankColor).decorate(TextDecoration.BOLD)
                    .append(Component.newline())
                    .append(Component.text("Libre", NamedTextColor.DARK_GRAY));
        }
        return Component.text(medal + "#" + rank + "  ", rankColor).decorate(TextDecoration.BOLD)
                .append(Component.text(name, NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text(formatMoney(bal) + " $", NamedTextColor.GREEN));
    }

    private static String formatMoney(double v) {
        String s = String.format("%,.0f", v);
        return s.replace(',', ' ').replace(' ', ' ').replace(' ', ' ');
    }

    private ItemStack headOf(OfflinePlayer op, String name) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            boolean done = false;
            try {
                PlayerProfile profile = profileCache.get(op.getUniqueId());
                if (profile == null) {
                    profile = Bukkit.createProfile(op.getUniqueId(), name != null ? name : op.getName());
                    if (!profile.hasTextures()) {
                        profile.complete(true); // une seule fois par joueur, ensuite en cache
                    }
                    profileCache.put(op.getUniqueId(), profile);
                }
                meta.setPlayerProfile(profile);
                done = true;
            } catch (Throwable ignored) {
            }
            if (!done) {
                try {
                    meta.setOwningPlayer(op);
                } catch (Throwable ignored) {
                }
            }
            head.setItemMeta(meta);
        }
        return head;
    }
}
