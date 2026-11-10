package fr.eternom.eterQuest.service;

import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.quest.QuestBridge;
import fr.eternom.eterQuest.api.QuestApi;
import fr.eternom.eterQuest.api.QuestInstance;
import fr.eternom.eterQuest.api.TurnInResult;
import fr.eternom.eterQuest.menu.Journal;
import fr.eternom.eterQuest.model.Objective;
import fr.eternom.eterQuest.model.Quest;
import fr.eternom.eterQuest.model.Stage;
import fr.eternom.eterQuest.model.Targets;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** L'API d'EterQuest (QuestApi) et le pont d'EterLib (QuestBridge). */
public class QuestApiService implements QuestApi, QuestBridge.Provider {

    private final QuestService service;
    private final Display display;
    private Journal journal;

    public QuestApiService(QuestService service, Display display) {
        this.service = service;
        this.display = display;
    }

    public void journal(Journal journal) {
        this.journal = journal;
    }

    // ---------- Définitions ----------

    @Override
    public List<Quest> register(Plugin owner, File folder) {
        return service.definitions().register(owner, folder);
    }

    @Override
    public Optional<Quest> quest(String id) {
        return service.definitions().get(id);
    }

    @Override
    public Collection<Quest> quests() {
        return service.definitions().all();
    }

    @Override
    public void namer(String owner, BiFunction<Player, QuestInstance, Component> namer) {
        service.texts().namer(owner, namer);
    }

    // ---------- Quêtes des joueurs (bloquant) ----------

    @Override
    public QuestInstance assign(UUID player, String owner, String slot, String quest, double scale, long expiresAt, Map<String, String> meta) {
        QuestInstance instance = service.store().assign(player, owner.toLowerCase(java.util.Locale.ROOT), slot, quest, scale, expiresAt, meta);
        refreshLater(player);
        return instance;
    }

    @Override
    public Optional<QuestInstance> replaceIfActive(UUID player, String owner, String slot, long token, String quest, double scale,
                                                   long expiresAt, Map<String, String> meta) {
        Optional<QuestInstance> replaced = service.store().replaceIfActive(player, owner.toLowerCase(java.util.Locale.ROOT), slot, token,
                quest, scale, expiresAt, meta);
        replaced.ifPresent(instance -> refreshLater(player));
        return replaced;
    }

    @Override
    public List<QuestInstance> instances(UUID player, String owner) {
        service.flush(player);
        return service.store().instances(player, owner.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public void remove(UUID player, String owner, String slot) {
        service.store().remove(player, owner.toLowerCase(java.util.Locale.ROOT), slot);
        refreshLater(player);
    }

    @Override
    public void clear(UUID player, String owner) {
        service.store().clear(player, owner.toLowerCase(java.util.Locale.ROOT));
        refreshLater(player);
    }

    @Override
    public boolean claim(UUID player, String owner, String slot, long token) {
        return service.store().claim(player, owner.toLowerCase(java.util.Locale.ROOT), slot, token);
    }

    @Override
    public List<QuestInstance> unclaimed(UUID player, String owner) {
        return service.store().unclaimed(player, owner.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public boolean canStart(UUID player, Quest quest) {
        return Requirements.testBlocking(player, quest.requires(), service.store()::completed);
    }

    @Override
    public int completions(UUID player, String quest) {
        return service.store().completions(player, quest);
    }

    @Override
    public void deposit(UUID player, String items) {
        service.store().deposit(player, items);
    }

    @Override
    public int boxCount(UUID player) {
        return service.store().boxCount(player);
    }

    /** Le joueur connecté ici relit ses quêtes (sur le thread principal). */
    private void refreshLater(UUID player) {
        Bukkit.getScheduler().runTask(service.plugin(), () -> {
            Player online = Bukkit.getPlayer(player);
            if (online != null) {
                service.refresh(online);
            }
        });
    }

    // ---------- Thread principal ----------

    @Override
    public List<QuestInstance> live(Player player, String owner) {
        return service.quests(player.getUniqueId(), owner.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public void refresh(Player player) {
        service.refresh(player);
    }

    @Override
    public boolean ready(Player player, QuestInstance instance) {
        QuestInstance known = service.quest(player.getUniqueId(), instance.owner(), instance.slot());
        return service.ready(player, known != null && known.token() == instance.token() ? known : instance);
    }

    @Override
    public void turnIn(Player player, String owner, String slot, String place, Consumer<TurnInResult> after) {
        service.turnIn(player, owner.toLowerCase(java.util.Locale.ROOT), slot, place, after);
    }

    @Override
    public Component name(Player player, QuestInstance instance) {
        return service.texts().name(player, service.definitions().getOrNull(instance.quest()), instance);
    }

    @Override
    public List<Component> lore(Player player, QuestInstance instance) {
        List<Component> lore = new ArrayList<>();
        Quest quest = service.definitions().getOrNull(instance.quest());
        if (quest == null) {
            return lore;
        }
        QuestInstance known = service.quest(player.getUniqueId(), instance.owner(), instance.slot());
        QuestInstance current = known != null && known.token() == instance.token() ? known : instance;
        Texts texts = service.texts();
        if (current.done()) {
            for (Objective objective : quest.stage(current.stage()).objectives()) {
                lore.add(Texts.plainItalic(service.messages().get(player, "lore.objective").append(texts.label(player, objective))));
            }
            return lore;
        }
        Stage stage = quest.stage(current.stage());
        if (quest.stages().size() > 1) {
            lore.add(Texts.plainItalic(service.messages().get(player, "lore.stage", "stage", String.valueOf(current.stage() + 1),
                    "stages", String.valueOf(quest.stages().size()))));
        }
        Component text = texts.stage(stage);
        if (text != null) {
            lore.add(Texts.plainItalic(text));
        }
        for (int idx = 0; idx < stage.objectives().size(); idx++) {
            Objective objective = stage.objectives().get(idx);
            lore.add(Texts.plainItalic(service.messages().get(player, "lore.objective")
                    .append(texts.objective(player, objective, service.have(player, current, idx, objective), objective.amount(current.scale())))));
        }
        if (stage.timeLimit() > 0) {
            lore.add(Texts.plainItalic(service.messages().get(player, "lore.time-limit", "time",
                    fr.eternom.eterLib.EterLib.get().formatDuration(player, stage.timeLimit()))));
        }
        if (!stage.turnInNpcs().isEmpty() && !stage.auto()) {
            lore.add(Texts.plainItalic(service.messages().get(player, "lore.turn-in-npc", "npc", Texts.pretty(stage.turnInNpcs().getFirst()))));
        }
        return lore;
    }

    @Override
    public Material icon(QuestInstance instance) {
        Quest quest = service.definitions().getOrNull(instance.quest());
        if (quest == null) {
            return Material.PAPER;
        }
        if (quest.icon() != null) {
            return quest.icon();
        }
        Stage stage = quest.stage(instance.stage());
        return stage.objectives().isEmpty() ? Material.WRITABLE_BOOK : Targets.icon(stage.objectives().getFirst());
    }

    @Override
    public void toggleTrack(Player player, String owner, String slot) {
        display.toggleTrack(player, owner == null ? null : owner.toLowerCase(java.util.Locale.ROOT), slot);
    }

    @Override
    public void toggleQuiet(Player player) {
        display.toggleQuiet(player);
    }

    @Override
    public boolean isQuiet(Player player) {
        return service.isQuiet(player.getUniqueId());
    }

    @Override
    public void openJournal(Player player) {
        if (journal != null) {
            journal.open(player, 0);
        }
    }

    @Override
    public void takeBox(Player player, Runnable after) {
        UUID uuid = player.getUniqueId();
        var plugin = service.plugin();
        var messages = service.messages();
        Tasks.async(plugin, player, () -> service.store().box(uuid), box -> {
            if (box.isEmpty()) {
                messages.send(player, "box.empty");
                after.run();
                return;
            }
            for (Map.Entry<Long, String> parcel : box.entrySet()) {
                List<ItemStack> stacks = Inventories.parse(parcel.getValue());
                if (!Inventories.fits(player.getInventory(), stacks)) {
                    messages.send(player, "box.full");
                    break;
                }
                long id = parcel.getKey();
                Tasks.async(plugin, player, () -> service.store().take(uuid, id), taken -> {
                    if (taken) {
                        service.giveBack(player, stacks);
                        messages.send(player, "box.taken");
                    }
                }, () -> { }, taken -> {
                    if (taken) {
                        Tasks.async(plugin, () -> service.store().deposit(uuid, parcel.getValue()), "Colis non remis dans la boîte");
                    }
                });
            }
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    // ---------- Pont d'EterLib ----------

    @Override
    public void signal(Player player, String key, Map<String, String> data, int amount) {
        service.signal(player, key, data, amount);
    }

    @Override
    public int wanted(Player player, String key, Map<String, String> data) {
        return service.wanted(player, key, data);
    }

    @Override
    public boolean interact(Player player, String npc) {
        return service.interact(player, npc);
    }
}
