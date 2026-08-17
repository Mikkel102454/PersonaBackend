package nu.miguel.personabackend.project;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/editor/export")
public final class ProjectExportController {
    private final ProjectExportService exports;

    public ProjectExportController(ProjectExportService exports) { this.exports = exports; }

    @PostMapping(produces = "application/zip")
    public ResponseEntity<byte[]> export(@RequestBody ProjectExportRequest request) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("persona-project.zip").build().toString())
                .body(exports.export(request));
    }
}
