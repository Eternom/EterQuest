package fr.eternom.eterQuest.api;

/** Ce que donne une tentative de rendre l'étape en cours d'une quête (QuestApi#turnIn). */
public enum TurnInResult {
    /** La quête est finie (récompenses données ; le propriétaire paie les siennes par QuestCompleteEvent). */
    DONE,
    /** L'étape est rendue, la suivante commence. */
    ADVANCED,
    /** Cette étape ne se rend pas ici (autre PNJ, ou pas à distance). */
    NOT_HERE,
    /** À distance, mais il y a des objets à remettre : chez le PNJ. */
    REMOTE_ITEMS,
    /** Les objectifs ne sont pas encore remplis. */
    NOT_READY,
    /** Il manque des objets à remettre. */
    MISSING_ITEMS,
    /** La quête n'est plus valable (jour passé...). */
    EXPIRED,
    /** Déjà finie, changée ou retirée (ailleurs, entre-temps). */
    ALREADY,
    /** Une remise est déjà en cours (double clic). */
    BUSY,
    /** Erreur (base indisponible...) : les objets ont été rendus. */
    ERROR;

    public boolean success() {
        return this == DONE || this == ADVANCED;
    }
}
