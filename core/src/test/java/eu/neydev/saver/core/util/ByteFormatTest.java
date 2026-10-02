package eu.neydev.saver.core.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ByteFormatTest {

    @Test
    void humanSizesAreBinaryLikeThePlatformUIs() {

        assertThat(ByteFormat.human(0)).isEqualTo("0 B");
        assertThat(ByteFormat.human(999)).isEqualTo("999 B");
        assertThat(ByteFormat.human(1_024)).isEqualTo("1.0 KB");
        assertThat(ByteFormat.human(1_500)).isEqualTo("1.5 KB");
        // The regression that matters: 146_432 bytes is what Telegram's file card
        // calls "143.0 KB"; a decimal formatter printed "146 KB" next to it.
        assertThat(ByteFormat.human(146_432)).isEqualTo("143 KB");
        assertThat(ByteFormat.human(4_200_000)).isEqualTo("4.0 MB");
        assertThat(ByteFormat.human(1_234_567_890)).isEqualTo("1.1 GB");
        assertThat(ByteFormat.human(150_000_000)).isEqualTo("143 MB");

    }

    @Test
    void parsesSuffixedAndPlainValues() {

        assertThat(ByteFormat.parse("500mb", "k")).isEqualTo(500_000_000L);
        assertThat(ByteFormat.parse("2GB", "k")).isEqualTo(2_000_000_000L);
        assertThat(ByteFormat.parse("512kb", "k")).isEqualTo(512_000L);
        assertThat(ByteFormat.parse("1048576", "k")).isEqualTo(1_048_576L);
        assertThat(ByteFormat.parse(" 10 b ", "k")).isEqualTo(10L);
        assertThat(ByteFormat.parse("1.5tb", "k")).isEqualTo(1_500_000_000_000L);

    }

    @Test
    void rejectsGarbage() {

        assertThatThrownBy(() -> ByteFormat.parse("abc", "my.key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("my.key");

    }

}
