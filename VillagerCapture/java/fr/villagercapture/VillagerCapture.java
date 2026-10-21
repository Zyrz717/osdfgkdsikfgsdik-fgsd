package fr.villagercapture;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * VillagerCapture - Optaland
 *
 * Permet de ramasser un villageois dans l'inventaire.
 *
 *   CAPTURE : sneak + clic droit sur un villageois, MAIN VIDE.
 *             -> le villageois disparait, le joueur recoit un oeuf
 *                "Villageois capture" (metier, niveau, nom, trades, tout est garde).
 *   POSE    : clic droit avec l'oeuf sur un bloc -> le MEME villageois reapparait.
 *
 * Permission : villagercapture.use (donnee a tout le monde par defaut ;
 *              un "lp group default permission set villagercapture.use false"
 *              la retire).
 *
 * Technique : l'entite complete est serialisee en NBT (API Paper
 * UnsafeValues#serializeEntity) et stockee dans l'item. La pose desactive
 * le comportement vanilla de l'oeuf et recree l'entite serialisee.
 * Les appels Paper passent par reflexion pour ne jamais casser la compilation
 * si la signature change d'une version a l'autre.
 *
 * CORRECTIF "Pose impossible" : un villageois marque non persistant par un autre
 * plugin etait serialise VIDE par Paper (pas d'id d'entite) -> l'oeuf ne pouvait
 * plus etre pose ("Skipping Entity with id"). Desormais :
 *   - a la capture, le villageois est rendu persistant avant la serialisation, et
 *     la capture est refusee si les donnees sont incompletes (le villageois reste) ;
 *   - l'oeuf stocke aussi metier / niveau / biome / nom en clair ;
 *   - a la pose, un id manquant est reconstitue ; si les donnees sont vides
 *     (anciens oeufs casses), un villageois du meme metier/niveau/nom est recree
 *     (ses anciens echanges sont alors perdus).
 *
 * GARDE-METIER (villageois qui perdent leurs echanges au redemarrage) :
 *   un villageois avec qui personne n'a encore echange (XP 0) perd son metier, donc
 *   ses echanges, des que son poste de travail "disparait" de sa memoire. Au
 *   redemarrage, Paper peut verifier ce poste avant que ses donnees soient chargees :
 *   le villageois croit son poste detruit et ses echanges sont re-tires.
 *   Desormais la position du poste est memorisee dans le villageois (PDC
 *   villagercapture:jobsite) au chargement, toutes les 10 s et au dechargement.
 *   Si le villageois va perdre son metier alors que ce poste est TOUJOURS en place
 *   (ou dans un chunk pas encore charge), la perte est annulee et le poste lui est
 *   rendu. Casser le poste reste le moyen normal de changer ses echanges.
 *   /villagers (OP/console) : statistiques ; /villagers snapshot : memorisation forcee.
 */
public class VillagerCapture extends LoadedPlugin implements Listener {

    static final String PERM = "villagercapture.use";

    private NamespacedKey keyData;
    private NamespacedKey keyMarker;
    private NamespacedKey keyProf, keyLevel, keyType, keyName;
    private NamespacedKey keyJob;

    private BukkitTask snapTask;
    private final Map<UUID, Long> lastKeepLog = new HashMap<>();
    private long keptCount = 0, allowedCount = 0;
    private String lastKept = "-";

    @Override
    public void onEnable() {
        keyJob = new NamespacedKey("villagercapture", "jobsite");
        keyData = new NamespacedKey("villagercapture", "entity");
        keyMarker = new NamespacedKey("villagercapture", "captured");
        keyProf = new NamespacedKey("villagercapture", "profession");
        keyLevel = new NamespacedKey("villagercapture", "level");
        keyType = new NamespacedKey("villagercapture", "vtype");
        keyName = new NamespacedKey("villagercapture", "name");

        try {
            if (Bukkit.getPluginManager().getPermission(PERM) == null) {
                Bukkit.getPluginManager().addPermission(new Permission(PERM,
                        "Capturer / reposer un villageois", PermissionDefault.TRUE));
            }
        } catch (Throwable ignored) {}

        // Une ancienne instance (rechargement) encore inscrite traiterait les events en double.
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

        try {
            Bukkit.getPluginManager().registerEvents(this, getHost());
            getLogger().info("[VillagerCapture] actif - sneak + clic droit main vide sur un villageois.");
        } catch (Throwable t) {
            getLogger().severe("[VillagerCapture] impossible d'enregistrer le listener : " + t);
        }

        // Garde-metier : memorisation immediate puis toutes les 10 s.
        try {
            int n = snapshotAll();
            getLogger().info("[VillagerCapture] garde-metier actif : poste de travail memorise pour " + n + " villageois charges.");
            snapTask = Bukkit.getScheduler().runTaskTimer((Plugin) getHost(), () -> snapshotAll(), 200L, 200L);
        } catch (Throwable t) {
            getLogger().warning("[VillagerCapture] garde-metier : tache de memorisation impossible : " + t);
        }
        try { registerCommand("villagers", this::onVillagersCommand); } catch (Throwable t) {
            getLogger().warning("[VillagerCapture] commande /villagers impossible : " + t);
        }
    }

    @Override
    public void onDisable() {
        try { snapshotAll(); } catch (Throwable ignored) {}
        try { if (snapTask != null) snapTask.cancel(); } catch (Throwable ignored) {}
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        getLogger().info("[VillagerCapture] desactive.");
    }

    // ===================== GARDE-METIER =====================

    /** Memorise le poste de travail de tous les villageois charges. */
    private int snapshotAll() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Villager v : w.getEntitiesByClass(Villager.class)) {
                if (snapshot(v)) n++;
            }
        }
        return n;
    }

    /** Copie la memoire JOB_SITE du villageois dans son PDC (sauvegarde avec l'entite). */
    private boolean snapshot(Villager v) {
        try {
            Location js = v.getMemory(MemoryKey.JOB_SITE);
            if (js == null || js.getWorld() == null) return false;
            String s = js.getWorld().getName() + ";" + js.getBlockX() + ";" + js.getBlockY() + ";" + js.getBlockZ();
            PersistentDataContainer pdc = v.getPersistentDataContainer();
            if (!s.equals(pdc.get(keyJob, PersistentDataType.STRING))) pdc.set(keyJob, PersistentDataType.STRING, s);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        // Au chargement la memoire vient tout juste d'etre relue depuis le disque :
        // c'est le bon moment pour noter le poste, avant toute verification.
        for (Entity ent : e.getEntities()) if (ent instanceof Villager v) snapshot(v);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent e) {
        for (Entity ent : e.getEntities()) if (ent instanceof Villager v) snapshot(v);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCareerChange(VillagerCareerChangeEvent e) {
        if (!"LOSING_JOB".equals(String.valueOf(e.getReason()))) return;
        Villager v = e.getEntity();
        String why;
        try { why = keepReason(v); } catch (Throwable t) { why = null; }
        if (why == null) { allowedCount++; return; }
        e.setCancelled(true);
        keptCount++;
        Location l = v.getLocation();
        lastKept = profKey(v) + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
        long now = System.currentTimeMillis();
        Long last = lastKeepLog.get(v.getUniqueId());
        if (last == null || now - last > 600_000L) {
            if (lastKeepLog.size() > 5000) lastKeepLog.clear();
            lastKeepLog.put(v.getUniqueId(), now);
            getLogger().info("[VillagerCapture] garde-metier : " + profKey(v) + " en " + l.getWorld().getName() + " "
                    + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ() + " garde ses echanges (" + why + ").");
        }
    }

    /**
     * Raison de garder le metier, ou null pour laisser faire vanilla
     * (poste reellement casse -> le joueur peut changer les echanges).
     */
    private String keepReason(Villager v) {
        String prof = profKey(v);
        if (prof == null || prof.equals("none") || prof.equals("nitwit")) return null;
        Location vl = v.getLocation();
        String s = v.getPersistentDataContainer().get(keyJob, PersistentDataType.STRING);
        if (s != null) {
            String[] p = s.split(";");
            if (p.length != 4) return null;
            World w = Bukkit.getWorld(p[0]);
            if (w == null) return null;
            int x, y, z;
            try { x = Integer.parseInt(p[1]); y = Integer.parseInt(p[2]); z = Integer.parseInt(p[3]); } catch (NumberFormatException ex) { return null; }
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                restoreJobSite(v, w, x, y, z);
                return "poste dans un chunk pas encore charge";
            }
            if (prof.equals(workstationProfession(w.getBlockAt(x, y, z).getType()))) {
                restoreJobSite(v, w, x, y, z);
                return "poste toujours en place";
            }
            return null;
        }
        // Rien de memorise (villageois jamais vu par le plugin) : poste du bon metier colle au villageois ?
        World w = vl.getWorld();
        if (w == null) return null;
        int bx = vl.getBlockX(), by = vl.getBlockY(), bz = vl.getBlockZ();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            int x = bx + dx, y = by + dy, z = bz + dz;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (prof.equals(workstationProfession(w.getBlockAt(x, y, z).getType()))) {
                restoreJobSite(v, w, x, y, z);
                return "poste a cote du villageois";
            }
        }
        return null;
    }

    /** Rend la memoire du poste (seulement si le villageois est assez pres pour le valider, 16 blocs). */
    private void restoreJobSite(Villager v, World w, int x, int y, int z) {
        try {
            Location vl = v.getLocation();
            if (vl.getWorld() == null || !vl.getWorld().equals(w)) return;
            double dx = x + 0.5 - vl.getX(), dy = y + 0.5 - vl.getY(), dz = z + 0.5 - vl.getZ();
            if (dx * dx + dy * dy + dz * dz > 16 * 16) return;
            v.setMemory(MemoryKey.JOB_SITE, new Location(w, x, y, z));
        } catch (Throwable ignored) {}
    }

    private static String profKey(Villager v) {
        try {
            Object p = v.getProfession();
            if (p instanceof org.bukkit.Keyed k) return k.getKey().getKey().toLowerCase(Locale.ROOT);
            String raw = String.valueOf(p);
            int i = raw.lastIndexOf(':');
            return (i >= 0 ? raw.substring(i + 1) : raw).toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Metier associe a un bloc de poste de travail (null si ce n'en est pas un). */
    private static String workstationProfession(Material m) {
        switch (m.name()) {
            case "LECTERN": return "librarian";
            case "COMPOSTER": return "farmer";
            case "BLAST_FURNACE": return "armorer";
            case "SMOKER": return "butcher";
            case "CARTOGRAPHY_TABLE": return "cartographer";
            case "BREWING_STAND": return "cleric";
            case "BARREL": return "fisherman";
            case "FLETCHING_TABLE": return "fletcher";
            case "CAULDRON": case "WATER_CAULDRON": case "LAVA_CAULDRON": case "POWDER_SNOW_CAULDRON": return "leatherworker";
            case "STONECUTTER": return "mason";
            case "LOOM": return "shepherd";
            case "SMITHING_TABLE": return "toolsmith";
            case "GRINDSTONE": return "weaponsmith";
            default: return null;
        }
    }

    private boolean onVillagersCommand(CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (!(sender.isOp() || !(sender instanceof Player))) {
            sender.sendMessage(PREFIX.append(Component.text("Commande reservee aux OP.", NamedTextColor.RED)));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("snapshot")) {
            int n = snapshotAll();
            sender.sendMessage(PREFIX.append(Component.text("Poste memorise pour " + n + " villageois.", NamedTextColor.GREEN)));
            return true;
        }
        int total = 0, withJob = 0, stored = 0, jobless = 0, traded = 0;
        Map<String, Integer> byProf = new TreeMap<>();
        for (World w : Bukkit.getWorlds()) {
            for (Villager v : w.getEntitiesByClass(Villager.class)) {
                total++;
                String p = profKey(v);
                byProf.merge(String.valueOf(p), 1, Integer::sum);
                try { if (v.getMemory(MemoryKey.JOB_SITE) != null) withJob++; } catch (Throwable ignored) {}
                if (v.getPersistentDataContainer().has(keyJob, PersistentDataType.STRING)) stored++;
                if ("none".equals(p)) jobless++;
                try { if (v.getVillagerExperience() > 0) traded++; } catch (Throwable ignored) {}
            }
        }
        sender.sendMessage(PREFIX.append(Component.text("Villageois charges : " + total + " (sans metier : " + jobless
                + ", deja echange : " + traded + ")", NamedTextColor.WHITE)));
        sender.sendMessage(PREFIX.append(Component.text("Avec poste en memoire : " + withJob + " | poste memorise par le plugin : " + stored, NamedTextColor.GRAY)));
        sender.sendMessage(PREFIX.append(Component.text("Pertes de metier bloquees : " + keptCount + " (derniere : " + lastKept
                + ") | laissees (poste casse) : " + allowedCount, NamedTextColor.GRAY)));
        sender.sendMessage(PREFIX.append(Component.text("Metiers : " + byProf, NamedTextColor.DARK_GRAY)));
        return true;
    }

    // ===================== CAPTURE =====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Villager villager)) return;
        Player p = e.getPlayer();
        if (!p.isSneaking()) return;

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand != null && hand.getType() != Material.AIR) return; // main vide uniquement

        if (!p.hasPermission(PERM)) {
            send(p, "Tu n'as pas la permission de capturer les villageois.", NamedTextColor.RED);
            return;
        }
        if (villager.isDead() || !villager.isValid()) return;

        // Un villageois non persistant (marque par un autre plugin) serait serialise VIDE.
        try {
            if (!villager.isPersistent()) {
                getLogger().info("[VillagerCapture] villageois non persistant capture par " + p.getName()
                        + " -> rendu persistant avant la sauvegarde.");
                villager.setPersistent(true);
            }
        } catch (Throwable ignored) {}

        byte[] data;
        try {
            data = serialize(villager);
        } catch (Throwable t) {
            getLogger().warning("[VillagerCapture] serialisation impossible : " + rootCause(t));
            send(p, "Capture impossible, previens un admin.", NamedTextColor.RED);
            return;
        }
        boolean complete = false;
        try { complete = data != null && data.length > 0 && entityIdOf(data) != null; } catch (Throwable ignored) {}
        if (!complete) {
            getLogger().warning("[VillagerCapture] donnees de " + p.getName() + " incompletes (pas d'id d'entite) -> capture annulee.");
            send(p, "Ce villageois ne peut pas etre capture.", NamedTextColor.RED);
            return;
        }

        ItemStack egg = buildEgg(villager, data);
        e.setCancelled(true); // pas de menu de trade

        try { villager.leaveVehicle(); } catch (Throwable ignored) {}
        try { villager.eject(); } catch (Throwable ignored) {}
        Location loc = villager.getLocation();
        villager.remove();

        // Donne l'oeuf (surplus au sol si inventaire plein).
        for (ItemStack left : p.getInventory().addItem(egg).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }

        try {
            p.playSound(loc, Sound.ENTITY_VILLAGER_YES, 1f, 1.2f);
            p.playSound(loc, Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        } catch (Throwable ignored) {}
        send(p, "Villageois capture ! Clic droit avec l'oeuf pour le reposer.", NamedTextColor.GREEN);
    }

    // ===================== POSE =====================

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        if (!isCapturedEgg(item)) return;

        // Dans tous les cas on bloque le comportement vanilla de l'oeuf
        // (spawn d'un villageois neuf, changement de type d'un spawner...).
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            if (e.getAction() == Action.RIGHT_CLICK_AIR) e.setCancelled(true);
            return;
        }
        // Zone protegee (WorldGuard, claim...) : l'event est deja annule -> on ne pose rien.
        boolean protectedHere = e.isCancelled() || e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY;
        e.setCancelled(true);

        Player p = e.getPlayer();
        if (protectedHere) {
            send(p, "Tu ne peux pas poser de villageois ici.", NamedTextColor.RED);
            return;
        }
        if (!p.hasPermission(PERM)) {
            send(p, "Tu n'as pas la permission de reposer un villageois.", NamedTextColor.RED);
            return;
        }
        Block clicked = e.getClickedBlock();
        if (clicked == null) return;
        Block target = clicked.getRelative(e.getBlockFace());
        Location loc = target.getLocation().add(0.5, 0.0, 0.5);

        byte[] data = item.getItemMeta().getPersistentDataContainer().get(keyData, PersistentDataType.BYTE_ARRAY);
        if (data == null) {
            send(p, "Oeuf invalide (donnees manquantes).", NamedTextColor.RED);
            return;
        }

        // Oeufs crees avant le correctif : l'id d'entite peut manquer dans les donnees.
        byte[] fixed = data;
        boolean hasPayload = false;
        try {
            hasPayload = payloadEntries(data) > 0;
            if (entityIdOf(data) == null && hasPayload) fixed = withEntityId(data, "minecraft:villager");
        } catch (Throwable t) {
            getLogger().warning("[VillagerCapture] lecture des donnees de l'oeuf impossible : " + t);
        }

        Entity ent = null;
        if (hasPayload) {
            try {
                ent = deserializeAndSpawn(fixed, loc);
            } catch (Throwable t) {
                getLogger().warning("[VillagerCapture] pose depuis les donnees impossible : " + rootCause(t));
            }
        }
        boolean fallback = false;
        if (ent == null) {
            // Donnees vides ou illisibles : on recree un villageois avec les infos en clair.
            ent = spawnFallback(item.getItemMeta(), loc);
            fallback = ent != null;
            if (fallback) getLogger().info("[VillagerCapture] " + p.getName() + " : oeuf sans donnees completes, villageois recree a partir du metier/niveau.");
        }
        if (ent == null) {
            send(p, "Pose impossible, previens un admin.", NamedTextColor.RED);
            return;
        }

        // Consomme l'oeuf (sauf en creatif).
        if (p.getGameMode() != GameMode.CREATIVE) {
            if (item.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
            else item.setAmount(item.getAmount() - 1);
        }
        try {
            p.playSound(loc, Sound.ENTITY_VILLAGER_CELEBRATE, 1f, 1f);
        } catch (Throwable ignored) {}
        if (fallback) {
            send(p, "Villageois repose. Ses anciens echanges n'ont pas pu etre recuperes (oeuf cree avant une correction).", NamedTextColor.YELLOW);
        } else {
            send(p, "Villageois repose.", NamedTextColor.GREEN);
        }
    }

    // ===================== ITEM =====================

    private ItemStack buildEgg(Villager v, byte[] data) {
        ItemStack egg = new ItemStack(Material.VILLAGER_SPAWN_EGG);
        ItemMeta meta = egg.getItemMeta();

        String prof = prettyProfession(v);
        int level = 1;
        try { level = v.getVillagerLevel(); } catch (Throwable ignored) {}
        Component custom = null;
        try { custom = v.customName(); } catch (Throwable ignored) {}

        meta.displayName(Component.text("Villageois capture", NamedTextColor.GREEN, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(kv("Metier", prof));
        lore.add(kv("Niveau", String.valueOf(level)));
        if (custom != null) {
            lore.add(Component.text("Nom : ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(custom.decoration(TextDecoration.ITALIC, false)));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Clic droit sur un bloc pour le reposer.", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Ses trades sont conserves.", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyMarker, PersistentDataType.BYTE, (byte) 1);
        pdc.set(keyData, PersistentDataType.BYTE_ARRAY, data);
        try { pdc.set(keyProf, PersistentDataType.STRING, ((org.bukkit.Keyed) v.getProfession()).getKey().getKey()); } catch (Throwable ignored) {}
        try { pdc.set(keyType, PersistentDataType.STRING, ((org.bukkit.Keyed) v.getVillagerType()).getKey().getKey()); } catch (Throwable ignored) {}
        pdc.set(keyLevel, PersistentDataType.INTEGER, level);
        if (custom != null) {
            try { pdc.set(keyName, PersistentDataType.STRING, net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(custom)); } catch (Throwable ignored) {}
        }
        egg.setItemMeta(meta);
        return egg;
    }

    private boolean isCapturedEgg(ItemStack item) {
        if (item == null || item.getType() != Material.VILLAGER_SPAWN_EGG || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(keyMarker, PersistentDataType.BYTE);
    }

    private Component kv(String k, String v) {
        return Component.text(k + " : ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(v, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
    }

    private String prettyProfession(Villager v) {
        try {
            String raw = v.getProfession().toString();
            // Villager.Profession est un enum (anciennes versions) ou un Keyed (recentes) : on normalise.
            int i = raw.lastIndexOf(':');
            if (i >= 0) raw = raw.substring(i + 1);
            raw = raw.toLowerCase(Locale.ROOT);
            return switch (raw) {
                case "none" -> "Aucun";
                case "nitwit" -> "Idiot";
                case "armorer" -> "Armurier";
                case "butcher" -> "Boucher";
                case "cartographer" -> "Cartographe";
                case "cleric" -> "Clerc";
                case "farmer" -> "Fermier";
                case "fisherman" -> "Pecheur";
                case "fletcher" -> "Flechier";
                case "leatherworker" -> "Tanneur";
                case "librarian" -> "Bibliothecaire";
                case "mason" -> "Tailleur de pierre";
                case "shepherd" -> "Berger";
                case "toolsmith" -> "Forgeron d'outils";
                case "weaponsmith" -> "Forgeron d'armes";
                default -> raw;
            };
        } catch (Throwable t) {
            return "?";
        }
    }

    // ===================== SERIALISATION (API Paper, via reflexion) =====================

    /** Bukkit.getUnsafe().serializeEntity(entity) -> byte[] (NBT complet de l'entite). */
    private byte[] serialize(Entity entity) throws Exception {
        Object unsafe = Bukkit.getUnsafe();
        Method m = unsafe.getClass().getMethod("serializeEntity", Entity.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(unsafe, entity);
    }

    /**
     * Bukkit.getUnsafe().deserializeEntity(byte[], World) puis entity.spawnAt(loc).
     * L'UUID n'est pas conserve (preserveUUID=false) pour eviter tout conflit.
     */
    private Entity deserializeAndSpawn(byte[] data, Location loc) throws Exception {
        World world = loc.getWorld();
        Object unsafe = Bukkit.getUnsafe();
        Entity ent = null;
        try {
            Method m = unsafe.getClass().getMethod("deserializeEntity", byte[].class, World.class, boolean.class);
            m.setAccessible(true);
            ent = (Entity) m.invoke(unsafe, data, world, false);
        } catch (NoSuchMethodException ex) {
            Method m = unsafe.getClass().getMethod("deserializeEntity", byte[].class, World.class);
            m.setAccessible(true);
            ent = (Entity) m.invoke(unsafe, data, world);
        }
        if (ent == null) return null;

        Method spawnAt = Entity.class.getMethod("spawnAt", Location.class);
        Object ok = spawnAt.invoke(ent, loc);
        if (ok instanceof Boolean b && !b) {
            getLogger().warning("[VillagerCapture] spawnAt a renvoye false.");
            return null;
        }
        return ent;
    }

    // ===================== REPLI (oeufs sans donnees) =====================

    private Entity spawnFallback(ItemMeta meta, Location loc) {
        if (meta == null || loc.getWorld() == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String prof = pdc.get(keyProf, PersistentDataType.STRING);
        Integer level = pdc.get(keyLevel, PersistentDataType.INTEGER);
        String vtype = pdc.get(keyType, PersistentDataType.STRING);
        Component name = null;
        String nameJson = pdc.get(keyName, PersistentDataType.STRING);
        if (nameJson != null) {
            try { name = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(nameJson); } catch (Throwable ignored) {}
        }
        // Anciens oeufs : on relit le lore ("Metier : ...", "Niveau : ...", "Nom : ...").
        List<Component> lore = null;
        try { lore = meta.lore(); } catch (Throwable ignored) {}
        if (lore != null) {
            for (Component line : lore) {
                Component c = line;
                String plain;
                try { plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c); } catch (Throwable t) { continue; }
                if (prof == null && plain.startsWith("Metier : ")) prof = professionKey(plain.substring(9).trim());
                else if (level == null && plain.startsWith("Niveau : ")) {
                    try { level = Integer.parseInt(plain.substring(9).trim()); } catch (NumberFormatException ignored) {}
                } else if (name == null && plain.startsWith("Nom : ")) {
                    name = Component.text(plain.substring(6));
                }
            }
        }
        Villager v;
        try {
            v = loc.getWorld().spawn(loc, Villager.class);
        } catch (Throwable t) {
            getLogger().warning("[VillagerCapture] repli impossible : " + t);
            return null;
        }
        if (vtype != null) {
            Object t = registryGet("VILLAGER_TYPE", vtype);
            if (t instanceof Villager.Type vt) try { v.setVillagerType(vt); } catch (Throwable ignored) {}
        }
        if (prof != null) {
            Object pr = registryGet("VILLAGER_PROFESSION", prof);
            if (pr instanceof Villager.Profession vp) try { v.setProfession(vp); } catch (Throwable ignored) {}
        }
        if (level != null && level >= 1 && level <= 5) {
            try { v.setVillagerLevel(level); } catch (Throwable ignored) {}
        }
        if (name != null) {
            try { v.customName(name); } catch (Throwable ignored) {}
        }
        try { v.setPersistent(true); } catch (Throwable ignored) {}
        return v;
    }

    /** Registry Bukkit (VILLAGER_PROFESSION / VILLAGER_TYPE) -> valeur pour une cle minecraft:xxx. */
    private Object registryGet(String field, String key) {
        try {
            Class<?> reg = Class.forName("org.bukkit.Registry");
            Object r = reg.getField(field).get(null);
            return reg.getMethod("get", NamespacedKey.class).invoke(r, NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Nom francais du lore -> cle minecraft du metier. */
    private static String professionKey(String fr) {
        switch (fr) {
            case "Aucun": return "none";
            case "Idiot": return "nitwit";
            case "Armurier": return "armorer";
            case "Boucher": return "butcher";
            case "Cartographe": return "cartographer";
            case "Clerc": return "cleric";
            case "Fermier": return "farmer";
            case "Pecheur": return "fisherman";
            case "Flechier": return "fletcher";
            case "Tanneur": return "leatherworker";
            case "Bibliothecaire": return "librarian";
            case "Tailleur de pierre": return "mason";
            case "Berger": return "shepherd";
            case "Forgeron d'outils": return "toolsmith";
            case "Forgeron d'armes": return "weaponsmith";
            default: return fr.toLowerCase(Locale.ROOT);
        }
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while ((c instanceof InvocationTargetException || c.getCause() != null) && c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    // ===================== NBT (format de serializeEntity : NBT gzip) =====================
    // Racine : [10][u16 longueur nom][nom][entrees...][0]

    private static byte[] gunzip(byte[] d) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(d))) {
            return in.readAllBytes();
        }
    }

    private static byte[] gzip(byte[] d) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bo)) {
            out.write(d);
        }
        return bo.toByteArray();
    }

    private static int u16(byte[] b, int p) { return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff); }

    private static int i32(byte[] b, int p) {
        return ((b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
    }

    private static String utf(byte[] b, int p) throws IOException {
        return new DataInputStream(new ByteArrayInputStream(b, p, 2 + u16(b, p))).readUTF();
    }

    /** Position juste apres la charge utile d'un tag de type {@code type} commencant en {@code p}. */
    private static int skip(byte[] b, int p, int type) {
        switch (type) {
            case 1: return p + 1;
            case 2: return p + 2;
            case 3: case 5: return p + 4;
            case 4: case 6: return p + 8;
            case 7: return p + 4 + i32(b, p);
            case 8: return p + 2 + u16(b, p);
            case 9: {
                int et = b[p];
                int n = i32(b, p + 1);
                int q = p + 5;
                for (int i = 0; i < n; i++) q = skip(b, q, et);
                return q;
            }
            case 10: {
                int q = p;
                while (true) {
                    int t = b[q++];
                    if (t == 0) return q;
                    q += 2 + u16(b, q);
                    q = skip(b, q, t);
                }
            }
            case 11: return p + 4 + 4 * i32(b, p);
            case 12: return p + 4 + 8 * i32(b, p);
            default: throw new IllegalStateException("tag NBT inconnu " + type);
        }
    }

    private static int rootStart(byte[] raw) {
        if (raw.length < 3 || raw[0] != 10) throw new IllegalStateException("racine NBT invalide");
        return 3 + u16(raw, 1);
    }

    /** Liste des entrees de la racine : {debut, fin, type, nom}. */
    private static List<Object[]> rootEntries(byte[] raw) throws IOException {
        List<Object[]> out = new ArrayList<>();
        int p = rootStart(raw);
        while (p < raw.length) {
            int type = raw[p];
            if (type == 0) break;
            String name = utf(raw, p + 1);
            int payload = p + 3 + u16(raw, p + 1);
            int end = skip(raw, payload, type);
            out.add(new Object[]{p, end, type, name, payload});
            p = end;
        }
        return out;
    }

    /** id d'entite contenu dans les donnees (null si absent ou vide). */
    private static String entityIdOf(byte[] data) throws IOException {
        byte[] raw = gunzip(data);
        for (Object[] e : rootEntries(raw)) {
            if ("id".equals(e[3]) && (int) e[2] == 8) {
                String v = utf(raw, (int) e[4]);
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /** Nombre d'entrees utiles (hors DataVersion) dans les donnees. */
    private static int payloadEntries(byte[] data) throws IOException {
        int n = 0;
        for (Object[] e : rootEntries(gunzip(data))) if (!"DataVersion".equals(e[3]) && !"id".equals(e[3])) n++;
        return n;
    }

    /** Copie des donnees avec l'id d'entite {@code id} (remplace un id vide/absent). */
    private static byte[] withEntityId(byte[] data, String id) throws IOException {
        byte[] raw = gunzip(data);
        int start = rootStart(raw);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(raw, 0, start);
        DataOutputStream out = new DataOutputStream(bo);
        out.writeByte(8);
        out.writeUTF("id");
        out.writeUTF(id);
        for (Object[] e : rootEntries(raw)) {
            if ("id".equals(e[3])) continue;
            int a = (int) e[0], b = (int) e[1];
            bo.write(raw, a, b - a);
        }
        bo.write(0);
        return gzip(bo.toByteArray());
    }

    // ===================== UTIL =====================

    static final Component PREFIX = Component.text("Villageois ", NamedTextColor.GREEN, TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_GRAY));

    private void send(Player p, String text, NamedTextColor color) {
        p.sendMessage(PREFIX.append(Component.text(text, color)));
    }
}
