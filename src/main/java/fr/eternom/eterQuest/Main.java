package fr.eternom.eterQuest;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.quest.QuestBridge;
import fr.eternom.eterQuest.api.QuestApi;
import fr.eternom.eterQuest.command.AdminCommand;
import fr.eternom.eterQuest.command.QuestActions;
import fr.eternom.eterQuest.command.QuestsCommand;
import fr.eternom.eterQuest.menu.Journal;
import fr.eternom.eterQuest.service.Definitions;
import fr.eternom.eterQuest.service.Display;
import fr.eternom.eterQuest.service.NpcHook;
import fr.eternom.eterQuest.service.ProgressListener;
import fr.eternom.eterQuest.service.QuestApiService;
import fr.eternom.eterQuest.service.QuestService;
import fr.eternom.eterQuest.service.Settings;
import fr.eternom.eterQuest.service.Texts;
import fr.eternom.eterQuest.store.QuestStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Objects;

/**
 * Le moteur de quêtes du réseau. Les quêtes sont écrites en YAML par les plugins qui les donnent (EterJob :
 * quests/<métier>.yml ; EterQuest lui-même : quests/), partagées par la base avec tous les serveurs ; les autres plugins
 * font avancer les objectifs par des signaux (QuestBridge d'EterLib) ou par les actions du jeu que compte EterQuest.
 * Journal : /quests. Staff : /eterquest.
 */
public final class Main extends JavaPlugin {

    private static final String REQUIRED_ETERLIB = "1.25.0";

    private QuestService service;
    private QuestApiService api;
    private Definitions definitions;
    private NetworkBus bus;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            if (!EterLib.requireVersion(this, REQUIRED_ETERLIB)) {
                return;
            }
        } catch (LinkageError tooOld) {
            getLogger().severe("EterLib " + REQUIRED_ETERLIB + " ou plus récent est nécessaire.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        EterLib lib = EterLib.get();
        Messages messages = lib.messages(this, "en_us", "fr_fr");
        QuestStore store = new QuestStore(lib.database("eterquest_"));
        definitions = new Definitions(this, store);
        definitions.reload();
        Texts texts = new Texts(messages);
        service = new QuestService(this, store, definitions, texts, Settings.load(getConfig()));
        Display display = new Display(service);
        api = new QuestApiService(service, display);
        Journal journal = new Journal(service, api, getConfig().getString("menus.back-command", ""));
        api.journal(journal);

        bus = lib.network(this, "eterquest", messages);
        definitions.start(bus, () -> getServer().getScheduler().runTask(this, () -> getServer().getOnlinePlayers().forEach(service::refresh)));
        bus.on("reload", data -> reloadLocal());

        getServer().getServicesManager().register(QuestApi.class, api, this, ServicePriority.Normal);
        QuestBridge.register(api);
        NpcHook npcs = getServer().getPluginManager().isPluginEnabled("EterNpc") ? NpcHook.create() : null;
        getServer().getPluginManager().registerEvents(new ProgressListener(service, display), this);
        QuestActions.register(this, service, api);
        register("quests", new QuestsCommand(api, messages));
        register("eterquest", new AdminCommand(service, () -> {
            reloadLocal();
            bus.publish("reload", new com.google.gson.JsonObject());
        }));
        service.start(display, npcs);
        display.start();

        // Les quêtes propres à EterQuest (quests/ : copié s'il manque)
        File folder = new File(getDataFolder(), "quests");
        if (!folder.exists() && getResource("quests/exemple.yml") != null) {
            saveResource("quests/exemple.yml", false);
        }
        Tasks.async(this, () -> api.register(this, folder), "Quêtes d'EterQuest non lues");
    }

    @Override
    public void onDisable() {
        if (api != null) {
            QuestBridge.unregister(api);
        }
        if (service != null) {
            service.stop();
        }
    }

    /** /eterquest reload (ici, ou reçu d'un autre serveur) : réglages, et les quêtes relues en base. */
    private void reloadLocal() {
        reloadConfig();
        service.reload(Settings.load(getConfig()));
        Tasks.async(this, () -> {
            api.register(this, new File(getDataFolder(), "quests"));
            definitions.reload();
            getServer().getScheduler().runTask(this, () -> getServer().getOnlinePlayers().forEach(service::refresh));
        }, "Quêtes non relues");
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand command = Objects.requireNonNull(getCommand(name), "Commande absente du plugin.yml : " + name);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
