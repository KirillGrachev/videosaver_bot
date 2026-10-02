package eu.neydev.saver.core.command.handlers;

import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.command.CommandHandler;
import eu.neydev.saver.core.command.Interaction;
import eu.neydev.saver.core.command.ReplyBuilder;

import java.util.List;

/** The static screens: start, help, about and the "back" navigation target. */
public final class MenuHandlers {

    private final ReplyBuilder replies;

    public MenuHandlers(ReplyBuilder replies) {
        this.replies = replies;
    }

    public CommandHandler start() {
        return interaction -> List.of(replies.start(interaction));
    }

    public CommandHandler help() {
        return interaction -> List.of(replies.help(interaction));
    }

    public CommandHandler about() {
        return interaction -> List.of(replies.about(interaction));
    }

    /** "Back" returns to the main menu - the root every navigation started from. */
    public CommandHandler back() {
        return interaction -> List.of(replies.start(interaction));
    }

    public List<OutboundMessage> handle(Interaction interaction) {
        return start().handle(interaction);
    }

}
