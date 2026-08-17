package nu.miguel.persona.editor.protocol;

import java.util.Locale;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/** Immutable least-privilege filters applied again by Persona for every live operation. */
public record SessionRestrictions(
        Set<String> worlds,
        Set<String> playerIds,
        Set<String> npcIds,
        Set<String> contentNamespaces
) {
    public static final SessionRestrictions UNRESTRICTED = new SessionRestrictions(Set.of(), Set.of(), Set.of(), Set.of());

    public SessionRestrictions {
        worlds = normalized(worlds, "world");
        playerIds = normalized(playerIds, "player ID");
        npcIds = normalized(npcIds, "NPC ID");
        contentNamespaces = normalized(contentNamespaces, "content namespace");
    }

    public boolean unrestricted() {
        return worlds.isEmpty() && playerIds.isEmpty() && npcIds.isEmpty() && contentNamespaces.isEmpty();
    }

    public String signingValue() {
        return "worlds=" + worlds + ";players=" + playerIds + ";npcs=" + npcIds + ";namespaces=" + contentNamespaces;
    }

    private static Set<String> normalized(Set<String> values, String label) {
        if (values == null || values.isEmpty()) return Set.of();
        if (values.size() > 128) throw new IllegalArgumentException("Too many " + label + " restrictions");
        TreeSet<String> result = new TreeSet<>();
        for (String raw : values) {
            if (raw == null) throw new IllegalArgumentException("Null " + label + " restriction");
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (!value.matches("[a-z0-9_.:-]{1,128}"))
                throw new IllegalArgumentException("Invalid " + label + " restriction: " + raw);
            result.add(value);
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(result));
    }
}
