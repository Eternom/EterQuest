package fr.eternom.eterQuest.api;

import fr.eternom.eterQuest.model.Quest;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/** Une étape d'une quête est rendue, la suivante commence (from -> to, indices d'étape). Thread principal. */
public class QuestStageEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final QuestInstance instance;
    private final Quest quest;
    private final int from;

    public QuestStageEvent(Player player, QuestInstance instance, Quest quest, int from) {
        super(player);
        this.instance = instance;
        this.quest = quest;
        this.from = from;
    }

    public QuestInstance instance() {
        return instance;
    }

    public Quest quest() {
        return quest;
    }

    public int from() {
        return from;
    }

    public int to() {
        return instance.stage();
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
