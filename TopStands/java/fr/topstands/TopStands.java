package fr.topstands;

import com.destroystokyo.paper.profile.PlayerProfile;
import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 *   /topstands update         recalcule tout de suite
 *
 * Mise a jour automatique toutes les 5 minutes.
 */
public class TopStands extends LoadedPlugin {

    private static final String[] STAFF = {"zyrx717", "Gusto77"};
    private static final long REFRESH_TICKS = 20L * 60L * 5L; // 5 minutes
    private static final double SEARCH_RADIUS = 4.0;
    private static final double TEXT_HEIGHT_NORMAL = 2.45;
    private static final double TEXT_HEIGHT_SMALL = 1.35;
    private static final String FILE_NAME = "topstands.yml";

    private final NamespacedKey rankKey = new NamespacedKey("topstands", "rank");

    private BukkitTask timer;
    private File file;
    private YamlConfiguration cfg;

    private final Map<UUID, PlayerProfile> profileCache = new HashMap<UUID, PlayerProfile>();

    private Object economy;        // net.milkbowl.vault.economy.Economy (reflexion)
    private Method getBalanceMethod;

    @Override
    public void onEnable() {
        Plugin host = Bukkit.getPluginManager().getPlugin("PluginLoader");
        File folder = host != null ? host.getDataFolder() : new File("plugins/PluginLoader");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        file = new File(folder, FILE_NAME);
        cfg = YamlConfiguration.loadConfiguration(file);

        hookEconomy();

        registerCommand("topstands", (sender, cmd, label, args) -> {
            handleCommand(sender, args);
            return true;
        });

        if (host != null) {
            timer = Bukkit.getScheduler().runTaskTimer(host, new Runnable() {
                @Override
                public void run() {
                    refresh(null);
                }
            }, 100L, REFRESH_TICKS);
        }
        getLogger().info("[TopStands] actif - " + boundRanks().size() + " rang(s) lie(s)"
                + (economy == null ? " (economie Vault introuvable !)" : ""));
    }

    @Override
    public void onDisable() {
        if (timer != null) {
            timer.cancel();
            timer = null;
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
            sender.sendMessage(Component.text("Demande \u00E0 zyrx717", NamedTextColor.RED));
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
            sender.sendMessage(Component.text("Armor stand ajoute au rang #" + rank + " (" + list.size() + " au total pour ce rang).", NamedTextColor.GREEN));
            refresh(sender);
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
            return;
        }

        if (sub.equals("update") || sub.equals("refresh")) {
            hookEconomy();
            refresh(sender);
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
        sender.sendMessage(Component.text("/topstands update", NamedTextColor.YELLOW).append(Component.text("  recalcule maintenant", NamedTextColor.GRAY)));
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

    // -------------------------------------------------------------- refresh

    /** Recalcule le classement et met a jour stands + textes (thread principal). */
    private void refresh(final CommandSender feedback) {
        final List<Integer> ranks = boundRanks();
        if (ranks.isEmpty()) {
            if (feedback != null) {
                feedback.sendMessage(Component.text("Aucun rang lie, rien a mettre a jour.", NamedTextColor.YELLOW));
            }
            return;
        }
        if (economy == null) {
            hookEconomy();
        }

        // 1) classement
        List<Object[]> top = new ArrayList<Object[]>(); // {OfflinePlayer, Double}
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op == null || op.getName() == null) {
                continue;
            }
            double bal = balanceOf(op);
            if (bal <= 0.0) {
                continue;
            }
            top.add(new Object[]{op, bal});
        }
        Collections.sort(top, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return Double.compare((Double) b[1], (Double) a[1]);
            }
        });

        // 2) application
        int applied = 0;
        int total = 0;
        for (Integer rank : ranks) {
            OfflinePlayer op = rank <= top.size() ? (OfflinePlayer) top.get(rank - 1)[0] : null;
            double bal = rank <= top.size() ? (Double) top.get(rank - 1)[1] : 0.0;
            total += entriesOf(rank).size();
            applied += applyRank(rank, op, bal);
        }
        save();
        if (feedback != null) {
            feedback.sendMessage(Component.text("Classement mis a jour : " + applied + "/" + total + " armor stand(s) sur "
                    + ranks.size() + " rang(s), " + top.size() + " joueur(s) classes.", NamedTextColor.GREEN));
        }
    }

    /** Applique le classement a tous les armor stands du rang. Renvoie le nombre de stands mis a jour. */
    private int applyRank(int rank, OfflinePlayer op, double bal) {
        List<Map<String, Object>> list = entriesOf(rank);
        int done = 0;
        boolean changed = false;
        ItemStack head = op != null ? headOf(op) : null;
        Component text = buildText(rank, op, bal);
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

    private Component buildText(int rank, OfflinePlayer op, double bal) {
        TextColor rankColor;
        String medal;
        if (rank == 1) {
            rankColor = TextColor.color(0xFFD700);
            medal = "\u2605 ";
        } else if (rank == 2) {
            rankColor = TextColor.color(0xC0C0C0);
            medal = "\u2605 ";
        } else if (rank == 3) {
            rankColor = TextColor.color(0xCD7F32);
            medal = "\u2605 ";
        } else {
            rankColor = NamedTextColor.WHITE;
            medal = "";
        }
        if (op == null) {
            return Component.text(medal + "#" + rank, rankColor).decorate(TextDecoration.BOLD)
                    .append(Component.newline())
                    .append(Component.text("Libre", NamedTextColor.DARK_GRAY));
        }
        return Component.text(medal + "#" + rank + "  ", rankColor).decorate(TextDecoration.BOLD)
                .append(Component.text(op.getName(), NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text(formatMoney(bal) + " $", NamedTextColor.GREEN));
    }

    private static String formatMoney(double v) {
        String s = String.format("%,.0f", v);
        return s.replace(',', ' ').replace('\u00A0', ' ').replace('\u202F', ' ');
    }

    private ItemStack headOf(OfflinePlayer op) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            boolean done = false;
            try {
                PlayerProfile profile = profileCache.get(op.getUniqueId());
                if (profile == null) {
                    profile = Bukkit.createProfile(op.getUniqueId(), op.getName());
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
