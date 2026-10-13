package fr.spawnerhopper;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SpawnerHopper - Optaland
 *
 * Un spawner OptaShop (stack spawner) pose juste au-dessus d'un hopper vide
 * automatiquement son stockage (le loot qu'il accumule) dans ce hopper.
 * Le hopper fait ensuite son travail vanilla : il pousse les objets dans le
 * coffre (ou autre conteneur) vers lequel il pointe.
 *
 *  - Peu importe l'ordre de pose (spawner puis hopper, ou l'inverse).
 *  - Si le hopper est plein, rien n'est perdu : le loot reste dans le
 *    stockage du spawner et sera transfere quand il y aura de la place.
 *  - L'XP n'est pas concernee (un hopper ne peut pas en stocker) : elle se
 *    recupere toujours dans le menu du spawner.
 *  - Seuls les spawners dans un chunk charge sont traites.
 *
 * Reglages : plugins/PluginLoader/spawnerhopper.yml
 *   interval-ticks      : frequence du transfert (20 = 1 seconde)
 *   max-items-per-cycle : nombre max d'objets deplaces par spawner et par cycle
 *   place-message       : message au joueur quand il pose un spawner sur un
 *                         hopper (ou un hopper sous un spawner)
 *
 * Technique : OptaShop garde ses spawners dans SpawnerManager.spawners
 * (Map<String, SpawnerData>) et le loot de chacun dans
 * SpawnerData.getStorage() (Map<Material, Long>). On y accede par reflexion,
 * comme SpawnerLootPlus : le classloader de PluginLoader ne voit pas les
 * classes d'OptaShop directement.
 */
public class SpawnerHopper extends LoadedPlugin implements Listener {

    private static final String CONFIG_FILE = "spawnerhopper.yml";

    // ---- reglages ----
    private int intervalTicks = 20;
    private int maxItemsPerCycle = 64;
    private boolean placeMessage = true;

    // ---- etat ----
    private BukkitTask task;
    private int hookAttempts = 0;

    // Reflexion OptaShop (resolue une fois, reutilisee ensuite)
    private Method mGetSpawnerManager;
    private Field fSpawners;
    private Method mWorld, mX, mY, mZ, mStorage;

    // ===================== CYCLE DE VIE =====================

    @Override
    public void onEnable() {
        unregisterStaleListeners();
        loadConfig();
        try {
            Bukkit.getPluginManager().registerEvents(this, host());
        } catch (Throwable t) {
            getLogger().warning("[SpawnerHopper] listener impossible a enregistrer : " + t);
        }
        tryStart();
    }

    @Override
    public void onDisable() {
        if (task != null) {
            try { task.cancel(); } catch (Throwable ignored) {}
            task = null;
        }
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        getLogger().info("[SpawnerHopper] desactive.");
    }

    private Plugin host() {
        return (Plugin) getHost();
    }

    /** Supprime tout listener fantome d'un ancien chargement (apres /plreload). */
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

    // ===================== CONFIG =====================

    private void loadConfig() {
        try {
            File dir = host().getDataFolder();
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, CONFIG_FILE);
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            boolean changed = false;
            if (!cfg.contains("interval-ticks")) { cfg.set("interval-ticks", 20); changed = true; }
            if (!cfg.contains("max-items-per-cycle")) { cfg.set("max-items-per-cycle", 64); changed = true; }
            if (!cfg.contains("place-message")) { cfg.set("place-message", true); changed = true; }
            if (changed) {
                cfg.options().header("SpawnerHopper - un spawner OptaShop pose sur un hopper y vide son loot.\n"
                        + "interval-ticks      : frequence du transfert (20 = 1 seconde)\n"
                        + "max-items-per-cycle : objets max deplaces par spawner et par cycle\n"
                        + "place-message       : message quand on pose un spawner sur un hopper");
                try { cfg.save(f); } catch (Throwable ignored) {}
            }
            intervalTicks = Math.max(1, cfg.getInt("interval-ticks", 20));
            maxItemsPerCycle = Math.max(1, cfg.getInt("max-items-per-cycle", 64));
            placeMessage = cfg.getBoolean("place-message", true);
        } catch (Throwable t) {
            getLogger().warning("[SpawnerHopper] config illisible, valeurs par defaut : " + t);
        }
    }

    // ===================== ACCROCHE OPTASHOP =====================

    private void tryStart() {
        if (hook()) {
            start();
            return;
        }
        hookAttempts++;
        if (hookAttempts > 30) {
            getLogger().warning("[SpawnerHopper] OptaShop introuvable apres plusieurs essais, abandon.");
            return;
        }
        // OptaShop n'est peut-etre pas encore charge : on reessaie dans 5 s.
        try {
            Bukkit.getScheduler().runTaskLater(host(), this::tryStart, 100L);
        } catch (Throwable t) {
            getLogger().warning("[SpawnerHopper] impossible de planifier la reprise : " + t);
        }
    }

    private Plugin optaShop() {
        Plugin pl = Bukkit.getPluginManager().getPlugin("OptaShop");
        return (pl != null && pl.isEnabled()) ? pl : null;
    }

    /** Resout les acces reflexion vers OptaShop. Retourne false si OptaShop n'est pas pret. */
    private boolean hook() {
        try {
            Plugin opta = optaShop();
            if (opta == null) return false;

            mGetSpawnerManager = opta.getClass().getMethod("getSpawnerManager");
            Object manager = mGetSpawnerManager.invoke(opta);
            if (manager == null) return false;

            Field f = null;
            try {
                f = manager.getClass().getDeclaredField("spawners");
            } catch (NoSuchFieldException ignored) {
                for (Field c : manager.getClass().getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(c.getType())) { f = c; break; }
                }
            }
            if (f == null) {
                getLogger().warning("[SpawnerHopper] champ 'spawners' introuvable dans SpawnerManager, abandon.");
                return false;
            }
            f.setAccessible(true);
            fSpawners = f;
            return true;
        } catch (Throwable t) {
            getLogger().warning("[SpawnerHopper] accroche OptaShop impossible : " + t);
            return false;
        }
    }

    /** La map vivante des spawners d'OptaShop (relue a chaque cycle, au cas ou OptaShop la remplace). */
    private Map<?, ?> spawners() {
        try {
            Plugin opta = optaShop();
            if (opta == null || mGetSpawnerManager == null || fSpawners == null) return null;
            Object manager = mGetSpawnerManager.invoke(opta);
            if (manager == null) return null;
            Object raw = fSpawners.get(manager);
            return (raw instanceof Map) ? (Map<?, ?>) raw : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void resolveDataMethods(Object data) throws NoSuchMethodException {
        if (mStorage != null) return;
        Class<?> c = data.getClass();
        mWorld = c.getMethod("getWorld");
        mX = c.getMethod("getX");
        mY = c.getMethod("getY");
        mZ = c.getMethod("getZ");
        mStorage = c.getMethod("getStorage");
    }

    private void start() {
        if (task != null) {
            try { task.cancel(); } catch (Throwable ignored) {}
        }
        task = Bukkit.getScheduler().runTaskTimer(host(), this::tick, intervalTicks, intervalTicks);
        Map<?, ?> map = spawners();
        int n = (map != null) ? map.size() : 0;
        getLogger().info("[SpawnerHopper] actif : " + n + " spawner(s) OptaShop suivis, cycle " + intervalTicks
                + " ticks, " + maxItemsPerCycle + " objets max par cycle.");
    }

    // ===================== TRANSFERT =====================

    private void tick() {
        Map<?, ?> map = spawners();
        if (map == null || map.isEmpty()) return;
        List<Object> list;
        try {
            list = new ArrayList<Object>(map.values());
        } catch (Throwable t) {
            return;
        }
        for (Object data : list) {
            if (data == null) continue;
            try {
                process(data);
            } catch (Throwable ignored) {
                // un spawner en erreur ne doit pas bloquer les autres
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void process(Object data) throws Exception {
        resolveDataMethods(data);

        Object rawStorage = mStorage.invoke(data);
        if (!(rawStorage instanceof Map)) return;
        Map<Object, Object> storage = (Map<Object, Object>) rawStorage;
        if (storage.isEmpty()) return;

        String worldName = (String) mWorld.invoke(data);
        int x = ((Number) mX.invoke(data)).intValue();
        int y = ((Number) mY.invoke(data)).intValue();
        int z = ((Number) mZ.invoke(data)).intValue();

        World w = (worldName != null) ? Bukkit.getWorld(worldName) : null;
        if (w == null) return;
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;

        Block spawnerBlock = w.getBlockAt(x, y, z);
        if (spawnerBlock.getType() != Material.SPAWNER) return;

        Block below = w.getBlockAt(x, y - 1, z);
        if (below.getType() != Material.HOPPER) return;

        BlockState st = below.getState();
        if (!(st instanceof Container)) return;
        Inventory inv = ((Container) st).getInventory();

        int budget = maxItemsPerCycle;
        for (Map.Entry<Object, Object> en : new ArrayList<Map.Entry<Object, Object>>(storage.entrySet())) {
            if (budget <= 0) break;

            Material mat = toMaterial(en.getKey());
            if (mat == null || mat.isAir() || !mat.isItem()) continue;

            long count = toLong(en.getValue());
            if (count <= 0) continue;

            int toMove = (int) Math.min(count, Math.min(budget, mat.getMaxStackSize()));
            if (toMove <= 0) continue;

            Map<Integer, ItemStack> left = inv.addItem(new ItemStack(mat, toMove));
            int notMoved = 0;
            if (left != null) {
                for (ItemStack r : left.values()) {
                    if (r != null) notMoved += r.getAmount();
                }
            }
            int moved = toMove - notMoved;
            if (moved <= 0) continue; // hopper plein pour cet objet

            long remaining = count - moved;
            if (remaining <= 0) {
                storage.remove(en.getKey());
            } else {
                storage.put(en.getKey(), sameNumberType(en.getValue(), remaining));
            }
            budget -= moved;
        }
    }

    private static Material toMaterial(Object key) {
        if (key instanceof Material) return (Material) key;
        if (key instanceof String) {
            try { return Material.matchMaterial((String) key); } catch (Throwable ignored) { return null; }
        }
        return null;
    }

    private static long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong(((String) v).trim()); } catch (Throwable ignored) { return 0L; }
        }
        return 0L;
    }

    /** Conserve le type numerique d'origine de la map d'OptaShop (Long attendu). */
    private static Object sameNumberType(Object original, long value) {
        if (original instanceof Integer) return Integer.valueOf((int) value);
        return Long.valueOf(value);
    }

    // ===================== MESSAGE A LA POSE =====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!placeMessage) return;
        Block b = e.getBlockPlaced();
        Material t = b.getType();
        boolean ok;
        if (t == Material.SPAWNER) {
            ok = b.getRelative(BlockFace.DOWN).getType() == Material.HOPPER;
        } else if (t == Material.HOPPER) {
            ok = b.getRelative(BlockFace.UP).getType() == Material.SPAWNER;
        } else {
            return;
        }
        if (!ok) return;
        e.getPlayer().sendMessage(Component.text("SPAWNER", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Le loot de ce spawner ira directement dans le hopper.", NamedTextColor.GRAY)));
    }
}
