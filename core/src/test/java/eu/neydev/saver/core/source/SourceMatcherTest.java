package eu.neydev.saver.core.source;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceMatcherTest {

    private final SourceMatcher matcher = new SourceMatcher(SourceCatalog.load(java.util.Set.of(), null));

    private String id(String url) {
        return matcher.match(url).id();
    }

    @Test
    void routesKnownHostsAndTheirSubdomains() {

        assertThat(id("https://www.youtube.com/watch?v=abc")).isEqualTo("youtube");
        assertThat(id("https://youtu.be/abc")).isEqualTo("youtube");
        assertThat(id("https://music.youtube.com/watch?v=abc")).isEqualTo("youtube");
        assertThat(id("https://m.youtube.com/watch?v=abc")).isEqualTo("youtube");
        assertThat(id("https://vm.tiktok.com/ZM8x/")).isEqualTo("tiktok");
        assertThat(id("https://t.me/s/durov")).isEqualTo("telegram");
        assertThat(id("https://vk.com/video-1_2")).isEqualTo("vk");
        assertThat(id("https://vkvideo.ru/video-1_2")).isEqualTo("vk");
        assertThat(id("https://x.com/user/status/1")).isEqualTo("x");
        assertThat(id("https://twitter.com/user/status/1")).isEqualTo("x");
        assertThat(id("https://t.co/abc")).isEqualTo("x");
        assertThat(id("https://b23.tv/xyz")).isEqualTo("bilibili");
        assertThat(id("https://i.imgur.com/abc.jpeg")).isEqualTo("imgur");
        assertThat(id("https://media.tenor.com/abc.mp4")).isEqualTo("tenor");

    }

    @Test
    void subdomainSpecificPatternsBeatGenericOnes() {

        // blog.naver.com / cafe.naver.com / tv.naver.com are three different products;
        // a bare naver.com entry would swallow them, so the catalog does not have one.
        assertThat(id("https://blog.naver.com/post/1")).isEqualTo("naverblog");
        assertThat(id("https://cafe.naver.com/articles/1")).isEqualTo("navercafe");
        assertThat(id("https://tv.naver.com/v/1")).isEqualTo("navertv");
        assertThat(id("https://story.kakao.com/post")).isEqualTo("kakaostory");
        assertThat(id("https://voom.line.me/post")).isEqualTo("linevoom");
        assertThat(id("https://v.qq.com/x/cover/1.html")).isEqualTo("tencentvideo");

    }

    @Test
    void suffixMatchingRespectsDotBoundaries() {

        assertThat(SourceMatcher.hostMatches("notyoutube.com", "youtube.com")).isFalse();
        assertThat(SourceMatcher.hostMatches("evil-youtu.be", "youtu.be")).isFalse();
        assertThat(SourceMatcher.hostMatches("a.b.youtube.com", "youtube.com")).isTrue();
        assertThat(SourceMatcher.hostMatches("youtube.com", "youtube.com")).isTrue();

    }

    @Test
    void unknownHostsFallBackToTheGenericWebSource() {

        Source source = matcher.match("https://some-random-blog.example/page");

        assertThat(source.id()).isEqualTo(SourceCatalog.GENERIC_ID);
        assertThat(source.isGeneric()).isTrue();

    }

    @Test
    void garbageDoesNotCrashTheMatcher() {

        assertThat(id("not a url")).isEqualTo(SourceCatalog.GENERIC_ID);
        assertThat(id("")).isEqualTo(SourceCatalog.GENERIC_ID);
        assertThat(id("ftp://example.com/file")).isEqualTo(SourceCatalog.GENERIC_ID);

    }

    @Test
    void mobilePrefixesAreStrippedBeforeMatching() {

        assertThat(SourceMatcher.hostOf("https://m.facebook.com/watch/?v=1")).isEqualTo("facebook.com");
        assertThat(SourceMatcher.hostOf("https://mobile.twitter.com/x")).isEqualTo("twitter.com");
        assertThat(SourceMatcher.hostOf("https://M.Instagram.com/reel/x")).isEqualTo("instagram.com");
        // "m.ru" is a real domain, not a mobile prefix of "ru"
        assertThat(SourceMatcher.hostOf("https://m.ru/page")).isEqualTo("m.ru");

    }

}
