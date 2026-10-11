package fr.notp;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NoTp - Optaland
 *
 * /notp  -> active / desactive la protection anti-teleportation (owner uniquement).
 *
 * Quand la protection d'un joueur est ACTIVE, un joueur de GRADE INFERIEUR ne peut
 * plus se teleporter vers lui ni le faire venir : /tpa, /tpahere, /tp, /tpo,
 * /teleport, /tphere... sont bloques AVANT d'etre executes (donc ca marche aussi
 * bien pour OptaTP que pour Essentials ou la commande vanilla).
 * Les joueurs de grade egal ou superieur ne sont pas concernes.
 *
 * Le grade est lu comme dans StaffMode : la permission grade.<nom>, dans l'ordre
 * owner > super_admin > admin > super_modo > mod (lecture LuckPerms EXPLICITE, le
 * statut OP ne compte pas). Un joueur sans aucune permission grade.<nom> est au
 * niveau le plus bas. Ca marche aussi quand l'owner est hors service (/staff),
 * puisque la permission reste sur son compte.
 *
 * Permissions optionnelles (LuckPerms) :
 *   notp.use    -> autorise /notp meme sans grade.owner
 *   notp.bypass -> ignore la protection (peut toujours se teleporter)
 *
 * Etat sauvegarde dans notp.yml (survit aux redemarrages).
 */
public class NoTp extends LoadedPlugin implements Listener {

    private static final List<String> GRADES = Arrays.asList("owner", "super_admin", "admin", "super_modo", "mod");

    /** Commandes interceptees (sans le prefixe de namespace). */
    private static final Set<String> TP_COMMANDS = new HashSet<>(Arrays.asList(
            "tpa", "tpahere", "tp", "tpo", "tpohere", "tphere", "teleport", "tppos",
            "etpa", "etpahere", "etp", "etpo", "etphere"));

    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();
    private File file;

    @Override
    public void onEnable() {
        load();
        try {
            Bukkit.getPluginManager().registerEvents(this, getHost());
        } catch (Throwable t) {
            getLogger().severe("[NoTp] listener impossible a enregistrer : " + t);
        }

        registerCommand("notp", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(msg("Commande reservee aux joueurs.", NamedTextColor.RED));
                return true;
            }
            if (!canUse(p)) {
                send(p, "Commande reservee au grade owner.", NamedTextColor.RED);
                return true;
            }
            UUID u = p.getUniqueId();
            if (enabled.remove(u)) {
                save();
                send(p, "Protection anti-teleportation DESACTIVEE : tout le monde peut de nouveau se teleporter vers toi.", NamedTextColor.GRAY);
            } else {
                enabled.add(u);
                save();
                send(p, "Protection anti-teleportation ACTIVEE : les grades inferieurs au tien ne peuvent plus se teleporter vers toi.", NamedTextColor.GREEN);
            }
            return true;
        });

        getLogger().info("[NoTp] actif - " + enabled.size() + " joueur(s) proteges.");
    }

    @Override
    public void onDisable() {
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        save();
        getLogger().info("[NoTp] desactive.");
    }

    // ===================== INTERCEPTION DES COMMANDES =====================

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (enabled.isEmpty()) return;
        String msg = e.getMessage();
        if (msg == null || msg.length() < 2) return;
        String[] parts = msg.substring(1).trim().split("\\s+");
        if (parts.length < 2) return; // pas de cible
        String label = parts[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!TP_COMMANDS.contains(label)) return;

        Player sender = e.getPlayer();
        if (hasExplicit(sender, "notp.bypass")) return;
        int senderLevel = gradeLevel(sender);

        for (int i = 1; i < parts.length; i++) {
            Player target = Bukkit.getPlayerExact(parts[i]);
            if (target == null || target.equals(sender)) continue;
            if (!enabled.contains(target.getUniqueId())) continue;
            int targetLevel = gradeLevel(target);
            if (senderLevel > targetLevel) { // plus grand = plus bas dans la hierarchie
                e.setCancelled(true);
                send(sender, target.getName() + " a desactive les teleportations : ton grade ne permet pas de te teleporter vers lui.", NamedTextColor.RED);
                return;
            }
        }
    }

    // ===================== GRADES =====================

    /** 0 = owner ... 4 = mod, GRADES.size() = aucun grade (le plus bas). */
    private int gradeLevel(Player p) {
        for (int i = 0; i < GRADES.size(); i++) {
            if (hasExplicit(p, "grade." + GRADES.get(i))) return i;
        }
        return GRADES.size();
    }

    private boolean canUse(Player p) {
        return hasExplicit(p, "grade.owner") || hasExplicit(p, "notp.use");
    }

    /**
     * Verification EXPLICITE via l'API LuckPerms (Tristate TRUE uniquement) : un OP
     * sans la permission n'est PAS considere comme l'ayant. Si LuckPerms est
     * indisponible, repli sur hasPermission classique.
     */
    private boolean hasExplicit(Player p, String perm) {
        try {
            Object lp = lpApi();
            if (lp != null) {
                Object um = lp.getClass().getMethod("getUserManager").invoke(lp);
                Object user = um.getClass().getMethod("getUser", UUID.class).invoke(um, p.getUniqueId());
                if (user != null) {
                    Object cached = user.getClass().getMethod("getCachedData").invoke(user);
                    Object permData = cached.getClass().getMethod("getPermissionData").invoke(cached);
                    Object tristate = permData.getClass().getMethod("checkPermission", String.class).invoke(permData, perm);
                    return tristate != null && "TRUE".equalsIgnoreCase(tristate.toString());
                }
            }
        } catch (Throwable ignored) {
        }
        return p.hasPermission(perm);
    }

    private Object lpApi() {
        try {
            Plugin lp = Bukkit.getPluginManager().getPlugin("LuckPerms");
            if (lp == null) return null;
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider", true, lp.getClass().getClassLoader());
            return provider.getMethod("get").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ===================== PERSISTANCE =====================

    private File file() {
        if (file == null) {
            try { file = new File(((Plugin) getHost()).getDataFolder(), "notp.yml"); }
            catch (Throwable t) { file = new File("plugins/PluginLoader", "notp.yml"); }
        }
        return file;
    }

    private void load() {
        enabled.clear();
        try {
            File f = file();
            if (!f.exists()) return;
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            for (String s : cfg.getStringList("enabled")) {
                try { enabled.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
            }
        } catch (Throwable t) {
            getLogger().warning("[NoTp] lecture de notp.yml KO : " + t);
        }
    }

    private void save() {
        try {
            File f = file();
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            YamlConfiguration cfg = new YamlConfiguration();
            List<String> list = new ArrayList<>();
            for (UUID u : enabled) list.add(u.toString());
            cfg.set("enabled", list);
            cfg.save(f);
        } catch (Throwable t) {
            getLogger().warning("[NoTp] sauvegarde de notp.yml KO : " + t);
        }
    }

    // ===================== UTIL =====================

    static final Component PREFIX = Component.text("NoTP ", NamedTextColor.RED, TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_GRAY));

    private Component msg(String text, NamedTextColor color) {
        return PREFIX.append(Component.text(text, color));
    }

    private void send(Player p, String text, NamedTextColor color) {
        p.sendMessage(msg(text, color));
    }
}
