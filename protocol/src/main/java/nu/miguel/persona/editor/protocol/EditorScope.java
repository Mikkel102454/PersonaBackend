package nu.miguel.persona.editor.protocol;

import java.util.Locale;

public enum EditorScope {
    ALL, CONTENT, BEHAVIORS, NPCS, DIALOGUES, QUESTS, SCRIPTS;

    public static EditorScope parse(String value) {
        if (value == null || value.isBlank()) return ALL;
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
