package nu.miguel.personabackend.graph;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

public final class GraphContractException extends ResponseStatusException {
    private final String code;
    private final String filePath;
    private final String yamlPath;
    public GraphContractException(HttpStatusCode status, String code, String reason, String filePath, String yamlPath) {
        super(status, reason); this.code = code; this.filePath = filePath; this.yamlPath = yamlPath;
    }
    public String code() { return code; }
    public String filePath() { return filePath; }
    public String yamlPath() { return yamlPath; }
}
