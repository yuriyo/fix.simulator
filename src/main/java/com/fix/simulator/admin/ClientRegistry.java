package com.fix.simulator.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import quickfix.Session;
import quickfix.SessionID;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Tracks active FIX sessions and manages blocked / allowed client lists.
 *
 * <p>Block / allow lists are persisted as YAML to {@code config/blocked-clients.yml}
 * (or a custom path supplied at construction time) so they survive server restarts.
 *
 * <p>An empty allowed-list means <em>all</em> clients are permitted (subject to the
 * blocked list).  Once any entry is added to the allowed list, only those compIDs
 * may connect.
 */
public class ClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistry.class);

    // ── Session record ────────────────────────────────────────────────────

    /** Immutable snapshot of one FIX session (login → logout). */
    public static class SessionRecord {
        public final String  sessionId;
        public final String  compId;
        public final Instant loginTime;
        public volatile Instant logoutTime; // null while still active

        SessionRecord(String sessionId, String compId, Instant loginTime) {
            this.sessionId = sessionId;
            this.compId    = compId;
            this.loginTime = loginTime;
        }

        public boolean isActive() { return logoutTime == null; }
    }

    /** sessionId.toString() → SessionID (for FIX message dispatch) */
    private final Map<String, SessionID> activeSessions = new ConcurrentHashMap<>();

    /** All sessions ever seen (active + historical), newest-first ordering maintained on retrieval. */
    private final Map<String, SessionRecord> sessionHistory = new ConcurrentHashMap<>();

    private final Set<String> blocked = ConcurrentHashMap.newKeySet();
    private final Set<String> allowed = ConcurrentHashMap.newKeySet();

    private final String persistPath;

    public ClientRegistry(String persistPath) {
        this.persistPath = persistPath;
        load();
    }

    // ── Session lifecycle (called from FIXSimulatorApp) ───────────────────────

    public void onConnect(SessionID sessionId) {
        activeSessions.put(sessionId.toString(), sessionId);
        // Create or refresh record for this session
        sessionHistory.put(sessionId.toString(),
                new SessionRecord(sessionId.toString(), sessionId.getTargetCompID(), Instant.now()));
    }

    public void onDisconnect(SessionID sessionId) {
        activeSessions.remove(sessionId.toString());
        SessionRecord rec = sessionHistory.get(sessionId.toString());
        if (rec != null) rec.logoutTime = Instant.now();
    }

    /**
     * Resolve a session-id string (as stored in {@link com.fix.simulator.engine.Order})
     * back to an active {@link SessionID} object, or {@code null} if not connected.
     */
    public SessionID resolve(String sessionIdStr) {
        return activeSessions.get(sessionIdStr);
    }

    // ── Access control ────────────────────────────────────────────────────────

    /** {@code true} if the compId may connect (priority: exact blocked > exact allowed > regex blocked > regex allowed). */
    public boolean isAllowed(String compId) {
        boolean exactBlocked = blocked.contains(compId);
        boolean exactAllowed = !allowed.isEmpty() && allowed.contains(compId);

        // Exact match in both → blocked wins
        if (exactBlocked) return false;
        if (exactAllowed) return true;

        // Regex match in blocked
        for (String pattern : blocked) {
            if (pattern.equals(compId)) continue; // already handled above
            try {
                if (java.util.regex.Pattern.matches(pattern, compId)) return false;
            } catch (java.util.regex.PatternSyntaxException ignored) {
                // invalid regex treated as literal – skip
            }
        }

        // Regex match in allowed (only when allowed list is non-empty)
        if (!allowed.isEmpty()) {
            for (String pattern : allowed) {
                if (pattern.equals(compId)) continue;
                try {
                    if (java.util.regex.Pattern.matches(pattern, compId)) return true;
                } catch (java.util.regex.PatternSyntaxException ignored) {}
            }
            return false; // non-empty allowed list, no match
        }

        return true; // open system
    }

    public void block(String compId) {
        blocked.add(compId);
        persist();
    }

    public void unblock(String compId) {
        blocked.remove(compId);
        persist();
    }

    public void allow(String compId) {
        allowed.add(compId);
        persist();
    }

    public void disallow(String compId) {
        allowed.remove(compId);
        persist();
    }

    public Set<String> getBlocked() { return Set.copyOf(blocked); }
    public Set<String> getAllowed() { return Set.copyOf(allowed); }

    // ── Connected client info ─────────────────────────────────────────────────

    public List<Map<String, String>> getConnectedClients() {
        List<Map<String, String>> result = new ArrayList<>();
        for (SessionID sid : activeSessions.values()) {
            Map<String, String> info = new LinkedHashMap<>();
            info.put("sessionId",       sid.toString());
            info.put("compId",          sid.getTargetCompID());
            info.put("senderCompId",    sid.getSenderCompID());
            result.add(info);
        }
        return result;
    }

    /**
     * Return all session records (active + historical), newest-first.
     * Stat fields (orders, open, cancelled, filled) are left at 0 here;
     * callers that have access to the matching engine should fill them in.
     */
    public List<SessionRecord> getSessionHistory() {
        return sessionHistory.values().stream()
                .sorted(Comparator.comparing((SessionRecord r) -> r.loginTime).reversed())
                .collect(Collectors.toList());
    }

    /** Send a Logout to every active session whose TargetCompID matches {@code compId}. */
    public void forceDisconnect(String compId) {
        activeSessions.values().stream()
                .filter(sid -> sid.getTargetCompID().equals(compId))
                .forEach(sid -> {
                    Session session = Session.lookupSession(sid);
                    if (session != null) {
                        session.logout("Forced disconnect by admin");
                        log.info("Admin: force-disconnected {}", compId);
                    }
                });
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void load() {
        Path path = Path.of(persistPath);
        if (!Files.exists(path)) return;
        try {
            Map<String, Object> data = new Yaml().load(Files.readString(path));
            if (data == null) return;
            List<String> b = (List<String>) data.getOrDefault("blocked", List.of());
            List<String> a = (List<String>) data.getOrDefault("allowed", List.of());
            blocked.addAll(b);
            allowed.addAll(a);
            log.info("Loaded client access lists: {} blocked, {} allowed", b.size(), a.size());
        } catch (Exception e) {
            log.warn("Could not load client lists from {}: {}", persistPath, e.getMessage());
        }
    }

    private void persist() {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("blocked", new ArrayList<>(blocked));
            data.put("allowed", new ArrayList<>(allowed));
            DumperOptions opts = new DumperOptions();
            opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            Path path = Path.of(persistPath);
            Files.createDirectories(path.getParent());
            Files.writeString(path, new Yaml(opts).dump(data));
        } catch (IOException e) {
            log.error("Failed to persist client lists to {}: {}", persistPath, e.getMessage());
        }
    }
}
