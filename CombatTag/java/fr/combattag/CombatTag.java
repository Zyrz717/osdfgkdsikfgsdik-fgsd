package fr.combattag;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Combat Tag.
 *   - Un coup PvP marque l'attaquant ET la victime en combat pendant 15 secondes.
 *   - Chaque coup relance le compte a rebours a 15 s.
 *   - MORT = fin de combat : le mort ET ses adversaires sortent du combat
 *     (sauf si un adversaire est encore engage contre quelqu'un d'autre).
 *   - Pendant le combat, seules /shop et /tag sont autorisees.
 *   - Pendant le combat, la ZONE PROTEGEE est inaccessible : blocage a l'entree,
 *     Lenteur I pendant 10 s + message. C'est INDIVIDUEL : un joueur hors combat
 *     peut entrer normalement meme si son adversaire est bloque.
 *   - /tag  -> affiche le temps de combat restant.
 *   - Se deconnecter en combat = mort (anti combat-log), sans duplication de stuff.
 */
public class CombatTag extends LoadedPlugin implements Listener {

    private static final long TAG_MS = 15_000L; // 15 secondes
    private static final List<String> ALLOWED = List.of("shop", "tag"); // commandes autorisees en combat

    // ---------- Zone interdite en combat ----------
    // Coins : (-686, 163, -207)  <->  (-781, 70, -302)
    private static final String REGION_WORLD = null; // null = tous les mondes, sinon ex: "world"
    private static final int MIN_X = Math.min(-686, -781), MAX_X = Math.max(-686, -781);
    private static final int MIN_Y = Math.min(163, 70),    MAX_Y = Math.max(163, 70);
    private static final int MIN_Z = Math.min(-207, -302), MAX_Z = Math.max(-207, -302);

    private static final int SLOW_TICKS = 200;   // 10 secondes
    private static final int SLOW_LEVEL = 0;     // amplifier 0 = Lenteur I
    private static final long DENY_COOLDOWN_MS = 2_000L; // anti-spam message/effet

    private final Map<UUID, Long> tagged = new HashMap<>();            // uuid -> instant de fin (ms)
    private final Map<UUID, Set<UUID>> opponents = new HashMap<>();    // uuid -> adversaires en cours
    private final Map<UUID, Long> denyCooldown = new HashMap<>();      // anti-spam zone
    private final Set<UUID> combatLogged = new HashSet<>();            // quit en combat (filet anti-dupe)

    @Override
    public void onEnable() {
        registerListener(this);

        registerCommand("tag", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(msg("Commande reservee aux joueurs.", NamedTextColor.RED));
                return true;
            }
            long remain = remainingSeconds(player.getUniqueId());
            if (remain > 0) {
                player.sendMessage(msg("Tu es en combat encore " + remain + " seconde(s).", NamedTextColor.RED));
            } else {
                player.sendMessage(msg("Tu n'es pas en combat.", NamedTextColor.GREEN));
            }
            return true;
        });

        // Rafraichissement chaque seconde : barre d'action + fin de combat.
        Bukkit.getScheduler().runTaskTimer(getHost(), this::tick, 20L, 20L);

        getLogger().info("CombatTag active.");
    }

    // ---------- Marquage combat ----------

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) attacker = p;
        }
        if (attacker == null || attacker.equals(victim)) return;

        tag(attacker);
        tag(victim);

        // memorise le lien attaquant <-> victime pour pouvoir couper le combat a la mort
        link(attacker.getUniqueId(), victim.getUniqueId());
    }

    private void tag(Player player) {
        boolean wasTagged = remainingSeconds(player.getUniqueId()) > 0;
        tagged.put(player.getUniqueId(), System.currentTimeMillis() + TAG_MS);
        if (!wasTagged) {
            player.sendMessage(msg("Tu es maintenant en combat ! (" + (TAG_MS / 1000) + "s)", NamedTextColor.RED));
        }
    }

    private void link(UUID a, UUID b) {
        opponents.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        opponents.computeIfAbsent(b, k -> new HashSet<>()).add(a);
    }

    private long remainingSeconds(UUID uuid) {
        Long end = tagged.get(uuid);
        if (end == null) return 0;
        long ms = end - System.currentTimeMillis();
        return ms <= 0 ? 0 : (ms + 999) / 1000; // arrondi au superieur
    }

    /** Retire completement le tag d'un joueur et nettoie les liens d'adversaires. */
    private void removeTag(UUID uuid) {
        tagged.remove(uuid);
        Set<UUID> mine = opponents.remove(uuid);
        if (mine != null) {
            for (UUID other : mine) {
                Set<UUID> theirs = opponents.get(other);
                if (theirs != null) {
                    theirs.remove(uuid);
                    if (theirs.isEmpty()) opponents.remove(other);
                }
            }
        }
    }

    // ---------- Mort = fin de combat ----------

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        handleDeath(event.getEntity().getUniqueId());
    }

    /**
     * Le joueur est mort : il sort du combat, et chacun de ses adversaires
     * en sort aussi s'il n'a plus personne d'autre en face.
     */
    private void handleDeath(UUID deadId) {
        Set<UUID> foes = opponents.get(deadId);
        List<UUID> copy = foes == null ? List.of() : new ArrayList<>(foes);

        removeTag(deadId); // nettoie aussi les liens dans les deux sens

        for (UUID foeId : copy) {
            // encore engage contre quelqu'un d'autre ? on le laisse en combat
            Set<UUID> still = opponents.get(foeId);
            if (still != null && !still.isEmpty()) continue;

            if (tagged.remove(foeId) != null) {
                Player foe = Bukkit.getPlayer(foeId);
                if (foe != null && foe.isOnline()) {
                    foe.sendMessage(msg("Ton adversaire est mort : tu n'es plus en combat.", NamedTextColor.GREEN));
                    foe.sendActionBar(Component.empty());
                }
            }
            opponents.remove(foeId);
        }
    }

    // ---------- Zone interdite pendant le combat ----------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (remainingSeconds(player.getUniqueId()) <= 0) return; // pas en combat -> libre

        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null) return;
        // on ne teste que si le joueur change de bloc (perf)
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;

        if (!inRegion(to)) return;
        if (inRegion(from)) return; // deja dedans (tag pris a l'interieur) -> on le laisse sortir

        event.setCancelled(true);
        denyEntry(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (remainingSeconds(player.getUniqueId()) <= 0) return;

        Location to = event.getTo();
        if (to == null || !inRegion(to)) return;
        if (inRegion(event.getFrom())) return;

        event.setCancelled(true); // bloque aussi perle d'ender / tp
        denyEntry(player);
    }

    private void denyEntry(Player player) {
        long now = System.currentTimeMillis();
        Long until = denyCooldown.get(player.getUniqueId());
        if (until != null && now < until) return; // anti-spam

        denyCooldown.put(player.getUniqueId(), now + DENY_COOLDOWN_MS);
        // PotionEffectType.SLOWNESS -> sur < 1.20.5 c'est PotionEffectType.SLOW
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, SLOW_TICKS, SLOW_LEVEL, false, true, true));
        player.sendMessage(msg("Zone interdite en combat ! Lenteur I pendant "
                + (SLOW_TICKS / 20) + "s. Reviens quand ton combat est termine.", NamedTextColor.RED));
    }

    private boolean inRegion(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        if (REGION_WORLD != null && !loc.getWorld().getName().equalsIgnoreCase(REGION_WORLD)) return false;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return x >= MIN_X && x <= MAX_X
                && y >= MIN_Y && y <= MAX_Y
                && z >= MIN_Z && z <= MAX_Z;
    }

    // ---------- Blocage des commandes ----------

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (remainingSeconds(player.getUniqueId()) <= 0) return;

        String message = event.getMessage();
        if (message.startsWith("/")) message = message.substring(1);
        String base = message.split(" ", 2)[0].toLowerCase();
        // gere les prefixes type "minecraft:tag" ou "plugin:shop"
        int colon = base.indexOf(':');
        if (colon >= 0) base = base.substring(colon + 1);

        if (!ALLOWED.contains(base)) {
            event.setCancelled(true);
            player.sendMessage(msg("Interdit en combat ! Seules /shop et /tag sont autorisees.", NamedTextColor.RED));
        }
    }

    // ---------- Anti combat-log ----------

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        if (remainingSeconds(id) > 0) {
            dropAndClear(player);                    // lache le stuff au sol + vide l'inventaire
            combatLogged.add(id);                    // filet de securite pour la reconnexion
            player.setHealth(0.0);                   // enregistre la mort
            // FORCE l'ecriture de l'inventaire VIDE sur le disque.
            // Sans ca, la sauvegarde du quit peut garder l'ancien inventaire
            // -> le joueur recupere son stuff au relog + celui au sol = DUPLICATION.
            player.saveData();
            // le combat-log compte comme une mort : ses adversaires sortent du combat
            handleDeath(id);
        }

        removeTag(id);
        denyCooldown.remove(id);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (combatLogged.remove(id)) {
            // Securite anti-dupe : si la sauvegarde n'a pas pris cote serveur,
            // on revide l'inventaire au login. Le stuff a DEJA ete lache au sol
            // lors du quit, donc aucune duplication possible.
            event.getPlayer().getInventory().clear();
        }
    }

    /**
     * Lache tout l'inventaire du joueur au sol puis le vide.
     * On utilise getStorageContents() (les 36 slots principaux UNIQUEMENT) :
     * getContents() inclut deja l'armure + l'offhand pour un joueur, ce qui
     * doublait l'armure quand on rajoutait les boucles armure/offhand.
     */
    private void dropAndClear(Player player) {
        var world = player.getWorld();
        var loc = player.getLocation();
        var inv = player.getInventory();

        for (var item : inv.getStorageContents()) { // 36 slots principaux
            if (item != null && !item.getType().isAir()) world.dropItemNaturally(loc, item);
        }
        for (var item : inv.getArmorContents()) {    // armure (4 slots)
            if (item != null && !item.getType().isAir()) world.dropItemNaturally(loc, item);
        }
        for (var item : inv.getExtraContents()) {    // main secondaire / offhand
            if (item != null && !item.getType().isAir()) world.dropItemNaturally(loc, item);
        }
        inv.clear();
    }

    // ---------- Boucle d'affichage ----------

    private void tick() {
        long now = System.currentTimeMillis();
        for (UUID uuid : new ArrayList<>(tagged.keySet())) {
            Long end = tagged.get(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (end == null || now >= end) {
                removeTag(uuid);
                if (player != null && player.isOnline()) {
                    player.sendMessage(msg("Tu n'es plus en combat.", NamedTextColor.GREEN));
                    player.sendActionBar(Component.empty());
                }
                continue;
            }
            if (player != null && player.isOnline()) {
                long s = (end - now + 999) / 1000;
                player.sendActionBar(msg("En combat : " + s + "s", NamedTextColor.RED));
            }
        }
    }

    private Component msg(String text, NamedTextColor color) {
        return Component.text(text).color(color);
    }

    @Override
    public void onDisable() {
        getLogger().info("CombatTag desactive.");
    }
}
