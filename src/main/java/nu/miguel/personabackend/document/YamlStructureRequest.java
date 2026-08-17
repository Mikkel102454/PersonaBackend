package nu.miguel.personabackend.document;

public record YamlStructureRequest(String content,Operation operation,String path,String targetPath) {
    public enum Operation { MOVE_BEFORE,MOVE_AFTER,DUPLICATE_AFTER,DELETE }
}
