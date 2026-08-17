package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.persona.editor.protocol.ContentProjectRevision;
import nu.miguel.personabackend.document.*;
import nu.miguel.personabackend.reference.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import java.util.*;

import static nu.miguel.personabackend.project.ProjectContentRules.bad;

@Service
public final class ProjectOperationService {
    private final ProjectContentRules rules;
    private final ProjectReferenceService references;
    private final YamlDocumentService documents;

    public ProjectOperationService(ProjectContentRules rules, ProjectReferenceService references,
                                   YamlDocumentService documents) {
        this.rules = rules;
        this.references = references;
        this.documents = documents;
    }

    public ProjectOperationResponse create(ProjectCreateRequest request) {
        if (request == null) throw bad("MISSING_REQUEST", "Missing create request");
        rules.requireKindAndId(request.kind(), request.id());
        rules.requireRequestedPath(request.kind(), request.id(), request.path());
        var project = verifyProject(request.files(), request.expectedRevision());
        TreeMap<String, ContentFile> candidate = copy(project);
        if (request.kind().equals("script")) createScript(candidate, request.id());
        else {
            if (candidate.containsKey(request.path())) throw bad("DUPLICATE_PATH", "A file already uses the requested path");
            ensureIdAvailable(candidate, request.kind(), request.id());
            candidate.put(request.path(), rules.file(request.path(), templateContent(request.kind(), request.id(), request.template())));
        }
        return finish(candidate, List.of(request.path()), List.of());
    }

    public ProjectOperationResponse duplicate(ProjectDuplicateRequest request) {
        if (request == null) throw bad("MISSING_REQUEST", "Missing duplicate request");
        rules.requireKindAndId(request.kind(), request.sourceId());
        rules.requireKindAndId(request.kind(), request.replacementId());
        rules.requireRequestedPath(request.kind(), request.replacementId(), request.replacementPath());
        var project = verifyProject(request.files(), request.expectedRevision());
        TreeMap<String, ContentFile> candidate = copy(project);
        ensureIdAvailable(candidate, request.kind(), request.replacementId());
        ProjectDeclaration source = declaration(candidate, request.kind(), request.sourceId());
        if (request.kind().equals("script")) {
            ContentFile scripts = candidate.get(source.path());
            YamlDocumentResponse model = documents.parse(scripts.content());
            YamlDocumentNode node = find(model.root(), declarationPath("script", request.sourceId()));
            String value = yamlValue(scripts.content(), node);
            YamlDocumentResponse updated = documents.insertField(new YamlMappingInsertRequest(scripts.content(),
                    "/scripts", request.replacementId(), value));
            candidate.put(source.path(), rules.file(source.path(), updated.content()));
            return finish(candidate, List.of(source.path()), List.of("References in the copy still point to the original resources."));
        }
        if (candidate.containsKey(request.replacementPath())) throw bad("DUPLICATE_PATH", "A file already uses the requested path");
        ContentFile sourceFile = candidate.get(source.path());
        String content = documents.edit(new YamlEditRequest(sourceFile.content(), "/id", request.replacementId())).content();
        candidate.put(request.replacementPath(), rules.file(request.replacementPath(), content));
        return finish(candidate, List.of(request.replacementPath()),
                List.of("References in the copy still point to the original resources."));
    }

    public ProjectOperationResponse rename(ProjectRenameApplyRequest request) {
        if (request == null) throw bad("MISSING_REQUEST", "Missing rename request");
        rules.requireKindAndId(request.kind(), request.currentId());
        rules.requireKindAndId(request.kind(), request.replacementId());
        var project = verifyProject(request.files(), request.expectedRevision());
        TreeMap<String, ContentFile> candidate = copy(project);
        RenamePreview preview = references.preview(new RenamePreviewRequest(List.copyOf(candidate.values()),
                request.kind(), request.currentId(), request.replacementId()));
        if (!preview.safe()) throw bad("RENAME_CONFLICT", String.join(" ", preview.conflicts()));

        Map<String, List<RenameOccurrence>> byFile = new TreeMap<>();
        preview.occurrences().forEach(item -> byFile.computeIfAbsent(item.path(), ignored -> new ArrayList<>()).add(item));
        for (var entry : byFile.entrySet()) {
            ContentFile file = candidate.get(entry.getKey());
            String content = file.content();
            for (RenameOccurrence occurrence : entry.getValue()) {
                content = occurrence.role().equals("declaration") && request.kind().equals("script")
                        ? documents.renameMappingKey(content, occurrence.yamlPath(), request.replacementId()).content()
                        : documents.edit(new YamlEditRequest(content, occurrence.yamlPath(), request.replacementId())).content();
            }
            candidate.put(file.path(), rules.file(file.path(), content));
        }

        List<String> affected = new ArrayList<>(byFile.keySet());
        if (request.renameFile() && !request.kind().equals("script")) {
            ProjectDeclaration source = declaration(candidate, request.kind(), request.replacementId());
            rules.requireRequestedPath(request.kind(), request.replacementId(), request.replacementPath());
            if (!source.path().equals(request.replacementPath()) && candidate.containsKey(request.replacementPath()))
                throw bad("DUPLICATE_PATH", "A file already uses the replacement path");
            ContentFile moved = candidate.remove(source.path());
            candidate.put(request.replacementPath(), rules.file(request.replacementPath(), moved.content()));
            affected.add(request.replacementPath());
        }
        return finish(candidate, affected, List.of());
    }

    public ProjectOperationResponse delete(ProjectDeleteRequest request) {
        if (request == null) throw bad("MISSING_REQUEST", "Missing delete request");
        rules.requireKindAndId(request.kind(), request.id());
        var project = verifyProject(request.files(), request.expectedRevision());
        TreeMap<String, ContentFile> candidate = copy(project);
        ProjectReferenceGraph graph = references.analyze(List.copyOf(candidate.values()));
        List<ProjectReference> inbound = graph.references().stream()
                .filter(item -> item.targetType().equals(request.kind()) && item.targetId().equals(request.id())).toList();
        if (!inbound.isEmpty()) throw bad("INBOUND_REFERENCES", "Delete is blocked by " + inbound.size() + " inbound reference(s)");
        ProjectDeclaration source = declaration(candidate, request.kind(), request.id());
        if (request.kind().equals("script")) {
            ContentFile file = candidate.get(source.path());
            String path = declarationPath("script", request.id());
            String content = documents.structure(new YamlStructureRequest(file.content(),
                    YamlStructureRequest.Operation.DELETE, path, null)).content();
            candidate.put(file.path(), rules.file(file.path(), content));
        } else candidate.remove(source.path());
        return finish(candidate, List.of(source.path()), List.of());
    }

    public ProjectOperationResponse move(ProjectMoveRequest request) {
        if (request == null) throw bad("MISSING_REQUEST", "Missing move request");
        rules.requireKindAndId(request.kind(), request.id());
        if (request.kind().equals("script"))
            throw bad("UNSUPPORTED_MOVE", "Reusable scripts remain in scripts.yml");
        rules.requireRequestedPath(request.kind(), request.id(), request.replacementPath());
        var project = verifyProject(request.files(), request.expectedRevision());
        TreeMap<String, ContentFile> candidate = copy(project);
        ProjectDeclaration source = declaration(candidate, request.kind(), request.id());
        if (source.path().equals(request.replacementPath()))
            return finish(candidate, List.of(), List.of("Resource already uses its canonical path."));
        if (candidate.containsKey(request.replacementPath()))
            throw bad("DUPLICATE_PATH", "A file already uses the replacement path");
        ContentFile moved = candidate.remove(source.path());
        candidate.put(request.replacementPath(), rules.file(request.replacementPath(), moved.content()));
        return finish(candidate, List.of(source.path(), request.replacementPath()), List.of());
    }

    public ProjectOperationResponse extractScript(ProjectExtractScriptRequest request) {
        if (request == null) throw bad("MISSING_REQUEST", "Missing reusable-script extraction request");
        rules.requireKindAndId("script", request.scriptId());
        var project = verifyProject(request.files(), request.expectedRevision());
        TreeMap<String, ContentFile> candidate = copy(project);
        ensureIdAvailable(candidate, "script", request.scriptId());
        ContentFile sourceFile = candidate.get(request.sourcePath());
        if (sourceFile == null || request.sourceYamlPath() == null || request.sourceYamlPath().length() > 2_048
                || !request.sourceYamlPath().startsWith("/"))
            throw bad("INVALID_EXTRACT_SOURCE", "The extraction source is not an authoritative project YAML path");
        YamlDocumentResponse sourceModel = documents.parse(sourceFile.content());
        YamlDocumentNode selected = find(sourceModel.root(), request.sourceYamlPath());
        YamlDocumentNode parent = selected == null ? null : find(sourceModel.root(), parentPath(request.sourceYamlPath()));
        if (selected == null || parent == null || !"sequence".equals(parent.kind()))
            throw bad("INVALID_EXTRACT_SOURCE", "Only a complete script command can be extracted");
        String exactScript = yamlValue(sourceFile.content(), selected);
        String replaced = documents.replaceSequenceItem(sourceFile.content(), request.sourceYamlPath(),
                "- type: run-script\n  script: " + request.scriptId()).content();
        candidate.put(sourceFile.path(), rules.file(sourceFile.path(), replaced));

        ContentFile scripts = candidate.get("scripts.yml");
        if (scripts == null) candidate.put("scripts.yml", rules.file("scripts.yml",
                "scripts:\n  " + request.scriptId() + ":\n" + indent(exactScript, 4)));
        else {
            YamlDocumentResponse updated = documents.insertField(new YamlMappingInsertRequest(
                    scripts.content(), "/scripts", request.scriptId(), exactScript));
            candidate.put("scripts.yml", rules.file("scripts.yml", updated.content()));
        }
        return finish(candidate, List.of(sourceFile.path(), "scripts.yml"), List.of());
    }

    public ProjectOperationResponse createAndAssign(ProjectCreateAndAssignRequest request) {
        if (request == null || !Set.of("npc-player-behavior", "npc-shared-behavior", "npc-dialogue", "typed-reference")
                .contains(request.assignment()))
            throw bad("INVALID_ASSIGNMENT", "Unsupported create-and-assign destination");
        String targetKind = request.assignment().equals("typed-reference") ? request.targetKind()
                : request.assignment().equals("npc-dialogue") ? "dialogue" : "behavior";
        rules.requireKindAndId(targetKind, request.targetId());
        var project = verifyProject(request.files(), request.expectedRevision());
        TreeMap<String, ContentFile> candidate = copy(project);
        ensureIdAvailable(candidate, targetKind, request.targetId());
        ContentFile source = candidate.get(request.sourcePath());
        ProjectDeclaration owner = source == null ? null : references.analyze(List.copyOf(candidate.values())).declarations().stream()
                .filter(item -> item.path().equals(request.sourcePath())).findFirst().orElse(null);
        if (source == null || owner == null || !request.assignment().equals("typed-reference") && !owner.type().equals("npc"))
            throw bad("INVALID_ASSIGNMENT_SOURCE", "Create-and-assign requires an authoritative source resource");

        YamlDocumentResponse model = documents.parse(source.content());
        String updated;
        if (request.assignment().equals("typed-reference")) {
            if (request.sourceYamlPath() == null || request.sourceYamlPath().length() > 2_048
                    || !request.sourceYamlPath().startsWith("/"))
                throw bad("INVALID_ASSIGNMENT_SOURCE", "Typed create-and-assign requires a bounded YAML path");
            YamlDocumentNode reference = find(model.root(), request.sourceYamlPath());
            if (reference == null || !reference.editable() || !reference.children().isEmpty())
                throw bad("INVALID_ASSIGNMENT_SOURCE", "Typed create-and-assign must target an editable scalar reference");
            updated = Objects.equals(reference.value(), request.targetId()) ? source.content()
                    : documents.edit(new YamlEditRequest(source.content(), reference.path(), request.targetId())).content();
        } else if (targetKind.equals("behavior")) {
            String key = request.assignment().equals("npc-player-behavior") ? "player-behavior" : "shared-behavior";
            YamlDocumentNode existing = find(model.root(), "/" + key);
            updated = existing == null
                    ? documents.insertField(new YamlMappingInsertRequest(source.content(), "", key, request.targetId())).content()
                    : documents.edit(new YamlEditRequest(source.content(), existing.path(), request.targetId())).content();
        } else {
            YamlDocumentNode dialogues = find(model.root(), "/dialogues");
            updated = dialogues == null
                    ? documents.insertField(new YamlMappingInsertRequest(source.content(), "", "dialogues", "- id: " + request.targetId())).content()
                    : documents.insertSequenceItem(source.content(), "/dialogues", "- id: " + request.targetId(), null).content();
        }
        candidate.put(source.path(), rules.file(source.path(), updated));
        String targetPath = rules.safePath(targetKind, request.targetId());
        if (targetKind.equals("script")) createScript(candidate, request.targetId());
        else {
            if (candidate.containsKey(targetPath)) throw bad("DUPLICATE_PATH", "A file already uses the target's safe path");
            candidate.put(targetPath, rules.file(targetPath, templateContent(targetKind, request.targetId(), "minimal")));
        }
        return finish(candidate, List.of(source.path(), targetPath), List.of());
    }

    public SafePathResponse safePath(String kind, String id) {
        return new SafePathResponse(kind, id, rules.safePath(kind, id));
    }

    public ProjectTemplateResponse template(String kind, String id, String requested) {
        rules.requireKindAndId(kind, id);
        String path = rules.safePath(kind, id);
        String content = kind.equals("script") ? "scripts:\n  " + id + ":\n    - type: stop\n"
                : templateContent(kind, id, requested);
        return new ProjectTemplateResponse(kind, id, path, content);
    }

    private void createScript(TreeMap<String, ContentFile> candidate, String id) {
        ensureIdAvailable(candidate, "script", id);
        ContentFile scripts = candidate.get("scripts.yml");
        if (scripts == null) {
            String content = "scripts:\n  " + id + ":\n    - type: stop\n";
            candidate.put("scripts.yml", rules.file("scripts.yml", content));
            return;
        }
        YamlDocumentResponse updated = documents.insertField(new YamlMappingInsertRequest(
                scripts.content(), "/scripts", id, "- type: stop\n"));
        candidate.put("scripts.yml", rules.file("scripts.yml", updated.content()));
    }

    private String templateContent(String kind, String id, String requested) {
        if (requested != null && !requested.isBlank() && !requested.equals("minimal"))
            throw bad("INVALID_TEMPLATE", "Unknown creation template");
        return switch (kind) {
            case "behavior" -> "id: " + id + "\nscope: player\nroot:\n  id: root\n  type: sequence\n  children: []\n";
            case "dialogue" -> "id: " + id + "\nstart: start\nnodes:\n  start:\n    script:\n      - type: say\n        text: \"New dialogue line\"\n      - type: end-dialogue\n";
            case "quest" -> "id: " + id + "\ntitle: \"New quest\"\nphases:\n  - id: start\n    objectives:\n      - id: begin\n        type: wait\n        duration: 1s\n";
            case "npc" -> "id: " + id + "\ndisplay-name: \"New NPC\"\n";
            default -> throw bad("INVALID_KIND", "Unsupported content kind");
        };
    }

    private ProjectOperationResponse finish(TreeMap<String, ContentFile> candidate, List<String> affected,
                                            List<String> warnings) {
        List<ContentFile> files = List.copyOf(candidate.values());
        String revision = ContentProjectRevision.compute(files);
        rules.verify(files, revision);
        requireValidYaml(files);
        references.analyze(files);
        return new ProjectOperationResponse(revision, files, affected.stream().distinct().sorted().toList(), warnings);
    }

    private ProjectContentRules.VerifiedProject verifyProject(List<ContentFile> files, String expectedRevision) {
        ProjectContentRules.VerifiedProject project = rules.verify(files, expectedRevision);
        requireValidYaml(List.copyOf(project.files().values()));
        return project;
    }

    private void requireValidYaml(List<ContentFile> files) {
        for (ContentFile file : files) {
            YamlDocumentResponse parsed = documents.parse(file.content());
            if (!parsed.valid()) {
                YamlDiagnostic issue = parsed.diagnostics().isEmpty() ? null : parsed.diagnostics().getFirst();
                throw new ProjectOperationException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PROJECT_YAML",
                        issue == null ? "The candidate project contains invalid YAML" : issue.message(),
                        file.path(), null);
            }
        }
    }

    private void ensureIdAvailable(TreeMap<String, ContentFile> candidate, String kind, String id) {
        boolean exists = references.analyze(List.copyOf(candidate.values())).declarations().stream()
                .anyMatch(item -> item.type().equals(kind) && item.id().equals(id));
        if (exists) throw bad("DUPLICATE_ID", "The content ID is already declared");
    }

    private ProjectDeclaration declaration(TreeMap<String, ContentFile> candidate, String kind, String id) {
        return references.analyze(List.copyOf(candidate.values())).declarations().stream()
                .filter(item -> item.type().equals(kind) && item.id().equals(id)).findFirst()
                .orElseThrow(() -> bad("MISSING_RESOURCE", "The requested resource is not declared"));
    }

    private static TreeMap<String, ContentFile> copy(ProjectContentRules.VerifiedProject project) {
        return new TreeMap<>(project.files());
    }
    private static String declarationPath(String kind, String id) {
        return kind.equals("script") ? "/scripts/" + escape(id) : "/id";
    }
    private static String parentPath(String path) {
        int slash = path == null ? -1 : path.lastIndexOf('/'); return slash <= 0 ? "" : path.substring(0, slash);
    }
    private static String indent(String value, int spaces) {
        String prefix = " ".repeat(spaces);
        return prefix + value.replace("\n", "\n" + prefix).stripTrailing() + "\n";
    }
    private static String escape(String value) { return value.replace("~", "~0").replace("/", "~1"); }
    private static YamlDocumentNode find(YamlDocumentNode node, String path) {
        if (node == null) return null;
        if (node.path().equals(path)) return node;
        for (YamlDocumentNode child : node.children()) {
            YamlDocumentNode found = find(child, path);
            if (found != null) return found;
        }
        return null;
    }
    private static String yamlValue(String content, YamlDocumentNode node) {
        if (node == null) throw bad("UNSUPPORTED_YAML", "The reusable script cannot be copied safely");
        String value = content.substring(node.startOffset(), node.endOffset());
        int indent = Math.max(0, node.startColumn() - 1);
        String prefix = " ".repeat(indent);
        String[] lines = value.split("(?<=\\n)", -1);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            result.append(index == 0 || !line.startsWith(prefix) ? line : line.substring(indent));
        }
        return result.toString();
    }

    public record SafePathResponse(String kind, String id, String path) {}
    public record ProjectTemplateResponse(String kind, String id, String path, String content) {}
}
