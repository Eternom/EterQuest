package fr.eternom.eterQuest.menu;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterQuest.api.QuestInstance;
import fr.eternom.eterQuest.api.TurnInResult;
import fr.eternom.eterQuest.model.Quest;
import fr.eternom.eterQuest.service.QuestApiService;
import fr.eternom.eterQuest.service.QuestService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Le journal des quêtes (/quests), 6 lignes :
 * <pre>
 *  ▣ ▣ ▣ ▣ ☺ ▣ ▣ ▣ ▣     ☺ = joueur : réputation, quêtes en cours, prêtes, faites
 *  ▣ q q q q q q q ▣
 *  ▣ q q q q q q q ▣     les quêtes en cours (toutes : métier, village...), puis celles finies depuis peu
 *  ▣ q q q q q q q ▣
 *  ▣ q q q q q q q ▣
 *  ◀ ▣ ✉ ▤ « ♪ ▣ ▣ ▶     page · boîte · sidebar (résumé) · retour · messages · page
 * </pre>
 * Sur une quête : clic = la suivre dans la sidebar ; clic droit = la rendre à distance (si elle le permet) ;
 * Maj + clic droit = l'abandonner (si elle le permet).
 */
public class Journal {

    private static final List<Integer> SLOTS = List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43);
    private static final int INFO = 4;
    private static final int PREVIOUS = 45;
    private static final int BOX = 47;
    private static final int SUMMARY = 48;
    private static final int BACK = 49;
    private static final int QUIET = 50;
    private static final int NEXT = 53;
    private static final long RECENT = 24 * 3_600_000L;

    private final QuestService service;
    private final QuestApiService api;
    private final Messages messages;
    private final BackButton back;

    public Journal(QuestService service, QuestApiService api, String backCommand) {
        this.service = service;
        this.api = api;
        this.messages = service.messages();
        this.back = EterLib.get().backButton(backCommand);
    }

    public void open(Player player, int page) {
        UUID uuid = player.getUniqueId();
        Tasks.async(service.plugin(), player, () -> new int[]{service.store().boxCount(uuid)},
                counts -> player.openInventory(new View(player, page, counts[0]).getInventory()), () -> messages.send(player, "error.generic"));
    }

    private final class View implements Menu {

        private final Player viewer;
        private final int page;
        private final int boxCount;
        private final Inventory inventory;
        private final Map<Integer, QuestInstance> at = new HashMap<>();
        private int pages = 1;

        View(Player viewer, int page, int boxCount) {
            this.viewer = viewer;
            this.page = page;
            this.boxCount = boxCount;
            this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "journal.title"));
            render();
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private List<QuestInstance> shown() {
            long now = System.currentTimeMillis();
            List<QuestInstance> list = new ArrayList<>(service.quests(viewer.getUniqueId(), null).stream()
                    .filter(q -> service.definitions().getOrNull(q.quest()) != null)
                    .filter(q -> q.live(now) || q.done() && now - q.completedAt() < RECENT)
                    .toList());
            list.sort(Comparator.comparing((QuestInstance q) -> !q.live(now)).thenComparing(q -> !q.tracked())
                    .thenComparing(QuestInstance::owner).thenComparing(QuestInstance::slot));
            return list;
        }

        private void render() {
            Frame.draw(inventory, Material.PURPLE_STAINED_GLASS_PANE);
            List<QuestInstance> quests = shown();
            pages = Math.max(1, (quests.size() + SLOTS.size() - 1) / SLOTS.size());
            int start = Math.min(page, pages - 1) * SLOTS.size();
            for (int i = 0; i < SLOTS.size() && start + i < quests.size(); i++) {
                QuestInstance quest = quests.get(start + i);
                at.put(SLOTS.get(i), quest);
                inventory.setItem(SLOTS.get(i), item(quest));
            }
            if (quests.isEmpty()) {
                inventory.setItem(22, Items.item(Material.BOOK, messages.get(viewer, "journal.empty"), List.of(messages.get(viewer, "journal.empty-lore"))));
            }
            inventory.setItem(INFO, info(quests));
            if (page > 0) {
                inventory.setItem(PREVIOUS, Items.item(Material.ARROW, messages.get(viewer, "journal.previous"), List.of(messages.get(viewer, "menu.page-of", "page", String.valueOf(page + 1), "pages", String.valueOf(pages)))));
            }
            if (page < pages - 1) {
                inventory.setItem(NEXT, Items.item(Material.ARROW, messages.get(viewer, "journal.next"), List.of(messages.get(viewer, "menu.page-of", "page", String.valueOf(page + 1), "pages", String.valueOf(pages)))));
            }
            inventory.setItem(BOX, Items.item(boxCount > 0 ? Material.CHEST_MINECART : Material.MINECART, messages.get(viewer, "journal.box"),
                    List.of(messages.get(viewer, "journal.box-lore", "count", String.valueOf(boxCount))), boxCount > 0));
            inventory.setItem(SUMMARY, Items.item(Material.MAP, messages.get(viewer, "journal.summary"),
                    List.of(messages.get(viewer, service.isSummary(viewer.getUniqueId()) ? "journal.summary-on" : "journal.summary-off"))));
            inventory.setItem(BACK, back.item(viewer));
            boolean quiet = service.isQuiet(viewer.getUniqueId());
            inventory.setItem(QUIET, Items.item(quiet ? Material.GRAY_DYE : Material.BELL, messages.get(viewer, "journal.quiet"),
                    List.of(messages.get(viewer, quiet ? "journal.quiet-on" : "journal.quiet-off"))));
        }

        private ItemStack info(List<QuestInstance> quests) {
            long now = System.currentTimeMillis();
            long live = quests.stream().filter(q -> q.live(now)).count();
            long ready = quests.stream().filter(q -> service.ready(viewer, q)).count();
            double reputation = EterLib.get().getReputation().cached(viewer.getUniqueId());
            List<Component> lore = new ArrayList<>();
            lore.add(messages.get(viewer, "journal.reputation", "points", String.valueOf(Math.round(reputation))));
            lore.add(messages.get(viewer, "journal.count", "live", String.valueOf(live), "ready", String.valueOf(ready)));
            lore.add(messages.get(viewer, "journal.completed", "count", String.valueOf(service.completed(viewer.getUniqueId()).size())));
            return Items.head(viewer.getPlayerProfile(), messages.get(viewer, "journal.player", "player", viewer.getName()), lore);
        }

        private ItemStack item(QuestInstance instance) {
            Quest quest = service.definitions().getOrNull(instance.quest());
            long now = System.currentTimeMillis();
            List<Component> lore = new ArrayList<>();
            quest.description().forEach(line -> lore.add(service.texts().render(line)));
            if (!quest.description().isEmpty()) {
                lore.add(Component.empty());
            }
            lore.addAll(api.lore(viewer, instance));
            lore.add(Component.empty());
            boolean ready = service.ready(viewer, instance);
            if (instance.done()) {
                lore.add(messages.get(viewer, "journal.done"));
            } else if (instance.expired(now)) {
                lore.add(messages.get(viewer, "journal.expired"));
            } else {
                if (instance.expiresAt() > 0) {
                    lore.add(messages.get(viewer, "journal.expires", "time", EterLib.get().formatDuration(viewer,
                            Math.max(0, instance.expiresAt() - now) / 1000)));
                }
                if (ready) {
                    boolean remote = quest.stage(instance.stage()).accepts("remote");
                    lore.add(messages.get(viewer, remote ? "journal.ready-remote" : "journal.ready"));
                }
                lore.add(messages.get(viewer, instance.tracked() ? "journal.untrack" : "journal.track"));
                if (quest.abandon() && instance.owner().equals("eterquest")) {
                    lore.add(messages.get(viewer, "journal.abandon"));
                }
            }
            Material icon = instance.done() ? Material.LIME_DYE : instance.expired(now) ? Material.GRAY_DYE : api.icon(instance);
            Component name = messages.get(viewer, "journal.quest-name", TagResolver.resolver(Placeholder.component("quest", api.name(viewer, instance)),
                    Placeholder.unparsed("category", messages.plain(viewer, "category." + quest.category()))));
            return Items.item(icon, name, lore, ready || instance.tracked());
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            QuestInstance instance = at.get(slot);
            if (instance != null) {
                clickQuest(player, instance, click);
                return;
            }
            switch (slot) {
                case PREVIOUS -> {
                    if (page > 0) {
                        Sounds.page(player);
                        open(player, page - 1);
                    }
                }
                case NEXT -> {
                    if (page < pages - 1) {
                        Sounds.page(player);
                        open(player, page + 1);
                    }
                }
                case BOX -> {
                    Sounds.click(player);
                    api.takeBox(player, () -> open(player, page));
                }
                case SUMMARY -> {
                    Sounds.click(player);
                    api.toggleTrack(player, null, null);
                    open(player, page);
                }
                case BACK -> back.click(player);
                case QUIET -> {
                    Sounds.click(player);
                    api.toggleQuiet(player);
                    open(player, page);
                }
                default -> {
                }
            }
        }

        private void clickQuest(Player player, QuestInstance instance, ClickType click) {
            long now = System.currentTimeMillis();
            if (!instance.live(now)) {
                Sounds.refuse(player);
                return;
            }
            if (click == ClickType.SHIFT_RIGHT) {
                if (!instance.owner().equals("eterquest")) {
                    Sounds.refuse(player);
                    messages.send(player, "quest.cannot-abandon");
                    return;
                }
                Sounds.click(player);
                service.abandon(player, instance, () -> open(player, page));
                return;
            }
            if (click.isRightClick()) {
                api.turnIn(player, instance.owner(), instance.slot(), "remote", result -> {
                    if (result.success()) {
                        Sounds.click(player);
                    } else {
                        Sounds.refuse(player);
                        messages.send(player, "turn-in." + result.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
                    }
                    if (result != TurnInResult.BUSY) {
                        open(player, page);
                    }
                });
                return;
            }
            Sounds.click(player);
            api.toggleTrack(player, instance.owner(), instance.slot());
            open(player, page);
        }
    }
}
