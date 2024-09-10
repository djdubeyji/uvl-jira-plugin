package com.yourcompany;

import com.atlassian.jira.plugin.webfragment.contextproviders.AbstractJiraContextProvider;
import com.atlassian.jira.plugin.webfragment.model.JiraHelper;
import com.atlassian.jira.user.ApplicationUser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FeedbackContextProvider extends AbstractJiraContextProvider {

    @Override
    public Map<String, Object> getContextMap(ApplicationUser user, JiraHelper jiraHelper) {
        Map<String, Object> contextMap = new HashMap<>();

        // Simulated feedback list with more items
        List<String> feedbackList = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            feedbackList.add(" This is feedback number " + i git commit -m);
        }

        contextMap.put("feedbackList", feedbackList);

        return contextMap;
    }
}
