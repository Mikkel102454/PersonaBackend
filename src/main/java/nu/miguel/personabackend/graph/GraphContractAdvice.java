package nu.miguel.personabackend.graph;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {GraphProjectionController.class, RelationshipProjectionController.class,
        GraphMutationController.class})
public final class GraphContractAdvice {
    @ExceptionHandler(GraphContractException.class)
    ResponseEntity<GraphContractError> graphError(GraphContractException error) {
        return ResponseEntity.status(error.getStatusCode()).body(new GraphContractError(
                error.code(), error.getReason(), error.filePath(), error.yamlPath()));
    }
}
