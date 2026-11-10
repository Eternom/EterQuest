package fr.eternom.eterQuest.service;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sidebar.SidebarOverrides;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterQuest.api.QuestInstance;
import fr.eternom.eterQuest.api.TurnInResult;
import fr.eternom.eterQuest.model.Objective;
import fr.eternom.eterQuest.model.Quest;
import fr.eternom.eterQuest.model.Stage;
import fr.eternom.eterQuest.service.QuestService.PlayerState;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ce que le joueur voit de ses quêtes : barre de boss pendant une action, sidebar (la quête suivie, ou le résumé de
 * toutes), messages d'étape et de fin, rappels « prête à rendre », marqueurs au-dessus des PNJ qui l'attendent, temps
 * restant d'une étape chronométrée. Thread principal.
 */
public class Display {

    private static final String SIDEBAR_OWNER = "EterQuest";
    private static final long BOSSBAR_MILLIS = 5000;

    private final JavaPlugin plugin;
    private final QuestService service;
    private final Messages messages;
    private final Texts texts;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Long> barUntil = new HashMap<>();

    public Display(QuestService service) {
        this.plugin = service.plugin();
        this.service = service;
        this.messages = service.messages();
        this.texts = service.texts();
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::second, 20, 20);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> markers(), 100, 100);
        Bukkit.getScheduler().runTaskTimer(plugin, this::minute, 1200, 1200);
    }

    public void quit(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
        barUntil.remove(player.getUniqueId());
        EterLib.get().getSidebars().clear(player.getUniqueId(), SIDEBAR_OWNER);
        if (service.npcs() != null) {
            service.npcs().forget(player.getUniqueId());
        }
    }

    /** Arrivée ou relecture : sidebar et marqueurs. */
    public void refresh(Player player) {
        refreshSidebar(player);
        markers(player);
    }

    // ---------- Progrès ----------

    void progress(Player player, PlayerState state, Quest quest, QuestInstance instance, Objective objective, int have, int amount) {
        if (have >= amount) {
            if (!state.quiet) {
                Msg.actionBar(messages, player, "quest.objective-done", TagResolver.resolver(Placeholder.component("objective",
                        texts.label(player, objective))));
            }
            player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
            return;
        }
        if (state.quiet) {
            return;
        }
        Component text = texts.objective(player, objective, have, amount);
        if (!service.settings().bossBar()) {
            player.sendActionBar(text);
            return;
        }
        UUID uuid = player.getUniqueId();
        float ratio = Math.clamp(have / (float) amount, 0f, 1f);
        BossBar bar = bars.get(uuid);
        if (bar == null) {
            bar = BossBar.bossBar(text, ratio, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            bars.put(uuid, bar);
        } else {
            bar.name(text);
            bar.progress(ratio);
        }
        player.showBossBar(bar);
        barUntil.put(uuid, System.currentTimeMillis() + BOSSBAR_MILLIS);
    }

    /** La première quête qui avance est suivie dans la sidebar, si aucune ne l'est. */
    void autoTrack(Player player, PlayerState state, QuestInstance instance) {
        if (!service.settings().autoTrack() || state.summary || state.quests.values().stream().anyMatch(QuestInstance::tracked)) {
            return;
        }
        state.quests.put(instance.key(), instance.withTracked(true));
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, () -> service.store().track(uuid, instance.owner(), instance.slot()), "Quête suivie non enregistrée");
    }

    void ready(Player player, PlayerState state, Quest quest, QuestInstance instance) {
        Stage stage = quest.stage(instance.stage());
        Component name = texts.name(player, quest, instance);
        List<String> npcs = stage.turnInNpcs();
        String key = !npcs.isEmpty() ? "quest.ready-npc" : stage.accepts("remote") ? "quest.ready-remote" : "quest.ready";
        Msg.send(messages, player, key, TagResolver.resolver(Placeholder.component("quest", name),
                Placeholder.unparsed("npc", npcs.isEmpty() ? "" : Texts.pretty(npcs.getFirst()))));
        player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
    }

    void stageAdvanced(Player player, PlayerState state, Quest quest, QuestInstance instance) {
        Stage stage = quest.stage(instance.stage());
        Component text = texts.stage(stage);
        Msg.send(messages, player, "quest.stage-next", TagResolver.resolver(Placeholder.component("quest", texts.name(player, quest, instance)),
                Placeholder.component("text", text == null ? messages.get(player, "quest.stage-next-default") : text)));
        player.playSound(player, Sound.ITEM_BOOK_PAGE_TURN, 0.9f, 1.1f);
        if (stage.timeLimit() > 0) {
            messages.send(player, "quest.time-limit", "time", EterLib.get().formatDuration(player, stage.timeLimit()));
        }
    }

    void completed(Player player, PlayerState state, Quest quest, QuestInstance instance) {
        // Les quêtes d'un autre plugin (EterJob...) : c'est lui qui annonce la fin avec ses récompenses
        boolean announce = instance.owner().equals("eterquest") || Boolean.parseBoolean(quest.meta("announce", "false"));
        if (announce) {
            Msg.send(messages, player, "quest.done", TagResolver.resolver(Placeholder.component("quest", texts.name(player, quest, instance))));
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        }
        if (quest.reputation() > 0 && !state.quiet) {
            messages.send(player, "reputation.gained", "points", trim(quest.reputation()));
        } else if (quest.reputation() < 0) {
            messages.send(player, "reputation.lost", "points", trim(-quest.reputation()));
        }
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** Ce que dit le PNJ : dans le chat, et la première ligne en bulle (EterNpc). */
    void say(Player player, String npc, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        for (String line : lines) {
            player.sendMessage(messages.get(player, "quest.npc-line", TagResolver.resolver(
                    Placeholder.unparsed("npc", Texts.pretty(npc)), Placeholder.component("text", texts.render(line.replace("{player}", player.getName()))))));
        }
        if (service.npcs() != null) {
            service.npcs().say(player, npc, texts.render(lines.getFirst().replace("{player}", player.getName())));
        }
    }

    /** Le résultat d'une remise faite chez un PNJ (les propriétaires affichent les leurs). */
    void turnInResult(Player player, TurnInResult result) {
        switch (result) {
            case DONE, ADVANCED -> {
            }
            default -> messages.actionBar(player, "turn-in." + result.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        }
    }

    // ---------- Sidebar ----------

    public void refreshSidebar(Player player) {
        PlayerState state = service.state(player.getUniqueId());
        boolean show = state != null && (state.summary && state.quests.values().stream().anyMatch(q -> q.live(System.currentTimeMillis()))
                || tracked(state) != null);
        if (show) {
            EterLib.get().getSidebars().show(player, SIDEBAR_OWNER, this::sidebar);
        } else {
            EterLib.get().getSidebars().clear(player.getUniqueId(), SIDEBAR_OWNER);
        }
    }

    private QuestInstance tracked(PlayerState state) {
        if (state == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        return state.quests.values().stream().filter(q -> q.tracked() && q.live(now)).findFirst().orElse(null);
    }

    /** Appelée par EterTab à chaque rafraîchissement. */
    private SidebarOverrides.Content sidebar(Player player) {
        PlayerState state = service.state(player.getUniqueId());
        if (state == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        if (state.summary) {
            for (QuestInstance instance : state.quests.values()) {
                Quest quest = service.definitions().getOrNull(instance.quest());
                if (quest == null || !instance.live(now)) {
                    continue;
                }
                String key = service.ready(player, instance) ? "sidebar.summary-ready" : "sidebar.summary-todo";
                lines.add(messages.get(player, key, TagResolver.resolver(Placeholder.component("quest", texts.name(player, quest, instance)))));
            }
            lines.add(Component.empty());
            return new SidebarOverrides.Content(messages.get(player, "sidebar.title"), lines);
        }
        QuestInstance instance = tracked(state);
        Quest quest = instance == null ? null : service.definitions().getOrNull(instance.quest());
        if (quest == null) {
            return null;
        }
        Stage stage = quest.stage(instance.stage());
        Component text = texts.stage(stage);
        if (text != null) {
            lines.add(text);
            lines.add(Component.empty());
        }
        for (int idx = 0; idx < stage.objectives().size(); idx++) {
            Objective objective = stage.objectives().get(idx);
            lines.add(texts.objective(player, objective, service.have(player, instance, idx, objective), objective.amount(instance.scale())));
        }
        if (stage.timeLimit() > 0) {
            long left = Math.max(0, instance.stageAt() + stage.timeLimit() * 1000L - now) / 1000;
            lines.add(messages.get(player, "sidebar.time-left", "time", EterLib.get().formatDuration(player, left)));
        }
        lines.add(Component.empty());
        lines.add(messages.get(player, service.ready(player, instance) ? "sidebar.ready" : "sidebar.todo"));
        return new SidebarOverrides.Content(texts.name(player, quest, instance), lines);
    }

    // ---------- Chaque seconde : barre de boss, temps limité ----------

    private void second() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Long until = barUntil.get(uuid);
            if (until != null && now >= until) {
                barUntil.remove(uuid);
                BossBar bar = bars.get(uuid);
                if (bar != null) {
                    player.hideBossBar(bar);
                }
            }
            PlayerState state = service.state(uuid);
            if (state != null) {
                service.checkTimers(player, state);
                if (now / 1000 % 2 == 0) {
                    service.explore(player, state);
                }
            }
        }
    }

    // ---------- Toutes les 5 s : marqueurs des PNJ ----------

    private void markers() {
        if (service.npcs() == null) {
            return;
        }
        Bukkit.getOnlinePlayers().forEach(this::markers);
    }

    /** « ? » au-dessus d'un PNJ à qui parler, « ! » au-dessus de celui à qui rendre une étape prête. */
    private void markers(Player player) {
        NpcHook npcs = service.npcs();
        PlayerState state = service.state(player.getUniqueId());
        if (npcs == null || state == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, Component> wanted = new LinkedHashMap<>();
        for (QuestInstance instance : state.quests.values()) {
            Quest quest = instance.live(now) ? service.definitions().getOrNull(instance.quest()) : null;
            if (quest == null) {
                continue;
            }
            Stage stage = quest.stage(instance.stage());
            for (int idx = 0; idx < stage.objectives().size(); idx++) {
                Objective objective = stage.objectives().get(idx);
                if (objective.kind() == Objective.Kind.TALK && instance.progress(idx) < objective.amount(instance.scale())) {
                    wanted.putIfAbsent(objective.target(), messages.get(player, "marker.talk"));
                }
            }
            if (service.ready(player, instance)) {
                for (String npc : stage.turnInNpcs()) {
                    wanted.put(npc, messages.get(player, "marker.ready"));
                }
            }
        }
        npcs.markers(player, wanted);
    }

    // ---------- Chaque minute : rappels, quêtes périmées ----------

    private void minute() {
        int minutes = service.settings().reminderMinutes();
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerState state = service.state(player.getUniqueId());
            if (state == null) {
                continue;
            }
            refreshSidebar(player);
            if (state.quiet || minutes <= 0 || now - state.lastReminder < minutes * 60_000L) {
                continue;
            }
            long ready = state.quests.values().stream().filter(instance -> {
                Quest quest = service.definitions().getOrNull(instance.quest());
                return quest != null && !quest.stage(instance.stage()).auto() && service.ready(player, instance);
            }).count();
            if (ready > 0) {
                state.lastReminder = now;
                messages.send(player, "quest.ready-reminder", "count", String.valueOf(ready));
            }
        }
    }

    // ---------- Suivi ----------

    /** Suivre cette quête (ou arrêter) ; owner null : le résumé de toutes. */
    public void toggleTrack(Player player, String owner, String slot) {
        PlayerState state = service.state(player.getUniqueId());
        if (state == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (owner == null) {
            boolean summary = !state.summary;
            state.quests.replaceAll((key, quest) -> quest.withTracked(false));
            service.setPrefs(player, state.quiet, summary);
            Tasks.async(plugin, () -> service.store().track(uuid, null, null), "Quête suivie non enregistrée");
            messages.send(player, summary ? "track.summary-on" : "track.off");
        } else {
            String key = owner + "/" + slot;
            boolean track = state.quests.get(key) != null && !state.quests.get(key).tracked();
            state.quests.replaceAll((k, quest) -> quest.withTracked(track && k.equals(key)));
            if (state.summary) {
                service.setPrefs(player, state.quiet, false);
            }
            Tasks.async(plugin, () -> service.store().track(uuid, track ? owner : null, track ? slot : null), "Quête suivie non enregistrée");
            messages.send(player, track ? "track.on" : "track.off");
        }
        refreshSidebar(player);
    }

    public void toggleQuiet(Player player) {
        PlayerState state = service.state(player.getUniqueId());
        boolean quiet = state == null || !state.quiet;
        service.setPrefs(player, quiet, state != null && state.summary);
        messages.send(player, quiet ? "quest.quiet-on" : "quest.quiet-off");
    }
}
