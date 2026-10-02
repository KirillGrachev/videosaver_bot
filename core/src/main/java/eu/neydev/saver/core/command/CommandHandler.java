package eu.neydev.saver.core.command;

import java.util.List;

/** A command/action handler: purely "event + settings -> outbound messages". */
@FunctionalInterface
public interface CommandHandler {

    List<eu.neydev.saver.core.api.OutboundMessage> handle(Interaction interaction);

}
