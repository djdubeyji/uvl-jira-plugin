package com.example.plugins.servlet;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.OutputStream;
import com.example.plugins.ParseApiResponse;
import java.nio.charset.StandardCharsets;

public class MyServlet extends HttpServlet {

    private static final String BASE_URL = "https://feed-uvl.ifi.uni-heidelberg.de/hitec/jira/uvldashboard/return_dashboard/";
    private String feedbackIdentifier = "Not set";
    private String usageIdentifier = "Not set";
    private static final String JIRA_BASE_URL = "http://localhost:2990/jira"; 
    private static final String ENTITY_PROPERTY_KEY_PREFIX = "customFeedbackData"; 
    private static final String USAGE_PROPERTY_KEY_PREFIX = "usageData"; 
    private static final String PROJECT_KEY = "KOMOOTRE"; 
    private static final String USERNAME = "admin"; 
    private static final String PASSWORD = "admin"; 
    private static final int MAX_PROPERTY_SIZE = 32000;
    private Map<String, HashSet<String>> issueKeyToReviewIds = new HashMap<>();
    private Map<String, String> reviewIdToText = new HashMap<>();
    private List<Code> usageCodes = new ArrayList<>();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/html;charset=utf-8");

        String templatePath = "/templates/dataset-management.html";
        InputStream templateStream = getClass().getResourceAsStream(templatePath);

        if (templateStream != null) {
            StringBuilder htmlContent = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(templateStream, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    htmlContent.append(line).append("\n");
                }
            }

            String feedbackUrl = feedbackIdentifier.equals("Not set") ? "Not set" : BASE_URL + feedbackIdentifier;
            String usageUrl = usageIdentifier.equals("Not set") ? "Not set" : BASE_URL + usageIdentifier;

            // Fetch and store API responses
            try {
                if (!feedbackIdentifier.equals("Not set")) {
                    String feedbackApiResponse = fetchApiResponse(feedbackUrl);
                    issueKeyToReviewIds = extractIssueKeyToReviewIds(feedbackApiResponse);
                    reviewIdToText = extractReviewIdToText(feedbackApiResponse);
                    storeFeedbackForAllIssuesWithChunking(issueKeyToReviewIds, reviewIdToText);
                }

                if (!usageIdentifier.equals("Not set")) {
                    String usageApiResponse = ParseApiResponse.fetchApiResponse(usageUrl);
                    List<ParseApiResponse.Review> usageReviews = ParseApiResponse.parseAndMapResponse(usageApiResponse);

                    for (ParseApiResponse.Review review : usageReviews) {
                        String propertyKey = "usage_review_" + ParseApiResponse.sanitizePropertyKey(review.name);
                        String jsonPayload = ParseApiResponse.createJsonPayloadForReview(review);
                        boolean isStored = ParseApiResponse.setEntityProperty(propertyKey, jsonPayload);
                        System.out.println("Property stored for usage review " + review.name + ": " + isStored);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        // Check if inputs are "Not set"
        boolean isError = "Not set".equals(feedbackIdentifier) || "Not set".equals(usageIdentifier);
        String errorMessage = isError ? "Incorrect API call: Please enter valid identifiers." : "";
        String errorMessageStyle = isError ? "" : "display: none;";

        // Prepare success message
        String successParam = req.getParameter("success");
        String successMessage = "";
        String successMessageStyle = "display: none;";

        if (!isError && "true".equals(successParam)) {
            successMessage = "Data stored successfully!";
            successMessageStyle = "";
        }


    
        String renderedHtml = htmlContent.toString()
                .replace("{{feedbackIdentifier}}", isError ? "" : feedbackIdentifier)
                .replace("{{usageIdentifier}}", isError ? "" : usageIdentifier)
                .replace("{{errorMessage}}", errorMessage)
                .replace("{{errorMessageStyle}}", errorMessageStyle)
                .replace("{{successMessage}}", successMessage)
                .replace("{{successMessageStyle}}", successMessageStyle);

            resp.getWriter().write(renderedHtml);
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Template not found.");
        }
    }

    
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        feedbackIdentifier = req.getParameter("feedbackInput");
        usageIdentifier = req.getParameter("usageInput");
        resp.sendRedirect(req.getRequestURI() + "?success=true");

    }

    private String fetchApiResponse(String apiUrl) throws Exception {
        System.out.println("Fetching API URL: " + apiUrl);
        HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");

        int responseCode = connection.getResponseCode();
        System.out.println("Response Code: " + responseCode);

        if (responseCode != 200) {
            throw new Exception("Failed to fetch data from API. HTTP Response Code: " + responseCode);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            System.out.println("API Response: " + response.toString());
            return response.toString();
        } finally {
            connection.disconnect();
        }
    }

    private Map<String, HashSet<String>> extractIssueKeyToReviewIds(String jsonResponse) {
        Map<String, HashSet<String>> issueKeyToReviewIds = new HashMap<>();
        int assignedFeedbackStart = jsonResponse.indexOf("\"assigned_feedback\":");
        if (assignedFeedbackStart == -1) return issueKeyToReviewIds;

        int feedbackArrayStart = jsonResponse.indexOf("[", assignedFeedbackStart);
        int feedbackArrayEnd = jsonResponse.indexOf("]", feedbackArrayStart) + 1;
        String rawFeedback = jsonResponse.substring(feedbackArrayStart, feedbackArrayEnd).replace("'", "\"");

        System.out.println("Raw Feedback Array: " + rawFeedback);

        String[] feedbackEntries = rawFeedback.split("},\\s*\\{");
        for (String entry : feedbackEntries) {
            String issueKey = extractJsonValue(entry, "issue_key");
            String feedbackId = extractJsonValue(entry, "feedback_id");

            if (issueKey != null && feedbackId != null) {
                issueKeyToReviewIds.computeIfAbsent(issueKey, k -> new HashSet<>()).add(feedbackId);
            }
        }

        System.out.println("Parsed Issue Key to Review IDs: " + issueKeyToReviewIds);
        return issueKeyToReviewIds;
    }

    private Map<String, String> extractReviewIdToText(String jsonResponse) {
        Map<String, String> reviewIdToText = new HashMap<>();
        int importedFeedbackStart = jsonResponse.indexOf("\"imported_feedback\":");
        if (importedFeedbackStart == -1) return reviewIdToText;

        int feedbackArrayStart = jsonResponse.indexOf("[", importedFeedbackStart);
        int feedbackArrayEnd = jsonResponse.indexOf("]", feedbackArrayStart) + 1;
        String rawFeedback = jsonResponse.substring(feedbackArrayStart, feedbackArrayEnd).replace("'", "\"");

        System.out.println("Raw Imported Feedback: " + rawFeedback);

        // Match individual feedback entries using regex
        Pattern feedbackEntryPattern = Pattern.compile("\\{.*?\\}");
        Matcher matcher = feedbackEntryPattern.matcher(rawFeedback);

        while (matcher.find()) {
            String entry = matcher.group();
            String reviewId = extractJsonValue(entry, "id");

            // Extract review text between \n### markers
            String reviewText = extractReviewText(entry);

            if (reviewId != null && reviewText != null) {
                reviewIdToText.put(reviewId, reviewText);
            }
        }

        System.out.println("Parsed Review ID to Text: " + reviewIdToText);
        return reviewIdToText;
    }

    private String extractReviewText(String jsonEntry) {
        String searchPattern = "\\\\n###"; // Matches \n###
        int startIndex = jsonEntry.indexOf(searchPattern);
        if (startIndex == -1) return null;

        // Find the end of the review text
        startIndex += searchPattern.length();
        int endIndex = jsonEntry.indexOf("\\n", startIndex); // Assume reviews end at the next newline
        if (endIndex == -1) endIndex = jsonEntry.length(); // If no further newline, take the rest of the entry

        String reviewText = jsonEntry.substring(startIndex, endIndex);

        // Clean up the extracted text
        reviewText = reviewText.replace("\\n", " ").trim();

        System.out.println("Extracted Review Text: " + reviewText);
        return reviewText;
    }

    private List<Code> parseAndMapResponse(String jsonResponse) {
        int annotationStartIndex = jsonResponse.indexOf("\"annotation\":\"") + 13;
        int annotationEndIndex = jsonResponse.lastIndexOf("\"");
        if (annotationStartIndex == -1 || annotationEndIndex == -1) {
            return new ArrayList<>();
        }

        String annotationJson = jsonResponse.substring(annotationStartIndex, annotationEndIndex)
                .replace("'", "\"")
                .replace("ObjectId(", "")
                .replace(")", "");

        System.out.println("Annotation JSON: " + annotationJson);

        Map<Integer, Token> tokenMap = extractTokens(annotationJson);
        List<Code> codes = extractCodes(annotationJson);

        for (Code code : codes) {
            for (Integer tokenIndex : code.tokenIndexes) {
                Token token = tokenMap.get(tokenIndex);
                if (token != null) {
                    code.mappedWords.add(token.name);
                }
            }
        }

        System.out.println("Parsed Codes: " + codes);
        return codes;
    }

    private Map<Integer, Token> extractTokens(String json) {
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

    private List<Code> extractCodes(String json) {
        List<Code> codes = new ArrayList<>();
        Pattern codePattern = Pattern.compile("\\{\"tokens\":\\s*(\\[[^\\]]*\\]),\\s*\"name\":\\s*\"([^\"]*)\",\\s*\"tore\":\\s*\"([^\"]*)\"");
        Matcher codeMatcher = codePattern.matcher(json);
    
        while (codeMatcher.find()) {
            String tokensArrayStr = codeMatcher.group(1);
            String name = codeMatcher.group(2);
            String tore = codeMatcher.group(3);
    
            // Debug logs for extracted values
            System.out.println("Extracted Code Name: " + name);
            System.out.println("Extracted Tore: " + tore);
    
            List<Integer> tokenIndexes = new ArrayList<>();
            Matcher numberMatcher = Pattern.compile("\\d+").matcher(tokensArrayStr);
            while (numberMatcher.find()) {
                tokenIndexes.add(Integer.parseInt(numberMatcher.group()));
            }
    
            codes.add(new Code(tokenIndexes, name, tore));
        }
    
        System.out.println("Extracted Codes: " + codes);
        return codes;
    }

    private String extractJsonValue(String json, String key) {
        String searchPattern = "\"" + key + "\":";
        int keyIndex = json.indexOf(searchPattern);
        if (keyIndex == -1) return null;

        int valueStart = json.indexOf("\"", keyIndex + searchPattern.length());
        int valueEnd = json.indexOf("\"", valueStart + 1);
        String extractedValue = json.substring(valueStart + 1, valueEnd);

        // Log extracted value for debugging
        System.out.println("Extracted value for key '" + key + "': " + extractedValue);

        return extractedValue;
    }

    private String generateFeedbackHtml() {
        StringBuilder html = new StringBuilder();
        for (Map.Entry<String, HashSet<String>> entry : issueKeyToReviewIds.entrySet()) {
            html.append("<p><strong>Issue Key: </strong>").append(entry.getKey()).append("</p>");
            html.append("<ul>");
            for (String reviewId : entry.getValue()) {
                String text = reviewIdToText.getOrDefault(reviewId, "No text available");
                html.append("<li><strong>").append(reviewId).append(":</strong> ").append(text).append("</li>");
            }
            html.append("</ul>");
        }
        return html.toString();
    }
    private String generateUsageHtml() {
        StringBuilder html = new StringBuilder();
        for (Code code : usageCodes) {
            html.append("<p><strong>Code Name: </strong>").append(code.name).append("</p>");
            html.append("<p><strong>Tore: </strong>").append(code.tore).append("</p>"); // Include Tore
            html.append("<p><strong>Tokens: </strong>").append(code.tokenIndexes).append("</p>");
            html.append("<p><strong>Mapped Words: </strong>").append(String.join(", ", code.mappedWords)).append("</p>");
            html.append("<hr/>");
        }
        return html.toString();
    }


    private void storeFeedbackForAllIssuesWithChunking(
        Map<String, HashSet<String>> issueKeyToReviewIds,
        Map<String, String> reviewIdToText) throws Exception {

    for (Map.Entry<String, HashSet<String>> entry : issueKeyToReviewIds.entrySet()) {
        String issueKey = entry.getKey();
        HashSet<String> reviewIds = entry.getValue();

        // Build feedback list
        List<String> feedbackList = new ArrayList<>();
        for (String reviewId : reviewIds) {
            String text = reviewIdToText.get(reviewId);
            if (text != null) {
                text = sanitizeTextForJson(text);
                feedbackList.add("{\"id\": \"" + reviewId + "\", \"text\": \"" + text + "\"}");
            }
        }

        // Chunk feedback data and store
        List<String> chunks = createJsonChunks(feedbackList);
        for (int i = 0; i < chunks.size(); i++) {
            String chunkKey = "feedbackDat_" + (i + 1);
            storeEntityPropertyInJira(issueKey, chunkKey, chunks.get(i));
        }
    }
}
private void storeUsageDataWithChunking(String projectKey, List<Code> usageCodes) throws Exception {
    List<String> usageDataList = new ArrayList<>();
    for (Code code : usageCodes) {
        String codeJson = String.format(
                "{\"name\": \"%s\", \"tore\": \"%s\", \"tokens\": %s, \"words\": %s}",
                sanitizeTextForJson(code.name),
                sanitizeTextForJson(code.tore),
                arrayToJson(code.tokenIndexes),
                arrayToJson(code.mappedWords)
        );
        usageDataList.add(codeJson);
    }

    // Chunk usage data and store
    List<String> chunks = createJsonChunks(usageDataList);
    for (int i = 0; i < chunks.size(); i++) {
        String chunkKey = "usageDat_" + (i + 1);
        storeProjectPropertyInJira(projectKey, chunkKey, chunks.get(i));
    }
}
private List<String> createJsonChunks(List<String> dataList) {
    List<String> chunks = new ArrayList<>();
    StringBuilder currentChunk = new StringBuilder("{\"data\": [");
    int currentSize = currentChunk.length();

    for (String data : dataList) {
        int dataSize = data.length();
        if (currentSize + dataSize + 2 > 32000) { // Approx 32KB limit
            currentChunk.append("]}");
            chunks.add(currentChunk.toString());
            currentChunk = new StringBuilder("{\"data\": [");
            currentSize = currentChunk.length();
        }
        if (currentSize > currentChunk.length()) {
            currentChunk.append(", ");
        }
        currentChunk.append(data);
        currentSize += dataSize + 2;
    }

    if (currentSize > 0) {
        currentChunk.append("]}");
        chunks.add(currentChunk.toString());
    }

    return chunks;
}
private void storeEntityPropertyInJira(String issueKey, String propertyKey, String jsonBody) throws Exception {
    String apiUrl = JIRA_BASE_URL + "/rest/api/2/issue/" + issueKey + "/properties/" + propertyKey;
    System.out.println("Storing property for issue: " + issueKey + ", Key: " + propertyKey);

    HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
    connection.setRequestMethod("PUT");
    connection.setRequestProperty("Authorization", "Basic " + getEncodedCredentials());
    connection.setRequestProperty("Content-Type", "application/json");
    connection.setDoOutput(true);

    try (OutputStream os = connection.getOutputStream()) {
        os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    int responseCode = connection.getResponseCode();
    System.out.println("HTTP Response Code: " + responseCode);

    if (responseCode == 201 || responseCode == 204) {
        System.out.println("Property stored successfully for issue: " + issueKey + ", Key: " + propertyKey);
    } else if (responseCode == 200) {
        System.err.println("Unexpected HTTP 200 response while storing property for issue: " + issueKey);
        try (InputStream errorStream = connection.getInputStream()) {
            if (errorStream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    System.err.println("Response Body: " + response);
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading response for HTTP 200: " + e.getMessage());
        }
    } else {
        System.err.println("Failed to store property for issue: " + issueKey + ". HTTP Code: " + responseCode);
        try (InputStream errorStream = connection.getErrorStream()) {
            if (errorStream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    System.err.println("Error Response: " + errorResponse);
                }
            }
        }
    }
}
private void storeProjectPropertyInJira(String projectKey, String propertyKey, String jsonBody) throws Exception {
    String apiUrl = JIRA_BASE_URL + "/rest/api/2/project/" + projectKey + "/properties/" + propertyKey;

    HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
    connection.setRequestMethod("PUT");
    connection.setRequestProperty("Authorization", "Basic " + getEncodedCredentials());
    connection.setRequestProperty("Content-Type", "application/json");
    connection.setDoOutput(true);

    try (OutputStream os = connection.getOutputStream()) {
        os.write(jsonBody.getBytes());
        os.flush();
    }

    int responseCode = connection.getResponseCode();
    if (responseCode == 201 || responseCode == 204) {
        System.out.println("Stored property for project: " + projectKey + ", Key: " + propertyKey);
    } else {
        System.err.println("Failed to store property for project: " + projectKey + ". HTTP Code: " + responseCode);
    }
}

private String sanitizeTextForJson(String text) {
    if (text == null) return "";
    return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
}

private String arrayToJson(List<?> list) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < list.size(); i++) {
        Object item = list.get(i);
        if (item instanceof String) {
            sb.append("\"").append(sanitizeTextForJson((String) item)).append("\"");
        } else {
            sb.append(item);
        }
        if (i < list.size() - 1) sb.append(", ");
    }
    sb.append("]");
    return sb.toString();
}

private String getEncodedCredentials() {
    String credentials = USERNAME + ":" + PASSWORD;
    return Base64.getEncoder().encodeToString(credentials.getBytes());
}


    private static class Token {
        int index;
        String name;

        Token(int index, String name) {
            this.index = index;
            this.name = name;
        }
    }

    private static class Code {
        List<Integer> tokenIndexes;
        String name;
        String tore;
        List<String> mappedWords = new ArrayList<>();

        Code(List<Integer> tokenIndexes, String name, String tore) {
            this.tokenIndexes = tokenIndexes;
            this.name = name;
            this.tore = tore;
        }
    }
}