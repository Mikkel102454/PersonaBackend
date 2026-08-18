package nu.miguel.personabackend.reference;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.personabackend.document.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public final class ProjectReferenceService {
    private static final int MAX_FILES = 2_048;
    private static final long MAX_BYTES = 10L * 1_024 * 1_024;
    private static final Map<String, String> REFERENCE_KEYS = Map.of(
            "behavior", "behavior", "shared-behavior", "behavior", "player-behavior", "behavior",
            "dialogue", "dialogue", "quest", "quest", "npc", "npc", "script", "script");
    private final YamlDocumentService documents;

    public ProjectReferenceService(YamlDocumentService documents) { this.documents = documents; }

    public ProjectReferenceGraph analyze(List<ContentFile> files) {
        List<Parsed> parsed = parse(files);
        List<ProjectDeclaration> declarations = new ArrayList<>();
        for (Parsed file : parsed) declarations.addAll(declarations(file));
        Set<String> targets = new HashSet<>();
        declarations.forEach(item -> targets.add(item.type() + "\0" + item.id()));
        List<ProjectReference> references = new ArrayList<>();
        for (Parsed file : parsed) {
            Owner owner = owner(file, declarations);
            collectReferences(file, file.model().root(), owner, targets, references);
        }
        declarations.sort(Comparator.comparing(ProjectDeclaration::type).thenComparing(ProjectDeclaration::id)
                .thenComparing(ProjectDeclaration::path));
        references.sort(Comparator.comparing(ProjectReference::targetType).thenComparing(ProjectReference::targetId)
                .thenComparing(ProjectReference::path).thenComparingInt(ProjectReference::line));
        return new ProjectReferenceGraph(declarations, references);
    }

    public RenamePreview preview(RenamePreviewRequest request) {
        if (request == null || !validType(request.type()) || !validId(request.currentId()) || !validId(request.replacementId()))
            throw bad("Invalid rename preview request");
        ProjectReferenceGraph graph = analyze(request.files());
        List<String> conflicts = new ArrayList<>();
        List<ProjectDeclaration> source = graph.declarations().stream()
                .filter(item -> item.type().equals(request.type()) && item.id().equals(request.currentId())).toList();
        if (source.isEmpty()) conflicts.add("The source ID is not declared in this project.");
        if (graph.declarations().stream().anyMatch(item -> item.type().equals(request.type())
                && item.id().equals(request.replacementId()))) conflicts.add("The replacement ID is already declared.");
        List<RenameOccurrence> occurrences = new ArrayList<>();
        source.forEach(item -> occurrences.add(new RenameOccurrence(item.path(), declarationPath(item.type(), item.id()),
                item.line(), item.column(), "declaration")));
        graph.references().stream().filter(item -> item.targetType().equals(request.type())
                        && item.targetId().equals(request.currentId()))
                .forEach(item -> occurrences.add(new RenameOccurrence(item.path(), item.yamlPath(), item.line(),
                        item.column(), "reference")));
        occurrences.sort(Comparator.comparing(RenameOccurrence::path).thenComparingInt(RenameOccurrence::line));
        return new RenamePreview(request.type(), request.currentId(), request.replacementId(), conflicts.isEmpty(),
                conflicts, occurrences);
    }

    private List<Parsed> parse(List<ContentFile> input) {
        if (input == null || input.size() > MAX_FILES) throw bad("Project exceeds the file limit");
        long bytes = 0; Set<String> paths = new HashSet<>(); List<Parsed> result = new ArrayList<>();
        for (ContentFile file : input) {
            if (file == null || file.path() == null || file.content() == null || !validPath(file.path())
                    || !paths.add(file.path())) throw bad("Project contains an invalid or duplicate path");
            bytes += file.content().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_BYTES) throw bad("Project exceeds 10 MiB");
            YamlDocumentResponse model = documents.parse(file.content());
            if (!model.valid()) throw bad("Cannot analyze invalid YAML in " + file.path());
            result.add(new Parsed(file.path(), model));
        }
        return result;
    }

    private static List<ProjectDeclaration> declarations(Parsed file) {
        String type = type(file.path());
        if (type == null || file.model().root() == null) return List.of();
        YamlDocumentNode id = child(file.model().root(), "id");
        return id != null && "string".equals(id.kind()) && validId(id.value())
                ? List.of(new ProjectDeclaration(type, id.value(), file.path(), id.startLine(), id.startColumn())) : List.of();
    }

    private static void collectReferences(Parsed file, YamlDocumentNode node, Owner owner, Set<String> targets,
                                          List<ProjectReference> output) {
        if (node == null) return;
        Owner effectiveOwner = owner;
        String targetType = node.key() == null ? null : REFERENCE_KEYS.get(node.key());
        if (targetType == null && "id".equals(node.key()) && file.path().startsWith("npcs/")
                && node.path().matches("/dialogues/\\d+/id")) targetType = "dialogue";
        if (targetType != null && "string".equals(node.kind()) && validId(node.value())) {
            output.add(new ProjectReference(effectiveOwner.type(), effectiveOwner.id(), targetType, node.value(), file.path(), node.path(),
                    node.startLine(), node.startColumn(), targets.contains(targetType + "\0" + node.value())));
        }
        if("mapping".equals(node.kind())){YamlDocumentNode nodeType=child(node,"type"),valueType=child(node,"value-type"),value=child(node,"value");if(nodeType!=null&&"value".equals(nodeType.value())&&valueType!=null&&value!=null&&validType(valueType.value())&&validId(value.value()))output.add(new ProjectReference(effectiveOwner.type(),effectiveOwner.id(),valueType.value(),value.value(),file.path(),value.path(),value.startLine(),value.startColumn(),targets.contains(valueType.value()+"\0"+value.value())));YamlDocumentNode parameterType=child(node,"type"),defaultValue=child(node,"default");if(defaultValue!=null&&parameterType!=null&&validType(parameterType.value())&&validId(defaultValue.value()))output.add(new ProjectReference(effectiveOwner.type(),effectiveOwner.id(),parameterType.value(),defaultValue.value(),file.path(),defaultValue.path(),defaultValue.startLine(),defaultValue.startColumn(),targets.contains(parameterType.value()+"\0"+defaultValue.value())));}
        for (YamlDocumentNode child : node.children()) collectReferences(file, child, effectiveOwner, targets, output);
    }

    private static Owner owner(Parsed file, List<ProjectDeclaration> declarations) {
        ProjectDeclaration declaration = declarations.stream().filter(item -> item.path().equals(file.path())).findFirst().orElse(null);
        return declaration == null ? new Owner(type(file.path()) == null ? "file" : type(file.path()), file.path())
                : new Owner(declaration.type(), declaration.id());
    }
    private static YamlDocumentNode child(YamlDocumentNode node, String key) {
        return node.children().stream().filter(item -> key.equals(item.key())).findFirst().orElse(null);
    }
    private static String type(String path) {
        if (path.startsWith("scripts/")) return "script";
        for (String type : List.of("behavior", "npc", "dialogue", "quest"))
            if (path.startsWith(type + "s/")) return type;
        return null;
    }
    private static String declarationPath(String type, String id) { return "/id"; }
    private static String unescape(String value) { return value.replace("~1", "/").replace("~0", "~"); }
    private static boolean validType(String value) { return Set.of("behavior", "npc", "dialogue", "quest", "script").contains(value); }
    private static boolean validId(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"); }
    private static boolean validPath(String path) {
        return !path.isBlank() && !path.startsWith("/") && !path.contains("\\") && !path.contains("\0")
                && Arrays.stream(path.split("/")).noneMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))
                && (path.endsWith(".yml") || path.endsWith(".yaml"));
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private record Parsed(String path, YamlDocumentResponse model) {}
    private record Owner(String type, String id) {}
}
