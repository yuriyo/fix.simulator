package com.fix.simulator.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.SessionID;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Branch and path coverage for {@link ClientRegistry}. */
@DisplayName("ClientRegistry – access control, persistence and sessions")
class ClientRegistryCoverageTest {

    @TempDir Path tmp;

    private Path file;
    private ClientRegistry reg;

    @BeforeEach
    void setUp() {
        file = tmp.resolve("clients.yml");
        reg = new ClientRegistry(file.toString());
    }

    private static SessionID sid(String target) {
        return new SessionID("FIX.4.2", "EXCHANGE", target);
    }

    @Nested
    @DisplayName("isAllowed")
    class Access {

        @Test
        void openSystem_everyoneAllowed() {
            assertTrue(reg.isAllowed("ANY"));
        }

        @Test
        void exactBlocked_denied() {
            reg.block("BAD");
            assertFalse(reg.isAllowed("BAD"));
            assertTrue(reg.isAllowed("GOOD"));
        }

        @Test
        void blockedRegex_deniesMatches() {
            reg.block("BOT.*");
            assertFalse(reg.isAllowed("BOT42"));
            assertTrue(reg.isAllowed("HUMAN"));
        }

        @Test
        void invalidBlockedRegex_treatedAsLiteral() {
            reg.block("([");
            assertTrue(reg.isAllowed("ANYTHING"));
            assertFalse(reg.isAllowed("(["));
        }

        @Test
        void exactAllowed_permittedWhenListNonEmpty() {
            reg.allow("VIP");
            assertTrue(reg.isAllowed("VIP"));
            assertFalse(reg.isAllowed("OTHER"));
        }

        @Test
        void allowedRegex_permitsMatches() {
            reg.allow("CLI.*");
            assertTrue(reg.isAllowed("CLIENT9"));
            assertFalse(reg.isAllowed("NOPE"));
        }

        @Test
        void invalidAllowedRegex_ignored() {
            reg.allow("([");
            reg.allow("VIP");
            assertTrue(reg.isAllowed("VIP"));
            assertFalse(reg.isAllowed("ZZZ"));
        }

        @Test
        void blockedWinsOverAllowed() {
            reg.allow("SAME");
            reg.block("SAME");
            assertFalse(reg.isAllowed("SAME"));
        }

        @Test
        void exactAllowWinsOverRegexBlock() {
            // Priority: exact blocked > exact allowed > regex blocked > regex allowed.
            reg.allow("ABC");
            reg.block("A.C");
            assertTrue(reg.isAllowed("ABC"));
            assertFalse(reg.isAllowed("AXC"));
        }
    }

    @Nested
    @DisplayName("list management and persistence")
    class Lists {

        @Test
        void blockUnblockAllowDisallow_updateSetsAndPersist() {
            reg.block("B1");
            reg.allow("A1");
            assertEquals(java.util.Set.of("B1"), reg.getBlocked());
            assertEquals(java.util.Set.of("A1"), reg.getAllowed());
            reg.unblock("B1");
            reg.disallow("A1");
            assertTrue(reg.getBlocked().isEmpty());
            assertTrue(reg.getAllowed().isEmpty());
        }

        @Test
        void persistedLists_reloadedByNewInstance() {
            reg.block("B1");
            reg.allow("A1");
            ClientRegistry reloaded = new ClientRegistry(file.toString());
            assertEquals(java.util.Set.of("B1"), reloaded.getBlocked());
            assertEquals(java.util.Set.of("A1"), reloaded.getAllowed());
        }

        @Test
        void missingFile_startsEmpty() {
            assertTrue(new ClientRegistry(tmp.resolve("absent.yml").toString()).getBlocked().isEmpty());
        }

        @Test
        void emptyFile_yieldsNullDocument_startsEmpty() throws Exception {
            Files.writeString(file, "");
            assertTrue(new ClientRegistry(file.toString()).getBlocked().isEmpty());
        }

        @Test
        void fileWithoutKnownKeys_usesDefaults() throws Exception {
            Files.writeString(file, "other: value\n");
            ClientRegistry r = new ClientRegistry(file.toString());
            assertTrue(r.getBlocked().isEmpty());
            assertTrue(r.getAllowed().isEmpty());
        }

        @Test
        void invalidYaml_isLoggedAndIgnored() throws Exception {
            Files.writeString(file, "blocked: [unclosed\n");
            assertTrue(new ClientRegistry(file.toString()).getBlocked().isEmpty());
        }

        @Test
        void unwritablePersistPath_isLoggedNotThrown() throws Exception {
            Path blocker = tmp.resolve("not-a-dir");
            Files.writeString(blocker, "x");
            ClientRegistry r = new ClientRegistry(blocker.resolve("sub/clients.yml").toString());
            assertDoesNotThrow(() -> r.block("X"));
            assertTrue(r.getBlocked().contains("X"));   // in-memory state still updated
        }

        @Test
        void yamlOutput_containsBothLists() throws Exception {
            reg.block("B1");
            reg.allow("A1");
            String text = Files.readString(file);
            assertTrue(text.contains("blocked:") && text.contains("allowed:"));
        }
    }

    @Nested
    @DisplayName("session lifecycle")
    class Sessions {

        @Test
        void connectResolveAndListActive() {
            SessionID s = sid("CLIENT1");
            reg.onConnect(s);
            assertEquals(s, reg.resolve(s.toString()));
            assertNull(reg.resolve("unknown"));
            List<Map<String, String>> clients = reg.getConnectedClients();
            assertEquals(1, clients.size());
            assertEquals("CLIENT1", clients.get(0).get("compId"));
            assertEquals("EXCHANGE", clients.get(0).get("senderCompId"));
        }

        @Test
        void disconnectMarksRecordInactive() {
            SessionID s = sid("CLIENT1");
            reg.onConnect(s);
            reg.onDisconnect(s);
            assertNull(reg.resolve(s.toString()));
            assertFalse(reg.getSessionHistory().get(0).isActive());
            assertTrue(reg.getConnectedClients().isEmpty());
        }

        @Test
        void disconnectUnknownSession_noRecordNoError() {
            reg.onDisconnect(sid("GHOST"));
            assertTrue(reg.getSessionHistory().isEmpty());
        }

        @Test
        void historyIsNewestFirst() throws Exception {
            reg.onConnect(sid("OLD"));
            Thread.sleep(5);
            reg.onConnect(sid("NEW"));
            List<ClientRegistry.SessionRecord> h = reg.getSessionHistory();
            assertEquals(2, h.size());
            assertEquals("NEW", h.get(0).compId);
        }

        @Test
        void forceDisconnectWithoutLiveQuickFixSession_isNoop() {
            reg.onConnect(sid("CLIENT1"));
            assertDoesNotThrow(() -> reg.forceDisconnect("CLIENT1"));
            assertDoesNotThrow(() -> reg.forceDisconnect("NOBODY"));
        }
    }
}
