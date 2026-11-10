package fr.eternom.eterQuest.command;

import fr.eternom.eterQuest.service.QuestApiService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/** /quests [box|sidebar|messages] : le journal des quêtes, la boîte de récupération, le résumé, les messages. */
public class QuestsCommand implements TabExecutor {

    private final QuestApiService api;
    private final fr.eternom.eterLib.helper.message.Messages messages;

    public QuestsCommand(QuestApiService api, fr.eternom.eterLib.helper.message.Messages messages) {
        this.api = api;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.players-only");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "box" -> api.takeBox(player, () -> { });
            case "sidebar" -> api.toggleTrack(player, null, null);
            case "messages" -> api.toggleQuiet(player);
            default -> api.openJournal(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return List.of("box", "sidebar", "messages").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
