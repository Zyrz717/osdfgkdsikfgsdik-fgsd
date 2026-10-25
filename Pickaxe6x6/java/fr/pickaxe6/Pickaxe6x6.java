package fr.pickaxe6;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pickaxe6x6 - fr/pickaxe6/Pickaxe6x6.java
 *
 * /givepickaxe6x6 [duree] [joueur]
 *   -> RESERVE a l'UUID de zyrx717 (voir ALLOWED). Permissions et OP ignores :
 *      tout le monde (et la console) recoit "Demande à zyrx717".
 *   -> sans duree (ou "perm") : pioche PERMANENTE ; avec duree (24h, 1d, 10d, 1d12h) : temporaire.
 *   -> pioche Netherite qui casse en 6x6 sur 2 couches de profondeur (72 blocs)
 *      (chaque bloc est casse "comme par le joueur" : protections, sac de minage, Fortune... respectes),
 *      son d'amethyste, incassable, Efficacite V,
 *      CONSERVEE a la mort (rendue au respawn, sauvegardee dans pickaxe6x6-keep.yml).
 */
public class Pickaxe6x6 extends LoadedPlugin implements Listener {

    // Seul ce joueur (par UUID) peut utiliser /givepickaxe6x6.
    private static final UUID ALLOWED = UUID.fromString("b024ff8c-d62e-4af8-b832-8184e0fab54b");

    // Format de duree accepte : 24h, 1d, 10d, 30m, 45s, ou combine (1d12h30m).
    private static final Pattern DURATION = Pattern.compile("(\\d+)([dhms])");

    private static final NamespacedKey KEY_PICK   = new NamespacedKey("pickaxe6x6", "pick");
    private static final NamespacedKey KEY_EXPIRE = new NamespacedKey("pickaxe6x6", "expire");

    private static final SimpleDateFormat FMT = new SimpleDateFormat("dd/MM/yyyy 'a' HH:mm");

    private BukkitTask purgeTask;

    // Joueurs en train de casser la zone (evite que nos propres cassages relancent le 6x6).
    private final Set<UUID> breaking = ConcurrentHashMap.newKeySet();

    // Pioches retirees des drops a la mort, rendues au respawn (par joueur).
    private final Map<UUID, List<ItemStack>> keptOnDeath = new ConcurrentHashMap<>();
    private File keepFile;

    // ===================== CYCLE DE VIE =====================

    @Override
    public void onEnable() {
        unregisterStaleListeners();
        registerListener(this);
        loadKept();

        registerCommand("givepickaxe6x6", (sender, cmd, label, args) -> {
            // Verrou PUR par UUID : aucune permission, aucun OP ne compte ici.
            if (!(sender instanceof Player p) || !p.getUniqueId().equals(ALLOWED)) {
                sender.sendMessage(msg("Demande à zyrx717", NamedTextColor.RED));
                return true;
            }
            long durationMs = 0L;   // 0 = permanente
            Player target = p;
            int i = 0;
            if (args.length > i) {
                if (args[i].equalsIgnoreCase("perm") || args[i].equalsIgnoreCase("permanent")) {
                    i++;
                } else {
                    long d = parseDuration(args[i]);
                    if (d > 0) { durationMs = d; i++; }
                }
            }
            if (args.length > i) {
                target = Bukkit.getPlayerExact(args[i]);
                if (target == null) {
                    sender.sendMessage(msg("Joueur introuvable : " + args[i] + "   Usage : /givepickaxe6x6 [duree|perm] [joueur]", NamedTextColor.RED));
                    return true;
                }
            }
            long expire = durationMs > 0 ? System.currentTimeMillis() + durationMs : 0L;
            giveItem(target, createPickaxe(expire, durationMs));
            String quoi = durationMs > 0
                    ? "pour " + formatDuration(durationMs) + " (expire le " + FMT.format(new Date(expire)) + ")"
                    : "permanente";
            target.sendMessage(msg("Tu as recu une Optaland Pickaxe 6x6 " + quoi + ".", NamedTextColor.GOLD));
            if (!p.equals(target)) {
                sender.sendMessage(msg("Pioche 6x6 donnee a " + target.getName() + " (" + quoi + ").", NamedTextColor.GREEN));
            }
            return true;
        });

        // Tache periodique : detruit les pioches expirees (toutes les 60s).
        try {
            purgeTask = Bukkit.getScheduler().runTaskTimer((Plugin) getHost(), this::purgeExpired, 20L * 60, 20L * 60);
        } catch (Throwable t) {
            getLogger().warning("[Pickaxe6x6] scheduler indisponible, purge auto desactivee (la verification au cassage reste active).");
        }

        getLogger().info("Pickaxe6x6 active - /givepickaxe6x6");
    }

    @Override
    public void onDisable() {
        if (purgeTask != null) { try { purgeTask.cancel(); } catch (Throwable ignored) {} }
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        getLogger().info("Pickaxe6x6 desactive.");
    }

    private void unregisterStaleListeners() {
        try {
            String myClass = getClass().getName();
            for (HandlerList hl : HandlerList.getHandlerLists()) {
                for (org.bukkit.plugin.RegisteredListener rl : hl.getRegisteredListeners()) {
                    Object l = rl.getListener();
                    if (l != null && l != this && l.getClass().getName().equals(myClass)) {
                        try { hl.unregister(rl); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    // ===================== CREATION =====================

    private ItemStack createPickaxe(long expire, long durationMs) {
        ItemStack is = new ItemStack(Material.NETHERITE_PICKAXE);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(gradientName("Optaland Pickaxe 6x6"));

            List<Component> lore = new ArrayList<>();
            lore.add(line("Casse en 6x6 sur 2 couches", NamedTextColor.YELLOW));
            if (expire > 0) {
                lore.add(line("Duree : " + formatDuration(durationMs), NamedTextColor.GRAY));
                lore.add(line("Expire le " + FMT.format(new Date(expire)), NamedTextColor.RED));
            } else {
                lore.add(line("Permanente", NamedTextColor.GREEN));
            }
            lore.add(Component.empty());
            lore.add(line("6X6", NamedTextColor.DARK_GRAY));
            meta.lore(lore);

            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            try { meta.addEnchant(Enchantment.EFFICIENCY, 5, true); } catch (Throwable ignored) {}

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(KEY_PICK, PersistentDataType.BYTE, (byte) 1);
            pdc.set(KEY_EXPIRE, PersistentDataType.LONG, expire);

            is.setItemMeta(meta);
        }
        return is;
    }

    // ===================== CASSAGE 6x6 + SON =====================

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (breaking.contains(p.getUniqueId())) return; // cassage lance par nous-memes
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!isOurPickaxe(tool)) return;

        long expire = getExpire(tool);
        if (expire > 0 && System.currentTimeMillis() > expire) {
            p.getInventory().setItemInMainHand(null);
            p.updateInventory();
            p.sendMessage(msg("Ta pioche 6x6 a expire.", NamedTextColor.RED));
            e.setCancelled(true);
            return;
        }

        p.getWorld().playSound(e.getBlock().getLocation(), Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1f, 1f);

        Block center = e.getBlock();
        BlockFace face = getFace(p);
        breaking.add(p.getUniqueId());
        try {
            for (Block b : area6x6(center, face)) {
                if (b.equals(center)) continue;
                if (!canBreak(b)) continue;
                boolean apiOk = true;
                try { p.breakBlock(b); } catch (Throwable t) { apiOk = false; }
                if (!apiOk) {
                    // repli (ancienne API) : on simule un cassage en verifiant les protections
                    try {
                        BlockBreakEvent ev = new BlockBreakEvent(b, p);
                        Bukkit.getPluginManager().callEvent(ev);
                        if (!ev.isCancelled() && canBreak(b)) b.breakNaturally(tool);
                    } catch (Throwable ignored) {}
                }
            }
        } finally {
            breaking.remove(p.getUniqueId());
        }
    }

    /**
     * Renvoie les 72 blocs de la zone 6x6 sur 2 couches de profondeur selon la face regardee.
     * 6 est pair : la zone va de -2 a +3 autour du bloc mine (vers le haut / la droite).
     * Couche 1 = celle du bloc mine, couche 2 = juste derriere (dans le sens du minage).
     */
    private List<Block> area6x6(Block center, BlockFace face) {
        List<Block> out = new ArrayList<>(72);
        boolean vertical = (face == BlockFace.UP || face == BlockFace.DOWN);
        boolean zAxis = (face == BlockFace.NORTH || face == BlockFace.SOUTH);
        BlockFace profondeur = face.getOppositeFace(); // on creuse vers l'interieur
        for (int couche = 0; couche < 2; couche++) {
            Block base = center.getRelative(profondeur, couche);
            for (int a = -2; a <= 3; a++) {
                for (int b = -2; b <= 3; b++) {
                    if (vertical)      out.add(base.getRelative(a, 0, b));   // plan horizontal
                    else if (zAxis)    out.add(base.getRelative(a, b, 0));   // plan X/Y
                    else               out.add(base.getRelative(0, b, a));   // plan Z/Y (EST/OUEST)
                }
            }
        }
        return out;
    }

    private boolean canBreak(Block b) {
        if (b.getType().isAir()) return false;
        if (b.isLiquid()) return false;
        // Ignore les blocs incassables (bedrock, barrier... hardness < 0).
        return b.getType().getHardness() >= 0f;
    }

    /** Determine la face visee par le joueur (pour orienter le 6x6). */
    private BlockFace getFace(Player p) {
        try {
            RayTraceResult rt = p.rayTraceBlocks(6.0);
            if (rt != null && rt.getHitBlockFace() != null) return rt.getHitBlockFace();
        } catch (Throwable ignored) {}
        // Repli : selon l'angle de vue.
        float pitch = p.getLocation().getPitch();
        if (pitch > 45f) return BlockFace.UP;      // regarde le sol -> plan horizontal
        if (pitch < -45f) return BlockFace.DOWN;
        return p.getFacing(); // NORTH/EAST/SOUTH/WEST
    }

    // ===================== CONSERVATION A LA MORT =====================

    /** A la mort : on retire nos pioches des drops et on les met de cote. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        try { if (e.getKeepInventory()) return; } catch (Throwable ignored) {}
        Player p = e.getEntity();
        List<ItemStack> kept = new ArrayList<>();
        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack d = it.next();
            if (isOurPickaxe(d)) {
                kept.add(d.clone());
                it.remove();
            }
        }
        if (kept.isEmpty()) return;
        keptOnDeath.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).addAll(kept);
        saveKept();
        p.sendMessage(msg("Ta pioche 6x6 est conservee : tu la retrouveras au respawn.", NamedTextColor.LIGHT_PURPLE));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        giveBackLater(e.getPlayer());
    }

    /** Si le joueur s'est deconnecte entre la mort et le respawn. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        giveBackLater(e.getPlayer());
    }

    private void giveBackLater(Player p) {
        if (!keptOnDeath.containsKey(p.getUniqueId())) return;
        try {
            Bukkit.getScheduler().runTaskLater((Plugin) getHost(), () -> giveBack(p), 2L);
        } catch (Throwable t) {
            giveBack(p);
        }
    }

    private void giveBack(Player p) {
        if (!p.isOnline() || p.isDead()) return; // encore mort : on attend le respawn
        List<ItemStack> list = keptOnDeath.remove(p.getUniqueId());
        if (list == null || list.isEmpty()) return;
        long now = System.currentTimeMillis();
        int given = 0;
        for (ItemStack is : list) {
            long ex = getExpire(is);
            if (ex > 0 && now > ex) continue; // expiree entre-temps : on ne la rend pas
            giveItem(p, is);
            given++;
        }
        saveKept();
        p.updateInventory();
        if (given > 0) {
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);
            p.sendMessage(msg("Ta pioche 6x6 t'a ete rendue.", NamedTextColor.LIGHT_PURPLE));
        }
    }

    private File keepFile() {
        if (keepFile == null) {
            try { keepFile = new File(((Plugin) getHost()).getDataFolder(), "pickaxe6x6-keep.yml"); }
            catch (Throwable t) { keepFile = new File("plugins/PluginLoader", "pickaxe6x6-keep.yml"); }
        }
        return keepFile;
    }

    private void saveKept() {
        try {
            File f = keepFile();
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            YamlConfiguration cfg = new YamlConfiguration();
            for (Map.Entry<UUID, List<ItemStack>> en : keptOnDeath.entrySet()) {
                if (en.getValue() != null && !en.getValue().isEmpty()) cfg.set(en.getKey().toString(), new ArrayList<>(en.getValue()));
            }
            cfg.save(f);
        } catch (Throwable t) {
            getLogger().warning("[Pickaxe6x6] sauvegarde des pioches conservees KO : " + t);
        }
    }

    private void loadKept() {
        keptOnDeath.clear();
        try {
            File f = keepFile();
            if (!f.exists()) return;
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            for (String key : cfg.getKeys(false)) {
                UUID u;
                try { u = UUID.fromString(key); } catch (IllegalArgumentException ex) { continue; }
                List<ItemStack> items = new ArrayList<>();
                for (Object o : cfg.getList(key, new ArrayList<>())) {
                    if (o instanceof ItemStack is && isOurPickaxe(is)) items.add(is);
                }
                if (!items.isEmpty()) keptOnDeath.put(u, items);
            }
            if (!keptOnDeath.isEmpty()) getLogger().info("[Pickaxe6x6] " + keptOnDeath.size() + " joueur(s) avec une pioche a rendre au respawn.");
        } catch (Throwable t) {
            getLogger().warning("[Pickaxe6x6] lecture des pioches conservees KO : " + t);
        }
    }

    // ===================== EXPIRATION =====================

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean removed = false;
            ItemStack[] contents = p.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack it = contents[i];
                if (isOurPickaxe(it)) {
                    long ex = getExpire(it);
                    if (ex > 0 && now > ex) {
                        p.getInventory().setItem(i, null);
                        removed = true;
                    }
                }
            }
            if (removed) {
                p.updateInventory();
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
                p.sendMessage(msg("Ta pioche 6x6 a expire et s'est detruite.", NamedTextColor.RED));
            }
        }
    }

    private boolean isOurPickaxe(ItemStack it) {
        if (it == null || it.getType() != Material.NETHERITE_PICKAXE) return false;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(KEY_PICK, PersistentDataType.BYTE);
    }

    private long getExpire(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return 0L;
        Long v = meta.getPersistentDataContainer().get(KEY_EXPIRE, PersistentDataType.LONG);
        return v == null ? 0L : v;
    }

    // ===================== UTILITAIRES =====================

    private void giveItem(Player p, ItemStack item) {
        for (ItemStack leftover : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), leftover);
        }
    }

    /** Parse une duree du type 24h, 1d, 10d, 30m, 45s ou combinee (1d12h30m). Renvoie des ms, ou -1 si invalide. */
    private long parseDuration(String s) {
        if (s == null || s.isEmpty()) return -1;
        s = s.toLowerCase();
        Matcher m = DURATION.matcher(s);
        long total = 0;
        int matchedEnd = 0;
        boolean found = false;
        while (m.find()) {
            found = true;
            long n;
            try { n = Long.parseLong(m.group(1)); } catch (NumberFormatException e) { return -1; }
            switch (m.group(2)) {
                case "d" -> total += n * 86_400_000L;
                case "h" -> total += n * 3_600_000L;
                case "m" -> total += n * 60_000L;
                case "s" -> total += n * 1_000L;
                default -> { return -1; }
            }
            matchedEnd = m.end();
        }
        // Refuse s'il reste des caracteres non reconnus (ex: "1d5x").
        if (!found || matchedEnd != s.length()) return -1;
        return total;
    }

    /** Affiche une duree lisible : 10j, 24h, 1j 12h, 30m... */
    private String formatDuration(long ms) {
        long s = ms / 1000;
        long d = s / 86400; s %= 86400;
        long h = s / 3600;  s %= 3600;
        long mi = s / 60;   s %= 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("j ");
        if (h > 0) sb.append(h).append("h ");
        if (mi > 0) sb.append(mi).append("m ");
        if (s > 0 && d == 0 && h == 0) sb.append(s).append("s ");
        String out = sb.toString().trim();
        return out.isEmpty() ? "0s" : out;
    }

    /** Nom en degrade jaune -> or fonce, en gras. */
    private Component gradientName(String text) {
        TextColor from = TextColor.color(0xFFE066); // jaune clair
        TextColor to   = TextColor.color(0xC8961E); // or fonce
        Component c = Component.empty();
        int len = text.length();
        for (int i = 0; i < len; i++) {
            float t = len <= 1 ? 0f : (float) i / (len - 1);
            TextColor col = TextColor.lerp(t, from, to);
            c = c.append(Component.text(String.valueOf(text.charAt(i)), col));
        }
        return c.decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false);
    }

    static final Component PREFIX = Component.text("Pioche ", NamedTextColor.GOLD, TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_GRAY));

    private Component msg(String text, NamedTextColor color) {
        return PREFIX.append(Component.text(text, color));
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
