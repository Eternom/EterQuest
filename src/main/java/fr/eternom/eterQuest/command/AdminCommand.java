package fr.eternom.eterQuest.command;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterQuest.api.QuestCompleteEvent;
import fr.eternom.eterQuest.api.QuestInstance;
import fr.eternom.eterQuest.model.Objective;
import fr.eternom.eterQuest.model.Quest;
import fr.eternom.eterQuest.model.Stage;
import fr.eternom.eterQuest.service.QuestService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * /eterquest (staff, eterquest.admin) :
 * <pre>
 *   list [propriétaire]                   les quêtes du réseau
 *   info <quête>                          détail : étapes, objectifs, conditions
 *   player <joueur>                       ses quêtes en cours (joueur connecté ici)
 *   give <joueur> <quête>                 donne une quête (propriétaire eterquest)
 *   stage <joueur> <owner/slot> <étape>   change d'étape (id ou numéro)
 *   complete <joueur> <owner/slot>        termine (le propriétaire paie ses récompenses)
 *   remove <joueur> <owner/slot>          retire
 *   reputation <joueur> [set|add <points>]
 *   debug <joueur>                        suivre ses signaux et progrès
 *   stats [jours]                         quêtes finies par jour
 *   reload                                réglages et quêtes, sur tout le réseau
 * </pre>
 */
public class AdminCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("list", "info", "player", "give", "stage", "complete", "remove", "reputation",
            "debug", "stats", "reload");

    private final QuestService service;
    private final Messages messages;
    private final Runnable reload;

    public AdminCommand(QuestService service, Runnable reload) {
        this.service = service;
        this.messages = service.messages();
        this.reload = reload;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            messages.send(sender, "admin.usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender, args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null);
            case "info" -> info(sender, args);
            case "player" -> player(sender, args);
            case "give" -> give(sender, args);
            case "stage" -> stage(sender, args);
            case "complete" -> complete(sender, args);
            case "remove" -> remove(sender, args);
            case "reputation" -> reputation(sender, args);
            case "debug" -> debug(sender, args);
            case "stats" -> stats(sender, args);
            case "reload" -> {
                reload.run();
                messages.send(sender, "admin.reloaded");
            }
            default -> messages.send(sender, "admin.usage");
        }
        return true;
    }

    private void list(CommandSender sender, String owner) {
        List<Quest> quests = service.definitions().all().stream().filter(q -> owner == null || q.owner().equals(owner))
                .sorted(Comparator.comparing(Quest::id)).toList();
        messages.send(sender, "admin.list-header", "count", String.valueOf(quests.size()));
        for (Quest quest : quests) {
            messages.send(sender, "admin.list-line", "id", quest.id(), "owner", quest.owner(), "category", quest.category(),
                    "stages", String.valueOf(quest.stages().size()), "version", String.valueOf(quest.version()));
        }
    }

    private void info(CommandSender sender, String[] args) {
        Optional<Quest> found = args.length > 1 ? service.definitions().get(args[1]) : Optional.empty();
        if (found.isEmpty()) {
            messages.send(sender, "admin.unknown-quest");
            return;
        }
        Quest quest = found.get();
        messages.send(sender, "admin.info-header", "id", quest.id(), "owner", quest.owner(), "category", quest.category(),
                "version", String.valueOf(quest.version()), "tags", String.join(", ", quest.tags()));
        if (!quest.requires().isEmpty()) {
            messages.send(sender, "admin.info-requires", "requires", String.join(" · ", quest.requires()));
        }
        for (int i = 0; i < quest.stages().size(); i++) {
            Stage stage = quest.stages().get(i);
            messages.send(sender, "admin.info-stage", "index", String.valueOf(i + 1), "id", stage.id(),
                    "turnin", String.join(", ", stage.turnIn()), "objectives",
                    String.join(" · ", stage.objectives().stream().map(Objective::format).toList()));
        }
        if (!quest.meta().isEmpty()) {
            messages.send(sender, "admin.info-meta", "meta", quest.meta().toString());
        }
    }

    private Player online(CommandSender sender, String[] args, int index) {
        Player player = args.length > index ? Bukkit.getPlayerExact(args[index]) : null;
        if (player == null) {
            messages.send(sender, "admin.player-offline");
        }
        return player;
    }

    private void player(CommandSender sender, String[] args) {
        Player target = online(sender, args, 1);
        if (target == null) {
            return;
        }
        List<QuestInstance> quests = service.quests(target.getUniqueId(), null);
        messages.send(sender, "admin.player-header", "player", target.getName(), "count", String.valueOf(quests.size()));
        long now = System.currentTimeMillis();
        for (QuestInstance quest : quests) {
            messages.send(sender, "admin.player-line", "key", quest.key(), "quest", quest.quest(), "stage", String.valueOf(quest.stage() + 1),
                    "state", quest.expired(now) ? "expired" : quest.state().name().toLowerCase(Locale.ROOT), "progress", quest.progress().toString());
        }
    }

    private void give(CommandSender sender, String[] args) {
        Player target = online(sender, args, 1);
        Optional<Quest> quest = args.length > 2 ? service.definitions().get(args[2]) : Optional.empty();
        if (target == null) {
            return;
        }
        if (quest.isEmpty()) {
            messages.send(sender, "admin.unknown-quest");
            return;
        }
        UUID uuid = target.getUniqueId();
        Tasks.async(service.plugin(), () -> {
            service.store().assign(uuid, "eterquest", quest.get().id(), quest.get().id(), 1, 0, Map.of());
            Bukkit.getScheduler().runTask(service.plugin(), () -> {
                service.refresh(target);
                messages.send(sender, "admin.given", "player", target.getName(), "quest", quest.get().id());
            });
        }, "Quête non donnée");
    }

    /** owner/slot d'une quête du joueur connu ici. */
    private QuestInstance instance(CommandSender sender, Player target, String key) {
        int slash = key.indexOf('/');
        QuestInstance instance = slash < 0 ? null : service.quest(target.getUniqueId(), key.substring(0, slash), key.substring(slash + 1));
        if (instance == null) {
            messages.send(sender, "admin.unknown-instance");
        }
        return instance;
    }

    private void stage(CommandSender sender, String[] args) {
        Player target = online(sender, args, 1);
        if (target == null || args.length < 4) {
            if (target != null) {
                messages.send(sender, "admin.usage");
            }
            return;
        }
        QuestInstance instance = instance(sender, target, args[2]);
        Quest quest = instance == null ? null : service.definitions().getOrNull(instance.quest());
        if (quest == null) {
            return;
        }
        int to = quest.indexOf(args[3]);
        if (to < 0) {
            try {
                to = Integer.parseInt(args[3]) - 1;
            } catch (NumberFormatException e) {
                to = -1;
            }
        }
        if (to < 0 || to >= quest.stages().size()) {
            messages.send(sender, "admin.unknown-stage");
            return;
        }
        int target2 = to;
        UUID uuid = target.getUniqueId();
        Tasks.async(service.plugin(), () -> {
            service.flush(uuid);
            boolean ok = service.store().advance(uuid, instance.owner(), instance.slot(), instance.token(), instance.stage(), target2);
            Bukkit.getScheduler().runTask(service.plugin(), () -> {
                service.refresh(target);
                messages.send(sender, ok ? "admin.stage-set" : "admin.failed", "stage", String.valueOf(target2 + 1));
            });
        }, "Étape non changée");
    }

    private void complete(CommandSender sender, String[] args) {
        Player target = online(sender, args, 1);
        if (target == null || args.length < 3) {
            return;
        }
        QuestInstance instance = instance(sender, target, args[2]);
        Quest quest = instance == null ? null : service.definitions().getOrNull(instance.quest());
        if (quest == null) {
            return;
        }
        UUID uuid = target.getUniqueId();
        Tasks.async(service.plugin(), () -> {
            service.flush(uuid);
            boolean ok = service.store().complete(uuid, instance.owner(), instance.slot(), instance.token(), instance.stage(), quest.id());
            Bukkit.getScheduler().runTask(service.plugin(), () -> {
                if (ok && target.isOnline()) {
                    Bukkit.getPluginManager().callEvent(new QuestCompleteEvent(target,
                            instance.withState(QuestInstance.State.DONE, System.currentTimeMillis()), quest));
                }
                service.refresh(target);
                messages.send(sender, ok ? "admin.completed" : "admin.failed");
            });
        }, "Quête non terminée");
    }

    private void remove(CommandSender sender, String[] args) {
        Player target = online(sender, args, 1);
        if (target == null || args.length < 3) {
            return;
        }
        QuestInstance instance = instance(sender, target, args[2]);
        if (instance == null) {
            return;
        }
        UUID uuid = target.getUniqueId();
        Tasks.async(service.plugin(), () -> {
            service.store().remove(uuid, instance.owner(), instance.slot());
            Bukkit.getScheduler().runTask(service.plugin(), () -> {
                service.refresh(target);
                messages.send(sender, "admin.removed");
            });
        }, "Quête non retirée");
    }

    private void reputation(CommandSender sender, String[] args) {
        Player target = online(sender, args, 1);
        if (target == null) {
            return;
        }
        UUID uuid = target.getUniqueId();
        var reputation = EterLib.get().getReputation();
        if (args.length < 4) {
            messages.send(sender, "admin.reputation", "player", target.getName(), "points", String.valueOf(Math.round(reputation.cached(uuid))));
            return;
        }
        double value;
        try {
            value = Double.parseDouble(args[3].replace(',', '.'));
        } catch (NumberFormatException e) {
            messages.send(sender, "admin.not-a-number");
            return;
        }
        boolean set = args[2].equalsIgnoreCase("set");
        String by = "staff " + sender.getName();
        Tasks.async(service.plugin(), () -> {
            double now = set ? reputation.set(uuid, value, by) : reputation.add(uuid, value, by);
            Bukkit.getScheduler().runTask(service.plugin(), () ->
                    messages.send(sender, "admin.reputation", "player", target.getName(), "points", String.valueOf(Math.round(now))));
        }, "Réputation non changée");
    }

    private void debug(CommandSender sender, String[] args) {
        if (!(sender instanceof Player staff)) {
            messages.send(sender, "error.players-only");
            return;
        }
        Player target = online(sender, args, 1);
        if (target != null) {
            messages.send(sender, service.toggleDebug(staff, target) ? "admin.debug-on" : "admin.debug-off", "player", target.getName());
        }
    }

    private void stats(CommandSender sender, String[] args) {
        int days;
        try {
            days = args.length > 1 ? Math.max(1, Integer.parseInt(args[1])) : 7;
        } catch (NumberFormatException e) {
            days = 7;
        }
        long since = System.currentTimeMillis() - days * 86_400_000L;
        Tasks.async(service.plugin(), () -> {
            Map<String, Map<String, Integer>> stats = service.store().stats(since);
            Bukkit.getScheduler().runTask(service.plugin(), () -> {
                messages.send(sender, "admin.stats-header", "days", String.valueOf(stats.size()));
                stats.forEach((day, owners) -> messages.send(sender, "admin.stats-line", "day", day, "detail", owners.toString()));
            });
        }, "Statistiques non lues");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(SUBS);
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 2 && sub.equals("list")) {
                service.definitions().all().stream().map(Quest::owner).distinct().forEach(options::add);
            } else if (args.length == 2 && sub.equals("info")) {
                service.definitions().all().stream().map(Quest::id).forEach(options::add);
            } else if (args.length == 2 && !sub.equals("stats") && !sub.equals("reload")) {
                Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
            } else if (args.length == 3 && sub.equals("give")) {
                service.definitions().all().stream().map(Quest::id).forEach(options::add);
            } else if (args.length == 3 && Stream.of("stage", "complete", "remove").anyMatch(sub::equals)) {
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target != null) {
                    service.quests(target.getUniqueId(), null).forEach(q -> options.add(q.key()));
                }
            } else if (args.length == 3 && sub.equals("reputation")) {
                options.addAll(List.of("set", "add"));
            }
        }
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).limit(80).toList();
    }
}
