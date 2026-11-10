package fr.eternom.eterQuest.service;

import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterQuest.api.QuestInstance;
import fr.eternom.eterQuest.model.Objective;
import fr.eternom.eterQuest.model.Quest;
import fr.eternom.eterQuest.model.Stage;
import fr.eternom.eterQuest.model.Targets;
import fr.eternom.eterQuest.model.Where;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Les textes des quêtes, communs au journal, à la sidebar, à la barre de boss et aux menus des propriétaires : nom,
 * texte de l'étape, une ligne par objectif avec sa progression (« Récolter Blé 12/64 (la nuit) »). Les noms d'objets,
 * de créatures et de biomes sont traduits par le client ; ceux des groupes sont dans lang/ (group.<groupe>).
 */
public class Texts {

    private final Messages messages;
    private final Map<String, BiFunction<Player, QuestInstance, Component>> namers = new ConcurrentHashMap<>();

    public Texts(Messages messages) {
        this.messages = messages;
    }

    public Messages messages() {
        return messages;
    }

    public void namer(String owner, BiFunction<Player, QuestInstance, Component> namer) {
        namers.put(owner.toLowerCase(Locale.ROOT), namer);
    }

    /** Le nom de la quête : son « name », sinon celui du propriétaire, sinon « Quête ». */
    public Component name(Player viewer, Quest quest, QuestInstance instance) {
        if (quest != null && !quest.name().isBlank()) {
            return render(quest.name());
        }
        BiFunction<Player, QuestInstance, Component> namer = instance == null ? null : namers.get(instance.owner());
        if (namer != null) {
            try {
                return namer.apply(viewer, instance);
            } catch (RuntimeException ignored) {
                // propriétaire arrêté : nom par défaut
            }
        }
        return messages.get(viewer, "quest.default-name");
    }

    public Component render(String miniMessage) {
        return messages.render(miniMessage, TagResolver.empty());
    }

    /** Le texte de l'étape, s'il y en a un. */
    public Component stage(Stage stage) {
        return stage.text().isBlank() ? null : render(stage.text());
    }

    /** Une ligne par objectif, have = où en est le joueur. */
    public Component objective(Player viewer, Objective objective, int have, int amount) {
        String key = have >= amount ? "objective.progress-done" : "objective.progress";
        Component progress = messages.get(viewer, key, "have", String.valueOf(Math.min(have, amount)), "amount", String.valueOf(amount));
        Component line = line(viewer, objective, progress);
        if (objective.optional()) {
            line = line.append(messages.get(viewer, "objective.optional"));
        }
        return line;
    }

    /** Sans progression (quête finie). */
    public Component label(Player viewer, Objective objective) {
        return line(viewer, objective, Component.empty());
    }

    private Component line(Player viewer, Objective objective, Component progress) {
        Component where = where(viewer, objective.where());
        if (!objective.text().isBlank()) {
            return messages.get(viewer, "objective.custom", TagResolver.resolver(Placeholder.component("text", render(objective.text())),
                    Placeholder.component("progress", progress), Placeholder.component("where", where)));
        }
        Component target;
        String suffix = "";
        if (objective.kind() == Objective.Kind.TALK) {
            target = Component.text(pretty(objective.target()));
        } else if (objective.kind() == Objective.Kind.SIGNAL) {
            target = Component.text(objective.key());
        } else if (objective.isGroup()) {
            target = messages.get(viewer, "group." + objective.target().substring(1));
        } else {
            target = Targets.name(objective);
            if (target == null) {
                target = Component.empty();
                suffix = "-any";
            }
        }
        return messages.get(viewer, "objective." + objective.kind().id() + suffix, TagResolver.resolver(Placeholder.component("progress", progress),
                Placeholder.component("item", target), Placeholder.component("where", where)));
    }

    /** « (la nuit, sous l'orage, sous Y 0) », vide partout. */
    public Component where(Player viewer, Where where) {
        if (where.isAnywhere()) {
            return Component.empty();
        }
        List<String> parts = new ArrayList<>();
        if (!where.time().isEmpty()) {
            parts.add(messages.plain(viewer, "where.time." + where.time()));
        }
        if (!where.weather().isEmpty()) {
            parts.add(messages.plain(viewer, "where.weather." + where.weather()));
        }
        if (!where.dimension().isEmpty()) {
            parts.add(messages.plain(viewer, "where.dimension." + where.dimension()));
        }
        if (where.maxY() != null) {
            parts.add(messages.plain(viewer, "where.max-y", "y", String.valueOf(where.maxY())));
        }
        if (where.minY() != null) {
            parts.add(messages.plain(viewer, "where.min-y", "y", String.valueOf(where.minY())));
        }
        if (!where.biomes().isEmpty()) {
            parts.add(messages.plain(viewer, "where.biomes", "biomes", String.join(", ", where.biomes().stream().map(Texts::pretty).toList())));
        }
        if (!where.servers().isEmpty()) {
            parts.add(messages.plain(viewer, "where.servers", "servers", String.join(", ", where.servers())));
        }
        return messages.get(viewer, "where.format", "conditions", String.join(", ", parts));
    }

    /** « eternpc:mathurin_fermier » -> « Mathurin fermier ». */
    static String pretty(String id) {
        String name = id.contains(":") ? id.substring(id.lastIndexOf(':') + 1) : id;
        name = name.replace('_', ' ').replace('-', ' ');
        return name.isEmpty() ? id : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** Pour les objets (pas d'italique). */
    public static Component plainItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }
}
