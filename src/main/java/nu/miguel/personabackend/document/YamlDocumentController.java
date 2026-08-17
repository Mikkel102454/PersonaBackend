package nu.miguel.personabackend.document;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions/{sessionId}/documents")
public final class YamlDocumentController {
    private final YamlDocumentService documents;

    public YamlDocumentController(YamlDocumentService documents) { this.documents = documents; }

    @PostMapping("/parse")
    public YamlDocumentResponse parse(@PathVariable UUID sessionId, @RequestBody YamlDocumentRequest request) {
        return documents.parse(request == null ? null : request.content());
    }

    @PostMapping("/edit")
    public YamlDocumentResponse edit(@PathVariable UUID sessionId, @RequestBody YamlEditRequest request) { return documents.edit(request); }

    @PostMapping("/structure")
    public YamlDocumentResponse structure(@PathVariable UUID sessionId, @RequestBody YamlStructureRequest request){return documents.structure(request);}

    @PostMapping("/insert")
    public YamlDocumentResponse insert(@PathVariable UUID sessionId, @RequestBody YamlInsertRequest request){return documents.insert(request);}

    @PostMapping("/insert-field")
    public YamlDocumentResponse insertField(@PathVariable UUID sessionId, @RequestBody YamlMappingInsertRequest request){return documents.insertField(request);}

    @PostMapping("/extract-subtree")
    public YamlExtractResponse extractSubtree(@PathVariable UUID sessionId, @RequestBody YamlExtractRequest request){return documents.extractSubtree(request);}
}
