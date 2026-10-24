package us.talabrek.ultimateskyblock.handbook;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns handbook sections into written-book pages.
 *
 * <p>The author writes flowing text (with {@code &} color codes and {@code [[label|target]]} links) and this class
 * does the tedious part that the in-game editor cannot do:
 * <ul>
 *     <li>wraps lines by <em>rendered pixel width</em> instead of by spaces (Chinese has no spaces, so plain
 *     word-wrap would produce one enormous line per page),</li>
 *     <li>splits a long section over as many pages as it needs (14 lines per page),</li>
 *     <li>resolves {@code [[label|anchor]]} links to the real page number, so inserting a page in the middle of the
 *     file does not break every manually written "see page 7".</li>
 * </ul>
 *
 * <p>Link targets:
 * <ul>
 *     <li>{@code [[标签|anchor-id]]} - jump to the page holding the section with that id,</li>
 *     <li>{@code [[标签|3]]} - jump to page 3 (1-based),</li>
 *     <li>{@code [[标签|cmd:/is home]]} - click to run a command,</li>
 *     <li>{@code [[标签|url:https://example.com]]} - click to open a link.</li>
 * </ul>
 *
 * <p>Two placeholders are replaced per page: {@code %page%} (current page number) and {@code %total%} (page count).
 * {@code {{anchor-id}}} is replaced with the page number that anchor starts on, which is what a table of contents
 * needs - it stays correct when sections are inserted or reordered.
 */
public final class HandbookRenderer {

    public static final int DEFAULT_LINES_PER_PAGE = 14;
    /**
     * Usable width of a book page in pixels (the client wraps at roughly 114).
     */
    public static final int DEFAULT_LINE_WIDTH = 114;
    /**
     * A written book cannot hold more pages than this.
     */
    public static final int MAX_PAGES = 100;

    private HandbookRenderer() {
    }

    /**
     * One section of a handbook, i.e. one entry of {@code pages:} in the YAML file.
     *
     * @param id   anchor name, may be {@code null}
     * @param text raw text, may contain {@code &} color codes, {@code [[label|target]]} links and newlines
     */
    public record Section(String id, String text) {
    }

    /**
     * @param pages    rendered pages, never empty
     * @param warnings problems found while rendering (unknown anchors, overflow, ...)
     */
    public record Result(List<Component> pages, List<String> warnings) {
    }

    public static Result render(List<Section> sections, int linesPerPage, int lineWidth) {
        int perPage = linesPerPage > 0 ? linesPerPage : DEFAULT_LINES_PER_PAGE;
        int width = lineWidth > 0 ? lineWidth : DEFAULT_LINE_WIDTH;
        // Pass 1: reserve three digits for page numbers just to learn how many pages the book has.
        Set<String> warnings = new LinkedHashSet<>();
        Layout first = layoutBook(sections, perPage, width, PAGE_NUMBER_WIDTH, warnings);
        int digits = Integer.toString(Math.min(first.pages().size(), MAX_PAGES)).length();

        // Pass 2: lay the book out again, reserving exactly the digits the page numbers need. Shrinking the
        // reservation can only remove line breaks, so this pass never needs more pages than the first one.
        Layout layout = layoutBook(sections, perPage, width, 6 * digits, warnings);
        List<List<List<Chunk>>> pages = new ArrayList<>(layout.pages());
        Map<String, Integer> anchors = layout.anchors();
        if (pages.size() > MAX_PAGES) {
            warnings.add("成书最多 " + MAX_PAGES + " 页，当前 " + pages.size() + " 页，多出的部分已被丢弃");
            pages = new ArrayList<>(pages.subList(0, MAX_PAGES));
        }

        // Pass 3: render, now that every anchor is known (a table of contents may point at later sections).
        List<Component> rendered = new ArrayList<>(pages.size());
        int total = pages.size();
        for (int i = 0; i < total; i++) {
            rendered.add(renderPage(pages.get(i), anchors, i + 1, total, warnings));
        }
        return new Result(rendered, new ArrayList<>(warnings));
    }

    private record Layout(List<List<List<Chunk>>> pages, Map<String, Integer> anchors) {
    }

    private static Layout layoutBook(List<Section> sections, int perPage, int width, int pageNumberWidth,
                                     Set<String> warnings) {
        Map<String, Integer> anchors = new LinkedHashMap<>();
        List<List<List<Chunk>>> pages = new ArrayList<>();
        for (Section section : sections) {
            if (section == null) {
                continue;
            }
            List<List<Chunk>> lines = layout(section.text(), width, pageNumberWidth);
            if (lines.isEmpty()) {
                lines.add(new ArrayList<>());
            }
            String id = section.id() == null ? null : section.id().trim();
            if (id != null && !id.isEmpty()) {
                if (anchors.put(id, pages.size() + 1) != null) {
                    warnings.add("重复的章节 id: " + id);
                }
            }
            for (int i = 0; i < lines.size(); i += perPage) {
                pages.add(lines.subList(i, Math.min(lines.size(), i + perPage)));
            }
        }
        if (pages.isEmpty()) {
            pages.add(new ArrayList<>());
        }
        return new Layout(pages, anchors);
    }

    // ------------------------------------------------------------------ layout

    private enum Kind { TEXT, LINK, PAGE_NUMBER, PLACEHOLDER }

    /**
     * @param target link target for {@link Kind#LINK}, anchor id for {@link Kind#PAGE_NUMBER},
     *               {@code null} otherwise
     */
    private record Chunk(String text, Kind kind, String target) {
        static Chunk text(String text) {
            return new Chunk(text, Kind.TEXT, null);
        }

        static Chunk link(String text, String target) {
            return new Chunk(text, Kind.LINK, target);
        }

        static Chunk pageNumber(String text, String anchor) {
            return new Chunk(text, Kind.PAGE_NUMBER, anchor);
        }

        static Chunk placeholder(String text) {
            return new Chunk(text, Kind.PLACEHOLDER, null);
        }
    }

    /**
     * {@code %page%} and {@code %total%} are their own chunk so that line wrapping cannot cut them in half.
     */
    private static final String[] PLACEHOLDERS = {"%page%", "%total%"};

    /**
     * Reserve the width of a three digit page number for {@code {{anchor}}} and {@code %page%}.
     */
    private static final int PAGE_NUMBER_WIDTH = 18;

    private static List<List<Chunk>> layout(String raw, int width, int pageNumberWidth) {
        List<List<Chunk>> lines = new ArrayList<>();
        if (raw == null) {
            return lines;
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] physicalLines = normalized.split("\n", -1);
        for (int i = 0; i < physicalLines.length; i++) {
            // A trailing newline in a YAML block scalar is an artefact, not an empty last line.
            if (i == physicalLines.length - 1 && physicalLines[i].isEmpty() && physicalLines.length > 1) {
                break;
            }
            lines.addAll(wrap(parseLine(physicalLines[i]), width, pageNumberWidth));
        }
        return lines;
    }

    private static List<Chunk> parseLine(String line) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            if (line.startsWith("[[", i)) {
                int end = line.indexOf("]]", i + 2);
                if (end > i) {
                    String body = line.substring(i + 2, end);
                    int bar = body.indexOf('|');
                    if (bar > 0) {
                        String label = body.substring(0, bar).trim();
                        String target = body.substring(bar + 1).trim();
                        if (!label.isEmpty() && !target.isEmpty()) {
                            if (buffer.length() > 0) {
                                chunks.add(Chunk.text(buffer.toString()));
                                buffer.setLength(0);
                            }
                            chunks.add(Chunk.link(label, target));
                            i = end + 2;
                            continue;
                        }
                    }
                }
            }
            if (line.startsWith("{{", i)) {
                int end = line.indexOf("}}", i + 2);
                if (end > i + 2) {
                    String anchor = line.substring(i + 2, end).trim();
                    if (!anchor.isEmpty() && !anchor.contains("{") && !anchor.contains("}")) {
                        if (buffer.length() > 0) {
                            chunks.add(Chunk.text(buffer.toString()));
                            buffer.setLength(0);
                        }
                        chunks.add(Chunk.pageNumber(line.substring(i, end + 2), anchor));
                        i = end + 2;
                        continue;
                    }
                }
            }
            boolean placeholder = false;
            for (String candidate : PLACEHOLDERS) {
                if (line.startsWith(candidate, i)) {
                    if (buffer.length() > 0) {
                        chunks.add(Chunk.text(buffer.toString()));
                        buffer.setLength(0);
                    }
                    chunks.add(Chunk.placeholder(candidate));
                    i += candidate.length();
                    placeholder = true;
                    break;
                }
            }
            if (placeholder) {
                continue;
            }
            buffer.append(line.charAt(i));
            i++;
        }
        if (buffer.length() > 0) {
            chunks.add(Chunk.text(buffer.toString()));
        }
        return chunks;
    }

    /**
     * Splits chunks into lines of at most {@code width} pixels. Link chunks are atomic: they are moved to the next
     * line rather than cut in half.
     */
    private static List<List<Chunk>> wrap(List<Chunk> chunks, int width, int pageNumberWidth) {
        List<List<Chunk>> lines = new ArrayList<>();
        List<Chunk> current = new ArrayList<>();
        int used = 0;
        for (Chunk chunk : chunks) {
            if (chunk.kind() != Kind.TEXT) {
                int chunkWidth = chunk.kind() == Kind.LINK ? measure(chunk.text()) : pageNumberWidth;
                if (used > 0 && used + chunkWidth > width) {
                    lines.add(current);
                    current = new ArrayList<>();
                    used = 0;
                }
                current.add(chunk);
                used += chunkWidth;
                continue;
            }
            String text = chunk.text();
            StringBuilder run = new StringBuilder();
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '&' && i + 1 < text.length() && isFormatCode(text.charAt(i + 1))) {
                    run.append(c).append(text.charAt(i + 1));
                    i += 2;
                    continue;
                }
                int charWidth = charWidth(c);
                if (used > 0 && used + charWidth > width) {
                    if (run.length() > 0) {
                        current.add(Chunk.text(run.toString()));
                        run.setLength(0);
                    }
                    lines.add(current);
                    current = new ArrayList<>();
                    used = 0;
                }
                run.append(c);
                used += charWidth;
                i++;
            }
            if (run.length() > 0) {
                current.add(Chunk.text(run.toString()));
            }
        }
        lines.add(current);
        return lines;
    }

    private static int measure(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length() && isFormatCode(text.charAt(i + 1))) {
                i++;
                continue;
            }
            width += charWidth(c);
        }
        return width;
    }

    private static boolean isFormatCode(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'k' && c <= 'o') || c == 'r'
            || (c >= 'A' && c <= 'F') || (c >= 'K' && c <= 'O') || c == 'R';
    }

    /**
     * Advance width of a single character in Minecraft's default font, in pixels.
     */
    private static int charWidth(char c) {
        if (c < 0x80) {
            return asciiWidth(c);
        }
        return isWide(c) ? 9 : 6;
    }

    private static int asciiWidth(char c) {
        return switch (c) {
            case ' ' -> 4;
            case '!', '.', ',', ':', ';', 'i', 'l', '|', '\'', '`' -> 2;
            case 'I', '[', ']', 't', 'f', 'k', '(', ')', '{', '}', '"', '*' -> 4;
            default -> 6;
        };
    }

    /**
     * CJK and other full-width characters advance 9 pixels in the unicode font.
     */
    private static boolean isWide(char c) {
        return (c >= 0x1100 && c <= 0x115F)
            || (c >= 0x2E80 && c <= 0xA4CF)
            || (c >= 0xAC00 && c <= 0xD7A3)
            || (c >= 0xF900 && c <= 0xFAFF)
            || (c >= 0xFE30 && c <= 0xFE4F)
            || (c >= 0xFF00 && c <= 0xFF60)
            || (c >= 0xFFE0 && c <= 0xFFE6);
    }

    // ----------------------------------------------------------------- render

    private static Component renderPage(List<List<Chunk>> lines, Map<String, Integer> anchors, int page, int total,
                                        Set<String> warnings) {
        PageWriter writer = new PageWriter(warnings);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                writer.newline();
            }
            for (Chunk chunk : lines.get(i)) {
                writer.write(chunk, anchors, page, total);
            }
        }
        return writer.build();
    }

    /**
     * Emits a page, turning {@code &} codes into styles and link chunks into click events.
     */
    private static final class PageWriter {
        private final Set<String> warnings;
        private Component result = Component.empty();
        private final StringBuilder run = new StringBuilder();
        private NamedTextColor color;
        private final EnumSet<TextDecoration> decorations = EnumSet.noneOf(TextDecoration.class);
        private ClickEvent click;
        private HoverEvent<?> hover;

        private PageWriter(Set<String> warnings) {
            this.warnings = warnings;
        }

        void newline() {
            flush();
            result = result.append(Component.newline());
        }

        void write(Chunk chunk, Map<String, Integer> anchors, int page, int total) {
            if (chunk.kind() == Kind.PLACEHOLDER) {
                flush();
                append(substitute(chunk.text(), page, total));
                flush();
                return;
            }
            if (chunk.kind() == Kind.PAGE_NUMBER) {
                flush();
                Integer anchorPage = anchors.get(chunk.target());
                if (anchorPage == null) {
                    warnings.add("页码引用指向不存在的章节: " + chunk.target());
                    append(chunk.text());
                } else {
                    append(Integer.toString(anchorPage));
                }
                flush();
                return;
            }
            String text = substitute(chunk.text(), page, total);
            if (chunk.kind() == Kind.LINK) {
                flush();
                Link link = linkFor(chunk.target(), anchors, total);
                click = link == null ? null : link.click();
                hover = link == null ? null : link.hover();
                append(text);
                flush();
                click = null;
                hover = null;
                return;
            }
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '&' && i + 1 < text.length() && isFormatCode(text.charAt(i + 1))) {
                    flush();
                    applyCode(text.charAt(i + 1));
                    i += 2;
                    continue;
                }
                run.append(c);
                i++;
            }
        }

        private static String substitute(String text, int page, int total) {
            return text.replace("%page%", Integer.toString(page)).replace("%total%", Integer.toString(total));
        }

        private void append(String text) {
            run.append(text);
        }

        private void applyCode(char code) {
            char lower = Character.toLowerCase(code);
            switch (lower) {
                case 'r' -> {
                    color = null;
                    decorations.clear();
                }
                case 'l' -> decorations.add(TextDecoration.BOLD);
                case 'm' -> decorations.add(TextDecoration.STRIKETHROUGH);
                case 'n' -> decorations.add(TextDecoration.UNDERLINED);
                case 'o' -> decorations.add(TextDecoration.ITALIC);
                case 'k' -> decorations.add(TextDecoration.OBFUSCATED);
                default -> {
                    NamedTextColor named = colorFor(lower);
                    if (named != null) {
                        // Vanilla: a color code also removes the formatting applied before it.
                        color = named;
                        decorations.clear();
                    }
                }
            }
        }

        private static NamedTextColor colorFor(char code) {
            return switch (code) {
                case '0' -> NamedTextColor.BLACK;
                case '1' -> NamedTextColor.DARK_BLUE;
                case '2' -> NamedTextColor.DARK_GREEN;
                case '3' -> NamedTextColor.DARK_AQUA;
                case '4' -> NamedTextColor.DARK_RED;
                case '5' -> NamedTextColor.DARK_PURPLE;
                case '6' -> NamedTextColor.GOLD;
                case '7' -> NamedTextColor.GRAY;
                case '8' -> NamedTextColor.DARK_GRAY;
                case '9' -> NamedTextColor.BLUE;
                case 'a' -> NamedTextColor.GREEN;
                case 'b' -> NamedTextColor.AQUA;
                case 'c' -> NamedTextColor.RED;
                case 'd' -> NamedTextColor.LIGHT_PURPLE;
                case 'e' -> NamedTextColor.YELLOW;
                case 'f' -> NamedTextColor.WHITE;
                default -> null;
            };
        }

        private record Link(ClickEvent click, HoverEvent<?> hover) {
        }

        private Link linkFor(String target, Map<String, Integer> anchors, int total) {
            String trimmed = target.trim();
            if (trimmed.regionMatches(true, 0, "cmd:", 0, 4)) {
                String command = trimmed.substring(4).trim();
                return command.isEmpty() ? null : new Link(ClickEvent.runCommand(command),
                    HoverEvent.showText(Component.text("点击执行 " + command, NamedTextColor.GRAY)));
            }
            if (trimmed.regionMatches(true, 0, "url:", 0, 4)) {
                String url = trimmed.substring(4).trim();
                return url.isEmpty() ? null : new Link(ClickEvent.openUrl(url),
                    HoverEvent.showText(Component.text("点击打开链接", NamedTextColor.GRAY)));
            }
            int page;
            if (trimmed.matches("[0-9]+")) {
                page = Integer.parseInt(trimmed);
                if (page < 1 || page > total) {
                    warnings.add("跳转页码超出范围: " + trimmed + "（共 " + total + " 页）");
                    return null;
                }
            } else {
                Integer anchor = anchors.get(trimmed);
                if (anchor == null) {
                    warnings.add("未知的跳转章节: " + trimmed);
                    return null;
                }
                page = anchor;
            }
            return new Link(ClickEvent.changePage(page),
                HoverEvent.showText(Component.text("点击跳到第 " + page + " 页", NamedTextColor.GRAY)));
        }

        private void flush() {
            if (run.length() == 0) {
                return;
            }
            Component piece = color != null ? Component.text(run.toString(), color) : Component.text(run.toString());
            for (TextDecoration decoration : decorations) {
                piece = piece.decorate(decoration);
            }
            if (click != null) {
                piece = piece.clickEvent(click);
            }
            if (hover != null) {
                piece = piece.hoverEvent(hover);
            }
            result = result.append(piece);
            run.setLength(0);
        }

        Component build() {
            flush();
            return result;
        }
    }
}
