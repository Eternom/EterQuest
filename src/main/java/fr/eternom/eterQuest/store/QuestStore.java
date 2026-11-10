package fr.eternom.eterQuest.store;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;
import fr.eternom.eterQuest.api.QuestInstance;
import fr.eternom.eterQuest.api.QuestInstance.State;
import fr.eternom.eterQuest.model.Quest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Les données d'EterQuest, communes au réseau. Appels bloquants : hors du thread principal.
 * - eterquest_definitions : les quêtes (contenu complet, version, empreinte) ;
 * - eterquest_instances : les quêtes données aux joueurs (une par emplacement de chaque propriétaire) ;
 * - eterquest_counters : la progression de chaque objectif de l'étape en cours, écrite en S'AJOUTANT (deux serveurs ne
 *   s'écrasent jamais), attachée au token de la quête et à l'étape ;
 * - eterquest_completions : les quêtes finies (déjà faite ? combien de fois ?) ;
 * - eterquest_box : la boîte de récupération (objets qui n'ont pas pu être rendus) ;
 * - eterquest_players : préférences (messages coupés, résumé dans la sidebar).
 * Chaque changement important est UNE requête qui vérifie ses conditions (token, étape, état) : jamais deux fois.
 */
public class QuestStore {

    private static final String DEFINITIONS = "definitions";
    private static final String INSTANCES = "instances";
    private static final String COUNTERS = "counters";
    private static final String COMPLETIONS = "completions";
    private static final String BOX = "box";
    private static final String PLAYERS = "players";
    private static final Gson GSON = new Gson();

    /** Une quête telle qu'en base. */
    public record Definition(String id, String owner, int version, String hash, String body) {
    }

    /** Ce que donne l'envoi d'une quête en base. */
    public enum Sync { INSERTED, UPDATED, SAME, OLDER, CONFLICT }

    public record Prefs(boolean quiet, boolean summary) {
    }

    private final Database database;

    public QuestStore(Database database) {
        this.database = database;
        database.createTable(DEFINITIONS,
                Column.of("id", Column.Type.STRING).length(128).primaryKey(),
                Column.of("owner", Column.Type.STRING).length(32).notNull(),
                Column.of("version", Column.Type.INT).notNull(),
                Column.of("hash", Column.Type.STRING).length(64).notNull(),
                Column.of("body", Column.Type.TEXT).notNull(),
                Column.of("updated_at", Column.Type.LONG).notNull());
        database.createTable(INSTANCES,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("owner", Column.Type.STRING).length(32).primaryKey(),
                Column.of("slot", Column.Type.STRING).length(64).primaryKey(),
                Column.of("quest", Column.Type.STRING).length(128).notNull(),
                Column.of("token", Column.Type.LONG).notNull(),
                Column.of("stage", Column.Type.INT).notNull(),
                Column.of("state", Column.Type.STRING).length(16).notNull(),
                Column.of("scale", Column.Type.DOUBLE).notNull(),
                Column.of("meta", Column.Type.TEXT),
                Column.of("started_at", Column.Type.LONG).notNull(),
                Column.of("stage_at", Column.Type.LONG).notNull(),
                Column.of("expires_at", Column.Type.LONG).notNull(),
                Column.of("completed_at", Column.Type.LONG).notNull(),
                Column.of("rewarded", Column.Type.BOOLEAN).notNull(),
                Column.of("tracked", Column.Type.BOOLEAN).notNull());
        database.createTable(COUNTERS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("owner", Column.Type.STRING).length(32).primaryKey(),
                Column.of("slot", Column.Type.STRING).length(64).primaryKey(),
                Column.of("token", Column.Type.LONG).primaryKey(),
                Column.of("stage", Column.Type.INT).primaryKey(),
                Column.of("idx", Column.Type.INT).primaryKey(),
                Column.of("value", Column.Type.INT).notNull());
        database.createTable(COMPLETIONS,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("uuid", Column.Type.UUID).notNull(),
                Column.of("quest", Column.Type.STRING).length(128).notNull(),
                Column.of("owner", Column.Type.STRING).length(32).notNull(),
                Column.of("completed_at", Column.Type.LONG).notNull());
        database.createTable(BOX,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("uuid", Column.Type.UUID).notNull(),
                Column.of("items", Column.Type.TEXT).notNull(),
                Column.of("created", Column.Type.LONG).notNull());
        database.createTable(PLAYERS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("quiet", Column.Type.BOOLEAN).notNull(),
                Column.of("summary", Column.Type.BOOLEAN).notNull());
        database.execute("CREATE INDEX IF NOT EXISTS eterquest_completions_uuid ON " + database.table(COMPLETIONS) + " (uuid, quest)");
        database.execute("CREATE INDEX IF NOT EXISTS eterquest_box_uuid ON " + database.table(BOX) + " (uuid)");
    }

    // ---------- Définitions ----------

    /** Envoie une quête : ajoutée si nouvelle, remplacée si sa version est plus haute ; jamais écrasée sinon. */
    public Sync sync(Quest quest) {
        Optional<Row> row = database.getFirst(DEFINITIONS, Map.of("id", quest.id()));
        long now = System.currentTimeMillis();
        if (row.isEmpty()) {
            database.execute("INSERT IGNORE INTO " + database.table(DEFINITIONS) + " (id, owner, version, hash, body, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?)", quest.id(), quest.owner(), quest.version(), quest.hash(), quest.body(), now);
            return Sync.INSERTED;
        }
        int version = row.get().getInt("version");
        if (quest.version() > version) {
            // Seulement si personne n'est passé à une version encore plus haute entre-temps
            int changed = database.execute("UPDATE " + database.table(DEFINITIONS) + " SET owner = ?, version = ?, hash = ?, body = ?,"
                    + " updated_at = ? WHERE id = ? AND version < ?", quest.owner(), quest.version(), quest.hash(), quest.body(), now,
                    quest.id(), quest.version());
            return changed > 0 ? Sync.UPDATED : Sync.OLDER;
        }
        if (quest.version() < version) {
            return Sync.OLDER;
        }
        return quest.hash().equals(row.get().getString("hash")) ? Sync.SAME : Sync.CONFLICT;
    }

    public List<Definition> definitions() {
        return database.query("SELECT id, owner, version, hash, body FROM " + database.table(DEFINITIONS)).stream()
                .map(row -> new Definition(row.getString("id"), row.getString("owner"), row.getInt("version"),
                        row.getString("hash"), row.getString("body"))).toList();
    }

    // ---------- Quêtes des joueurs ----------

    public List<QuestInstance> instances(UUID player) {
        return withProgress(player, database.get(INSTANCES, Map.of("uuid", player)));
    }

    public List<QuestInstance> instances(UUID player, String owner) {
        return withProgress(player, database.get(INSTANCES, Map.of("uuid", player, "owner", owner)));
    }

    public Optional<QuestInstance> instance(UUID player, String owner, String slot) {
        return withProgress(player, database.get(INSTANCES, Map.of("uuid", player, "owner", owner, "slot", slot))).stream().findFirst();
    }

    private List<QuestInstance> withProgress(UUID player, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        // Compteurs de l'étape en cours de chaque quête, en une requête
        Map<String, Map<Integer, Integer>> counters = new HashMap<>();
        for (Row row : database.query("SELECT c.owner, c.slot, c.idx, c.value FROM " + database.table(COUNTERS) + " c JOIN "
                + database.table(INSTANCES) + " i ON i.uuid = c.uuid AND i.owner = c.owner AND i.slot = c.slot AND i.token = c.token"
                + " AND i.stage = c.stage WHERE c.uuid = ?", player)) {
            counters.computeIfAbsent(row.getString("owner") + "/" + row.getString("slot"), key -> new HashMap<>())
                    .put(row.getInt("idx"), row.getInt("value"));
        }
        List<QuestInstance> instances = new ArrayList<>();
        for (Row row : rows) {
            Map<Integer, Integer> values = counters.getOrDefault(row.getString("owner") + "/" + row.getString("slot"), Map.of());
            int size = values.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
            List<Integer> progress = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                progress.add(values.getOrDefault(i, 0));
            }
            instances.add(toInstance(row, progress));
        }
        return instances;
    }

    /** Donne une quête dans cet emplacement (remplace celle qui y était). */
    public QuestInstance assign(UUID player, String owner, String slot, String quest, double scale, long expiresAt, Map<String, String> meta) {
        long now = System.currentTimeMillis();
        long token = newToken();
        database.delete(COUNTERS, Map.of("uuid", player, "owner", owner, "slot", slot));
        Map<String, Object> values = new HashMap<>();
        values.put("uuid", player);
        values.put("owner", owner);
        values.put("slot", slot);
        values.put("quest", quest);
        values.put("token", token);
        values.put("stage", 0);
        values.put("state", State.ACTIVE.name());
        values.put("scale", scale);
        values.put("meta", GSON.toJson(meta));
        values.put("started_at", now);
        values.put("stage_at", now);
        values.put("expires_at", expiresAt);
        values.put("completed_at", 0L);
        values.put("rewarded", false);
        values.put("tracked", false);
        database.set(INSTANCES, values, "uuid", "owner", "slot");
        return new QuestInstance(owner, slot, quest, token, 0, State.ACTIVE, scale, meta, now, now, expiresAt, 0, false, false, List.of());
    }

    /** Remplace une quête encore en cours (token donné) par une autre : false si elle a changé entre-temps. */
    public Optional<QuestInstance> replaceIfActive(UUID player, String owner, String slot, long oldToken, String quest, double scale,
                                                   long expiresAt, Map<String, String> meta) {
        long now = System.currentTimeMillis();
        long token = newToken();
        int changed = database.execute("UPDATE " + database.table(INSTANCES) + " SET quest = ?, token = ?, stage = 0, scale = ?, meta = ?,"
                        + " started_at = ?, stage_at = ?, expires_at = ?, completed_at = 0, rewarded = FALSE"
                        + " WHERE uuid = ? AND owner = ? AND slot = ? AND token = ? AND state = ?",
                quest, token, scale, GSON.toJson(meta), now, now, expiresAt, player, owner, slot, oldToken, State.ACTIVE.name());
        if (changed == 0) {
            return Optional.empty();
        }
        database.execute("DELETE FROM " + database.table(COUNTERS) + " WHERE uuid = ? AND owner = ? AND slot = ? AND token = ?",
                player, owner, slot, oldToken);
        return Optional.of(new QuestInstance(owner, slot, quest, token, 0, State.ACTIVE, scale, meta, now, now, expiresAt, 0, false,
                false, List.of()));
    }

    public void remove(UUID player, String owner, String slot) {
        database.delete(INSTANCES, Map.of("uuid", player, "owner", owner, "slot", slot));
        database.delete(COUNTERS, Map.of("uuid", player, "owner", owner, "slot", slot));
    }

    public void clear(UUID player, String owner) {
        database.delete(INSTANCES, Map.of("uuid", player, "owner", owner));
        database.delete(COUNTERS, Map.of("uuid", player, "owner", owner));
    }

    /** Ajoute à un compteur, sans dépasser target. */
    public void addCounter(UUID player, String owner, String slot, long token, int stage, int idx, int delta, int target) {
        database.execute("INSERT INTO " + database.table(COUNTERS) + " (uuid, owner, slot, token, stage, idx, value) VALUES (?, ?, ?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE value = LEAST(?, value + ?)",
                player, owner, slot, token, stage, idx, Math.min(target, delta), target, delta);
    }

    /** Les compteurs d'une étape (index d'objectif -> valeur). */
    public Map<Integer, Integer> counters(UUID player, String owner, String slot, long token, int stage) {
        Map<Integer, Integer> values = new HashMap<>();
        database.get(COUNTERS, Map.of("uuid", player, "owner", owner, "slot", slot, "token", token, "stage", stage))
                .forEach(row -> values.put(row.getInt("idx"), row.getInt("value")));
        return values;
    }

    /** Passe à l'étape to, seulement depuis l'étape from de cette quête-là, encore en cours. */
    public boolean advance(UUID player, String owner, String slot, long token, int from, int to) {
        int changed = database.execute("UPDATE " + database.table(INSTANCES) + " SET stage = ?, stage_at = ?"
                        + " WHERE uuid = ? AND owner = ? AND slot = ? AND token = ? AND stage = ? AND state = ?",
                to, System.currentTimeMillis(), player, owner, slot, token, from, State.ACTIVE.name());
        if (changed > 0) {
            database.execute("DELETE FROM " + database.table(COUNTERS) + " WHERE uuid = ? AND owner = ? AND slot = ? AND token = ? AND stage = ?",
                    player, owner, slot, token, from);
        }
        return changed > 0;
    }

    /** Temps dépassé : l'étape recommence (compteurs à zéro). */
    public boolean restartStage(UUID player, String owner, String slot, long token, int stage) {
        int changed = database.execute("UPDATE " + database.table(INSTANCES) + " SET stage_at = ?"
                        + " WHERE uuid = ? AND owner = ? AND slot = ? AND token = ? AND stage = ? AND state = ?",
                System.currentTimeMillis(), player, owner, slot, token, stage, State.ACTIVE.name());
        if (changed > 0) {
            database.execute("DELETE FROM " + database.table(COUNTERS) + " WHERE uuid = ? AND owner = ? AND slot = ? AND token = ? AND stage = ?",
                    player, owner, slot, token, stage);
        }
        return changed > 0;
    }

    /** Termine la quête (une seule fois : cette quête-là, à cette étape, encore en cours) et la note comme faite. */
    public boolean complete(UUID player, String owner, String slot, long token, int stage, String quest) {
        long now = System.currentTimeMillis();
        int changed = database.execute("UPDATE " + database.table(INSTANCES) + " SET state = ?, completed_at = ?, tracked = FALSE"
                        + " WHERE uuid = ? AND owner = ? AND slot = ? AND token = ? AND stage = ? AND state = ?",
                State.DONE.name(), now, player, owner, slot, token, stage, State.ACTIVE.name());
        if (changed == 0) {
            return false;
        }
        database.insert(COMPLETIONS, Map.of("uuid", player, "quest", quest, "owner", owner, "completed_at", now));
        database.execute("DELETE FROM " + database.table(COUNTERS) + " WHERE uuid = ? AND owner = ? AND slot = ? AND token = ?",
                player, owner, slot, token);
        return true;
    }

    /** Abandon (ou échec) d'une quête en cours. */
    public boolean end(UUID player, String owner, String slot, long token, State state) {
        return database.execute("UPDATE " + database.table(INSTANCES) + " SET state = ?, completed_at = ?, tracked = FALSE"
                        + " WHERE uuid = ? AND owner = ? AND slot = ? AND token = ? AND state = ?",
                state.name(), System.currentTimeMillis(), player, owner, slot, token, State.ACTIVE.name()) > 0;
    }

    /** Le propriétaire réserve le paiement de ses récompenses : true une seule fois. */
    public boolean claim(UUID player, String owner, String slot, long token) {
        return database.execute("UPDATE " + database.table(INSTANCES) + " SET rewarded = TRUE"
                        + " WHERE uuid = ? AND owner = ? AND slot = ? AND token = ? AND state = ? AND rewarded = FALSE",
                player, owner, slot, token, State.DONE.name()) > 0;
    }

    /** Quêtes finies dont le propriétaire n'a pas encore payé les récompenses (finies sur un serveur sans lui). */
    public List<QuestInstance> unclaimed(UUID player, String owner) {
        return database.query("SELECT * FROM " + database.table(INSTANCES) + " WHERE uuid = ? AND owner = ? AND state = ? AND rewarded = FALSE",
                player, owner, State.DONE.name()).stream().map(row -> toInstance(row, List.of())).toList();
    }

    public int completions(UUID player, String quest) {
        return database.query("SELECT COUNT(*) AS total FROM " + database.table(COMPLETIONS) + " WHERE uuid = ? AND quest = ?", player, quest)
                .getFirst().getInt("total");
    }

    public Set<String> completed(UUID player) {
        Set<String> quests = new HashSet<>();
        database.query("SELECT DISTINCT quest FROM " + database.table(COMPLETIONS) + " WHERE uuid = ?", player)
                .forEach(row -> quests.add(row.getString("quest")));
        return quests;
    }

    /** Finies par jour et par propriétaire depuis since (statistiques). */
    public Map<String, Map<String, Integer>> stats(long since) {
        Map<String, Map<String, Integer>> stats = new LinkedHashMap<>();
        database.query("SELECT FROM_UNIXTIME(completed_at / 1000, '%Y-%m-%d') AS day, owner, COUNT(*) AS total FROM "
                        + database.table(COMPLETIONS) + " WHERE completed_at >= ? GROUP BY day, owner ORDER BY day DESC", since)
                .forEach(row -> stats.computeIfAbsent(row.getString("day"), day -> new LinkedHashMap<>())
                        .put(row.getString("owner"), row.getInt("total")));
        return stats;
    }

    /** La quête suivie dans la sidebar (une seule, tous propriétaires) ; owner null = aucune. */
    public void track(UUID player, String owner, String slot) {
        database.execute("UPDATE " + database.table(INSTANCES) + " SET tracked = (owner = ? AND slot = ?) WHERE uuid = ?",
                owner == null ? "" : owner, slot == null ? "" : slot, player);
    }

    // ---------- Préférences ----------

    public Prefs prefs(UUID player) {
        return database.getFirst(PLAYERS, Map.of("uuid", player)).map(row -> new Prefs(row.getBoolean("quiet"), row.getBoolean("summary")))
                .orElse(new Prefs(false, false));
    }

    public void setPrefs(UUID player, Prefs prefs) {
        database.set(PLAYERS, Map.of("uuid", player, "quiet", prefs.quiet(), "summary", prefs.summary()), "uuid");
    }

    // ---------- Boîte de récupération ----------

    public void deposit(UUID player, String items) {
        database.insert(BOX, Map.of("uuid", player, "items", items, "created", System.currentTimeMillis()));
    }

    /** Les colis du joueur : id -> objets (« MATIÈRE quantité;... »). */
    public Map<Long, String> box(UUID player) {
        Map<Long, String> box = new LinkedHashMap<>();
        database.query("SELECT id, items FROM " + database.table(BOX) + " WHERE uuid = ? ORDER BY id", player)
                .forEach(row -> box.put(row.getLong("id"), row.getString("items")));
        return box;
    }

    /** Retire un colis ; true pour le seul appel qui le retire (il le donne). */
    public boolean take(UUID player, long id) {
        return database.delete(BOX, Map.of("uuid", player, "id", id)) > 0;
    }

    public int boxCount(UUID player) {
        return database.query("SELECT COUNT(*) AS total FROM " + database.table(BOX) + " WHERE uuid = ?", player).getFirst().getInt("total");
    }

    // ---------- Lecture ----------

    private static QuestInstance toInstance(Row row, List<Integer> progress) {
        Map<String, String> meta = Map.of();
        String json = row.getString("meta");
        if (json != null && !json.isBlank()) {
            try {
                Map<String, String> parsed = GSON.fromJson(json, new TypeToken<Map<String, String>>() { }.getType());
                meta = parsed == null ? Map.of() : parsed;
            } catch (RuntimeException ignored) {
                // données abîmées : sans meta
            }
        }
        return new QuestInstance(row.getString("owner"), row.getString("slot"), row.getString("quest"), row.getLong("token"),
                row.getInt("stage"), State.of(row.getString("state")), row.getDouble("scale"), meta, row.getLong("started_at"),
                row.getLong("stage_at"), row.getLong("expires_at"), row.getLong("completed_at"), row.getBoolean("rewarded"),
                row.getBoolean("tracked"), progress);
    }

    private static long newToken() {
        return System.currentTimeMillis() * 1000 + ThreadLocalRandom.current().nextInt(1000);
    }
}
