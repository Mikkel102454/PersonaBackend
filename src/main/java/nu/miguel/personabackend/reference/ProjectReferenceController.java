package nu.miguel.personabackend.reference;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions/{sessionId}/projects")
public final class ProjectReferenceController {
    private final ProjectReferenceService references;
    public ProjectReferenceController(ProjectReferenceService references) { this.references = references; }

    @PostMapping("/references")
    public ProjectReferenceGraph references(@PathVariable UUID sessionId, @RequestBody ProjectReferenceRequest request) {
        return references.analyze(request == null ? null : request.files());
    }

    @PostMapping("/rename-preview")
    public RenamePreview renamePreview(@PathVariable UUID sessionId, @RequestBody RenamePreviewRequest request) { return references.preview(request); }
}
