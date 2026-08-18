package nu.miguel.personabackend.graph;

import java.util.*;

/**
 * Versioned, source-ranged projection of authoritative YAML. Nodes retain embedded ports for
 * browser compatibility; {@link #ports()} is the normalized contract used for validation.
 */
public record EditorGraphProjection(
        int graphVersion,
        String resourceIdentity,
        String resourceKind,
        String resourceId,
        String filePath,
        String rootYamlPath,
        String contentDigest,
        boolean editable,
        List<GraphNode> nodes,
        List<GraphEdge> edges,
        List<GraphDiagnostic> diagnostics,
        List<String> capabilities,
        String schemaCatalogVersion,
        String readOnlyReason,
        List<GraphResource> resources,
        List<GraphPin> ports,
        List<SourceRangeEntry> sourceRanges,
        List<GraphField> editableFields,
        Map<String, String> identityRemap) {
    public static final int VERSION = 3;

    public EditorGraphProjection {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        schemaCatalogVersion = schemaCatalogVersion == null ? "builtin-v1" : schemaCatalogVersion;
        readOnlyReason = editable ? null : readOnlyReason == null ? "Graph is read only" : readOnlyReason;
        resources = resources == null || resources.isEmpty()
                ? List.of(new GraphResource(resourceIdentity, resourceKind, resourceId, filePath, rootYamlPath, true))
                : List.copyOf(resources);
        sourceRanges = sourceRanges == null || sourceRanges.isEmpty()
                ? deriveRanges(filePath, nodes) : List.copyOf(sourceRanges);
        Map<String, SourceRange> rangeByPath = new HashMap<>();
        sourceRanges.forEach(entry -> rangeByPath.putIfAbsent(entry.yamlPath(), entry.range()));
        List<GraphPin> suppliedPorts = ports == null || ports.isEmpty()
                ? nodes.stream().flatMap(node -> node.pins().stream()).toList() : ports;
        Set<String> connectedTargets=edges.stream().map(GraphEdge::targetPinId).collect(java.util.stream.Collectors.toSet());
        ports = suppliedPorts.stream().map(port -> {
            LiteralMetadata literal=port.literal()==null?null:new LiteralMetadata(port.literal().value(),port.literal().defaultValue(),
                    port.literal().hasDefault(),connectedTargets.contains(port.id()),port.literal().editable());
            return port.sourceRange() == null
                ? new GraphPin(port.id(), port.nodeId(), port.direction(), port.semanticType(),
                port.cardinality(), port.required(), port.label(), port.yamlPath(), port.order(),
                rangeByPath.get(port.yamlPath()), port.compatibility(),port.channel(),port.valueType(),
                literal,port.resourceKind()):new GraphPin(port.id(),port.nodeId(),port.direction(),port.semanticType(),port.cardinality(),
                    port.required(),port.label(),port.yamlPath(),port.order(),port.sourceRange(),port.compatibility(),port.channel(),
                    port.valueType(),literal,port.resourceKind());}).toList();
        editableFields = editableFields == null || editableFields.isEmpty()
                ? nodes.stream().flatMap(node -> node.fields().stream()).toList() : List.copyOf(editableFields);
        identityRemap = identityRemap == null ? Map.of() : Map.copyOf(identityRemap);
        validate(nodes, ports, edges);
    }

    /** Compatibility constructor for projection builders while the transport uses the v2 shape. */
    public EditorGraphProjection(int graphVersion, String resourceIdentity, String resourceKind,
                                 String resourceId, String filePath, String rootYamlPath,
                                 String contentDigest, boolean editable, List<GraphNode> nodes,
                                 List<GraphEdge> edges, List<GraphDiagnostic> diagnostics,
                                 List<String> capabilities) {
        this(graphVersion, resourceIdentity, resourceKind, resourceId, filePath, rootYamlPath,
                contentDigest, editable, nodes, edges, diagnostics, capabilities, "builtin-v1",
                editable ? null : "Graph is read only", List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    private static List<SourceRangeEntry> deriveRanges(String filePath, List<GraphNode> nodes) {
        LinkedHashMap<String, SourceRangeEntry> ranges = new LinkedHashMap<>();
        for (GraphNode node : nodes) {
            ranges.putIfAbsent(node.yamlPath(), new SourceRangeEntry("node:" + node.id(), filePath,
                    node.yamlPath(), node.range()));
            for (GraphField field : node.fields()) ranges.putIfAbsent(field.yamlPath(),
                    new SourceRangeEntry("field:" + field.id(), filePath, field.yamlPath(), field.range()));
        }
        return List.copyOf(ranges.values());
    }

    private static void validate(List<GraphNode> nodes, List<GraphPin> ports, List<GraphEdge> edges) {
        Set<String> nodeIds = new HashSet<>(), portIds = new HashSet<>();
        for (GraphNode node : nodes) if (!nodeIds.add(node.id())) throw new IllegalArgumentException("Duplicate graph node ID");
        for (GraphPin port : ports) {
            if (!portIds.add(port.id())) throw new IllegalArgumentException("Duplicate graph port ID");
            if (!nodeIds.contains(port.nodeId())) throw new IllegalArgumentException("Graph port owner is absent");
        }
        Set<String> edgeIds = new HashSet<>();
        Map<String, Integer> incoming = new HashMap<>();
        for (GraphEdge edge : edges) {
            if (!edgeIds.add(edge.id())) throw new IllegalArgumentException("Duplicate graph edge ID");
            GraphPin source = ports.stream().filter(port -> port.id().equals(edge.sourcePinId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Graph edge source port is absent"));
            GraphPin target = ports.stream().filter(port -> port.id().equals(edge.targetPinId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Graph edge target port is absent"));
            if (!source.direction().equals("OUTPUT") || !target.direction().equals("INPUT"))
                throw new IllegalArgumentException("Graph edges must run from OUTPUT to INPUT ports");
            if(!source.channel().equals(target.channel()))throw new IllegalArgumentException("Graph edge mixes execution and data channels");
            if(source.channel().equals("DATA")&&!source.valueType().equals(target.valueType()))
                throw new IllegalArgumentException("Graph data edge requires exact nominal value types");
            if(source.channel().equals("EXECUTION")&&(!compatible(source.semanticType(),edge.semanticType())
                    ||!compatible(target.semanticType(),edge.semanticType())))
                throw new IllegalArgumentException("Graph execution edge is incompatible with an endpoint");
            incoming.merge(target.id(), 1, Integer::sum);
        }
        for (GraphPin port : ports) if (Set.of("ZERO_OR_ONE", "EXACTLY_ONE").contains(port.cardinality())
                && incoming.getOrDefault(port.id(), 0) > 1)
            throw new IllegalArgumentException("Graph input cardinality is exceeded");
    }

    private static boolean compatible(String portType, String edgeType) {
        return Objects.equals(portType, edgeType)
                || "reference".equals(portType) && edgeType != null && edgeType.endsWith("-reference")
                || "reference".equals(portType) && edgeType != null && edgeType.startsWith("reference:")
                || portType != null && portType.startsWith("reference:") && "reference".equals(edgeType);
    }

    public record SourceRange(int startOffset, int endOffset, int startLine, int startColumn,
                              int endLine, int endColumn) {}
    public record SourceRangeEntry(String id, String filePath, String yamlPath, SourceRange range) {}
    public record GraphResource(String identity, String kind, String id, String filePath,
                                String rootYamlPath, boolean openResource) {}
    public record GraphField(String id, String label, String yamlPath, SourceRange range,
                             String valueType, String value, boolean editable, boolean required,
                             boolean custom) {}
    public record PortCompatibility(List<String> semanticTypes, List<String> resourceScopes,
                                    String cyclePolicy, List<String> capabilityRequirements) {
        public PortCompatibility {
            semanticTypes = semanticTypes == null ? List.of() : List.copyOf(semanticTypes);
            resourceScopes = resourceScopes == null ? List.of("CURRENT_RESOURCE") : List.copyOf(resourceScopes);
            cyclePolicy = cyclePolicy == null ? "DENY" : cyclePolicy;
            capabilityRequirements = capabilityRequirements == null ? List.of("CONNECT") : List.copyOf(capabilityRequirements);
        }
    }
    public record LiteralMetadata(String value,String defaultValue,boolean hasDefault,
                                  boolean connected,boolean editable) {}
    public record GraphPin(String id, String nodeId, String direction, String semanticType,
                           String cardinality, boolean required, String label, String yamlPath,
                           Integer order, SourceRange sourceRange, PortCompatibility compatibility,
                           String channel,String valueType,LiteralMetadata literal,String resourceKind) {
        public GraphPin {
            direction = direction == null ? "" : direction.toUpperCase(Locale.ROOT);
            channel = channel == null ? inferChannel(semanticType) : channel.toUpperCase(Locale.ROOT);
            valueType = valueType == null ? inferValueType(channel,semanticType) : valueType;
            cardinality = normalizeCardinality(cardinality, required);
            compatibility = compatibility == null
                    ? new PortCompatibility(List.of(semanticType), List.of("CURRENT_RESOURCE"), "DENY", List.of("CONNECT"))
                    : compatibility;
            literal = literal == null ? new LiteralMetadata(null,null,false,false,channel.equals("DATA")&&direction.equals("INPUT")) : literal;
            resourceKind = resourceKind == null&&valueType!=null&&valueType.startsWith("reference:")?valueType.substring("reference:".length()):resourceKind;
            if(!Set.of("EXECUTION","DATA").contains(channel))throw new IllegalArgumentException("Unknown graph port channel");
            if(channel.equals("EXECUTION")&&!valueType.equals("execution"))throw new IllegalArgumentException("Execution ports require the execution value type");
        }
        public GraphPin(String id,String nodeId,String direction,String semanticType,String cardinality,
                        boolean required,String label,String yamlPath,Integer order,SourceRange sourceRange,
                        PortCompatibility compatibility){this(id,nodeId,direction,semanticType,cardinality,required,
                label,yamlPath,order,sourceRange,compatibility,null,null,null,null);}
        public GraphPin(String id, String nodeId, String direction, String semanticType,
                        String cardinality, boolean required, String label, String yamlPath) {
            this(id, nodeId, direction, semanticType, cardinality, required, label, yamlPath,
                    inferredOrder(label), null, null);
        }
        private static String inferChannel(String semanticType){return semanticType!=null&&(semanticType.equals("reference")||semanticType.startsWith("reference:" )||semanticType.endsWith("-reference"))?"DATA":"EXECUTION";}
        private static String inferValueType(String channel,String semanticType){if(channel.equals("EXECUTION"))return "execution";if(semanticType==null)return "string";if(semanticType.equals("reference"))return "string";if(semanticType.startsWith("reference:"))return semanticType.substring("reference:".length());if(semanticType.endsWith("-reference"))return semanticType.substring(0,semanticType.length()-"-reference".length());return semanticType;}
        private static Integer inferredOrder(String label) {
            try { return label == null ? null : Integer.parseInt(label) - 1; }
            catch (NumberFormatException ignored) { return null; }
        }
        private static String normalizeCardinality(String value, boolean required) {
            if (value == null) return required ? "EXACTLY_ONE" : "ZERO_OR_ONE";
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "single", "zero_or_one" -> required ? "EXACTLY_ONE" : "ZERO_OR_ONE";
                case "exactly_one" -> "EXACTLY_ONE";
                case "many", "zero_or_many" -> required ? "ONE_OR_MANY" : "ZERO_OR_MANY";
                case "one_or_many" -> "ONE_OR_MANY";
                default -> value.toUpperCase(Locale.ROOT);
            };
        }
    }
    public record GraphNode(String id, String yamlPath, SourceRange range, String kind, String title,
                            String subtitle, List<GraphField> fields, List<GraphPin> pins,
                            List<String> badges, boolean custom, String extensionOwner) {
        public GraphNode {
            fields = fields == null ? List.of() : List.copyOf(fields);
            pins = pins == null ? List.of() : List.copyOf(pins);
            badges = badges == null ? List.of() : List.copyOf(badges);
        }
    }
    public record GraphEdge(String id, String sourcePinId, String targetPinId, String semanticType,
                            String label, String sourceYamlPath, String targetYamlPath,
                            boolean resolved, boolean cyclic, SourceRange sourceRange,
                            SourceRange targetRange) {
        public GraphEdge(String id, String sourcePinId, String targetPinId, String semanticType,
                         String label, String sourceYamlPath, String targetYamlPath,
                         boolean resolved, boolean cyclic) {
            this(id, sourcePinId, targetPinId, semanticType, label, sourceYamlPath,
                    targetYamlPath, resolved, cyclic, null, null);
        }
    }
    public record GraphDiagnostic(String code, String severity, String message, String filePath,
                                  String yamlPath, SourceRange range, String nodeId,
                                  String relatedResourceKind, String relatedResourceId) {}
}
