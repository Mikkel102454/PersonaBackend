package nu.miguel.personabackend.project;

import nu.miguel.personabackend.security.QuotaProperties;
import nu.miguel.personabackend.security.RateLimitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions/{sessionId}/projects")
public final class ProjectOperationController {
    private final ProjectOperationService operations;
    private final RateLimitService limits; private final QuotaProperties quotas;
    @Autowired public ProjectOperationController(ProjectOperationService operations, RateLimitService limits, QuotaProperties quotas) {
        this.operations = operations; this.limits = limits; this.quotas = quotas;
    }
    ProjectOperationController(ProjectOperationService operations) { this(operations, new RateLimitService(), QuotaProperties.defaults()); }

    private void check(UUID sessionId) {
        limits.check("project-operation", sessionId.toString(), quotas.messagesPerSession(), quotas.window());
    }

    @GetMapping("/safe-path")
    public ProjectOperationService.SafePathResponse safePath(@PathVariable UUID sessionId,
                                                             @RequestParam String kind, @RequestParam String id) {
        check(sessionId);
        return operations.safePath(kind, id);
    }
    @GetMapping("/template")
    public ProjectOperationService.ProjectTemplateResponse template(@PathVariable UUID sessionId,
                                                                    @RequestParam String kind,
                                                                    @RequestParam String id,
                                                                    @RequestParam(defaultValue = "minimal") String template) {
        check(sessionId);
        return operations.template(kind, id, template);
    }
    @PostMapping("/create")
    public ProjectOperationResponse create(@PathVariable UUID sessionId, @RequestBody ProjectCreateRequest request) {
        check(sessionId);
        return operations.create(request);
    }
    @PostMapping("/duplicate")
    public ProjectOperationResponse duplicate(@PathVariable UUID sessionId, @RequestBody ProjectDuplicateRequest request) {
        check(sessionId);
        return operations.duplicate(request);
    }
    @PostMapping("/rename")
    public ProjectOperationResponse rename(@PathVariable UUID sessionId, @RequestBody ProjectRenameApplyRequest request) {
        check(sessionId);
        return operations.rename(request);
    }
    @PostMapping("/delete")
    public ProjectOperationResponse delete(@PathVariable UUID sessionId, @RequestBody ProjectDeleteRequest request) {
        check(sessionId);
        return operations.delete(request);
    }
    @PostMapping("/move")
    public ProjectOperationResponse move(@PathVariable UUID sessionId, @RequestBody ProjectMoveRequest request) {
        check(sessionId);
        return operations.move(request);
    }
    @PostMapping("/extract-script")
    public ProjectOperationResponse extractScript(@PathVariable UUID sessionId,
                                                   @RequestBody ProjectExtractScriptRequest request) {
        check(sessionId);
        return operations.extractScript(request);
    }
    @PostMapping("/create-and-assign")
    public ProjectOperationResponse createAndAssign(@PathVariable UUID sessionId,
                                                     @RequestBody ProjectCreateAndAssignRequest request) {
        check(sessionId);
        return operations.createAndAssign(request);
    }
}
