package org.bytopia.foreclojure;

import android.graphics.Color;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import clojure.lang.PersistentHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.util.Log;

class CodeboxTextWatcher implements TextWatcher {

    private static String TAG = "org.bytopia.foreclojure.CodeboxTextWatcher";
    private PersistentHashSet coreForms;
    private final static PersistentHashSet defunIndents =
        PersistentHashSet.create("fn", "let", "if", "when", "if-let", "when-let",
                                 "if-not", "when-not", "loop", "for", "doseq", "while");
    private int editPoint = 0;
    private boolean enterPressed = false;
    private Pattern formPattern = Pattern.compile("[-?a-z]+");

    public CodeboxTextWatcher(PersistentHashSet coreForms) {
        this.coreForms = coreForms;
    }

    private static int calculateIndent(String txt) {
        String[] lines = txt.split("\n");
        int closingP = 0;
        int closingB = 0;
        int closingC = 0;
        for (int i = lines.length - 1; i >= 0; i--) {
            String cl = lines[i];
            for (int j = cl.length() - 1; j >= 0; j--) {
                char c = cl.charAt(j);
                switch (c) {
                case ')': closingP++; break;
                case ']': closingB++; break;
                case '}': closingC++; break;
                case '(': closingP--; break;
                case '[': closingB--; break;
                case '{': closingC--; break;
                }
                Log.d(TAG, "calculate: " + c + "   " + closingP + closingB + closingC);
                if (closingB < 0 || closingC < 0) {
                    return j + 1;
                } else if (closingP < 0) {
                    String[] words = cl.substring(j+1).split("[ ,]");
                    if (words.length <= 1)
                        return j + 1;
                    else if (defunIndents.contains(words[0]))
                        return j + 2;
                    else
                        return j + words[0].length() + 2;
                }
            }
        }
        return 0;
    }

    private static String makeSpaces(int count) {
        if (count == 0) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++)
            b.append(" ");
        return b.toString();
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        try {
            editPoint = start;
            enterPressed = ((s.length() > 0) && (count > 0) &&
                            (s.charAt(start) == '\n'));
        } catch (Exception e) { Log.e(TAG, "onTextChanged: " + s + " " + start); }
    }

    @Override
    public void afterTextChanged(Editable s) {
        try {
            // Handle Enter pressed
            if (enterPressed) {
                int spacesCount = calculateIndent(s.toString().substring(0, editPoint));
                s.insert(editPoint+1, makeSpaces(spacesCount));
            }

            // Remove spans at the point of editing
            for (ForegroundColorSpan span : s.getSpans(editPoint, editPoint,
                                                       ForegroundColorSpan.class)) {
                s.removeSpan(span);
            }

            // Add new spans for Clojure forms
            Matcher m = formPattern.matcher(s.toString());
            while (m.find()) {
                String form = (String)coreForms.get(m.group());
                if (form != null) {
                    ForegroundColorSpan[] spans = s.getSpans(m.start(), m.end(),
                                                             ForegroundColorSpan.class);
                    if (spans.length == 0) {
                        s.setSpan(new ForegroundColorSpan(Color.BLUE),
                                  m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "afterTextChanged: " + s + " " + editPoint); }
    }

}
