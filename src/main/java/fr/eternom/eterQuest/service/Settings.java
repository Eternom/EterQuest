package fr.eternom.eterQuest.service;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Les réglages d'EterQuest (config.yml).
 *
 * @param bossBar             progression en barre de boss (sinon au-dessus de la barre d'objets)
 * @param autoTrack           une quête est suivie dans la sidebar dès qu'elle avance (si aucune ne l'est)
 * @param reminderMinutes     rappel « quête prête à rendre », au plus toutes les N minutes (0 : jamais)
 * @param minPlaytimeMinutes  pas de progrès avant ce temps de jeu actif (EterSuccess ; 0 : aucun)
 * @param trialSpawners       les créatures des spawners de trial chamber comptent
 * @param fishOnlyFish        pêcher « n'importe quoi » : seuls les poissons comptent
 * @param breakAlertPerMinute blocs cassés par minute au-delà desquels le staff est prévenu (0 : jamais)
 */
public record Settings(boolean bossBar, boolean autoTrack, int reminderMinutes, int minPlaytimeMinutes, boolean trialSpawners,
                       boolean fishOnlyFish, int breakAlertPerMinute) {

    public static Settings load(FileConfiguration config) {
        return new Settings(config.getBoolean("display.boss-bar", true), config.getBoolean("display.auto-track", true),
                Math.max(0, config.getInt("display.ready-reminder-minutes", 60)),
                Math.max(0, config.getInt("anti-cheat.min-playtime-minutes", 30)),
                config.getBoolean("anti-cheat.count-trial-spawners", false), config.getBoolean("anti-cheat.fish-only-fish", true),
                Math.max(0, config.getInt("anti-cheat.break-alert-per-minute", 300)));
    }
}
