package fr.eternom.eterQuest.service;

import fr.eternom.eterNpc.api.NpcApi;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Les PNJ d'EterNpc (softdepend : cette classe n'est créée que s'il tourne ici) : marqueur au-dessus d'un PNJ qui attend
 * le joueur (« ? » quand il faut lui parler, « ! » quand une étape est prête à lui rendre) et bulles de dialogue. Seuls
 * les changements sont envoyés. Thread principal.
 */
public final class NpcHook {

    private final NpcApi npcs;
    /** Joueur -> PNJ -> marqueur posé (texte brut, pour comparer). */
    private final Map<UUID, Map<String, String>> shown = new HashMap<>();

    private NpcHook(NpcApi npcs) {
        this.npcs = npcs;
    }

    public static NpcHook create() {
        return NpcApi.get().map(NpcHook::new).orElse(null);
    }

    /** Les marqueurs voulus pour ce joueur (PNJ -> marqueur) : posés, changés, ou retirés s'ils ne le sont plus. */
    public void markers(Player player, Map<String, Component> wanted) {
        Map<String, String> current = shown.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>());
        Set<String> all = new HashSet<>(current.keySet());
        all.addAll(wanted.keySet());
        for (String npc : all) {
            Component marker = wanted.get(npc);
            String state = marker == null ? null : String.valueOf(marker.hashCode());
            if (Objects.equals(state, current.get(npc))) {
                continue;
            }
            npcs.marker(player, npc, marker);
            if (state == null) {
                current.remove(npc);
            } else {
                current.put(npc, state);
            }
        }
    }

    public void say(Player player, String npc, Component text) {
        npcs.say(player, npc, text, Duration.ofSeconds(5));
    }

    public void forget(UUID player) {
        shown.remove(player);
    }
}
