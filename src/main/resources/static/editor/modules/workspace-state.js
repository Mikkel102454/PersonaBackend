/** Normalized editor state. Content, projections, layouts, validation, live data, and history remain separate. */
export function createWorkspaceState() {
  return {
    files: new Map(), original: new Map(), selected: null, socket: null, connected: false,
    connectionGeneration: 0, socketSequence: 0, peerSequence: 0, reconnectAttempt: 0, reconnectTimer: null,
    verified: null, privateKey: null, heartbeat: null, baseRevision: null, currentRevision: null,
    draftId: null, autosaveTimer: null, capabilityTimer: null, saving: false, saveAgain: false,
    documentModels: new Map(), originalModels: new Map(), documentValidity: new Map(),
    parseTimer: null, parseGeneration: 0, selectedNode: null,
    histories: new Map(), recoveryTimer: null, recordingInput: false,
    validationRequest: null, validationResult: null, publishTimer: null,
    editorSchemas: new Map(), editorCatalogs: new Map(), metadataRevision: null,
    catalogRequests: new Map(), catalogCache: new Map(), installationIdentity: null,
    referenceGraph: { declarations: [], references: [] }, liveSubscription: null, liveRevision: 0,
    liveData: { players: new Map(), npcs: new Map(), behaviors: new Map(), quests: new Map(),
      dialogues: new Map(), memories: new Map(), server: null }, liveStaleTimer: null,
    pendingMutation: null, mutationRequests: new Map(), dragPath: null, pendingYamlPath: null,
    graphGeneration: 0, graphProjections: new Map(), relationshipMode: false,
    graphClipboard: null, graphMutationInFlight: false, graphTabContexts: new Map(), nestedGraph: null
  };
}
