package fr.eternom.eterQuest.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Une quête donnée à un joueur, dans un emplacement de son propriétaire (EterJob : « daily-0 », « weekly »... ; une
 * quête lancée par un PNJ : son id). token : l'identité de cette fois-ci (une quête redonnée dans le même emplacement a
 * un autre token : les vieux progrès ne comptent pas pour elle). progress : un compteur par objectif de l'étape en cours.
 *
 * @param scale     taille de la quête (quantités × scale, ex : le rang du métier)
 * @param meta      données du propriétaire (EterJob : récompense calculée au tirage, XP, sorte, niveau)
 * @param expiresAt fin de validité (0 : jamais) : passée, elle n'avance plus et ne se rend plus
 * @param rewarded  le propriétaire a payé ses récompenses propres (une seule fois)
 */
public record QuestInstance(String owner, String slot, String quest, long token, int stage, State state, double scale,
                            Map<String, String> meta, long startedAt, long stageAt, long expiresAt, long completedAt,
                            boolean rewarded, boolean tracked, List<Integer> progress) {

    public enum State {
        ACTIVE, DONE, FAILED, ABANDONED;

        public static State of(String text) {
            try {
                return valueOf(text.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException e) {
                return ACTIVE;
            }
        }
    }

    public QuestInstance {
        meta = Map.copyOf(meta);
        progress = List.copyOf(progress);
    }

    public String key() {
        return owner + "/" + slot;
    }

    public boolean done() {
        return state == State.DONE;
    }

    public boolean expired(long now) {
        return state == State.ACTIVE && expiresAt > 0 && now >= expiresAt;
    }

    /** En cours et encore valable : elle avance. */
    public boolean live(long now) {
        return state == State.ACTIVE && !expired(now);
    }

    public int progress(int objective) {
        return objective < progress.size() ? progress.get(objective) : 0;
    }

    public String meta(String key, String fallback) {
        return meta.getOrDefault(key, fallback);
    }

    public double metaNumber(String key, double fallback) {
        try {
            return Double.parseDouble(meta.getOrDefault(key, ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public QuestInstance withProgress(int objective, int value) {
        List<Integer> next = new ArrayList<>(progress);
        while (next.size() <= objective) {
            next.add(0);
        }
        next.set(objective, value);
        return new QuestInstance(owner, slot, quest, token, stage, state, scale, meta, startedAt, stageAt, expiresAt, completedAt,
                rewarded, tracked, next);
    }

    public QuestInstance withStage(int value, long at) {
        return new QuestInstance(owner, slot, quest, token, value, state, scale, meta, startedAt, at, expiresAt, completedAt,
                rewarded, tracked, List.of());
    }

    public QuestInstance withState(State value, long at) {
        return new QuestInstance(owner, slot, quest, token, stage, value, scale, meta, startedAt, stageAt, expiresAt, at,
                rewarded, value == State.ACTIVE && tracked, progress);
    }

    public QuestInstance withTracked(boolean value) {
        return new QuestInstance(owner, slot, quest, token, stage, state, scale, meta, startedAt, stageAt, expiresAt, completedAt,
                rewarded, value, progress);
    }

    /** L'étape recommence (temps dépassé) : compteurs à zéro. */
    public QuestInstance restarted(long at) {
        return new QuestInstance(owner, slot, quest, token, stage, state, scale, meta, startedAt, at, expiresAt, completedAt,
                rewarded, tracked, List.of());
    }
}
