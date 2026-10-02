package eu.neydev.saver.core.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlIntakeTest {

    private final UrlIntake intake = new UrlIntake();

    @Test
    void findsUrlsInsideSentences() {

        List<String> urls = intake.extractUrls(
                "смотри какой кот https://youtu.be/dQw4w9WgXcQ а вот еще https://tiktok.com/@a/video/1");

        assertThat(urls).containsExactly("https://youtu.be/dQw4w9WgXcQ",
                "https://tiktok.com/@a/video/1");

    }

    @Test
    void stripsTrailingChatPunctuation() {

        assertThat(intake.extractUrls("Check https://vimeo.com/12345."))
                .containsExactly("https://vimeo.com/12345");

        assertThat(intake.extractUrls("Wow https://vimeo.com/12345!!!"))
                .containsExactly("https://vimeo.com/12345");

        assertThat(intake.extractUrls("(https://vimeo.com/1)"))
                .containsExactly("https://vimeo.com/1");

    }

    @Test
    void keepsBalancedParenthesesInsideUrls() {

        // Wikipedia-style links legitimately end with a balanced parenthesis.
        assertThat(intake.extractUrls(
                "https://en.wikipedia.org/wiki/Cat_(animal)"))
                .containsExactly("https://en.wikipedia.org/wiki/Cat_(animal)");

        // An unbalanced trailing parenthesis is sentence punctuation, not part of the URL.
        assertThat(intake.extractUrls(
                "(see https://example.com/page)"))
                .containsExactly("https://example.com/page");

    }

    @Test
    void unwrapsTelegramAngleBracketsAndDeduplicates() {

        List<String> urls = intake.extractUrls(
                "<https://example.com/a> and again https://example.com/a plus <https://example.com/b>");

        assertThat(urls).containsExactly("https://example.com/a", "https://example.com/b");

    }

    @Test
    void capsTheNumberOfUrlsPerMessage() {

        StringBuilder text = new StringBuilder();

        for (int i = 0; i < 12; i++) {
            text.append("https://example.com/").append(i).append(' ');
        }

        assertThat(intake.extractUrls(text.toString()))
                .hasSize(UrlIntake.MAX_URLS_PER_MESSAGE);

    }

    @Test
    void validateRejectsNonHttpSchemesAndCredentials() {

        assertThatThrownBy(() -> intake.validate("javascript:alert(1)"))
                .isInstanceOf(UrlIntake.InvalidUrlException.class);

        assertThatThrownBy(() -> intake.validate("file:///etc/passwd"))
                .isInstanceOf(UrlIntake.InvalidUrlException.class);

        assertThatThrownBy(() -> intake.validate("http://user:pass@example.com/"))
                .isInstanceOf(UrlIntake.InvalidUrlException.class)
                .hasMessageContaining("credentials");

        assertThatThrownBy(() -> intake.validate("not a url"))
                .isInstanceOf(UrlIntake.InvalidUrlException.class);

    }

    @Test
    void validateAcceptsAndNormalizes() {

        assertThat(intake.validate("HTTPS://Example.COM/Path").toString())
                .isEqualTo("HTTPS://Example.COM/Path");

        assertThat(intake.validate(" https://example.com/x. ").getHost())
                .isEqualTo("example.com");

    }

    @Test
    void containsUrlDetectsLinks() {

        assertThat(intake.containsUrl("no links here")).isFalse();
        assertThat(intake.containsUrl("look: http://a.example/x")).isTrue();
        assertThat(intake.containsUrl(null)).isFalse();

    }

}
