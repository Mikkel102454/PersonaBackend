package nu.miguel.personabackend.document;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.nodes.*;

import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public final class YamlDocumentService {
    static final int MAX_DOCUMENT_BYTES = 1_048_576;
    private static final Set<Tag> EDITABLE_TAGS = Set.of(Tag.STR, Tag.BOOL, Tag.INT, Tag.FLOAT, Tag.NULL);

    public YamlDocumentResponse parse(String content) {
        requireBounded(content);
        try {
            Node root = yaml().compose(new StringReader(content));
            if (root == null) return new YamlDocumentResponse(true, content, null, List.of());
            return new YamlDocumentResponse(true, content,
                    describe(root, "", null, null, content, new IdentityHashMap<>()), List.of());
        } catch (MarkedYAMLException error) {
            Mark mark = error.getProblemMark();
            int line = mark == null ? 1 : mark.getLine() + 1;
            int column = mark == null ? 1 : mark.getColumn() + 1;
            String message = error.getProblem() == null ? "Invalid YAML" : error.getProblem();
            return new YamlDocumentResponse(false, content, null,
                    List.of(new YamlDiagnostic(line, column, message)));
        } catch (RuntimeException error) {
            return new YamlDocumentResponse(false, content, null,
                    List.of(new YamlDiagnostic(1, 1, "Invalid YAML: " + safeMessage(error))));
        }
    }

    public YamlDocumentResponse edit(YamlEditRequest request) {
        if (request == null) throw bad("Missing YAML edit request");
        YamlDocumentResponse parsed = parse(request.content());
        if (!parsed.valid()) throw bad("Cannot visually edit YAML while it has syntax errors");
        YamlDocumentNode target = find(parsed.root(), request.path());
        if (target == null || !target.editable()) throw bad("The selected YAML value is not safely editable");
        String replacement = scalar(target.kind(), request.value());
        String updated = request.content().substring(0, target.startOffset()) + replacement
                + request.content().substring(target.endOffset());
        YamlDocumentResponse result = parse(updated);
        if (!result.valid()) throw bad("The visual edit did not produce valid YAML");
        return result;
    }

    public YamlDocumentResponse structure(YamlStructureRequest request){if(request==null||request.operation()==null)throw bad("Missing YAML structure request");YamlDocumentResponse parsed=parse(request.content());if(!parsed.valid())throw bad("Cannot structurally edit YAML while it has syntax errors");YamlDocumentNode source=find(parsed.root(),request.path());if(source==null||source.path().isEmpty())throw bad("Invalid structural source");Range sourceRange=range(request.content(),source);String updated;switch(request.operation()){case DELETE->updated=request.content().substring(0,sourceRange.start())+request.content().substring(sourceRange.end());case DUPLICATE_AFTER->{String copy=uniqueId(request.content().substring(sourceRange.start(),sourceRange.end()));updated=request.content().substring(0,sourceRange.end())+copy+request.content().substring(sourceRange.end());}case MOVE_BEFORE,MOVE_AFTER->{YamlDocumentNode target=find(parsed.root(),request.targetPath());if(target==null||target.path().isEmpty()||source.path().equals(target.path())||target.path().startsWith(source.path()+"/"))throw bad("Invalid structural destination");YamlDocumentNode sourceParent=find(parsed.root(),parentPath(source.path())),targetParent=find(parsed.root(),parentPath(target.path()));if(sourceParent==null||targetParent==null||!sourceParent.kind().equals("sequence")||!targetParent.kind().equals("sequence"))throw bad("Only compatible ordered list items can be moved");Range targetRange=range(request.content(),target);String block=reindent(request.content().substring(sourceRange.start(),sourceRange.end()),indentAt(request.content(),sourceRange.start()),indentAt(request.content(),targetRange.start()));String without=request.content().substring(0,sourceRange.start())+request.content().substring(sourceRange.end());int insertion=request.operation()==YamlStructureRequest.Operation.MOVE_BEFORE?targetRange.start():targetRange.end();if(sourceRange.start()<insertion)insertion-=sourceRange.end()-sourceRange.start();updated=without.substring(0,insertion)+block+without.substring(insertion);}default->throw bad("Unsupported structural operation");}YamlDocumentResponse result=parse(updated);if(!result.valid())throw bad("The structural edit did not produce valid YAML");return result;}

    public YamlDocumentResponse insert(YamlInsertRequest request){if(request==null||request.yaml()==null||request.yaml().isBlank()||request.yaml().getBytes(StandardCharsets.UTF_8).length>65_536)throw bad("Invalid visual block template");YamlDocumentResponse parsed=parse(request.content());YamlDocumentNode parent=find(parsed.root(),request.parentPath());if(!parsed.valid()||parent==null||!parent.kind().equals("sequence")||parent.children().isEmpty())throw bad("Choose a non-empty compatible ordered list");YamlDocumentResponse fragment=parse("items:\n  "+request.yaml().replace("\n","\n  "));if(!fragment.valid()||fragment.root().children().isEmpty()||!fragment.root().children().getFirst().kind().equals("sequence")||fragment.root().children().getFirst().children().size()!=1)throw bad("Template must contain exactly one YAML list item");YamlDocumentNode last=parent.children().getLast();Range lastRange=range(request.content(),last);String block=request.yaml();if(!block.endsWith("\n"))block+='\n';block=reindent(block,indentAt(block,0),indentAt(request.content(),lastRange.start()));String updated=request.content().substring(0,lastRange.end())+block+request.content().substring(lastRange.end());YamlDocumentResponse result=parse(updated);if(!result.valid())throw bad("The inserted block did not produce valid YAML");return result;}

    public YamlDocumentResponse insertField(YamlMappingInsertRequest request){if(request==null||request.key()==null||!request.key().matches("[a-zA-Z0-9_.:-]{1,128}")||request.yamlValue()==null||request.yamlValue().getBytes(StandardCharsets.UTF_8).length>65_536)throw bad("Invalid visual mapping template");YamlDocumentResponse parsed=parse(request.content());YamlDocumentNode parent=find(parsed.root(),request.parentPath());if(!parsed.valid()||parent==null||!parent.kind().equals("mapping")||parent.children().isEmpty()||parent.children().stream().anyMatch(child->request.key().equals(child.key())))throw bad("Choose a non-empty mapping without that key");String candidate=request.key()+":\n"+indent(request.yamlValue(),2);YamlDocumentResponse fragment=parse(candidate);if(!fragment.valid())throw bad("Invalid mapping value template");YamlDocumentNode last=parent.children().getLast();Range lastRange=range(request.content(),last);int targetIndent=indentAt(request.content(),range(request.content(),parent.children().getFirst()).start());String block=reindent(candidate,0,targetIndent);if(!block.endsWith("\n"))block+='\n';String updated=request.content().substring(0,lastRange.end())+block+request.content().substring(lastRange.end());YamlDocumentResponse result=parse(updated);if(!result.valid())throw bad("The inserted mapping field did not produce valid YAML");return result;}

    public YamlExtractResponse extractSubtree(YamlExtractRequest request){if(request==null||request.behaviorId()==null||!request.behaviorId().matches("[a-z0-9][a-z0-9_.:-]{0,127}")||!Set.of("shared","player").contains(request.scope()))throw bad("Invalid subtree identity or scope");YamlDocumentResponse parsed=parse(request.content());YamlDocumentNode node=find(parsed.root(),request.path()),parent=node==null?null:find(parsed.root(),parentPath(node.path()));if(!parsed.valid()||node==null||!node.kind().equals("mapping")||parent==null||!parent.kind().equals("sequence"))throw bad("Only a complete behavior branch can be extracted");Range range=range(request.content(),node);String segment=request.content().substring(range.start(),range.end()),root=sequenceItemToMapping(segment);String extracted="# Extracted losslessly by the Persona visual editor.\nid: "+request.behaviorId()+"\nscope: "+request.scope()+"\nroot:\n"+indent(root,2);if(!extracted.endsWith("\n"))extracted+='\n';YamlDocumentResponse extractedModel=parse(extracted);if(!extractedModel.valid())throw bad("Selected branch cannot form a standalone subtree");String localId=Optional.ofNullable(node.children().stream().filter(child->child.key().equals("id")).findFirst().map(YamlDocumentNode::value).orElse(null)).orElse("subtree-"+UUID.randomUUID().toString().substring(0,8));String replacement=" ".repeat(indentAt(request.content(),range.start()))+"- id: "+localId+"\n"+" ".repeat(indentAt(request.content(),range.start())+2)+"type: subtree\n"+" ".repeat(indentAt(request.content(),range.start())+2)+"subtree: "+request.behaviorId()+"\n";String updated=request.content().substring(0,range.start())+replacement+request.content().substring(range.end());YamlDocumentResponse source=parse(updated);if(!source.valid())throw bad("Subtree replacement did not produce valid YAML");return new YamlExtractResponse(source,request.behaviorId(),extracted);}

    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setProcessComments(true);
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(100);
        options.setCodePointLimit(MAX_DOCUMENT_BYTES);
        return new Yaml(options);
    }

    private static YamlDocumentNode describe(Node source, String path, String key, Mark keyMark, String content,
                                             Map<Node, String> seen) {
        Node node = source instanceof AnchorNode anchor ? anchor.getRealNode() : source;
        String originalPath = seen.putIfAbsent(node, path);
        if (originalPath != null)
            return valueNode(path, key, keyMark, "alias", node.getAnchor() == null ? originalPath : "*" + node.getAnchor(),
                    node, content, false, List.of());
        if (node instanceof MappingNode mapping) {
            List<YamlDocumentNode> children = new ArrayList<>();
            for (NodeTuple tuple : mapping.getValue()) {
                String childKey = tuple.getKeyNode() instanceof ScalarNode scalar ? scalar.getValue() : "<complex key>";
                children.add(describe(tuple.getValueNode(), path + "/" + escape(childKey), childKey,
                        tuple.getKeyNode().getStartMark(), content, seen));
            }
            return valueNode(path, key, keyMark, "mapping", null, node, content, false, children);
        }
        if (node instanceof SequenceNode sequence) {
            List<YamlDocumentNode> children = new ArrayList<>();
            for (int index = 0; index < sequence.getValue().size(); index++)
                children.add(describe(sequence.getValue().get(index), path + "/" + index, "[" + index + "]", null, content, seen));
            return valueNode(path, key, keyMark, "sequence", null, node, content, false, children);
        }
        ScalarNode scalar = (ScalarNode) node;
        String kind = kind(scalar.getTag());
        boolean editable = EDITABLE_TAGS.contains(scalar.getTag()) && scalar.getAnchor() == null;
        return valueNode(path, key, keyMark, kind, scalar.getValue(), scalar, content, editable, List.of());
    }

    private static YamlDocumentNode valueNode(String path, String key, Mark keyMark, String kind, String value,
                                               Node node, String content, boolean editable, List<YamlDocumentNode> children) {
        Mark start = node.getStartMark();
        Mark end = node.getEndMark();
        return new YamlDocumentNode(path, key, kind, value, node.getTag().getValue(), editable,
                keyMark == null ? -1 : utf16Offset(content, keyMark.getIndex()),
                keyMark == null ? -1 : keyMark.getLine() + 1, keyMark == null ? -1 : keyMark.getColumn() + 1,
                utf16Offset(content, start.getIndex()), utf16Offset(content, end.getIndex()),
                start.getLine() + 1, start.getColumn() + 1,
                end.getLine() + 1, end.getColumn() + 1, children);
    }

    private static YamlDocumentNode find(YamlDocumentNode node, String path) {
        if (node == null || path == null) return null;
        if (node.path().equals(path)) return node;
        for (YamlDocumentNode child : node.children()) {
            YamlDocumentNode found = find(child, path);
            if (found != null) return found;
        }
        return null;
    }

    private static String scalar(String kind, String value) {
        String input = value == null ? "" : value;
        try {
            return switch (kind) {
                case "string" -> quote(input);
                case "boolean" -> Boolean.parseBoolean(input) ? "true" : "false";
                case "integer" -> new BigInteger(input.trim()).toString();
                case "number" -> new BigDecimal(input.trim()).stripTrailingZeros().toPlainString();
                case "null" -> "null";
                default -> throw bad("Unsupported scalar type");
            };
        } catch (NumberFormatException error) {
            throw bad("Invalid " + kind + " value");
        }
    }

    private static String kind(Tag tag) {
        if (Tag.STR.equals(tag)) return "string";
        if (Tag.BOOL.equals(tag)) return "boolean";
        if (Tag.INT.equals(tag)) return "integer";
        if (Tag.FLOAT.equals(tag)) return "number";
        if (Tag.NULL.equals(tag)) return "null";
        return "custom";
    }
    private static String quote(String value) {
        StringBuilder output = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) output.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    else output.append(character);
                }
            }
        }
        return output.append('"').toString();
    }
    private static String escape(String part) { return part.replace("~", "~0").replace("/", "~1"); }
    private static int utf16Offset(String content, int codePointOffset) {
        return content.offsetByCodePoints(0, Math.min(codePointOffset, content.codePointCount(0, content.length())));
    }
    private static String parentPath(String path){int slash=path.lastIndexOf('/');return slash<=0?"":path.substring(0,slash);}
    private static Range range(String content,YamlDocumentNode node){int anchor=node.keyOffset()>=0?node.keyOffset():node.startOffset(),start=lineStart(content,anchor),end=lineEnd(content,node.endOffset());return new Range(start,end);}
    private static int lineStart(String content,int offset){int newline=content.lastIndexOf('\n',Math.max(0,offset-1));return newline<0?0:newline+1;}
    private static int lineEnd(String content,int offset){int newline=content.indexOf('\n',Math.max(0,offset));return newline<0?content.length():newline+1;}
    private static int indentAt(String content,int start){int count=0;while(start+count<content.length()&&content.charAt(start+count)==' ')count++;return count;}
    private static String reindent(String value,int from,int to){if(from==to)return value;String prefix=" ".repeat(Math.max(0,to));StringBuilder result=new StringBuilder();for(String line:value.split("(?<=\\n)",-1)){if(line.isEmpty())continue;int remove=0;while(remove<line.length()&&remove<from&&line.charAt(remove)==' ')remove++;result.append(prefix).append(line.substring(remove));}return result.toString();}
    private static String indent(String value,int spaces){String prefix=" ".repeat(spaces);return Arrays.stream(value.split("\\R",-1)).map(line->prefix+line).collect(java.util.stream.Collectors.joining("\n"));}
    private static String uniqueId(String value){String suffix=UUID.randomUUID().toString().substring(0,8);return value.replaceFirst("(?m)^(\\s*(?:-\\s*)?id\\s*:\\s*)([^#\\r\\n]+)","$1duplicate-"+suffix);}
    private static String sequenceItemToMapping(String segment){String[] lines=segment.split("\\R",-1);int base=indentAt(segment,0);StringBuilder result=new StringBuilder();for(int index=0;index<lines.length;index++){String line=lines[index];if(line.isEmpty())continue;int remove=Math.min(line.length(),base);String value=line.substring(remove);if(index==0&&value.startsWith("- "))value=value.substring(2);else if(index>0&&value.startsWith("  "))value=value.substring(2);result.append(value).append('\n');}return result.toString();}
    private record Range(int start,int end){}
    private static void requireBounded(String content) {
        if (content == null) throw bad("Missing YAML content");
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) throw bad("YAML document exceeds 1 MiB");
    }
    private static String safeMessage(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
