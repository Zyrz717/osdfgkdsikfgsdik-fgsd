package fr.shopui;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ShopUI - Optaland
 *
 * Nouveau menu principal /shop avec un fond dessine (texture ItemsAdder
 * "optashop_ui:shop_main") et de gros boutons cliquables, a la place du menu
 * vanilla d'OptaShop. Les categories, objets et prix restent ceux d'OptaShop :
 * un clic sur un bouton ouvre la categorie OptaShop correspondante
 * (ShopGUI.openSection), le bouton VENDRE ouvre /sell.
 *
 * Disposition (coffre 9x6, boutons de 3 colonnes x 2 lignes) :
 *   RESSOURCES  REDSTONE  NOURRITURE
 *   EQUIPEMENT  NETHER    END
 *   SPAWNERS    VENDRE    OEUFS
 * L'icone (vrai item) est dans la case du milieu de la ligne du haut de chaque
 * bouton, les 5 autres cases contiennent un item invisible (optashop_ui:invisible)
 * pour que tout le bouton soit cliquable.
 *
 * Pre-requis ItemsAdder : contents/optashop_ui (font image shop_main + item
 * invisible), puis /iazip. Sans ItemsAdder, le menu s'ouvre quand meme avec
 * un titre texte et des vitres grises a la place des zones invisibles.
 */
public class ShopUI extends LoadedPlugin implements Listener {

    private static final String FONT_IMAGE = "optashop_ui:shop_main";
    private static final String INVISIBLE  = "optashop_ui:invisible";
    private static final int TITLE_OFFSET  = -8; // le titre commence a x=8 dans la GUI

    /** Boutons : id de section OptaShop (ou "sell"), colonne/ligne de depart, libelle. */
    private static final Object[][] LAYOUT = {
            {"resources", 0, 0, "Ressources"}, {"redstone", 3, 0, "Redstone"}, {"food",    6, 0, "Nourriture"},
            {"gear",      0, 2, "Équipement"}, {"nether",   3, 2, "Nether"},   {"end",     6, 2, "End"},
            {"spawner",   0, 4, "Spawners"},   {"sell",     3, 4, "Vendre"},   {"egg",     6, 4, "Œufs de spawn"},
    };

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        final Map<Integer, String> slotToButton = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private String cachedTitle;   // titre texture (calcule une fois)
    private ItemStack invisible;  // item invisible ItemsAdder (cache)

    @Override
    public void onEnable() {
        try {
            Bukkit.getPluginManager().registerEvents(this, getHost());
        } catch (Throwable t) {
            getLogger().severe("[ShopUI] listener impossible a enregistrer : " + t);
        }
        registerCommand("shopui", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player p)) { sender.sendMessage("Joueurs uniquement."); return true; }
            openMain(p);
            return true;
        });
        getLogger().info("[ShopUI] actif - /shop ouvre le menu texture.");
    }

    @Override
    public void onDisable() {
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory();
        }
    }

    // ===================== /shop -> notre menu =====================

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage().trim();
        String[] parts = msg.substring(1).split("\\s+");
        if (parts.length == 0) return;
        String label = parts[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!label.equals("shop") || parts.length > 1) return; // /shop seul
        Player p = e.getPlayer();
        if (!p.hasPermission("optashop.shop")) return; // OptaShop refusera lui-meme
        e.setCancelled(true);
        openMain(p);
    }

    // ===================== MENU PRINCIPAL =====================

    private void openMain(Player p) {
        Object opta = optaShop();
        if (opta == null) {
            p.sendMessage(Component.text("Le shop est indisponible pour le moment.", NamedTextColor.RED));
            return;
        }
        Map<String, Object> sections = sectionsById(opta);

        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 54, titleComponent());
        holder.inv = inv;

        for (Object[] b : LAYOUT) {
            String id = (String) b[0];
            int c0 = (Integer) b[1], r0 = (Integer) b[2];
            String label = (String) b[3];

            Object section = sections.get(id);
            ItemStack icon;
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            if (id.equals("sell")) {
                icon = new ItemStack(Material.EMERALD);
                lore.add(line("Vends tes items en vrac.", NamedTextColor.GRAY));
                lore.add(line("Ouvre le menu /sell.", NamedTextColor.DARK_GRAY));
            } else if (section != null) {
                Material m = sectionIcon(section);
                icon = new ItemStack(m != null ? m : Material.CHEST);
                int count = sectionItemCount(section);
                lore.add(line(count + " article" + (count > 1 ? "s" : ""), NamedTextColor.GRAY));
                lore.add(line("Clic pour ouvrir.", NamedTextColor.DARK_GRAY));
            } else {
                icon = new ItemStack(Material.BARRIER);
                lore.add(line("Categorie indisponible.", NamedTextColor.RED));
            }
            lore.add(Component.empty());
            Component name = Component.text(label, NamedTextColor.WHITE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false);
            ItemMeta im = icon.getItemMeta();
            im.displayName(name);
            im.lore(lore);
            icon.setItemMeta(im);

            // 3 colonnes x 2 lignes ; icone au milieu de la ligne du haut
            for (int dr = 0; dr < 2; dr++) {
                for (int dc = 0; dc < 3; dc++) {
                    int slot = (r0 + dr) * 9 + (c0 + dc);
                    holder.slotToButton.put(slot, id);
                    if (dr == 0 && dc == 1) {
                        inv.setItem(slot, icon);
                    } else {
                        inv.setItem(slot, invisibleItem(name, lore));
                    }
                }
            }
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof Holder)) return;
        String id = holder.slotToButton.get(e.getSlot());
        if (id == null) return;
        if (id.equals("sell")) {
            p.closeInventory();
            Bukkit.getScheduler().runTask(getHost(), () -> p.performCommand("sell"));
            return;
        }
        Object opta = optaShop();
        if (opta == null) return;
        Object section = sectionsById(opta).get(id);
        if (section == null) {
            p.sendMessage(Component.text("Cette categorie n'existe pas dans shop.yml.", NamedTextColor.RED));
            return;
        }
        try {
            Object gui = opta.getClass().getMethod("getGui").invoke(opta);
            Method open = null;
            for (Method m : gui.getClass().getMethods()) {
                if (m.getName().equals("openSection") && m.getParameterCount() == 2) { open = m; break; }
            }
            if (open == null) throw new NoSuchMethodException("openSection");
            open.invoke(gui, p, section);
        } catch (Throwable t) {
            getLogger().warning("[ShopUI] ouverture de la categorie " + id + " KO : " + t);
            p.sendMessage(Component.text("Impossible d'ouvrir cette categorie.", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    // ===================== OptaShop (reflexion) =====================

    private Object optaShop() {
        Plugin pl = Bukkit.getPluginManager().getPlugin("OptaShop");
        return (pl != null && pl.isEnabled()) ? pl : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sectionsById(Object opta) {
        Map<String, Object> out = new HashMap<>();
        try {
            Object manager = opta.getClass().getMethod("getShopManager").invoke(opta);
            Object list = manager.getClass().getMethod("getSections").invoke(manager);
            for (Object s : (Iterable<Object>) list) {
                Object id = s.getClass().getMethod("getId").invoke(s);
                if (id != null) out.put(id.toString().toLowerCase(Locale.ROOT), s);
            }
        } catch (Throwable t) {
            getLogger().warning("[ShopUI] lecture des categories OptaShop KO : " + t);
        }
        return out;
    }

    private Material sectionIcon(Object section) {
        try {
            Object m = section.getClass().getMethod("getIcon").invoke(section);
            return (m instanceof Material mat) ? mat : null;
        } catch (Throwable t) { return null; }
    }

    private int sectionItemCount(Object section) {
        try {
            Object items = section.getClass().getMethod("getItems").invoke(section);
            if (items instanceof java.util.Collection<?> c) return c.size();
        } catch (Throwable ignored) {}
        return 0;
    }

    // ===================== ItemsAdder (reflexion) =====================

    private ClassLoader iaLoader() {
        Plugin ia = Bukkit.getPluginManager().getPlugin("ItemsAdder");
        return ia != null ? ia.getClass().getClassLoader() : getClass().getClassLoader();
    }

    /** Titre : "&f" + offset(-8) + image. Repli : texte simple. */
    private Component titleComponent() {
        if (cachedTitle == null) cachedTitle = buildTitle();
        if (cachedTitle.isEmpty()) return Component.text("Boutique", NamedTextColor.DARK_GRAY);
        return Component.text(cachedTitle, NamedTextColor.WHITE);
    }

    private String buildTitle() {
        // 1) API ItemsAdder : FontImageWrapper
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
                    getLogger().info("[ShopUI] titre texture via l'API ItemsAdder.");
                    return offset + img;
                }
            }
        } catch (Throwable t) {
            getLogger().info("[ShopUI] API ItemsAdder indisponible (" + t.getClass().getSimpleName() + "), essai PlaceholderAPI.");
        }
        // 2) PlaceholderAPI : %img_offset_-8%%img_optashop_ui:shop_main%
        for (String name : new String[]{"%img_" + FONT_IMAGE + "%", "%img_shop_main%"}) {
            String s = papi("%img_offset_" + TITLE_OFFSET + "%" + name);
            if (s != null && !s.contains("%")) {
                getLogger().info("[ShopUI] titre texture via PlaceholderAPI.");
                return s;
            }
        }
        getLogger().warning("[ShopUI] texture de menu introuvable (contents/optashop_ui + /iazip ?) -> titre texte.");
        return "";
    }

    private String papi(String text) {
        try {
            Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (papi == null) return "";
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, papi.getClass().getClassLoader());
            Object out = api.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class).invoke(null, null, text);
            return out == null ? "" : out.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Item invisible ItemsAdder (optashop_ui:invisible), repli : vitre grise. */
    private ItemStack invisibleItem(Component name, List<Component> lore) {
        if (invisible == null) {
            try {
                Class<?> cs = Class.forName("dev.lone.itemsadder.api.CustomStack", true, iaLoader());
                Object stack = cs.getMethod("getInstance", String.class).invoke(null, INVISIBLE);
                if (stack != null) {
                    Object is = stack.getClass().getMethod("getItemStack").invoke(stack);
                    if (is instanceof ItemStack it) invisible = it.clone();
                }
            } catch (Throwable ignored) {}
            if (invisible == null) {
                getLogger().warning("[ShopUI] item " + INVISIBLE + " introuvable -> vitres grises.");
                invisible = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            }
        }
        ItemStack it = invisible.clone();
        ItemMeta m = it.getItemMeta();
        m.displayName(name);
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
