package fr.rtp;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Teleportation aleatoire (RTP) avec menu.
 *   /rtp -> ouvre un coffre 3x9 :
 *
 *      x x x x x x x x x
 *      x x R x N x E x x      R = Terre (Overworld)
 *      x x x x x x x x x      N = Netherrack (Nether)
 *                             E = End Stone (End, loin de l'ile du dragon)
 *
 * Portee (distance depuis 0,0) :
 *   - basique  : 10 000 -> 25 000 blocks   (permission rtp.use)
 *   - rtp.vip  : 30 000 -> 50 000 blocks
 *
 * Cooldown : 5 minutes (bypass : rtp.cooldown.bypass).
 * Warmup   : 5 secondes, annule si le joueur bouge.
 */
public class RandomTeleport extends LoadedPlugin {

    private static final long COOLDOWN_MS = 5L * 60L * 1000L; // 5 minutes
    private static final int BASIC_MIN = 2500, BASIC_MAX = 6250;
    private static final int VIP_MIN   = 7500, VIP_MAX   = 12500;

    private static final int WARMUP_SECONDS = 5;

    /**
     * Marge (en blocks) gardee entre le point d'arrivee et la bordure du monde.
     * CORRECTIF : avant, la portee etait fixe (jusqu'a 12 500 blocks) alors que la
     * bordure de l'Overworld fait 15 000 de large (+/- 7 500). Les joueurs etaient
     * teleportes HORS de la bordure et mouraient (degats de bordure). La portee est
     * maintenant calculee d'apres la bordure reelle de chaque monde.
     */
    private static final int BORDER_MARGIN = 48;

    /** Rayon interdit autour de 0,0 dans l'End (ile principale / dragon / fontaine). */
    private static final int END_FORBIDDEN_RADIUS = 2_000;

    // Slots du menu (ligne du milieu : 9..17)
    private static final int SLOT_OVERWORLD = 11;
    private static final int SLOT_NETHER    = 13;
    private static final int SLOT_END       = 15;

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> warming = new HashSet<>();

    // ------------------------------------------------------------------ setup

    @Override
    public void onEnable() {
        // 1) La commande D'ABORD : si l'enregistrement des events echoue,
        //    /rtp continue au moins de repondre.
        registerCommand("rtp", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(msg("Commande reservee aux joueurs.", NamedTextColor.RED));
                return true;
            }
            if (!player.hasPermission("rtp.use")) {
                player.sendMessage(msg("Vous n'avez pas la permission d'utiliser le RTP.", NamedTextColor.RED));
                return true;
            }
            if (isOnCooldown(player, true)) return true;

            openMenu(player);
            return true;
        });
        getLogger().info("[RTP] Commande /rtp enregistree.");

        // 2) Les events ensuite, isoles : une erreur ici ne casse plus la commande.
        try {
            Bukkit.getPluginManager().registerEvents(new MenuListener(), getHost());
            getLogger().info("[RTP] Listener du menu enregistre.");
        } catch (Throwable t) {
            getLogger().severe("[RTP] Impossible d'enregistrer le listener : " + t);
            t.printStackTrace();
        }

        // 3) Reprise du label court /rtp, 1s apres le demarrage (une fois que tous
        //    les autres plugins se sont enregistres).
        Bukkit.getScheduler().runTaskLater(getHost(), () -> forceClaimLabel("rtp"), 20L);

        getLogger().info("RandomTeleport active.");
    }

    /**
     * Si /rtp est occupe par un autre plugin (ou par une ancienne instance restee
     * dans la command map apres un reload a chaud), on reprend le label.
     * rtp:rtp continue evidemment de fonctionner dans tous les cas.
     */
    @SuppressWarnings("unchecked")
    private void forceClaimLabel(String label) {
        try {
            Object server = Bukkit.getServer();
            Object map = server.getClass().getMethod("getCommandMap").invoke(server);
            Method getCommand = map.getClass().getMethod("getCommand", String.class);

            // Notre commande, via sa version prefixee.
            Object ours = getCommand.invoke(map, "rtp:" + label);
            if (ours == null) {
                getLogger().warning("[RTP] Commande rtp:" + label + " introuvable dans la command map.");
                return;
            }

            Object current = getCommand.invoke(map, label);
            if (current == ours) {
                getLogger().info("[RTP] /" + label + " pointe bien vers nous.");
                return;
            }

            // knownCommands est declare dans SimpleCommandMap : on remonte la hierarchie.
            Field field = null;
            for (Class<?> c = map.getClass(); c != null && field == null; c = c.getSuperclass()) {
                try { field = c.getDeclaredField("knownCommands"); } catch (NoSuchFieldException ignored) { }
            }
            if (field == null) {
                getLogger().warning("[RTP] knownCommands introuvable, /" + label + " reste pris.");
                return;
            }
            field.setAccessible(true);
            ((Map<String, Object>) field.get(map)).put(label, ours);

            getLogger().warning("[RTP] /" + label + " etait occupe par : "
                    + (current == null ? "une entree morte" : current.getClass().getName())
                    + " -> label repris.");

            // Resynchronise l'auto-completion cote client (Brigadier).
            try { server.getClass().getMethod("syncCommands").invoke(server); } catch (Throwable ignored) { }

        } catch (Throwable t) {
            getLogger().warning("[RTP] Reprise de /" + label + " impossible : " + t
                    + " (utilise /rtp:rtp en attendant)");
        }
    }

    @Override
    public void onDisable() {
        // Ferme les menus encore ouverts pour eviter les items fantomes.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof RtpMenu) {
                p.closeInventory();
            }
        }
        warming.clear();
        getLogger().info("RandomTeleport desactive.");
    }

    // ------------------------------------------------------------------- menu

    /** Marqueur : permet de reconnaitre notre inventaire sans se fier au titre. */
    private static final class RtpMenu implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    private void openMenu(Player player) {
        boolean vip = player.hasPermission("rtp.vip");

        RtpMenu holder = new RtpMenu();
        Inventory inv = Bukkit.createInventory(holder, 27,
                Component.text("ᴛéʟéᴘᴏʀᴛᴀᴛɪᴏɴ ᴀʟéᴀᴛᴏɪʀᴇ"));
        holder.inventory = inv;

        inv.setItem(SLOT_OVERWORLD, icon(player, Material.GRASS_BLOCK, "ᴏᴠᴇʀᴡᴏʀʟᴅ", NamedTextColor.GREEN,
                World.Environment.NORMAL, vip));
        inv.setItem(SLOT_NETHER, icon(player, Material.NETHERRACK, "ɴᴇᴛʜᴇʀ", NamedTextColor.RED,
                World.Environment.NETHER, vip));
        inv.setItem(SLOT_END, icon(player, Material.END_STONE, "ᴇɴᴅ", NamedTextColor.LIGHT_PURPLE,
                World.Environment.THE_END, vip));

        try {
            player.openInventory(inv);
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.0f);
        } catch (Throwable t) {
            player.sendMessage(msg("Erreur a l'ouverture du menu, previens un admin.", NamedTextColor.RED));
            getLogger().severe("[RTP] openMenu a echoue : " + t);
            t.printStackTrace();
        }
    }

    private ItemStack icon(Player player, Material mat, String name, NamedTextColor color,
                           World.Environment env, boolean vip) {
        World world = findWorld(env);
        int[] range = (world == null) ? new int[]{0, 0} : rangeFor(player, world);
        int min = range[0], max = range[1];

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text(name, color)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));

        List<Component> lore = new ArrayList<>();

        // Infos live sur la dimension
        lore.add(kv("\u1d0a\u1d0f\u1d1c\u1d07\u1d1c\u0280", world == null ? "-" : String.valueOf(world.getPlayers().size()), color));
        lore.add(kv("\u1d0d\u1d00\u1d18", world == null ? "-" : mapSize(world), color));
        lore.add(kv("\u1d05\u026a\ua731\u1d1b\u1d00\u0274\u1d04\u1d07", fmt(min) + " - " + fmt(max), color));
        lore.add(kv("\u0280\u1d00\u0274\u0262", vip ? "VIP" : "basique", color));
        lore.add(Component.empty());

        if (env == World.Environment.THE_END) {
            lore.add(lore("Iles exterieures uniquement.", NamedTextColor.DARK_GRAY));
            lore.add(lore("Jamais sur l'ile du dragon.", NamedTextColor.DARK_GRAY));
            lore.add(Component.empty());
        }
        if (world == null) {
            lore.add(lore("Indisponible sur ce serveur.", NamedTextColor.RED));
        } else {
            lore.add(lore("Clique pour partir (" + WARMUP_SECONDS + "s)", NamedTextColor.YELLOW));
        }
        meta.lore(lore);

        item.setItemMeta(meta);
        return item;
    }

    /** Ligne de description : "\u029f\u1d00\u0299\u1d07\u029f\ufe55 valeur" */
    private Component kv(String label, String value, NamedTextColor color) {
        return Component.text(label + "\ufe55 ", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(value, color).decoration(TextDecoration.ITALIC, false));
    }

    /** Taille de la map d'apres la worldborder, ex : "25k x 25k". */
    private String mapSize(World world) {
        double size = world.getWorldBorder().getSize();
        if (size >= 29_999_984d) return "illimitee";
        String side = shortNum(size);
        return side + " x " + side;
    }

    /** 25000 -> "25k", 1500 -> "1.5k", 800 -> "800". */
    private String shortNum(double blocks) {
        if (blocks < 1000d) return String.valueOf(Math.round(blocks));
        double k = blocks / 1000d;
        if (Math.abs(k - Math.rint(k)) < 0.05d) return Math.round(k) + "k";
        return String.format(java.util.Locale.ROOT, "%.1fk", k);
    }

    private Component lore(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    /** Listener separe : evite les fuites si le loader recharge le plugin. */
    private final class MenuListener implements Listener {

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof RtpMenu) event.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof RtpMenu)) return;
        event.setCancelled(true); // rien ne sort du menu

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof RtpMenu)) return;

        World.Environment env = switch (event.getSlot()) {
            case SLOT_OVERWORLD -> World.Environment.NORMAL;
            case SLOT_NETHER    -> World.Environment.NETHER;
            case SLOT_END       -> World.Environment.THE_END;
            default             -> null;
        };
        if (env == null) return;

        player.closeInventory();
        launch(player, env);
    }
    }

    // --------------------------------------------------------------- lancement

    private void launch(Player player, World.Environment env) {
        if (!player.hasPermission("rtp.use")) {
            player.sendMessage(msg("Vous n'avez pas la permission d'utiliser le RTP.", NamedTextColor.RED));
            return;
        }
        if (warming.contains(player.getUniqueId())) {
            player.sendMessage(msg("Une teleportation est deja en cours.", NamedTextColor.RED));
            return;
        }
        if (isOnCooldown(player, true)) return;

        World world = findWorld(env);
        if (world == null) {
            player.sendMessage(msg("Cette dimension n'est pas disponible sur ce serveur.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
            return;
        }

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
        startWarmup(player, world);
    }

    /** Compte a rebours de 5 secondes : sons, titres, particules. Annule si le joueur bouge. */
    private void startWarmup(Player player, World target) {
        UUID id = player.getUniqueId();
        warming.add(id);

        final Location origin = player.getLocation().clone();
        final String worldName = prettyName(target.getEnvironment());

        new BukkitRunnable() {
            int left = WARMUP_SECONDS;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    warming.remove(id);
                    cancel();
                    return;
                }

                Location now = player.getLocation();
                if (now.getWorld() != origin.getWorld() || now.distanceSquared(origin) > 4.0) {
                    warming.remove(id);
                    cancel();
                    player.clearTitle();
                    player.sendMessage(msg("Teleportation annulee : tu as bouge.", NamedTextColor.RED));
                    player.playSound(now, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
                    return;
                }

                if (left <= 0) {
                    warming.remove(id);
                    cancel();
                    player.clearTitle();
                    player.playSound(now, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.9f);
                    now.getWorld().spawnParticle(Particle.PORTAL, now.clone().add(0, 1, 0), 80, 0.4, 1.0, 0.4, 0.6);
                    player.sendMessage(msg("Recherche d'un endroit sur...", NamedTextColor.GRAY));
                    tryTeleport(player, target, attemptsFor(target));
                    return;
                }

                // Titre : gros chiffre + destination
                player.showTitle(Title.title(
                        Component.text(String.valueOf(left), colorFor(left)),
                        Component.text("Destination : " + worldName, NamedTextColor.GRAY),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ofMillis(200))));

                // Petit "ding" d'xp, de plus en plus aigu
                float pitch = 1.0f + (WARMUP_SECONDS - left) * 0.18f;
                player.playSound(now, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, pitch);
                player.playSound(now, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, pitch);

                // Spirale de particules autour du joueur
                for (int i = 0; i < 12; i++) {
                    double a = (Math.PI * 2 / 12) * i + (left * 0.4);
                    double r = 0.9;
                    Location p = now.clone().add(Math.cos(a) * r, 0.15 + (WARMUP_SECONDS - left) * 0.25, Math.sin(a) * r);
                    now.getWorld().spawnParticle(Particle.END_ROD, p, 1, 0, 0, 0, 0);
                }

                left--;
            }
        }.runTaskTimer(getHost(), 0L, 20L);
    }

    // ------------------------------------------------------------ teleportation

    /** Tente une teleportation ; recommence (chunk charge en async) tant qu'aucun endroit sur n'est trouve. */
    private void tryTeleport(Player player, World world, int attemptsLeft) {
        if (!player.isOnline()) return;
        if (attemptsLeft <= 0) {
            player.sendMessage(msg("Impossible de trouver un endroit sur, reessaie.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
            return;
        }

        int[] range = rangeFor(player, world);
        int min = range[0], max = range[1];

        // Le point est tire autour du CENTRE de la bordure (0,0 en general), et
        // dist <= demi-largeur de la bordure - marge : on reste toujours dedans.
        Location center = world.getWorldBorder().getCenter();
        double angle = Math.random() * Math.PI * 2;
        int dist = min + (int) (Math.random() * (max - min + 1));
        int x = (int) Math.round(center.getX() + Math.cos(angle) * dist);
        int z = (int) Math.round(center.getZ() + Math.sin(angle) * dist);

        // Double securite : jamais hors bordure (sinon le joueur prend des degats et meurt).
        if (!insideBorder(world, x, z)) {
            tryTeleport(player, world, attemptsLeft - 1);
            return;
        }

        // Securite : jamais l'ile principale de l'End (dragon / fontaine).
        if (world.getEnvironment() == World.Environment.THE_END
                && (long) x * x + (long) z * z < (long) END_FORBIDDEN_RADIUS * END_FORBIDDEN_RADIUS) {
            tryTeleport(player, world, attemptsLeft - 1);
            return;
        }

        world.getChunkAtAsync(x >> 4, z >> 4, true).thenAccept(chunk ->
            Bukkit.getScheduler().runTask(getHost(), () -> {
                if (!player.isOnline()) return;
                Integer y = findSafeY(world, x, z);
                if (y != null) {
                    Location loc = new Location(world, x + 0.5, y, z + 0.5, player.getLocation().getYaw(), 0f);
                    player.teleport(loc);
                    cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
                    arrivalEffects(player, loc, dist);
                } else {
                    tryTeleport(player, world, attemptsLeft - 1);
                }
            })
        );
    }

    /** Sons + particules + titre a l'arrivee. */
    private void arrivalEffects(Player player, Location loc, int dist) {
        World w = loc.getWorld();
        w.spawnParticle(Particle.PORTAL, loc.clone().add(0, 1, 0), 120, 0.5, 1.0, 0.5, 0.8);
        w.spawnParticle(Particle.END_ROD, loc.clone().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.12);
        w.spawnParticle(Particle.CLOUD, loc, 25, 0.4, 0.1, 0.4, 0.05);

        player.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        player.playSound(loc, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.6f);
        player.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.1f);

        player.showTitle(Title.title(
                Component.text("Bon voyage !", NamedTextColor.AQUA),
                Component.text(prettyName(w.getEnvironment()) + " - " + fmt(dist) + " blocks du centre", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1600), Duration.ofMillis(400))));

        player.sendMessage(msg("Teleporte a " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()
                + "  (" + fmt(dist) + " blocks du centre).", NamedTextColor.GREEN));
    }

    // ---------------------------------------------------------------- utilitaires

    /**
     * Portee {min, max} pour ce joueur dans ce monde, LIMITEE a la bordure du monde.
     * Ex : bordure 15 000 de large -> demi-largeur 7 500 -> max 7 452 (marge 48).
     */
    private int[] rangeFor(Player player, World world) {
        boolean vip = player.hasPermission("rtp.vip");
        int min = vip ? VIP_MIN : BASIC_MIN;
        int max = vip ? VIP_MAX : BASIC_MAX;

        double half = world.getWorldBorder().getSize() / 2.0 - BORDER_MARGIN;
        if (half < 64) half = 64; // bordure minuscule : on fait au mieux
        if (max > half) max = (int) half;
        if (min >= max) min = Math.max(0, max / 2);
        return new int[]{min, max};
    }

    /** Vrai si (x,z) est a l'interieur de la bordure du monde, marge comprise. */
    private boolean insideBorder(World world, int x, int z) {
        org.bukkit.WorldBorder border = world.getWorldBorder();
        Location c = border.getCenter();
        double half = border.getSize() / 2.0 - BORDER_MARGIN;
        return Math.abs(x + 0.5 - c.getX()) <= half && Math.abs(z + 0.5 - c.getZ()) <= half;
    }

    private int attemptsFor(World world) {
        return switch (world.getEnvironment()) {
            case THE_END -> 120; // beaucoup de void, il faut insister
            case NETHER  -> 60;
            default      -> 40;
        };
    }

    private World findWorld(World.Environment env) {
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == env) return w;
        }
        return null;
    }

    private boolean isOnCooldown(Player player, boolean notify) {
        if (player.hasPermission("rtp.cooldown.bypass")) return false;
        Long last = cooldowns.get(player.getUniqueId());
        if (last == null) return false;
        long now = System.currentTimeMillis();
        if (now - last >= COOLDOWN_MS) return false;
        if (notify) {
            long remain = (COOLDOWN_MS - (now - last)) / 1000L;
            player.sendMessage(msg("Patiente encore " + formatTime(remain) + " avant un nouveau /rtp.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
        }
        return true;
    }

    /** Trouve une hauteur sure a (x,z), ou null si rien de sur. */
    private Integer findSafeY(World world, int x, int z) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            // Le nether a un plafond de bedrock : on scanne sous le plafond.
            for (int y = 120; y > 10; y--) {
                if (isSafe(world, x, y, z)) return y;
            }
            return null;
        }
        int top = world.getHighestBlockYAt(x, z);
        if (top <= world.getMinHeight()) return null;                  // colonne vide (void de l'End)
        if (world.getBlockAt(x, top, z).getType().isAir()) return null;
        return isSafe(world, x, top + 1, z) ? top + 1 : null;
    }

    /** Vrai si (x,y,z) est un endroit ou le joueur peut se tenir sans danger. */
    private boolean isSafe(World world, int x, int y, int z) {
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block ground = world.getBlockAt(x, y - 1, z);

        if (!ground.getType().isSolid()) return false;
        Material g = ground.getType();
        if (g == Material.LAVA || g == Material.WATER || g == Material.MAGMA_BLOCK
                || g == Material.CACTUS || g == Material.FIRE || g == Material.CAMPFIRE
                || g == Material.SOUL_CAMPFIRE || g == Material.POWDER_SNOW) return false;
        if (!feet.isPassable() || !head.isPassable()) return false;
        Material f = feet.getType();
        return f != Material.LAVA && f != Material.WATER && f != Material.FIRE;
    }

    private NamedTextColor colorFor(int secondsLeft) {
        if (secondsLeft <= 1) return NamedTextColor.GREEN;
        if (secondsLeft <= 3) return NamedTextColor.YELLOW;
        return NamedTextColor.AQUA;
    }

    private String prettyName(World.Environment env) {
        return switch (env) {
            case NETHER  -> "Nether";
            case THE_END -> "End";
            default      -> "Overworld";
        };
    }

    private String fmt(int n) {
        return String.format("%,d", n).replace(',', ' ');
    }

    private String formatTime(long seconds) {
        long m = seconds / 60, s = seconds % 60;
        if (m > 0) return m + " min " + s + " s";
        return s + " s";
    }

    private Component msg(String text, NamedTextColor color) {
        return Component.text(text).color(color);
    }
}
