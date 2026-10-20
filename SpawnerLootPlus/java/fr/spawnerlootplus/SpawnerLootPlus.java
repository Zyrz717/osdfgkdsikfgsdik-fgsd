package fr.spawnerlootplus;

import fr.loader.api.LoadedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
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
 */
public class SpawnerLootPlus extends LoadedPlugin {

    private static final String LOOT_CLASS = "net.optaland.shop.spawner.SpawnerLoot";
    private static final String DROP_CLASS = "net.optaland.shop.spawner.SpawnerLoot$Drop";

    private boolean done = false;

    @Override
    public void onEnable() {
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
