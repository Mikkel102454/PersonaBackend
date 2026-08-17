package nu.miguel.personabackend.storage;

import java.time.Instant;

public record RetentionPolicy(Instant revisionsBefore, Instant draftsBefore, Instant publishesBefore,
                              Instant auditBefore, Instant subscriptionsBefore, int maximumRevisionsPerInstallation) {}
