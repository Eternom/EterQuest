package fr.eternom.eterQuest.service;

import com.google.gson.JsonObject;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterQuest.model.Quest;
import fr.eternom.eterQuest.model.QuestParser;
import fr.eternom.eterQuest.model.Stage;
import fr.eternom.eterQuest.store.QuestStore;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Les quêtes du réseau. Chaque plugin propriétaire lit ses fichiers et les envoie en base (register) ; tous les
 * serveurs relisent la base (même ceux qui n'ont pas le plugin : le journal, les signaux). Règles d'envoi : une quête
 * nouvelle est ajoutée ; une version plus haute remplace ; même version mais contenu différent : rien n'est écrasé, un
 * avertissement demande d'augmenter « version » du fichier. Après un envoi, les autres serveurs relisent (bus).
 */
public class Definitions {

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final QuestStore store;
    private final Logger logger;
    private NetworkBus bus;
    private volatile Map<String, Quest> quests = Map.of();

    public Definitions(org.bukkit.plugin.java.JavaPlugin plugin, QuestStore store) {
        this.plugin = plugin;
        this.store = store;
        this.logger = plugin.getLogger();
    }

    public void start(NetworkBus bus, Runnable afterReload) {
        this.bus = bus;
        bus.on("definitions", data -> fr.eternom.eterLib.helper.task.Tasks.async(plugin, () -> {
                    reload();
                    afterReload.run();
                }, "Quêtes non relues"));
    }

    public Optional<Quest> get(String id) {
        return Optional.ofNullable(quests.get(id));
    }

    public Quest getOrNull(String id) {
        return quests.get(id);
    }

    public Collection<Quest> all() {
        return quests.values();
    }

    /** Bloquant : relit toutes les quêtes de la base. */
    public void reload() {
        Map<String, Quest> fresh = new LinkedHashMap<>();
        for (QuestStore.Definition definition : store.definitions()) {
            QuestParser.parseBody(definition.id(), definition.owner(), definition.version(), definition.body(), logger::warning)
                    .ifPresent(quest -> fresh.put(quest.id(), quest));
        }
        quests = Map.copyOf(fresh);
    }

    /** Bloquant : lit et envoie les fichiers d'un propriétaire ; renvoie les quêtes lues. */
    public List<Quest> register(Plugin owner, File folder) {
        List<Quest> parsed = new ArrayList<>();
        Path root = folder.toPath();
        if (!Files.isDirectory(root)) {
            logger.warning(owner.getName() + " : dossier de quêtes absent (" + folder + ")");
            return parsed;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(path -> path.toString().endsWith(".yml")).sorted().toList();
        } catch (IOException e) {
            logger.warning(owner.getName() + " : dossier de quêtes illisible : " + e.getMessage());
            return parsed;
        }
        String ownerId = owner.getName().toLowerCase(Locale.ROOT);
        for (Path path : files) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
            String where = owner.getName() + "/" + folder.getName() + "/" + root.relativize(path).toString().replace('\\', '/');
            List<Quest> quests = QuestParser.parseFile(yaml, ownerId, where, logger::warning);
            quests.forEach(quest -> check(quest, where));
            parsed.addAll(quests);
        }
        Map<QuestStore.Sync, Integer> outcome = new EnumMap<>(QuestStore.Sync.class);
        for (Quest quest : parsed) {
            QuestStore.Sync sync = store.sync(quest);
            outcome.merge(sync, 1, Integer::sum);
            if (sync == QuestStore.Sync.CONFLICT) {
                logger.warning("Quête " + quest.id() + " : contenu changé sans changer « version » (" + quest.version()
                        + ") : la version en base est gardée. Augmente version dans son fichier.");
            }
        }
        logger.info(owner.getName() + " : " + parsed.size() + " quêtes lues (" + outcome + ")");
        reload();
        if (bus != null && (outcome.containsKey(QuestStore.Sync.INSERTED) || outcome.containsKey(QuestStore.Sync.UPDATED))) {
            bus.publish("definitions", new JsonObject());
        }
        return parsed;
    }

    /** Vérifications de cohérence au chargement (avertissements seulement). */
    private void check(Quest quest, String where) {
        for (String line : quest.requires()) {
            if (!Requirements.known(line)) {
                logger.warning(where + " > " + quest.id() + " : condition inconnue « " + line + " »");
            }
        }
        for (Stage stage : quest.stages()) {
            for (Stage.Branch branch : stage.branches()) {
                branch.when().stream().filter(line -> !Requirements.known(line)).forEach(line ->
                        logger.warning(where + " > " + quest.id() + " : condition de branche inconnue « " + line + " »"));
            }
            if (stage.objectives().isEmpty() && !stage.auto()) {
                logger.warning(where + " > " + quest.id() + " : l'étape " + stage.id() + " n'a aucun objectif mais se rend à " + stage.turnIn());
            }
        }
    }
}
