package fyi.slot.android;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import static org.junit.Assert.*;

/** Portable file/format rules; samples contain only synthetic headers. */
public final class LibraryCoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File root() throws IOException { return temp.newFolder(); }
    private File rom(File root, String platform, String name, int flag) throws IOException {
        File folder = new File(root, "Games/" + platform); assertTrue(folder.isDirectory() || folder.mkdirs());
        byte[] bytes = new byte[512]; bytes[0x143] = (byte) flag;
        File file = new File(folder, name); Files.write(file.toPath(), bytes); return file;
    }
    private List<RomRecord> scan(File root, Cartridge.Platform platform) throws IOException {
        return new LibraryScanner().scan(root, platform, () -> false);
    }
    @Test public void directoryControlsPlatform() throws Exception {
        File root = root(); rom(root, "GB", "Color.gbc", 0xc0);
        assertEquals(Cartridge.Platform.GB, scan(root, Cartridge.Platform.GB).get(0).platform);
    }
    @Test public void gbaRejectsGbExtensions() throws Exception {
        File root = root(); rom(root, "GBA", "Game.gb", 0); rom(root, "GBA", "Game.GBA", 0);
        assertEquals(1, scan(root, Cartridge.Platform.GBA).size());
    }
    @Test public void gbAcceptsBothExtensions() throws Exception {
        File root = root(); rom(root, "GB", "Game.gb", 0); rom(root, "GB", "Game.gbc", 0);
        List<RomRecord> result = scan(root, Cartridge.Platform.GB); assertEquals(2, result.size());
        assertNotEquals(result.get(0).id, result.get(1).id);
        assertEquals(RomNaming.stem(result.get(0).filename), RomNaming.stem(result.get(1).filename));
    }
    @Test public void ignoresDotFilesAndDirectories() throws Exception {
        File root = root(); rom(root, "GB", ".Game.gb", 0);
        assertTrue(new File(root, "Games/GB/Sub.gb").mkdir()); assertTrue(scan(root, Cartridge.Platform.GB).isEmpty());
    }
    @Test public void excludesShortAndEmptyRoms() throws Exception {
        File root = root(); File file = rom(root, "GBA", "Short.gba", 0); Files.write(file.toPath(), new byte[0x14f]);
        assertTrue(scan(root, Cartridge.Platform.GBA).isEmpty()); Files.write(file.toPath(), new byte[0]);
        assertTrue(scan(root, Cartridge.Platform.GBA).isEmpty());
    }
    @Test public void platformDirectoriesIgnoreCase() throws Exception {
        File root = root(); rom(root, "gBa", "Game.gba", 0);
        assertEquals(1, scan(root, Cartridge.Platform.GBA).size());
    }
    @Test public void onlyScansOneLevel() throws Exception {
        File root = root(); rom(root, "GBA/sub", "Game.gba", 0);
        assertTrue(scan(root, Cartridge.Platform.GBA).isEmpty());
    }
    @Test public void missingPlatformIsSuccessfulEmpty() throws Exception {
        assertTrue(scan(root(), Cartridge.Platform.GBA).isEmpty());
    }
    @Test public void missingRootIsFailure() throws Exception {
        assertThrows(IOException.class, () -> scan(new File(temp.getRoot(), "missing"), Cartridge.Platform.GBA));
    }
    @Test public void cancellationIsFailure() throws Exception {
        File root = root(); rom(root, "GBA", "Game.gba", 0);
        assertThrows(IOException.class, () -> new LibraryScanner().scan(root, Cartridge.Platform.GBA, () -> true));
    }
    @Test public void rootIsPartOfIdentity() throws Exception {
        File a = root(), b = root(); rom(a, "GB", "Game.gb", 0); rom(b, "GB", "Game.gb", 0);
        assertNotEquals(scan(a, Cartridge.Platform.GB).get(0).id, scan(b, Cartridge.Platform.GB).get(0).id);
    }
    @Test public void namesSplitTags() {
        String file = "Metroid - Zero Mission (Europe) (En,Fr) [v1].gba";
        assertEquals("Metroid - Zero Mission", RomNaming.title(file));
        assertEquals("(Europe) (En,Fr) [v1]", RomNaming.tags(file));
    }
    @Test public void naturalSortHandlesLargeNumbers() {
        assertTrue(RomNaming.compare("Game 2", "Game 10") < 0);
        assertTrue(RomNaming.compare("Game 99999999999999999999", "Game 100000000000000000000") < 0);
        assertEquals(0, RomNaming.compare("Game 02", "game 2"));
    }
    @Test public void nfcKeysMatch() {
        assertEquals(LibraryPaths.nfc("Cafe\u0301"), LibraryPaths.nfc("Café"));
        Map<String, ShellConfig.Choice> choices = ShellConfig.layered("Cafe\u0301 = rounded 123456 clear", "");
        assertNotNull(choices.get("Café"));
    }
    @Test public void iniMatchesUpstreamCommentsDuplicatesAndEquals() {
        Map<String, String> entries = ShellConfig.entries("# hi\n; hi\n[section]\nname=x\nname=y=z\n = nope\n");
        assertEquals(1, entries.size()); assertEquals("y=z", entries.get("name"));
    }
    @Test public void iniRejectsMalformedChoices() {
        for (String text : List.of("auto", "auto 12345 solid", "auto #123456 solid", "auto 123456 clear extra", "square 123456 solid", "auto 123456 bad"))
            assertNull(text, ShellConfig.parse(text));
    }
    @Test public void bareAutoCancelsConfig() {
        assertFalse(ShellConfig.layered("Game=rounded 123456 clear", "Game=auto").containsKey("Game"));
    }
    @Test public void coloredAutoIsOverride() {
        ShellConfig.Choice choice = ShellConfig.layered("Game=rounded 123456 clear", "Game=auto 2f8f4e glitter").get("Game");
        assertEquals(0xff2f8f4e, choice.color()); assertEquals(CartridgeRenderer.Finish.GLITTER, choice.finish());
    }
    @Test public void invalidLabelsLeaveConfigIntact() {
        assertEquals(0xff123456, ShellConfig.layered("Game=rounded 123456 clear", "Game=invalid").get("Game").color());
    }
    @Test public void outlineUsesHeaderIndependentlyOfPlatform() throws Exception {
        File root = root(); rom(root, "GBC", "Dual.gbc", 0x80); rom(root, "GBC", "Only.gbc", 0xc0);
        List<RomRecord> records = scan(root, Cartridge.Platform.GBC);
        assertEquals(Cartridge.Platform.GB, ShellConfig.shape(records.get(0), null));
        assertEquals(Cartridge.Platform.GBC, ShellConfig.shape(records.get(1), null));
        assertEquals(Cartridge.Platform.GBC, ShellConfig.shape(records.get(0), ShellConfig.parse("rounded 123456 solid")));
    }
    @Test public void gbaHeaderAndAutomaticShell() throws Exception {
        File root = root(); File file = rom(root, "GBA", "Game.gba", 0); byte[] head = Files.readAllBytes(file.toPath());
        System.arraycopy("SYNTH TITLE".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, head, 0xa0, 11);
        System.arraycopy("BPEE".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, head, 0xac, 4); Files.write(file.toPath(), head);
        RomRecord record = scan(root, Cartridge.Platform.GBA).get(0);
        assertEquals("SYNTH TITLE", record.headerTitle()); assertEquals("BPEE", record.code());
        assertEquals(0xff249c60, ShellConfig.defaults(record).color());
    }
    @Test public void crcStreamsAndRejectsChangedFile() throws Exception {
        File root = root(); File file = rom(root, "GB", "Game.gb", 0); RomRecord record = scan(root, Cartridge.Platform.GB).get(0);
        CRC32 expected = new CRC32(); expected.update(Files.readAllBytes(file.toPath()));
        assertEquals(expected.getValue(), LibraryScanner.crc(record, () -> false));
        Files.write(file.toPath(), new byte[600]); assertThrows(IOException.class, () -> LibraryScanner.crc(record, () -> false));
    }
    @Test public void unsafePathsAreRejected() throws Exception {
        File root = root(); assertThrows(IOException.class, () -> LibraryPaths.safe(root, new File(root, "../outside")));
    }
    @Test public void queuedAndActiveScansCoalesce() {
        List<Runnable> tasks = new ArrayList<>(); AtomicInteger scans = new AtomicInteger(); ScanScheduler[] scheduler = new ScanScheduler[1];
        scheduler[0] = new ScanScheduler(tasks::add, platforms -> {
            int count = scans.incrementAndGet();
            if (count == 1) for (int i = 0; i < 10; i++) scheduler[0].request(EnumSet.of(Cartridge.Platform.GBA));
        });
        for (int i = 0; i < 10; i++) scheduler[0].request(EnumSet.of(Cartridge.Platform.GBA));
        assertEquals(1, tasks.size()); tasks.get(0).run(); assertEquals(2, scans.get()); scheduler[0].close();
    }
    @Test public void labelLookupNormalizesNfcAndPlatformCaseOnce() throws Exception {
        File root = root(); File folder = new File(root, "labels/gBa"); assertTrue(folder.mkdirs());
        File label = new File(folder, "Cafe\u0301.png"); Files.write(label.toPath(), new byte[]{1});
        Map<String, File> labels = LibraryPaths.labels(root, Cartridge.Platform.GBA);
        assertEquals(label, labels.get("Café"));
        assertEquals(label, LibraryPaths.label(root, Cartridge.Platform.GBA, "Café"));
    }
    @Test public void missingLabelsAreSuccessfulEmpty() throws Exception {
        assertTrue(LibraryPaths.labels(root(), Cartridge.Platform.GB).isEmpty());
    }
    @Test public void unknownCgbFlagFallsBackToOriginalShell() throws Exception {
        File root = root(); rom(root, "GBC", "Game.gbc", 0xc1);
        RomRecord record = scan(root, Cartridge.Platform.GBC).get(0);
        assertEquals(Cartridge.Platform.GB, ShellConfig.shape(record, null));
        assertEquals(0xff9a978f, ShellConfig.defaults(record).color());
    }
}
