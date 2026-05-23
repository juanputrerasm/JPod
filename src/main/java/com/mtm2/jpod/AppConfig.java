package com.mtm2.jpod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persisted application config stored as JSON in the user's preferences space.
 */
public record AppConfig(List<Path> recentOpenedFiles) {
    private static final Pattern STRING_ARRAY_VALUE = Pattern.compile("\"((?:\\\\.|[^\"])*)\"");

    public static AppConfig defaults() {
        return new AppConfig(List.of());
    }

    public static Path configPath() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, "JPod", "config.json");
            }
            return Path.of(home, "AppData", "Roaming", "JPod", "config.json");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "JPod", "config.json");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "JPod", "config.json");
        }
        return Path.of(home, ".config", "JPod", "config.json");
    }

    public static AppConfig load() {
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            return defaults();
        }
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException ex) {
            return defaults();
        }
    }

    public void save() throws IOException {
        Path path = configPath();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, toJson(), StandardCharsets.UTF_8);
    }

    public AppConfig withRecentOpenedFile(Path path) {
        if (path == null) {
            return this;
        }
        List<Path> updated = new ArrayList<>();
        updated.add(path.toAbsolutePath().normalize());
        for (Path existing : recentOpenedFiles) {
            if (existing == null) {
                continue;
            }
            Path normalized = existing.toAbsolutePath().normalize();
            if (!normalized.equals(updated.get(0))) {
                updated.add(normalized);
            }
            if (updated.size() == 10) {
                break;
            }
        }
        return new AppConfig(List.copyOf(updated));
    }

    public AppConfig withoutRecentOpenedFile(Path path) {
        if (path == null) {
            return this;
        }
        Path target = path.toAbsolutePath().normalize();
        List<Path> updated = new ArrayList<>();
        for (Path existing : recentOpenedFiles) {
            if (existing == null) {
                continue;
            }
            Path normalized = existing.toAbsolutePath().normalize();
            if (!normalized.equals(target)) {
                updated.add(normalized);
            }
        }
        return new AppConfig(List.copyOf(updated));
    }

    private static AppConfig parse(String json) {
        return new AppConfig(parsePathArray(json, "recentOpenedFiles"));
    }

    private String toJson() {
        return "{\n"
                + "  \"recentOpenedFiles\": " + jsonPathArray(recentOpenedFiles) + "\n"
                + "}\n";
    }

    private static List<Path> parsePathArray(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        if (keyIndex < 0) {
            return List.of();
        }
        int arrayStart = json.indexOf('[', keyIndex);
        if (arrayStart < 0) {
            return List.of();
        }
        int depth = 0;
        for (int i = arrayStart; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth--;
                if (depth == 0) {
                    String body = json.substring(arrayStart + 1, i);
                    Matcher matcher = STRING_ARRAY_VALUE.matcher(body);
                    List<Path> values = new ArrayList<>();
                    while (matcher.find()) {
                        values.add(Path.of(unescapeJson(matcher.group(1))));
                    }
                    return List.copyOf(values);
                }
            }
        }
        return List.of();
    }

    private static String jsonPathArray(List<Path> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(jsonString(values.get(i) != null ? values.get(i).toString() : null));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + escapeJson(value) + "\"";
    }

    private static String escapeJson(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String unescapeJson(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        boolean escaping = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaping) {
                switch (ch) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '\\', '"' -> sb.append(ch);
                    default -> sb.append(ch);
                }
                escaping = false;
            } else if (ch == '\\') {
                escaping = true;
            } else {
                sb.append(ch);
            }
        }
        if (escaping) {
            sb.append('\\');
        }
        return sb.toString();
    }
}
