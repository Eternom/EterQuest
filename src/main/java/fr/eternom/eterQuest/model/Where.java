package fr.eternom.eterQuest.model;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Où et quand un objectif compte (tout est facultatif) :
 * biomes (« plains », « lush_caves »), dimension (normal, nether, the_end), min-y / max-y, time (day, night),
 * weather (clear, rain, thunder), servers (début du nom : « lobby », « survie »).
 * Les quêtes « spéciales » : pêcher sous l'orage, miner sous la couche 0, chasser la nuit... time : l'heure vue par
 * le joueur (celle du monde, ou celle d'un lieu du village qui la règle).
 */
public record Where(Set<String> biomes, String dimension, Integer minY, Integer maxY, String time, String weather,
                    List<String> servers) {

    public static final Where ANYWHERE = new Where(Set.of(), "", null, null, "", "", List.of());

    public Where {
        biomes = biomes == null ? Set.of() : Set.copyOf(biomes.stream().map(b -> b.toLowerCase(Locale.ROOT)).toList());
        dimension = dimension == null ? "" : dimension.toLowerCase(Locale.ROOT);
        time = time == null ? "" : time.toLowerCase(Locale.ROOT);
        weather = weather == null ? "" : weather.toLowerCase(Locale.ROOT);
        servers = servers == null ? List.of() : servers.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    public boolean isAnywhere() {
        return equals(ANYWHERE);
    }

    /** Ce lieu, ce moment, ce serveur conviennent-ils ? */
    public boolean test(Location location, String server, long playerTime) {
        if (isAnywhere()) {
            return true;
        }
        if (!servers.isEmpty() && servers.stream().noneMatch(prefix -> server.toLowerCase(Locale.ROOT).startsWith(prefix))) {
            return false;
        }
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        if (!dimension.isEmpty() && !dimension.equals(dimension(world))) {
            return false;
        }
        int y = location.getBlockY();
        if (minY != null && y < minY || maxY != null && y > maxY) {
            return false;
        }
        if (!time.isEmpty()) {
            long ticks = Math.floorMod(playerTime, 24000L);
            boolean night = ticks >= 13000 && ticks < 23000;
            if (time.equals("night") != night) {
                return false;
            }
        }
        if (!weather.isEmpty()) {
            String now = world.isThundering() ? "thunder" : world.hasStorm() ? "rain" : "clear";
            // « rain » accepte aussi l'orage (il pleut)
            boolean ok = weather.equals(now) || weather.equals("rain") && now.equals("thunder");
            if (!ok) {
                return false;
            }
        }
        if (!biomes.isEmpty()) {
            Biome biome = location.getBlock().getBiome();
            NamespacedKey key = Registry.BIOME.getKey(biome);
            return key != null && biomes.contains(key.getKey());
        }
        return true;
    }

    private static String dimension(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> "nether";
            case THE_END -> "the_end";
            default -> "normal";
        };
    }
}
