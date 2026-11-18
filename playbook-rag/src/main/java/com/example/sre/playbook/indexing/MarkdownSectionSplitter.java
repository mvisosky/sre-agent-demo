package com.example.sre.playbook.indexing;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits Markdown on structural boundaries (headings level 1-3 and horizontal rules, ignoring fenced code blocks).
 * Each section's text depends only on that section, so an edit in one section never shifts or alters the others.
 * Only sections longer than {@code maxSectionChars} are sub-chunked with {@link TokenTextSplitter}.
 */
public class MarkdownSectionSplitter {

    private static final Pattern HEADING = Pattern.compile("^#{1,3}\\s+.*");
    private static final Pattern HRULE = Pattern.compile("^ {0,3}([-*_])( ?\\1){2,}\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*");

    private final int maxSectionChars;
    private final TokenTextSplitter tokenSplitter = new TokenTextSplitter();

    public MarkdownSectionSplitter() {
        this(3000);
    }

    public MarkdownSectionSplitter(int maxSectionChars) {
        this.maxSectionChars = maxSectionChars;
    }

    /** Returns chunk texts (trimmed, non-blank) paired with their section heading, in document order. */
    public List<Section> split(String markdown) {
        List<Section> out = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return out;
        }
        List<String[]> raw = new ArrayList<>(); // [heading, body]
        StringBuilder cur = new StringBuilder();
        String heading = "";
        boolean inFence = false;
        for (String line : markdown.split("\\R", -1)) {
            if (FENCE.matcher(line).matches()) {
                inFence = !inFence;
            }
            if (!inFence && !FENCE.matcher(line).matches() && HRULE.matcher(line).matches()) {
                raw.add(new String[] {heading, cur.toString()});
                cur.setLength(0);
                continue;
            }
            if (!inFence && HEADING.matcher(line).matches()) {
                raw.add(new String[] {heading, cur.toString()});
                cur.setLength(0);
                heading = line.replaceFirst("^#+\\s+", "").trim();
            }
            cur.append(line).append('\n');
        }
        raw.add(new String[] {heading, cur.toString()});

        for (String[] r : raw) {
            String text = r[1].trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > maxSectionChars) {
                for (Document d : tokenSplitter.apply(List.of(new Document(text)))) {
                    String t = d.getText() == null ? "" : d.getText().trim();
                    if (!t.isEmpty()) {
                        out.add(new Section(r[0], t));
                    }
                }
            } else {
                out.add(new Section(r[0], text));
            }
        }
        return out;
    }

    public record Section(String heading, String text) {
    }
}
