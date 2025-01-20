package com.example.plugins;

import com.atlassian.plugin.web.ContextProvider;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;


public class IssueKeyContextProvider implements ContextProvider {

    private static final String JIRA_BASE_URL = "http://localhost:2990/jira"; // Jira Base URL
    private static final String ENTITY_PROPERTY_KEY_PREFIX = "feedbackDat_"; 
    private static final String USERNAME = "admin"; 
    private static final String PASSWORD = "admin"; 

    @Override
    public void init(Map<String, String> params) {
        // No initialization required
    }

    @Override
    public Map<String, Object> getContextMap(Map<String, Object> context) {
        Map<String, Object> result = new HashMap<>();
    
        try {
            // Retrieve the issue key from the context
            Object issueObject = context.get("issue");
            String issueKey = null;
    
            if (issueObject != null) {
                if (issueObject instanceof String) {
                    issueKey = (String) issueObject;
                } else if (issueObject instanceof com.atlassian.jira.issue.Issue) {
                    issueKey = ((com.atlassian.jira.issue.Issue) issueObject).getKey();
                }
            }
    
            if (issueKey == null || issueKey.isEmpty()) {
                throw new IllegalArgumentException("Issue key is not available in the context.");
            }
    
            // Fetch and combine feedback data for the issue
            List<Map<String, String>> feedbackList = fetchAndCombineChunkedFeedback(issueKey);
    
            // Add the issue key and feedback list to the Velocity context
            result.put("issueKey", issueKey);
            result.put("feedbackList", feedbackList); // Pass as List<Map<String, String>>
    
        } catch (Exception e) {
            e.printStackTrace();
            result.put("issueKey", "Error retrieving issue key");
            result.put("feedbackList", new ArrayList<>()); // Return an empty list on error
        }
    
        return result;
    }

    private List<Map<String, String>> fetchAndCombineChunkedFeedback(String issueKey) throws Exception {
        List<Map<String, String>> feedbackList = new ArrayList<>();
        int chunkIndex = 1;
    
        while (true) {
            String propertyKey = ENTITY_PROPERTY_KEY_PREFIX + chunkIndex;
            String feedbackJson = fetchEntityPropertyFromJira(issueKey, propertyKey);
    
            if (feedbackJson == null) {
                break; // No more chunks
            }
    
            feedbackList.addAll(parseFeedbackJson(feedbackJson));
            chunkIndex++;
        }
    
        return feedbackList;
    }

    private String fetchEntityPropertyFromJira(String issueKey, String propertyKey) throws Exception {
        String apiUrl = JIRA_BASE_URL + "/rest/api/2/issue/" + issueKey + "/properties/" + propertyKey;
        HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Basic " + getEncodedCredentials());
        connection.setRequestProperty("Accept", "application/json");

        int responseCode = connection.getResponseCode();
        if (responseCode == 200) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            return response.toString();
        } else {
            System.err.println("Failed to fetch property. HTTP Response Code: " + responseCode);
        }

        return null;
    }

    private String getEncodedCredentials() {
        String credentials = USERNAME + ":" + PASSWORD;
        return Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    private List<Map<String, String>> parseFeedbackJson(String feedbackJson) {
        List<Map<String, String>> feedbackList = new ArrayList<>();
    
        try {
            // Locate the "data" array in the JSON
            int dataStartIndex = feedbackJson.indexOf("\"data\":");
            if (dataStartIndex != -1) {
                int arrayStartIndex = feedbackJson.indexOf("[", dataStartIndex);
                int arrayEndIndex = feedbackJson.indexOf("]", arrayStartIndex);
                if (arrayStartIndex != -1 && arrayEndIndex != -1) {
                    String dataArray = feedbackJson.substring(arrayStartIndex + 1, arrayEndIndex);
    
                    // Split individual feedback items
                    String[] feedbackItems = dataArray.split("},\\s*\\{");
                    for (String item : feedbackItems) {
                        // Ensure JSON object is properly enclosed
                        String cleanedItem = item.trim();
                        if (!cleanedItem.startsWith("{")) {
                            cleanedItem = "{" + cleanedItem;
                        }
                        if (!cleanedItem.endsWith("}")) {
                            cleanedItem = cleanedItem + "}";
                        }
    
                        Map<String, String> feedbackMap = new HashMap<>();
    
                        // Extract "id" field
                        int idStartIndex = cleanedItem.indexOf("\"id\":");
                        if (idStartIndex != -1) {
                            int valueStartIndex = cleanedItem.indexOf("\"", idStartIndex + 5) + 1;
                            int valueEndIndex = cleanedItem.indexOf("\"", valueStartIndex);
                            if (valueStartIndex != -1 && valueEndIndex != -1 && valueEndIndex > valueStartIndex) {
                                String feedbackId = cleanedItem.substring(valueStartIndex, valueEndIndex).trim();
                                feedbackMap.put("id", feedbackId);
                            }
                        }
    
                        // Extract "text" field
                        int textStartIndex = cleanedItem.indexOf("\"text\":");
                        if (textStartIndex != -1) {
                            int valueStartIndex = cleanedItem.indexOf("\"", textStartIndex + 7) + 1;
                            int valueEndIndex = cleanedItem.lastIndexOf("\"");
                            if (valueStartIndex != -1 && valueEndIndex != -1 && valueEndIndex > valueStartIndex) {
                    
                                String feedbackText = cleanedItem.substring(valueStartIndex, valueEndIndex)
                                        .replace("\\n", "\n")   
                                        .replace("\\t", "\t")   
                                        .replace("\\\"", "\"")  
                                        .replace("\\\\", "\\")  
                                        .trim();
                                feedbackMap.put("text", feedbackText);
                            }
                        }
    
                        if (!feedbackMap.isEmpty()) {
                            feedbackList.add(feedbackMap);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing feedback JSON: " + e.getMessage());
            e.printStackTrace();
        }
    
        return feedbackList;
    }
}
