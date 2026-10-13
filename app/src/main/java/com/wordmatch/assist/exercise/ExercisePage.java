package com.wordmatch.assist.exercise;

import com.wordmatch.assist.sentence.*;
import com.wordmatch.assist.sentence.SentencePage.Node;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Only app-owned exercise containers enter the solver. Menus and account fields never do. */
public final class ExercisePage {
    public enum Mode { CHOICE, TEXT, ORDER, SKIP }
    public ExerciseType type;
    public Mode mode;
    public String instruction = "", prompt = "", container = "";
    public boolean recognized, complete, feedback, correct, wrong, paused, timed, multiple, gradesOnChoice, skipConfirmation;
    public Node submit, next, skip, resume;
    public final List<Node> options = new ArrayList<>(), fields = new ArrayList<>(), selected = new ArrayList<>();
    public List<String> inventory() {
        List<String> all = SentencePage.words(options);
        if (mode == Mode.ORDER) all.addAll(SentencePage.words(selected));
        return all;
    }
    public List<String> visibleAnswer() {
        if (mode == Mode.TEXT) { List<String> text = new ArrayList<>(); for (Node n : fields) text.add(n.text); return text; }
        if (mode == Mode.ORDER) return SentencePage.words(selected);
        List<String> answer = new ArrayList<>(); for (Node n : options) if (n.selected || n.checked) answer.add(n.text);
        return answer;
    }
    public List<String> memoryAnswer(List<String> answer) {
        List<String> result = new ArrayList<>(answer);
        if (mode == Mode.CHOICE) result.sort(Comparator.comparing(SentencePlan::normalize));
        return result;
    }
    public String contextKey() { return container + "|" + instruction + "|" + prompt; }
    public String identity() { return contextKey() + "|" + mode + "|" + fields.size() + "|" + SentencePlan.bankKey(inventory()); }
    public String memoryKey() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
            return type.label + "｜" + prompt.substring(0, Math.min(prompt.length(), 500)) + "\n#" + hex;
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static String id(Node n) { return n.id.startsWith("com.duolingo:id/") ? n.id.substring(16) : ""; }
    public static Node find(Node root, String id) {
        if (id(root).equals(id)) return root;
        for (Node n : root.children) { Node found = find(n, id); if (found != null) return found; } return null;
    }
    private static void flatten(Node n, List<Node> all) { all.add(n); for (Node child : n.children) flatten(child, all); }
    private static Node exercise(Node root) {
        if (id(root).startsWith("challenge_")) return root;
        for (Node child : root.children) { Node found = exercise(child); if (found != null) return found; } return null;
    }
    private static ExerciseType classify(String id) {
        if (id.contains("listen")) return ExerciseType.LISTEN;
        if (id.contains("speak")) return ExerciseType.SPEAK;
        switch (id) {
            case "challenge_assist": case "challenge_reverse_assist": case "challenge_select":
            case "challenge_name": case "challenge_definition": case "challenge_meaning_guess":
            case "challenge_multiple_choice_flashcards": case "challenge_same_different": return ExerciseType.CHOICE;
            case "challenge_read_comprehension": case "challenge_type_comprehension":
            case "challenge_write_comprehension": return ExerciseType.READING;
            case "challenge_dialogue": case "challenge_dialogue_type_complete": return ExerciseType.DIALOGUE;
            case "challenge_gap_fill": case "challenge_tap_cloze": case "challenge_tap_complete":
            case "challenge_type_cloze": case "challenge_type_complete": case "challenge_partial_reverse_translate":
            case "challenge_complete_reverse_translation": return ExerciseType.FILL;
            case "challenge_order_tap_complete": case "challenge_pattern_tap_complete":
            case "challenge_write_word_bank": return ExerciseType.ORDER;
            case "challenge_translate": return ExerciseType.TEXT;
            default: return null;
        }
    }
    private static String plain(String s) { return s.replaceAll("[\\p{Cf}]", "").trim().toLowerCase(Locale.ROOT); }
    public static boolean skipLabel(ExerciseType type, String label) {
        String value = plain(label).replace('’', '\'');
        if (type == ExerciseType.LISTEN) return Arrays.asList("现在不做听力题", "现在不能听", "can't listen now", "cannot listen now").contains(value);
        if (type == ExerciseType.SPEAK) return Arrays.asList("现在不能说话", "现在不方便说话", "现在不做口语题", "can't speak now", "cannot speak now").contains(value);
        return false;
    }
    public static ExercisePage parse(Node root, int width, int height, boolean truncated) {
        ExercisePage p = new ExercisePage();
        SentencePage pause = SentencePage.parse(root, width, height, truncated);
        p.paused = pause.paused; p.resume = pause.resume;
        if (p.paused) return p;
        List<Node> confirmationNodes = new ArrayList<>(); flatten(root, confirmationNodes);
        for (Node n : confirmationNodes) {
            String label = plain(n.text);
            if (label.equals("将跳过本单元的所有听力练习") || label.equals("we’ll skip listening for this lesson.") || label.equals("we'll skip listening for this lesson.")) p.type = ExerciseType.LISTEN;
            if (label.equals("将跳过本单元的所有口语练习") || label.equals("we’ll skip speaking for this lesson.") || label.equals("we'll skip speaking for this lesson.")) p.type = ExerciseType.SPEAK;
        }
        if (p.type != null) {
            p.skipConfirmation = p.recognized = true; p.mode = Mode.SKIP;
            for (Node n : confirmationNodes) if (n.clickable && n.enabled && inside(n,width,height) && n.top > height * .6
                    && Arrays.asList("继续", "知道了", "好的", "continue", "got it", "ok").contains(plain(n.label()))) p.next = n;
            p.complete = !truncated && p.next != null; return p;
        }
        Node exercise = exercise(root); if (exercise == null) return p;
        p.container = id(exercise); p.type = classify(p.container); if (p.type == null) return p;
        p.recognized = true;
        Node instruction = find(root, "challengeInstruction"); p.instruction = instruction == null ? "" : instruction.label();
        p.timed = find(root, "rampUpTimerText") != null;
        SentencePage feedback = new SentencePage(); SentencePage.readFeedback(feedback, root, width, height);
        p.feedback = feedback.feedback; p.correct = feedback.correct; p.wrong = feedback.wrong; p.next = feedback.next;
        List<Node> nodes = new ArrayList<>(); flatten(root, nodes);
        if (p.type.skip()) {
            p.mode = Mode.SKIP;
            for (Node n : nodes) if (n.clickable && n.enabled && inside(n, width, height) && skipLabel(p.type, n.label())) p.skip = n;
            p.complete = !truncated && p.skip != null; return p;
        }
        // Locate the explicit submit region, including layouts whose initial label is Continue.
        Node submit = find(root, "submitButton");
        // Assist/select layouts grade the option tap itself; Continue advances the result.
        p.gradesOnChoice = submit != null && Arrays.asList("继续", "continue").contains(plain(submit.label()));
        if (!p.feedback && submit != null) p.submit = action(submit);
        if (!p.feedback && p.submit == null) p.submit = feedback.check;
        collectOptions(exercise, null, p.options);
        List<Node> content = new ArrayList<>(); flatten(exercise, content);
        for (Node n : content) if (n.editable && n.enabled && !n.password) p.fields.add(n);
        Node input = find(exercise, "tapInputViewContainer");
        Node group = input == null ? null : SentencePage.inputGroup(input);
        if (!p.fields.isEmpty()) { p.mode = Mode.TEXT; p.options.clear(); }
        else if (group != null) {
            p.mode = Mode.ORDER; p.options.clear();
            for (Node child : group.children) {
                if (SentencePage.tile(child)) p.options.add(child);
                else if (child.width() >= group.width() * .9 && child.top < group.top + group.height() * .25)
                    for (Node tile : child.children) if (SentencePage.tile(tile)) p.selected.add(tile);
            }
        } else p.mode = Mode.CHOICE;
        p.multiple = (plain(p.instruction).contains("选择所有") || plain(p.instruction).contains("选出所有")) || plain(p.instruction).contains("select all");
        Set<Node> exclude = Collections.newSetFromMap(new IdentityHashMap<>());
        if (instruction != null) exclude.add(instruction);
        if (input != null) exclude.add(input);
        // Options are wrappers; remove their original paths from the question text.
        StringBuilder prompt = new StringBuilder(); collectPrompt(exercise, p, exclude, prompt);
        p.prompt = prompt.toString().trim();
        if (p.type == ExerciseType.TEXT) {
            Node source = find(exercise, "hintablePrompt");
            if (source == null) source = find(exercise, "translatePrompt");
            if (source != null && !source.label().isEmpty()) p.prompt = source.label() + "\n[blank]";
        }
        Comparator<Node> reading = Comparator.comparingInt((Node n) -> n.top).thenComparingInt(n -> n.left);
        p.options.sort(reading); p.selected.sort(reading); p.fields.sort(reading);
        p.complete = !truncated && !p.prompt.isEmpty() && p.prompt.length() <= 6000 && !p.instruction.isEmpty();
        if (p.mode == Mode.TEXT) p.complete &= p.fields.size() <= 8;
        else p.complete &= !p.inventory().isEmpty() && p.inventory().size() <= 60;
        if (p.mode == Mode.CHOICE) p.complete &= new HashSet<>(SentencePlan.normalized(p.inventory())).size() == p.options.size();
        for (Node n : p.options) if (!n.enabled || !inside(n, width, height) || n.label().length() > 1000) p.complete = false;
        for (Node n : p.fields) if (!inside(n, width, height)) p.complete = false;
        return p;
    }
    private static boolean inside(Node n, int w, int h) { return n.width() > 0 && n.height() > 0 && n.left >= 0 && n.top >= 0 && n.right <= w && n.bottom <= h; }
    private static Node action(Node n) {
        if (n.clickable && n.enabled) return n;
        for (Node c : n.children) { Node found = action(c); if (found != null) return found; } return null;
    }
    private static boolean state(Node n) {
        if (n.selected || n.checked) return true;
        for (Node c : n.children) if (state(c)) return true; return false;
    }
    private static void collectOptions(Node n, Node clickable, List<Node> options) {
        if (n.clickable) clickable = n;
        if (Arrays.asList("optionText", "choiceToken", "optionTitle").contains(id(n)) && clickable != null && !n.label().isEmpty()) {
            for (Node old : options) if (Arrays.equals(old.path, clickable.path)) return;
            Node target = new Node(); target.text = n.label(); target.id = clickable.id; target.path = clickable.path;
            target.left = clickable.left; target.top = clickable.top; target.right = clickable.right; target.bottom = clickable.bottom;
            target.clickable = true; target.enabled = clickable.enabled; target.selected = state(clickable); options.add(target); return;
        }
        for (Node child : n.children) collectOptions(child, clickable, options);
    }
    private static void collectPrompt(Node n, ExercisePage p, Set<Node> exclude, StringBuilder text) {
        if (exclude.contains(n) || n.password) return;
        for (Node option : p.options) if (Arrays.equals(option.path, n.path)) return;
        String id = id(n);
        if (id.equals("prompt") && p.type == ExerciseType.FILL) { richPrompt(n, text); text.append('\n'); return; }
        if (Arrays.asList("optionsContainer", "formViewOptionsContainer", "optionText", "choiceToken", "optionTitle").contains(id)) return;
        if (id.equals("challengeInstruction") || id.toLowerCase(Locale.ROOT).contains("play") || id.contains("Audio")) return;
        if (n.editable || Arrays.asList("blank", "emptyBlank", "blankTextField", "textInput", "wordInput", "completeTranslationInput").contains(id)) { text.append(" [blank] "); return; }
        if (!n.text.trim().isEmpty()) { text.append(n.text.trim()).append('\n'); return; }
        for (Node c : n.children) collectPrompt(c, p, exclude, text);
    }
    /** The installed gap-fill view exposes blanks as an empty box with a bottom rule. */
    private static void richPrompt(Node n, StringBuilder text) {
        if (n.editable || id(n).equals("blank") || id(n).equals("emptyBlank")) { text.append(" [blank] "); return; }
        if (!n.text.isEmpty()) {
            // Line wrapping can omit the space node between two English words.
            if (text.length() > 0 && Character.isLetterOrDigit(text.charAt(text.length()-1))
                    && Character.isLetterOrDigit(n.text.charAt(0))) text.append(' ');
            text.append(n.text); return;
        }
        if (n.className.equals("android.widget.FrameLayout") && n.label().isEmpty() && !n.clickable
                && n.width() >= 40 && n.height() > 30) { text.append(" [blank] "); return; }
        if (n.children.size() == 1 && n.label().isEmpty() && !n.clickable && n.height() > 30) {
            Node line = n.children.get(0);
            if (line.children.isEmpty() && line.height() > 0 && line.height() <= 10
                    && line.width() == n.width() && line.bottom == n.bottom) { text.append(" [blank] "); return; }
        }
        for (Node child : n.children) richPrompt(child, text);
    }

}
