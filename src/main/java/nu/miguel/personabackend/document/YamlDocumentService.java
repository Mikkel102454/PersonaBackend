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
            OffsetIndex offsets = OffsetIndex.create(content);
            return new YamlDocumentResponse(true, content,
                    describe(root, "", null, null, offsets, new IdentityHashMap<>()), List.of());
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

    public YamlDocumentResponse renameMappingKey(String content, String valuePath, String replacement) {
        if (replacement == null || !replacement.matches("[A-Za-z0-9_.:-]{1,128}"))
            throw bad("Invalid replacement mapping key");
        YamlDocumentResponse parsed = parse(content);
        if (!parsed.valid()) throw bad("Cannot rename a key while YAML has syntax errors");
        YamlDocumentNode target = find(parsed.root(), valuePath);
        if (target == null || target.keyOffset() < 0 || target.key() == null)
            throw bad("The selected YAML mapping key is not safely editable");
        int start = target.keyOffset(), end = start + target.key().length();
        if (end >= content.length() || !content.regionMatches(start, target.key(), 0, target.key().length())
                || content.charAt(end) != ':')
            throw bad("Quoted or complex mapping keys must be renamed in YAML");
        String updated = content.substring(0, start) + replacement + content.substring(end);
        YamlDocumentResponse result = parse(updated);
        if (!result.valid()) throw bad("Mapping-key rename did not produce valid YAML");
        return result;
    }

    public YamlDocumentResponse structure(YamlStructureRequest request){if(request==null||request.operation()==null)throw bad("Missing YAML structure request");YamlDocumentResponse parsed=parse(request.content());if(!parsed.valid())throw bad("Cannot structurally edit YAML while it has syntax errors");YamlDocumentNode source=find(parsed.root(),request.path());if(source==null||source.path().isEmpty())throw bad("Invalid structural source");Range sourceRange=range(request.content(),parsed.root(),source);String updated;switch(request.operation()){case DELETE->updated=request.content().substring(0,sourceRange.start())+request.content().substring(sourceRange.end());case DUPLICATE_AFTER->{String copy=uniqueId(request.content().substring(sourceRange.start(),sourceRange.end()));updated=request.content().substring(0,sourceRange.end())+copy+request.content().substring(sourceRange.end());}case MOVE_BEFORE,MOVE_AFTER->{YamlDocumentNode target=find(parsed.root(),request.targetPath());if(target==null||target.path().isEmpty()||source.path().equals(target.path())||target.path().startsWith(source.path()+"/"))throw bad("Invalid structural destination");YamlDocumentNode sourceParent=find(parsed.root(),parentPath(source.path())),targetParent=find(parsed.root(),parentPath(target.path()));if(sourceParent==null||targetParent==null||!sourceParent.kind().equals("sequence")||!targetParent.kind().equals("sequence"))throw bad("Only compatible ordered list items can be moved");Range targetRange=range(request.content(),parsed.root(),target);String block=reindent(request.content().substring(sourceRange.start(),sourceRange.end()),indentAt(request.content(),sourceRange.start()),indentAt(request.content(),targetRange.start()));String without=request.content().substring(0,sourceRange.start())+request.content().substring(sourceRange.end());int insertion=request.operation()==YamlStructureRequest.Operation.MOVE_BEFORE?targetRange.start():targetRange.end();if(sourceRange.start()<insertion)insertion-=sourceRange.end()-sourceRange.start();updated=without.substring(0,insertion)+block+without.substring(insertion);}default->throw bad("Unsupported structural operation");}YamlDocumentResponse result=parse(updated);if(!result.valid())throw bad("The structural edit did not produce valid YAML");return result;}

    public YamlDocumentResponse insert(YamlInsertRequest request){if(request==null||request.yaml()==null||request.yaml().isBlank()||request.yaml().getBytes(StandardCharsets.UTF_8).length>65_536)throw bad("Invalid visual block template");YamlDocumentResponse parsed=parse(request.content());YamlDocumentNode parent=find(parsed.root(),request.parentPath());if(!parsed.valid()||parent==null||!parent.kind().equals("sequence")||parent.children().isEmpty())throw bad("Choose a non-empty compatible ordered list");YamlDocumentResponse fragment=parse("items:\n  "+request.yaml().replace("\n","\n  "));if(!fragment.valid()||fragment.root().children().isEmpty()||!fragment.root().children().getFirst().kind().equals("sequence")||fragment.root().children().getFirst().children().size()!=1)throw bad("Template must contain exactly one YAML list item");YamlDocumentNode last=parent.children().getLast();Range lastRange=range(request.content(),parsed.root(),last);String block=request.yaml();if(!block.endsWith("\n"))block+='\n';block=reindent(block,indentAt(block,0),indentAt(request.content(),lastRange.start()));String updated=request.content().substring(0,lastRange.end())+block+request.content().substring(lastRange.end());YamlDocumentResponse result=parse(updated);if(!result.valid())throw bad("The inserted block did not produce valid YAML");return result;}

    /**
     * Inserts one complete list item without serializing the containing document. Empty flow-style
     * sequences are expanded only at their exact scalar range; non-empty sequences are spliced at a
     * sibling line boundary. This is the primitive used by graph mutations.
     */
    public YamlDocumentResponse insertSequenceItem(String content, String parentPath, String yaml, Integer index) {
        requireSequenceFragment(yaml);
        YamlDocumentResponse parsed = parse(content);
        if (!parsed.valid()) throw bad("Cannot insert while YAML has syntax errors");
        YamlDocumentNode parent = find(parsed.root(), parentPath);
        if (parent == null || !"sequence".equals(parent.kind())) throw bad("The destination is not a YAML sequence");
        int requested = index == null ? parent.children().size() : index;
        if (requested < 0 || requested > parent.children().size()) throw bad("Sequence insertion index is out of bounds");

        String block = yaml.endsWith("\n") ? yaml : yaml + '\n';
        String updated;
        if (parent.children().isEmpty()) {
            String existing = content.substring(parent.startOffset(), parent.endOffset());
            if (!"[]".equals(existing.trim())) throw bad("An empty custom sequence must be edited in YAML");
            int targetIndent = Math.max(0, parent.keyColumn() - 1) + 2;
            block = reindent(block, indentAt(block, 0), targetIndent);
            updated = content.substring(0, parent.startOffset()) + "\n" + block + content.substring(parent.endOffset());
        } else {
            YamlDocumentNode sibling = requested == parent.children().size()
                    ? parent.children().getLast() : parent.children().get(requested);
            Range siblingRange = range(content, parsed.root(), sibling);
            int insertion = requested == parent.children().size() ? siblingRange.end() : siblingRange.start();
            block = reindent(block, indentAt(block, 0), indentAt(content, siblingRange.start()));
            updated = content.substring(0, insertion) + block + content.substring(insertion);
        }
        YamlDocumentResponse result = parse(updated);
        if (!result.valid()) throw bad("The sequence insertion did not produce valid YAML: "
                + (result.diagnostics().isEmpty() ? "unknown parse error" : result.diagnostics().getFirst().message()
                + " at line " + result.diagnostics().getFirst().line() + ", column "
                + result.diagnostics().getFirst().column()));
        return result;
    }

    /** Moves an exact sequence-item source range into another sequence, preserving its bytes. */
    public YamlDocumentResponse moveSequenceItem(String content, String sourcePath,
                                                  String destinationSequencePath, Integer index) {
        YamlDocumentResponse parsed = parse(content);
        if (!parsed.valid()) throw bad("Cannot rewire while YAML has syntax errors");
        YamlDocumentNode source = find(parsed.root(), sourcePath);
        YamlDocumentNode sourceParent = source == null ? null : find(parsed.root(), parentPath(sourcePath));
        YamlDocumentNode destination = find(parsed.root(), destinationSequencePath);
        if (source == null || source.path().isEmpty() || sourceParent == null
                || !"sequence".equals(sourceParent.kind()) || destination == null
                || !"sequence".equals(destination.kind())) throw bad("Only complete sequence items can be rewired");
        if (destination.path().equals(source.path()) || destination.path().startsWith(source.path() + "/"))
            throw bad("A node cannot be connected below itself");
        Integer adjustedIndex = index;
        if (adjustedIndex != null && sourceParent.path().equals(destination.path())) {
            int sourceIndex = sourceParent.children().indexOf(source);
            if (sourceIndex >= 0 && sourceIndex < adjustedIndex) adjustedIndex--;
        }
        Range sourceRange = range(content, parsed.root(), source);
        String block = content.substring(sourceRange.start(), sourceRange.end());
        String without = content.substring(0, sourceRange.start()) + content.substring(sourceRange.end());
        return insertSequenceItem(without, destinationSequencePath, block, adjustedIndex);
    }

    /** Copies one exact sequence item between draft documents and changes only its stable ID. */
    public YamlDocumentResponse copySequenceItem(String targetContent, String destinationSequencePath,
                                                  String sourceContent, String sourcePath, Integer index,
                                                  String replacementId) {
        if (replacementId == null || !replacementId.matches("[a-z0-9][a-z0-9_.-]{0,127}"))
            throw bad("A copied behavior node requires a new lowercase stable ID");
        YamlDocumentResponse parsed = parse(sourceContent);
        if (!parsed.valid()) throw bad("Cannot copy from invalid YAML");
        YamlDocumentNode source = find(parsed.root(), sourcePath);
        YamlDocumentNode parent = source == null ? null : find(parsed.root(), parentPath(sourcePath));
        YamlDocumentNode id = source == null ? null : source.children().stream()
                .filter(child -> Objects.equals(child.key(), "id")).findFirst().orElse(null);
        if (source == null || parent == null || !"sequence".equals(parent.kind()) || id == null || !id.editable())
            throw bad("Only complete behavior list nodes with stable IDs can be copied visually");
        Range selected = range(sourceContent, parsed.root(), source);
        if (id.startOffset() < selected.start() || id.endOffset() > selected.end())
            throw bad("The copied node ID is outside its exact source range");
        String block = sourceContent.substring(selected.start(), id.startOffset()) + scalar(id.kind(), replacementId)
                + sourceContent.substring(id.endOffset(), selected.end());
        return insertSequenceItem(targetContent, destinationSequencePath, block, index);
    }

    /** Replaces one complete list item with one server-owned list-item template. */
    public YamlDocumentResponse replaceSequenceItem(String content, String sourcePath, String yaml) {
        requireSequenceFragment(yaml);
        YamlDocumentResponse parsed = parse(content);
        if (!parsed.valid()) throw bad("Cannot replace an item while YAML has syntax errors");
        YamlDocumentNode source = find(parsed.root(), sourcePath);
        YamlDocumentNode parent = source == null ? null : find(parsed.root(), parentPath(sourcePath));
        if (source == null || parent == null || !"sequence".equals(parent.kind()))
            throw bad("Only a complete sequence item can be extracted");
        Range selected = range(content, parsed.root(), source);
        String block = yaml.endsWith("\n") ? yaml : yaml + '\n';
        block = reindent(block, indentAt(block, 0), indentAt(content, selected.start()));
        YamlDocumentResponse result = parse(content.substring(0, selected.start()) + block + content.substring(selected.end()));
        if (!result.valid()) throw bad("The extraction replacement did not produce valid YAML");
        return result;
    }

    /** Removes one complete mapping entry at its source line boundary. */
    public YamlDocumentResponse deleteMappingField(String content, String valuePath) {
        YamlDocumentResponse parsed = parse(content);
        if (!parsed.valid()) throw bad("Cannot remove a field while YAML has syntax errors");
        YamlDocumentNode node = find(parsed.root(), valuePath);
        YamlDocumentNode parent = node == null ? null : find(parsed.root(), parentPath(valuePath));
        if (node == null || node.keyOffset() < 0 || parent == null || !"mapping".equals(parent.kind()))
            throw bad("The selected mapping field is not safely removable");
        Range selected = range(content, parsed.root(), node);
        YamlDocumentResponse result = parse(content.substring(0, selected.start()) + content.substring(selected.end()));
        if (!result.valid()) throw bad("The field removal did not produce valid YAML");
        return result;
    }

    /** Moves one mapping entry beside a sibling without serializing either value or their neighbors. */
    public YamlDocumentResponse moveMappingField(String content, String sourcePath, String targetPath,
                                                 boolean beforeTarget) {
        YamlDocumentResponse parsed = parse(content);
        if (!parsed.valid()) throw bad("Cannot reorder a field while YAML has syntax errors");
        YamlDocumentNode source = find(parsed.root(), sourcePath), target = find(parsed.root(), targetPath);
        YamlDocumentNode sourceParent = source == null ? null : find(parsed.root(), parentPath(sourcePath));
        YamlDocumentNode targetParent = target == null ? null : find(parsed.root(), parentPath(targetPath));
        if (source == null || target == null || source.path().equals(target.path())
                || sourceParent == null || targetParent == null || !sourceParent.path().equals(targetParent.path())
                || !"mapping".equals(sourceParent.kind())) throw bad("Mapping fields can only move beside a sibling");
        Range sourceRange = range(content, parsed.root(), source), targetRange = range(content, parsed.root(), target);
        String block = content.substring(sourceRange.start(), sourceRange.end());
        String without = content.substring(0, sourceRange.start()) + content.substring(sourceRange.end());
        int insertion = beforeTarget ? targetRange.start() : targetRange.end();
        if (sourceRange.start() < insertion) insertion -= sourceRange.end() - sourceRange.start();
        String updated = without.substring(0, insertion) + block + without.substring(insertion);
        YamlDocumentResponse result = parse(updated);
        if (!result.valid()) throw bad("The mapping reorder did not produce valid YAML");
        return result;
    }

    /** Copies one complete mapping entry, changing only its stable key and indentation. */
    public YamlDocumentResponse copyMappingField(String targetContent, String targetParentPath,
                                                 String sourceContent, String sourcePath, String replacementKey) {
        if (replacementKey == null || !replacementKey.matches("[a-z0-9][a-z0-9_.-]{0,127}"))
            throw bad("Invalid copied mapping key");
        YamlDocumentResponse sourceDocument = parse(sourceContent), targetDocument = parse(targetContent);
        YamlDocumentNode source = sourceDocument.valid() ? find(sourceDocument.root(), sourcePath) : null;
        YamlDocumentNode sourceParent = source == null ? null : find(sourceDocument.root(), parentPath(sourcePath));
        YamlDocumentNode targetParent = targetDocument.valid() ? find(targetDocument.root(), targetParentPath) : null;
        if (source == null || source.key() == null || source.keyOffset() < 0 || sourceParent == null
                || !"mapping".equals(sourceParent.kind()) || targetParent == null || !"mapping".equals(targetParent.kind())
                || targetParent.children().stream().anyMatch(child -> replacementKey.equals(child.key())))
            throw bad("Choose a complete mapping node and a destination without that key");
        Range sourceRange = range(sourceContent, sourceDocument.root(), source);
        int relativeKey = source.keyOffset() - sourceRange.start();
        String block = sourceContent.substring(sourceRange.start(), sourceRange.end());
        if (relativeKey < 0 || relativeKey + source.key().length() > block.length()
                || !block.regionMatches(relativeKey, source.key(), 0, source.key().length()))
            throw bad("The copied mapping key is not a plain stable key");
        block = block.substring(0, relativeKey) + replacementKey
                + block.substring(relativeKey + source.key().length());
        String updated;
        if (targetParent.children().isEmpty()) {
            String existing = targetContent.substring(targetParent.startOffset(), targetParent.endOffset());
            int lineEnd = lineEnd(targetContent, targetParent.endOffset());
            if (!existing.equals("{}") || !targetContent.substring(targetParent.endOffset(), lineEnd).trim().isEmpty())
                throw bad("An empty custom mapping with inline data must be edited in YAML");
            int targetIndent = Math.max(0, targetParent.keyColumn() - 1) + 2;
            block = reindent(block, indentAt(sourceContent, sourceRange.start()), targetIndent);
            if (!block.endsWith("\n")) block += '\n';
            updated = targetContent.substring(0, targetParent.startOffset()) + "\n" + block
                    + targetContent.substring(targetParent.endOffset());
        } else {
            YamlDocumentNode last = targetParent.children().getLast();
            Range lastRange = range(targetContent, targetDocument.root(), last);
            int targetIndent = indentAt(targetContent,
                    range(targetContent, targetDocument.root(), targetParent.children().getFirst()).start());
            block = reindent(block, indentAt(sourceContent, sourceRange.start()), targetIndent);
            if (!block.endsWith("\n")) block += '\n';
            updated = targetContent.substring(0, lastRange.end()) + block + targetContent.substring(lastRange.end());
        }
        YamlDocumentResponse result = parse(updated);
        if (!result.valid()) throw bad("The mapping copy did not produce valid YAML");
        return result;
    }

    /** Wraps one list item in a server-defined behaviour container without touching sibling bytes. */
    public YamlDocumentResponse wrapSequenceItem(String content, String sourcePath, String wrapperId,
                                                 String wrapperType) {
        if (wrapperId == null || !wrapperId.matches("[a-z0-9][a-z0-9_.-]{0,127}")
                || !Set.of("sequence", "selector", "priority-selector", "parallel").contains(wrapperType))
            throw bad("Invalid graph wrapper");
        YamlDocumentResponse parsed = parse(content);
        YamlDocumentNode source = parsed.valid() ? find(parsed.root(), sourcePath) : null;
        YamlDocumentNode parent = source == null ? null : find(parsed.root(), parentPath(sourcePath));
        if (source == null || parent == null || !"sequence".equals(parent.kind()))
            throw bad("Only a complete list node can be wrapped");
        Range selected = range(content, parsed.root(), source);
        int base = indentAt(content, selected.start());
        String original = content.substring(selected.start(), selected.end());
        String wrapper = " ".repeat(base) + "- id: " + wrapperId + "\n"
                + " ".repeat(base + 2) + "type: " + wrapperType + "\n"
                + " ".repeat(base + 2) + "children:\n"
                + reindent(original, base, base + 4);
        YamlDocumentResponse result = parse(content.substring(0, selected.start()) + wrapper
                + content.substring(selected.end()));
        if (!result.valid()) throw bad("The wrapper did not produce valid YAML");
        return result;
    }

    /** Unwraps a pure behaviour container. Containers with extra semantic fields are rejected. */
    public YamlDocumentResponse unwrapSequenceItem(String content, String wrapperPath) {
        YamlDocumentResponse parsed = parse(content);
        YamlDocumentNode wrapper = parsed.valid() ? find(parsed.root(), wrapperPath) : null;
        YamlDocumentNode parent = wrapper == null ? null : find(parsed.root(), parentPath(wrapperPath));
        YamlDocumentNode children = wrapper == null ? null : wrapper.children().stream()
                .filter(child -> "children".equals(child.key())).findFirst().orElse(null);
        if (wrapper == null || parent == null || !"sequence".equals(parent.kind())
                || !"mapping".equals(wrapper.kind()) || children == null || !"sequence".equals(children.kind())
                || children.children().isEmpty()
                || wrapper.children().stream().anyMatch(child -> !Set.of("id", "type", "children").contains(child.key())))
            throw bad("Only a pure non-empty behaviour container can be unwrapped safely");
        Range selected = range(content, parsed.root(), wrapper);
        int base = indentAt(content, selected.start());
        StringBuilder replacement = new StringBuilder();
        for (YamlDocumentNode child : children.children()) {
            Range childRange = range(content, parsed.root(), child);
            replacement.append(reindent(content.substring(childRange.start(), childRange.end()),
                    indentAt(content, childRange.start()), base));
        }
        YamlDocumentResponse result = parse(content.substring(0, selected.start()) + replacement
                + content.substring(selected.end()));
        if (!result.valid()) throw bad("The unwrap did not produce valid YAML");
        return result;
    }

    public YamlDocumentResponse insertField(YamlMappingInsertRequest request) {
        if (request == null || request.key() == null || !request.key().matches("[a-zA-Z0-9_.:-]{1,128}")
                || request.yamlValue() == null
                || request.yamlValue().getBytes(StandardCharsets.UTF_8).length > 65_536)
            throw bad("Invalid visual mapping template");
        YamlDocumentResponse parsed = parse(request.content());
        YamlDocumentNode parent = find(parsed.root(), request.parentPath());
        boolean implicitNullParent = parent != null && "null".equals(parent.kind())
                && parent.startOffset() == parent.endOffset()
                && request.content().substring(parent.endOffset(),
                lineEnd(request.content(), parent.endOffset())).trim().isEmpty();
        if (!parsed.valid() || parent == null
                || (!parent.kind().equals("mapping") && !implicitNullParent)
                || parent.children().stream().anyMatch(child -> request.key().equals(child.key())))
            throw bad("Choose a mapping without that key");
        String candidate = request.key() + ":\n" + indent(request.yamlValue(), 2);
        YamlDocumentResponse fragment = parse(candidate);
        if (!fragment.valid()) throw bad("Invalid mapping value template");
        String updated;
        String existing = request.content().substring(parent.startOffset(), parent.endOffset());
        boolean scalarValue = !fragment.root().children().isEmpty()
                && fragment.root().children().getFirst().children().isEmpty();
        if (implicitNullParent) {
            int targetIndent = Math.max(0, parent.keyColumn() - 1) + 2;
            String block = reindent(candidate, 0, targetIndent);
            if (!block.endsWith("\n")) block += '\n';
            updated = request.content().substring(0, parent.startOffset()) + "\n" + block
                    + request.content().substring(lineEnd(request.content(), parent.endOffset()));
        } else if (scalarValue && !request.yamlValue().contains("\n")
                && existing.strip().startsWith("{") && existing.strip().endsWith("}")) {
            int closing = existing.lastIndexOf('}');
            int insertionAt = closing;
            while (insertionAt > 0 && Character.isWhitespace(existing.charAt(insertionAt - 1))) insertionAt--;
            String interior = existing.substring(existing.indexOf('{') + 1, closing).trim();
            String insertion = (interior.isEmpty() ? "" : ", ") + request.key() + ": " + request.yamlValue();
            updated = request.content().substring(0, parent.startOffset() + insertionAt) + insertion
                    + request.content().substring(parent.startOffset() + insertionAt);
        } else if (parent.children().isEmpty()) {
            int lineEnd = lineEnd(request.content(), parent.endOffset());
            String suffix = request.content().substring(parent.endOffset(), lineEnd).trim();
            if (!existing.equals("{}") || !suffix.isEmpty())
                throw bad("An empty custom mapping with inline data must be edited in YAML");
            int targetIndent = Math.max(0, parent.keyColumn() - 1) + 2;
            String block = reindent(candidate, 0, targetIndent);
            if (!block.endsWith("\n")) block += '\n';
            updated = request.content().substring(0, parent.startOffset()) + "\n" + block
                    + request.content().substring(parent.endOffset());
        } else {
            YamlDocumentNode last = parent.children().getLast();
            Range lastRange = range(request.content(), parsed.root(), last);
            int targetIndent = Math.max(parent.startColumn() - 1,
                    indentAt(request.content(), range(request.content(), parsed.root(), parent.children().getFirst()).start()));
            String block = reindent(candidate, 0, targetIndent);
            if (!block.endsWith("\n")) block += '\n';
            updated = request.content().substring(0, lastRange.end()) + block
                    + request.content().substring(lastRange.end());
        }
        YamlDocumentResponse result = parse(updated);
        if (!result.valid()) throw bad("The inserted mapping field did not produce valid YAML: "
                + (result.diagnostics().isEmpty() ? "unknown parser error" : result.diagnostics().getFirst().message()
                + " at " + result.diagnostics().getFirst().line() + ":" + result.diagnostics().getFirst().column()));
        return result;
    }

    public YamlExtractResponse extractSubtree(YamlExtractRequest request){if(request==null||request.behaviorId()==null||!request.behaviorId().matches("[a-z0-9][a-z0-9_.:-]{0,127}")||!Set.of("shared","player").contains(request.scope()))throw bad("Invalid subtree identity or scope");YamlDocumentResponse parsed=parse(request.content());YamlDocumentNode node=find(parsed.root(),request.path()),parent=node==null?null:find(parsed.root(),parentPath(node.path()));if(!parsed.valid()||node==null||!node.kind().equals("mapping")||parent==null||!parent.kind().equals("sequence"))throw bad("Only a complete behavior branch can be extracted");Range range=range(request.content(),parsed.root(),node);String segment=request.content().substring(range.start(),range.end()),root=sequenceItemToMapping(segment);String extracted="# Extracted losslessly by the Persona visual editor.\nid: "+request.behaviorId()+"\nscope: "+request.scope()+"\nroot:\n"+indent(root,2);if(!extracted.endsWith("\n"))extracted+='\n';YamlDocumentResponse extractedModel=parse(extracted);if(!extractedModel.valid())throw bad("Selected branch cannot form a standalone subtree");String localId=Optional.ofNullable(node.children().stream().filter(child->child.key().equals("id")).findFirst().map(YamlDocumentNode::value).orElse(null)).orElse("subtree-"+UUID.randomUUID().toString().substring(0,8));String replacement=" ".repeat(indentAt(request.content(),range.start()))+"- id: "+localId+"\n"+" ".repeat(indentAt(request.content(),range.start())+2)+"type: subtree\n"+" ".repeat(indentAt(request.content(),range.start())+2)+"subtree: "+request.behaviorId()+"\n";String updated=request.content().substring(0,range.start())+replacement+request.content().substring(range.end());YamlDocumentResponse source=parse(updated);if(!source.valid())throw bad("Subtree replacement did not produce valid YAML");return new YamlExtractResponse(source,request.behaviorId(),extracted);}

    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setProcessComments(true);
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(100);
        options.setCodePointLimit(MAX_DOCUMENT_BYTES);
        return new Yaml(options);
    }

    private static YamlDocumentNode describe(Node source, String path, String key, Mark keyMark, OffsetIndex offsets,
                                             Map<Node, String> seen) {
        Node node = source instanceof AnchorNode anchor ? anchor.getRealNode() : source;
        String originalPath = seen.putIfAbsent(node, path);
        if (originalPath != null)
            return valueNode(path, key, keyMark, "alias", node.getAnchor() == null ? originalPath : "*" + node.getAnchor(),
                    node, offsets, false, List.of());
        if (node instanceof MappingNode mapping) {
            List<YamlDocumentNode> children = new ArrayList<>();
            for (NodeTuple tuple : mapping.getValue()) {
                String childKey = tuple.getKeyNode() instanceof ScalarNode scalar ? scalar.getValue() : "<complex key>";
                children.add(describe(tuple.getValueNode(), path + "/" + escape(childKey), childKey,
                        tuple.getKeyNode().getStartMark(), offsets, seen));
            }
            return valueNode(path, key, keyMark, "mapping", null, node, offsets, false, children);
        }
        if (node instanceof SequenceNode sequence) {
            List<YamlDocumentNode> children = new ArrayList<>();
            for (int index = 0; index < sequence.getValue().size(); index++)
                children.add(describe(sequence.getValue().get(index), path + "/" + index, "[" + index + "]", null, offsets, seen));
            return valueNode(path, key, keyMark, "sequence", null, node, offsets, false, children);
        }
        ScalarNode scalar = (ScalarNode) node;
        String kind = kind(scalar.getTag());
        boolean editable = EDITABLE_TAGS.contains(scalar.getTag()) && scalar.getAnchor() == null;
        return valueNode(path, key, keyMark, kind, scalar.getValue(), scalar, offsets, editable, List.of());
    }

    private static YamlDocumentNode valueNode(String path, String key, Mark keyMark, String kind, String value,
                                               Node node, OffsetIndex offsets, boolean editable, List<YamlDocumentNode> children) {
        Mark start = node.getStartMark();
        Mark end = node.getEndMark();
        return new YamlDocumentNode(path, key, kind, value, node.getTag().getValue(), editable,
                keyMark == null ? -1 : offsets.utf16(keyMark.getIndex()),
                keyMark == null ? -1 : keyMark.getLine() + 1, keyMark == null ? -1 : keyMark.getColumn() + 1,
                offsets.utf16(start.getIndex()), offsets.utf16(end.getIndex()),
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
    private record OffsetIndex(int utf16Length, int[] codePointOffsets) {
        static OffsetIndex create(String content) {
            int codePoints = content.codePointCount(0, content.length());
            if (codePoints == content.length()) return new OffsetIndex(content.length(), null);
            int[] offsets = new int[codePoints + 1];
            int utf16 = 0;
            for (int point = 0; point < codePoints; point++) {
                offsets[point] = utf16;
                utf16 += Character.charCount(content.codePointAt(utf16));
            }
            offsets[codePoints] = content.length();
            return new OffsetIndex(content.length(), offsets);
        }
        int utf16(int codePointOffset) {
            if (codePointOffsets == null) return Math.max(0, Math.min(codePointOffset, utf16Length));
            return codePointOffsets[Math.max(0, Math.min(codePointOffset, codePointOffsets.length - 1))];
        }
    }
    private static String parentPath(String path){int slash=path.lastIndexOf('/');return slash<=0?"":path.substring(0,slash);}
    private static Range range(String content,YamlDocumentNode root,YamlDocumentNode node){int anchor=node.keyOffset()>=0?node.keyOffset():node.startOffset(),start=lineStart(content,anchor),end=structuralEnd(content,root,node);if(end<=start)throw bad("Flow-style YAML structures must be edited in raw YAML");return new Range(start,end);}
    private static int structuralEnd(String content,YamlDocumentNode root,YamlDocumentNode node){YamlDocumentNode parent=find(root,parentPath(node.path()));if(parent!=null){int index=parent.children().indexOf(node);if(index>=0&&index+1<parent.children().size()){YamlDocumentNode next=parent.children().get(index+1);int anchor=next.keyOffset()>=0?next.keyOffset():next.startOffset();return lineStart(content,anchor);}if(!parent.path().equals(node.path()))return structuralEnd(content,root,parent);}return content.length();}
    private static int lineStart(String content,int offset){int newline=content.lastIndexOf('\n',Math.max(0,offset-1));return newline<0?0:newline+1;}
    private static int lineEnd(String content,int offset){int newline=content.indexOf('\n',Math.max(0,offset));return newline<0?content.length():newline+1;}
    private static int indentAt(String content,int start){int count=0;while(start+count<content.length()&&content.charAt(start+count)==' ')count++;return count;}
    private static String reindent(String value,int from,int to){if(from==to)return value;String prefix=" ".repeat(Math.max(0,to));StringBuilder result=new StringBuilder();for(String line:value.split("(?<=\\n)",-1)){if(line.isEmpty())continue;int remove=0;while(remove<line.length()&&remove<from&&line.charAt(remove)==' ')remove++;result.append(prefix).append(line.substring(remove));}return result.toString();}
    private static String indent(String value, int spaces) {
        String prefix = " ".repeat(spaces);
        String[] lines = value.split("\\R", -1);
        StringBuilder result = new StringBuilder(value.length() + lines.length * spaces);
        for (int index = 0; index < lines.length; index++) {
            if (!(index == lines.length - 1 && lines[index].isEmpty())) result.append(prefix).append(lines[index]);
            if (index + 1 < lines.length) result.append('\n');
        }
        return result.toString();
    }
    private static String uniqueId(String value){String suffix=UUID.randomUUID().toString().substring(0,8);return value.replaceFirst("(?m)^(\\s*(?:-\\s*)?id\\s*:\\s*)([^#\\r\\n]+)","$1duplicate-"+suffix);}
    private void requireSequenceFragment(String yaml) {
        if (yaml == null || yaml.isBlank() || yaml.getBytes(StandardCharsets.UTF_8).length > 65_536)
            throw bad("Invalid graph node template");
        String normalized = reindent(yaml, indentAt(yaml, 0), 0);
        YamlDocumentResponse fragment = parse("items:\n  " + normalized.replace("\n", "\n  "));
        if (!fragment.valid() || fragment.root() == null || fragment.root().children().isEmpty()
                || !"sequence".equals(fragment.root().children().getFirst().kind())
                || fragment.root().children().getFirst().children().size() != 1)
            throw bad("Graph node template must contain exactly one YAML list item");
    }
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
