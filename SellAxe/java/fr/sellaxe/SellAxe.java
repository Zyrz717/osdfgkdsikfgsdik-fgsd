package fr.sellaxe;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * SellAxe - Optaland
 *
 * La "Hache de vente" : clic droit sur un coffre (coffre, double coffre,
 * tonneau, shulker, entonnoir...) -> TOUT le contenu vendable est vendu
 * d'un coup, aux prix de plugins/OptaShop/sell.yml (les memes que /sell).
 * L'argent est verse via Vault (EssentialsX).
 *
 * /sellaxe [joueur] [utilisations]
 *   - sans argument    : te donne une hache illimitee
 *   - joueur           : la donne a ce joueur (illimitee)
 *   - utilisations     : nombre de coffres vendables avant que la hache casse
 *                        (ex: /sellaxe Gusto77 50) ; "inf" ou rien eq illimitee
 *   Qui peut l'utiliser : la console (pratique pour les crates), un joueur
 *   avec la permission sellaxe.give, ou le grade owner (grade.owner).
 *
 * Securite :
 *   - les items avec un NOM personnalise ne sont jamais vendus (cles de crate,
 *     oeufs de villageois captures, pioche temporaire, items speciaux...)
 *   - si le clic est annule par une protection (WorldGuard, claim...), rien
 *     n'est vendu
 */
public class SellAxe extends LoadedPlugin implements Listener {

    static final String PERM_GIVE = "sellaxe.give";
    static final String PERM_USE  = "sellaxe.use";

    private NamespacedKey keyMarker;
    private NamespacedKey keyUses;

    // Prix de vente (copie de OptaShop/sell.yml), recharges si le fichier change.
    private final Map<Material, Double> prices = new HashMap<>();
    private long pricesMtime = -1L;

    private Object economy;            // net.milkbowl.vault.economy.Economy
    private Method depositMethod;      // depositPlayer(OfflinePlayer, double)

    @Override
    public void onEnable() {
        keyMarker = new NamespacedKey("sellaxe", "axe");
        keyUses = new NamespacedKey("sellaxe", "uses");

        try {
            if (Bukkit.getPluginManager().getPermission(PERM_USE) == null) {
                Bukkit.getPluginManager().addPermission(new Permission(PERM_USE,
                        "Utiliser la hache de vente", PermissionDefault.TRUE));
            }
        } catch (Throwable ignored) {}

        loadPrices();
        hookEconomy();

        try {
            Bukkit.getPluginManager().registerEvents(this, getHost());
        } catch (Throwable t) {
            getLogger().severe("[SellAxe] listener impossible a enregistrer : " + t);
        }

        registerCommand("sellaxe", (sender, cmd, label, args) -> {
            boolean console = !(sender instanceof Player);
            if (!console) {
                Player p = (Player) sender;
                if (!(hasExplicit(p, PERM_GIVE) || hasExplicit(p, "grade.owner"))) {
                    p.sendMessage(msg("Commande reservee au grade owner.", NamedTextColor.RED));
                    return true;
                }
            }
            Player target;
            if (args.length >= 1) {
                target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    sender.sendMessage(msg("Joueur introuvable : " + args[0], NamedTextColor.RED));
                    return true;
                }
            } else if (!console) {
                target = (Player) sender;
            } else {
                sender.sendMessage(msg("Usage : /sellaxe <joueur> [utilisations|inf]", NamedTextColor.GRAY));
                return true;
            }
            int uses = -1;
            if (args.length >= 2 && !args[1].equalsIgnoreCase("inf") && !args[1].equalsIgnoreCase("illimite")) {
                try { uses = Math.max(1, Integer.parseInt(args[1])); }
                catch (NumberFormatException ex) {
                    sender.sendMessage(msg("Nombre d'utilisations invalide : " + args[1], NamedTextColor.RED));
                    return true;
                }
            }
            ItemStack axe = buildAxe(uses);
            for (ItemStack left : target.getInventory().addItem(axe).values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), left);
            }
            target.sendMessage(msg("Tu as recu une Hache de vente" + (uses > 0 ? " (" + uses + " utilisations)" : "")
                    + ". Clic droit sur un coffre pour vendre son contenu.", NamedTextColor.GOLD));
            if (target != sender) {
                sender.sendMessage(msg("Hache de vente donnee a " + target.getName() + ".", NamedTextColor.GREEN));
            }
            return true;
        });

        getLogger().info("[SellAxe] actif - " + prices.size() + " prix charges"
                + (economy == null ? " (Vault introuvable : repli sur /eco give)" : "") + ".");
    }

    @Override
    public void onDisable() {
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        getLogger().info("[SellAxe] desactive.");
    }

    // ===================== VENTE =====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = e.getItem();
        if (!isAxe(hand)) return;
        Block block = e.getClickedBlock();
        if (block == null) return;
        BlockState state = block.getState();
        if (!(state instanceof Container container)) return; // pas un coffre : hache normale

        Player p = e.getPlayer();
        e.setCancelled(true); // on n'ouvre pas le coffre

        if (e.useInteractedBlock() == Event.Result.DENY) {
            send(p, "Tu ne peux pas vendre le contenu de ce coffre ici (zone protegee).", NamedTextColor.RED);
            return;
        }
        if (!p.hasPermission(PERM_USE)) {
            send(p, "Tu n'as pas la permission d'utiliser la hache de vente.", NamedTextColor.RED);
            return;
        }

        loadPrices();
        Inventory inv = container.getInventory(); // double coffre inclus
        double total = 0.0;
        int count = 0;
        int skipped = 0;
        ItemStack[] contents = inv.getContents();
        ItemStack[] backup = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) backup[i] = (contents[i] == null) ? null : contents[i].clone();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir()) continue;
            Double price = prices.get(it.getType());
            if (price == null || price <= 0.0) { skipped++; continue; }
            if (hasCustomName(it)) { skipped++; continue; } // items speciaux : jamais vendus
            total += price * it.getAmount();
            count += it.getAmount();
            inv.setItem(i, null);
        }

        if (count == 0) {
            send(p, "Rien de vendable dans ce coffre" + (skipped > 0 ? " (" + skipped + " item(s) non vendables)" : "") + ".", NamedTextColor.GRAY);
            try { p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f); } catch (Throwable ignored) {}
            return;
        }

        total = Math.round(total * 100.0) / 100.0;
        if (!deposit(p, total)) {
            // paiement impossible : on remet les items (rien n'est perdu)
            send(p, "Paiement impossible, previens un admin. Les items sont remis dans le coffre.", NamedTextColor.RED);
            getLogger().warning("[SellAxe] depot impossible pour " + p.getName() + " (" + total + ")");
            // Les items ont ete retires de l'inventaire -> on les remet depuis la copie.
            inv.setContents(backup);
            return;
        }

        try {
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.3f);
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.8f, 1f);
        } catch (Throwable ignored) {}
        send(p, "Coffre vendu : " + count + " item(s) pour " + money(total) + " $"
                + (skipped > 0 ? " (" + skipped + " item(s) non vendables laisses dedans)" : "") + ".", NamedTextColor.GREEN);

        consumeUse(p, hand);
    }

    private boolean hasCustomName(ItemStack it) {
        try {
            ItemMeta m = it.getItemMeta();
            return m != null && m.hasDisplayName();
        } catch (Throwable t) {
            return true; // doute -> on ne vend pas
        }
    }

    /** Decremente le compteur d'utilisations ; casse la hache a 0. */
    private void consumeUse(Player p, ItemStack axe) {
        ItemMeta meta = axe.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Integer uses = pdc.get(keyUses, PersistentDataType.INTEGER);
        if (uses == null || uses < 0) return; // illimitee
        int left = uses - 1;
        if (left <= 0) {
            p.getInventory().setItemInMainHand(null);
            try { p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f); } catch (Throwable ignored) {}
            send(p, "Ta hache de vente est usee et se brise.", NamedTextColor.RED);
            return;
        }
        pdc.set(keyUses, PersistentDataType.INTEGER, left);
        meta.lore(lore(left));
        axe.setItemMeta(meta);
        if (left <= 5) send(p, "Il reste " + left + " utilisation(s) a ta hache de vente.", NamedTextColor.YELLOW);
    }

    // ===================== ITEM =====================

    private ItemStack buildAxe(int uses) {
        ItemStack axe = new ItemStack(Material.GOLDEN_AXE);
        ItemMeta meta = axe.getItemMeta();
        meta.displayName(Component.text("Hache de vente", TextColor.color(0xFFA500), TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore(uses));
        meta.setUnbreakable(true);
        try { meta.addEnchant(Enchantment.UNBREAKING, 1, true); } catch (Throwable ignored) {}
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyMarker, PersistentDataType.BYTE, (byte) 1);
        pdc.set(keyUses, PersistentDataType.INTEGER, uses);
        axe.setItemMeta(meta);
        return axe;
    }

    private List<Component> lore(int uses) {
        List<Component> l = new ArrayList<>();
        l.add(Component.empty());
        l.add(line("Clic droit sur un coffre pour vendre", NamedTextColor.GRAY));
        l.add(line("tout son contenu aux prix du /sell.", NamedTextColor.GRAY));
        l.add(Component.empty());
        l.add(Component.text("Utilisations : ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(uses < 0 ? "illimitees" : String.valueOf(uses), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)));
        l.add(line("Les items renommes ne sont pas vendus.", NamedTextColor.DARK_GRAY));
        return l;
    }

    private boolean isAxe(ItemStack it) {
        if (it == null || it.getType() != Material.GOLDEN_AXE || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(keyMarker, PersistentDataType.BYTE);
    }

    // ===================== PRIX (OptaShop/sell.yml) =====================

    private void loadPrices() {
        try {
            File f = new File("plugins/OptaShop", "sell.yml");
            if (!f.exists()) {
                if (prices.isEmpty()) getLogger().warning("[SellAxe] plugins/OptaShop/sell.yml introuvable.");
                return;
            }
            long mtime = f.lastModified();
            if (mtime == pricesMtime && !prices.isEmpty()) return;
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            Map<Material, Double> fresh = new HashMap<>();
            if (cfg.isConfigurationSection("prices")) {
                for (String key : cfg.getConfigurationSection("prices").getKeys(false)) {
                    Material m = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
                    if (m == null) continue;
                    double v = cfg.getDouble("prices." + key, 0.0);
                    if (v > 0) fresh.put(m, v);
                }
            }
            prices.clear();
            prices.putAll(fresh);
            pricesMtime = mtime;
        } catch (Throwable t) {
            getLogger().warning("[SellAxe] lecture de sell.yml KO : " + t);
        }
    }

    // ===================== ECONOMIE (Vault, reflexion) =====================

    private void hookEconomy() {
        economy = null;
        depositMethod = null;
        try {
            Class<?> ecoClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(ecoClass);
            if (rsp == null) return;
            economy = rsp.getProvider();
            depositMethod = ecoClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
        } catch (Throwable t) {
            economy = null;
        }
    }

    private boolean deposit(Player p, double amount) {
        if (economy == null) hookEconomy();
        if (economy != null && depositMethod != null) {
            try {
                Object resp = depositMethod.invoke(economy, p, amount);
                try {
                    Object ok = resp.getClass().getMethod("transactionSuccess").invoke(resp);
                    if (Boolean.TRUE.equals(ok)) return true;
                } catch (NoSuchMethodException ignored) {
                    return true;
                }
            } catch (Throwable t) {
                getLogger().warning("[SellAxe] Vault depositPlayer KO : " + t);
            }
        }
        // Repli : EssentialsX
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "eco give " + p.getName() + " " + String.format(Locale.ROOT, "%.2f", amount));
        } catch (Throwable t) {
            return false;
        }
    }

    // ===================== PERMISSIONS (LuckPerms explicite) =====================

    private boolean hasExplicit(Player p, String perm) {
        try {
            Plugin lp = Bukkit.getPluginManager().getPlugin("LuckPerms");
            if (lp != null) {
                Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider", true, lp.getClass().getClassLoader());
                Object api = provider.getMethod("get").invoke(null);
                Object um = api.getClass().getMethod("getUserManager").invoke(api);
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

    // ===================== UTIL =====================

    private static String money(double v) {
        String s = String.format(Locale.FRANCE, "%,.2f", v);
        if (s.endsWith(",00")) s = s.substring(0, s.length() - 3);
        return s.replace(' ', ' ').replace(' ', ' ');
    }

    static final Component PREFIX = Component.text("Vente ", TextColor.color(0xFFA500), TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_GRAY));

    private Component msg(String text, NamedTextColor color) {
        return PREFIX.append(Component.text(text, color));
    }

    private void send(Player p, String text, NamedTextColor color) {
        p.sendMessage(msg(text, color));
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
