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
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
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
 *
 * Les menus de CATEGORIE sont aussi remplaces : OptaShop n'affichait que les
 * 9 premiers items d'une categorie (une seule ligne). Ici : 45 items par page
 * (5 lignes), pagination, bouton retour vers le menu texture. L'affichage de
 * chaque item (nom, prix d'achat, prix de vente) est celui d'OptaShop
 * (ShopGUI.buildShopItem) et le clic ouvre son ecran d'achat (ShopGUI.openBuy).
 * Quand OptaShop veut rouvrir son propre menu principal ou une categorie
 * (bouton "Retour" de l'ecran d'achat, /shop...), on le remplace par le notre.
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

    /** Menu d'une categorie (45 items par page). */
    private static final class SectionHolder implements InventoryHolder {
        Inventory inv;
        Object section;            // net.optaland.shop.model.ShopSection
        int page;
        int pages;
        final Map<Integer, Object> slotToItem = new HashMap<>(); // slot -> ShopItem
        @Override public Inventory getInventory() { return inv; }
    }

    private static final int PER_PAGE = 45;
    private static final int SLOT_PREV = 45, SLOT_BACK = 49, SLOT_NEXT = 53;

    private String cachedTitle;   // titre texture (calcule une fois)
    private ItemStack invisible;  // item invisible ItemsAdder (cache)

    @Override
    public void onEnable() {
        // CORRECTIF "items prenables" : apres /plreload, un menu ouvert par l'ANCIENNE instance
        // (classe differente) n'etait plus reconnu par le nouveau listener -> clics non annules.
        // Desormais : listeners fantomes retires, label /shopui repris, et tout menu d'une
        // ancienne instance est bloque puis rouvert a jour (isStaleMenu).
        unregisterStaleListeners();
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
        forceClaimLabels();
        try { Bukkit.getScheduler().runTaskLater(getHost(), this::forceClaimLabels, 20L); } catch (Throwable ignored) {}
        getLogger().info("[ShopUI] actif - /shop ouvre le menu texture.");
    }

    @Override
    public void onDisable() {
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        for (Player p : Bukkit.getOnlinePlayers()) {
            InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
            if (h instanceof Holder || h instanceof SectionHolder) p.closeInventory();
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

    /** Menu ouvert par une ANCIENNE instance du plugin (classes differentes apres rechargement). */
    private static boolean isStaleMenu(InventoryHolder h) {
        if (h == null || h instanceof Holder || h instanceof SectionHolder) return false;
        String cn = h.getClass().getName();
        return cn.endsWith("ShopUI$Holder") || cn.endsWith("ShopUI$SectionHolder");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStaleClick(InventoryClickEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (!isStaleMenu(h)) return;
        e.setCancelled(true);
        if (e.getWhoClicked() instanceof Player p) {
            Bukkit.getScheduler().runTask(getHost(), () -> { p.closeInventory(); openMain(p); });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
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
        openSection(p, section, 0);
    }

    // ===================== MENU DE CATEGORIE =====================

    @SuppressWarnings("unchecked")
    private void openSection(Player p, Object section, int page) {
        Object opta = optaShop();
        if (opta == null) return;
        List<Object> items = new ArrayList<>();
        try {
            Object list = section.getClass().getMethod("getItems").invoke(section);
            for (Object o : (Iterable<Object>) list) items.add(o);
        } catch (Throwable t) {
            getLogger().warning("[ShopUI] lecture des items KO : " + t);
            return;
        }
        int pages = Math.max(1, (items.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));

        String rawTitle = "&8Boutique";
        try { Object t = section.getClass().getMethod("getTitle").invoke(section); if (t != null) rawTitle = t.toString(); } catch (Throwable ignored) {}
        Component title = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(rawTitle
                + (pages > 1 ? " &8(" + (page + 1) + "/" + pages + ")" : ""));

        SectionHolder holder = new SectionHolder();
        holder.section = section; holder.page = page; holder.pages = pages;
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.inv = inv;

        // items de la page
        Method build = null;
        try {
            Object gui = opta.getClass().getMethod("getGui").invoke(opta);
            int start = page * PER_PAGE;
            for (int i = start; i < items.size() && i < start + PER_PAGE; i++) {
                Object item = items.get(i);
                if (build == null) {
                    build = gui.getClass().getDeclaredMethod("buildShopItem", item.getClass());
                    build.setAccessible(true);
                }
                Object stack = build.invoke(gui, item);
                if (stack instanceof ItemStack is) {
                    inv.setItem(i - start, is);
                    holder.slotToItem.put(i - start, item);
                }
            }
        } catch (Throwable t) {
            getLogger().warning("[ShopUI] affichage des items KO : " + t);
        }

        // barre du bas
        ItemStack filler = pane(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int s = 45; s < 54; s++) inv.setItem(s, filler);
        inv.setItem(SLOT_BACK, button(Material.ARROW, "« Retour", "Revenir au menu de la boutique"));
        if (page > 0) inv.setItem(SLOT_PREV, button(Material.PAPER, "« Page precedente", "Page " + page + "/" + pages));
        if (page < pages - 1) inv.setItem(SLOT_NEXT, button(Material.PAPER, "Page suivante »", "Page " + (page + 2) + "/" + pages));

        p.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSectionClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof SectionHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof SectionHolder)) return;
        int slot = e.getSlot();
        if (slot == SLOT_BACK) { openMain(p); return; }
        if (slot == SLOT_PREV && holder.page > 0) { openSection(p, holder.section, holder.page - 1); return; }
        if (slot == SLOT_NEXT && holder.page < holder.pages - 1) { openSection(p, holder.section, holder.page + 1); return; }
        Object item = holder.slotToItem.get(slot);
        if (item == null) return;
        Object opta = optaShop();
        if (opta == null) return;
        try {
            Object gui = opta.getClass().getMethod("getGui").invoke(opta);
            Method open = null;
            for (Method m : gui.getClass().getMethods()) {
                if (m.getName().equals("openBuy") && m.getParameterCount() == 3) { open = m; break; }
            }
            if (open == null) throw new NoSuchMethodException("openBuy");
            open.invoke(gui, p, holder.section, item);
        } catch (Throwable t) {
            getLogger().warning("[ShopUI] ouverture de l'ecran d'achat KO : " + t);
            p.sendMessage(Component.text("Impossible d'ouvrir cet article.", NamedTextColor.RED));
        }
    }

    /**
     * OptaShop veut ouvrir SON menu principal ou SA categorie (bouton Retour de
     * l'ecran d'achat, /shop...) : on annule et on ouvre le notre au tick suivant.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOptaShopOpen(InventoryOpenEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (h == null) return;
        String cls = h.getClass().getName();
        if (!cls.startsWith("net.optaland.shop.gui.Holders$")) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        if (cls.endsWith("$MainMenu")) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(getHost(), () -> openMain(p));
        } else if (cls.endsWith("$Section")) {
            Object section = null;
            try {
                for (Field f : h.getClass().getDeclaredFields()) {
                    if (f.getType().getName().endsWith("ShopSection")) { f.setAccessible(true); section = f.get(h); break; }
                }
            } catch (Throwable ignored) {}
            if (section == null) return; // on laisse OptaShop faire
            final Object sec = section;
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(getHost(), () -> openSection(p, sec, 0));
        }
    }

    private ItemStack pane(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack button(Material m, String name, String loreLine) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.WHITE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(loreLine, NamedTextColor.GRAY));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (h instanceof Holder || h instanceof SectionHolder || isStaleMenu(h)) e.setCancelled(true);
    }

    // ===================== rechargement a chaud =====================

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
            for (String label : new String[]{"shopui"}) {
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
                    getLogger().info("[ShopUI] /" + label + " etait pris par " + (current == null ? "personne" : current.getClass().getName()) + " -> repris.");
                }
            }
            if (changed) { try { server.getClass().getMethod("syncCommands").invoke(server); } catch (Throwable ignored) {} }
        } catch (Throwable t) {
            getLogger().warning("[ShopUI] reprise du label impossible : " + t);
        }
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
