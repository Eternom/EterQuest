package fr.eternom.eterQuest.service;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.ranking.RankingSource;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterQuest.api.QuestCompleteEvent;
import fr.eternom.eterQuest.api.QuestInstance;
import fr.eternom.eterQuest.api.QuestStageEvent;
import fr.eternom.eterQuest.api.TurnInResult;
import fr.eternom.eterQuest.model.Objective;
import fr.eternom.eterQuest.model.Quest;
import fr.eternom.eterQuest.model.Stage;
import fr.eternom.eterQuest.store.QuestStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Les quêtes des joueurs connectés ici : progression des objectifs, étapes, remises et fins.
 *
 * L'état vit sur le thread principal. Les progrès partent en base par paquets (toutes les 30 s, au départ, avant chaque
 * étape ou fin) en S'AJOUTANT : deux serveurs ne s'écrasent jamais. Un progrès est attaché au token de la quête et à
 * son étape : une quête changée entre-temps ne le reçoit pas. Changer d'étape et finir sont des requêtes qui vérifient
 * leurs conditions (cette quête-là, à cette étape, encore en cours) : une seule fois, même sur deux serveurs.
 */
public class QuestService {

    /** Un progrès pas encore écrit. */
    private record Pending(UUID player, String owner, String slot, long token, int stage, int idx, int target) {
    }

    /** Ce que ce serveur sait d'un joueur connecté. */
    static final class PlayerState {
        final Map<String, QuestInstance> quests = new LinkedHashMap<>();
        final Set<String> completed = new HashSet<>();
        boolean quiet;
        boolean summary;
        boolean playtimeOk = true;
        long lastReminder;
        /** Quêtes déjà annoncées « prêtes à rendre » (pas de répétition à chaque progrès). */
        final Set<String> announcedReady = new HashSet<>();
    }

    private final JavaPlugin plugin;
    private final QuestStore store;
    private final Definitions definitions;
    private final Texts texts;
    private final Messages messages;
    private final String server;
    private volatile Settings settings;
    private Display display;
    private NpcHook npcs;
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private final Map<Pending, Integer> pending = new ConcurrentHashMap<>();
    /** Remises, étapes et fins en cours (joueur|owner/slot) : jamais deux à la fois. */
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    /** Staff qui suit les signaux d'un joueur (/eterquest debug). */
    private final Map<UUID, Set<UUID>> debuggers = new ConcurrentHashMap<>();

    public QuestService(JavaPlugin plugin, QuestStore store, Definitions definitions, Texts texts, Settings settings) {
        this.plugin = plugin;
        this.store = store;
        this.definitions = definitions;
        this.texts = texts;
        this.messages = texts.messages();
        this.settings = settings;
        this.server = EterLib.get().getServerName();
    }

    public void start(Display display, NpcHook npcs) {
        this.display = display;
        this.npcs = npcs;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushAll, 30 * 20L, 30 * 20L);
        Bukkit.getOnlinePlayers().forEach(this::join); // /reload
    }

    public void stop() {
        flushAll();
    }

    public void reload(Settings fresh) {
        this.settings = fresh;
    }

    // ---------- Accès ----------

    public JavaPlugin plugin() {
        return plugin;
    }

    public QuestStore store() {
        return store;
    }

    public Definitions definitions() {
        return definitions;
    }

    public Texts texts() {
        return texts;
    }

    public Messages messages() {
        return messages;
    }

    public Settings settings() {
        return settings;
    }

    public String server() {
        return server;
    }

    PlayerState state(UUID player) {
        return players.get(player);
    }

    public Set<UUID> online() {
        return players.keySet();
    }

    /** Les quêtes connues ici du joueur (toutes, ou d'un propriétaire). */
    public List<QuestInstance> quests(UUID player, String owner) {
        PlayerState state = players.get(player);
        if (state == null) {
            return List.of();
        }
        return state.quests.values().stream().filter(quest -> owner == null || quest.owner().equals(owner)).toList();
    }

    public QuestInstance quest(UUID player, String owner, String slot) {
        PlayerState state = players.get(player);
        return state == null ? null : state.quests.get(owner + "/" + slot);
    }

    public Set<String> completed(UUID player) {
        PlayerState state = players.get(player);
        return state == null ? Set.of() : state.completed;
    }

    // ---------- Connexion ----------

    public void join(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            flush(uuid); // progrès d'un départ très récent d'ici
            return new Object[]{store.instances(uuid), store.prefs(uuid), store.completed(uuid), playtime(uuid)};
        }, result -> {
            @SuppressWarnings("unchecked") List<QuestInstance> instances = (List<QuestInstance>) result[0];
            QuestStore.Prefs prefs = (QuestStore.Prefs) result[1];
            @SuppressWarnings("unchecked") Set<String> completed = (Set<String>) result[2];
            PlayerState state = new PlayerState();
            PlayerState previous = players.get(uuid);
            if (previous != null) {
                state.lastReminder = previous.lastReminder;
                state.announcedReady.addAll(previous.announcedReady);
            }
            state.quiet = prefs.quiet();
            state.summary = prefs.summary();
            state.completed.addAll(completed);
            state.playtimeOk = settings.minPlaytimeMinutes() <= 0 || (long) result[3] >= settings.minPlaytimeMinutes();
            for (QuestInstance instance : instances) {
                state.quests.put(instance.key(), withPending(uuid, instance));
            }
            players.put(uuid, state);
            display.refresh(player);
        }, () -> { });
    }

    /** Ajoute ce qui n'est pas encore écrit à la progression lue en base. */
    private QuestInstance withPending(UUID uuid, QuestInstance instance) {
        QuestInstance merged = instance;
        for (Map.Entry<Pending, Integer> entry : pending.entrySet()) {
            Pending key = entry.getKey();
            if (key.player().equals(uuid) && key.owner().equals(instance.owner()) && key.slot().equals(instance.slot())
                    && key.token() == instance.token() && key.stage() == instance.stage()) {
                merged = merged.withProgress(key.idx(), Math.min(key.target(), merged.progress(key.idx()) + entry.getValue()));
            }
        }
        return merged;
    }

    /** Temps de jeu actif (minutes, EterSuccess par EterLib) ; Long.MAX_VALUE sans source. Bloquant. */
    private static long playtime(UUID player) {
        try {
            return RankingSource.find("playtime_total").map(source -> source.of(player)[1]).orElse(Long.MAX_VALUE);
        } catch (RuntimeException e) {
            return Long.MAX_VALUE;
        }
    }

    public void quit(Player player) {
        UUID uuid = player.getUniqueId();
        players.remove(uuid);
        debuggers.remove(uuid);
        debuggers.values().forEach(set -> set.remove(uuid));
        busy.removeIf(key -> key.startsWith(uuid + "|"));
        Tasks.async(plugin, () -> flush(uuid), "Progression des quêtes non enregistrée");
    }

    /** Relit les quêtes du joueur (après un changement fait ailleurs). Thread principal. */
    public void refresh(Player player) {
        join(player);
    }

    // ---------- Progression ----------

    /** Une action du jeu (casser, tuer...) : chaque objectif de ce genre qui l'accepte avance de amount. */
    public void record(Player player, Objective.Kind kind, Predicate<Objective> matches, int amount) {
        progress(player, objective -> objective.kind() == kind && matches.test(objective), amount, kind.id());
    }

    /** Un signal d'un autre plugin (QuestBridge). */
    public void signal(Player player, String key, Map<String, String> data, int amount) {
        String id = key.toLowerCase(Locale.ROOT);
        debug(player, id + " " + data + " ×" + amount);
        progress(player, objective -> objective.kind() == Objective.Kind.SIGNAL && objective.key().equals(id)
                && objective.matches(data, false), amount, id);
    }

    private void progress(Player player, Predicate<Objective> matches, int amount, String what) {
        PlayerState state = players.get(player.getUniqueId());
        if (state == null || !state.playtimeOk || amount <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Location location = player.getLocation();
        for (QuestInstance instance : new ArrayList<>(state.quests.values())) {
            if (!instance.live(now)) {
                continue;
            }
            Quest quest = definitions.getOrNull(instance.quest());
            if (quest == null) {
                continue;
            }
            Stage stage = quest.stage(instance.stage());
            if (timedOut(stage, instance, now)) {
                continue;
            }
            QuestInstance updated = instance;
            boolean changed = false;
            for (int idx = 0; idx < stage.objectives().size(); idx++) {
                Objective objective = stage.objectives().get(idx);
                if (!objective.kind().counted()) {
                    continue;
                }
                int target = objective.amount(instance.scale());
                int before = updated.progress(idx);
                if (before >= target || !matches.test(objective) || !objective.where().test(location, server, player.getPlayerTime())) {
                    continue;
                }
                int after = Math.min(target, before + amount);
                pending.merge(new Pending(player.getUniqueId(), instance.owner(), instance.slot(), instance.token(), instance.stage(),
                        idx, target), after - before, Integer::sum);
                updated = updated.withProgress(idx, after);
                changed = true;
                display.progress(player, state, quest, updated, objective, after, target);
            }
            if (!changed) {
                continue;
            }
            state.quests.put(updated.key(), updated);
            debug(player, "  -> " + quest.id() + " (" + what + ") " + updated.progress());
            display.autoTrack(player, state, updated);
            afterProgress(player, state, quest, updated);
        }
        display.refreshSidebar(player);
    }

    /** Après un progrès : étape remplie -> rendue toute seule (auto), ou annoncée prête. */
    private void afterProgress(Player player, PlayerState state, Quest quest, QuestInstance instance) {
        Stage stage = quest.stage(instance.stage());
        if (!countedComplete(stage, instance)) {
            return;
        }
        if (stage.auto() && !stage.hasDeliveries()) {
            advanceOrComplete(player, instance, List.of(), result -> { });
        } else if (state.announcedReady.add(instance.key() + "#" + instance.token() + "#" + instance.stage())) {
            display.ready(player, state, quest, instance);
        }
    }

    /** Les objectifs comptés (non facultatifs) de l'étape sont remplis. */
    public static boolean countedComplete(Stage stage, QuestInstance instance) {
        for (int idx = 0; idx < stage.objectives().size(); idx++) {
            Objective objective = stage.objectives().get(idx);
            if (objective.kind().counted() && !objective.optional() && instance.progress(idx) < objective.amount(instance.scale())) {
                return false;
            }
        }
        return true;
    }

    /** Ce que le joueur a pour cet objectif : objets sur lui (à remettre), ou progression. */
    public int have(Player player, QuestInstance instance, int idx, Objective objective) {
        return objective.kind() == Objective.Kind.DELIVER ? Inventories.count(player.getInventory(), objective) : instance.progress(idx);
    }

    /** Tout est rempli, objets sur lui compris. */
    public boolean ready(Player player, QuestInstance instance) {
        if (!instance.live(System.currentTimeMillis())) {
            return false;
        }
        Quest quest = definitions.getOrNull(instance.quest());
        if (quest == null) {
            return false;
        }
        Stage stage = quest.stage(instance.stage());
        if (!countedComplete(stage, instance)) {
            return false;
        }
        for (int idx = 0; idx < stage.objectives().size(); idx++) {
            Objective objective = stage.objectives().get(idx);
            if (objective.kind() == Objective.Kind.DELIVER && !objective.optional()
                    && Inventories.count(player.getInventory(), objective) < objective.amount(instance.scale())) {
                return false;
            }
        }
        return true;
    }

    /** Combien les quêtes du joueur veulent encore de ce signal (correspondance souple). */
    public int wanted(Player player, String key, Map<String, String> data) {
        PlayerState state = players.get(player.getUniqueId());
        if (state == null) {
            return 0;
        }
        String id = key.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Location location = player.getLocation();
        int wanted = 0;
        for (QuestInstance instance : state.quests.values()) {
            Quest quest = instance.live(now) ? definitions.getOrNull(instance.quest()) : null;
            if (quest == null) {
                continue;
            }
            Stage stage = quest.stage(instance.stage());
            for (int idx = 0; idx < stage.objectives().size(); idx++) {
                Objective objective = stage.objectives().get(idx);
                if (objective.kind() == Objective.Kind.SIGNAL && objective.key().equals(id) && objective.matches(data, true)
                        && objective.where().test(location, server, player.getPlayerTime())) {
                    wanted += Math.max(0, objective.amount(instance.scale()) - instance.progress(idx));
                }
            }
        }
        return wanted;
    }

    // ---------- PNJ ----------

    /**
     * Un clic sur un PNJ : parler (objectifs TALK), ou rendre une étape qui se rend chez lui. true si une quête s'en est
     * servie (le PNJ ne fait alors rien d'autre).
     */
    public boolean interact(Player player, String npc) {
        PlayerState state = players.get(player.getUniqueId());
        if (state == null) {
            return false;
        }
        String id = npc.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        boolean used = false;
        Component waiting = null;
        for (QuestInstance instance : new ArrayList<>(state.quests.values())) {
            Quest quest = instance.live(now) ? definitions.getOrNull(instance.quest()) : null;
            if (quest == null || busy.contains(busyKey(player.getUniqueId(), instance))) {
                continue;
            }
            Stage stage = quest.stage(instance.stage());
            QuestInstance updated = instance;
            boolean talked = false;
            for (int idx = 0; idx < stage.objectives().size(); idx++) {
                Objective objective = stage.objectives().get(idx);
                int target = objective.amount(instance.scale());
                if (objective.kind() == Objective.Kind.TALK && objective.target().equals(id) && updated.progress(idx) < target) {
                    int before = updated.progress(idx);
                    pending.merge(new Pending(player.getUniqueId(), instance.owner(), instance.slot(), instance.token(), instance.stage(),
                            idx, target), 1, Integer::sum);
                    updated = updated.withProgress(idx, before + 1);
                    talked = true;
                }
            }
            if (talked) {
                used = true;
                state.quests.put(updated.key(), updated);
                display.say(player, id, stage.say());
                afterProgress(player, state, quest, updated);
                break; // un clic, une chose
            }
            if (stage.accepts("npc:" + id)) {
                if (ready(player, updated)) {
                    used = true;
                    display.say(player, id, stage.say());
                    turnIn(player, updated.owner(), updated.slot(), "npc:" + id, result -> display.turnInResult(player, result));
                    break;
                }
                waiting = texts.name(player, quest, updated);
            }
        }
        if (!used && waiting != null) {
            Msg.actionBar(messages, player, "quest.npc-waiting", TagResolver.resolver(Placeholder.component("quest", waiting)));
        }
        display.refreshSidebar(player);
        return used;
    }

    // ---------- Rendre, étapes, fin ----------

    /** Thread principal : rendre l'étape en cours (voir QuestApi#turnIn). */
    public void turnIn(Player player, String owner, String slot, String place, Consumer<TurnInResult> after) {
        UUID uuid = player.getUniqueId();
        QuestInstance instance = quest(uuid, owner, slot);
        long now = System.currentTimeMillis();
        if (instance == null || instance.state() != QuestInstance.State.ACTIVE) {
            after.accept(TurnInResult.ALREADY);
            return;
        }
        if (instance.expired(now)) {
            after.accept(TurnInResult.EXPIRED);
            return;
        }
        Quest quest = definitions.getOrNull(instance.quest());
        if (quest == null) {
            after.accept(TurnInResult.ERROR);
            return;
        }
        Stage stage = quest.stage(instance.stage());
        String where = Stage.normalize(place);
        if (!stage.accepts(where)) {
            after.accept(where.equals("remote") && stage.hasDeliveries() && stage.accepts("giver")
                    ? TurnInResult.REMOTE_ITEMS : TurnInResult.NOT_HERE);
            return;
        }
        if (where.equals("remote") && stage.hasDeliveries()) {
            after.accept(TurnInResult.REMOTE_ITEMS);
            return;
        }
        if (!countedComplete(stage, instance)) {
            after.accept(TurnInResult.NOT_READY);
            return;
        }
        for (Objective objective : stage.objectives()) {
            if (objective.kind() == Objective.Kind.DELIVER && !objective.optional()
                    && Inventories.count(player.getInventory(), objective) < objective.amount(instance.scale())) {
                after.accept(TurnInResult.MISSING_ITEMS);
                return;
            }
        }
        if (busy.contains(busyKey(uuid, instance))) {
            after.accept(TurnInResult.BUSY);
            return;
        }
        List<ItemStack> taken = new ArrayList<>();
        for (Objective objective : stage.objectives()) {
            if (objective.kind() == Objective.Kind.DELIVER && !objective.optional()) {
                taken.addAll(Inventories.take(player.getInventory(), objective, objective.amount(instance.scale())));
            }
        }
        advanceOrComplete(player, instance, taken, after);
    }

    /** L'étape est remplie (et rendue) : la suivante, ou la fin. taken : objets pris, rendus si ça échoue. */
    private void advanceOrComplete(Player player, QuestInstance instance, List<ItemStack> taken, Consumer<TurnInResult> after) {
        Quest quest = definitions.getOrNull(instance.quest());
        PlayerState state = players.get(player.getUniqueId());
        if (quest == null || state == null) {
            giveBack(player, taken);
            after.accept(TurnInResult.ERROR);
            return;
        }
        int next = next(player, state, quest, instance.stage());
        if (next < 0) {
            complete(player, quest, instance, taken, after);
        } else {
            advance(player, quest, instance, next, taken, after);
        }
    }

    /** L'étape d'après : la première branche vraie, sinon next, sinon la suivante ; -1 : fin. */
    private int next(Player player, PlayerState state, Quest quest, int from) {
        Stage stage = quest.stage(from);
        if (stage.end()) {
            return -1;
        }
        for (Stage.Branch branch : stage.branches()) {
            if (Requirements.test(player, branch.when(), state.completed)) {
                int index = quest.indexOf(branch.to());
                if (index >= 0) {
                    return index;
                }
            }
        }
        return quest.plainNext(from);
    }

    private void advance(Player player, Quest quest, QuestInstance instance, int to, List<ItemStack> taken, Consumer<TurnInResult> after) {
        UUID uuid = player.getUniqueId();
        String key = busyKey(uuid, instance);
        if (!busy.add(key)) {
            giveBack(player, taken);
            after.accept(TurnInResult.BUSY);
            return;
        }
        int from = instance.stage();
        Tasks.async(plugin, player, () -> {
            flush(uuid);
            return store.advance(uuid, instance.owner(), instance.slot(), instance.token(), from, to);
        }, ok -> {
            busy.remove(key);
            PlayerState state = players.get(uuid);
            if (!ok || state == null) {
                giveBack(player, taken);
                after.accept(TurnInResult.ALREADY);
                refresh(player);
                return;
            }
            QuestInstance advanced = instance.withStage(to, System.currentTimeMillis());
            state.quests.put(advanced.key(), advanced);
            Stage done = quest.stage(from);
            Stage next = quest.stage(to);
            Rewards.run(plugin, player, done.onComplete());
            Rewards.run(plugin, player, next.onStart());
            display.stageAdvanced(player, state, quest, advanced);
            Bukkit.getPluginManager().callEvent(new QuestStageEvent(player, advanced, quest, from));
            after.accept(TurnInResult.ADVANCED);
            // Une étape sans objectif à rendre toute seule (un embranchement) : on continue
            if (next.auto() && !next.hasDeliveries() && countedComplete(next, advanced) && next.objectives().stream()
                    .noneMatch(objective -> objective.kind().counted() && !objective.optional())) {
                advanceOrComplete(player, advanced, List.of(), result -> { });
            }
            display.refreshSidebar(player);
        }, () -> {
            busy.remove(key);
            giveBack(player, taken);
            after.accept(TurnInResult.ERROR);
        }, ok -> busy.remove(key));
    }

    private void complete(Player player, Quest quest, QuestInstance instance, List<ItemStack> taken, Consumer<TurnInResult> after) {
        UUID uuid = player.getUniqueId();
        String key = busyKey(uuid, instance);
        if (!busy.add(key)) {
            giveBack(player, taken);
            after.accept(TurnInResult.BUSY);
            return;
        }
        Stage stage = quest.stage(instance.stage());
        Tasks.async(plugin, player, () -> {
            flush(uuid);
            // La base fait foi : chaque objectif compté est-il rempli (ailleurs aussi) ?
            Map<Integer, Integer> counters = store.counters(uuid, instance.owner(), instance.slot(), instance.token(), instance.stage());
            for (int idx = 0; idx < stage.objectives().size(); idx++) {
                Objective objective = stage.objectives().get(idx);
                if (objective.kind().counted() && !objective.optional()
                        && counters.getOrDefault(idx, 0) < objective.amount(instance.scale())) {
                    return TurnInResult.NOT_READY;
                }
            }
            if (!store.complete(uuid, instance.owner(), instance.slot(), instance.token(), instance.stage(), quest.id())) {
                return TurnInResult.ALREADY;
            }
            if (quest.reputation() != 0) {
                try {
                    EterLib.get().getReputation().add(uuid, quest.reputation(), "quête " + quest.id());
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Réputation de la quête " + quest.id() + " non donnée : " + e.getMessage());
                }
            }
            return TurnInResult.DONE;
        }, result -> {
            busy.remove(key);
            PlayerState state = players.get(uuid);
            if (result != TurnInResult.DONE || state == null) {
                giveBack(player, taken);
                after.accept(result);
                refresh(player);
                return;
            }
            QuestInstance done = instance.withState(QuestInstance.State.DONE, System.currentTimeMillis());
            state.quests.put(done.key(), done);
            state.completed.add(quest.id());
            Rewards.run(plugin, player, stage.onComplete());
            Rewards.run(plugin, player, quest.rewards());
            display.completed(player, state, quest, done);
            Bukkit.getPluginManager().callEvent(new QuestCompleteEvent(player, done, quest));
            after.accept(TurnInResult.DONE);
            display.refreshSidebar(player);
        }, () -> {
            busy.remove(key);
            giveBack(player, taken);
            after.accept(TurnInResult.ERROR);
        }, result -> {
            busy.remove(key);
            if (result != TurnInResult.DONE && !taken.isEmpty()) {
                // Pas finie, et le joueur est parti : ses objets attendent dans sa boîte
                Tasks.async(plugin, () -> store.deposit(uuid, Inventories.text(taken)), "Objets de quête non mis de côté pour " + uuid);
            }
        });
    }

    /** Rend des objets ; ce qui ne tient pas va dans la boîte (jamais au sol). */
    public void giveBack(Player player, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        List<ItemStack> left = player.isOnline() ? Inventories.give(player.getInventory(), items) : items;
        if (!left.isEmpty()) {
            UUID uuid = player.getUniqueId();
            String text = Inventories.text(left);
            Tasks.async(plugin, () -> store.deposit(uuid, text), "Objets non mis de côté pour " + player.getName());
            messages.send(player, "box.added");
        }
    }

    /** Abandonner une quête en cours (si elle le permet). */
    public void abandon(Player player, QuestInstance instance, Runnable after) {
        Quest quest = definitions.getOrNull(instance.quest());
        if (quest == null || !quest.abandon() || instance.state() != QuestInstance.State.ACTIVE) {
            messages.send(player, "quest.cannot-abandon");
            return;
        }
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> store.end(uuid, instance.owner(), instance.slot(), instance.token(),
                QuestInstance.State.ABANDONED), ok -> {
            if (ok) {
                Msg.send(messages, player, "quest.abandoned", TagResolver.resolver(Placeholder.component("quest", texts.name(player, quest, instance))));
            }
            refresh(player);
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    /** Toutes les 2 s (Display) : le biome du joueur, s'il a un objectif d'exploration. */
    void explore(Player player, PlayerState state) {
        long now = System.currentTimeMillis();
        boolean exploring = state.quests.values().stream().anyMatch(instance -> {
            Quest quest = instance.live(now) ? definitions.getOrNull(instance.quest()) : null;
            return quest != null && quest.stage(instance.stage()).objectives().stream().anyMatch(o -> o.kind() == Objective.Kind.EXPLORE);
        });
        if (exploring) {
            org.bukkit.block.Biome biome = player.getLocation().getBlock().getBiome();
            record(player, Objective.Kind.EXPLORE, objective -> fr.eternom.eterQuest.model.Targets.matchesBiome(objective, biome), 1);
        }
    }

    // ---------- Temps limité ----------

    static boolean timedOut(Stage stage, QuestInstance instance, long now) {
        return stage.timeLimit() > 0 && now > instance.stageAt() + stage.timeLimit() * 1000L;
    }

    /** Chaque seconde (Display) : une étape à temps limité dépassé recommence. */
    void checkTimers(Player player, PlayerState state) {
        long now = System.currentTimeMillis();
        for (QuestInstance instance : new ArrayList<>(state.quests.values())) {
            Quest quest = instance.live(now) ? definitions.getOrNull(instance.quest()) : null;
            if (quest == null) {
                continue;
            }
            Stage stage = quest.stage(instance.stage());
            String key = busyKey(player.getUniqueId(), instance);
            if (!timedOut(stage, instance, now) || countedComplete(stage, instance) || !busy.add(key)) {
                continue;
            }
            UUID uuid = player.getUniqueId();
            pending.keySet().removeIf(p -> p.player().equals(uuid) && p.owner().equals(instance.owner()) && p.slot().equals(instance.slot()));
            Tasks.async(plugin, player, () -> store.restartStage(uuid, instance.owner(), instance.slot(), instance.token(), instance.stage()),
                    ok -> {
                        busy.remove(key);
                        PlayerState current = players.get(uuid);
                        if (ok && current != null) {
                            QuestInstance restarted = instance.restarted(System.currentTimeMillis());
                            current.quests.put(restarted.key(), restarted);
                            Msg.send(messages, player, "quest.time-up", TagResolver.resolver(Placeholder.component("quest",
                                    texts.name(player, quest, instance))));
                            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            display.refreshSidebar(player);
                        }
                    }, () -> busy.remove(key), ok -> busy.remove(key));
        }
    }

    // ---------- Base ----------

    /** Bloquant : écrit les progrès en attente d'un joueur. */
    public void flush(UUID player) {
        for (Pending key : pending.keySet()) {
            if (key.player().equals(player)) {
                write(key);
            }
        }
    }

    private void flushAll() {
        pending.keySet().forEach(this::write);
    }

    private void write(Pending key) {
        Integer delta = pending.remove(key);
        if (delta != null && delta > 0) {
            try {
                store.addCounter(key.player(), key.owner(), key.slot(), key.token(), key.stage(), key.idx(), delta, key.target());
            } catch (RuntimeException e) {
                pending.merge(key, delta, Integer::sum); // réessayé au prochain passage
                throw e;
            }
        }
    }

    private static String busyKey(UUID player, QuestInstance instance) {
        return player + "|" + instance.key();
    }

    // ---------- Préférences ----------

    public void setPrefs(Player player, boolean quiet, boolean summary) {
        PlayerState state = players.get(player.getUniqueId());
        if (state != null) {
            state.quiet = quiet;
            state.summary = summary;
        }
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, () -> store.setPrefs(uuid, new QuestStore.Prefs(quiet, summary)), "Préférences des quêtes non enregistrées");
    }

    public boolean isQuiet(UUID player) {
        PlayerState state = players.get(player);
        return state != null && state.quiet;
    }

    public boolean isSummary(UUID player) {
        PlayerState state = players.get(player);
        return state != null && state.summary;
    }

    // ---------- Suivi du staff ----------

    /** Le staff suit (ou arrête de suivre) les signaux et progrès d'un joueur. */
    public boolean toggleDebug(Player staff, Player target) {
        Set<UUID> watchers = debuggers.computeIfAbsent(target.getUniqueId(), uuid -> ConcurrentHashMap.newKeySet());
        if (watchers.remove(staff.getUniqueId())) {
            return false;
        }
        watchers.add(staff.getUniqueId());
        return true;
    }

    private void debug(Player player, String text) {
        Set<UUID> watchers = debuggers.get(player.getUniqueId());
        if (watchers == null || watchers.isEmpty()) {
            return;
        }
        for (UUID watcher : watchers) {
            Player staff = Bukkit.getPlayer(watcher);
            if (staff != null) {
                messages.send(staff, "admin.debug-line", "player", player.getName(), "text", text);
            }
        }
    }

    public NpcHook npcs() {
        return npcs;
    }

    /** Pour Display : tous les états des joueurs connectés. */
    Map<UUID, PlayerState> states() {
        return players;
    }

    /** Copie des compteurs connus (affichage de debug). */
    Map<String, Integer> pendingFor(UUID player) {
        Map<String, Integer> out = new HashMap<>();
        pending.forEach((key, value) -> {
            if (key.player().equals(player)) {
                out.put(key.owner() + "/" + key.slot() + "#" + key.idx(), value);
            }
        });
        return out;
    }
}
