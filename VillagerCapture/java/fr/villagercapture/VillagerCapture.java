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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 */
public class VillagerCapture extends LoadedPlugin implements Listener {

    static final String PERM = "villagercapture.use";

    private NamespacedKey keyData;
    private NamespacedKey keyMarker;

    @Override
    public void onEnable() {
        keyData = new NamespacedKey("villagercapture", "entity");
        keyMarker = new NamespacedKey("villagercapture", "captured");

        try {
            if (Bukkit.getPluginManager().getPermission(PERM) == null) {
                Bukkit.getPluginManager().addPermission(new Permission(PERM,
                        "Capturer / reposer un villageois", PermissionDefault.TRUE));
            }
        } catch (Throwable ignored) {}

        try {
            Bukkit.getPluginManager().registerEvents(this, getHost());
            getLogger().info("[VillagerCapture] actif - sneak + clic droit main vide sur un villageois.");
        } catch (Throwable t) {
            getLogger().severe("[VillagerCapture] impossible d'enregistrer le listener : " + t);
        }
    }

    @Override
    public void onDisable() {
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        getLogger().info("[VillagerCapture] desactive.");
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

        byte[] data;
        try {
            data = serialize(villager);
        } catch (Throwable t) {
            getLogger().warning("[VillagerCapture] serialisation impossible : " + t);
            send(p, "Capture impossible, previens un admin.", NamedTextColor.RED);
            return;
        }
        if (data == null || data.length == 0) {
            send(p, "Capture impossible, previens un admin.", NamedTextColor.RED);
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

        Entity ent;
        try {
            ent = deserializeAndSpawn(data, loc);
        } catch (Throwable t) {
            getLogger().warning("[VillagerCapture] pose impossible : " + t);
            send(p, "Pose impossible, previens un admin.", NamedTextColor.RED);
            return;
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
        send(p, "Villageois repose.", NamedTextColor.GREEN);
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

    // ===================== UTIL =====================

    static final Component PREFIX = Component.text("Villageois ", NamedTextColor.GREEN, TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_GRAY));

    private void send(Player p, String text, NamedTextColor color) {
        p.sendMessage(PREFIX.append(Component.text(text, color)));
    }
}
