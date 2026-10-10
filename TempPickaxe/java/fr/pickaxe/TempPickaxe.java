package fr.pickaxe;

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
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TempPickaxe - a mettre dans : fr/pickaxe/TempPickaxe.java
 *
 * /givepickaxe <duree> [joueur]
 *   -> RESERVE a un seul UUID (voir ALLOWED). Les permissions et le statut OP sont
 *      TOTALEMENT ignores : meme un joueur OP ou avec la permission "*" ne peut PAS
 *      l'utiliser. Seul l'UUID autorise passe.
 *   -> Toute autre personne (et la console) recoit le message ""Demande à zyrx717"".
 *   -> donne une pioche Netherite TEMPORAIRE qui :
 *        - casse en 3x3 (dans le plan de la face minee)
 *        - joue un son d'amethyste a chaque cassage
 *        - s'auto-detruit apres la duree choisie (heure d'expiration ecrite dans le lore)
 */
public class TempPickaxe extends LoadedPlugin implements Listener {

    // Seul ce joueur (par UUID) peut utiliser /givepickaxe.
    private static final UUID ALLOWED = UUID.fromString("b024ff8c-d62e-4af8-b832-8184e0fab54b");

    // Format de duree accepte : 24h, 1d, 10d, 30m, 45s, ou combine (1d12h30m).
    private static final Pattern DURATION = Pattern.compile("(\\d+)([dhms])");

    private static final NamespacedKey KEY_PICK   = new NamespacedKey("temppickaxe", "temp");
    private static final NamespacedKey KEY_EXPIRE = new NamespacedKey("temppickaxe", "expire");

    private static final SimpleDateFormat FMT = new SimpleDateFormat("dd/MM/yyyy 'a' HH:mm");

    private BukkitTask purgeTask;

    // ===================== CYCLE DE VIE =====================

    @Override
    public void onEnable() {
        unregisterStaleListeners();
        registerListener(this);

        registerCommand("givepickaxe", (sender, cmd, label, args) -> {
            // Verrou PUR par UUID : aucune permission, aucun OP ne compte ici.
            // Seul l'UUID ALLOWED passe. Tout le reste (joueurs, OP, console) -> ""Demande à zyrx717"".
            if (!(sender instanceof Player p) || !p.getUniqueId().equals(ALLOWED)) {
                sender.sendMessage(msg("Demande à zyrx717", NamedTextColor.RED));
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage(msg("Usage : /givepickaxe <duree> [joueur]  (ex: 24h, 1d, 10d, 1d12h)", NamedTextColor.GRAY));
                return true;
            }
            long durationMs = parseDuration(args[0]);
            if (durationMs <= 0) {
                sender.sendMessage(msg("Duree invalide : " + args[0] + "  (ex: 24h, 1d, 10d, 1d12h)", NamedTextColor.RED));
                return true;
            }
            Player target;
            if (args.length >= 2) {
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(msg("Joueur introuvable : " + args[1], NamedTextColor.RED));
                    return true;
                }
            } else {
                target = p;
            }

            long expire = System.currentTimeMillis() + durationMs;
            giveItem(target, createPickaxe(expire, durationMs));
            target.sendMessage(msg("Tu as recu une Optaland Pickaxe (" + formatDuration(durationMs) + "). Elle expire le " + FMT.format(new Date(expire)) + ".", NamedTextColor.LIGHT_PURPLE));
            if (!p.equals(target)) {
                sender.sendMessage(msg("Pioche donnee a " + target.getName() + " pour " + formatDuration(durationMs) + ".", NamedTextColor.GREEN));
            }
            return true;
        });

        // Tache periodique : detruit les pioches expirees (toutes les 60s).
        try {
            purgeTask = Bukkit.getScheduler().runTaskTimer((Plugin) getHost(), this::purgeExpired, 20L * 60, 20L * 60);
        } catch (Throwable t) {
            getLogger().warning("[TempPickaxe] scheduler indisponible, purge auto desactivee (la verification au cassage reste active).");
        }

        getLogger().info("TempPickaxe active.");
    }

    @Override
    public void onDisable() {
        if (purgeTask != null) { try { purgeTask.cancel(); } catch (Throwable ignored) {} }
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        getLogger().info("TempPickaxe desactive.");
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
            meta.displayName(gradientName("Optaland Pickaxe"));

            List<Component> lore = new ArrayList<>();
            lore.add(line("Casse en 3x3", NamedTextColor.AQUA));
            lore.add(line("Duree : " + formatDuration(durationMs), NamedTextColor.GRAY));
            lore.add(line("Expire le " + FMT.format(new Date(expire)), NamedTextColor.RED));
            lore.add(Component.empty());
            lore.add(line("TEMP", NamedTextColor.DARK_GRAY));
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

    // ===================== CASSAGE 3x3 + SON =====================

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!isOurPickaxe(tool)) return;

        // Expiree ? On la detruit et on annule le cassage.
        long expire = getExpire(tool);
        if (expire > 0 && System.currentTimeMillis() > expire) {
            p.getInventory().setItemInMainHand(null);
            p.updateInventory();
            p.sendMessage(msg("Ta pioche temporaire a expire.", NamedTextColor.RED));
            e.setCancelled(true);
            return;
        }

        // Son d'amethyste.
        p.getWorld().playSound(e.getBlock().getLocation(), Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1f, 1f);

        // Casse le 3x3 autour du bloc mine (le bloc central est deja gere par l'event).
        Block center = e.getBlock();
        BlockFace face = getFace(p);
        for (Block b : area3x3(center, face)) {
            if (b.equals(center)) continue;
            if (canBreak(b)) b.breakNaturally(tool);
        }
    }

    /** Renvoie les 9 blocs du plan 3x3 selon la face regardee. */
    private List<Block> area3x3(Block center, BlockFace face) {
        List<Block> out = new ArrayList<>(9);
        boolean vertical = (face == BlockFace.UP || face == BlockFace.DOWN);
        boolean zAxis = (face == BlockFace.NORTH || face == BlockFace.SOUTH);
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (vertical)      out.add(center.getRelative(a, 0, b));   // plan horizontal
                else if (zAxis)    out.add(center.getRelative(a, b, 0));   // plan X/Y
                else               out.add(center.getRelative(0, b, a));   // plan Z/Y (EST/OUEST)
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

    /** Determine la face visee par le joueur (pour orienter le 3x3). */
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
                p.sendMessage(msg("Ta pioche temporaire a expire et s'est detruite.", NamedTextColor.RED));
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

    /** Nom en degrade violet (clair -> fonce), en gras. */
    private Component gradientName(String text) {
        TextColor from = TextColor.color(0xC77DFF); // violet clair
        TextColor to   = TextColor.color(0x7B2CBF); // violet fonce
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
