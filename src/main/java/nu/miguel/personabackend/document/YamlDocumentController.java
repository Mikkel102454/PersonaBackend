package nu.miguel.personabackend.document;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/editor/documents")
public final class YamlDocumentController {
    private final YamlDocumentService documents;

    public YamlDocumentController(YamlDocumentService documents) { this.documents = documents; }

    @PostMapping("/parse")
    public YamlDocumentResponse parse(@RequestBody YamlDocumentRequest request) {
        return documents.parse(request == null ? null : request.content());
    }

    @PostMapping("/edit")
    public YamlDocumentResponse edit(@RequestBody YamlEditRequest request) { return documents.edit(request); }

    @PostMapping("/structure")
    public YamlDocumentResponse structure(@RequestBody YamlStructureRequest request){return documents.structure(request);}

    @PostMapping("/insert")
    public YamlDocumentResponse insert(@RequestBody YamlInsertRequest request){return documents.insert(request);}

    @PostMapping("/insert-field")
    public YamlDocumentResponse insertField(@RequestBody YamlMappingInsertRequest request){return documents.insertField(request);}

    @PostMapping("/extract-subtree")
    public YamlExtractResponse extractSubtree(@RequestBody YamlExtractRequest request){return documents.extractSubtree(request);}
}
