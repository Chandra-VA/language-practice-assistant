package com.wordmatch.assist.sentence;

import java.util.*;
import com.wordmatch.assist.matching.PauseConfirmationDetector;

/** A bounded, detached node snapshot. Also usable in JVM tests without Android. */
public final class SentencePage {
    public static final class Node {
        public String id = "", text = "", className = "";
        public boolean clickable, editable, selected, checked, password, enabled = true;
        public String hint = "";
        public int left, top, right, bottom;
        public int[] path = new int[0];
        public final List<Node> children = new ArrayList<>();
        public int width() { return right - left; }
        public int height() { return bottom - top; }
        public String label() {
            if (!text.trim().isEmpty()) return text.trim();
            for (Node child : children) { String label = child.label(); if (!label.isEmpty()) return label; }
            return "";
        }
    }
    public final List<Node> bank = new ArrayList<>(), selected = new ArrayList<>();
    public String prompt = "";
    public boolean recognized, complete, selectedComplete, paused, feedback, correct, wrong, timed;
    public Node check, next, resume;
    public List<String> selectedWords() { return words(selected); }
    public List<String> allWords() { List<String> all = words(bank); all.addAll(words(selected)); return all; }
    public String identity() { return SentencePlan.key(prompt) + "|" + SentencePlan.bankKey(allWords()); }
    public static List<String> words(List<Node> nodes) {
        List<String> words = new ArrayList<>(); for (Node n : nodes) words.add(n.label()); return words;
    }
    private static String suffix(Node n) { return n.id.substring(n.id.lastIndexOf('/') + 1); }
    private static Node find(Node root, String id) {
        if (suffix(root).equals(id)) return root;
        for (Node child : root.children) { Node found = find(child, id); if (found != null) return found; }
        return null;
    }
    private static void flatten(Node n, List<Node> nodes) { nodes.add(n); for (Node c : n.children) flatten(c, nodes); }
    public static boolean tile(Node n) {
        if (!n.clickable || !n.enabled || n.label().isEmpty() || n.label().length() > 100) return false;
        // Compose exposes one clickable tile containing a text leaf and a Button role.
        int labels = 0;
        for (Node child : n.children) if (!child.text.trim().isEmpty()) labels++;
        return labels == 1;
    }
    public static Node inputGroup(Node root) {
        boolean bank = false, answer = false;
        for (Node child : root.children) {
            if (tile(child)) bank = true;
            if (child.width() >= root.width() * 0.9 && child.height() >= 100 && child.height() < root.height() * 0.8
                    && child.top < root.top + root.height() * 0.25 && !tile(child)) answer = true;
        }
        boolean selected = false;
        for (Node child : root.children) for (Node nested : child.children) if (tile(nested)) selected = true;
        if (answer && (bank || selected)) return root;
        for (Node child : root.children) { Node found = inputGroup(child); if (found != null) return found; }
        return null;
    }
    public static SentencePage parse(Node root, int width, int height, boolean truncated) {
        SentencePage p = new SentencePage();
        List<Node> nodes = new ArrayList<>(); flatten(root, nodes);
        PauseConfirmationDetector pause = new PauseConfirmationDetector();
        for (Node n : nodes) pause.observe(n.text, n.left, n.top, n.right, n.bottom, width, height);
        p.paused = pause.isVisible();
        if (p.paused) {
            for (Node n : nodes) if (n.clickable && n.enabled && PauseConfirmationDetector.isResumeControl(
                    n.label(), n.left,n.top,n.right,n.bottom,width,height)) { p.resume = n; break; }
            return p;
        }
        Node exercise = find(root, "challenge_translate");
        Node prompt = find(root, "hintablePrompt"), input = find(root, "tapInputViewContainer");
        Node instruction = find(root, "challengeInstruction");
        if (exercise == null || prompt == null || input == null || instruction == null
                || !(instruction.label().contains("翻译") || instruction.label().toLowerCase(Locale.ROOT).contains("translate"))) return p;
        p.recognized = true; p.prompt = prompt.label(); p.timed = find(root, "rampUpTimerText") != null;
        Node group = inputGroup(input);
        if (group != null) {
            for (Node child : group.children) {
                if (tile(child)) p.bank.add(child);
                else if (child.width() >= group.width() * 0.9 && child.top < group.top + group.height() * 0.25) {
                    for (Node selected : child.children) if (tile(selected)) p.selected.add(selected);
                }
            }
        }
        Comparator<Node> reading = Comparator.comparingInt((Node n) -> n.top).thenComparingInt(n -> n.left);
        p.bank.sort(reading); p.selected.sort(reading);
        readFeedback(p, root, width, height);
        p.selectedComplete = !truncated && group != null && !p.selected.isEmpty();
        p.complete = !truncated && group != null && !p.prompt.isEmpty() && p.prompt.length() <= 2000
                && !p.allWords().isEmpty() && p.allWords().size() <= 60;
        for (Node n : p.bank) if (n.left < 0 || n.top < 0 || n.right > width || n.bottom > height) p.complete = false;
        for (Node n : p.selected) if (n.left < 0 || n.top < 0 || n.right > width || n.bottom > height) { p.complete = false; p.selectedComplete = false; }
        return p;
    }
    /** Shared positive/negative feedback contract for every supported exercise. */
    public static void readFeedback(SentencePage p, Node root, int width, int height) {
        List<Node> nodes = new ArrayList<>(); flatten(root, nodes);
        Node footer = find(root, "composeBottomContainer");
        boolean positive = false, negative = false;
        if (footer != null) {
            List<Node> feedbackNodes = new ArrayList<>(); flatten(footer, feedbackNodes);
            for (Node n : feedbackNodes) {
                String label = n.text.trim().toLowerCase(Locale.ROOT);
                if (label.contains("正确答案") || label.contains("正确翻译") || label.contains("不正确")
                        || label.contains("答错") || label.contains("correct solution") || label.contains("incorrect")) negative = true;
                String positiveLabel = label.replaceFirst("(?:意思是[:：]|meaning[:：])$", "").trim();
                if (positiveLabel.matches("(答得漂亮|完美修正|完美|太棒了|你太棒了|非常棒|非常好|很棒|好棒|真厉害|答对了|你答对了|正确|做得好|做得不错|很好|不错|不错哦|真不错|厉害|干得好|真棒|棒棒哒|泰裤辣|完全正确|perfect|correct|great|excellent|amazing|awesome|good job|great job|nice|nice job|wow|nicely done)[！!。.]*")) positive = true;
            }
        }
        for (Node n : nodes) {
            String label = n.label().toLowerCase(Locale.ROOT);
            if (!n.clickable || !n.enabled || n.top < height * 0.80 || n.width() < width * 0.5) continue;
            if (label.equals("检查") || label.equals("check")) p.check = n;
            if (label.equals("继续") || label.equals("continue") || label.equals("知道了") || label.equals("got it")) p.next = n;
        }
        p.feedback = footer != null && p.next != null;
        p.correct = p.feedback && positive && !negative && find(root, "continueButtonGreenStub") != null;
        p.wrong = p.feedback && negative;
    }

}
