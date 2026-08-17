package nu.miguel.personabackend.project;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

public final class ProjectOperationException extends ResponseStatusException {
    private final String code;
    private final String filePath;
    private final String yamlPath;

    public ProjectOperationException(HttpStatusCode status, String code, String reason, String filePath, String yamlPath) {
        super(status, reason);
        this.code = code;
        this.filePath = filePath;
        this.yamlPath = yamlPath;
    }

    public String code() { return code; }
    public String filePath() { return filePath; }
    public String yamlPath() { return yamlPath; }
}
