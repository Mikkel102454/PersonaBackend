package nu.miguel.personabackend.reference;

import java.util.List;

public record RenamePreview(String type, String currentId, String replacementId, boolean safe,
                            List<String> conflicts, List<RenameOccurrence> occurrences) {
    public RenamePreview {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        occurrences = occurrences == null ? List.of() : List.copyOf(occurrences);
    }
}
