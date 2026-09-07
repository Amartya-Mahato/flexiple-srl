package com.flexiple.sourcing.session;

import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.Rubric;
import com.flexiple.sourcing.domain.SearchSession;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory home for sourcing sessions. There is no database and nothing survives a restart, which
 * is exactly the scope of this exercise: one recruiter, one search, refresh to start again.
 * The size cap is only there so a long-lived process cannot grow without bound.
 */
@Component
public class SessionStore {

    private static final int MAX_LIVE_SESSIONS = 100;

    private final Map<String, SearchSession> sessionsById = new ConcurrentHashMap<>();

    public SearchSession createSession(String originalQuery, ObjectiveFilters filters, Rubric rubric) {
        evictOldestSessionIfAtCapacity();
        SearchSession session = new SearchSession(UUID.randomUUID().toString(), originalQuery, filters, rubric);
        sessionsById.put(session.id(), session);
        return session;
    }

    public SearchSession requireSession(String sessionId) {
        SearchSession session = sessionId == null ? null : sessionsById.get(sessionId);
        if (session == null) {
            throw new SessionNotFoundException();
        }
        return session;
    }

    /** Same as {@link #requireSession} but refuses a frozen session, which is final by design. */
    public SearchSession requireEditableSession(String sessionId) {
        SearchSession session = requireSession(sessionId);
        if (session.isFrozen()) {
            throw new SessionFrozenException();
        }
        return session;
    }

    private void evictOldestSessionIfAtCapacity() {
        if (sessionsById.size() < MAX_LIVE_SESSIONS) {
            return;
        }
        sessionsById.values().stream()
                .min(Comparator.comparing(SearchSession::createdAt))
                .ifPresent(oldest -> sessionsById.remove(oldest.id()));
    }

    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException() {
            super("That search session has expired. Refresh the page to start a new search.");
        }
    }

    public static class SessionFrozenException extends RuntimeException {
        public SessionFrozenException() {
            super("This search is frozen. Its filters, rubric and shortlist are final.");
        }
    }
}
