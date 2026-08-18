package nu.miguel.personabackend.project;

import nu.miguel.personabackend.reference.ProjectReference;
import java.util.List;

public record ProjectFolderDeletePreview(String folder,String projectRevision,String manifestDigest,List<String> resources,List<ProjectReference> blockingReferences){
    public ProjectFolderDeletePreview{resources=List.copyOf(resources);blockingReferences=List.copyOf(blockingReferences);}
    public boolean allowed(){return blockingReferences.isEmpty();}
}
