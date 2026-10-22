package fr.antixrayinfo;

import fr.loader.api.LoadedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * AntiXrayInfo - etat de l'anti-xray integre de Paper, monde par monde, et
 * rechargement a chaud apres modification de la config.
 *
 *   /antixray            etat (moteur actif, mode, hauteur max, blocs caches)
 *   /antixray reload     a lancer APRES "paper reload" : reconstruit l'anti-xray de
 *                        chaque monde avec la nouvelle config, sans redemarrer.
 *                        Les chunks deja envoyes aux joueurs restent tels quels
 *                        jusqu'a ce qu'ils soient renvoyes (deco/reco ou s'eloigner).
 */
public class AntiXrayInfo extends LoadedPlugin {

    @Override
    public void onEnable() {
        registerCommand("antixray", this::handle);
        try {
            Bukkit.getScheduler().runTaskLater((org.bukkit.plugin.Plugin) getHost(), () -> {
                for (World w : Bukkit.getWorlds()) getLogger().info("[AntiXrayInfo] " + status(w));
            }, 40L);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onDisable() {}

    private boolean handle(CommandSender s, org.bukkit.command.Command c, String label, String[] args) {
        if (s instanceof Player p && !p.isOp()) { s.sendMessage("Reserve aux OP."); return true; }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            for (World w : Bukkit.getWorlds()) {
                String r;
                try { r = rebuild(w); } catch (Throwable t) { r = "ERREUR " + root(t); }
                out(s, w.getName() + " : " + r);
            }
        }
        for (World w : Bukkit.getWorlds()) out(s, status(w));
        return true;
    }

    private void out(CommandSender s, String m) {
        s.sendMessage("[AntiXray] " + m);
        if (s instanceof Player) getLogger().info("[AntiXrayInfo] " + m);
    }

    private static Object level(World w) throws Exception {
        return w.getClass().getMethod("getHandle").invoke(w);
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    private static Object get(Object o, String name) {
        try { return findField(o.getClass(), name).get(o); } catch (Throwable t) { return "?"; }
    }

    private String status(World w) {
        try {
            Object lvl = level(w);
            Object ctl = findField(lvl.getClass(), "chunkPacketBlockController").get(lvl);
            String cls = ctl.getClass().getSimpleName();
            if (!cls.contains("AntiXray")) return w.getName() + " : anti-xray INACTIF (" + cls + ")";
            int hidden = 0;
            Object og = get(ctl, "obfuscateGlobal");
            if (og instanceof boolean[] arr) for (boolean b : arr) if (b) hidden++;
            Object preset = get(ctl, "presetBlockStatesFull");
            int presetN = preset instanceof Object[] pa ? pa.length : -1;
            return w.getName() + " : anti-xray ACTIF (" + ctl.getClass().getName() + "), mode " + get(ctl, "engineMode") + ", hauteur max " + get(ctl, "maxBlockHeight")
                    + ", rayon " + get(ctl, "updateRadius") + ", etats caches " + hidden + ", faux blocs " + presetN
                    + ", permission " + get(ctl, "usePermission");
        } catch (Throwable t) {
            return w.getName() + " : lecture impossible " + root(t);
        }
    }

    /** Reconstruit le controleur anti-xray du monde avec la config Paper actuelle. */
    private String rebuild(World w) throws Exception {
        Object lvl = level(w);
        Field f = findField(lvl.getClass(), "chunkPacketBlockController");
        Object old = f.get(lvl);
        Object cfg = lvl.getClass().getMethod("paperConfig").invoke(lvl);
        Object anticheat = findField(cfg.getClass(), "anticheat").get(cfg);
        Object ax = findField(anticheat.getClass(), "antiXray").get(anticheat);
        boolean enabled = (boolean) findField(ax.getClass(), "enabled").get(ax);
        ClassLoader cl = old.getClass().getClassLoader();
        String pkg = old.getClass().getPackageName();
        Class<?> iface = Class.forName(pkg + ".ChunkPacketBlockController", true, cl);
        Object neu;
        if (!enabled) {
            neu = findField(iface, "NO_OPERATION_INSTANCE").get(null);
        } else {
            Class<?> axc = Class.forName(pkg + ".ChunkPacketBlockControllerAntiXray", true, cl);
            Executor ex = null;
            if (axc.isInstance(old)) { Object e = get(old, "executor"); if (e instanceof Executor ee) ex = ee; }
            if (ex == null) {
                try { ex = (Executor) Class.forName("net.minecraft.Util", true, cl).getMethod("backgroundExecutor").invoke(null); } catch (Throwable t) { ex = java.util.concurrent.ForkJoinPool.commonPool(); }
            }
            Constructor<?> ctor = null;
            for (Constructor<?> k : axc.getConstructors()) if (k.getParameterCount() == 2) ctor = k;
            if (ctor == null) throw new IllegalStateException("constructeur anti-xray introuvable");
            neu = ctor.newInstance(lvl, ex);
        }
        f.set(lvl, neu);
        return "anti-xray reconstruit (" + (enabled ? "actif" : "desactive") + ")";
    }

    private static String root(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
