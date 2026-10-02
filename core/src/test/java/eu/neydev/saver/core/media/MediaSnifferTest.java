package eu.neydev.saver.core.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The magic-byte table: extensionless CDN downloads are renamed from what their own
 * header promises, and everything the table does not know stays untouched - a wrong
 * extension would lie about the content, which is worse than no extension.
 */
class MediaSnifferTest {

    @TempDir
    Path dir;

    private Path file(byte[] bytes) throws IOException {
        Path file = dir.resolve("KLz17Yc9shs");
        Files.write(file, bytes);
        return file;
    }

    @Test
    void recognizesTheContainersTheGenericBackendsMeet() throws IOException {

        assertThat(MediaSniffer.extension(file(new byte[]{
                (byte) 0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
                0x69, 0x73, 0x6F, 0x6D, 0, 0, 0, 0}))).contains("mp4");
        assertThat(MediaSniffer.extension(file(new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0}))).contains("jpg");
        assertThat(MediaSniffer.extension(file(new byte[]{
                0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0,
                0x57, 0x45, 0x42, 0x50, 0, 0, 0, 0}))).contains("webp");
        assertThat(MediaSniffer.extension(file(new byte[]{
                0x49, 0x44, 0x33, 0x04, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0}))).contains("mp3");
        assertThat(MediaSniffer.extension(file(new byte[]{
                0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0}))).contains("mkv");

    }

    @Test
    void unknownAndShortHeadersCarryNoOpinion() throws IOException {

        assertThat(MediaSniffer.extension(file("plain text, not media at all".getBytes())))
                .isEmpty();
        assertThat(MediaSniffer.extension(file(new byte[]{0x00, 0x01}))).isEmpty();

    }

    @Test
    void hasExtensionOnlyWhenADotIsFollowedBySomething() {

        assertThat(MediaSniffer.hasExtension("clip.mp4")).isTrue();
        assertThat(MediaSniffer.hasExtension("KLz17Yc9shs")).isFalse();
        assertThat(MediaSniffer.hasExtension("trailing.")).isFalse();
        assertThat(MediaSniffer.hasExtension(".part")).isFalse();

    }

    @Test
    void htmlPageSignaturesMatchAndMediaDoesNot() throws IOException {

        assertThat(MediaSniffer.isHtmlPage(file(
                "<!DOCTYPE html><html><head></head></html>".getBytes()))).isTrue();
        assertThat(MediaSniffer.isHtmlPage(file(
                "  \n  <HTML><BODY>player shell</BODY></HTML>".getBytes()))).isTrue();
        assertThat(MediaSniffer.isHtmlPage(file(new byte[]{
                (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '<', 'b', 'o', 'd', 'y', '>'})))
                .isTrue();

        // An SVG opens with xml/svg markup and IS media: it must not read as a page.
        assertThat(MediaSniffer.isHtmlPage(file(
                "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>"
                        .getBytes()))).isFalse();
        assertThat(MediaSniffer.isHtmlPage(file(new byte[]{
                (byte) 0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
                0x69, 0x73, 0x6F, 0x6D, 0, 0, 0, 0}))).isFalse();

    }

}
