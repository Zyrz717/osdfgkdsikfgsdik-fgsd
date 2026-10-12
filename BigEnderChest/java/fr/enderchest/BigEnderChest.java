package fr.enderchest;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BigEnderChest - a mettre dans : fr/enderchest/BigEnderChest.java
 *
 * Remplace le coffre de l'Ender vanilla (27 slots) par un menu DOUBLE COFFRE (54 slots),
 * propre a chaque joueur et sauvegarde sur le disque (un fichier .yml par joueur).
 *
 *  - Clic droit sur un bloc Ender Chest -> ouvre le menu 54 slots.
 *  - /enderchest (alias /ec)            -> ouvre ton menu 54 slots.
 *  - /ec JOUEUR (ou /enderchest JOUEUR) -> ouvre l'ender chest d'un autre (perm admin.ec.see).
 *  - Sauvegarde a la fermeture, a la deconnexion et a l'arret du serveur.
 *
 * ANTI-DUP : un SEUL inventaire vivant par joueur (partage entre tous les visiteurs).
 * On ne recharge JAMAIS depuis le disque tant qu'une copie est ouverte, et on sauvegarde
 * seulement quand le dernier visiteur ferme. Ca empeche la duplication qui arrivait quand
 * on prenait un objet puis qu'on rouvrait (ou qu'un admin ouvrait en meme temps) avant la
 * sauvegarde : la nouvelle copie rechargeait l'ancien contenu du fichier.
 *
 * Tout le monde peut utiliser son propre ender chest.
 */
public class BigEnderChest extends LoadedPlugin implements Listener {

    private static final int SIZE = 54; // double coffre
    private static final String PERM_OTHERS = "admin.ec.see"; // pour voir l'ender chest d'un autre
    private static final String PERM_BLOCK = "bspdpsi.edd"; // si le joueur l'a -> il ne peut PAS voir l'ender chest
    private static final Component TITLE =
            Component.text("Coffre de l'Ender", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD);

    private File dataFolder;

    /** Inventaires actuellement ouverts, un seul par proprietaire (cle = UUID du proprietaire). */
    private final Map<UUID, Inventory> live = new HashMap<>();

    // ===================== CYCLE DE VIE =====================

    @Override
    public void onEnable() {
        unregisterStaleListeners(); // evite les listeners fantomes apres /plreload
        dataFolder = new File(getHost().getDataFolder(), "enderchests");
        if (!dataFolder.exists()) dataFolder.mkdirs();

        registerListener(this);

        // Le meme handler pour /enderchest et son alias /ec.
        registerCommand("enderchest", this::handleCommand);
        registerCommand("ec", this::handleCommand);

        // CORRECTIF : EssentialsX enregistre lui aussi /enderchest et /ec (au demarrage,
        // avant nous), donc nos deux commandes n'etaient accessibles que via
        // /bigenderchest:ec. /ec JOUEUR tombait sur la version Essentials, qui ouvre
        // le coffre de l'Ender VANILLA (27 slots, vide ici) -> "ca ne marche plus".
        // On reprend les deux labels de force, 1 s apres le demarrage.
        forceClaimLabels();
        try {
            Bukkit.getScheduler().runTaskLater((Plugin) getHost(), this::forceClaimLabels, 20L);
        } catch (Throwable ignored) {}

        getLogger().info("BigEnderChest active.");
    }

    /** Branche /enderchest et /ec sur NOS commandes meme si un autre plugin les avait prises. */
    @SuppressWarnings("unchecked")
    private void forceClaimLabels() {
        try {
            Object server = Bukkit.getServer();
            Object map = server.getClass().getMethod("getCommandMap").invoke(server);
            java.lang.reflect.Method getCommand = map.getClass().getMethod("getCommand", String.class);
            java.lang.reflect.Field field = null;
            for (Class<?> c = map.getClass(); c != null && field == null; c = c.getSuperclass()) {
                try { field = c.getDeclaredField("knownCommands"); } catch (NoSuchFieldException ignored) {}
            }
            if (field == null) { getLogger().warning("[BigEnderChest] knownCommands introuvable."); return; }
            field.setAccessible(true);
            Map<String, Object> known = (Map<String, Object>) field.get(map);
            String prefix = getName().toLowerCase(java.util.Locale.ROOT) + ":";
            boolean changed = false;
            for (String label : new String[]{"enderchest", "ec"}) {
                Object ours = getCommand.invoke(map, prefix + label);
                if (ours == null) ours = known.get(prefix + label);
                if (ours == null) {
                    // Repli : une entree "xxx:label" dont la commande est un wrapper du PluginLoader.
                    for (Map.Entry<String, Object> en : known.entrySet()) {
                        if (en.getKey().endsWith(":" + label) && en.getValue() != null
                                && en.getValue().getClass().getName().startsWith("fr.loader.api.LoadedPlugin")) {
                            ours = en.getValue();
                            break;
                        }
                    }
                }
                if (ours == null) { getLogger().warning("[BigEnderChest] commande " + prefix + label + " introuvable."); continue; }
                Object current = known.get(label);
                if (current != ours) {
                    known.put(label, ours);
                    changed = true;
                    getLogger().info("[BigEnderChest] /" + label + " etait pris par "
                            + (current == null ? "personne" : current.getClass().getName()) + " -> repris.");
                }
            }
            if (changed) {
                try { server.getClass().getMethod("syncCommands").invoke(server); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            getLogger().warning("[BigEnderChest] reprise de /ec impossible : " + t);
        }
    }

    /** Logique commune a /enderchest et /ec. */
    private boolean handleCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Commande reservee aux joueurs.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            open(p); // son propre ender chest
            return true;
        }
        // Ouvrir l'ender chest d'un autre joueur : reserve a admin.ec.see
        if (!p.hasPermission(PERM_OTHERS)) {
            p.sendMessage(Component.text("Tu n'as pas la permission " + PERM_OTHERS + ".", NamedTextColor.RED));
            return true;
        }
        String targetName = args[0];
        Player online = Bukkit.getPlayerExact(targetName);
        UUID targetId;
        String resolvedName;
        if (online != null) {
            targetId = online.getUniqueId();
            resolvedName = online.getName();
        } else {
            OfflinePlayer off = Bukkit.getOfflinePlayer(targetName);
            if (off == null || off.getUniqueId() == null
                    || (!off.hasPlayedBefore() && !off.isOnline())) {
                p.sendMessage(Component.text("Joueur introuvable : " + targetName, NamedTextColor.RED));
                return true;
            }
            targetId = off.getUniqueId();
            resolvedName = off.getName() != null ? off.getName() : targetName;
        }
        open(p, targetId, resolvedName, false);
        p.sendMessage(Component.text("Ouverture de l'ender chest de " + resolvedName + ".", NamedTextColor.LIGHT_PURPLE));
        return true;
    }

    @Override
    public void onDisable() {
        // On sauvegarde tous les menus encore ouverts avant l'arret.
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = topInventory(p);
            if (top != null && top.getHolder() instanceof EnderHolder h) {
                saveContents(h.owner, top.getContents());
                p.closeInventory();
            }
        }
        live.clear();
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        getLogger().info("BigEnderChest desactive.");
    }

    /** Supprime tout listener BigEnderChest fantome d'un ancien chargement (compare par nom de classe). */
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

    // ===================== OUVERTURE =====================

    /** Ouvre le menu 54 slots du joueur (son propre ender chest). */
    private void open(Player viewer) {
        open(viewer, viewer.getUniqueId(), viewer.getName(), true);
    }

    /** Ouvre le menu 54 slots de 'owner' pour 'viewer'. */
    private void open(Player viewer, UUID owner, String ownerName, boolean self) {
        // Blocage : ce joueur n'a pas le droit de voir d'ender chest.
        if (viewer.hasPermission(PERM_BLOCK)) {
            viewer.sendMessage(Component.text("Tu ne peux pas ouvrir l'ender chest.", NamedTextColor.RED));
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        // ANTI-DUP : si le joueur a DEJA un ender chest ouvert (le sien ou celui d'un autre),
        // on refuse d'en ouvrir un second. C'est ce qui empechait le dup via /ec : avoir deux
        // fenetres du meme coffre ouvertes en meme temps.
        Inventory current = topInventory(viewer);
        if (current != null && current.getHolder() instanceof EnderHolder) {
            viewer.sendMessage(Component.text("Tu as deja un ender chest ouvert.", NamedTextColor.RED));
            return;
        }

        // Si un inventaire de ce proprietaire est deja ouvert quelque part,
        // on REUTILISE le meme objet (pas de rechargement disque). Sinon on en cree un.
        Inventory inv = live.get(owner);
        if (inv == null) {
            EnderHolder holder = new EnderHolder(owner);
            Component title = self ? TITLE
                    : Component.text("Ender de " + ownerName, NamedTextColor.DARK_PURPLE, TextDecoration.BOLD);
            inv = Bukkit.createInventory(holder, SIZE, title);
            holder.inventory = inv;

            ItemStack[] saved = loadContents(owner);
            if (saved != null) inv.setContents(saved);

            // Migration : une seule fois par joueur, on recupere l'ancien ender chest vanilla (27 slots).
            Player ownerOnline = Bukkit.getPlayer(owner);
            if (ownerOnline != null && !isMigrated(owner)) {
                mergeVanilla(inv, ownerOnline);
                ownerOnline.getEnderChest().clear(); // evite la duplication
                markMigrated(owner, inv.getContents());
            }

            live.put(owner, inv);
        }

        viewer.openInventory(inv);
        viewer.playSound(viewer.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1f);
    }

    // ---- Clic droit sur le bloc Ender Chest ----
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return; // evite le double declenchement
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.ENDER_CHEST) return;

        Player p = e.getPlayer();
        // Si le joueur est accroupi avec un objet en main, on laisse le comportement vanilla
        // (placer un bloc contre l'ender chest, etc.).
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (p.isSneaking() && hand != null && !hand.getType().isAir()) return;

        e.setCancelled(true); // on annule l'ouverture vanilla (27 slots)
        open(p);
    }

    // ---- Filet : si un autre plugin / une autre source ouvre l'ender chest vanilla ----
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getInventory().getType() != InventoryType.ENDER_CHEST) return;
        if (e.getInventory().getHolder() instanceof EnderHolder) return; // deja le notre
        if (!(e.getPlayer() instanceof Player p)) return;

        e.setCancelled(true);
        // On ouvre le notre au tick suivant (ouvrir un inventaire pendant InventoryOpenEvent est deconseille).
        try {
            Bukkit.getScheduler().runTask((Plugin) getHost(), () -> open(p));
        } catch (Throwable t) {
            open(p); // repli si le scheduler n'est pas dispo
        }
    }

    // ===================== SAUVEGARDE =====================

    // ---- Sauvegarde a CHAQUE mouvement : clic, prise, depot, drop ----
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        // Le menu du haut est-il un des notres ? (couvre aussi les shift-clic depuis l'inventaire perso)
        if (e.getView().getTopInventory().getHolder() instanceof EnderHolder h) {
            scheduleSave(h.owner, e.getView().getTopInventory());
        }
    }

    // ---- Sauvegarde quand on GLISSE des objets (drag) ----
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof EnderHolder h) {
            scheduleSave(h.owner, e.getView().getTopInventory());
        }
    }

    /**
     * Sauvegarde au TICK SUIVANT : pendant le clic/drag, l'inventaire n'a pas encore
     * ete mis a jour, donc on attend un tick pour enregistrer le contenu final.
     */
    private void scheduleSave(UUID owner, Inventory inv) {
        try {
            Bukkit.getScheduler().runTask((Plugin) getHost(),
                    () -> saveContents(owner, inv.getContents()));
        } catch (Throwable t) {
            saveContents(owner, inv.getContents()); // repli immediat si pas de scheduler
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof EnderHolder h)) return;

        Inventory inv = e.getInventory();
        // On sauvegarde toujours l'etat courant (l'objet est partage, donc a jour).
        saveContents(h.owner, inv.getContents());

        if (e.getPlayer() instanceof Player p) {
            p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_CLOSE, 1f, 1f);
        }

        // ANTI-DUP : tant que le proprietaire est EN LIGNE, on GARDE l'inventaire vivant
        // en memoire. On ne le relit donc jamais depuis le disque a la reouverture -> aucun
        // risque de "vieux contenu" qui reapparait. On ne le libere que si le proprietaire
        // est hors-ligne et qu'aucun autre visiteur ne le regarde.
        boolean ownerOnline = Bukkit.getPlayer(h.owner) != null;
        if (!ownerOnline && !hasOtherViewers(inv, e.getPlayer())) {
            live.remove(h.owner);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();

        // 1) S'il regardait un ender chest (le sien ou celui d'un autre), on le sauvegarde.
        Inventory top = topInventory(p);
        if (top != null && top.getHolder() instanceof EnderHolder h) {
            saveContents(h.owner, top.getContents());
        }

        // 2) Il part : on libere SON propre inventaire vivant (apres l'avoir sauvegarde).
        Inventory own = live.get(p.getUniqueId());
        if (own != null) {
            saveContents(p.getUniqueId(), own.getContents());
            if (!hasOtherViewers(own, p)) {
                live.remove(p.getUniqueId());
            }
        }
    }

    /** Reste-t-il un visiteur autre que 'closing' en train de regarder cet inventaire ? */
    private boolean hasOtherViewers(Inventory inv, HumanEntity closing) {
        try {
            for (HumanEntity v : inv.getViewers()) {
                if (v != closing) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ===================== PERSISTANCE (un fichier par joueur) =====================

    private File fileOf(UUID u) {
        return new File(dataFolder, u.toString() + ".yml");
    }

    @SuppressWarnings("unchecked")
    private ItemStack[] loadContents(UUID u) {
        File f = fileOf(u);
        if (!f.exists()) return null;
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            List<ItemStack> list = (List<ItemStack>) (List<?>) cfg.getList("contents");
            if (list == null) return null;
            ItemStack[] arr = new ItemStack[SIZE];
            for (int i = 0; i < SIZE && i < list.size(); i++) arr[i] = list.get(i);
            return arr;
        } catch (Throwable t) {
            getLogger().warning("[BigEnderChest] chargement KO pour " + u + " : " + t.getMessage());
            return null;
        }
    }

    private void saveContents(UUID u, ItemStack[] contents) {
        try {
            File f = fileOf(u);
            // On charge l'existant pour ne pas ecraser le marqueur "migrated".
            YamlConfiguration cfg = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
            cfg.set("contents", new ArrayList<>(Arrays.asList(contents)));
            if (!dataFolder.exists()) dataFolder.mkdirs();
            cfg.save(f);
        } catch (Throwable e) {
            // Throwable (pas seulement IOException) : certains items exotiques peuvent lever
            // une erreur de serialisation qui, sinon, annulerait la sauvegarde en silence.
            getLogger().warning("[BigEnderChest] sauvegarde KO pour " + u + " : " + e);
        }
    }

    /** A deja migre son ancien ender chest vanilla ? */
    private boolean isMigrated(UUID u) {
        File f = fileOf(u);
        if (!f.exists()) return false;
        return YamlConfiguration.loadConfiguration(f).getBoolean("migrated", false);
    }

    /** Marque le joueur comme migre et enregistre le contenu. */
    private void markMigrated(UUID u, ItemStack[] contents) {
        try {
            File f = fileOf(u);
            YamlConfiguration cfg = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
            cfg.set("contents", new ArrayList<>(Arrays.asList(contents)));
            cfg.set("migrated", true);
            if (!dataFolder.exists()) dataFolder.mkdirs();
            cfg.save(f);
        } catch (IOException e) {
            getLogger().warning("[BigEnderChest] marquage migration KO pour " + u + " : " + e.getMessage());
        }
    }

    /** Verse les objets de l'ender chest vanilla (27 slots) dans les emplacements libres du menu. */
    private void mergeVanilla(Inventory inv, Player owner) {
        for (ItemStack it : owner.getEnderChest().getContents()) {
            if (it != null && !it.getType().isAir()) inv.addItem(it.clone());
        }
    }

    // ===================== UTILITAIRES =====================

    private Inventory topInventory(Player p) {
        try { return p.getOpenInventory().getTopInventory(); }
        catch (Throwable t) { return null; }
    }

    /** Identifie nos menus + retient le proprietaire pour la sauvegarde. */
    private static final class EnderHolder implements InventoryHolder {
        final UUID owner;
        Inventory inventory;
        EnderHolder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
