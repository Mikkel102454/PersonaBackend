package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Canonical path and empty-folder manifest contract shared by project operations. */
public final class ProjectPathRules {
    public static final String MANIFEST_PATH=".persona/project.yml";
    public static final Map<String,String> KIND_ROOTS=Map.of("npc","npcs","dialogue","dialogues","quest","quests","behavior","behaviors","script","scripts");
    private ProjectPathRules(){}

    public static boolean validResourcePath(String path){
        if(path==null||path.isBlank()||path.length()>240||path.startsWith("/")||path.contains("\\")||path.contains("\0"))return false;
        String[] parts=path.split("/",-1);if(parts.length<2||parts.length>10||!KIND_ROOTS.containsValue(parts[0]))return false;
        for(int index=1;index<parts.length-1;index++)if(!validSegment(parts[index]))return false;
        String file=parts[parts.length-1];return file.matches("[a-z0-9][a-z0-9._-]*\\.ya?ml");
    }
    public static boolean validResourcePath(String kind,String path){String root=KIND_ROOTS.get(kind);return root!=null&&validResourcePath(path)&&path.startsWith(root+"/");}
    public static boolean validFolder(String folder){
        if(folder==null||folder.isBlank()||folder.length()>236||folder.startsWith("/")||folder.endsWith("/")||folder.contains("\\")||folder.contains("\0"))return false;
        String[] parts=folder.split("/",-1);if(parts.length<2||parts.length>9||!KIND_ROOTS.containsValue(parts[0]))return false;
        for(int index=1;index<parts.length;index++)if(!validSegment(parts[index]))return false;return true;
    }
    public static String folderOf(String resourcePath){int slash=resourcePath.lastIndexOf('/');return slash<0?"":resourcePath.substring(0,slash);}
    public static String rootForKind(String kind){return KIND_ROOTS.get(kind);}

    public static Manifest manifest(List<ContentFile> files){
        ContentFile file=files==null?null:files.stream().filter(item->MANIFEST_PATH.equals(item.path())).findFirst().orElse(null);
        if(file==null)return new Manifest(Set.of(),sha256(""));
        return new Manifest(parseManifest(file.content()),file.sha256());
    }
    public static Set<String> parseManifest(String content){
        if(content==null)throw ProjectContentRules.bad("INVALID_MANIFEST","Project folder manifest has no content");
        List<String> lines=content.replace("\r\n","\n").replace('\r','\n').lines().toList();boolean version=false,foldersHeader=false;TreeSet<String> folders=new TreeSet<>();Map<String,String> folded=new HashMap<>();
        for(String raw:lines){String line=raw.stripTrailing();if(line.isBlank()||line.stripLeading().startsWith("#"))continue;if(!version&&line.equals("version: 1")){version=true;continue;}if(version&&!foldersHeader&&line.equals("folders:")){foldersHeader=true;continue;}if(foldersHeader&&line.startsWith("  - ")){String folder=line.substring(4).trim();if(!validFolder(folder))throw ProjectContentRules.bad("INVALID_FOLDER","Invalid folder path in .persona/project.yml: "+folder);String collision=folded.putIfAbsent(folder.toLowerCase(Locale.ROOT),folder);if(collision!=null)throw ProjectContentRules.bad("FOLDER_COLLISION","Folder paths collide by case: "+collision+" and "+folder);folders.add(folder);continue;}throw ProjectContentRules.bad("INVALID_MANIFEST",".persona/project.yml must contain only version: 1 and a folders list");}
        if(!version||!foldersHeader)throw ProjectContentRules.bad("INVALID_MANIFEST",".persona/project.yml requires version: 1 and folders");return Set.copyOf(folders);
    }
    public static String renderManifest(Collection<String> folders){StringBuilder yaml=new StringBuilder("version: 1\nfolders:\n");folders.stream().sorted().forEach(folder->yaml.append("  - ").append(folder).append('\n'));return yaml.toString();}
    public static String sha256(String content){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static boolean validSegment(String value){return value.matches("[a-z0-9][a-z0-9._-]*");}
    public record Manifest(Set<String> folders,String digest){public Manifest{folders=Set.copyOf(folders);}}
}
