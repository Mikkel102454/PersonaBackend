package nu.miguel.personabackend.diff;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.personabackend.document.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public final class SemanticDiffService {
    private static final int MAX_FILES = 1_024;
    private static final int MAX_CHANGES = 20_000;
    private static final long MAX_BYTES = 10L * 1_024 * 1_024;
    private final YamlDocumentService documents;
    public SemanticDiffService(YamlDocumentService documents) { this.documents = documents; }

    public SemanticDiffResponse compare(SemanticDiffRequest request) {
        if (request == null) throw bad("Missing semantic diff request");
        Map<String, ContentFile> before = files(request.before()), after = files(request.after());
        TreeSet<String> paths = new TreeSet<>(before.keySet()); paths.addAll(after.keySet());
        List<SemanticDiffEntry> changes = new ArrayList<>();
        for (String path : paths) {
            ContentFile left = before.get(path), right = after.get(path); String category = category(path);
            if (left == null) { changes.add(new SemanticDiffEntry(category, path, "", "FILE_ADDED", null, null, null, null)); continue; }
            if (right == null) { changes.add(new SemanticDiffEntry(category, path, "", "FILE_REMOVED", null, null, null, null)); continue; }
            if (left.content().equals(right.content())) continue;
            Map<String, Value> leftValues = flatten(path, left.content()), rightValues = flatten(path, right.content());
            TreeSet<String> yamlPaths = new TreeSet<>(leftValues.keySet()); yamlPaths.addAll(rightValues.keySet());
            for (String yamlPath : yamlPaths) {
                Value a = leftValues.get(yamlPath), b = rightValues.get(yamlPath);
                if (Objects.equals(a, b)) continue;
                changes.add(new SemanticDiffEntry(category, path, yamlPath,
                        a == null ? "ADDED" : b == null ? "REMOVED" : "CHANGED",
                        a == null ? null : a.kind(), a == null ? null : a.value(),
                        b == null ? null : b.kind(), b == null ? null : b.value()));
                if (changes.size() > MAX_CHANGES) throw bad("Semantic diff exceeds 20000 changes");
            }
        }
        return new SemanticDiffResponse(changes);
    }

    private Map<String, ContentFile> files(List<ContentFile> input) {
        if (input.size() > MAX_FILES) throw bad("Project exceeds the file limit");
        Map<String, ContentFile> result = new TreeMap<>(); long bytes = 0;
        for (ContentFile file : input) {
            if (file == null || file.path() == null || file.content() == null || !validPath(file.path())
                    || result.putIfAbsent(file.path(), file) != null) throw bad("Project contains an invalid or duplicate path");
            bytes += file.content().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_BYTES) throw bad("Project exceeds 10 MiB");
        }
        return result;
    }
    private Map<String, Value> flatten(String file, String content) {
        YamlDocumentResponse model = documents.parse(content);
        if (!model.valid()) throw bad("Cannot create semantic diff for invalid YAML in " + file);
        Map<String, Value> values = new TreeMap<>(); collect(model.root(), values); return values;
    }
    private static void collect(YamlDocumentNode node, Map<String, Value> output) {
        if (node == null) return;
        if (node.children().isEmpty()) output.put(node.path(), new Value(node.kind(), bounded(node.value())));
        else for (YamlDocumentNode child : node.children()) collect(child, output);
    }
    private static String bounded(String value) {
        if (value == null) return null;
        return value.length() <= 256 ? value : value.substring(0, 256) + "…";
    }
    private static String category(String path) {
        if (path.equals("scripts.yml")) return "script";
        for (String category : List.of("behavior", "npc", "dialogue", "quest"))
            if (path.startsWith(category + "s/")) return category;
        return "content";
    }
    private static boolean validPath(String path) {
        return !path.isBlank() && !path.startsWith("/") && !path.contains("\\") && !path.contains("\0")
                && Arrays.stream(path.split("/")).noneMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))
                && (path.endsWith(".yml") || path.endsWith(".yaml"));
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private record Value(String kind, String value) {}
}
