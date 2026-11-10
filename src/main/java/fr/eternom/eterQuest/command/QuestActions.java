package fr.eternom.eterQuest.command;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.action.Actions;
import fr.eternom.eterLib.module.action.Actions.Argument;
import fr.eternom.eterQuest.api.QuestInstance;
import fr.eternom.eterQuest.model.Quest;
import fr.eternom.eterQuest.service.QuestApiService;
import fr.eternom.eterQuest.service.QuestService;
import fr.eternom.eterQuest.service.Requirements;
import fr.eternom.eterQuest.service.Msg;
import fr.eternom.eterQuest.service.Rewards;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Les actions et conditions d'EterQuest dans EterLib (PNJ, menus, autres plugins) :
 * <pre>
 *   eterquest:journal                  le journal (/quests)
 *   eterquest:start <quête>            donne une quête (propriétaire eterquest), si ses conditions sont remplies
 *   eterquest:talk <pnj>               comme un clic sur ce PNJ (objectifs « parler », étapes à rendre chez lui)
 *   eterquest:signal <clé> [k=v...]    un signal (ex : eterquest:signal eterhub:secret lieu=crypte)
 *   eterquest:box                      la boîte de récupération
 *   ?eterquest:done <quête>            déjà faite
 *   ?eterquest:active <quête>          en cours (n'importe quel propriétaire)
 *   ?eterquest:stage <quête> <étape>   en cours, à cette étape (id)
 * </pre>
 */
public final class QuestActions {

    private QuestActions() {
    }

    public static void register(JavaPlugin plugin, QuestService service, QuestApiService api) {
        Actions actions = EterLib.get().getActions();
        Messages messages = service.messages();
        actions.register(plugin, "eterquest:journal", "Le journal des quêtes", List.of(), (player, args) -> api.openJournal(player));
        actions.register(plugin, "eterquest:box", "La boîte de récupération des quêtes", List.of(), (player, args) -> api.takeBox(player, () -> { }));
        actions.register(plugin, "eterquest:start", "Donne une quête au joueur (si ses conditions sont remplies)",
                List.of(Argument.required("quête", typed -> service.definitions().all().stream().map(Quest::id).toList())),
                (player, args) -> start(plugin, service, messages, player, args.getFirst()));
        actions.register(plugin, "eterquest:talk", "Comme un clic sur ce PNJ (quêtes)", List.of(Argument.required("pnj")),
                (player, args) -> service.interact(player, args.getFirst()));
        actions.register(plugin, "eterquest:signal", "Envoie un signal aux quêtes du joueur", List.of(Argument.required("clé"),
                Argument.optional("donnée=valeur")), (player, args) -> {
                    Map<String, String> data = new HashMap<>();
                    for (String arg : args.subList(1, args.size())) {
                        int equals = arg.indexOf('=');
                        if (equals > 0) {
                            data.put(arg.substring(0, equals).toLowerCase(Locale.ROOT), arg.substring(equals + 1).toLowerCase(Locale.ROOT));
                        }
                    }
                    service.signal(player, args.getFirst(), data, 1);
                });
        actions.registerCondition(plugin, "eterquest:done", "La quête a déjà été faite", List.of(Argument.required("quête")),
                (player, args) -> service.completed(player.getUniqueId()).contains(args.getFirst()));
        actions.registerCondition(plugin, "eterquest:active", "La quête est en cours", List.of(Argument.required("quête")),
                (player, args) -> active(service, player, args.getFirst()).isPresent());
        actions.registerCondition(plugin, "eterquest:stage", "La quête est en cours, à cette étape",
                List.of(Argument.required("quête"), Argument.required("étape")), (player, args) -> active(service, player, args.getFirst())
                        .map(instance -> {
                            Quest quest = service.definitions().getOrNull(instance.quest());
                            return quest != null && quest.stage(instance.stage()).id().equals(args.get(1));
                        }).orElse(false));
    }

    private static Optional<QuestInstance> active(QuestService service, Player player, String quest) {
        long now = System.currentTimeMillis();
        return service.quests(player.getUniqueId(), null).stream().filter(q -> q.quest().equals(quest) && q.live(now)).findFirst();
    }

    /** eterquest:start : pas déjà en cours, pas déjà faite (sauf tag « repeatable »), conditions remplies. */
    private static void start(JavaPlugin plugin, QuestService service, Messages messages, Player player, String id) {
        Optional<Quest> found = service.definitions().get(id);
        if (found.isEmpty()) {
            plugin.getLogger().warning("eterquest:start : quête inconnue " + id);
            return;
        }
        Quest quest = found.get();
        TagResolver name = TagResolver.resolver(Placeholder.component("quest", service.texts().name(player, quest, null)));
        if (active(service, player, id).isPresent()) {
            Msg.send(messages, player, "quest.already-active", name);
            return;
        }
        if (service.completed(player.getUniqueId()).contains(id) && !quest.hasTag("repeatable")) {
            Msg.send(messages, player, "quest.already-done", name);
            return;
        }
        if (!Requirements.test(player, quest.requires(), service.completed(player.getUniqueId()))) {
            Msg.send(messages, player, "quest.locked", name);
            return;
        }
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> service.store().assign(uuid, "eterquest", id, id, 1, 0, Map.of()), instance -> {
            Msg.send(messages, player, "quest.started", name);
            Rewards.run(plugin, player, quest.stage(0).onStart());
            service.refresh(player);
        }, () -> messages.send(player, "error.generic"));
    }
}
