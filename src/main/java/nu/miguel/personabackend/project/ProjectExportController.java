package nu.miguel.personabackend.project;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions/{sessionId}/export")
public final class ProjectExportController {
    private final ProjectExportService exports;

    public ProjectExportController(ProjectExportService exports) { this.exports = exports; }

    @PostMapping(produces = "application/zip")
    public ResponseEntity<byte[]> export(@PathVariable UUID sessionId,
                                         @RequestBody ProjectExportRequest request) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("persona-project.zip").build().toString())
                .body(exports.export(request));
    }
}
