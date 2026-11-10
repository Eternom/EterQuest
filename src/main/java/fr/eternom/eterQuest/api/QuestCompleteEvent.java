package fr.eternom.eterQuest.api;

import fr.eternom.eterQuest.model.Quest;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Une quête vient d'être finie (une seule fois, même sur deux serveurs). Le propriétaire (instance.owner()) paie ses
 * récompenses propres : QuestApi#claim d'abord (true une seule fois), puis payer. Thread principal.
 */
public class QuestCompleteEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final QuestInstance instance;
    private final Quest quest;

    public QuestCompleteEvent(Player player, QuestInstance instance, Quest quest) {
        super(player);
        this.instance = instance;
        this.quest = quest;
    }

    public QuestInstance instance() {
        return instance;
    }

    public Quest quest() {
        return quest;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
