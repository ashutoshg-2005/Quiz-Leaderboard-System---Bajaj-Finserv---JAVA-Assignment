import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class QuizLeaderboardApp {
    private static final String BASE_URL = "https://devapigw.vidalhealthtpa.com/srm-quiz-task";
    private static final int TOTAL_POLLS = 10;
    private static final int WAIT_SECONDS = 5;

    private static final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Please pass your registration number in the args.");
            return;
        }

        String regNo = args[0].trim();
        boolean dryRun = args.length > 1 && "--dry-run".equalsIgnoreCase(args[1]);

        Map<String, Integer> participantScores = new HashMap<>();
        Set<String> alreadyCounted = new HashSet<>();
        int duplicateCount = 0;

        System.out.println("Starting quiz polling for regNo: " + regNo);

        for (int poll = 0; poll < TOTAL_POLLS; poll++) {
            String response = getMessages(regNo, poll);
            List<QuizEvent> events = parseEvents(response);

            System.out.println("Poll " + poll + " received " + events.size() + " events");

            for (QuizEvent event : events) {
                String uniqueKey = event.roundId + "|" + event.participant;

                if (alreadyCounted.contains(uniqueKey)) {
                    duplicateCount++;
                    continue;
                }

                alreadyCounted.add(uniqueKey);
                int oldScore = participantScores.getOrDefault(event.participant, 0);
                participantScores.put(event.participant, oldScore + event.score);
            }

            if (poll < TOTAL_POLLS - 1) {
                Thread.sleep(WAIT_SECONDS * 1000L);
            }
        }

        List<LeaderboardRow> leaderboard = makeLeaderboard(participantScores);
        int totalScore = 0;

        System.out.println();
        System.out.println("Final Leaderboard");
        for (LeaderboardRow row : leaderboard) {
            totalScore += row.totalScore;
            System.out.println(row.participant + " - " + row.totalScore);
        }

        System.out.println();
        System.out.println("Unique records counted: " + alreadyCounted.size());
        System.out.println("Duplicates skipped: " + duplicateCount);
        System.out.println("Total score: " + totalScore);

        String payload = buildSubmitPayload(regNo, leaderboard);

        if (dryRun) {
            System.out.println();
            System.out.println("Dry run enabled, so submit was skipped.");
            System.out.println(payload);
            return;
        }

        String submitResponse = submitLeaderboard(payload);

        System.out.println();
        System.out.println("Submit response:");
        System.out.println(submitResponse);
    }

    // Function to get messages for a given registration number and poll number
    private static String getMessages(String regNo, int poll) throws IOException, InterruptedException {
        String url = BASE_URL + "/quiz/messages?regNo=" + encode(regNo) + "&poll=" + poll;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Poll " + poll + " failed. Status: " + response.statusCode()
                    + ", Body: " + response.body());
        }

        return response.body();
    }

    // Function to submit the leaderboard payload to the server
    private static String submitLeaderboard(String payload) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/quiz/submit"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Submit failed. Status: " + response.statusCode()
                    + ", Body: " + response.body());
        }

        return response.body();
    }

    // Function to create a sorted leaderboard from the participant scores
    private static List<LeaderboardRow> makeLeaderboard(Map<String, Integer> participantScores) {
        List<LeaderboardRow> leaderboard = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : participantScores.entrySet()) {
            leaderboard.add(new LeaderboardRow(entry.getKey(), entry.getValue()));
        }

        leaderboard.sort((first, second) -> {
            int scoreCompare = Integer.compare(second.totalScore, first.totalScore);
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            return first.participant.compareToIgnoreCase(second.participant);
        });

        return leaderboard;
    }

    // Function to parse the JSON response and extract quiz events
    private static List<QuizEvent> parseEvents(String json) {
        List<QuizEvent> events = new ArrayList<>();

        int eventsIndex = json.indexOf("\"events\"");
        if (eventsIndex == -1) {
            return events;
        }

        int arrayStart = json.indexOf("[", eventsIndex);
        int arrayEnd = json.indexOf("]", arrayStart);
        if (arrayStart == -1 || arrayEnd == -1) {
            return events;
        }

        String eventsText = json.substring(arrayStart + 1, arrayEnd).trim();
        if (eventsText.isEmpty()) {
            return events;
        }

        String[] eventObjects = eventsText.split("\\}\\s*,\\s*\\{");

        for (String eventText : eventObjects) {
            eventText = eventText.replace("{", "").replace("}", "");

            String roundId = getTextValue(eventText, "roundId");
            String participant = getTextValue(eventText, "participant");
            int score = getNumberValue(eventText, "score");

            events.add(new QuizEvent(roundId, participant, score));
        }

        return events;
    }

    // Helper function to extract text values from JSON based on a key
    private static String getTextValue(String json, String key) {
        String searchText = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchText);
        if (keyIndex == -1) {
            return "";
        }

        int firstQuote = json.indexOf("\"", keyIndex + searchText.length());
        int secondQuote = json.indexOf("\"", firstQuote + 1);
        if (firstQuote == -1 || secondQuote == -1) {
            return "";
        }

        return json.substring(firstQuote + 1, secondQuote);
    }

    // Helper function to extract numeric values from JSON based on a key
    private static int getNumberValue(String json, String key) {
        String searchText = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchText);
        int colonIndex = json.indexOf(":", keyIndex);
        int commaIndex = json.indexOf(",", colonIndex);

        if (commaIndex == -1) {
            commaIndex = json.length();
        }

        String numberText = json.substring(colonIndex + 1, commaIndex).trim();
        return Integer.parseInt(numberText);
    }

    // Function to build the JSON payload for submitting the leaderboard
    private static String buildSubmitPayload(String regNo, List<LeaderboardRow> leaderboard) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"regNo\":\"").append(cleanJsonText(regNo)).append("\",");
        json.append("\"leaderboard\":[");

        for (int i = 0; i < leaderboard.size(); i++) {
            LeaderboardRow row = leaderboard.get(i);
            if (i > 0) {
                json.append(",");
            }

            json.append("{");
            json.append("\"participant\":\"").append(cleanJsonText(row.participant)).append("\",");
            json.append("\"totalScore\":").append(row.totalScore);
            json.append("}");
        }

        json.append("]");
        json.append("}");
        return json.toString();
    }

    // Function to URL-encode values for safe transmission in HTTP requests
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // Function to clean text values for safe inclusion in JSON strings
    private static String cleanJsonText(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // Class representing a quiz event with round ID, participant name, and score
    static class QuizEvent {
        String roundId;
        String participant;
        int score;

        QuizEvent(String roundId, String participant, int score) {
            this.roundId = roundId;
            this.participant = participant;
            this.score = score;
        }
    }

    // Class representing a row in the leaderboard with participant name and total
    // score
    static class LeaderboardRow {
        String participant;
        int totalScore;

        LeaderboardRow(String participant, int totalScore) {
            this.participant = participant;
            this.totalScore = totalScore;
        }
    }
}
