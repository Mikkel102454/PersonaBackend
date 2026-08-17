package nu.miguel.personabackend.project;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ProjectOperationController.class)
public final class ProjectOperationAdvice {
    @ExceptionHandler(ProjectOperationException.class)
    ResponseEntity<ProjectOperationError> operationError(ProjectOperationException error) {
        return ResponseEntity.status(error.getStatusCode()).body(new ProjectOperationError(
                error.code(), error.getReason(), error.filePath(), error.yamlPath()));
    }
}
