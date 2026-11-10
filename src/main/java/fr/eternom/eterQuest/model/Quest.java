package fr.eternom.eterQuest.model;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * Une quête, telle que définie dans un fichier YAML d'un plugin (EterJob : quests/<métier>.yml ; EterQuest : quests/)
 * puis partagée par la base avec tout le réseau.
 *
 * @param id          identifiant unique et définitif (« fermier-pain-du-village » : minuscules, chiffres, - et _) : la progression des joueurs s'y attache
 * @param owner       plugin propriétaire (« eterjob ») : c'est lui qui donne la quête et paie ses récompenses propres
 * @param category    job, village, event...
 * @param tags        mots libres (le métier, daily, rare, lobby...)
 * @param name        nom affiché (MiniMessage) ; vide : celui que le propriétaire donne
 * @param description lignes de présentation (MiniMessage)
 * @param icon        icône (null : celle du premier objectif)
 * @param requires    conditions pour la recevoir (voir Requirements)
 * @param stages      les étapes, dans l'ordre
 * @param rewards     à la fin : commandes console ({player}) ou « action: <action d'EterLib> »
 * @param reputation  réputation gagnée à la fin (EterLib ; négative : perdue)
 * @param meta        données pour le propriétaire (EterJob : kind, tier, reward, xp, min-rank, rank)
 * @param abandon     le joueur peut l'abandonner
 * @param version     version du fichier : la plus haute l'emporte sur le réseau
 * @param hash        empreinte du contenu (même version, contenu différent : avertissement)
 * @param body        le contenu complet (YAML, valeurs par défaut du fichier comprises), tel que gardé en base
 */
public record Quest(String id, String owner, String category, List<String> tags, String name, List<String> description,
                    Material icon, List<String> requires, List<Stage> stages, List<String> rewards, double reputation,
                    Map<String, String> meta, boolean abandon, int version, String hash, String body) {

    public Quest {
        tags = List.copyOf(tags);
        description = List.copyOf(description);
        requires = List.copyOf(requires);
        stages = List.copyOf(stages);
        rewards = List.copyOf(rewards);
        meta = Map.copyOf(meta);
    }

    public Stage stage(int index) {
        return stages.get(Math.clamp(index, 0, stages.size() - 1));
    }

    public int indexOf(String stageId) {
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).id().equals(stageId)) {
                return i;
            }
        }
        return -1;
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    public String meta(String key, String fallback) {
        return meta.getOrDefault(key, fallback);
    }

    public double metaNumber(String key, double fallback) {
        try {
            return Double.parseDouble(meta.getOrDefault(key, "").replace(',', '.'));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** L'étape d'après index, sans les branches (next, sinon la suivante) ; -1 : la quête se termine. */
    public int plainNext(int index) {
        Stage stage = stage(index);
        if (stage.end()) {
            return -1;
        }
        if (!stage.next().isEmpty()) {
            return indexOf(stage.next());
        }
        return index + 1 < stages.size() ? index + 1 : -1;
    }
}
