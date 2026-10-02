package eu.neydev.saver.core.command.handlers;

import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.command.CommandHandler;
import eu.neydev.saver.core.command.Interaction;
import eu.neydev.saver.core.command.ReplyBuilder;
import eu.neydev.saver.core.download.FileVault;
import eu.neydev.saver.core.download.JobManager;
import eu.neydev.saver.core.i18n.MessageBundleHolder;
import eu.neydev.saver.core.service.UserService;
import eu.neydev.saver.core.source.SourceCatalogHolder;
import eu.neydev.saver.core.storage.JobRepository;

import java.util.List;

/**
 * Owner-only operations. Authorization is enforced by the router (owners come from the
 * config), these handlers only assemble the answers.
 */
public final class AdminHandlers {

    private final ReplyBuilder replies;
    private final MessageBundleHolder holder;
    private final SourceCatalogHolder catalogHolder;
    private final JobManager jobManager;
    private final FileVault vault;
    private final JobRepository jobs;
    private final UserService users;

    public AdminHandlers(ReplyBuilder replies, MessageBundleHolder holder,
                         SourceCatalogHolder catalogHolder, JobManager jobManager,
                         FileVault vault, JobRepository jobs, UserService users) {
        this.replies = replies;
        this.holder = holder;
        this.catalogHolder = catalogHolder;
        this.jobManager = jobManager;
        this.vault = vault;
        this.jobs = jobs;
        this.users = users;
    }

    /**
     * /reload - hot-swaps BOTH message bundles and the source catalog (an operator who
     * fixed a host pattern in config/sources.yml must not need a restart; the javadoc
     * of SourceCatalog promises exactly this).
     */
    public CommandHandler reload() {

        return interaction -> {

            holder.reload();
            catalogHolder.reload();

            return List.of(replies.reloadDone(interaction, catalogHolder.catalog().size()));

        };

    }

    /** /admin - one screen with everything an operator asks first when the bot "is broken". */
    public CommandHandler admin() {

        return interaction -> {

            ReplyBuilder.AdminStats stats = new ReplyBuilder.AdminStats(
                    jobManager.engineStatus(),
                    jobManager.activeCount(),
                    jobManager.queuedCount(),
                    vault.bytesInUse(),
                    vault.maxBytes(),
                    users.count(),
                    jobs.statusCounts(),
                    jobs.topSources(8));

            return List.of(replies.admin(interaction, stats));

        };

    }

    public List<OutboundMessage> handleReload(Interaction interaction) {
        return reload().handle(interaction);
    }

    public List<OutboundMessage> handleAdmin(Interaction interaction) {
        return admin().handle(interaction);
    }

}
