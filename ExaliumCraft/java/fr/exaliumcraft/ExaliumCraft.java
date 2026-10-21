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
public class ExaliumCraft extends LoadedPlugin {

    @Override
    public void onEnable() {
        registerCommand("exaliumcraft", this::handle);
        getLogger().info("[ExaliumCraft] actif - /exaliumcraft diag");
    }

    @Override
    public void onDisable() {}

    private boolean handle(CommandSender s, org.bukkit.command.Command c, String label, String[] args) {
        if (s instanceof org.bukkit.entity.Player p && !p.isOp()) { s.sendMessage("Reserve aux OP."); return true; }
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
