package com.md2docu.util;

public final class MarkdownText {

    private MarkdownText() {}

    public static String escapeText(String text) {
        StringBuilder escaped = new StringBuilder();
        int start = 0, end = text.length();
        while (start < end && text.charAt(start) == ' ') start++;
        while (end > start && text.charAt(end - 1) == ' ') end--;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' && (i < start || i >= end)) escaped.append("&#32;");
            else if (c == '\t') escaped.append("&#9;");
            else if (c == '\n' || c == '\r') escaped.append("<br />");
            else if (c != ' ' && Character.isWhitespace(c)) escaped.append("&#").append((int) c).append(';');
            else if (c == ':' || c == '.' || c == '@') escaped.append("&#").append((int) c).append(';');
            else {
                if ("\\`*_{}[]<>()#+-.!|~&".indexOf(c) >= 0) escaped.append('\\');
                escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
