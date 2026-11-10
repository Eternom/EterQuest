# EterQuest

Le **moteur de quêtes** du réseau Akia (Paper 26.2, Java 25), installé sur **tous les serveurs**. Document développeur,
à tenir à jour avec le code.

Un plugin ne fait pas ses quêtes lui-même : il les **écrit en YAML**, les **enregistre** ici, les **donne** aux joueurs,
les fait **rendre**, et paie ses récompenses propres à la fin. Les autres plugins font avancer les objectifs par des
**signaux** (QuestBridge d'EterLib), sans dépendre d'EterQuest.

- **EterLib 1.25.0+** (`depend`) : base, Redis, bus réseau, langues, menus, actions, réputation, QuestBridge.
- Facultatifs (`softdepend`) : **EterNpc** (marqueurs « ? » et « ! » au-dessus des PNJ, bulles), **EterClan** (pas de
  progrès sur le terrain d'un autre clan), EterSuccess (temps de jeu, anti-triche).

## Principe

| Qui | Quoi |
|---|---|
| Le plugin **propriétaire** (EterJob…) | écrit ses quêtes (`plugins/<Plugin>/quests/*.yml`), `register`, `assign`, `turnIn`, paie à `QuestCompleteEvent` après `claim` |
| **EterQuest** | partage les quêtes par la base, compte les objectifs, gère étapes, branches, remises, fins, journal, sidebar |
| Les **autres plugins** (EterHub, EterNpc…) | `QuestBridge.signal` (ce que fait le joueur), `wanted` (une quête en veut-elle encore ?), `interact` (clic sur un PNJ) |

## Définitions partagées (`Definitions`, `QuestStore`)

- Chaque fichier a une `version`. À l'envoi : quête nouvelle → ajoutée ; **version plus haute → remplace** ; même
  version mais contenu différent → **rien n'est écrasé**, un avertissement demande d'augmenter `version`.
- Tous les serveurs relisent la base (même ceux qui n'ont pas le propriétaire : journal, signaux) ; un envoi prévient
  les autres serveurs (bus `eterquest`).
- Identifiant : minuscules, chiffres, `-` et `_` (pas de point). Définitif : la progression s'y attache.

## Format d'une quête (`QuestParser`)

Voir `src/main/resources/quests/exemple.yml` (commenté) et les fichiers d'EterJob. En bref :

- `defaults` du fichier : repris par chaque quête (`tags`, `meta`, `requires` ajoutés ; le reste si absent).
- `name`, `description`, `icon`, `category`, `tags`, `requires`, `reputation`, `rewards`, `abandon`, `meta` (pour le
  propriétaire).
- `objectives` (une étape) ou `stages` : `id`, `text`, `objectives`, `turn-in`, `on-start`, `on-complete`, `say`,
  `branches` (`when` → `to`), `next`, `end`, `time-limit` (secondes ; dépassé, l'étape recommence).
- Objectif : `"TYPE:CIBLE:QUANTITÉ"` ou `{type, target, amount, scale, key, match, where, text, optional}`.
  - Actions du jeu (`ProgressListener`, anti-triche repris d'EterJob 2.x) : `break harvest kill fish craft smelt breed
    explore enchant trade` ; cibles : matière, créature, biome, `ANY`, groupes `#ores #logs #crops #fish #stone #flowers`.
  - `deliver` (objets simples pris au moment de rendre), `talk` (PNJ), `signal` (`key` + `match` : valeurs, listes,
    `>=n`, `<=n`, `*`).
  - `where` : `biomes`, `dimension`, `min-y`, `max-y`, `time` (heure **vue par le joueur**), `weather`, `servers`.
- `turn-in` : `auto`, `giver` (le menu du propriétaire), `remote` (à distance), `npc:<pnj>`. Par défaut : la dernière
  étape (et celles avec des objets) prend le `turn-in` de la quête, les autres sont `auto`.
- Conditions (`Requirements`) : `reputation <pnj|global> <min>`, `done <quête>`, `!done <quête>`, `permission <perm>`,
  `?<condition d'EterLib>` (thread principal seulement).

## Joueurs (`QuestService`, `Display`)

- Une quête donnée = une **instance** (joueur, propriétaire, emplacement) avec un **token** : une quête redonnée dans le
  même emplacement ne reçoit jamais les progrès de l'ancienne.
- Progrès en mémoire, écrits **en s'ajoutant** (`eterquest_counters`, toutes les 30 s, au départ, avant chaque étape ou
  fin) : deux serveurs ne s'écrasent jamais. Changer d'étape, finir, payer : une requête qui vérifie ses conditions.
- Fin : la base fait foi (compteurs relus), objets pris rendus si refusée (sinon boîte de récupération), `rewards`,
  `reputation` (EterLib), `QuestCompleteEvent`. Le propriétaire paie après `claim` (une seule fois) ; une quête finie
  sur un serveur sans lui est payée à l'arrivée suivante (`unclaimed`).
- Affichage : barre de boss pendant une action, sidebar (quête suivie ou résumé), « ? » / « ! » au-dessus des PNJ,
  rappels, temps restant d'une étape chronométrée.
- `wanted` : correspondance souple (une donnée absente ne gêne pas) — EterHub s'en sert pour la récolte de quête.

## Commandes et actions

| Commande | Rôle | Permission |
|---|---|---|
| `/quests` (`quest`, `journal`) `[box\|sidebar\|messages]` | Journal, boîte de récupération, résumé, messages | `eterquest.use` |
| `/eterquest list [propriétaire] · info <quête> · player <joueur> · give <joueur> <quête> · stage <joueur> <owner/slot> <étape> · complete · remove · reputation <joueur> [set\|add <points>] · debug <joueur> · stats [jours] · reload` | Staff | `eterquest.admin` |

Actions EterLib : `eterquest:journal`, `eterquest:start <quête>`, `eterquest:talk <pnj>`, `eterquest:signal <clé>
[k=v…]`, `eterquest:box` ; conditions `?eterquest:done <quête>`, `?eterquest:active <quête>`, `?eterquest:stage
<quête> <étape>`.

## API

`QuestApi.get()` — `compileOnly("com.github.Eternom:EterQuest:<tag>")` : `register`, `quest`, `namer`, `assign`,
`replaceIfActive`, `instances`, `remove`, `clear`, `claim`, `unclaimed`, `canStart`, `completions`, `deposit`,
`boxCount` (bloquants) ; `live`, `refresh`, `ready`, `turnIn`, `name`, `lore`, `icon`, `toggleTrack`, `toggleQuiet`,
`openJournal`, `takeBox` (thread principal). Événements : `QuestCompleteEvent`, `QuestStageEvent`.

## Tables

`eterquest_definitions`, `instances`, `counters`, `completions`, `box`, `players`.

## Technique

- Tests : `QuestParserTest` (`gradlew test`).
- Compilation : `gradlew build` → `build/libs/EterQuest-<version>.jar`. Release : tag = version de `gradle.properties`.
