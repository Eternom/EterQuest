package fr.eternom.eterQuest.model;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Lit les quêtes d'un fichier YAML :
 * <pre>
 * version: 3                      # la plus haute version l'emporte sur le réseau (l'augmenter après un changement)
 * defaults:                       # repris par chaque quête du fichier (tags et meta : ajoutés ; le reste : si absent)
 *   category: job
 *   tags: [fermier]
 *   turn-in: [giver, remote]      # où se rend la DERNIÈRE étape (et celles avec des objets à remettre)
 *   meta: {job: fermier}
 * quests:
 *   fermier-pain:                 # forme courte : une seule étape
 *     meta: {kind: daily, tier: easy, reward: 120}
 *     objectives: ["HARVEST:WHEAT:64", "DELIVER:BREAD:16"]
 *   fermier-commande-du-village:  # forme longue : des étapes
 *     name: "<gold>La commande du village"
 *     requires: ["reputation eternpc:fermier 10"]
 *     stages:
 *       - id: rencontre
 *         text: "Va voir Mathurin, le fermier du village"
 *         objectives: [{type: talk, npc: fermier}]
 *         say: ["Ah, la guilde t'envoie !"]
 *       - id: recolte
 *         objectives:
 *           - {type: signal, key: eterhub:harvest, match: {crop: wheat}, amount: 48, text: "Blé des Champs"}
 *         turn-in: [npc:fermier]
 * </pre>
 * Objectif : forme courte « TYPE:CIBLE:QUANTITÉ », ou {type, target (item, npc), amount, scale, key, match, where, text,
 * optional}. where : {biomes, dimension, min-y, max-y, time, weather, servers}.
 */
public final class QuestParser {

    private QuestParser() {
    }

    /** Les quêtes d'un fichier, prêtes (valeurs par défaut comprises). Les erreurs vont à warn (« où : quoi »). */
    public static List<Quest> parseFile(YamlConfiguration file, String owner, String where, Consumer<String> warn) {
        int version = Math.max(1, file.getInt("version", 1));
        ConfigurationSection defaults = file.getConfigurationSection("defaults");
        ConfigurationSection quests = file.getConfigurationSection("quests");
        List<Quest> parsed = new ArrayList<>();
        if (quests == null) {
            return parsed;
        }
        for (String id : quests.getKeys(false)) {
            ConfigurationSection section = quests.getConfigurationSection(id);
            String place = where + " > " + id;
            if (section == null || !id.matches("[a-z0-9_-]+")) {
                warn.accept(place + " : identifiant invalide (minuscules, chiffres, - et _ ; pas de point), quête ignorée");
                continue;
            }
            YamlConfiguration resolved = resolve(section, defaults);
            parse(id, owner, version, resolved, place, warn).ifPresent(parsed::add);
        }
        return parsed;
    }

    /** Une quête gardée en base (son contenu complet). */
    public static Optional<Quest> parseBody(String id, String owner, int version, String body, Consumer<String> warn) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(body);
        } catch (InvalidConfigurationException e) {
            warn.accept("quête " + id + " (base) : contenu illisible");
            return Optional.empty();
        }
        return parse(id, owner, version, yaml, "quête " + id, warn);
    }

    /** La quête avec les valeurs par défaut du fichier (tags, meta, requires : ajoutés ; le reste : si absent). */
    static YamlConfiguration resolve(ConfigurationSection quest, ConfigurationSection defaults) {
        YamlConfiguration resolved = new YamlConfiguration();
        if (defaults != null) {
            for (String key : defaults.getKeys(false)) {
                resolved.set(key, copy(defaults.get(key)));
            }
        }
        for (String key : quest.getKeys(false)) {
            Object value = copy(quest.get(key));
            if (defaults != null && (key.equals("tags") || key.equals("requires")) && defaults.isList(key)) {
                LinkedHashSet<String> merged = new LinkedHashSet<>(defaults.getStringList(key));
                merged.addAll(quest.getStringList(key));
                resolved.set(key, new ArrayList<>(merged));
            } else if (defaults != null && key.equals("meta") && defaults.isConfigurationSection(key) && quest.isConfigurationSection(key)) {
                Map<String, Object> merged = new LinkedHashMap<>(defaults.getConfigurationSection(key).getValues(false));
                merged.putAll(quest.getConfigurationSection(key).getValues(false));
                resolved.set(key, null);
                merged.forEach((k, v) -> resolved.set("meta." + k, v));
            } else {
                resolved.set(key, value);
            }
        }
        return resolved;
    }

    private static Object copy(Object value) {
        if (value instanceof ConfigurationSection section) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                map.put(key, copy(section.get(key)));
            }
            return map;
        }
        return value;
    }

    private static Optional<Quest> parse(String id, String owner, int version, YamlConfiguration yaml, String place, Consumer<String> warn) {
        String body = yaml.saveToString();
        List<String> defaultTurnIn = yaml.getStringList("turn-in");
        List<Stage> stages = new ArrayList<>();
        if (yaml.isList("stages")) {
            List<Map<?, ?>> raw = yaml.getMapList("stages");
            for (int i = 0; i < raw.size(); i++) {
                ConfigurationSection stage = new YamlConfiguration().createSection("s", raw.get(i));
                boolean last = i == raw.size() - 1;
                stages.add(stage(stage, "etape-" + (i + 1), last, false, defaultTurnIn, place + " > étape " + (i + 1), warn));
            }
        } else {
            stages.add(stage(yaml, "main", true, true, defaultTurnIn, place, warn));
        }
        if (stages.isEmpty()) {
            warn.accept(place + " : aucune étape, quête ignorée");
            return Optional.empty();
        }
        for (Stage stage : stages) {
            if (!stage.next().isEmpty() && stages.stream().noneMatch(s -> s.id().equals(stage.next()))) {
                warn.accept(place + " : étape " + stage.id() + " : next inconnue " + stage.next());
            }
            for (Stage.Branch branch : stage.branches()) {
                if (stages.stream().noneMatch(s -> s.id().equals(branch.to()))) {
                    warn.accept(place + " : étape " + stage.id() + " : branche vers une étape inconnue " + branch.to());
                }
            }
        }
        Map<String, String> meta = new LinkedHashMap<>();
        ConfigurationSection metaSection = yaml.getConfigurationSection("meta");
        if (metaSection != null) {
            for (String key : metaSection.getKeys(false)) {
                meta.put(key, String.valueOf(metaSection.get(key)));
            }
        }
        Material icon = yaml.isString("icon") ? Material.matchMaterial(yaml.getString("icon", "")) : null;
        List<String> description = yaml.isList("description") ? yaml.getStringList("description")
                : yaml.isString("description") ? List.of(yaml.getString("description", "")) : List.of();
        return Optional.of(new Quest(id, owner.toLowerCase(Locale.ROOT), yaml.getString("category", "quest"),
                yaml.getStringList("tags").stream().map(tag -> tag.toLowerCase(Locale.ROOT)).toList(), yaml.getString("name", ""),
                description, icon != null && icon.isItem() ? icon : null, yaml.getStringList("requires"), stages,
                yaml.getStringList("rewards"), yaml.getDouble("reputation", 0), meta, yaml.getBoolean("abandon", true), version,
                hash(body), body));
    }

    private static Stage stage(ConfigurationSection section, String fallbackId, boolean last, boolean shortForm, List<String> defaultTurnIn,
                               String place, Consumer<String> warn) {
        List<Objective> objectives = new ArrayList<>();
        for (Object raw : section.getList("objectives", List.of())) {
            Optional<Objective> objective = raw instanceof Map<?, ?> map
                    ? objective(new YamlConfiguration().createSection("o", map), place, warn)
                    : Objective.parse(String.valueOf(raw));
            if (objective.isEmpty()) {
                if (!(raw instanceof Map<?, ?>)) {
                    warn.accept(place + " : objectif illisible ignoré : " + raw);
                }
                continue;
            }
            if (!Targets.valid(objective.get())) {
                warn.accept(place + " : objectif invalide ignoré : " + objective.get().format());
                continue;
            }
            objectives.add(objective.get());
        }
        objectives = Objective.merge(objectives);
        // Forme courte : le turn-in de la quête est celui de son unique étape
        List<String> turnIn = shortForm ? List.of() : section.getStringList("turn-in");
        if (turnIn.isEmpty() && (last || objectives.stream().anyMatch(o -> o.kind() == Objective.Kind.DELIVER))) {
            turnIn = defaultTurnIn;
        }
        if (objectives.stream().anyMatch(o -> o.kind() == Objective.Kind.DELIVER) && (turnIn.isEmpty() || turnIn.contains("auto"))) {
            warn.accept(place + " : des objets à remettre demandent un lieu (turn-in), « giver » pris par défaut");
            turnIn = List.of("giver");
        }
        List<Stage.Branch> branches = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("branches")) {
            ConfigurationSection branch = new YamlConfiguration().createSection("b", raw);
            List<String> when = branch.isList("when") ? branch.getStringList("when")
                    : branch.isString("when") ? List.of(branch.getString("when", "")) : List.of();
            String to = branch.getString("to", "");
            if (to.isEmpty()) {
                warn.accept(place + " : branche sans « to » ignorée");
                continue;
            }
            branches.add(new Stage.Branch(when, to));
        }
        return new Stage(section.getString("id", fallbackId), section.getString("text", ""), objectives, turnIn,
                lines(section, "on-start"), lines(section, "on-complete"), lines(section, "say"), branches,
                section.getString("next", ""), section.getBoolean("end", false), Math.max(0, section.getInt("time-limit", 0)));
    }

    private static List<String> lines(ConfigurationSection section, String key) {
        if (section.isList(key)) {
            return section.getStringList(key);
        }
        return section.isString(key) ? List.of(section.getString(key, "")) : List.of();
    }

    private static Optional<Objective> objective(ConfigurationSection section, String place, Consumer<String> warn) {
        Optional<Objective.Kind> kind = Objective.Kind.of(section.getString("type", ""));
        if (kind.isEmpty()) {
            warn.accept(place + " : type d'objectif inconnu ignoré : " + section.getString("type", "(aucun)"));
            return Optional.empty();
        }
        String target = section.getString("target", section.getString("item", section.getString("npc", "")));
        if (kind.get() != Objective.Kind.SIGNAL && target.isBlank()) {
            warn.accept(place + " : objectif " + kind.get().id() + " sans cible ignoré");
            return Optional.empty();
        }
        int amount = Math.max(1, section.getInt("amount", 1));
        Map<String, List<String>> match = new LinkedHashMap<>();
        ConfigurationSection matchSection = section.getConfigurationSection("match");
        if (matchSection != null) {
            for (String key : matchSection.getKeys(false)) {
                List<String> values = matchSection.isList(key) ? matchSection.getStringList(key)
                        : List.of(String.valueOf(matchSection.get(key)));
                match.put(key.toLowerCase(Locale.ROOT), values);
            }
        }
        return Optional.of(new Objective(kind.get(), target, amount, section.getBoolean("scale", true), section.getString("key", ""),
                match, where(section.getConfigurationSection("where")), section.getString("text", ""),
                section.getBoolean("optional", false)));
    }

    private static Where where(ConfigurationSection section) {
        if (section == null) {
            return Where.ANYWHERE;
        }
        List<String> biomes = section.isList("biomes") ? section.getStringList("biomes")
                : section.isString("biomes") ? List.of(section.getString("biomes", "")) : List.of();
        List<String> servers = section.isList("servers") ? section.getStringList("servers")
                : section.isString("servers") ? List.of(section.getString("servers", "")) : List.of();
        Where where = new Where(new LinkedHashSet<>(biomes), section.getString("dimension", ""),
                section.isInt("min-y") ? section.getInt("min-y") : null, section.isInt("max-y") ? section.getInt("max-y") : null,
                section.getString("time", ""), section.getString("weather", ""), servers);
        return where.equals(Where.ANYWHERE) ? Where.ANYWHERE : where;
    }

    static String hash(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(body.hashCode());
        }
    }
}
