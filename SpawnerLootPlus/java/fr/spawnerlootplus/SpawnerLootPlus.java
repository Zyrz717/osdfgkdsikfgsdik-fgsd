package fr.spawnerlootplus;

import fr.loader.api.LoadedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ajoute des mobs a la table de loot des spawners d'OptaShop.
 *
 * OptaShop garde sa table (net.optaland.shop.spawner.SpawnerLoot) en dur dans
 * son code : tout mob absent retombe sur de la chair putrefiee. Ce plugin
 * injecte les mobs manquants dans cette table au demarrage, par reflexion.
 *
 * Les 23 mobs deja geres par OptaShop gardent leur loot d'origine, sauf
 * IRON_GOLEM dont le loot est corrige ici (fer manquant).
 * CAVE_SPIDER garde son loot d'origine et recoit en plus des toiles d'araignee
 * (voir extend() : on complete la liste existante au lieu de l'ecraser).
 *
 * SECURITE : les spawners de boss (Wither, Ender Dragon, Warden, Elder Guardian)
 * ne font JAMAIS apparaitre de vrai mob (un vrai Wither detruisait les bases et
 * tuait les villageois des qu'un joueur passait a moins de 16 blocs). Leur loot
 * virtuel OptaShop continue de fonctionner normalement.
 *
 * SPAWNERS NON ENREGISTRES : OptaShop n'empeche les vrais mobs QUE pour les spawners
 * qu'il a enregistres (spawners.yml). Un spawner pose autrement (WorldEdit, Axiom,
 * avant l'installation...) fait apparaitre de vrais mobs. Commandes (OP / console) :
 *   /spawnerloot scan [rayon]              liste les spawners autour de toi (enregistres ou non)
 *   /spawnerloot scan x y z [rayon] [monde] idem depuis la console
 *   /spawnerloot adopt x y z [monde]       enregistre ce spawner dans OptaShop (loot virtuel)
 *   /spawnerloot adoptall [rayon]          enregistre tous les spawners non enregistres autour de toi
 * Chaque vrai mob venant d'un spawner non enregistre est aussi signale dans la console.
 */
public class SpawnerLootPlus extends LoadedPlugin implements Listener {

    /** Mobs dont un spawner ne doit jamais faire apparaitre de vraie entite. */
    private static final Set<String> NO_REAL_SPAWN = new HashSet<String>(java.util.Arrays.asList(
            "WITHER", "ENDER_DRAGON", "WARDEN", "ELDER_GUARDIAN"));
    private final Map<String, Long> lastLog = new HashMap<String, Long>();

    private static final String LOOT_CLASS = "net.optaland.shop.spawner.SpawnerLoot";
    private static final String DROP_CLASS = "net.optaland.shop.spawner.SpawnerLoot$Drop";

    private boolean done = false;

    @Override
    public void onEnable() {
        unregisterStaleListeners();
        registerCommand("spawnerloot", (sender, cmd, label, args) -> {
            handleCommand(sender, args);
            return true;
        });
        try {
            Bukkit.getPluginManager().registerEvents(this, getHost());
        } catch (Throwable t) {
            getLogger().warning("[SpawnerLootPlus] listener impossible a enregistrer : " + t);
        }
        if (tryInject()) {
            return;
        }
        Plugin host = Bukkit.getPluginManager().getPlugin("PluginLoader");
        if (host != null) {
            Bukkit.getScheduler().runTaskLater(host, new Runnable() {
                @Override
                public void run() {
                    tryInject();
                }
            }, 100L);
        }
    }

    @Override
    public void onDisable() {
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- spawners non enregistres

    /** Signale les vrais mobs venant d'un spawner qu'OptaShop ne gere pas (non annules). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnmanagedSpawn(SpawnerSpawnEvent e) {
        CreatureSpawner sp = e.getSpawner();
        if (sp == null) {
            return;
        }
        Location l = sp.getBlock().getLocation();
        String key = "unmanaged:" + l.getWorld().getName() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
        long now = System.currentTimeMillis();
        Long last = lastLog.get(key);
        if (last != null && now - last < 1800000L) {
            return; // un message par spawner et par 30 minutes
        }
        lastLog.put(key, now);
        getLogger().info("[SpawnerLootPlus] spawner NON enregistre dans OptaShop (" + e.getEntityType().name() + ") en "
                + l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                + " : il fait apparaitre de vrais mobs. Pour le passer en loot virtuel : spawnerloot adopt "
                + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ() + " " + l.getWorld().getName());
    }

    private Object spawnerManager() throws Exception {
        Plugin ob = Bukkit.getPluginManager().getPlugin("OptaShop");
        if (ob == null || !ob.isEnabled()) {
            throw new IllegalStateException("OptaShop introuvable");
        }
        return ob.getClass().getMethod("getSpawnerManager").invoke(ob);
    }

    private boolean isRegistered(Object mgr, Location l) throws Exception {
        return mgr.getClass().getMethod("get", Location.class).invoke(mgr, l) != null;
    }

    /** Enregistre le spawner du bloc dans OptaShop. Renvoie un message de resultat. */
    private String adopt(Object mgr, Block b, boolean save) throws Exception {
        if (b.getType() != Material.SPAWNER || !(b.getState() instanceof CreatureSpawner)) {
            return "pas de spawner en " + b.getX() + " " + b.getY() + " " + b.getZ();
        }
        if (isRegistered(mgr, b.getLocation())) {
            return "deja enregistre : " + b.getX() + " " + b.getY() + " " + b.getZ();
        }
        EntityType mob = ((CreatureSpawner) b.getState()).getSpawnedType();
        if (mob == null) {
            return "spawner vide (aucun mob) en " + b.getX() + " " + b.getY() + " " + b.getZ();
        }
        Plugin ob = Bukkit.getPluginManager().getPlugin("OptaShop");
        Class<?> dataCls = Class.forName("net.optaland.shop.spawner.SpawnerData", true, ob.getClass().getClassLoader());
        Object data = dataCls.getConstructor(String.class, int.class, int.class, int.class, EntityType.class, int.class)
                .newInstance(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), mob, 1);
        mgr.getClass().getMethod("register", dataCls).invoke(mgr, data);
        if (save) {
            mgr.getClass().getMethod("save").invoke(mgr);
        }
        getLogger().info("[SpawnerLootPlus] spawner " + mob.name() + " en " + b.getWorld().getName() + " "
                + b.getX() + " " + b.getY() + " " + b.getZ() + " enregistre dans OptaShop.");
        return "enregistre : " + mob.name() + " en " + b.getX() + " " + b.getY() + " " + b.getZ();
    }

    private List<Block> spawnersAround(Location c, int r) {
        List<Block> out = new ArrayList<Block>();
        World w = c.getWorld();
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        int minY = Math.max(w.getMinHeight(), cy - r), maxY = Math.min(w.getMaxHeight() - 1, cy + r);
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() == Material.SPAWNER) {
                        out.add(b);
                    }
                }
            }
        }
        return out;
    }

    private void msg(CommandSender s, String text) {
        s.sendMessage(net.kyori.adventure.text.Component.text("[SpawnerLoot] ", net.kyori.adventure.text.format.NamedTextColor.GOLD)
                .append(net.kyori.adventure.text.Component.text(text, net.kyori.adventure.text.format.NamedTextColor.GRAY)));
    }

    private void handleCommand(CommandSender s, String[] a) {
        if (!(s.isOp() || !(s instanceof Player))) {
            msg(s, "Reserve aux OP.");
            return;
        }
        if (a.length == 0) {
            msg(s, "/spawnerloot scan [rayon]  |  /spawnerloot adopt x y z [monde]  |  /spawnerloot adoptall [rayon]");
            return;
        }
        try {
            Object mgr = spawnerManager();
            String sub = a[0].toLowerCase(java.util.Locale.ROOT);
            if (sub.equals("adopt")) {
                if (a.length < 4) { msg(s, "Usage : /spawnerloot adopt x y z [monde]"); return; }
                World w = a.length >= 5 ? Bukkit.getWorld(a[4]) : (s instanceof Player ? ((Player) s).getWorld() : Bukkit.getWorlds().get(0));
                if (w == null) { msg(s, "Monde introuvable."); return; }
                Block b = w.getBlockAt(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                msg(s, adopt(mgr, b, true));
                return;
            }
            Location center;
            int r;
            if (s instanceof Player && (a.length <= 2)) {
                center = ((Player) s).getLocation();
                r = a.length >= 2 ? Integer.parseInt(a[1]) : 16;
            } else {
                if (a.length < 4) { msg(s, "Depuis la console : /spawnerloot " + sub + " x y z [rayon] [monde]"); return; }
                World w = a.length >= 6 ? Bukkit.getWorld(a[5]) : Bukkit.getWorlds().get(0);
                if (w == null) { msg(s, "Monde introuvable."); return; }
                center = new Location(w, Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                r = a.length >= 5 ? Integer.parseInt(a[4]) : 16;
            }
            r = Math.max(1, Math.min(32, r));
            List<Block> found = spawnersAround(center, r);
            if (sub.equals("scan")) {
                msg(s, found.size() + " spawner(s) dans un rayon de " + r + " blocs :");
                for (Block b : found) {
                    EntityType t = b.getState() instanceof CreatureSpawner ? ((CreatureSpawner) b.getState()).getSpawnedType() : null;
                    msg(s, " - " + (t == null ? "?" : t.name()) + " en " + b.getX() + " " + b.getY() + " " + b.getZ()
                            + (isRegistered(mgr, b.getLocation()) ? "  (OptaShop : ok)" : "  (NON enregistre -> vrais mobs)"));
                }
                return;
            }
            if (sub.equals("adoptall")) {
                int n = 0;
                for (Block b : found) {
                    if (!isRegistered(mgr, b.getLocation())) {
                        msg(s, adopt(mgr, b, false));
                        n++;
                    }
                }
                if (n > 0) {
                    mgr.getClass().getMethod("save").invoke(mgr);
                }
                msg(s, n + " spawner(s) enregistre(s) dans OptaShop.");
                return;
            }
            msg(s, "Sous-commande inconnue.");
        } catch (NumberFormatException ex) {
            msg(s, "Coordonnees / rayon invalides.");
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            msg(s, "Erreur : " + c);
            getLogger().warning("[SpawnerLootPlus] commande KO : " + c);
        }
    }

    // ---------------------------------------------------------------- securite boss

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent e) {
        if (!NO_REAL_SPAWN.contains(e.getEntityType().name())) {
            return;
        }
        e.setCancelled(true);
        logBlocked(e.getEntityType().name(), e.getLocation());
    }

    /** Filet de securite : toute apparition "SPAWNER" d'un boss est annulee. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER) {
            return;
        }
        if (!NO_REAL_SPAWN.contains(e.getEntityType().name())) {
            return;
        }
        e.setCancelled(true);
        logBlocked(e.getEntityType().name(), e.getLocation());
    }

    private void logBlocked(String mob, org.bukkit.Location l) {
        if (l == null || l.getWorld() == null) {
            return;
        }
        String key = l.getWorld().getName() + ":" + l.getBlockX() / 16 + ":" + l.getBlockZ() / 16 + ":" + mob;
        long now = System.currentTimeMillis();
        Long last = lastLog.get(key);
        if (last != null && now - last < 600000L) {
            return; // un message par zone et par 10 minutes
        }
        lastLog.put(key, now);
        getLogger().info("[SpawnerLootPlus] apparition reelle de " + mob + " bloquee pres de "
                + l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                + " (le loot virtuel du spawner continue).");
    }

    private void unregisterStaleListeners() {
        try {
            String myClass = getClass().getName();
            for (HandlerList hl : HandlerList.getHandlerLists()) {
                for (RegisteredListener rl : hl.getRegisteredListeners()) {
                    Object l = rl.getListener();
                    if (l != null && l != this && l.getClass().getName().equals(myClass)) {
                        try { hl.unregister(rl); } catch (Throwable ignored) { }
                    }
                }
            }
        } catch (Throwable ignored) { }
    }

    private boolean tryInject() {
        if (done) {
            return true;
        }
        try {
            Class<?> lootClass = Class.forName(LOOT_CLASS);
            Class<?> dropClass = Class.forName(DROP_CLASS);

            Constructor<?> ctor = null;
            for (Constructor<?> c : dropClass.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 4 && p[0] == Material.class && p[1] == int.class
                        && p[2] == int.class && p[3] == double.class) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) {
                getLogger().warning("[SpawnerLootPlus] Constructeur Drop(Material,int,int,double) introuvable, abandon.");
                return false;
            }
            ctor.setAccessible(true);

            Field tableField = null;
            for (Field f : lootClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && Map.class.isAssignableFrom(f.getType())) {
                    tableField = f;
                    break;
                }
            }
            if (tableField == null) {
                getLogger().warning("[SpawnerLootPlus] Table de loot introuvable dans SpawnerLoot, abandon.");
                return false;
            }
            tableField.setAccessible(true);

            Object raw = tableField.get(null);
            if (!(raw instanceof Map)) {
                getLogger().warning("[SpawnerLootPlus] Champ table inattendu, abandon.");
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> table = (Map<Object, Object>) raw;

            boolean stringKeys = false;
            for (Object k : table.keySet()) {
                stringKeys = (k instanceof String);
                break;
            }

            int before = table.size();

            add(table, ctor, stringKeys, "WARDEN",
                    d("SCULK", 2, 6, 1.0),
                    d("SCULK_VEIN", 1, 4, 0.80),
                    d("SCULK_SENSOR", 0, 1, 0.30),
                    d("SCULK_CATALYST", 0, 1, 0.25),
                    d("SCULK_SHRIEKER", 0, 1, 0.15),
                    d("ECHO_SHARD", 0, 1, 0.10),
                    d("CALIBRATED_SCULK_SENSOR", 0, 1, 0.05));
            add(table, ctor, stringKeys, "DROWNED", d("ROTTEN_FLESH", 0, 2, 1.0), d("COPPER_INGOT", 0, 1, 0.11));
            add(table, ctor, stringKeys, "BOGGED", d("BONE", 0, 2, 1.0), d("ARROW", 0, 2, 1.0), d("BROWN_MUSHROOM", 0, 1, 0.5));
            add(table, ctor, stringKeys, "ZOMBIE_VILLAGER", d("ROTTEN_FLESH", 0, 2, 1.0), d("EMERALD", 0, 1, 0.10));
            add(table, ctor, stringKeys, "VILLAGER", d("EMERALD", 1, 2, 1.0));
            add(table, ctor, stringKeys, "WANDERING_TRADER", d("EMERALD", 0, 1, 0.40));
            add(table, ctor, stringKeys, "PHANTOM", d("PHANTOM_MEMBRANE", 0, 1, 1.0));
            add(table, ctor, stringKeys, "SHULKER", d("SHULKER_SHELL", 0, 1, 0.50));
            add(table, ctor, stringKeys, "GUARDIAN", d("PRISMARINE_SHARD", 0, 2, 1.0), d("PRISMARINE_CRYSTALS", 0, 1, 0.40), d("COD", 0, 1, 0.40));
            add(table, ctor, stringKeys, "ELDER_GUARDIAN", d("PRISMARINE_SHARD", 0, 2, 1.0), d("WET_SPONGE", 1, 1, 1.0));
            add(table, ctor, stringKeys, "PILLAGER", d("ARROW", 0, 2, 1.0), d("EMERALD", 0, 1, 0.10));
            add(table, ctor, stringKeys, "VINDICATOR", d("EMERALD", 0, 1, 0.20), d("IRON_AXE", 0, 1, 0.03));
            add(table, ctor, stringKeys, "EVOKER", d("EMERALD", 0, 1, 0.50), d("TOTEM_OF_UNDYING", 1, 1, 1.0));
            add(table, ctor, stringKeys, "RAVAGER", d("SADDLE", 1, 1, 1.0));
            add(table, ctor, stringKeys, "BREEZE", d("BREEZE_ROD", 1, 2, 1.0));
            add(table, ctor, stringKeys, "HOGLIN", d("PORKCHOP", 2, 4, 1.0), d("LEATHER", 0, 1, 0.50));
            add(table, ctor, stringKeys, "ZOGLIN", d("ROTTEN_FLESH", 1, 3, 1.0));
            add(table, ctor, stringKeys, "PIGLIN_BRUTE", d("GOLDEN_AXE", 0, 1, 0.09));
            add(table, ctor, stringKeys, "MOOSHROOM", d("BEEF", 1, 3, 1.0), d("LEATHER", 0, 2, 1.0), d("RED_MUSHROOM", 0, 2, 0.50));
            add(table, ctor, stringKeys, "STRIDER", d("STRING", 2, 5, 1.0));
            add(table, ctor, stringKeys, "SQUID", d("INK_SAC", 1, 3, 1.0));
            add(table, ctor, stringKeys, "GLOW_SQUID", d("GLOW_INK_SAC", 1, 3, 1.0));
            add(table, ctor, stringKeys, "DOLPHIN", d("COD", 0, 1, 1.0));
            add(table, ctor, stringKeys, "POLAR_BEAR", d("COD", 0, 2, 0.75), d("SALMON", 0, 2, 0.25));
            add(table, ctor, stringKeys, "HORSE", d("LEATHER", 0, 2, 1.0));
            add(table, ctor, stringKeys, "SKELETON_HORSE", d("BONE", 0, 2, 1.0));
            add(table, ctor, stringKeys, "PANDA", d("BAMBOO", 1, 2, 1.0));
            add(table, ctor, stringKeys, "PARROT", d("FEATHER", 1, 2, 1.0));
            add(table, ctor, stringKeys, "ARMADILLO", d("ARMADILLO_SCUTE", 1, 1, 0.50));
            add(table, ctor, stringKeys, "CREAKING", d("RESIN_CLUMP", 1, 1, 1.0));
            add(table, ctor, stringKeys, "ENDER_DRAGON",
                    d("EMERALD", 2, 5, 1.0),
                    d("DRAGON_BREATH", 1, 3, 1.0),
                    d("END_STONE", 2, 6, 0.60),
                    d("DRAGON_EGG", 0, 1, 0.01));
            // Spawner Wither (crate Spawner) : uniquement 1 etoile du Nether a chaque fois.
            add(table, ctor, stringKeys, "WITHER",
                    d("NETHER_STAR", 1, 1, 1.0));
            // Golem de fer : l'entree d'origine d'OptaShop ne donnait que le coquelicot.
            // On l'ecrase avec le loot vanilla : 3-5 lingots de fer + 0-2 coquelicots.
            add(table, ctor, stringKeys, "IRON_GOLEM",
                    d("IRON_INGOT", 3, 5, 1.0),
                    d("POPPY", 0, 2, 1.0));

            // Araignee des cavernes : on GARDE le loot d'origine d'OptaShop (ficelle, oeil...)
            // et on ajoute des toiles d'araignee. Si OptaShop n'avait pas d'entree, on part du
            // loot vanilla (ficelle 0-2, oeil d'araignee 1/3).
            extend(table, ctor, stringKeys, "CAVE_SPIDER",
                    new Object[][]{d("STRING", 0, 2, 1.0), d("SPIDER_EYE", 0, 1, 0.33)},
                    d("COBWEB", 1, 1, 0.50));

            int added = table.size() - before;
            getLogger().info("[SpawnerLootPlus] " + added + " mobs ajoutes a la table de loot OptaShop (total " + table.size() + ").");
            done = true;
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            getLogger().warning("[SpawnerLootPlus] Injection impossible : " + t);
            return false;
        }
    }

    /**
     * Complete le loot existant d'un mob (sans l'ecraser). Les drops d'un materiau deja
     * present sont remplaces (ce qui rend l'operation idempotente avec /plreload).
     * Si le mob n'a pas d'entree, on utilise {@code base} + les nouveaux drops.
     */
    private void extend(Map<Object, Object> table, Constructor<?> ctor, boolean stringKeys,
                        String mobName, Object[][] base, Object[]... drops) {
        EntityType type;
        try {
            type = EntityType.valueOf(mobName);
        } catch (IllegalArgumentException ex) {
            return;
        }
        Object key = stringKeys ? (Object) mobName : (Object) type;

        Set<Material> replaced = new HashSet<Material>();
        for (Object[] row : drops) {
            try { replaced.add(Material.valueOf((String) row[0])); } catch (IllegalArgumentException ignored) { }
        }

        List<Object> list = new ArrayList<Object>();
        Object current = table.get(key);
        List<Object> existing = new ArrayList<Object>();
        if (current instanceof Collection) {
            existing.addAll((Collection<?>) current);
        } else if (current != null && current.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(current);
            for (int i = 0; i < n; i++) existing.add(java.lang.reflect.Array.get(current, i));
        }

        if (existing.isEmpty()) {
            for (Object[] row : base) {
                Object drop = newDrop(ctor, row);
                if (drop != null) list.add(drop);
            }
        } else {
            for (Object drop : existing) {
                Material m = materialOf(drop);
                if (m == null || !replaced.contains(m)) list.add(drop);
            }
        }
        for (Object[] row : drops) {
            Object drop = newDrop(ctor, row);
            if (drop != null) list.add(drop);
        }
        if (list.isEmpty()) return;
        table.put(key, list);

        StringBuilder sb = new StringBuilder();
        for (Object drop : list) {
            Material m = materialOf(drop);
            if (sb.length() > 0) sb.append(", ");
            sb.append(m != null ? m.name() : "?");
        }
        getLogger().info("[SpawnerLootPlus] " + mobName + (existing.isEmpty() ? " (loot vanilla)" : " (loot OptaShop conserve)")
                + " : " + sb + ".");
    }

    private static Object newDrop(Constructor<?> ctor, Object[] row) {
        try {
            Material mat = Material.valueOf((String) row[0]);
            return ctor.newInstance(mat, row[1], row[2], row[3]);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Materiau d'un drop OptaShop (premier champ de type Material). */
    private static Material materialOf(Object drop) {
        if (drop == null) return null;
        for (Class<?> c = drop.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == Material.class && !Modifier.isStatic(f.getModifiers())) {
                    try {
                        f.setAccessible(true);
                        return (Material) f.get(drop);
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static Object[] d(String material, int min, int max, double chance) {
        return new Object[]{material, min, max, chance};
    }

    private void add(Map<Object, Object> table, Constructor<?> ctor, boolean stringKeys,
                     String mobName, Object[]... drops) {
        EntityType type;
        try {
            type = EntityType.valueOf(mobName);
        } catch (IllegalArgumentException ex) {
            return;
        }
        Object key = stringKeys ? (Object) mobName : (Object) type;
        // Aucun de ces mobs ne fait partie des 23 entrees d origine d OptaShop :
        // on ecrit sans condition, ce qui permet de corriger un loot avec /plreload.
        List<Object> list = new ArrayList<Object>();
        for (Object[] row : drops) {
            Material mat;
            try {
                mat = Material.valueOf((String) row[0]);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            try {
                list.add(ctor.newInstance(mat, row[1], row[2], row[3]));
            } catch (Throwable t) {
                return;
            }
        }
        if (!list.isEmpty()) {
            table.put(key, list);
        }
    }
}
