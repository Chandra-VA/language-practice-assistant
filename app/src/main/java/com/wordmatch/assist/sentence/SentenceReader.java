package com.wordmatch.assist.sentence;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Arrays;

public final class SentenceReader {
    private int count;
    private boolean truncated;
    public SentencePage read(AccessibilityNodeInfo root, int width, int height) {
        count = 0; truncated = false;
        return SentencePage.parse(copy(root, new int[0], 0), width, height, truncated);
    }
    public SentencePage.Node snapshot(AccessibilityNodeInfo root) {
        count = 0; truncated = false;
        return copy(root, new int[0], 0);
    }
    public boolean truncated() { return truncated; }
    /** Rich gap-fill prompts expose literal spaces as individual text nodes. */
    public static String nodeText(CharSequence text, CharSequence description) {
        if ((text == null || text.toString().trim().isEmpty()) && description != null && !description.toString().isEmpty()) return description.toString();
        return text == null ? "" : text.toString();
    }
    private SentencePage.Node copy(AccessibilityNodeInfo source, int[] path, int depth) {
        SentencePage.Node node = new SentencePage.Node(); node.path = path;
        if (++count > 2400 || depth > 40) { truncated = true; return node; }
        if (!source.isVisibleToUser()) return node;
        node.id = source.getViewIdResourceName() == null ? "" : source.getViewIdResourceName();
        node.text = nodeText(source.getText(), source.getContentDescription());
        node.className = source.getClassName() == null ? "" : source.getClassName().toString();
        node.clickable = source.isClickable(); node.enabled = source.isEnabled();
        node.editable = source.isEditable(); node.selected = source.isSelected(); node.checked = source.isChecked();
        node.password = source.isPassword();
        if (node.password) { node.text = ""; return node; }
        if (Build.VERSION.SDK_INT >= 26 && source.getHintText() != null) node.hint = source.getHintText().toString();
        Rect r = new Rect(); source.getBoundsInScreen(r);
        node.left = r.left; node.top = r.top; node.right = r.right; node.bottom = r.bottom;
        for (int i = 0; i < source.getChildCount() && !truncated; i++) {
            AccessibilityNodeInfo child = source.getChild(i);
            if (child == null) continue;
            try {
                int[] childPath = Arrays.copyOf(path, path.length + 1); childPath[path.length] = i;
                node.children.add(copy(child, childPath, depth + 1));
            } finally { child.recycle(); }
        }
        return node;
    }
    /** Resolve a target in this fresh snapshot, never a coordinate saved from another question. */
    public static boolean click(AccessibilityNodeInfo root, SentencePage.Node target) {
        return act(root, target, null);
    }
    public static boolean setText(AccessibilityNodeInfo root, SentencePage.Node target, String text) {
        return text != null && act(root, target, text);
    }
    private static boolean act(AccessibilityNodeInfo root, SentencePage.Node target, String text) {
        if (target == null) return false;
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(root);
        try {
            for (int i : target.path) {
                AccessibilityNodeInfo child = node.getChild(i); node.recycle(); node = child;
                if (node == null) return false;
            }
            Rect r = new Rect(); node.getBoundsInScreen(r);
            if (!node.isVisibleToUser() || !node.isEnabled() || node.isPassword() || (text == null ? !node.isClickable() : !node.isEditable())
                    || r.left != target.left || r.top != target.top || r.right != target.right || r.bottom != target.bottom) return false;
            if (text == null) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } finally { if (node != null) node.recycle(); }
    }
}
