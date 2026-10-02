package eu.neydev.saver.core.command.handlers;

import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.command.Actions;
import eu.neydev.saver.core.command.CommandHandler;
import eu.neydev.saver.core.command.Interaction;
import eu.neydev.saver.core.command.ReplyBuilder;
import eu.neydev.saver.core.conversation.ConversationStore;
import eu.neydev.saver.core.source.SourceCatalogHolder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sources browser: paged catalog list + substring search over ~120 entries.
 * Search results are paginated too, and the QUERY lives in a short-lived per-user
 * session so pagination buttons ("»") need no payload beyond the page number -
 * callback data is 64 bytes on Telegram, a Cyrillic query would not survive twice.
 */
public final class SourcesHandlers {

    private static final Duration SESSION_TTL = Duration.ofMinutes(10);

    private record SearchSession(String query, Instant expiresAt) {
    }

    private final ReplyBuilder replies;
    private final SourceCatalogHolder catalogHolder;
    private final ConversationStore conversations;
    private final Map<PlatformUser, SearchSession> searchSessions = new ConcurrentHashMap<>();

    public SourcesHandlers(ReplyBuilder replies, SourceCatalogHolder catalogHolder,
                           ConversationStore conversations) {
        this.replies = replies;
        this.catalogHolder = catalogHolder;
        this.conversations = conversations;
    }

    public CommandHandler sources() {
        return interaction -> List.of(replies.sourcesPage(interaction, 0));
    }

    public CommandHandler page(int page) {
        return interaction -> List.of(replies.sourcesPage(interaction, page));
    }

    public CommandHandler searchPrompt() {

        return interaction -> {

            conversations.begin(interaction.update().user(),
                    ConversationStore.State.AWAITING_SOURCE_SEARCH);

            return List.of(replies.sourcesSearchPrompt(interaction));

        };

    }

    /** The AWAITING_SOURCE_SEARCH dialog answer. */
    public List<OutboundMessage> handleSearchText(Interaction interaction, String text) {

        conversations.clear(interaction.update().user());

        if (text.isBlank()) {
            return List.of(replies.sourcesPage(interaction, 0));
        }

        String query = text.trim();

        putSession(interaction.update().user(), query);

        return List.of(replies.sourcesSearchResult(interaction, query, 0));

    }

    /** "»" under search results: the query comes from the session, the page from the action. */
    public List<OutboundMessage> searchPage(Interaction interaction, int page) {

        SearchSession session = takeSession(interaction.update().user());

        if (session == null) {
            return List.of(replies.sourcesPage(interaction, 0));
        }

        return List.of(replies.sourcesSearchResult(interaction, session.query(), page));

    }

    public List<OutboundMessage> routeAction(Interaction interaction, String actionId) {

        if (Actions.hasPrefix(actionId, Actions.SOURCES_PAGE_PREFIX)) {
            return page(Actions.pageOf(actionId, Actions.SOURCES_PAGE_PREFIX))
                    .handle(interaction);
        }

        if (Actions.hasPrefix(actionId, Actions.SEARCH_PAGE_PREFIX)) {
            return searchPage(interaction,
                    Actions.pageOf(actionId, Actions.SEARCH_PAGE_PREFIX));
        }

        return List.of();

    }

    public int catalogSize() {
        return catalogHolder.catalog().size();
    }

    private void putSession(PlatformUser user, String query) {

        Instant now = Instant.now();

        // Cheap opportunistic purge keeps the session map from growing per unique user.
        if (searchSessions.size() > 1_000) {
            searchSessions.values().removeIf(session -> session.expiresAt().isBefore(now));
        }

        searchSessions.put(user, new SearchSession(query, now.plus(SESSION_TTL)));

    }

    private SearchSession takeSession(PlatformUser user) {

        SearchSession session = searchSessions.get(user);

        if (session == null) {
            return null;
        }

        if (session.expiresAt().isBefore(Instant.now())) {
            searchSessions.remove(user);
            return null;
        }

        return session;

    }

}
