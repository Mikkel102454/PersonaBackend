package nu.miguel.persona.editor.protocol;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public record ContentSnapshot(
        int protocolVersion,
        UUID sessionId,
        String revision,
        int contentFormatVersion,
        Instant createdAt,
        String installationPublicKey,
        List<ContentFile> files,
        Set<String> folders,
        String manifestDigest,
        String signature
) {
    public ContentSnapshot {
        files = files == null ? List.of() : List.copyOf(files);
        folders = folders == null ? Set.of() : Set.copyOf(new TreeSet<>(folders));
        manifestDigest = manifestDigest == null ? "" : manifestDigest;
    }

    public ContentSnapshot(int protocolVersion,UUID sessionId,String revision,int contentFormatVersion,
                           Instant createdAt,String installationPublicKey,List<ContentFile> files,String signature){
        this(protocolVersion,sessionId,revision,contentFormatVersion,createdAt,installationPublicKey,files,Set.of(),"",signature);
    }

    public String signingInput() {
        StringBuilder value = new StringBuilder()
                .append(protocolVersion).append('\n')
                .append(sessionId).append('\n')
                .append(revision).append('\n')
                .append(contentFormatVersion).append('\n')
                .append(createdAt).append('\n')
                .append(installationPublicKey).append('\n')
                .append(manifestDigest);
        folders.stream().sorted().forEach(folder -> value.append('\n').append("folder:").append(folder));
        files.stream().sorted(Comparator.comparing(ContentFile::path))
                .forEach(file -> value.append('\n').append(file.path()).append('\n').append(file.sha256()));
        return value.toString();
    }
}
