package fr.trade;

import fr.loader.api.LoadedPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TradePlugin - echange securise entre deux joueurs (/trade).
 *
 *  /trade <joueur>        envoie une demande (clic sur [Accepter] / [Refuser] dans le chat)
 *  /trade accept [joueur] accepte la demande recue
 *  /trade deny            refuse la demande recue
 *  /trade cancel          annule l'echange en cours
 *
 *  Menu 9x6 texture ItemsAdder (optaland_trade:trade_gui) :
 *   - clic sur un item de SON inventaire -> ajoute a son offre (clic droit = 1 seul)
 *   - clic sur un item de son offre      -> le reprend (clic droit = 1 seul)
 *   - bouton argent -> montant tape dans le chat (Vault)
 *   - CONFIRMER des deux cotes -> compte a rebours puis echange
 *   - toute modification annule les confirmations ; fermer le menu = annuler
 *   - sauvegarde anti-crash des items offerts (pending.yml), rendus a la reconnexion
 */
public class TradePlugin extends LoadedPlugin implements Listener {

    private static final String FONT_IMAGE = "optaland_trade:trade_gui";
    private static final int TITLE_OFFSET = -8;
    private static final String NS = "optaland_trade:";
    private static final String[] LABELS = {"trade", "echange", "trader"};

    private static final int[] LEFT  = {9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30, 36, 37, 38, 39};
    private static final int[] RIGHT = {14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35, 41, 42, 43, 44};
    private static final int SLOT_MY_HEAD = 0, SLOT_MY_MONEY = 2, SLOT_INFO = 4, SLOT_THEIR_MONEY = 6, SLOT_THEIR_HEAD = 8;
    private static final int[] CONFIRM = {45, 46, 47};
    private static final int SLOT_CANCEL = 49;
    private static final int[] THEIR_STATUS = {51, 52, 53};

    private final class Trade {
        final UUID a, b;
        final ItemStack[] offerA = new ItemStack[16], offerB = new ItemStack[16];
        double moneyA, moneyB;
        boolean confA, confB;
        Inventory invA, invB;
        int countdown = -1;
        int taskId = -1;
        boolean finished;
        boolean suppressClose;
        UUID inputting;
        int inputTaskId = -1;
        Trade(UUID a, UUID b) { this.a = a; this.b = b; }
        boolean isA(UUID u) { return a.equals(u); }
        UUID other(UUID u) { return a.equals(u) ? b : a; }
        ItemStack[] offerOf(UUID u) { return a.equals(u) ? offerA : offerB; }
        double moneyOf(UUID u) { return a.equals(u) ? moneyA : moneyB; }
        boolean confOf(UUID u) { return a.equals(u) ? confA : confB; }
        Inventory invOf(UUID u) { return a.equals(u) ? invA : invB; }
    }

    private final class Holder implements InventoryHolder {
        final Trade trade; final UUID viewer; Inventory inv;
        Holder(Trade t, UUID v) { trade = t; viewer = v; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static final class Request {
        final UUID from; final long expires;
        Request(UUID from, long expires) { this.from = from; this.expires = expires; }
    }

    private final Map<UUID, Trade> trades = new ConcurrentHashMap<>();
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>(); // cle = destinataire
    private final Map<String, ItemStack> iaItems = new HashMap<>();
    private String cachedTitle;
    private File dataFolder, configFile, pendingFile;
    private YamlConfiguration config;
    private Object ecoProvider; private Class<?> ecoClass;

    private String prefix = "&b&l\u00c9CHANGE &8\u00bb &7";
    private int requestSeconds = 30, countdownSeconds = 3, inputSeconds = 30;
    private double maxDistance = 0;
    private boolean moneyEnabled = true;

    // ===================== cycle de vie =====================

    @Override
    public void onEnable() {
        unregisterStaleListeners();
        dataFolder = new File(getHost().getDataFolder(), "trade");
        if (!dataFolder.exists() && !dataFolder.mkdirs()) getLogger().warning("[Trade] impossible de creer " + dataFolder);
        configFile = new File(dataFolder, "config.yml");
        pendingFile = new File(dataFolder, "pending.yml");
        loadConfig();
        try {
            Bukkit.getPluginManager().registerEvents(this, getHost());
        } catch (Throwable t) {
            getLogger().severe("[Trade] listener impossible a enregistrer : " + t);
        }
        for (String l : LABELS) registerCommand(l, this::handleCommand);
        forceClaimLabels();
        try { Bukkit.getScheduler().runTaskLater((Plugin) getHost(), this::forceClaimLabels, 20L); } catch (Throwable ignored) {}
        for (Player p : Bukkit.getOnlinePlayers()) returnPending(p);
        getLogger().info("[Trade] actif - /trade <joueur> (argent " + (moneyEnabled && eco() != null ? "oui" : "non") + ").");
    }

    @Override
    public void onDisable() {
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        for (Trade t : new ArrayList<>(trades.values())) cancelTrade(t, "Plugin recharge : \u00e9change annul\u00e9, items rendus.");
        trades.clear();
        requests.clear();
    }

    // ===================== commandes =====================

    private boolean handleCommand(CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Joueurs uniquement."); return true; }
        if (args.length == 0) { usage(p, label); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("accept") || sub.equals("accepter") || sub.equals("oui")) {
            accept(p, args.length >= 2 ? args[1] : null);
        } else if (sub.equals("deny") || sub.equals("refuser") || sub.equals("non")) {
            deny(p);
        } else if (sub.equals("cancel") || sub.equals("annuler")) {
            Trade t = trades.get(p.getUniqueId());
            if (t == null) send(p, "&cTu n'es pas en \u00e9change.");
            else cancelTrade(t, p.getName() + " a annul\u00e9 l'\u00e9change.");
        } else if (sub.equals("reload")) {
            if (!p.hasPermission("trade.admin")) { send(p, "&cPas la permission."); return true; }
            loadConfig(); cachedTitle = null; iaItems.clear();
            send(p, "&aConfiguration recharg\u00e9e.");
        } else {
            request(p, args[0]);
        }
        return true;
    }

    private void usage(Player p, String label) {
        send(p, "&f/" + label + " <joueur> &7- proposer un \u00e9change");
        send(p, "&f/" + label + " accept &7- accepter la demande re\u00e7ue");
        send(p, "&f/" + label + " deny &7- refuser la demande");
        send(p, "&f/" + label + " cancel &7- annuler l'\u00e9change en cours");
    }

    private void request(Player p, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) { send(p, "&cJoueur introuvable ou hors ligne."); return; }
        if (target.getUniqueId().equals(p.getUniqueId())) { send(p, "&cTu ne peux pas \u00e9changer avec toi-m\u00eame."); return; }
        if (trades.containsKey(p.getUniqueId())) { send(p, "&cTu es d\u00e9j\u00e0 en \u00e9change."); return; }
        if (trades.containsKey(target.getUniqueId())) { send(p, "&c" + target.getName() + " est d\u00e9j\u00e0 en \u00e9change."); return; }
        if (maxDistance > 0 && (!p.getWorld().equals(target.getWorld()) || p.getLocation().distance(target.getLocation()) > maxDistance)) {
            send(p, "&cTu dois \u00eatre \u00e0 moins de " + (int) maxDistance + " blocs de " + target.getName() + ".");
            return;
        }
        long now = System.currentTimeMillis();
        // Si l'autre nous a deja fait une demande : on l'accepte directement.
        Request mine = requests.get(p.getUniqueId());
        if (mine != null && mine.from.equals(target.getUniqueId()) && mine.expires > now) { accept(p, target.getName()); return; }

        requests.put(target.getUniqueId(), new Request(p.getUniqueId(), now + requestSeconds * 1000L));
        send(p, "&7Demande envoy\u00e9e \u00e0 &e" + target.getName() + "&7 (expire dans " + requestSeconds + " s).");
        send(target, "&e" + p.getName() + " &7veut \u00e9changer avec toi.");
        Component line = LEGACY.deserialize(prefix)
                .append(Component.text("[Accepter]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/trade accept " + p.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text("Accepter l'\u00e9change avec " + p.getName(), NamedTextColor.GREEN))))
                .append(Component.text("  "))
                .append(Component.text("[Refuser]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/trade deny"))
                        .hoverEvent(HoverEvent.showText(Component.text("Refuser", NamedTextColor.RED))));
        target.sendMessage(line);
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
    }

    private void accept(Player p, String fromName) {
        Request r = requests.get(p.getUniqueId());
        if (r == null || r.expires < System.currentTimeMillis()) {
            requests.remove(p.getUniqueId());
            send(p, "&cAucune demande d'\u00e9change en attente.");
            return;
        }
        Player from = Bukkit.getPlayer(r.from);
        if (from == null || !from.isOnline()) { requests.remove(p.getUniqueId()); send(p, "&cCe joueur n'est plus en ligne."); return; }
        if (fromName != null && !from.getName().equalsIgnoreCase(fromName)) { send(p, "&cLa demande en attente vient de &e" + from.getName() + "&c."); return; }
        if (trades.containsKey(p.getUniqueId()) || trades.containsKey(from.getUniqueId())) { send(p, "&cL'un de vous est d\u00e9j\u00e0 en \u00e9change."); return; }
        if (maxDistance > 0 && (!p.getWorld().equals(from.getWorld()) || p.getLocation().distance(from.getLocation()) > maxDistance)) {
            send(p, "&cVous \u00eates trop loin l'un de l'autre."); return;
        }
        requests.remove(p.getUniqueId());
        requests.remove(from.getUniqueId());
        startTrade(from, p);
    }

    private void deny(Player p) {
        Request r = requests.remove(p.getUniqueId());
        if (r == null) { send(p, "&cAucune demande en attente."); return; }
        send(p, "&7Demande refus\u00e9e.");
        Player from = Bukkit.getPlayer(r.from);
        if (from != null) send(from, "&c" + p.getName() + " a refus\u00e9 l'\u00e9change.");
    }

    // ===================== echange =====================

    private void startTrade(Player a, Player b) {
        Trade t = new Trade(a.getUniqueId(), b.getUniqueId());
        trades.put(t.a, t);
        trades.put(t.b, t);
        Holder ha = new Holder(t, t.a), hb = new Holder(t, t.b);
        t.invA = Bukkit.createInventory(ha, 54, titleComponent()); ha.inv = t.invA;
        t.invB = Bukkit.createInventory(hb, 54, titleComponent()); hb.inv = t.invB;
        render(t);
        a.openInventory(t.invA);
        b.openInventory(t.invB);
        for (Player p : new Player[]{a, b}) {
            send(p, "&a\u00c9change ouvert ! &7Clique sur tes items pour les proposer, puis &aCONFIRMER&7.");
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.8f, 1.2f);
        }
        getLogger().info("[Trade] " + a.getName() + " <-> " + b.getName() + " : echange ouvert.");
    }

    private void offerItem(Trade t, Player p, Inventory from, int fromSlot, ItemStack item, boolean one) {
        ItemStack[] mine = t.offerOf(p.getUniqueId());
        int amount = one ? 1 : item.getAmount();
        int target = -1;
        for (int i = 0; i < mine.length; i++) {
            if (mine[i] != null && mine[i].isSimilar(item) && mine[i].getAmount() + amount <= mine[i].getMaxStackSize()) { target = i; break; }
        }
        if (target < 0) for (int i = 0; i < mine.length; i++) if (mine[i] == null) { target = i; break; }
        if (target < 0) { send(p, "&cTon offre est pleine (16 cases)."); return; }
        if (mine[target] == null) { ItemStack c = item.clone(); c.setAmount(amount); mine[target] = c; }
        else mine[target].setAmount(mine[target].getAmount() + amount);
        if (amount >= item.getAmount()) from.setItem(fromSlot, null);
        else item.setAmount(item.getAmount() - amount);
        afterChange(t, p);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
    }

    private void takeBack(Trade t, Player p, int idx, boolean one) {
        ItemStack[] mine = t.offerOf(p.getUniqueId());
        ItemStack cur = mine[idx];
        if (cur == null) return;
        int amount = one ? 1 : cur.getAmount();
        ItemStack give = cur.clone(); give.setAmount(amount);
        Map<Integer, ItemStack> left = p.getInventory().addItem(give);
        int notGiven = 0; for (ItemStack l : left.values()) notGiven += l.getAmount();
        int given = amount - notGiven;
        if (given <= 0) { send(p, "&cTon inventaire est plein."); return; }
        if (given >= cur.getAmount()) mine[idx] = null; else cur.setAmount(cur.getAmount() - given);
        afterChange(t, p);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.9f);
    }

    private void afterChange(Trade t, Player who) {
        boolean wasConfirmed = t.confA || t.confB;
        resetConfirms(t);
        if (wasConfirmed) {
            Player o = Bukkit.getPlayer(t.other(who.getUniqueId()));
            if (o != null) send(o, "&e" + who.getName() + " a modifi\u00e9 son offre : confirmations annul\u00e9es.");
        }
        savePending(t);
        render(t);
        try { who.updateInventory(); } catch (Throwable ignored) {}
    }

    private void resetConfirms(Trade t) {
        t.confA = false; t.confB = false;
        if (t.taskId != -1) { try { Bukkit.getScheduler().cancelTask(t.taskId); } catch (Throwable ignored) {} t.taskId = -1; }
        t.countdown = -1;
    }

    private void toggleConfirm(Trade t, Player p) {
        UUID u = p.getUniqueId();
        boolean now = !t.confOf(u);
        if (now && moneyEnabled && t.moneyOf(u) > 0 && balance(p) + 0.0001 < t.moneyOf(u)) {
            send(p, "&cTu n'as plus assez d'argent pour ton offre (" + fmtMoney(t.moneyOf(u)) + "$).");
            return;
        }
        if (t.isA(u)) t.confA = now; else t.confB = now;
        Player o = Bukkit.getPlayer(t.other(u));
        if (now) {
            send(p, "&aTu as confirm\u00e9. &7En attente de l'autre joueur\u2026");
            if (o != null) { send(o, "&e" + p.getName() + " a confirm\u00e9 l'\u00e9change."); o.playSound(o.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f); }
        } else {
            send(p, "&7Confirmation annul\u00e9e.");
            if (o != null) send(o, "&e" + p.getName() + " a annul\u00e9 sa confirmation.");
        }
        if (t.confA && t.confB) startCountdown(t); else render(t);
    }

    private void startCountdown(Trade t) {
        t.countdown = Math.max(0, countdownSeconds);
        render(t);
        if (t.countdown == 0) { complete(t); return; }
        t.taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask((Plugin) getHost(), () -> {
            if (t.finished) return;
            if (!t.confA || !t.confB) { resetConfirms(t); render(t); return; }
            t.countdown--;
            for (UUID u : new UUID[]{t.a, t.b}) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
            }
            if (t.countdown <= 0) complete(t); else render(t);
        }, 20L, 20L);
    }

    private void complete(Trade t) {
        if (t.finished) return;
        if (t.taskId != -1) { try { Bukkit.getScheduler().cancelTask(t.taskId); } catch (Throwable ignored) {} t.taskId = -1; }
        Player pa = Bukkit.getPlayer(t.a), pb = Bukkit.getPlayer(t.b);
        if (pa == null || pb == null) { cancelTrade(t, "Un joueur s'est d\u00e9connect\u00e9."); return; }
        if (moneyEnabled) {
            if (t.moneyA > 0 && balance(pa) + 0.0001 < t.moneyA) { cancelTrade(t, pa.getName() + " n'a pas assez d'argent."); return; }
            if (t.moneyB > 0 && balance(pb) + 0.0001 < t.moneyB) { cancelTrade(t, pb.getName() + " n'a pas assez d'argent."); return; }
            if (t.moneyA > 0 && !withdraw(pa, t.moneyA)) { cancelTrade(t, "Transfert d'argent impossible."); return; }
            if (t.moneyB > 0 && !withdraw(pb, t.moneyB)) { deposit(pa, t.moneyA); cancelTrade(t, "Transfert d'argent impossible."); return; }
            if (t.moneyA > 0) deposit(pb, t.moneyA);
            if (t.moneyB > 0) deposit(pa, t.moneyB);
        }
        t.finished = true;
        int toA = giveAll(pa, t.offerB), toB = giveAll(pb, t.offerA);
        clearPending(t);
        trades.remove(t.a); trades.remove(t.b);
        closeBoth(t);
        String sumA = summary(toA, t.moneyB), sumB = summary(toB, t.moneyA);
        send(pa, "&a\u00c9change termin\u00e9 ! &7Tu as re\u00e7u : &f" + sumA);
        send(pb, "&a\u00c9change termin\u00e9 ! &7Tu as re\u00e7u : &f" + sumB);
        pa.playSound(pa.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
        pb.playSound(pb.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
        getLogger().info("[Trade] " + pa.getName() + " <-> " + pb.getName() + " : OK. " + pa.getName() + " recoit " + sumA
                + " | " + pb.getName() + " recoit " + sumB);
    }

    private String summary(int items, double money) {
        StringBuilder sb = new StringBuilder();
        if (items > 0) sb.append(items).append(" item").append(items > 1 ? "s" : "");
        if (money > 0) { if (sb.length() > 0) sb.append(" + "); sb.append(fmtMoney(money)).append("$"); }
        if (sb.length() == 0) sb.append("rien");
        return sb.toString();
    }

    /** Donne les items au joueur (surplus lache au sol). Retourne le nombre d'items donnes. */
    private int giveAll(Player p, ItemStack[] items) {
        int n = 0;
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir()) continue;
            n += it.getAmount();
            Map<Integer, ItemStack> left = p.getInventory().addItem(it.clone());
            for (ItemStack l : left.values()) {
                try { p.getWorld().dropItemNaturally(p.getLocation(), l); } catch (Throwable ignored) {}
            }
        }
        return n;
    }

    private void cancelTrade(Trade t, String reason) {
        if (t.finished) return;
        t.finished = true;
        if (t.taskId != -1) { try { Bukkit.getScheduler().cancelTask(t.taskId); } catch (Throwable ignored) {} t.taskId = -1; }
        if (t.inputTaskId != -1) { try { Bukkit.getScheduler().cancelTask(t.inputTaskId); } catch (Throwable ignored) {} t.inputTaskId = -1; }
        trades.remove(t.a); trades.remove(t.b);
        for (UUID u : new UUID[]{t.a, t.b}) {
            Player p = Bukkit.getPlayer(u);
            ItemStack[] offer = t.offerOf(u);
            if (p != null && p.isOnline()) {
                giveAll(p, offer);
                clearPendingFor(u);
                send(p, "&c\u00c9change annul\u00e9. &7" + reason);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.7f);
            } else {
                // hors ligne : les items restent dans pending.yml et seront rendus a la reconnexion
                savePendingFor(u, offer);
            }
        }
        closeBoth(t);
        getLogger().info("[Trade] echange annule (" + reason + ").");
    }

    private void closeBoth(Trade t) {
        t.suppressClose = true;
        try {
            for (UUID u : new UUID[]{t.a, t.b}) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) continue;
                InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
                if (h instanceof Holder hh && hh.trade == t) {
                    Bukkit.getScheduler().runTask((Plugin) getHost(), () -> {
                        InventoryHolder h2 = p.getOpenInventory().getTopInventory().getHolder();
                        if (h2 instanceof Holder hh2 && hh2.trade == t) p.closeInventory();
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    // ===================== argent (saisie chat) =====================

    private void startMoneyInput(Trade t, Player p) {
        if (!moneyEnabled || eco() == null) { send(p, "&cL'argent n'est pas disponible dans les \u00e9changes."); return; }
        UUID u = p.getUniqueId();
        if (t.inputting != null) { send(p, "&cUn montant est d\u00e9j\u00e0 en cours de saisie."); return; }
        t.inputting = u;
        Bukkit.getScheduler().runTask((Plugin) getHost(), () -> {
            t.suppressClose = true;
            try { p.closeInventory(); } finally { t.suppressClose = false; }
        });
        send(p, "&eTape dans le chat le montant d'argent \u00e0 mettre dans l'\u00e9change &7(0 pour retirer, &cannuler&7 pour revenir). " + inputSeconds + " s.");
        Player o = Bukkit.getPlayer(t.other(u));
        if (o != null) send(o, "&7" + p.getName() + " saisit un montant d'argent\u2026");
        render(t);
        t.inputTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask((Plugin) getHost(), () -> {
            if (!t.finished && u.equals(t.inputting)) {
                Player pp = Bukkit.getPlayer(u);
                if (pp != null) send(pp, "&7Temps \u00e9coul\u00e9, montant inchang\u00e9.");
                finishMoneyInput(t, u, null);
            }
        }, inputSeconds * 20L);
    }

    private void finishMoneyInput(Trade t, UUID u, Double amount) {
        if (t.finished) return;
        t.inputting = null;
        if (t.inputTaskId != -1) { try { Bukkit.getScheduler().cancelTask(t.inputTaskId); } catch (Throwable ignored) {} t.inputTaskId = -1; }
        Player p = Bukkit.getPlayer(u);
        if (amount != null && p != null) {
            double v = Math.max(0, Math.floor(amount * 100) / 100.0);
            if (v > 0 && balance(p) + 0.0001 < v) {
                send(p, "&cTu n'as que " + fmtMoney(balance(p)) + "$.");
            } else {
                if (t.isA(u)) t.moneyA = v; else t.moneyB = v;
                send(p, v > 0 ? "&aArgent dans ton offre : &e" + fmtMoney(v) + "$" : "&7Argent retir\u00e9 de ton offre.");
                Player o = Bukkit.getPlayer(t.other(u));
                if (o != null) send(o, "&e" + p.getName() + " propose " + fmtMoney(v) + "$.");
                if (t.confA || t.confB) resetConfirms(t);
            }
        }
        render(t);
        if (p != null && p.isOnline()) {
            Inventory inv = t.invOf(u);
            Bukkit.getScheduler().runTask((Plugin) getHost(), () -> { if (!t.finished && p.isOnline()) p.openInventory(inv); });
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Trade t = trades.get(p.getUniqueId());
        if (t == null || t.finished || !p.getUniqueId().equals(t.inputting)) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim().replace(" ", "").replace(",", ".");
        final UUID u = p.getUniqueId();
        Bukkit.getScheduler().runTask((Plugin) getHost(), () -> {
            if (t.finished || !u.equals(t.inputting)) return;
            if (text.equalsIgnoreCase("annuler") || text.equalsIgnoreCase("cancel")) { finishMoneyInput(t, u, null); return; }
            try {
                String clean = text.replace("$", "").replace("k", "000").replace("K", "000");
                double v = Double.parseDouble(clean);
                if (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) throw new NumberFormatException();
                finishMoneyInput(t, u, v);
            } catch (NumberFormatException ex) {
                send(p, "&cMontant invalide. Tape un nombre (ex: 2500) ou &eannuler&c.");
            }
        });
    }

    // ===================== rendu =====================

    private void render(Trade t) {
        if (t.finished) return;
        renderSide(t, t.a);
        renderSide(t, t.b);
    }

    private void renderSide(Trade t, UUID viewer) {
        Inventory inv = t.invOf(viewer);
        if (inv == null) return;
        UUID otherId = t.other(viewer);
        Player me = Bukkit.getPlayer(viewer), other = Bukkit.getPlayer(otherId);
        ItemStack[] mine = t.offerOf(viewer), theirs = t.offerOf(otherId);
        boolean myConf = t.confOf(viewer), theirConf = t.confOf(otherId);
        String myName = me != null ? me.getName() : "?", otherName = other != null ? other.getName() : "?";

        for (int i = 0; i < 16; i++) {
            inv.setItem(LEFT[i], mine[i] == null ? null : mine[i].clone());
            inv.setItem(RIGHT[i], theirs[i] == null ? null : theirs[i].clone());
        }
        inv.setItem(SLOT_MY_HEAD, head(me, "&b&lToi &7: &f" + myName, List.of(legacy("&7Items propos\u00e9s : &f" + count(mine)))));
        inv.setItem(SLOT_THEIR_HEAD, head(other, "&d&lLui &7: &f" + otherName, List.of(legacy("&7Items propos\u00e9s : &f" + count(theirs)))));

        if (moneyEnabled && eco() != null) {
            List<Component> ml = new ArrayList<>();
            ml.add(legacy("&7Montant propos\u00e9 : &e" + fmtMoney(t.moneyOf(viewer)) + "$"));
            ml.add(Component.empty());
            ml.add(legacy(otherId.equals(t.inputting) ? "&7(" + otherName + " saisit un montant\u2026)" : "&eClique pour saisir un montant"));
            inv.setItem(SLOT_MY_MONEY, named(iaItem(NS + "money", Material.GOLD_INGOT), "&6&lTon argent", ml));
            inv.setItem(SLOT_THEIR_MONEY, named(iaItem(NS + "money", Material.GOLD_INGOT), "&6&lSon argent",
                    List.of(legacy("&7" + otherName + " propose : &e" + fmtMoney(t.moneyOf(otherId)) + "$"))));
        } else {
            inv.setItem(SLOT_MY_MONEY, null);
            inv.setItem(SLOT_THEIR_MONEY, null);
        }

        ItemStack info;
        if (t.countdown >= 0) {
            info = named(iaItem(NS + "status_wait", Material.CLOCK), "&a&l\u00c9change dans " + t.countdown + " s", List.of(legacy("&7Ferme le menu pour annuler.")));
        } else if (t.inputting != null) {
            info = named(iaItem(NS + "status_wait", Material.CLOCK), "&e&lSaisie d'un montant\u2026", List.of(legacy("&7Patiente un instant.")));
        } else {
            info = named(iaItem(NS + "status_wait", Material.CLOCK), "&f&lEn attente", List.of(
                    legacy("&7Clic sur un item de ton inventaire : &fl'ajouter"),
                    legacy("&7Clic sur un item de ton offre : &fle reprendre"),
                    legacy("&7Clic droit : &f1 seul item"),
                    Component.empty(),
                    legacy("&7Les deux joueurs doivent &aCONFIRMER&7.")));
        }
        inv.setItem(SLOT_INFO, info);

        String cName = myConf ? "&a&lCONFIRM\u00c9 \u2714" : "&a&lCONFIRMER";
        List<Component> cLore = List.of(legacy(myConf ? "&7Clique pour annuler ta confirmation." : "&7Clique quand ton offre est pr\u00eate."));
        ItemStack cMain = named(iaItem(myConf ? NS + "status_ok" : NS + "btn_confirm", Material.LIME_DYE), cName, cLore);
        ItemStack cSide = named(iaItem(NS + "invisible", Material.LIME_STAINED_GLASS_PANE), cName, cLore);
        inv.setItem(CONFIRM[0], cSide); inv.setItem(CONFIRM[1], cMain); inv.setItem(CONFIRM[2], cSide.clone());

        inv.setItem(SLOT_CANCEL, named(iaItem(NS + "btn_cancel", Material.BARRIER), "&c&lANNULER L'\u00c9CHANGE", List.of(legacy("&7Tous les items sont rendus."))));

        String sName = theirConf ? "&a" + otherName + " a confirm\u00e9 \u2714" : "&7" + otherName + " n'a pas encore confirm\u00e9";
        ItemStack sMain = named(iaItem(theirConf ? NS + "status_ok" : NS + "status_wait", theirConf ? Material.LIME_DYE : Material.GRAY_DYE), sName, List.of());
        ItemStack sSide = named(iaItem(NS + "invisible", Material.GRAY_STAINED_GLASS_PANE), sName, List.of());
        inv.setItem(THEIR_STATUS[0], sSide); inv.setItem(THEIR_STATUS[1], sMain); inv.setItem(THEIR_STATUS[2], sSide.clone());
    }

    private int count(ItemStack[] arr) {
        int n = 0; for (ItemStack it : arr) if (it != null) n += it.getAmount(); return n;
    }

    private ItemStack head(Player p, String name, List<Component> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta m = it.getItemMeta();
        if (p != null && m instanceof SkullMeta sm) { try { sm.setOwningPlayer(p); } catch (Throwable ignored) {} }
        m.displayName(legacy(name));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack named(ItemStack it, String name, List<Component> lore) {
        ItemMeta m = it.getItemMeta();
        m.displayName(legacy(name));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    // ===================== events =====================

    private static boolean isStaleMenu(InventoryHolder h) {
        return h != null && !(h instanceof Holder) && h.getClass().getName().endsWith("TradePlugin$Holder");
    }

    private static int indexOf(int[] arr, int v) { for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i; return -1; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder h = top.getHolder();
        if (isStaleMenu(h)) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) Bukkit.getScheduler().runTask((Plugin) getHost(), () -> p.closeInventory());
            return;
        }
        if (!(h instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Trade t = holder.trade;
        if (t.finished || e.getClickedInventory() == null) return;
        if (!p.getUniqueId().equals(holder.viewer)) return;
        ClickType ct = e.getClick();
        boolean one = ct == ClickType.RIGHT || ct == ClickType.SHIFT_RIGHT;
        if (e.getClickedInventory() == top) {
            int raw = e.getRawSlot();
            if (raw == SLOT_CANCEL) { cancelTrade(t, p.getName() + " a annul\u00e9 l'\u00e9change."); return; }
            if (t.countdown >= 0) { send(p, "&7Validation en cours\u2026 (ferme le menu ou ANNULER pour arr\u00eater)"); return; }
            if (t.inputting != null) return;
            if (indexOf(CONFIRM, raw) >= 0) { toggleConfirm(t, p); return; }
            if (raw == SLOT_MY_MONEY) { startMoneyInput(t, p); return; }
            int idx = indexOf(LEFT, raw);
            if (idx >= 0) takeBack(t, p, idx, one);
        } else {
            if (t.countdown >= 0 || t.inputting != null) return;
            if (ct == ClickType.DOUBLE_CLICK || ct == ClickType.NUMBER_KEY || ct == ClickType.SWAP_OFFHAND || ct == ClickType.CREATIVE) return;
            ItemStack it = e.getCurrentItem();
            if (it == null || it.getType().isAir()) return;
            offerItem(t, p, e.getClickedInventory(), e.getSlot(), it, one);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (h instanceof Holder || isStaleMenu(h)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (!(h instanceof Holder holder)) return;
        Trade t = holder.trade;
        if (t.finished || t.suppressClose) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        if (p.getUniqueId().equals(t.inputting)) return; // ferme par nous pour la saisie
        cancelTrade(t, p.getName() + " a ferm\u00e9 le menu.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        requests.remove(u);
        Trade t = trades.get(u);
        if (t != null) cancelTrade(t, e.getPlayer().getName() + " s'est d\u00e9connect\u00e9.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater((Plugin) getHost(), () -> { if (p.isOnline()) returnPending(p); }, 40L);
    }

    // ===================== anti-crash : pending.yml =====================

    private synchronized void savePending(Trade t) {
        savePendingFor(t.a, t.offerA);
        savePendingFor(t.b, t.offerB);
    }

    private synchronized void savePendingFor(UUID u, ItemStack[] offer) {
        try {
            YamlConfiguration y = pendingFile.exists() ? YamlConfiguration.loadConfiguration(pendingFile) : new YamlConfiguration();
            List<ItemStack> list = new ArrayList<>();
            for (ItemStack it : offer) if (it != null && !it.getType().isAir()) list.add(it.clone());
            if (list.isEmpty()) y.set(u.toString(), null); else y.set(u.toString(), list);
            y.save(pendingFile);
        } catch (Throwable ex) {
            getLogger().warning("[Trade] sauvegarde pending KO : " + ex);
        }
    }

    private synchronized void clearPending(Trade t) { clearPendingFor(t.a); clearPendingFor(t.b); }

    private synchronized void clearPendingFor(UUID u) {
        try {
            if (!pendingFile.exists()) return;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(pendingFile);
            if (!y.contains(u.toString())) return;
            y.set(u.toString(), null);
            y.save(pendingFile);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private synchronized void returnPending(Player p) {
        try {
            if (!pendingFile.exists()) return;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(pendingFile);
            String k = p.getUniqueId().toString();
            if (!y.contains(k)) return;
            List<?> raw = y.getList(k);
            y.set(k, null);
            y.save(pendingFile);
            if (raw == null || raw.isEmpty()) return;
            List<ItemStack> items = new ArrayList<>();
            for (Object o : raw) if (o instanceof ItemStack it) items.add(it);
            if (items.isEmpty()) return;
            giveAll(p, items.toArray(new ItemStack[0]));
            send(p, "&eDes items d'un \u00e9change interrompu t'ont \u00e9t\u00e9 rendus.");
            getLogger().info("[Trade] " + p.getName() + " : " + items.size() + " stack(s) d'un echange interrompu rendus.");
        } catch (Throwable ex) {
            getLogger().warning("[Trade] retour pending KO pour " + p.getName() + " : " + ex);
        }
    }

    // ===================== economie (Vault, reflexion) =====================

    private Object eco() {
        if (ecoProvider != null) return ecoProvider;
        try {
            Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
            if (vault == null) return null;
            Class<?> c = Class.forName("net.milkbowl.vault.economy.Economy", true, vault.getClass().getClassLoader());
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(c);
            if (rsp == null) return null;
            ecoClass = c;
            ecoProvider = rsp.getProvider();
        } catch (Throwable ignored) {}
        return ecoProvider;
    }

    private double balance(OfflinePlayer p) {
        try {
            Object e = eco(); if (e == null) return 0;
            Object v = ecoClass.getMethod("getBalance", OfflinePlayer.class).invoke(e, p);
            return v instanceof Number n ? n.doubleValue() : 0;
        } catch (Throwable t) { return 0; }
    }

    private boolean withdraw(OfflinePlayer p, double amount) {
        try {
            Object e = eco(); if (e == null) return false;
            Object r = ecoClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class).invoke(e, p, amount);
            Object ok = r.getClass().getMethod("transactionSuccess").invoke(r);
            return Boolean.TRUE.equals(ok);
        } catch (Throwable t) { getLogger().warning("[Trade] retrait KO : " + t); return false; }
    }

    private boolean deposit(OfflinePlayer p, double amount) {
        try {
            Object e = eco(); if (e == null) return false;
            Object r = ecoClass.getMethod("depositPlayer", OfflinePlayer.class, double.class).invoke(e, p, amount);
            Object ok = r.getClass().getMethod("transactionSuccess").invoke(r);
            return Boolean.TRUE.equals(ok);
        } catch (Throwable t) { getLogger().warning("[Trade] depot KO : " + t); return false; }
    }

    private static String fmtMoney(double v) {
        String s = String.format(Locale.ROOT, "%,.2f", v).replace(",", " ");
        if (s.endsWith(".00")) s = s.substring(0, s.length() - 3);
        return s;
    }

    // ===================== config =====================

    private static final String DEFAULT_CONFIG = String.join("\n",
            "# ============================================================",
            "#  Trade - echange entre joueurs (/trade)",
            "#  Apres modification : /trade reload",
            "# ============================================================",
            "prefix: \"&b&l\u00c9CHANGE &8\u00bb &7\"",
            "# Argent dans les echanges (necessite Vault)",
            "money: true",
            "# Distance max entre les deux joueurs (0 = n'importe ou)",
            "max-distance: 0",
            "# Duree de validite d'une demande (secondes)",
            "request-seconds: 30",
            "# Compte a rebours apres la double confirmation (secondes, anti-arnaque)",
            "countdown-seconds: 3",
            "# Temps laisse pour taper un montant dans le chat (secondes)",
            "input-seconds: 30",
            "");

    private void loadConfig() {
        try {
            if (!configFile.exists()) Files.writeString(configFile.toPath(), DEFAULT_CONFIG, StandardCharsets.UTF_8);
            config = YamlConfiguration.loadConfiguration(configFile);
        } catch (Throwable t) {
            config = new YamlConfiguration();
            try { config.loadFromString(DEFAULT_CONFIG); } catch (Throwable ignored) {}
        }
        prefix = config.getString("prefix", "&b&l\u00c9CHANGE &8\u00bb &7");
        moneyEnabled = config.getBoolean("money", true);
        maxDistance = config.getDouble("max-distance", 0);
        requestSeconds = Math.max(5, config.getInt("request-seconds", 30));
        countdownSeconds = Math.max(0, config.getInt("countdown-seconds", 3));
        inputSeconds = Math.max(5, config.getInt("input-seconds", 30));
    }

    // ===================== labels / listeners (reload) =====================

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
            if (field == null) return;
            field.setAccessible(true);
            Map<String, Object> known = (Map<String, Object>) field.get(map);
            String pfx = getName().toLowerCase(Locale.ROOT) + ":";
            boolean changed = false;
            for (String label : LABELS) {
                Object ours = getCommand.invoke(map, pfx + label);
                if (ours == null) ours = known.get(pfx + label);
                if (ours == null) {
                    for (Map.Entry<String, Object> en : known.entrySet()) {
                        if (en.getKey().endsWith(":" + label) && en.getValue() != null
                                && en.getValue().getClass().getName().startsWith("fr.loader.api.LoadedPlugin")) { ours = en.getValue(); break; }
                    }
                }
                if (ours == null) continue;
                Object current = known.get(label);
                if (current != ours) {
                    known.put(label, ours);
                    changed = true;
                    getLogger().info("[Trade] /" + label + " etait pris par " + (current == null ? "personne" : current.getClass().getName()) + " -> repris.");
                }
            }
            if (changed) { try { server.getClass().getMethod("syncCommands").invoke(server); } catch (Throwable ignored) {} }
        } catch (Throwable t) {
            getLogger().warning("[Trade] reprise des labels impossible : " + t);
        }
    }

    // ===================== ItemsAdder (reflexion) =====================

    private ClassLoader iaLoader() {
        Plugin ia = Bukkit.getPluginManager().getPlugin("ItemsAdder");
        return ia != null ? ia.getClass().getClassLoader() : getClass().getClassLoader();
    }

    private Component titleComponent() {
        if (cachedTitle == null) cachedTitle = buildTitle();
        if (cachedTitle.isEmpty()) return Component.text("\u00c9change", NamedTextColor.DARK_GRAY);
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
                    try { offset = (String) fiw.getMethod("applyPixelsOffset", String.class, int.class).invoke(null, "", TITLE_OFFSET); } catch (Throwable ignored) {}
                    if (offset == null || offset.isEmpty()) offset = papi("%img_offset_" + TITLE_OFFSET + "%");
                    return offset + img;
                }
            }
        } catch (Throwable ignored) {}
        for (String name : new String[]{"%img_" + FONT_IMAGE + "%", "%img_trade_gui%"}) {
            String s = papi("%img_offset_" + TITLE_OFFSET + "%" + name);
            if (s != null && !s.isEmpty() && !s.contains("%")) return s;
        }
        getLogger().warning("[Trade] texture " + FONT_IMAGE + " introuvable (contents/optaland_trade + /iazip ?) -> titre texte.");
        return "";
    }

    private String papi(String text) {
        try {
            Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (papi == null) return "";
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, papi.getClass().getClassLoader());
            Object out = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class).invoke(null, null, text);
            return out == null ? "" : out.toString();
        } catch (Throwable t) { return ""; }
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
                getLogger().warning("[Trade] item " + id + " introuvable -> " + fallback.name().toLowerCase(Locale.ROOT) + ".");
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
}
