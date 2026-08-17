package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ProjectImportResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions/{sessionId}/import")
public final class ProjectImportController {
    private final ProjectImportService imports;

    public ProjectImportController(ProjectImportService imports) { this.imports = imports; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProjectImportResponse importProject(@PathVariable UUID sessionId,
                                               @RequestPart("files") List<MultipartFile> files) {
        return imports.importFiles(files);
    }
}
