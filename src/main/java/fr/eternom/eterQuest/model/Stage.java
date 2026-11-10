package fr.eternom.eterQuest.model;

import java.util.List;
import java.util.Locale;

/**
 * Une étape de quête.
 * - text : ce qu'il faut faire, en une phrase (MiniMessage) ; vide : les objectifs suffisent.
 * - objectives : à remplir (tous, sauf les facultatifs).
 * - turnIn : où l'étape se rend une fois remplie. « auto » : tout de suite ; « giver » : chez le donneur du plugin
 *   propriétaire (menu d'EterJob...) ; « remote » : à distance (/quests, /job) ; « npc:eternpc:fermier » : en cliquant
 *   sur ce PNJ. Une étape avec des objets à remettre ne peut pas être « auto ».
 * - onStart / onComplete : actions d'EterLib (message, titre, son, réputation...) au début et à la fin de l'étape.
 * - say : ce que dit le PNJ de l'étape quand on lui parle (TALK, ou au moment de rendre).
 * - branches : la suite selon des conditions (la première vraie) ; sinon next (id d'étape), sinon l'étape suivante.
 * - end : la quête se termine après cette étape (même si d'autres suivent : une autre fin).
 * - timeLimit : secondes pour la remplir une fois commencée (0 : pas de limite) ; dépassé, elle recommence.
 */
public record Stage(String id, String text, List<Objective> objectives, List<String> turnIn, List<String> onStart,
                    List<String> onComplete, List<String> say, List<Branch> branches, String next, boolean end, int timeLimit) {

    /** Une suite possible : si toutes les conditions sont vraies, on va à l'étape to. */
    public record Branch(List<String> when, String to) {
        public Branch {
            when = List.copyOf(when);
        }
    }

    public Stage {
        text = text == null ? "" : text;
        objectives = List.copyOf(objectives);
        turnIn = turnIn == null || turnIn.isEmpty() ? List.of("auto")
                : turnIn.stream().map(place -> normalize(place)).toList();
        onStart = onStart == null ? List.of() : List.copyOf(onStart);
        onComplete = onComplete == null ? List.of() : List.copyOf(onComplete);
        say = say == null ? List.of() : List.copyOf(say);
        branches = branches == null ? List.of() : List.copyOf(branches);
        next = next == null ? "" : next;
    }

    /** « npc:fermier » -> « npc:eternpc:fermier » ; le reste en minuscules. */
    public static String normalize(String place) {
        String value = place.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("npc:")) {
            String npc = value.substring(4);
            return "npc:" + (npc.contains(":") ? npc : "eternpc:" + npc);
        }
        return value;
    }

    public boolean auto() {
        return turnIn.contains("auto");
    }

    public boolean accepts(String place) {
        return turnIn.contains(normalize(place));
    }

    /** Les PNJ chez qui l'étape se rend (« eternpc:fermier »). */
    public List<String> turnInNpcs() {
        return turnIn.stream().filter(place -> place.startsWith("npc:")).map(place -> place.substring(4)).toList();
    }

    public boolean hasDeliveries() {
        return objectives.stream().anyMatch(objective -> objective.kind() == Objective.Kind.DELIVER);
    }
}
