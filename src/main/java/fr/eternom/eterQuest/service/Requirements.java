package fr.eternom.eterQuest.service;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.module.reputation.Reputation;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Les conditions d'une quête (requires) et des branches (when), une par ligne :
 * <pre>
 *   reputation <pnj|global> <minimum>   réputation auprès du PNJ (× son ratio, EterNpc) ou globale
 *   done <quête>                        la quête a déjà été faite (au moins une fois)
 *   !done <quête>                       jamais faite
 *   permission <permission>             le joueur a cette permission
 *   ?<condition d'EterLib>              n'importe quelle condition (?eterjob:rank fermier artisan, ?eterhub:discovered lac...)
 * </pre>
 * Les conditions ?EterLib ne se testent que sur le thread principal (test) ; testBlocking les laisse passer.
 */
public final class Requirements {

    private Requirements() {
    }

    /** Bloquant : réputation et quêtes faites lues en base. */
    public static boolean testBlocking(UUID player, List<String> lines, Function<UUID, Set<String>> completed) {
        Set<String> done = null;
        for (String line : lines) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("?")) {
                continue;
            }
            String[] words = text.split("\\s+");
            String head = words[0].toLowerCase(Locale.ROOT);
            if (head.equals("done") || head.equals("!done")) {
                if (done == null) {
                    done = completed.apply(player);
                }
                if (words.length < 2 || done.contains(words[1]) != head.equals("done")) {
                    return false;
                }
            } else if (!native_(head, words, player, false)) {
                return false;
            }
        }
        return true;
    }

    /** Thread principal : tout, avec ce que ce serveur sait déjà (réputation en mémoire, quêtes faites lues à l'arrivée). */
    public static boolean test(Player player, List<String> lines, Set<String> completed) {
        for (String line : lines) {
            String text = line.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.startsWith("?")) {
                if (!EterLib.get().getActions().test(player, List.of(text))) {
                    return false;
                }
                continue;
            }
            String[] words = text.split("\\s+");
            String head = words[0].toLowerCase(Locale.ROOT);
            if (head.equals("done") || head.equals("!done")) {
                if (words.length < 2 || completed.contains(words[1]) != head.equals("done")) {
                    return false;
                }
            } else if (!native_(head, words, player.getUniqueId(), true)) {
                return false;
            }
        }
        return true;
    }

    private static boolean native_(String head, String[] words, UUID player, boolean cached) {
        switch (head) {
            case "reputation" -> {
                if (words.length < 3) {
                    return false;
                }
                double minimum;
                try {
                    minimum = Double.parseDouble(words[2].replace(',', '.'));
                } catch (NumberFormatException e) {
                    return false;
                }
                Reputation reputation = EterLib.get().getReputation();
                String npc = words[1].equalsIgnoreCase("global") ? "" : words[1];
                double value = cached ? reputation.cachedTowards(player, npc) : reputation.towards(player, npc);
                return value >= minimum;
            }
            case "permission" -> {
                Player online = Bukkit.getPlayer(player);
                return words.length >= 2 && online != null && online.hasPermission(words[1]);
            }
            default -> {
                return false; // inconnue : fausse (signalée au chargement)
            }
        }
    }

    /** Une ligne reconnue ? (vérification au chargement des quêtes) */
    public static boolean known(String line) {
        String text = line.trim();
        if (text.startsWith("?")) {
            return true;
        }
        String head = text.split("\\s+")[0].toLowerCase(Locale.ROOT);
        return Set.of("reputation", "done", "!done", "permission").contains(head);
    }
}
