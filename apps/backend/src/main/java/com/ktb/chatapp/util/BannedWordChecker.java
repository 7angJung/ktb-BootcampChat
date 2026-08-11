package com.ktb.chatapp.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.Assert;

public class BannedWordChecker {

    private final TrieNode root = new TrieNode();
    private final int maxWordLength;
    
    public BannedWordChecker(Set<String> bannedWords) {
        Set<String> normalizedWords =
                bannedWords.stream()
                        .filter(word -> word != null && !word.isBlank())
                        .map(word -> word.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        Assert.notEmpty(normalizedWords, "Banned words set must not be empty");

        int longestWordLength = 0;
        for (String word : normalizedWords) {
            addWord(word);
            longestWordLength = Math.max(longestWordLength, word.length());
        }
        this.maxWordLength = longestWordLength;
    }
    
    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        
        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        for (int start = 0; start < normalizedMessage.length(); start++) {
            TrieNode node = root;
            int end = Math.min(normalizedMessage.length(), start + maxWordLength);

            for (int index = start; index < end; index++) {
                node = node.children.get(normalizedMessage.charAt(index));
                if (node == null) {
                    break;
                }
                if (node.terminal) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addWord(String word) {
        TrieNode node = root;
        for (int index = 0; index < word.length(); index++) {
            node = node.children.computeIfAbsent(word.charAt(index), ignored -> new TrieNode());
        }
        node.terminal = true;
    }

    private static final class TrieNode {
        private final Map<Character, TrieNode> children = new HashMap<>();
        private boolean terminal;
    }
}
