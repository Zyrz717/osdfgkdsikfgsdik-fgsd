package fr.exaliumcraft;

import fr.loader.api.LoadedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.command.CommandSender;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.RegisteredListener;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * ExaliumCraft - diagnostic des recettes Exalium (ItemsAdder).
 *   /exaliumcraft diag : liste les recettes Bukkit qui produisent un objet exalium
 *                        et les plugins qui ecoutent le craft.
 */
public class ExaliumCraft extends LoadedPlugin implements org.bukkit.event.Listener {

    @Override
    public void onEnable() {
        registerCommand("exaliumcraft", this::handle);
        try {
            for (org.bukkit.event.HandlerList hl : org.bukkit.event.HandlerList.getHandlerLists()) {
                for (RegisteredListener rl : hl.getRegisteredListeners()) {
                    Object l = rl.getListener();
                    if (l != null && l != this && l.getClass().getName().equals(getClass().getName())) hl.unregister(rl);
                }
            }
            Bukkit.getPluginManager().registerEvents(this, (org.bukkit.plugin.Plugin) getHost());
        } catch (Throwable t) { getLogger().warning("[ExaliumCraft] listener impossible : " + t); }
        getLogger().info("[ExaliumCraft] actif - /exaliumcraft diag");
    }

    @Override
    public void onDisable() {
        try { org.bukkit.event.HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
    }

    // ===== Journal des essais de craft avec de l'exalium (diagnostic) =====
    private final java.util.Map<java.util.UUID, Long> lastPrep = new java.util.HashMap<>();

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onPrepare(PrepareItemCraftEvent e) {
        ItemStack[] m = e.getInventory().getMatrix();
        StringBuilder sb = new StringBuilder();
        boolean ex = false;
        for (int i = 0; i < m.length; i++) {
            String id = iaId(m[i]);
            if (id != null && id.startsWith("exalium:")) ex = true;
            sb.append(m[i] == null || m[i].getType().isAir() ? "." : (id != null ? id.replace("exalium:", "") : m[i].getType().name().toLowerCase())).append(i % 3 == 2 && m.length == 9 ? " / " : ",");
        }
        if (!ex) return;
        java.util.UUID u = e.getView().getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastPrep.get(u);
        if (last != null && now - last < 700) return;
        lastPrep.put(u, now);
        Recipe r = e.getRecipe();
        getLogger().info("[ExaliumCraft] ESSAI " + e.getView().getPlayer().getName() + " grille=" + sb + " recette="
                + ((r instanceof Keyed k) ? k.getKey().toString() : "AUCUNE") + " resultat=" + desc(e.getInventory().getResult())
                + " type=" + e.getInventory().getType());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onCraft(CraftItemEvent e) {
        String rid = iaId(e.getCurrentItem());
        if (rid == null || !rid.startsWith("exalium:")) return;
        getLogger().info("[ExaliumCraft] CRAFT " + e.getWhoClicked().getName() + " " + rid + " annule=" + e.isCancelled()
                + " clic=" + e.getClick() + " action=" + e.getAction());
    }

    private boolean handle(CommandSender s, org.bukkit.command.Command c, String label, String[] args) {
        if (s instanceof org.bukkit.entity.Player p && !p.isOp()) { s.sendMessage("Reserve aux OP."); return true; }
        if (args.length >= 1 && args[0].equalsIgnoreCase("test")) { test(s, args.length >= 2 ? args[1] : null); return true; }
        if (args.length >= 1 && args[0].equalsIgnoreCase("smelt")) { smelt(s); return true; }
        if (args.length >= 2 && args[0].equalsIgnoreCase("inv")) { inv(s, args[1]); return true; }
        int n = 0;
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r;
            try { r = it.next(); } catch (Throwable t) { continue; }
            ItemStack res = r.getResult();
            String key = (r instanceof Keyed k) ? k.getKey().toString() : "?";
            String rid = iaId(res);
            boolean match = key.contains("exalium") || (rid != null && rid.startsWith("exalium:"));
            if (!match) continue;
            n++;
            StringBuilder sb = new StringBuilder();
            sb.append("[ExaliumCraft] ").append(r.getClass().getSimpleName()).append(' ').append(key)
              .append(" -> ").append(res.getType()).append(" x").append(res.getAmount()).append(" ia=").append(rid);
            if (r instanceof ShapedRecipe sr) {
                sb.append(" shape=").append(String.join("/", sr.getShape()));
                for (Map.Entry<Character, RecipeChoice> e : sr.getChoiceMap().entrySet()) {
                    sb.append(' ').append(e.getKey()).append('=').append(choice(e.getValue()));
                }
            } else if (r instanceof ShapelessRecipe sl) {
                for (RecipeChoice ch : sl.getChoiceList()) sb.append(' ').append(choice(ch));
            }
            getLogger().info(sb.toString());
        }
        getLogger().info("[ExaliumCraft] " + n + " recette(s) exalium dans le registre Bukkit.");
        listeners("PrepareItemCraftEvent", PrepareItemCraftEvent.getHandlerList().getRegisteredListeners());
        listeners("CraftItemEvent", CraftItemEvent.getHandlerList().getRegisteredListeners());
        s.sendMessage("Diagnostic ecrit dans la console (" + n + " recettes).");
        return true;
    }

    /** Simule chaque recette exalium avec des objets neufs (et pour un joueur si donne). */
    private void test(CommandSender s, String playerName) {
        org.bukkit.entity.Player pl = playerName == null ? null : Bukkit.getPlayerExact(playerName);
        org.bukkit.World w = pl != null ? pl.getWorld() : Bukkit.getWorlds().get(0);
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r;
            try { r = it.next(); } catch (Throwable t) { continue; }
            if (!(r instanceof ShapedRecipe sr) || !sr.getKey().getNamespace().equals("exalium")) continue;
            ItemStack[] m = new ItemStack[9];
            String[] shape = sr.getShape();
            Map<Character, RecipeChoice> cm = sr.getChoiceMap();
            for (int row = 0; row < shape.length; row++) {
                for (int col = 0; col < shape[row].length(); col++) {
                    RecipeChoice ch = cm.get(shape[row].charAt(col));
                    ItemStack item = null;
                    if (ch instanceof RecipeChoice.ExactChoice ec) item = ec.getChoices().get(0).clone();
                    else if (ch instanceof RecipeChoice.MaterialChoice mc) item = new ItemStack(mc.getChoices().get(0));
                    m[row * 3 + col] = item;
                }
            }
            String noPlayer, withPlayer = "-";
            try {
                Recipe found = Bukkit.getCraftingRecipe(cloneAll(m), w);
                ItemStack res = Bukkit.craftItem(cloneAll(m), w);
                noPlayer = "recette=" + ((found instanceof Keyed k) ? k.getKey().toString() : "AUCUNE") + " resultat=" + desc(res);
            } catch (Throwable t) { noPlayer = "erreur " + t; }
            if (pl != null) {
                try { withPlayer = desc(Bukkit.craftItem(cloneAll(m), w, pl)); } catch (Throwable t) { withPlayer = "erreur " + t; }
            }
            getLogger().info("[ExaliumCraft] TEST " + sr.getKey().getKey() + " : " + noPlayer + " | pour " + playerName + " : " + withPlayer);
        }
        s.sendMessage("Test ecrit dans la console.");
    }

    /** Objets exalium de l'inventaire d'un joueur compares aux objets neufs. */
    private void inv(CommandSender s, String name) {
        org.bukkit.entity.Player pl = Bukkit.getPlayerExact(name);
        if (pl == null) { s.sendMessage("Joueur hors ligne."); return; }
        int slot = 0;
        for (ItemStack i : pl.getInventory().getContents()) {
            slot++;
            String id = iaId(i);
            if (id == null || !id.startsWith("exalium:")) continue;
            ItemStack fresh = freshIa(id);
            boolean sim = fresh != null && fresh.isSimilar(i);
            getLogger().info("[ExaliumCraft] INV " + name + " slot " + (slot - 1) + " " + id + " x" + i.getAmount()
                    + " identique-au-neuf=" + sim + (sim ? "" : " | joueur=" + i.getItemMeta() + " | neuf=" + (fresh == null ? null : fresh.getItemMeta())));
        }
        s.sendMessage("Inventaire ecrit dans la console.");
    }

    /** Vrai test de cuisson : four + haut fourneau avec de l'exalium brut neuf, loin de tout. */
    private void smelt(CommandSender s) {
        final org.bukkit.World w = Bukkit.getWorlds().get(0);
        final int x = 7000, y = 241, z = -7000;
        w.setChunkForceLoaded(x >> 4, z >> 4, true);
        final org.bukkit.block.Block[] blocks = { w.getBlockAt(x, y, z), w.getBlockAt(x + 2, y, z) };
        blocks[0].setType(org.bukkit.Material.FURNACE);
        blocks[1].setType(org.bukkit.Material.BLAST_FURNACE);
        ItemStack raw = freshIa("exalium:raw_exalium");
        if (raw == null) { s.sendMessage("exalium:raw_exalium introuvable."); return; }
        for (org.bukkit.block.Block b : blocks) {
            org.bukkit.block.Furnace f = (org.bukkit.block.Furnace) b.getState();
            org.bukkit.inventory.FurnaceInventory inv = f.getInventory();
            inv.setSmelting(raw.clone());
            inv.setFuel(new ItemStack(org.bukkit.Material.COAL, 2));
        }
        s.sendMessage("Cuisson lancee, resultat dans 13 s (console).");
        Bukkit.getScheduler().runTaskLater((org.bukkit.plugin.Plugin) getHost(), () -> {
            for (org.bukkit.block.Block b : blocks) {
                try {
                    org.bukkit.block.Furnace f = (org.bukkit.block.Furnace) b.getState();
                    org.bukkit.inventory.FurnaceInventory inv = f.getInventory();
                    getLogger().info("[ExaliumCraft] SMELT " + b.getType() + " : entree=" + desc(inv.getSmelting())
                            + " resultat=" + desc(inv.getResult()) + " combustible=" + desc(inv.getFuel()));
                    inv.clear();
                } catch (Throwable t) {
                    getLogger().info("[ExaliumCraft] SMELT erreur " + t);
                }
                b.setType(org.bukkit.Material.AIR);
            }
            w.setChunkForceLoaded(x >> 4, z >> 4, false);
        }, 260L);
    }

    private static ItemStack[] cloneAll(ItemStack[] m) {
        ItemStack[] c = new ItemStack[m.length];
        for (int i = 0; i < m.length; i++) c[i] = m[i] == null ? null : m[i].clone();
        return c;
    }

    private static String desc(ItemStack i) {
        if (i == null || i.getType().isAir()) return "RIEN";
        return i.getType() + " x" + i.getAmount() + " ia=" + iaId(i);
    }

    private static ItemStack freshIa(String id) {
        try {
            Class<?> cs = iaClass();
            Object st = cs.getMethod("getInstance", String.class).invoke(null, id);
            return st == null ? null : (ItemStack) cs.getMethod("getItemStack").invoke(st);
        } catch (Throwable t) { return null; }
    }

    private void listeners(String name, RegisteredListener[] arr) {
        List<String> l = new ArrayList<>();
        for (RegisteredListener rl : arr) {
            l.add(rl.getPlugin().getName() + "/" + rl.getListener().getClass().getName() + "@" + rl.getPriority() + (rl.isIgnoringCancelled() ? "(ic)" : ""));
        }
        getLogger().info("[ExaliumCraft] ecouteurs " + name + " : " + l);
    }

    private String choice(RecipeChoice ch) {
        if (ch == null) return "vide";
        if (ch instanceof RecipeChoice.MaterialChoice mc) return "MAT" + mc.getChoices();
        if (ch instanceof RecipeChoice.ExactChoice ec) {
            List<String> l = new ArrayList<>();
            for (ItemStack i : ec.getChoices()) l.add(i.getType() + "(ia=" + iaId(i) + ")");
            return "EXACT" + l;
        }
        return ch.getClass().getSimpleName();
    }

    private static Class<?> iaCs;

    private static Class<?> iaClass() throws ClassNotFoundException {
        if (iaCs != null) return iaCs;
        org.bukkit.plugin.Plugin ia = Bukkit.getPluginManager().getPlugin("ItemsAdder");
        ClassLoader cl = ia != null ? ia.getClass().getClassLoader() : ExaliumCraft.class.getClassLoader();
        iaCs = Class.forName("dev.lone.itemsadder.api.CustomStack", true, cl);
        return iaCs;
    }

    private static String iaId(ItemStack i) {
        try {
            if (i == null || !i.hasItemMeta()) return null;
            Class<?> cs = iaClass();
            Object st = cs.getMethod("byItemStack", ItemStack.class).invoke(null, i);
            if (st == null) return null;
            return (String) cs.getMethod("getNamespacedID").invoke(st);
        } catch (Throwable t) {
            return null;
        }
    }
}
