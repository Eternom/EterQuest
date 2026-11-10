package fr.eternom.eterQuest.model;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestParserTest {

    private static final String FILE = """
            version: 3
            defaults:
              category: job
              tags: [fermier]
              turn-in: [giver, remote]
              meta: {job: fermier}
            quests:
              fermier-court:
                tags: [daily]
                meta: {kind: daily, reward: 120}
                objectives: ["HARVEST:WHEAT:64", "TALK:eternpc:fermier:1"]
              fermier-long:
                reputation: 2
                requires: ["reputation fermier 10"]
                stages:
                  - id: rencontre
                    objectives: [{type: talk, npc: fermier}]
                    say: ["Bonjour !"]
                  - id: recolte
                    objectives:
                      - {type: signal, key: eterhub:harvest, match: {crop: wheat, quality: [fine, golden]}, amount: 16}
                    branches:
                      - {when: ["done fermier-court"], to: bonus}
                    next: fin
                  - id: bonus
                    objectives: [{type: signal, key: eterhub:fish, amount: 2, where: {time: night, weather: rain}}]
                  - id: fin
                    objectives: [{type: talk, npc: fermier}]
                    turn-in: [npc:fermier]
            """;

    private static List<Quest> parse(List<String> warnings) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(FILE);
        return QuestParser.parseFile(yaml, "EterJob", "test.yml", warnings::add);
    }

    @Test
    void shortFormIsOneStageWithDefaults() throws Exception {
        List<String> warnings = new ArrayList<>();
        Quest quest = parse(warnings).stream().filter(q -> q.id().equals("fermier-court")).findFirst().orElseThrow();
        assertEquals("eterjob", quest.owner());
        assertEquals("job", quest.category());
        assertEquals(List.of("fermier", "daily"), quest.tags());
        assertEquals(Map.of("job", "fermier", "kind", "daily", "reward", "120"), quest.meta());
        assertEquals(3, quest.version());
        assertEquals(1, quest.stages().size());
        Stage stage = quest.stage(0);
        assertEquals(List.of("giver", "remote"), stage.turnIn());
        assertEquals(2, stage.objectives().size());
        assertEquals("eternpc:fermier", stage.objectives().get(1).target());
    }

    @Test
    void longFormKeepsStagesBranchesAndTurnIn() throws Exception {
        List<String> warnings = new ArrayList<>();
        Quest quest = parse(warnings).stream().filter(q -> q.id().equals("fermier-long")).findFirst().orElseThrow();
        assertEquals(4, quest.stages().size());
        assertTrue(quest.stage(0).auto(), "une étape du milieu se rend toute seule");
        assertEquals(List.of("npc:eternpc:fermier"), quest.stage(3).turnIn());
        assertEquals(List.of("eternpc:fermier"), quest.stage(3).turnInNpcs());
        assertEquals("bonus", quest.stage(1).branches().getFirst().to());
        assertEquals(3, quest.plainNext(1), "next: fin");
        assertEquals(-1, quest.plainNext(3));
        assertEquals(2.0, quest.reputation());
        Objective night = quest.stage(2).objectives().getFirst();
        assertEquals("night", night.where().time());
        assertEquals("rain", night.where().weather());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void bodyParsesBackToTheSameQuest() throws Exception {
        Quest quest = parse(new ArrayList<>()).getFirst();
        Quest again = QuestParser.parseBody(quest.id(), quest.owner(), quest.version(), quest.body(), warning -> { }).orElseThrow();
        assertEquals(quest.stages(), again.stages());
        assertEquals(quest.meta(), again.meta());
        assertEquals(quest.hash(), again.hash());
    }

    @Test
    void hashChangesWithContent() throws Exception {
        Quest quest = parse(new ArrayList<>()).getFirst();
        assertNotEquals(quest.hash(), QuestParser.hash(quest.body() + "x"));
    }

    @Test
    void signalMatching() {
        Objective objective = new Objective(Objective.Kind.SIGNAL, "", 4, true, "eterhub:harvest",
                Map.of("crop", List.of("wheat"), "quality", List.of("fine", "golden")), Where.ANYWHERE, "", false);
        assertTrue(objective.matches(Map.of("crop", "wheat", "quality", "golden"), false));
        assertFalse(objective.matches(Map.of("crop", "wheat", "quality", "normal"), false));
        assertFalse(objective.matches(Map.of("crop", "wheat"), false), "qualité absente : pas une prise");
        assertTrue(objective.matches(Map.of("crop", "wheat"), true), "wanted : la qualité n'est pas encore tirée");
        assertFalse(objective.matches(Map.of("crop", "carrot"), true));
        Objective heavy = new Objective(Objective.Kind.SIGNAL, "", 1, true, "eterhub:fish", Map.of("grams", List.of(">=3000")),
                Where.ANYWHERE, "", false);
        assertTrue(heavy.matches(Map.of("grams", "4200"), false));
        assertFalse(heavy.matches(Map.of("grams", "800"), false));
    }

    @Test
    void scaleAndShortForm() {
        Objective objective = Objective.parse("HARVEST:WHEAT:64").orElseThrow();
        assertEquals(64, objective.amount(1));
        assertEquals(77, objective.amount(1.2));
        Objective talk = Objective.parse("TALK:fermier:1").orElseThrow();
        assertEquals("eternpc:fermier", talk.target());
        assertEquals(1, talk.amount(1.5), "parler : jamais agrandi");
        assertEquals(Objective.Kind.DELIVER, Objective.parse("ITEM:BREAD:16").orElseThrow().kind(), "ancien nom d'EterJob");
        assertTrue(Objective.parse("HARVEST:WHEAT").isEmpty());
    }
}
