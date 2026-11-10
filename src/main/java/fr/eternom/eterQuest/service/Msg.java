package fr.eternom.eterQuest.service;

import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Messages avec des balises déjà mises en forme (le nom d'une quête, un objectif) : Messages ne les envoie pas seul. */
public final class Msg {

    private Msg() {
    }

    public static void send(Messages messages, CommandSender receiver, String key, TagResolver tags, String... placeholders) {
        receiver.sendMessage(messages.prefix().append(messages.get(receiver, key, tags, placeholders)));
    }

    public static void actionBar(Messages messages, Player player, String key, TagResolver tags, String... placeholders) {
        player.sendActionBar(messages.get(player, key, tags, placeholders));
    }
}
