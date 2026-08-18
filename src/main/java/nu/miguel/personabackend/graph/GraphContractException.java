package nu.miguel.personabackend.graph;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

public final class GraphContractException extends ResponseStatusException {
    private final String code;
    private final String filePath;
    private final String yamlPath;
    private final EditorGraphProjection.SourceRange sourceRange;
    private final String nodeId;
    private final String portId;
    private final String fieldId;
    private final boolean retryable;
    private final String currentContentDigest;
    private final String currentProjectRevision;
    public GraphContractException(HttpStatusCode status, String code, String reason, String filePath, String yamlPath) {
        this(status, code, reason, filePath, yamlPath, null, null, null, null,
                status.value() == 409, null, null);
    }
    public GraphContractException(HttpStatusCode status, String code, String reason, String filePath,
                                  String yamlPath, EditorGraphProjection.SourceRange sourceRange,
                                  String nodeId, String portId, String fieldId, boolean retryable,
                                  String currentContentDigest, String currentProjectRevision) {
        super(status, reason); this.code = code; this.filePath = filePath; this.yamlPath = yamlPath;
        this.sourceRange = sourceRange; this.nodeId = nodeId; this.portId = portId; this.fieldId = fieldId;
        this.retryable = retryable; this.currentContentDigest = currentContentDigest;
        this.currentProjectRevision = currentProjectRevision;
    }
    public String code() { return code; }
    public String filePath() { return filePath; }
    public String yamlPath() { return yamlPath; }
    public EditorGraphProjection.SourceRange sourceRange() { return sourceRange; }
    public String nodeId() { return nodeId; }
    public String portId() { return portId; }
    public String fieldId() { return fieldId; }
    public boolean retryable() { return retryable; }
    public String currentContentDigest() { return currentContentDigest; }
    public String currentProjectRevision() { return currentProjectRevision; }
}
