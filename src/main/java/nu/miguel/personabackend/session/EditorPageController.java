package nu.miguel.personabackend.session;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public final class EditorPageController {
    @GetMapping(value = "/editor", produces = MediaType.TEXT_HTML_VALUE)
    public String offlineEditor() { return page(""); }

    @GetMapping(value = "/editor/session/{sessionId}", produces = MediaType.TEXT_HTML_VALUE)
    public String editor(@PathVariable UUID sessionId) { return page(sessionId.toString()); }

    private String page(String sessionId) {
        return PAGE.replace("__SESSION_ID__", sessionId)
                .replace("<p id=\"yaml-status\"", "<section id=\"visual-insights\"><header><strong id=\"visual-insights-title\">Structure</strong><button id=\"simulate-open\" type=\"button\">Simulate</button></header><div id=\"visual-graph\"></div><div id=\"visual-preview\"></div></section><p id=\"yaml-status\"")
                .replace("</nav>", "<button id=\"references-open\" type=\"button\">References &amp; rename</button><button id=\"semantic-diff-open\" type=\"button\">Semantic diff</button><button id=\"live-open\" type=\"button\" disabled>Live server</button><button id=\"publish-request\" type=\"button\" disabled>Request publication</button></nav>")
                .replace("</body>", """
                        <dialog id="references-dialog"><section><header><h2>Project references</h2><button id="references-close" type="button">Close</button></header><p id="references-summary"></p><ul id="references-list"></ul><form id="rename-preview-form"><h3>Safe rename preview</h3><label>Content type<select id="rename-type"><option>behavior</option><option>npc</option><option>dialogue</option><option>quest</option><option>script</option></select></label><label>Current ID<input id="rename-current" required></label><label>Replacement ID<input id="rename-replacement" required></label><button>Preview impact</button></form><div id="rename-result" aria-live="polite"></div></section></dialog>
                        <dialog id="semantic-diff-dialog"><section><header><h2>Semantic project diff</h2><button id="semantic-diff-close" type="button">Close</button></header><p id="semantic-diff-summary"></p><ul id="semantic-diff-list"></ul></section></dialog>
                        <dialog id="live-dialog"><section><header><h2>Live server <span id="live-mode">— read only</span></h2><button id="live-close" type="button">Close</button></header><p id="live-status">Not subscribed.</p><div id="live-content"><section><h3>Players</h3><ul id="live-players"></ul></section><section><h3>NPC presentations</h3><ul id="live-npcs"></ul></section><section><h3>Behavior runtimes</h3><ul id="live-behaviors"></ul></section><section><h3>Quests</h3><ul id="live-quests"></ul></section><section><h3>Dialogues</h3><ul id="live-dialogues"></ul></section><section><h3>Memories</h3><ul id="live-memories"></ul></section><section><h3>Server</h3><pre id="live-server"></pre></section><section id="live-controls" hidden><h3>Elevated live controls</h3><form id="behavior-mutation-form"><label>Runtime<select id="behavior-mutation-target" required></select></label><label>Operation<select id="behavior-mutation-operation"><option>PAUSE</option><option>RESUME</option><option>RESTART</option><option>WAKE</option><option>SIGNAL</option></select></label><label>Signal name<input id="behavior-mutation-signal" pattern="[a-z0-9][a-z0-9_.:-]{0,63}"></label><button>Review behavior mutation</button></form><form id="memory-mutation-form"><label>Memory<select id="memory-mutation-target" required></select></label><label>Operation<select id="memory-mutation-operation"><option>SET</option><option>INCREMENT</option><option>EXPIRE</option><option>DELETE</option></select></label><label>Type<select id="memory-mutation-type"><option>STRING</option><option>NUMBER</option><option>BOOLEAN</option><option>TIMESTAMP</option></select></label><label>Value / increment<input id="memory-mutation-value"></label><label>Expiry<input id="memory-mutation-expiry" type="datetime-local"></label><button>Review memory mutation</button></form><p id="mutation-result" role="status"></p></section></div></section></dialog>
                        <dialog id="mutation-confirm"><form method="dialog"><h2>Confirm live mutation</h2><pre id="mutation-confirm-details"></pre><p>This changes live server state immediately.</p><button value="cancel">Cancel</button><button id="mutation-confirm-apply" value="apply">Apply mutation</button></form></dialog>
                        <dialog id="simulation-dialog"><section><header><h2>Deterministic preview</h2><button id="simulation-close" type="button">Close</button></header><label>Mock flags, variables, quests, memories, and events (JSON)<textarea id="simulation-input">{"flags":{},"variables":{},"quests":{},"memories":{},"events":[]}</textarea></label><button id="simulation-run" type="button">Run simulation</button><pre id="simulation-output"></pre></section></dialog>
                        </body>""");
    }

    private static final String PAGE = """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Persona Editor</title><link rel="stylesheet" href="/editor/style.css">
            <script type="module" src="/editor/app.js"></script></head>
            <body data-session-id="__SESSION_ID__"><header><h1>Persona Editor</h1><p id="status" role="status"></p></header>
            <section id="connect" class="panel"><h2>Connect to a server</h2><p>Enter the one-time code shown separately in Minecraft. The session remains read-only until elevated capabilities are explicitly trusted in-game.</p>
            <form id="verify"><label for="code">Verification code</label><input id="code" autocomplete="one-time-code" required maxlength="16"><button>Verify browser</button></form></section>
            <section id="import" class="panel"><h2>Open an offline project</h2><p>YAML and ZIP files are imported without access to a Minecraft server.</p>
            <form id="import-form"><input id="files" name="files" type="file" accept=".yml,.yaml,.zip" multiple required><button>Import project</button></form></section>
            <main id="workspace" hidden><nav><div class="nav-heading"><h2>Project files</h2><button id="palette-open" type="button" title="Command palette (Ctrl/Cmd+K)">⌘</button></div><ul id="project"></ul><div class="project-actions"><button id="export-all" type="button">Download project</button><button id="export-changed" type="button" disabled>Download changed</button></div></nav><section class="editor"><div class="editor-header"><strong id="file-name">Select a file</strong><div class="toolbar"><button id="undo" type="button" disabled title="Undo (Ctrl/Cmd+Z)">Undo</button><button id="redo" type="button" disabled title="Redo (Ctrl/Cmd+Shift+Z)">Redo</button><button id="copy" type="button" disabled>Copy</button><button id="paste" type="button" disabled>Paste</button><button id="diff-toggle" type="button" disabled>Changes</button><button id="download" type="button" disabled>Download file</button></div></div><div class="split"><section class="visual-pane" aria-label="Visual editor"><div class="mode-heading"><strong>Visual</strong><span>Unrecognized fields are shown as preserved custom data.</span></div><form id="visual-tools"><label>Container<select id="visual-container"></select></label><label>Palette<select id="visual-template"></select></label><button>Add block</button></form><p id="yaml-status" role="status"></p><section id="validation-panel" hidden aria-live="polite"><strong id="validation-summary"></strong><ul id="validation-list"></ul></section><div id="visual"></div></section><section class="source-pane" aria-label="YAML editor"><div class="mode-heading"><strong>YAML</strong><span>Authoritative source</span></div><textarea id="source" spellcheck="false" disabled aria-label="YAML source"></textarea><pre id="diff" hidden aria-label="Textual changes"></pre></section></div></section></main>
            <dialog id="palette"><form method="dialog"><label for="palette-search">Command palette</label><input id="palette-search" autocomplete="off" placeholder="Search commands…"><ul id="palette-results"></ul><button value="cancel" class="visually-hidden">Close</button></form></dialog>
            </body></html>
            """;
}
