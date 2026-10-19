package fr.dailyrewards;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * DailyRewards - recompenses de connexion (/daily).
 *
 *  - 32 jours de recompenses (config: days), affiches par semaine de 7, 1 recompense toutes les 24h (cooldown-hours)
 *  - serie perdue (retour au jour 1) apres reset-hours sans reclamer (48h)
 *  - menu 9x3 texture ItemsAdder (optaland_daily:daily_gui) + icones cadeaux
 *  - recompenses = commandes console dans plugins/PluginLoader/dailyrewards/config.yml
 *  - a la connexion : rappel + ouverture automatique du menu (open-on-join)
 *
 *  Commandes : /daily (alias /recompense /recompenses /journalier)
 *              /daily reset <joueur>   (dailyrewards.admin)
 *              /daily reload           (dailyrewards.admin)
 */
public class DailyRewards extends LoadedPlugin implements Listener {

    private static final String FONT_IMAGE = "optaland_daily:daily_gui32";
    private static final int TITLE_OFFSET = -8; // le titre commence a x=8 dans la GUI
    private static final String ITEM_GIFT = "optaland_daily:gift";
    private static final String ITEM_OPEN = "optaland_daily:gift_open";
    private static final String ITEM_LOCK = "optaland_daily:gift_locked";

    private static final String[] LABELS = {"daily", "recompense", "recompenses", "journalier"};
    private static final int[] DAY_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int SLOT_INFO = 9, SLOT_CLOSE = 17;
    private static final long HOUR = 3600_000L;

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private static final class Data {
        long last;          // date de la derniere recompense (ms), 0 = jamais
        int day = 1;        // prochain jour a reclamer (1..7)
        int total;          // recompenses recuperees en tout
        boolean cycleDone;  // jour 7 recupere, nouvelle serie au prochain cooldown
    }

    private File dataFolder, configFile, playersFile;
    private YamlConfiguration config;
    private final Map<UUID, Data> players = new HashMap<>();
    private final Map<String, ItemStack> iaItems = new HashMap<>();
    private String cachedTitle;

    private int maxDays = 32;          // nombre de jours de la serie (config: days)
    private long cooldownMs = 24 * HOUR;
    private long resetMs = 48 * HOUR;
    private String prefix = "";
    private String openOnJoin = "always";      // always | available | never
    private long openDelayTicks = 12 * 20L;    // delai max si le pack de textures tarde
    private final Set<UUID> pendingOpen = new HashSet<>();

    // ===================== cycle de vie =====================

    @Override
    public void onEnable() {
        unregisterStaleListeners(); // listeners fantomes d'une ancienne instance (apres /plreload)
        dataFolder = new File(getHost().getDataFolder(), "dailyrewards");
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            getLogger().warning("[DailyRewards] impossible de creer " + dataFolder);
        }
        configFile = new File(dataFolder, "config.yml");
        playersFile = new File(dataFolder, "players.yml");
        loadConfig();
        loadPlayers();

        try {
            Bukkit.getPluginManager().registerEvents(this, getHost());
        } catch (Throwable t) {
            getLogger().severe("[DailyRewards] listener impossible a enregistrer : " + t);
        }
        for (String label : LABELS) registerCommand(label, this::handleCommand);
        // CORRECTIF : apres /plreload, le label /daily reste branche sur l'ANCIENNE instance
        // (commandMap.register ne remplace pas un label deja pris) -> son menu n'est pas
        // reconnu par le nouveau listener et les items sont prenables. On reprend les labels.
        forceClaimLabels();
        try {
            Bukkit.getScheduler().runTaskLater((Plugin) getHost(), this::forceClaimLabels, 20L);
        } catch (Throwable ignored) {}
        getLogger().info("[DailyRewards] actif - /daily (cooldown " + (cooldownMs / HOUR) + "h, reset " + (resetMs / HOUR) + "h, ouverture a la connexion : " + openOnJoin + ").");
    }

    @Override
    public void onDisable() {
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        pendingOpen.clear();
        savePlayers();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory();
        }
    }

    /** Retire les listeners d'une ancienne instance de cette classe (reload). */
    private void unregisterStaleListeners() {
        try {
            String myClass = getClass().getName();
            for (HandlerList hl : HandlerList.getHandlerLists()) {
                for (RegisteredListener rl : hl.getRegisteredListeners()) {
                    Object l = rl.getListener();
                    if (l != null && l != this && l.getClass().getName().equals(myClass)) {
                        try { hl.unregister(rl); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Branche /daily (et alias) sur NOS commandes meme si une ancienne instance les tenait. */
    @SuppressWarnings("unchecked")
    private void forceClaimLabels() {
        try {
            Object server = Bukkit.getServer();
            Object map = server.getClass().getMethod("getCommandMap").invoke(server);
            Method getCommand = map.getClass().getMethod("getCommand", String.class);
            Field field = null;
            for (Class<?> c = map.getClass(); c != null && field == null; c = c.getSuperclass()) {
                try { field = c.getDeclaredField("knownCommands"); } catch (NoSuchFieldException ignored) {}
            }
            if (field == null) { getLogger().warning("[DailyRewards] knownCommands introuvable."); return; }
            field.setAccessible(true);
            Map<String, Object> known = (Map<String, Object>) field.get(map);
            String prefix = getName().toLowerCase(Locale.ROOT) + ":";
            boolean changed = false;
            for (String label : LABELS) {
                Object ours = getCommand.invoke(map, prefix + label);
                if (ours == null) ours = known.get(prefix + label);
                if (ours == null) {
                    for (Map.Entry<String, Object> en : known.entrySet()) {
                        if (en.getKey().endsWith(":" + label) && en.getValue() != null
                                && en.getValue().getClass().getName().startsWith("fr.loader.api.LoadedPlugin")) {
                            ours = en.getValue();
                            break;
                        }
                    }
                }
                if (ours == null) continue; // le label nous appartient deja directement
                Object current = known.get(label);
                if (current != ours) {
                    known.put(label, ours);
                    changed = true;
                    getLogger().info("[DailyRewards] /" + label + " etait pris par "
                            + (current == null ? "personne" : current.getClass().getName()) + " -> repris.");
                }
            }
            if (changed) {
                try { server.getClass().getMethod("syncCommands").invoke(server); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            getLogger().warning("[DailyRewards] reprise des labels impossible : " + t);
        }
    }

    /** Menu ouvert par une ANCIENNE instance du plugin (classe differente apres rechargement). */
    private static boolean isStaleMenu(InventoryHolder h) {
        return h != null && !(h instanceof Holder) && h.getClass().getName().endsWith("DailyRewards$Holder");
    }

    // ===================== commandes =====================

    private boolean handleCommand(CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (args.length >= 1) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("reload")) {
                if (!sender.hasPermission("dailyrewards.admin")) { send(sender, "&cTu n'as pas la permission."); return true; }
                loadConfig();
                cachedTitle = null;
                iaItems.clear();
                send(sender, "&aConfiguration rechargee.");
                return true;
            }
            if (sub.equals("reset")) {
                if (!sender.hasPermission("dailyrewards.admin")) { send(sender, "&cTu n'as pas la permission."); return true; }
                if (args.length < 2) { send(sender, "&cUsage : /" + label + " reset <joueur>"); return true; }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                players.remove(target.getUniqueId());
                savePlayers();
                send(sender, "&aS\u00e9rie de &e" + (target.getName() != null ? target.getName() : args[1]) + "&a remise \u00e0 z\u00e9ro.");
                return true;
            }
            if (sub.equals("info") && args.length >= 2 && sender.hasPermission("dailyrewards.admin")) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                Data d = players.get(target.getUniqueId());
                if (d == null) { send(sender, "&7Aucune donn\u00e9e pour &e" + args[1] + "&7."); return true; }
                send(sender, "&e" + args[1] + " &7: prochain jour &e" + d.day + "&7, total &e" + d.total
                        + "&7, derni\u00e8re il y a &e" + (d.last == 0 ? "jamais" : fmt(System.currentTimeMillis() - d.last)) + "&7.");
                return true;
            }
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Joueurs uniquement (ou /daily reset <joueur>)."); return true; }
        open(p);
        return true;
    }

    // ===================== logique =====================

    private Data data(UUID u) {
        return players.computeIfAbsent(u, k -> new Data());
    }

    private boolean claimable(Data d, long now) {
        return d.last == 0 || now - d.last >= cooldownMs;
    }

    /** Applique la perte de serie / fin de cycle. Retourne true si la serie vient d'etre perdue. */
    private boolean normalize(Data d, long now) {
        boolean lost = false;
        // migration ancienne serie de 7 jours : un joueur qui avait fini les 7 jours
        // continue au jour suivant (8, 15...) au lieu de voir toute la serie terminee
        if (d.cycleDone && d.total > 0 && d.total < maxDays) {
            d.day = d.total + 1;
            d.cycleDone = false;
        }
        if (d.last > 0 && now - d.last >= resetMs && (d.day > 1 || d.cycleDone)) {
            lost = d.day > 1;          // un cycle termine qui expire n'est pas une "perte"
            d.day = 1;
            d.cycleDone = false;
        }
        if (d.cycleDone && claimable(d, now)) {
            d.cycleDone = false;       // nouvelle serie : le jour 1 redevient disponible
        }
        return lost;
    }

    private void tryClaim(Player p, int clicked) {
        long now = System.currentTimeMillis();
        Data d = data(p.getUniqueId());
        if (normalize(d, now)) send(p, msg("reset").replace("{hours}", String.valueOf(resetMs / HOUR)));

        if (d.cycleDone || clicked < d.day) {
            send(p, msg("already"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f);
            return;
        }
        if (clicked > d.day) {
            send(p, msg("locked").replace("{day}", String.valueOf(d.day)));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f);
            return;
        }
        if (!claimable(d, now)) {
            send(p, msg("wait").replace("{time}", fmt(cooldownMs - (now - d.last))));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f);
            return;
        }

        int day = d.day;
        for (String c : rewardCommands(day)) {
            String run = c.replace("{player}", p.getName()).replace("{uuid}", p.getUniqueId().toString());
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), run);
            } catch (Throwable t) {
                getLogger().warning("[DailyRewards] commande KO '" + run + "' : " + t);
            }
        }
        d.last = now;
        d.total++;
        if (day >= maxDays) { d.day = 1; d.cycleDone = true; } else { d.day = day + 1; }
        savePlayers();

        send(p, msg("claimed").replace("{day}", String.valueOf(day)).replace("{reward}", rewardName(day)));
        if (day >= maxDays) send(p, msg("cycle"));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        getLogger().info("[DailyRewards] " + p.getName() + " a recupere le jour " + day + " (" + stripColors(rewardName(day)) + ").");
        open(p);
    }

    // ===================== menu =====================

    private void open(Player p) {
        long now = System.currentTimeMillis();
        Data d = data(p.getUniqueId());
        if (normalize(d, now)) send(p, msg("reset").replace("{hours}", String.valueOf(resetMs / HOUR)));
        boolean can = claimable(d, now);
        String remaining = can ? "" : fmt(cooldownMs - (now - d.last));

        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, 27, titleComponent());
        h.inv = inv;

        int base = pageBase(d);
        for (int k = 1; k <= DAY_SLOTS.length; k++) {
            int day = base + k;
            if (day > maxDays) break;
            List<Component> lore = new ArrayList<>();
            lore.add(legacy("&7R\u00e9compense : " + rewardName(day)));
            lore.add(Component.empty());
            ItemStack it;
            String name;
            if (d.cycleDone || day < d.day) {
                it = iaItem(ITEM_OPEN, Material.LIME_DYE);
                name = "&aJour " + day + " &8\u00bb &7R\u00e9cup\u00e9r\u00e9 \u2714";
                lore.add(legacy("&aD\u00e9j\u00e0 r\u00e9cup\u00e9r\u00e9e."));
                if (d.cycleDone && day == 1) lore.add(legacy("&7Nouvelle s\u00e9rie dans &e" + remaining + "&7."));
            } else if (day == d.day) {
                it = iaItem(ITEM_GIFT, Material.CHEST);
                if (can) {
                    name = "&6Jour " + day + " &8\u00bb &a&lDISPONIBLE !";
                    lore.add(legacy("&eClique pour r\u00e9cup\u00e9rer !"));
                } else {
                    name = "&6Jour " + day + " &8\u00bb &eBient\u00f4t";
                    lore.add(legacy("&7Disponible dans &e" + remaining + "&7."));
                }
            } else {
                it = iaItem(ITEM_LOCK, Material.GRAY_DYE);
                name = "&7Jour " + day + " &8\u00bb &cVerrouill\u00e9";
                lore.add(legacy("&8Reviens chaque jour pour avancer."));
            }
            ItemMeta m = it.getItemMeta();
            m.displayName(legacy(name));
            m.lore(lore);
            if (day == d.day && can && !d.cycleDone) {
                try { m.setEnchantmentGlintOverride(true); } catch (Throwable ignored) {}
            }
            it.setItemMeta(m);
            it.setAmount(Math.max(1, Math.min(64, day)));
            inv.setItem(DAY_SLOTS[k - 1], it);
        }

        // info
        int done = d.cycleDone ? maxDays : d.day - 1;
        ItemStack info = new ItemStack(Material.CLOCK);
        ItemMeta im = info.getItemMeta();
        im.displayName(legacy("&6&lTa s\u00e9rie"));
        List<Component> il = new ArrayList<>();
        il.add(legacy("&7Progression : &e" + done + "&7/&e" + maxDays));
        il.add(legacy("&7Semaine : &e" + (pageBase(d) / DAY_SLOTS.length + 1) + "&7/&e" + ((maxDays + DAY_SLOTS.length - 1) / DAY_SLOTS.length)));
        il.add(legacy("&7R\u00e9compenses r\u00e9cup\u00e9r\u00e9es : &e" + d.total));
        il.add(legacy("&7Prochaine : " + (can ? "&aMaintenant !" : "&e" + remaining)));
        il.add(Component.empty());
        il.add(legacy("&81 r\u00e9compense toutes les " + (cooldownMs / HOUR) + "h"));
        il.add(legacy("&8S\u00e9rie perdue apr\u00e8s " + (resetMs / HOUR) + "h sans r\u00e9clamer"));
        im.lore(il);
        info.setItemMeta(im);
        inv.setItem(SLOT_INFO, info);

        // fermer
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta cm = close.getItemMeta();
        cm.displayName(legacy("&cFermer"));
        close.setItemMeta(cm);
        inv.setItem(SLOT_CLOSE, close);

        p.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (isStaleMenu(h)) {
            // menu d'une ancienne instance : on bloque et on rouvre le menu a jour
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) {
                p.closeInventory();
                Bukkit.getScheduler().runTask((Plugin) getHost(), () -> open(p));
            }
            return;
        }
        if (!(h instanceof Holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (slot == SLOT_CLOSE) { p.closeInventory(); return; }
        for (int i = 0; i < DAY_SLOTS.length; i++) {
            if (DAY_SLOTS[i] == slot) { tryClaim(p, pageBase(data(p.getUniqueId())) + i + 1); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (h instanceof Holder || isStaleMenu(h)) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        pendingOpen.add(p.getUniqueId());
        // ouverture des que le pack de textures est charge (voir onPackStatus), sinon au plus tard apres le delai
        Bukkit.getScheduler().runTaskLater((Plugin) getHost(), () -> autoOpen(p), openDelayTicks);
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent e) {
        if (e.getStatus() != PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) return;
        Player p = e.getPlayer();
        if (!pendingOpen.contains(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater((Plugin) getHost(), () -> autoOpen(p), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pendingOpen.remove(e.getPlayer().getUniqueId());
    }

    /** Rappel + ouverture automatique (une seule fois par connexion). */
    private void autoOpen(Player p) {
        UUID u = p.getUniqueId();
        if (!p.isOnline()) { pendingOpen.remove(u); return; }
        if (!pendingOpen.remove(u)) return; // deja fait pour cette connexion
        long now = System.currentTimeMillis();
        Data d = data(u);
        normalize(d, now);
        boolean can = claimable(d, now);
        if (can) {
            send(p, msg("reminder"));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
        }
        boolean doOpen = openOnJoin.equals("always") || (openOnJoin.equals("available") && can);
        if (doOpen && !(p.getOpenInventory().getTopInventory().getHolder() instanceof Holder)) open(p);
    }

    // ===================== config =====================

    private static final String DEFAULT_CONFIG = String.join("\n",
            "# ============================================================",
            "#  DailyRewards - recompenses de connexion (/daily)",
            "#  cooldown-hours : delai entre deux recompenses",
            "#  reset-hours    : sans reclamer pendant ce delai, la serie repart au jour 1",
            "#  rewards.<jour>.commands : commandes console, {player} = pseudo",
            "#  Apres modification : /daily reload",
            "# ============================================================",
            "cooldown-hours: 24",
            "reset-hours: 48",
            "# Ouverture automatique du menu a la connexion : always (toujours), available (seulement si une recompense est dispo), never",
            "open-on-join: always",
            "# Delai max (secondes) avant l'ouverture si le pack de textures n'a pas fini de charger",
            "open-on-join-delay-seconds: 12",
            "prefix: \"&6&lR\u00c9COMPENSES &8\u00bb &7\"",
            "messages:",
            "  claimed: \"Jour {day} r\u00e9cup\u00e9r\u00e9 : {reward}&7 !\"",
            "  cycle: \"&6S\u00e9rie compl\u00e8te ! &7Une nouvelle s\u00e9rie commence demain.\"",
            "  wait: \"Prochaine r\u00e9compense dans &e{time}&7.\"",
            "  already: \"Tu as d\u00e9j\u00e0 r\u00e9cup\u00e9r\u00e9 cette r\u00e9compense.\"",
            "  locked: \"R\u00e9cup\u00e8re d'abord le &ejour {day}&7 !\"",
            "  reminder: \"Ta r\u00e9compense du jour est disponible ! &eFais /daily\"",
            "  reset: \"Plus de {hours}h sans r\u00e9clamer : ta s\u00e9rie repart au &ejour 1&7.\"",
            "rewards:",
            "  1:",
            "    name: \"&a500$\"",
            "    commands:",
            "      - \"eco give {player} 500\"",
            "  2:",
            "    name: \"&a1 000$\"",
            "    commands:",
            "      - \"eco give {player} 1000\"",
            "  3:",
            "    name: \"&f1 cl\u00e9 Commune\"",
            "    commands:",
            "      - \"crate give physical Commune 1 {player}\"",
            "  4:",
            "    name: \"&a2 000$\"",
            "    commands:",
            "      - \"eco give {player} 2000\"",
            "  5:",
            "    name: \"&d100 Tokens\"",
            "    commands:",
            "      - \"token give {player} 100\"",
            "  6:",
            "    name: \"&b1 cl\u00e9 Rare\"",
            "    commands:",
            "      - \"crate give physical Rare 1 {player}\"",
            "  7:",
            "    name: \"&a5 000$ &7+ &51 cl\u00e9 \u00c9pique\"",
            "    commands:",
            "      - \"eco give {player} 5000\"",
            "      - \"crate give physical Epique 1 {player}\"",
            "");

    private void loadConfig() {
        try {
            if (!configFile.exists()) {
                Files.writeString(configFile.toPath(), DEFAULT_CONFIG, StandardCharsets.UTF_8);
                getLogger().info("[DailyRewards] config.yml cree dans " + configFile.getParentFile().getName() + "/");
            }
            config = YamlConfiguration.loadConfiguration(configFile);
        } catch (Throwable t) {
            getLogger().warning("[DailyRewards] config.yml illisible (" + t.getMessage() + ") -> valeurs par defaut.");
            config = new YamlConfiguration();
            try { config.loadFromString(DEFAULT_CONFIG); } catch (Throwable ignored) {}
        }
        maxDays = Math.max(1, config.getInt("days", 32));
        cooldownMs = Math.max(1, (long) (config.getDouble("cooldown-hours", 24) * HOUR));
        resetMs = Math.max(cooldownMs, (long) (config.getDouble("reset-hours", 48) * HOUR));
        prefix = config.getString("prefix", "&6&lR\u00c9COMPENSES &8\u00bb &7");
        openOnJoin = config.getString("open-on-join", "always").toLowerCase(Locale.ROOT).trim();
        if (openOnJoin.equals("true")) openOnJoin = "always";
        if (openOnJoin.equals("false")) openOnJoin = "never";
        openDelayTicks = Math.max(20L, (long) (config.getDouble("open-on-join-delay-seconds", 12) * 20));
    }

    private String msg(String key) {
        String def;
        switch (key) {
            case "claimed":  def = "Jour {day} r\u00e9cup\u00e9r\u00e9 : {reward}&7 !"; break;
            case "cycle":    def = "&6S\u00e9rie compl\u00e8te ! &7Une nouvelle s\u00e9rie commence demain."; break;
            case "wait":     def = "Prochaine r\u00e9compense dans &e{time}&7."; break;
            case "already":  def = "Tu as d\u00e9j\u00e0 r\u00e9cup\u00e9r\u00e9 cette r\u00e9compense."; break;
            case "locked":   def = "R\u00e9cup\u00e8re d'abord le &ejour {day}&7 !"; break;
            case "reminder": def = "Ta r\u00e9compense du jour est disponible ! &eFais /daily"; break;
            case "reset":    def = "Plus de {hours}h sans r\u00e9clamer : ta s\u00e9rie repart au &ejour 1&7."; break;
            default:         def = key;
        }
        return config.getString("messages." + key, def);
    }

    private String rewardName(int day) {
        return config.getString("rewards." + day + ".name", "&7Jour " + day);
    }

    private List<String> rewardCommands(int day) {
        List<String> l = config.getStringList("rewards." + day + ".commands");
        if (l.isEmpty()) {
            ConfigurationSection s = config.getConfigurationSection("rewards." + day);
            if (s == null) getLogger().warning("[DailyRewards] aucune recompense configuree pour le jour " + day);
        }
        return l;
    }

    // ===================== sauvegarde joueurs =====================

    private void loadPlayers() {
        players.clear();
        if (!playersFile.exists()) return;
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(playersFile);
            for (String key : y.getKeys(false)) {
                try {
                    Data d = new Data();
                    d.last = y.getLong(key + ".last", 0L);
                    d.day = Math.min(7, Math.max(1, y.getInt(key + ".day", 1)));
                    d.total = y.getInt(key + ".total", 0);
                    d.cycleDone = y.getBoolean(key + ".cycle-done", false);
                    players.put(UUID.fromString(key), d);
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (Throwable t) {
            getLogger().warning("[DailyRewards] players.yml illisible : " + t.getMessage());
        }
    }

    private void savePlayers() {
        try {
            YamlConfiguration y = new YamlConfiguration();
            for (Map.Entry<UUID, Data> en : players.entrySet()) {
                String k = en.getKey().toString();
                Data d = en.getValue();
                y.set(k + ".last", d.last);
                y.set(k + ".day", d.day);
                y.set(k + ".total", d.total);
                y.set(k + ".cycle-done", d.cycleDone);
            }
            y.save(playersFile);
        } catch (Throwable t) {
            getLogger().warning("[DailyRewards] sauvegarde players.yml KO : " + t);
        }
    }

    // ===================== ItemsAdder (reflexion) =====================

    private ClassLoader iaLoader() {
        Plugin ia = Bukkit.getPluginManager().getPlugin("ItemsAdder");
        return ia != null ? ia.getClass().getClassLoader() : getClass().getClassLoader();
    }

    private Component titleComponent() {
        if (cachedTitle == null) cachedTitle = buildTitle();
        if (cachedTitle.isEmpty()) return Component.text("R\u00e9compenses journali\u00e8res", NamedTextColor.DARK_GRAY);
        return Component.text(cachedTitle, NamedTextColor.WHITE);
    }

    private String buildTitle() {
        try {
            Class<?> fiw = Class.forName("dev.lone.itemsadder.api.FontImages.FontImageWrapper", true, iaLoader());
            Object wrapper = fiw.getConstructor(String.class).newInstance(FONT_IMAGE);
            Object exists = null;
            try { exists = fiw.getMethod("exists").invoke(wrapper); } catch (NoSuchMethodException ignored) {}
            if (exists == null || Boolean.TRUE.equals(exists)) {
                String img = (String) fiw.getMethod("getString").invoke(wrapper);
                if (img != null && !img.isEmpty()) {
                    String offset = "";
                    try {
                        offset = (String) fiw.getMethod("applyPixelsOffset", String.class, int.class).invoke(null, "", TITLE_OFFSET);
                    } catch (Throwable ignored) {}
                    if (offset == null || offset.isEmpty()) offset = papi("%img_offset_" + TITLE_OFFSET + "%");
                    getLogger().info("[DailyRewards] titre texture via l'API ItemsAdder.");
                    return offset + img;
                }
            }
        } catch (Throwable t) {
            getLogger().info("[DailyRewards] API ItemsAdder indisponible (" + t.getClass().getSimpleName() + "), essai PlaceholderAPI.");
        }
        for (String name : new String[]{"%img_" + FONT_IMAGE + "%", "%img_daily_gui%"}) {
            String s = papi("%img_offset_" + TITLE_OFFSET + "%" + name);
            if (s != null && !s.isEmpty() && !s.contains("%")) {
                getLogger().info("[DailyRewards] titre texture via PlaceholderAPI.");
                return s;
            }
        }
        getLogger().warning("[DailyRewards] texture " + FONT_IMAGE + " introuvable (contents/optaland_daily + /iazip ?) -> titre texte.");
        return "";
    }

    private String papi(String text) {
        try {
            Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (papi == null) return "";
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, papi.getClass().getClassLoader());
            Object out = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class).invoke(null, null, text);
            return out == null ? "" : out.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Item ItemsAdder (namespace:id), repli : item vanilla. */
    /** premier jour (-1) de la page de 7 affichee : la semaine du prochain jour a reclamer */
    private int pageBase(Data d) {
        int idx = d.cycleDone ? 0 : Math.min(maxDays, Math.max(1, d.day)) - 1;
        return (idx / DAY_SLOTS.length) * DAY_SLOTS.length;
    }

    private ItemStack iaItem(String id, Material fallback) {
        ItemStack cached = iaItems.get(id);
        if (cached == null) {
            try {
                Class<?> cs = Class.forName("dev.lone.itemsadder.api.CustomStack", true, iaLoader());
                Object stack = cs.getMethod("getInstance", String.class).invoke(null, id);
                if (stack != null) {
                    Object is = stack.getClass().getMethod("getItemStack").invoke(stack);
                    if (is instanceof ItemStack it) cached = it.clone();
                }
            } catch (Throwable ignored) {}
            if (cached == null) {
                getLogger().warning("[DailyRewards] item " + id + " introuvable -> " + fallback.name().toLowerCase(Locale.ROOT) + ".");
                cached = new ItemStack(fallback);
            }
            iaItems.put(id, cached);
        }
        return cached.clone();
    }

    // ===================== utilitaires =====================

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private static Component legacy(String s) {
        return LEGACY.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private void send(CommandSender to, String s) {
        to.sendMessage(LEGACY.deserialize(prefix + s));
    }

    private static String stripColors(String s) {
        return s.replaceAll("(?i)&[0-9a-fk-or]", "");
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + "h " + String.format(Locale.ROOT, "%02d", m) + "min";
        if (m > 0) return m + "min " + String.format(Locale.ROOT, "%02d", sec) + "s";
        return sec + "s";
    }
}
