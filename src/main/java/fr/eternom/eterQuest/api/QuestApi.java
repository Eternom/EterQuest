package fr.eternom.eterQuest.api;

import fr.eternom.eterQuest.model.Quest;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Le moteur de quêtes du réseau. Un plugin ne fait pas ses quêtes lui-même : il les écrit en YAML (format :
 * QuestParser), les enregistre ici (register), les donne aux joueurs (assign), les fait rendre (turnIn) et paie ses
 * récompenses propres à la fin (QuestCompleteEvent puis claim). Pour faire avancer des objectifs depuis un autre plugin
 * sans en dépendre : QuestBridge d'EterLib (signaux).
 *
 * Obtenir : QuestApi.get() (EterQuest en depend ou softdepend). compileOnly("com.github.Eternom:EterQuest:<tag>").
 * « Bloquant » : hors du thread principal (base de données). Les autres : thread principal.
 */
public interface QuestApi {

    static Optional<QuestApi> get() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(QuestApi.class));
    }

    // ---------- Définitions ----------

    /**
     * Bloquant. Lit les fichiers .yml du dossier (sous-dossiers compris) et envoie leurs quêtes à tout le réseau (une
     * version plus haute remplace, jamais l'inverse). Renvoie les quêtes lues ici (même celles qu'une version plus récente
     * ailleurs remplace : la quête en vigueur est celle de quest(id)).
     */
    List<Quest> register(Plugin owner, File folder);

    /** La quête en vigueur sur le réseau. */
    Optional<Quest> quest(String id);

    Collection<Quest> quests();

    /** Le nom affiché des quêtes d'un propriétaire sans « name » (EterJob : « Quête facile », « ✦ Quête rare »...). */
    void namer(String owner, BiFunction<Player, QuestInstance, Component> namer);

    // ---------- Quêtes des joueurs (bloquant) ----------

    /** Donne une quête dans cet emplacement du propriétaire (remplace celle qui y était). Le joueur connecté ici la voit. */
    QuestInstance assign(UUID player, String owner, String slot, String quest, double scale, long expiresAt, Map<String, String> meta);

    /** Remplace une quête encore en cours (son token) : vide si elle a été finie ou changée entre-temps. */
    Optional<QuestInstance> replaceIfActive(UUID player, String owner, String slot, long token, String quest, double scale,
                                            long expiresAt, Map<String, String> meta);

    /** Les quêtes du joueur chez ce propriétaire (progression écrite comprise). */
    List<QuestInstance> instances(UUID player, String owner);

    void remove(UUID player, String owner, String slot);

    void clear(UUID player, String owner);

    /** Le propriétaire réserve le paiement de ses récompenses : true une seule fois (puis payer). */
    boolean claim(UUID player, String owner, String slot, long token);

    /** Finies sans que le propriétaire ait payé (finies sur un serveur où il ne tourne pas) : claim puis payer. */
    List<QuestInstance> unclaimed(UUID player, String owner);

    /** Les conditions « requires » de la quête (réputation, quêtes faites, permission) ; les conditions ?EterLib ne sont pas testées ici. */
    boolean canStart(UUID player, Quest quest);

    int completions(UUID player, String quest);

    /** Met des objets dans la boîte de récupération (« MATIÈRE quantité;... »). */
    void deposit(UUID player, String items);

    int boxCount(UUID player);

    // ---------- Thread principal ----------

    /** Les quêtes de ce propriétaire telles que ce serveur les connaît (progression à jour). */
    List<QuestInstance> live(Player player, String owner);

    /** Relit les quêtes du joueur en base (après assign/replace depuis une tâche de fond, c'est fait tout seul). */
    void refresh(Player player);

    /** Tous les objectifs de l'étape en cours sont remplis (objets sur lui compris) : il ne reste qu'à rendre. */
    boolean ready(Player player, QuestInstance instance);

    /**
     * Rendre l'étape en cours : place = « giver » (le donneur du propriétaire), « remote » (à distance), « npc:<id> ».
     * Objets pris, puis l'étape suivante ou la fin ; refusée : objets rendus. after : le résultat (thread principal).
     */
    void turnIn(Player player, String owner, String slot, String place, Consumer<TurnInResult> after);

    /** Nom de la quête pour ce joueur. */
    Component name(Player player, QuestInstance instance);

    /** Lignes de description : texte de l'étape, objectifs avec leur progression, limite de temps. */
    List<Component> lore(Player player, QuestInstance instance);

    Material icon(QuestInstance instance);

    /** Suivre cette quête dans la sidebar (ou arrêter) ; owner null : le résumé de toutes. */
    void toggleTrack(Player player, String owner, String slot);

    void toggleQuiet(Player player);

    boolean isQuiet(Player player);

    /** Le journal des quêtes (/quests). */
    void openJournal(Player player);

    /** Prend les colis de la boîte de récupération qui tiennent dans l'inventaire ; after ensuite. */
    void takeBox(Player player, Runnable after);
}
