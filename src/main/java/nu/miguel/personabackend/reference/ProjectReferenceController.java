package nu.miguel.personabackend.reference;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/editor/projects")
public final class ProjectReferenceController {
    private final ProjectReferenceService references;
    public ProjectReferenceController(ProjectReferenceService references) { this.references = references; }

    @PostMapping("/references")
    public ProjectReferenceGraph references(@RequestBody ProjectReferenceRequest request) {
        return references.analyze(request == null ? null : request.files());
    }

    @PostMapping("/rename-preview")
    public RenamePreview renamePreview(@RequestBody RenamePreviewRequest request) { return references.preview(request); }
}
