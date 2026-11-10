package fr.eternom.eterQuest.service;

import fr.eternom.eterLib.EterLib;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Des lignes de récompense ou d'action : « action: <action d'EterLib> » (message, titre, son, réputation...), sinon une
 * commande console ({player} = pseudo, {uuid}). Thread principal.
 */
public final class Rewards {

    private Rewards() {
    }

    public static void run(JavaPlugin plugin, Player player, List<String> lines) {
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                if (line.startsWith("action:")) {
                    EterLib.get().getActions().run(player, List.of(line.substring("action:".length()).trim()));
                } else if (line.contains(":") && !line.contains(" ") || line.matches("^[a-z0-9_-]+:[a-z0-9_.-]+( .*)?$")) {
                    // « eterlib:message ... » sans le préfixe action: : c'est bien une action
                    EterLib.get().getActions().run(player, List.of(line));
                } else {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.replace("{player}", player.getName())
                            .replace("{uuid}", player.getUniqueId().toString()));
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Récompense en erreur (" + line + ") pour " + player.getName() + " : " + e.getMessage());
            }
        }
    }
}
