package com.example.plugins;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class ParseApiResponse {
    public static final String JIRA_BASE_URL = "http://localhost:2990/jira";
    public static final String PROJECT_KEY = "KOMOOTRE";
    public static final String USERNAME = "admin";
    public static final String PASSWORD = "admin";

    public static class Token {
        public int index;
        public String name;

        public Token(int index, String name) {
            this.index = index;
            this.name = name;
        }
    }

    public static class Code {
        public List<Integer> tokenIndexes;
        public String name;
        public String tore;
        public List<String> mappedWords;

        public Code(List<Integer> tokenIndexes, String name, String tore) {
            this.tokenIndexes = tokenIndexes;
            this.name = name;
            this.tore = tore;
            this.mappedWords = new ArrayList<>();
        }
    }

    public static class Review {
        public String name;
        public int beginIndex;
        public int endIndex;
        public List<Code> codes;

        public Review(String name, int beginIndex, int endIndex) {
            this.name = name;
            this.beginIndex = beginIndex;
            this.endIndex = endIndex;
            this.codes = new ArrayList<>();
        }
    }

    public static String fetchApiResponse(String apiUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        } finally {
            connection.disconnect();
        }
    }

    public static boolean setEntityProperty(String propertyKey, String jsonData) throws Exception {
        String endpoint = String.format("%s/rest/api/2/project/%s/properties/%s", JIRA_BASE_URL, PROJECT_KEY, propertyKey);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("PUT");
        connection.setRequestProperty("Authorization", "Basic " + getEncodedCredentials());
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = jsonData.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
            os.flush();
        }

        int responseCode = connection.getResponseCode();
        return responseCode >= 200 && responseCode < 300;
    }

    private static String getEncodedCredentials() {
        String credentials = USERNAME + ":" + PASSWORD;
        return Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    public static List<Review> parseAndMapResponse(String jsonResponse) {
        int annotationStartIndex = jsonResponse.indexOf("\"annotation\":\"") + 13;
        int annotationEndIndex = jsonResponse.indexOf("\",\"classifier\"");
        if (annotationStartIndex == -1 || annotationEndIndex == -1) {
            System.err.println("No 'annotation' field found or malformed.");
            return new ArrayList<>();
        }

        String annotationJson = jsonResponse.substring(annotationStartIndex, annotationEndIndex)
                .replace("'", "\"")
                .replace("ObjectId(", "")
                .replace(")", "");

        Map<Integer, Token> tokenMap = extractTokens(annotationJson);
        List<Review> reviews = extractReviews(annotationJson);
        List<Code> codes = extractCodes(annotationJson);

        for (Review review : reviews) {
            for (Code code : codes) {
                boolean belongsToReview = false;
                for (Integer tokenIndex : code.tokenIndexes) {
                    if (tokenIndex >= review.beginIndex && tokenIndex < review.endIndex) {
                        belongsToReview = true;
                        break;
                    }
                }
                if (belongsToReview) {
                    Code reviewCode = new Code(
                        code.tokenIndexes,
                        code.name,
                        code.tore
                    );
                    for (Integer tokenIndex : code.tokenIndexes) {
                        if (tokenIndex >= review.beginIndex && tokenIndex < review.endIndex) {
                            Token token = tokenMap.get(tokenIndex);
                            if (token != null) {
                                reviewCode.mappedWords.add(token.name);
                            }
                        }
                    }
                    review.codes.add(reviewCode);
                }
            }
        }
        return reviews;
    }

    private static Map<Integer, Token> extractTokens(String json) {
        Map<Integer, Token> tokenMap = new HashMap<>();
        Pattern tokenPattern = Pattern.compile("\\{\"index\":\\s*(\\d+),\\s*\"name\":\\s*\"([^\"]+)\"");
        Matcher tokenMatcher = tokenPattern.matcher(json);

        while (tokenMatcher.find()) {
            int index = Integer.parseInt(tokenMatcher.group(1));
            String name = tokenMatcher.group(2);
            tokenMap.put(index, new Token(index, name));
        }
        return tokenMap;
    }

    private static List<Review> extractReviews(String json) {
        List<Review> reviews = new ArrayList<>();
        Pattern reviewPattern = Pattern.compile("\\{\"name\":\\s*\"([^\"]+)\",\\s*\"begin_index\":\\s*(\\d+),\\s*\"end_index\":\\s*(\\d+)\\}");
        Matcher reviewMatcher = reviewPattern.matcher(json);

        while (reviewMatcher.find()) {
            String name = reviewMatcher.group(1);
            int beginIndex = Integer.parseInt(reviewMatcher.group(2));
            int endIndex = Integer.parseInt(reviewMatcher.group(3));
            reviews.add(new Review(name, beginIndex, endIndex));
        }
        return reviews;
    }

    private static List<Code> extractCodes(String json) {
        List<Code> codes = new ArrayList<>();
        Pattern codePattern = Pattern.compile("\\{\"tokens\":\\s*(\\[[^\\]]*\\]),\\s*\"name\":\\s*\"([^\"]*)\",\\s*\"tore\":\\s*\"([^\"]*)\"");
        Matcher codeMatcher = codePattern.matcher(json);

        while (codeMatcher.find()) {
            String tokensArrayStr = codeMatcher.group(1);
            String name = codeMatcher.group(2);
            String tore = codeMatcher.group(3);

            List<Integer> tokenIndexes = new ArrayList<>();
            Matcher numberMatcher = Pattern.compile("\\d+").matcher(tokensArrayStr);

            while (numberMatcher.find()) {
                tokenIndexes.add(Integer.parseInt(numberMatcher.group()));
            }
            codes.add(new Code(tokenIndexes, name, tore));
        }
        return codes;
    }

    public static String sanitizePropertyKey(String reviewName) {
        return reviewName.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
    }

    public static String createJsonPayloadForReview(Review review) {
        StringBuilder jsonPayload = new StringBuilder();
        jsonPayload.append("{");
        jsonPayload.append("\"begin_index\": ").append(review.beginIndex).append(",");
        jsonPayload.append("\"end_index\": ").append(review.endIndex).append(",");
        jsonPayload.append("\"codes\": [");

        for (int i = 0; i < review.codes.size(); i++) {
            Code code = review.codes.get(i);
            jsonPayload.append("{");
            jsonPayload.append("\"name\": \"").append(code.name).append("\",");
            jsonPayload.append("\"tore\": \"").append(code.tore).append("\",");
            jsonPayload.append("\"tokens\": ").append(arrayToJson(code.tokenIndexes)).append(",");
            jsonPayload.append("\"words\": ").append(arrayToJson(code.mappedWords));
            jsonPayload.append("}");

            if (i < review.codes.size() - 1) {
                jsonPayload.append(",");
            }
        }
        jsonPayload.append("]}");
        return jsonPayload.toString();
    }

    private static String arrayToJson(List<?> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof String) {
                sb.append("\"").append(list.get(i)).append("\"");
            } else {
                sb.append(list.get(i));
            }
            if (i < list.size() - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
