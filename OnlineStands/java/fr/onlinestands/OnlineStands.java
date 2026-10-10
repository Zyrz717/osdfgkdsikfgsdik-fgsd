package fr.onlinestands;

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
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OnlineStands - mur des joueurs connectes.
 *
 * Chaque armor stand enregistre est un "slot". Les joueurs en ligne sont
 * repartis dans les slots dans l'ordre de leur connexion : tete du joueur
 * sur l'armor stand, et un texte au-dessus avec son pseudo et depuis
 * combien de temps il est la. Un slot sans joueur affiche "Libre".
 *
 * Les joueurs en vanish (metadata "vanished", utilisee par Essentials,
 * SuperVanish, etc.) ne sont pas affiches.
 *
 * Commandes (OP, ou zyrx717 / Gusto77) :
 *   /onlinestands set      ajoute l'armor stand le plus proche (4 blocs) comme slot
 *   /onlinestands remove   retire l'armor stand le plus proche
 *   /onlinestands list     liste les slots
 *   /onlinestands update   rafraichit tout de suite
 *
 * Verification toutes les 2 secondes ; les textes ne sont reecrits que
 * quand quelque chose change (ou chaque minute pour la duree).
 */
public class OnlineStands extends LoadedPlugin {

    private static final String[] STAFF = {"zyrx717", "Gusto77"};
    private static final long POLL_TICKS = 40L;          // 2 secondes
    private static final int FORCE_EVERY_POLLS = 30;     // reecrit la duree toutes les 60 s
    private static final double SEARCH_RADIUS = 4.0;
    private static final double TEXT_HEIGHT_NORMAL = 2.45;
    private static final double TEXT_HEIGHT_SMALL = 1.35;
    private static final String FILE_NAME = "onlinestands.yml";

    private final NamespacedKey slotKey = new NamespacedKey("onlinestands", "slot");

    private BukkitTask timer;
    private File file;
    private YamlConfiguration cfg;

    private final Map<UUID, PlayerProfile> profileCache = new HashMap<UUID, PlayerProfile>();
    private final Map<UUID, Long> firstSeen = new HashMap<UUID, Long>();
    private String lastSignature = "";
    private int pollCounter = 0;

    @Override
    public void onEnable() {
        Plugin host = Bukkit.getPluginManager().getPlugin("PluginLoader");
        File folder = host != null ? host.getDataFolder() : new File("plugins/PluginLoader");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        file = new File(folder, FILE_NAME);
        cfg = YamlConfiguration.loadConfiguration(file);

        registerCommand("onlinestands", (sender, cmd, label, args) -> {
            handleCommand(sender, args);
            return true;
        });

        if (host != null) {
            timer = Bukkit.getScheduler().runTaskTimer(host, new Runnable() {
                @Override
                public void run() {
                    poll();
                }
            }, 60L, POLL_TICKS);
        }
        getLogger().info("[OnlineStands] actif - " + slots().size() + " slot(s)");
    }

    @Override
    public void onDisable() {
        if (timer != null) {
            timer.cancel();
            timer = null;
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
            ArmorStand stand = nearestStand((Player) sender);
            if (stand == null) {
                sender.sendMessage(Component.text("Aucun armor stand a moins de 4 blocs de toi.", NamedTextColor.RED));
                return;
            }
            unbind(stand.getUniqueId().toString());
            Location l = stand.getLocation();
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("stand", stand.getUniqueId().toString());
            entry.put("world", l.getWorld().getName());
            entry.put("x", l.getX());
            entry.put("y", l.getY());
            entry.put("z", l.getZ());
            List<Map<String, Object>> list = slots();
            list.add(entry);
            writeSlots(list);
            save();
            sender.sendMessage(Component.text("Slot #" + list.size() + " ajoute.", NamedTextColor.GREEN));
            lastSignature = "";
            poll();
            return;
        }

        if (sub.equals("remove") || sub.equals("del") || sub.equals("delete")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Component.text("Commande en jeu uniquement.", NamedTextColor.RED));
                return;
            }
            ArmorStand stand = nearestStand((Player) sender);
            if (stand == null) {
                sender.sendMessage(Component.text("Aucun armor stand a moins de 4 blocs de toi.", NamedTextColor.RED));
                return;
            }
            if (!unbind(stand.getUniqueId().toString())) {
                sender.sendMessage(Component.text("Cet armor stand n'est pas un slot.", NamedTextColor.RED));
                return;
            }
            save();
            sender.sendMessage(Component.text("Slot retire (" + slots().size() + " restant(s)).", NamedTextColor.GREEN));
            lastSignature = "";
            poll();
            return;
        }

        if (sub.equals("clear")) {
            List<Map<String, Object>> list = slots();
            for (Map<String, Object> e : list) {
                cleanup(e);
            }
            writeSlots(new ArrayList<Map<String, Object>>());
            save();
            sender.sendMessage(Component.text(list.size() + " slot(s) retire(s).", NamedTextColor.GREEN));
            return;
        }

        if (sub.equals("list")) {
            List<Map<String, Object>> list = slots();
            if (list.isEmpty()) {
                sender.sendMessage(Component.text("Aucun slot. Utilise /onlinestands set pres d'un armor stand.", NamedTextColor.YELLOW));
                return;
            }
            sender.sendMessage(Component.text("Slots (" + list.size() + ") :", NamedTextColor.GOLD));
            int i = 0;
            for (Map<String, Object> e : list) {
                i++;
                String where = e.get("world") + " " + (int) toDouble(e.get("x")) + " " + (int) toDouble(e.get("y")) + " " + (int) toDouble(e.get("z"));
                boolean alive = false;
                try {
                    alive = Bukkit.getEntity(UUID.fromString(String.valueOf(e.get("stand")))) != null;
                } catch (Throwable ignored) {
                }
                sender.sendMessage(Component.text(" #" + i + "  ", NamedTextColor.YELLOW)
                        .append(Component.text(where, NamedTextColor.WHITE))
                        .append(Component.text(alive ? "  (ok)" : "  (armor stand introuvable / chunk non charge)", alive ? NamedTextColor.GREEN : NamedTextColor.RED)));
            }
            return;
        }

        if (sub.equals("update") || sub.equals("refresh")) {
            lastSignature = "";
            poll();
            sender.sendMessage(Component.text("Mur des connectes rafraichi (" + onlineSorted().size() + " joueur(s) en ligne).", NamedTextColor.GREEN));
            return;
        }

        help(sender);
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text("OnlineStands", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        sender.sendMessage(Component.text("/onlinestands set", NamedTextColor.YELLOW).append(Component.text("  ajoute l'armor stand le plus proche comme slot", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/onlinestands remove", NamedTextColor.YELLOW).append(Component.text("  retire l'armor stand le plus proche", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/onlinestands list", NamedTextColor.YELLOW).append(Component.text("  liste les slots", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/onlinestands update", NamedTextColor.YELLOW).append(Component.text("  rafraichit maintenant", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/onlinestands clear", NamedTextColor.YELLOW).append(Component.text("  retire tous les slots", NamedTextColor.GRAY)));
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

    private static double toDouble(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    private List<Map<String, Object>> slots() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<?, ?> m : cfg.getMapList("slots")) {
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

    private void writeSlots(List<Map<String, Object>> list) {
        cfg.set("slots", list.isEmpty() ? null : list);
    }

    private void save() {
        try {
            cfg.save(file);
        } catch (Throwable t) {
            getLogger().warning("[OnlineStands] Sauvegarde impossible : " + t);
        }
    }

    private void cleanup(Map<String, Object> e) {
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

    private boolean unbind(String standId) {
        List<Map<String, Object>> list = slots();
        List<Map<String, Object>> keep = new ArrayList<Map<String, Object>>();
        boolean found = false;
        for (Map<String, Object> e : list) {
            if (standId.equals(String.valueOf(e.get("stand")))) {
                cleanup(e);
                found = true;
            } else {
                keep.add(e);
            }
        }
        if (found) {
            writeSlots(keep);
        }
        return found;
    }

    // ----------------------------------------------------------------- poll

    private boolean isVanished(Player p) {
        try {
            if (p.hasMetadata("vanished")) {
                for (MetadataValue v : p.getMetadata("vanished")) {
                    if (v.asBoolean()) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Joueurs visibles, du plus ancien connecte au plus recent. */
    private List<Player> onlineSorted() {
        long now = System.currentTimeMillis();
        List<Player> out = new ArrayList<Player>();
        List<UUID> present = new ArrayList<UUID>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            present.add(p.getUniqueId());
            if (!firstSeen.containsKey(p.getUniqueId())) {
                firstSeen.put(p.getUniqueId(), now);
            }
            if (!isVanished(p)) {
                out.add(p);
            }
        }
        // oublie ceux qui sont partis
        List<UUID> gone = new ArrayList<UUID>();
        for (UUID id : firstSeen.keySet()) {
            if (!present.contains(id)) {
                gone.add(id);
            }
        }
        for (UUID id : gone) {
            firstSeen.remove(id);
        }
        Collections.sort(out, new Comparator<Player>() {
            @Override
            public int compare(Player a, Player b) {
                long ta = firstSeen.get(a.getUniqueId());
                long tb = firstSeen.get(b.getUniqueId());
                if (ta != tb) {
                    return ta < tb ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    private void poll() {
        List<Map<String, Object>> list = slots();
        if (list.isEmpty()) {
            return;
        }
        List<Player> online = onlineSorted();
        StringBuilder sig = new StringBuilder();
        for (Player p : online) {
            sig.append(p.getUniqueId()).append(';');
        }
        pollCounter++;
        boolean force = pollCounter % FORCE_EVERY_POLLS == 0;
        if (!force && sig.toString().equals(lastSignature)) {
            return;
        }
        lastSignature = sig.toString();

        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> e = list.get(i);
            Player p = i < online.size() ? online.get(i) : null;
            if (apply(e, i + 1, p)) {
                changed = true;
            }
        }
        if (changed) {
            writeSlots(list);
            save();
        }
    }

    /** Renvoie true si l'entree a ete modifiee (nouveau display cree). */
    private boolean apply(Map<String, Object> e, int slot, Player p) {
        Entity standEnt = null;
        try {
            standEnt = Bukkit.getEntity(UUID.fromString(String.valueOf(e.get("stand"))));
        } catch (Throwable ignored) {
        }
        if (!(standEnt instanceof ArmorStand)) {
            return false;
        }
        ArmorStand stand = (ArmorStand) standEnt;
        stand.getEquipment().setHelmet(p == null ? null : headOf(p));

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
        boolean created = false;
        Location target = stand.getLocation().clone().add(0.0, stand.isSmall() ? TEXT_HEIGHT_SMALL : TEXT_HEIGHT_NORMAL, 0.0);
        if (td == null) {
            td = spawnDisplay(target, slot);
            e.put("display", td.getUniqueId().toString());
            created = true;
        } else if (td.getLocation().distanceSquared(target) > 0.01) {
            td.teleport(target);
        }
        td.text(buildText(p));
        return created;
    }

    private TextDisplay spawnDisplay(Location loc, int slot) {
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
        td.getPersistentDataContainer().set(slotKey, PersistentDataType.INTEGER, slot);
        return td;
    }

    private Component buildText(Player p) {
        if (p == null) {
            return Component.text("Libre", NamedTextColor.DARK_GRAY);
        }
        Long since = firstSeen.get(p.getUniqueId());
        long mins = since == null ? 0L : (System.currentTimeMillis() - since) / 60000L;
        String dur;
        if (mins < 1) {
            dur = "vient d'arriver";
        } else if (mins < 60) {
            dur = "en ligne depuis " + mins + " min";
        } else {
            dur = "en ligne depuis " + (mins / 60) + " h " + String.format("%02d", mins % 60);
        }
        return Component.text("\u25CF ", TextColor.color(0x55FF55))
                .append(Component.text(p.getName(), NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text(dur, NamedTextColor.GRAY));
    }

    private ItemStack headOf(Player p) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            boolean done = false;
            try {
                PlayerProfile profile = profileCache.get(p.getUniqueId());
                if (profile == null) {
                    // joueur en ligne : son profil (avec skin) est deja connu du serveur
                    profile = p.getPlayerProfile();
                    if (profile == null || !profile.hasTextures()) {
                        profile = Bukkit.createProfile(p.getUniqueId(), p.getName());
                        if (!profile.hasTextures()) {
                            profile.complete(true);
                        }
                    }
                    profileCache.put(p.getUniqueId(), profile);
                }
                meta.setPlayerProfile(profile);
                done = true;
            } catch (Throwable ignored) {
            }
            if (!done) {
                try {
                    meta.setOwningPlayer(p);
                } catch (Throwable ignored) {
                }
            }
            head.setItemMeta(meta);
        }
        return head;
    }
}
