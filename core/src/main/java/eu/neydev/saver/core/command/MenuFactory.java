package eu.neydev.saver.core.command;

import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.InlineKeyboard.KeyboardButton;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.i18n.MessageBundleHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keyboard assembly, isolated from message texts: every button caption comes from the
 * i18n bundle, every payload from {@link Actions}. Adapters never see these types -
 * they map {@link InlineKeyboard} to their platform format.
 */
public final class MenuFactory {

    /** Languages per picker page; the picker is paginated so a 30-language config
        still shows two tidy rows per page. */
    private static final int LANGS_PER_PAGE = 8;

    private final MessageBundleHolder holder;
    private final AppConfig config;

    public MenuFactory(MessageBundleHolder holder, AppConfig config) {
        this.holder = holder;
        this.config = config;
    }

    private String label(Locale locale, String key) {
        return holder.renderer().raw(key, locale);
    }

    public InlineKeyboard mainMenu(Locale locale) {

        List<List<KeyboardButton>> rows = new ArrayList<>();

        rows.add(List.of(
                KeyboardButton.callback(label(locale, "button.settings"), Actions.SETTINGS),
                KeyboardButton.callback(label(locale, "button.sources"), Actions.SOURCES)));

        rows.add(List.of(
                KeyboardButton.callback(label(locale, "button.language"), Actions.LANG_MENU),
                KeyboardButton.callback(label(locale, "button.about"), Actions.ABOUT)));

        if (config.community().hasGithub()) {
            rows.add(List.of(KeyboardButton.url(
                    label(locale, "button.github"), config.community().githubUrl())));
        }

        return new InlineKeyboard(rows);

    }

    public InlineKeyboard settingsMenu(Locale locale) {

        return InlineKeyboard.of(
                List.of(KeyboardButton.callback(
                        label(locale, "button.quality_menu"), Actions.QUALITY_MENU),
                        KeyboardButton.callback(
                                label(locale, "button.language"), Actions.LANG_MENU)),
                List.of(KeyboardButton.callback(
                        label(locale, "button.stats"), Actions.STATS)),
                List.of(KeyboardButton.callback(
                        label(locale, "button.back"), Actions.BACK)));

    }

    public InlineKeyboard qualityMenu(Locale locale, QualityPreset current) {

        List<KeyboardButton> presets = new ArrayList<>();

        for (QualityPreset preset : QualityPreset.values()) {

            String mark = preset == current ? " ✓" : "";
            presets.add(KeyboardButton.callback(
                    label(locale, "button.quality." + preset.id()) + mark,
                    Actions.QUALITY_SET_PREFIX + preset.id(),
                    preset == current ? KeyboardButton.Style.PRIMARY : KeyboardButton.Style.SECONDARY));

        }

        List<List<KeyboardButton>> rows = new ArrayList<>();

        for (int i = 0; i < presets.size(); i += 2) {
            rows.add(presets.subList(i, Math.min(i + 2, presets.size())));
        }

        rows.add(List.of(KeyboardButton.callback(label(locale, "button.back"), Actions.BACK)));

        return new InlineKeyboard(rows);

    }

    public InlineKeyboard langPage(Locale locale, int page) {

        List<String> languages = new ArrayList<>(config.locale().supported());
        int pages = Math.max(1, (languages.size() + LANGS_PER_PAGE - 1) / LANGS_PER_PAGE);
        int safePage = Math.min(Math.max(0, page), pages - 1);

        List<List<KeyboardButton>> rows = new ArrayList<>();
        List<KeyboardButton> row = new ArrayList<>(2);

        for (int i = safePage * LANGS_PER_PAGE;
             i < Math.min((safePage + 1) * LANGS_PER_PAGE, languages.size()); i++) {

            String code = languages.get(i);
            String caption = config.locale().displayNames().getOrDefault(code, code);

            row.add(KeyboardButton.callback(caption, Actions.LANG_SET_PREFIX + code));

            if (row.size() == 2) {
                rows.add(List.copyOf(row));
                row.clear();
            }

        }

        if (!row.isEmpty()) {
            rows.add(List.copyOf(row));
        }

        if (pages > 1) {

            int prev = (safePage + pages - 1) % pages;
            int next = (safePage + 1) % pages;

            rows.add(List.of(
                    KeyboardButton.callback("« " + (prev + 1), Actions.LANG_PAGE_PREFIX + prev),
                    KeyboardButton.callback((safePage + 1) + "/" + pages, Actions.NOOP),
                    KeyboardButton.callback((next + 1) + " »", Actions.LANG_PAGE_PREFIX + next)));

        }

        rows.add(List.of(KeyboardButton.callback(label(locale, "button.back"), Actions.BACK)));

        return new InlineKeyboard(rows);

    }

    public InlineKeyboard sourcesNav(Locale locale, int page, int pageCount) {

        List<List<KeyboardButton>> rows = new ArrayList<>();

        if (pageCount > 1) {

            int prev = (page + pageCount - 1) % pageCount;
            int next = (page + 1) % pageCount;

            rows.add(List.of(
                    KeyboardButton.callback("«", Actions.SOURCES_PAGE_PREFIX + prev),
                    KeyboardButton.callback((page + 1) + "/" + pageCount, Actions.NOOP),
                    KeyboardButton.callback("»", Actions.SOURCES_PAGE_PREFIX + next)));

        }

        rows.add(List.of(
                KeyboardButton.callback(label(locale, "button.search"), Actions.SOURCES_SEARCH),
                KeyboardButton.callback(label(locale, "button.back"), Actions.BACK)));

        return new InlineKeyboard(rows);

    }

    /** Pagination under search results: arrows keep the query in the handler session. */
    public InlineKeyboard searchNav(Locale locale, int page, int pageCount) {

        List<List<KeyboardButton>> rows = new ArrayList<>();

        if (pageCount > 1) {

            int prev = (page + pageCount - 1) % pageCount;
            int next = (page + 1) % pageCount;

            rows.add(List.of(
                    KeyboardButton.callback("«", Actions.SEARCH_PAGE_PREFIX + prev),
                    KeyboardButton.callback((page + 1) + "/" + pageCount, Actions.NOOP),
                    KeyboardButton.callback("»", Actions.SEARCH_PAGE_PREFIX + next)));

        }

        rows.add(List.of(KeyboardButton.callback(
                label(locale, "button.back"), Actions.SOURCES)));

        return new InlineKeyboard(rows);

    }

    /** The stop button rides on the "queued" status message: cancel without typing. */
    public InlineKeyboard stopButton(Locale locale) {
        return InlineKeyboard.of(List.of(
                KeyboardButton.callback(label(locale, "button.stop"), Actions.JOB_STOP,
                        KeyboardButton.Style.DANGER)));
    }

    /** The "original" URL button under every delivered file. */
    public InlineKeyboard sourceLink(Locale locale, String url) {
        return InlineKeyboard.of(List.of(
                KeyboardButton.url(label(locale, "button.original"), url)));
    }

    public int langPages() {
        return Math.max(1, (config.locale().supported().size() + LANGS_PER_PAGE - 1) / LANGS_PER_PAGE);
    }

}
