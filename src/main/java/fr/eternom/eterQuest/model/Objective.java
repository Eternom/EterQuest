package fr.eternom.eterQuest.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Un objectif d'étape.
 * - Actions du jeu, comptées par EterQuest : BREAK, HARVEST, KILL, FISH, CRAFT, SMELT, BREED, EXPLORE, ENCHANT, TRADE ;
 *   cible : une matière, une créature, un biome (EXPLORE), ANY, ou un groupe (#ores, #logs, #crops, #fish, #stone,
 *   #flowers) — voir Targets.
 * - DELIVER : objets à remettre (pris au moment de rendre l'étape).
 * - TALK : parler à un PNJ (target = « eternpc:fermier »).
 * - SIGNAL : ce qu'un autre plugin signale (key = « eterhub:harvest »), avec des données à faire correspondre (match).
 * amount : quantité (× la taille de la quête si scalable) ; where : où et quand ça compte ; text : libellé à la place du
 * texte automatique ; optional : pas nécessaire pour finir l'étape (un bonus).
 */
public record Objective(Kind kind, String target, int amount, boolean scalable, String key, Map<String, List<String>> match,
                        Where where, String text, boolean optional) {

    public enum Kind {
        BREAK, HARVEST, KILL, FISH, CRAFT, SMELT, BREED, EXPLORE, ENCHANT, TRADE, DELIVER, TALK, SIGNAL;

        /** Compté pendant le jeu (tout sauf DELIVER, vérifié au moment de rendre). */
        public boolean counted() {
            return this != DELIVER;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Kind> of(String text) {
            String name = text.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if (name.equals("ITEM")) {
                return Optional.of(DELIVER); // ancien nom (EterJob)
            }
            try {
                return Optional.of(valueOf(name));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    public static final String ANY = "ANY";

    public Objective {
        target = target == null ? "" : normalizeTarget(kind, target);
        key = key == null ? "" : key.toLowerCase(Locale.ROOT);
        match = match == null || match.isEmpty() ? Map.of() : Map.copyOf(match);
        where = where == null ? Where.ANYWHERE : where;
        text = text == null ? "" : text;
    }

    private static String normalizeTarget(Kind kind, String target) {
        String value = target.trim();
        if (kind == Kind.TALK) {
            value = value.toLowerCase(Locale.ROOT);
            return value.contains(":") ? value : "eternpc:" + value;
        }
        if (value.startsWith("#")) {
            return value.toLowerCase(Locale.ROOT);
        }
        return value.toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    /** Un objectif simple (« HARVEST:WHEAT:64 »). */
    public static Objective simple(Kind kind, String target, int amount) {
        return new Objective(kind, target, amount, true, "", Map.of(), Where.ANYWHERE, "", false);
    }

    public boolean isAny() {
        return target.equals(ANY);
    }

    public boolean isGroup() {
        return target.startsWith("#");
    }

    /** La quantité pour une quête de cette taille (un PNJ, un biome : toujours la même). */
    public int amount(double scale) {
        if (!scalable || scale == 1 || kind == Kind.TALK || kind == Kind.EXPLORE) {
            return amount;
        }
        return Math.max(1, (int) Math.round(amount * scale));
    }

    /**
     * Ces données (d'un signal) correspondent-elles ? Chaque clé de match doit être dans data avec une des valeurs
     * permises (sans casse ; « >=5 », « <=5 » pour un nombre). lenient : une clé absente de data ne gêne pas (wanted).
     */
    public boolean matches(Map<String, String> data, boolean lenient) {
        for (Map.Entry<String, List<String>> entry : match.entrySet()) {
            String value = data.get(entry.getKey());
            if (value == null) {
                if (lenient) {
                    continue;
                }
                return false;
            }
            if (entry.getValue().stream().noneMatch(wanted -> accepts(wanted, value))) {
                return false;
            }
        }
        return true;
    }

    static boolean accepts(String wanted, String value) {
        String w = wanted.trim().toLowerCase(Locale.ROOT);
        if (w.equals("*")) {
            return true;
        }
        if (w.startsWith(">=") || w.startsWith("<=")) {
            try {
                double limit = Double.parseDouble(w.substring(2).trim());
                double actual = Double.parseDouble(value.trim());
                return w.startsWith(">=") ? actual >= limit : actual <= limit;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return w.equals(value.trim().toLowerCase(Locale.ROOT));
    }

    /** « HARVEST:WHEAT:64 » (forme courte) ; vide si illisible. */
    public static Optional<Objective> parse(String text) {
        String[] fields = text.trim().split(":");
        if (fields.length != 3 && !(fields.length == 4 && Kind.of(fields[0]).orElse(null) == Kind.TALK)) {
            return Optional.empty();
        }
        Optional<Kind> kind = Kind.of(fields[0]);
        if (kind.isEmpty() || kind.get() == Kind.SIGNAL) {
            return Optional.empty();
        }
        try {
            // TALK:eternpc:fermier:1 (le PNJ contient lui-même un « : »)
            String target = fields.length == 4 ? fields[1] + ":" + fields[2] : fields[1];
            int amount = Integer.parseInt(fields[fields.length - 1].trim());
            if (amount < 1 || target.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(simple(kind.get(), target, amount));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Une description stable (empreinte, journaux). */
    public String format() {
        StringBuilder builder = new StringBuilder(kind.name()).append(':').append(kind == Kind.SIGNAL ? key : target).append(':').append(amount);
        if (!match.isEmpty()) {
            builder.append(new java.util.TreeMap<>(match));
        }
        return builder.toString();
    }

    /** Les objectifs identiques (même genre, même cible, sans filtre ni texte) sont regroupés, quantités additionnées. */
    public static List<Objective> merge(List<Objective> objectives) {
        Map<String, Objective> merged = new LinkedHashMap<>();
        List<Objective> special = new ArrayList<>();
        for (Objective objective : objectives) {
            if (objective.kind == Kind.SIGNAL || !objective.match.isEmpty() || objective.where != Where.ANYWHERE
                    || !objective.text.isEmpty() || objective.optional) {
                special.add(objective);
                continue;
            }
            merged.merge(objective.kind + ":" + objective.target, objective, (a, b) -> new Objective(a.kind, a.target,
                    a.amount + b.amount, a.scalable && b.scalable, "", Map.of(), Where.ANYWHERE, "", false));
        }
        List<Objective> all = new ArrayList<>(merged.values());
        all.addAll(special);
        return all;
    }
}
