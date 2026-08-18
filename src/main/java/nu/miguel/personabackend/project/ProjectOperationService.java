package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.persona.editor.protocol.ContentProjectRevision;
import nu.miguel.personabackend.document.*;
import nu.miguel.personabackend.graph.EditorGraphProjection;
import nu.miguel.personabackend.graph.GraphProjectionRequest;
import nu.miguel.personabackend.graph.GraphProjectionService;
import nu.miguel.personabackend.reference.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static nu.miguel.personabackend.project.ProjectContentRules.bad;

@Service
public final class ProjectOperationService {
    private final ProjectContentRules rules;
    private final ProjectReferenceService references;
    private final YamlDocumentService documents;
    private final GraphProjectionService projections;

    @Autowired public ProjectOperationService(ProjectContentRules rules, ProjectReferenceService references,
                                   YamlDocumentService documents, GraphProjectionService projections) {
        this.rules = rules;
        this.references = references;
        this.documents = documents;
        this.projections = projections;
    }
    ProjectOperationService(ProjectContentRules rules, ProjectReferenceService references,
                            YamlDocumentService documents) {
        this(rules, references, documents, new GraphProjectionService(documents, references, rules));
    }

    public ProjectOperationResponse createFolder(ProjectCreateFolderRequest request){
        if(request==null||!ProjectPathRules.validFolder(request.folder())||isRoot(request.folder()))throw bad("INVALID_FOLDER","A new folder must be 1-8 levels beneath a fixed kind root");
        var project=verifyFolderProject(request.files(),request.expectedRevision(),request.expectedManifestDigest());TreeSet<String> folders=new TreeSet<>(project.folders());
        requireParent(folders,request.folder());if(folders.stream().anyMatch(folder->folder.equalsIgnoreCase(request.folder())))throw bad("FOLDER_COLLISION","The folder already exists or differs only by case");folders.add(request.folder());
        TreeMap<String,ContentFile> candidate=copy(project);writeManifest(candidate,folders);return finish(candidate,List.of(ProjectPathRules.MANIFEST_PATH),List.of());
    }

    public ProjectOperationResponse moveFolder(ProjectMoveFolderRequest request){
        if(request==null||!ProjectPathRules.validFolder(request.folder())||!ProjectPathRules.validFolder(request.replacementFolder())||isRoot(request.folder())||isRoot(request.replacementFolder()))throw bad("INVALID_FOLDER","Folder moves require valid non-root source and destination paths");
        if(!root(request.folder()).equals(root(request.replacementFolder())))throw bad("INVALID_FOLDER_MOVE","Folders cannot move between kind roots");
        var project=verifyFolderProject(request.files(),request.expectedRevision(),request.expectedManifestDigest());TreeSet<String> folders=new TreeSet<>(project.folders());if(!folders.contains(request.folder()))throw bad("MISSING_FOLDER","The source folder is not declared in the project manifest");
        TreeSet<String> unaffected=new TreeSet<>(folders);unaffected.removeIf(folder->folder.equals(request.folder())||folder.startsWith(request.folder()+"/"));requireParent(unaffected,request.replacementFolder());
        Map<String,String> movedFolders=new LinkedHashMap<>();for(String folder:folders)if(folder.equals(request.folder())||folder.startsWith(request.folder()+"/")){String moved=request.replacementFolder()+folder.substring(request.folder().length());if(!ProjectPathRules.validFolder(moved))throw bad("FOLDER_DEPTH","The folder move would exceed eight subfolder levels");movedFolders.put(folder,moved);}
        for(String moved:movedFolders.values())if(unaffected.stream().anyMatch(folder->folder.equalsIgnoreCase(moved)))throw bad("FOLDER_COLLISION","The folder move collides with an existing folder");
        TreeMap<String,ContentFile> candidate=copy(project);List<String> affected=new ArrayList<>();List<ContentFile> resources=candidate.values().stream().filter(file->file.path().startsWith(request.folder()+"/")).toList();for(ContentFile file:resources){candidate.remove(file.path());String moved=request.replacementFolder()+file.path().substring(request.folder().length());if(candidate.keySet().stream().anyMatch(path->path.equalsIgnoreCase(moved)))throw bad("PATH_COLLISION","The folder move collides with an existing resource");candidate.put(moved,rules.file(moved,file.content()));affected.add(file.path());affected.add(moved);}
        unaffected.addAll(movedFolders.values());writeManifest(candidate,unaffected);affected.add(ProjectPathRules.MANIFEST_PATH);return finish(candidate,affected,List.of());
    }

    public ProjectFolderDeletePreview previewFolderDeletion(ProjectDeleteFolderRequest request){
        var project=folderDeleteProject(request);Set<String> paths=project.files().keySet().stream().filter(path->path.startsWith(request.folder()+"/")).collect(java.util.stream.Collectors.toCollection(TreeSet::new));ProjectReferenceGraph graph=references.analyze(List.copyOf(project.files().values()));Set<String> targets=new HashSet<>();graph.declarations().stream().filter(declaration->paths.contains(declaration.path())).forEach(declaration->targets.add(declaration.type()+"\0"+declaration.id()));List<ProjectReference> blockers=graph.references().stream().filter(reference->!paths.contains(reference.path())&&targets.contains(reference.targetType()+"\0"+reference.targetId())).toList();return new ProjectFolderDeletePreview(request.folder(),project.revision(),project.manifestDigest(),List.copyOf(paths),blockers);
    }

    public ProjectOperationResponse deleteFolder(ProjectDeleteFolderRequest request){ProjectContentRules.VerifiedProject project=folderDeleteProject(request);ProjectFolderDeletePreview preview=previewFolderDeletion(request);if(!preview.allowed())throw bad("INBOUND_REFERENCES","Folder deletion is blocked by "+preview.blockingReferences().size()+" external inbound reference(s)");TreeMap<String,ContentFile> candidate=copy(project);preview.resources().forEach(candidate::remove);TreeSet<String> folders=new TreeSet<>(project.folders());folders.removeIf(folder->folder.equals(request.folder())||folder.startsWith(request.folder()+"/"));writeManifest(candidate,folders);List<String> affected=new ArrayList<>(preview.resources());affected.add(ProjectPathRules.MANIFEST_PATH);return finish(candidate,affected,List.of());}

    private ProjectContentRules.VerifiedProject folderDeleteProject(ProjectDeleteFolderRequest request){if(request==null||!ProjectPathRules.validFolder(request.folder())||isRoot(request.folder()))throw bad("INVALID_FOLDER","A fixed kind root cannot be deleted");var project=verifyFolderProject(request.files(),request.expectedRevision(),request.expectedManifestDigest());if(!project.folders().contains(request.folder()))throw bad("MISSING_FOLDER","The folder is not declared in the project manifest");return project;}
    private ProjectContentRules.VerifiedProject verifyFolderProject(List<ContentFile> files,String revision,String manifestDigest){var project=verifyProject(files,revision);if(manifestDigest==null||!MessageDigest.isEqual(project.manifestDigest().getBytes(StandardCharsets.US_ASCII),manifestDigest.getBytes(StandardCharsets.US_ASCII)))throw ProjectContentRules.conflict("STALE_MANIFEST","The submitted folder manifest digest is stale");return project;}
    private void writeManifest(TreeMap<String,ContentFile> candidate,Collection<String> folders){String content=ProjectPathRules.renderManifest(folders);candidate.put(ProjectPathRules.MANIFEST_PATH,rules.file(ProjectPathRules.MANIFEST_PATH,content));}
    private static boolean isRoot(String folder){return !folder.contains("/");}private static String root(String folder){int slash=folder.indexOf('/');return slash<0?folder:folder.substring(0,slash);}private static void requireParent(Set<String> folders,String folder){int slash=folder.lastIndexOf('/');String parent=folder.substring(0,slash);if(parent.contains("/")&&!folders.contains(parent))throw bad("MISSING_PARENT_FOLDER","The destination parent folder does not exist");}

    public ProjectOperationResponse create(ProjectCreateRequest request) {
        if (request == null) throw bad("MISSING_REQUEST", "Missing create request");
        rules.requireKindAndId(request.kind(), request.id());
        rules.requireRequestedPath(request.kind(), request.id(), request.path());
        var project = verifyProject(request.files(), request.expectedRevision());
        requireResourceFolder(project,request.path());
        TreeMap<String, ContentFile> candidate = copy(project);
        if (candidate.containsKey(request.path())) throw bad("DUPLICATE_PATH", "A file already uses the requested path");
        ensureIdAvailable(candidate, request.kind(), request.id());
        candidate.put(request.path(), rules.file(request.path(), templateContent(request.kind(), request.id(), request.template())));
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
        requireResourceFolder(project, request.replacementPath());
        TreeMap<String, ContentFile> candidate = copy(project);
        RenamePreview preview = references.preview(new RenamePreviewRequest(List.copyOf(candidate.values()),
                request.kind(), request.currentId(), request.replacementId()));
        if (!preview.safe()) throw bad("RENAME_CONFLICT", String.join(" ", preview.conflicts()));

        Map<String, List<RenameOccurrence>> byFile = new TreeMap<>();
        preview.occurrences().forEach(item -> byFile.computeIfAbsent(item.path(), ignored -> new ArrayList<>()).add(item));
        for (var entry : byFile.entrySet()) {
            ContentFile file = candidate.get(entry.getKey());
            if(ProjectPathRules.MANIFEST_PATH.equals(entry.getKey()))continue;String content = file.content();
            for (RenameOccurrence occurrence : entry.getValue()) {
                content = documents.edit(new YamlEditRequest(content, occurrence.yamlPath(), request.replacementId())).content();
            }
            candidate.put(file.path(), rules.file(file.path(), content));
        }

        List<String> affected = new ArrayList<>(byFile.keySet());
        if (request.renameFile()) {
            ProjectDeclaration source = declaration(candidate, request.kind(), request.replacementId());
            rules.requireRequestedPath(request.kind(), request.replacementId(), request.replacementPath());
            requireResourceFolder(project,request.replacementPath());
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
        candidate.remove(source.path());
        return finish(candidate, List.of(source.path()), List.of());
    }

    public ProjectOperationResponse move(ProjectMoveRequest request) {
        if (request == null) throw bad("MISSING_REQUEST", "Missing move request");
        rules.requireKindAndId(request.kind(), request.id());
        rules.requireRequestedPath(request.kind(), request.id(), request.replacementPath());
        var project = verifyProject(request.files(), request.expectedRevision());
        requireResourceFolder(project, request.replacementPath());
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
        List<String> requestedCandidates = request.sourceYamlPaths().isEmpty()
                ? List.of(request.sourceYamlPath()) : request.sourceYamlPaths();
        List<String> requestedPaths = requestedCandidates.stream().filter(Objects::nonNull).distinct().toList();
        if (sourceFile == null || requestedPaths.isEmpty() || requestedPaths.size() > 500
                || requestedPaths.stream().anyMatch(path -> path.length() > 2_048 || !path.startsWith("/")))
            throw bad("INVALID_EXTRACT_SOURCE", "The extraction source is not an authoritative project YAML path");
        YamlDocumentResponse sourceModel = documents.parse(sourceFile.content());
        List<YamlDocumentNode> selectedNodes = requestedPaths.stream().map(path -> find(sourceModel.root(), path)).toList();
        YamlDocumentNode selected = selectedNodes.getFirst();
        YamlDocumentNode parent = selected == null ? null : find(sourceModel.root(), parentPath(selected.path()));
        if (selectedNodes.stream().anyMatch(Objects::isNull) || parent == null
                || !Set.of("sequence", "mapping").contains(parent.kind()))
            throw bad("INVALID_EXTRACT_SOURCE", "Only a complete graph command can be extracted");
        String replaced;
        String extractedKey;
        String descriptorInputs = "inputs: {}\n", descriptorOutputs = "outputs: {}\n";
        List<String> boundaryConnections = new ArrayList<>();
        Map<String, String> boundaryConnectionKeys = new LinkedHashMap<>();
        Set<String> usedConnectionKeys = new LinkedHashSet<>();
        String nodesYaml;
        List<String> internalConnections = new ArrayList<>();
        if ("mapping".equals(parent.kind())) {
            if ((!parent.path().endsWith("/nodes") && !parent.path().equals("/nodes"))
                    || selectedNodes.stream().anyMatch(node -> node.key() == null
                    || !parent.path().equals(parentPath(node.path()))))
                throw bad("INVALID_EXTRACT_SOURCE", "Graph extraction requires keyed nodes from one graph");
            extractedKey = selected.key();
            ProjectDeclaration declaration = references.analyze(List.copyOf(candidate.values())).declarations().stream()
                    .filter(value -> value.path().equals(sourceFile.path())).findFirst()
                    .orElseThrow(() -> bad("INVALID_EXTRACT_SOURCE", "The source file has no typed resource declaration"));
            EditorGraphProjection graph = projections.project(new GraphProjectionRequest(sourceFile.path(),
                    declaration.type(), declaration.id(), "", sourceFile.content(), sha256(sourceFile.content()),
                    List.copyOf(candidate.values())));
            Map<String, EditorGraphProjection.GraphNode> graphNodes = new LinkedHashMap<>();
            graph.nodes().stream().filter(value -> requestedPaths.contains(value.yamlPath()))
                    .forEach(value -> graphNodes.put(value.yamlPath(), value));
            if (graphNodes.size() != selectedNodes.size())
                throw bad("INVALID_EXTRACT_SOURCE", "Every selected item must be a node in the same explicit typed graph");
            Map<String, EditorGraphProjection.GraphPin> ports = new HashMap<>();
            graph.ports().forEach(port -> ports.put(port.id(), port));
            Set<String> selectedPorts = graphNodes.values().stream().flatMap(node -> node.pins().stream())
                    .map(EditorGraphProjection.GraphPin::id)
                    .collect(java.util.stream.Collectors.toSet());
            Map<String, String> inputs = new LinkedHashMap<>(), outputs = new LinkedHashMap<>();
            Map<String, String> inputNames = new LinkedHashMap<>(), outputNames = new LinkedHashMap<>();
            List<EditorGraphProjection.GraphEdge> incomingExecution = new ArrayList<>(), outgoingExecution = new ArrayList<>();
            List<EditorGraphProjection.GraphEdge> boundaryEdges = new ArrayList<>();
            for (EditorGraphProjection.GraphEdge edge : graph.edges()) {
                boolean incoming = selectedPorts.contains(edge.targetPinId()) && !selectedPorts.contains(edge.sourcePinId());
                boolean outgoing = selectedPorts.contains(edge.sourcePinId()) && !selectedPorts.contains(edge.targetPinId());
                boolean internal = selectedPorts.contains(edge.sourcePinId()) && selectedPorts.contains(edge.targetPinId());
                if (internal) {
                    YamlDocumentNode connection = find(sourceModel.root(), parentPath(edge.sourceYamlPath()));
                    YamlDocumentNode connectionParent = connection == null ? null
                            : find(sourceModel.root(), parentPath(connection.path()));
                    if (connection != null && connectionParent != null) {
                        internalConnections.add(mappingField(sourceFile.content(), connection, connectionParent));
                        usedConnectionKeys.add(connection.key());
                    }
                }
                if (!incoming && !outgoing) continue;
                boundaryEdges.add(edge);
                EditorGraphProjection.GraphPin boundary = ports.get(incoming ? edge.targetPinId() : edge.sourcePinId());
                if (boundary == null) continue;
                if (boundary.channel().equals("EXECUTION")) {
                    if (incoming) incomingExecution.add(edge); else outgoingExecution.add(edge);
                    if (incoming && !boundary.label().equals("exec") || outgoing && !boundary.label().equals("success"))
                        throw bad("UNSUPPORTED_EXECUTION_BOUNDARY",
                                "Extract this branch together with its flow node; reusable scripts expose exec and success boundaries");
                    continue;
                }
                Map<String, String> names = incoming ? inputNames : outputNames;
                String name = names.get(boundary.id());
                if (name == null) name = uniqueBoundaryName(boundary, graphNodes.values(), incoming ? inputs : outputs);
                if (!name.matches("[a-z0-9][a-z0-9_.-]{0,127}"))
                    throw bad("INVALID_EXTRACT_BOUNDARY", "A data boundary pin has no reusable parameter name");
                (incoming ? inputs : outputs).put(name, boundary.valueType());
                names.put(boundary.id(), name);
            }
            if (incomingExecution.size() > 1 || outgoingExecution.size() > 1)
                throw bad("AMBIGUOUS_EXECUTION_BOUNDARY",
                        "Collapse a selection with at most one incoming and one outgoing execution wire");
            if (!inputs.isEmpty()) {
                StringBuilder yaml = new StringBuilder("inputs:\n");
                inputs.forEach((name, type) -> { yaml.append("  ").append(name).append(": { type: ")
                        .append(type).append(", required: true }\n"); });
                descriptorInputs = yaml.toString();
            }
            if (!outputs.isEmpty()) {
                StringBuilder yaml = new StringBuilder("outputs:\n");
                outputs.forEach((name, type) -> { yaml.append("  ").append(name).append(": { type: ")
                        .append(type).append(" }\n"); });
                descriptorOutputs = yaml.toString();
            }
            for (EditorGraphProjection.GraphEdge edge : boundaryEdges) {
                boolean incoming = selectedPorts.contains(edge.targetPinId());
                EditorGraphProjection.GraphPin boundary = ports.get(incoming ? edge.targetPinId() : edge.sourcePinId());
                String originalEndpoint = scalarValue(sourceModel.root(), incoming ? edge.targetYamlPath() : edge.sourceYamlPath());
                if (boundary.channel().equals("EXECUTION")) {
                    addBoundaryConnection(boundaryConnections, boundaryConnectionKeys, usedConnectionKeys,
                            incoming ? "enter" : "leave", incoming
                            ? "  enter: { from: $input.exec, to: " + originalEndpoint + " }\n"
                            : "  leave: { from: " + originalEndpoint + ", to: $output.exec }\n");
                } else {
                    String name = (incoming ? inputNames : outputNames).get(boundary.id());
                    addBoundaryConnection(boundaryConnections, boundaryConnectionKeys, usedConnectionKeys,
                            (incoming ? "input-" : "output-") + name, incoming
                            ? "  input-" + name + ": { from: $input." + name + ", to: " + originalEndpoint + " }\n"
                            : "  output-" + name + ": { from: " + originalEndpoint + ", to: $output." + name + " }\n");
                }
            }
            if (incomingExecution.isEmpty()) {
                EditorGraphProjection.GraphPin entry = graphNodes.values().stream().flatMap(node -> node.pins().stream())
                        .filter(pin -> pin.channel().equals("EXECUTION") && pin.direction().equals("INPUT")
                                && pin.label().equals("exec")).findFirst()
                        .orElseThrow(() -> bad("UNSUPPORTED_EXECUTION_BOUNDARY", "The selection has no executable entry pin"));
                EditorGraphProjection.GraphNode owner = graphNodes.values().stream()
                        .filter(node -> node.id().equals(entry.nodeId())).findFirst().orElseThrow();
                addBoundaryConnection(boundaryConnections, boundaryConnectionKeys, usedConnectionKeys, "enter",
                        "  enter: { from: $input.exec, to: " + owner.title() + ".exec }\n");
            }
            if (outgoingExecution.isEmpty()) {
                EditorGraphProjection.GraphPin exit = graphNodes.values().stream().flatMap(node -> node.pins().stream())
                        .filter(pin -> pin.channel().equals("EXECUTION") && pin.direction().equals("OUTPUT")
                                && pin.label().equals("success")).findFirst()
                        .orElseThrow(() -> bad("UNSUPPORTED_EXECUTION_BOUNDARY", "The selection has no successful exit pin"));
                EditorGraphProjection.GraphNode owner = graphNodes.values().stream()
                        .filter(node -> node.id().equals(exit.nodeId())).findFirst().orElseThrow();
                addBoundaryConnection(boundaryConnections, boundaryConnectionKeys, usedConnectionKeys, "leave",
                        "  leave: { from: " + owner.title() + ".success, to: $output.exec }\n");
            }
            nodesYaml = selectedNodes.stream().map(node -> indent(mappingField(sourceFile.content(), node, parent), 2))
                    .reduce("", String::concat);
            replaced = sourceFile.content();
            for (EditorGraphProjection.GraphEdge edge : boundaryEdges) {
                boolean incoming = selectedPorts.contains(edge.targetPinId());
                EditorGraphProjection.GraphPin boundary = ports.get(incoming ? edge.targetPinId() : edge.sourcePinId());
                String label = boundary.channel().equals("EXECUTION") ? (incoming ? "exec" : "success")
                        : (incoming ? inputNames : outputNames).get(boundary.id());
                String path = incoming ? edge.targetYamlPath() : edge.sourceYamlPath();
                String replacement = extractedKey + "." + label;
                if (!replacement.equals(scalarValue(sourceModel.root(), path)))
                    replaced = documents.edit(new YamlEditRequest(replaced, path, replacement)).content();
            }
            Set<String> internalPaths = graph.edges().stream()
                    .filter(edge -> selectedPorts.contains(edge.sourcePinId()) && selectedPorts.contains(edge.targetPinId()))
                    .map(edge -> parentPath(edge.sourceYamlPath())).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            for (String path : internalPaths)
                replaced = documents.structure(new YamlStructureRequest(replaced,
                        YamlStructureRequest.Operation.DELETE, path, null)).content();
            for (YamlDocumentNode node : selectedNodes)
                replaced = documents.structure(new YamlStructureRequest(replaced,
                        YamlStructureRequest.Operation.DELETE, node.path(), null)).content();
            replaced = documents.insertField(new YamlMappingInsertRequest(replaced, parent.path(), extractedKey,
                    "type: run-script\nscript: " + request.scriptId() + "\ninputs: {}")).content();
        } else throw bad("SCRIPT_FORMAT_MIGRATION_REQUIRED",
                "Only content-version 2 keyed graph nodes can be collapsed into a reusable script");
        candidate.put(sourceFile.path(), rules.file(sourceFile.path(), replaced));

        String descriptor=descriptorInputs+descriptorOutputs+"variables: {}\nnodes:\n"+nodesYaml
                +"connections:\n" + internalConnections.stream().map(value -> indent(value, 2))
                .reduce("", String::concat) + String.join("", boundaryConnections);

        String scriptPath=rules.safePath("script",request.scriptId());if(candidate.containsKey(scriptPath))throw bad("DUPLICATE_PATH","A file already uses the extracted script path");
        String script="content-version: 2\nid: "+request.scriptId()+"\n"+descriptor;
        candidate.put(scriptPath,rules.file(scriptPath,script));
        return finish(candidate, List.of(sourceFile.path(), scriptPath), List.of());
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
        if (candidate.containsKey(targetPath)) throw bad("DUPLICATE_PATH", "A file already uses the target's safe path");
        candidate.put(targetPath, rules.file(targetPath, templateContent(targetKind, request.targetId(), "minimal")));
        return finish(candidate, List.of(source.path(), targetPath), List.of());
    }

    public SafePathResponse safePath(String kind, String id) {
        return new SafePathResponse(kind, id, rules.safePath(kind, id));
    }

    public ProjectTemplateResponse template(String kind, String id, String requested) {
        rules.requireKindAndId(kind, id);
        String path = rules.safePath(kind, id);
        String content = templateContent(kind, id, requested);
        return new ProjectTemplateResponse(kind, id, path, content);
    }

    private static String scriptDescriptor(){return "inputs: {}\noutputs: {}\nvariables: {}\nnodes:\n  pause: { type: wait, duration: 1ms }\nconnections:\n  enter: { from: $input.exec, to: pause.exec }\n  leave: { from: pause.success, to: $output.exec }\n";}

    private String templateContent(String kind, String id, String requested) {
        if (requested != null && !requested.isBlank() && !requested.equals("minimal"))
            throw bad("INVALID_TEMPLATE", "Unknown creation template");
        return switch (kind) {
            case "behavior" -> "content-version: 2\nid: " + id + "\nscope: player\nroot:\n  id: root\n  type: sequence\n  children: []\n";
            case "dialogue" -> "content-version: 2\nid: " + id + "\nstart: start\nnodes:\n  start:\n    graph:\n      variables: {}\n      nodes:\n        line: { type: say, text: \"New dialogue line\" }\n        end: { type: end-dialogue }\n      connections:\n        enter: { from: $event.exec, to: line.exec }\n        finish: { from: line.success, to: end.exec }\n";
            case "quest" -> "content-version: 2\nid: " + id + "\ntitle: \"New quest\"\nphases:\n  - id: start\n    objectives:\n      - id: begin\n        type: wait\n        duration: 1s\n";
            case "npc" -> "content-version: 2\nid: " + id + "\ndisplay-name: \"New NPC\"\n";
            case "script" -> "content-version: 2\nid: "+id+"\n"+scriptDescriptor();
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
    private static void requireResourceFolder(ProjectContentRules.VerifiedProject project,String path){String folder=ProjectPathRules.folderOf(path);if(folder.contains("/")&&!project.folders().contains(folder))throw bad("MISSING_FOLDER","The target folder is not declared in .persona/project.yml");}

    private void requireValidYaml(List<ContentFile> files) {
        for (ContentFile file : files) {
            YamlDocumentResponse parsed = documents.parse(file.content());
            if (!parsed.valid()) {
                YamlDiagnostic issue = parsed.diagnostics().isEmpty() ? null : parsed.diagnostics().getFirst();
                throw new ProjectOperationException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PROJECT_YAML",
                        file.path() + ": " + (issue == null ? "the candidate project contains invalid YAML" : issue.message()),
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
    private static String parentPath(String path) {
        int slash = path == null ? -1 : path.lastIndexOf('/'); return slash <= 0 ? "" : path.substring(0, slash);
    }
    private static String indent(String value, int spaces) {
        String prefix = " ".repeat(spaces);
        return prefix + value.replace("\n", "\n" + prefix).stripTrailing() + "\n";
    }
    private static String escape(String value) { return value.replace("~", "~0").replace("/", "~1"); }
    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
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

    private static String scalarValue(YamlDocumentNode root, String path) {
        YamlDocumentNode value = find(root, path);
        if (value == null || value.value() == null)
            throw bad("INVALID_EXTRACT_BOUNDARY", "A graph connection boundary is missing its endpoint");
        return value.value();
    }

    private static String lineTerminated(String value) { return value.endsWith("\n") ? value : value + "\n"; }

    private static String mappingField(String content, YamlDocumentNode node, YamlDocumentNode parent) {
        if (node.keyOffset() < 0 || node.key() == null)
            throw bad("INVALID_EXTRACT_SOURCE", "The selected graph node has no stable mapping key");
        int start = lineStart(content, node.keyOffset());
        int index = parent.children().indexOf(node);
        int end = index >= 0 && index + 1 < parent.children().size()
                ? lineStart(content, Math.max(parent.children().get(index + 1).keyOffset(),
                parent.children().get(index + 1).startOffset()))
                : lineEnd(content, node.endOffset());
        String segment = content.substring(start, Math.max(start, end));
        int indentation = Math.max(0, node.keyColumn() - 1);
        String prefix = " ".repeat(indentation);
        StringBuilder result = new StringBuilder();
        for (String line : segment.split("(?<=\\n)", -1))
            result.append(line.startsWith(prefix) ? line.substring(indentation) : line);
        return lineTerminated(result.toString());
    }

    private static int lineStart(String content, int offset) {
        int index = Math.max(0, Math.min(offset, content.length()));
        while (index > 0 && content.charAt(index - 1) != '\n') index--;
        return index;
    }

    private static int lineEnd(String content, int offset) {
        int index = Math.max(0, Math.min(offset, content.length()));
        while (index < content.length() && content.charAt(index) != '\n') index++;
        return index < content.length() ? index + 1 : index;
    }

    private static String uniqueBoundaryName(EditorGraphProjection.GraphPin pin,
                                             Collection<EditorGraphProjection.GraphNode> nodes,
                                             Map<String, String> existing) {
        String base = pin.label().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isBlank()) base = "value";
        if (!existing.containsKey(base)) return base;
        String owner = nodes.stream().filter(node -> node.id().equals(pin.nodeId())).map(EditorGraphProjection.GraphNode::title)
                .findFirst().orElse("node").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
        String candidate = owner + "-" + base;
        int suffix = 2;
        while (existing.containsKey(candidate)) candidate = owner + "-" + base + "-" + suffix++;
        return candidate;
    }

    private static void addBoundaryConnection(List<String> connections, Map<String, String> boundaryKeys,
                                              Set<String> usedKeys, String preferredKey, String yaml) {
        if (boundaryKeys.containsKey(preferredKey)) return;
        String key = preferredKey;
        for (int suffix = 2; usedKeys.contains(key); suffix++) key = preferredKey + "-" + suffix;
        usedKeys.add(key);
        boundaryKeys.put(preferredKey, key);
        connections.add(yaml.replaceFirst("^  " + java.util.regex.Pattern.quote(preferredKey) + ":",
                "  " + key + ":"));
    }

    public record SafePathResponse(String kind, String id, String path) {}
    public record ProjectTemplateResponse(String kind, String id, String path, String content) {}
}
