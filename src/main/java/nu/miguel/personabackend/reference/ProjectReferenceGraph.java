package nu.miguel.personabackend.reference;

import java.util.List;

public record ProjectReferenceGraph(List<ProjectDeclaration> declarations, List<ProjectReference> references) {
    public ProjectReferenceGraph {
        declarations = declarations == null ? List.of() : List.copyOf(declarations);
        references = references == null ? List.of() : List.copyOf(references);
    }
}
