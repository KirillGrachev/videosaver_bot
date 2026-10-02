package eu.neydev.saver.core.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog contract: every source the product promises is present, external files
 * override and extend, disabled ids stay resolvable (so refusals are honest), and the
 * search finds things by both id and human title.
 */
class SourceCatalogTest {

    private final SourceCatalog catalog = SourceCatalog.load(Set.of(), null);

    @Test
    void builtinCatalogCoversEveryPromisedSource() {

        // The list the product was ordered with. A removal from sources.yml that is
        // not reflected here is a silent regression in what users were promised.
        List<String> promised = List.of(
                "youtube", "instagram", "tiktok", "facebook", "x", "reddit", "pinterest",
                "snapchat", "threads", "telegram", "vk", "linkedin", "tumblr", "bluesky",
                "mastodon", "likee", "lemon8", "rednote", "clapper", "triller", "fanbase",
                "kwai", "josh", "moj", "chingari", "rizzle", "firework", "vero", "flickr",
                "vsco", "bereal", "glass", "pixelfed", "500px", "unsplash", "eyeem",
                "photocrowd", "gurushots", "behance", "dribbble", "cara", "artstation",
                "deviantart", "artfol", "pixiv", "newgrounds", "weasyl", "toyhouse",
                "furaffinity", "niconico", "bilibili", "dailymotion", "vimeo", "rumble",
                "odysee", "peertube", "veoh", "twitch", "streamable", "giphy", "tenor",
                "imgur", "imgflip", "9gag", "memedroid", "knowyourmeme", "designspiration",
                "arenap", "awwwards", "figma", "producthunt", "indiehackers", "devto",
                "hashnode", "medium", "substack", "quora", "discord", "lemmy", "nostr",
                "farcaster", "diaspora", "friendica", "misskey", "firefish", "pleroma",
                "linevoom", "mildom", "fantia", "weibo", "douyin", "kuaishou", "youku",
                "tencentvideo", "douban", "naverblog", "navercafe", "navertv", "kakaostory",
                "soop", "sharechat", "roposo", "snackvideo",
                // the extras the same pipeline covers
                "soundcloud", "rutube", "okru", "kick", "dzen", "loom", "archiveorg",
                "bitchute", "pikabu", "patreon", "boosty", "bandcamp", "mixcloud",
                "audiomack", "instructables", "minds", "gab", "gettr", "truthsocial",
                "writeas");

        List<String> missing = promised.stream()
                .filter(id -> catalog.find(id).isEmpty())
                .toList();

        assertThat(missing).as("sources missing from the built-in catalog").isEmpty();
        assertThat(catalog.size()).isGreaterThan(promised.size());

    }

    @Test
    void everyCatalogEntryIsWellFormed() {

        for (Source source : catalog.all()) {

            assertThat(source.id()).isEqualTo(source.id().toLowerCase());
            assertThat(source.title()).isNotBlank();

            if (!source.isGeneric()) {
                assertThat(source.hosts())
                        .as("source %s must route by at least one host", source.id())
                        .isNotEmpty();
            }

            for (String host : source.hosts()) {
                assertThat(host).isEqualTo(host.toLowerCase()).doesNotStartWith(".");
            }

        }

    }

    @Test
    void externalFileOverridesAndExtends(@TempDir Path dir) throws IOException {

        Path external = dir.resolve("sources.yml");
        Files.writeString(external, """
                sources:
                  - id: youtube
                    title: YouTube (fixed)
                    hosts: [youtube.com, youtu.be, youtube-nocookie.com, yt.example]
                    backend: ytdlp
                    status: ok
                  - id: niche-site
                    title: Niche Site
                    hosts: [niche.example]
                    backend: http
                    status: limited
                """);

        SourceCatalog merged = SourceCatalog.load(Set.of(), external);

        assertThat(merged.find("youtube"))
                .get()
                .extracting(Source::title)
                .isEqualTo("YouTube (fixed)");
        assertThat(merged.find("youtube").orElseThrow().hosts()).contains("yt.example");
        assertThat(merged.find("niche-site")).isPresent();
        // overriding must not lose the rest of the built-in catalog
        assertThat(merged.find("tiktok")).isPresent();

    }

    @Test
    void disabledSourcesStayListedButAreMarked() {

        SourceCatalog withDisabled = SourceCatalog.load(Set.of("tiktok"), null);

        assertThat(withDisabled.isDisabled("tiktok")).isTrue();
        assertThat(withDisabled.isDisabled("youtube")).isFalse();
        assertThat(withDisabled.find("tiktok")).isPresent();

    }

    @Test
    void searchFindsByIdAndTitleCaseInsensitively() {

        assertThat(catalog.search("tiktok")).isNotEmpty();
        assertThat(catalog.search("TIKTOK")).isNotEmpty();
        assertThat(catalog.search("fur")).isNotEmpty();
        assertThat(catalog.search("know your")).isNotEmpty();
        assertThat(catalog.search("zzz-nothing")).isEmpty();
        assertThat(catalog.search("  ")).hasSize(catalog.size());

    }

}
