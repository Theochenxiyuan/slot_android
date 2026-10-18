package fyi.slot.android;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public final class ImportWriterTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private byte[] bytes() { return new byte[512]; }
    private ImportWriter.Result write(File root, String name, long length, byte[] data) throws IOException {
        return ImportWriter.write(root, name, length, new ByteArrayInputStream(data), () -> false, count -> {});
    }
    private void noParts(File root) throws IOException {
        try (var paths = Files.walk(root.toPath())) { assertFalse(paths.anyMatch(path -> path.toString().endsWith(".part"))); }
    }
    @Test public void mapsExtensionsToOriginalPlatformFolders() throws Exception {
        assertEquals(Cartridge.Platform.GBA, ImportWriter.platform("Game.GBA"));
        assertEquals(Cartridge.Platform.GB, ImportWriter.platform("Color.gb"));
        assertEquals(Cartridge.Platform.GBC, ImportWriter.platform("Mono.gbc"));
    }
    @Test public void writesAtomicallyAndPreservesBytes() throws Exception {
        File root = temp.newFolder();
        ImportWriter.Result result = write(root, "Game.gba", 512, bytes());
        assertEquals(ImportWriter.Outcome.ADDED, result.outcome());
        assertArrayEquals(bytes(), Files.readAllBytes(result.file().toPath())); noParts(root);
        assertEquals(1, new LibraryScanner().scan(root, Cartridge.Platform.GBA, () -> false).size());
    }
    @Test public void skipsSameNameWithoutReplacing() throws Exception {
        File root = temp.newFolder(); File file = write(root, "Game.gba", 512, bytes()).file();
        byte[] other = bytes(); other[0] = 42;
        assertEquals(ImportWriter.Outcome.SKIPPED, write(root, "Game.gba", 512, other).outcome());
        assertEquals(0, Files.readAllBytes(file.toPath())[0]); noParts(root);
    }
    @Test public void skipsCaseAndNfcEquivalentNames() throws Exception {
        File root = temp.newFolder(); write(root, "Café.gba", 512, bytes());
        assertEquals(ImportWriter.Outcome.SKIPPED, write(root, "CAFE\u0301.GBA", 512, bytes()).outcome());
    }
    @Test public void sameStemDifferentExtensionsRemainSeparate() throws Exception {
        File root = temp.newFolder(); write(root, "Twin.gb", 512, bytes()); write(root, "Twin.gbc", 512, bytes());
        assertTrue(new File(root, "Games/GB/Twin.gb").isFile()); assertTrue(new File(root, "Games/GBC/Twin.gbc").isFile());
    }
    @Test public void rejectsUnsafeNames() {
        for (String name : new String[]{"../x.gba", ".x.gba", "x/y.gb", "x\\y.gb", "x\n.gb", "x:gb.gba", "x.gba ", ""})
            assertThrows(name, ImportWriter.Failure.class, () -> ImportWriter.platform(name));
    }
    @Test public void rejectsArchivesAndUnsupportedExtensions() {
        assertThrows(ImportWriter.Failure.class, () -> ImportWriter.platform("Game.zip"));
        assertThrows(ImportWriter.Failure.class, () -> ImportWriter.platform("Game.nds"));
    }
    @Test public void rejectsKnownSizeAboveLimitBeforeReading() throws Exception {
        File root = temp.newFolder();
        assertEquals(ImportWriter.Problem.SIZE, assertThrows(ImportWriter.Failure.class,
                () -> write(root, "Game.gba", ImportWriter.MAX_BYTES + 1, bytes())).problem); noParts(root);
    }
    @Test public void rejectsShortKnownHeader() throws Exception {
        assertEquals(ImportWriter.Problem.SHORT, assertThrows(ImportWriter.Failure.class,
                () -> write(temp.newFolder(), "Short.gb", 335, new byte[335])).problem);
    }
    @Test public void unknownSizeStillRequiresHeader() throws Exception {
        File root = temp.newFolder();
        assertThrows(ImportWriter.Failure.class, () -> write(root, "Short.gb", -1, new byte[335])); noParts(root);
    }
    @Test public void unknownSizeStreamsAndReportsProgress() throws Exception {
        File root = temp.newFolder(); AtomicLong progress = new AtomicLong();
        ImportWriter.write(root, "Game.gba", -1, new ByteArrayInputStream(bytes()), () -> false, progress::set);
        assertEquals(512, progress.get()); noParts(root);
    }
    @Test public void shorterThanDeclaredIsDiscarded() throws Exception {
        File root = temp.newFolder(); assertThrows(ImportWriter.Failure.class, () -> write(root, "Game.gba", 1024, bytes()));
        assertFalse(new File(root, "Games/GBA/Game.gba").exists()); noParts(root);
    }
    @Test public void longerThanDeclaredIsDiscarded() throws Exception {
        File root = temp.newFolder(); assertThrows(ImportWriter.Failure.class, () -> write(root, "Game.gba", 400, bytes())); noParts(root);
    }
    @Test public void cancellationBeforeStartDoesNotCreateDirectories() throws Exception {
        File root = temp.newFolder(); assertThrows(ImportWriter.Failure.class,
                () -> ImportWriter.write(root, "Game.gba", 512, new ByteArrayInputStream(bytes()), () -> true, count -> {}));
        assertEquals(0, root.list().length);
    }
    @Test public void cancellationDuringWriteRemovesPart() throws Exception {
        File root = temp.newFolder(); AtomicBoolean cancelled = new AtomicBoolean();
        assertEquals(ImportWriter.Problem.CANCELLED, assertThrows(ImportWriter.Failure.class,
                () -> ImportWriter.write(root, "Game.gba", -1, new ByteArrayInputStream(new byte[131072]), cancelled::get,
                        count -> cancelled.set(true))).problem); noParts(root);
    }
    @Test public void readFailureRemovesPart() throws Exception {
        File root = temp.newFolder(); InputStream broken = new InputStream() {
            int calls;
            @Override public int read() throws IOException { if (++calls > 400) throw new IOException("Lost source"); return 0; }
        };
        assertThrows(IOException.class, () -> ImportWriter.write(root, "Game.gba", -1, broken, () -> false, count -> {})); noParts(root);
    }
    @Test public void targetAppearingDuringCopyIsNotOverwritten() throws Exception {
        File root = temp.newFolder(); AtomicBoolean created = new AtomicBoolean();
        ImportWriter.Result result = ImportWriter.write(root, "Game.gba", 512, new ByteArrayInputStream(bytes()), () -> false,
                count -> { if (!created.getAndSet(true)) try { Files.write(new File(root, "Games/GBA/Game.gba").toPath(), new byte[]{42}); }
                catch (IOException error) { throw new AssertionError(error); } });
        assertEquals(ImportWriter.Outcome.SKIPPED, result.outcome()); assertEquals(42, Files.readAllBytes(result.file().toPath())[0]); noParts(root);
    }
    @Test public void usesExistingCaseInsensitiveDirectories() throws Exception {
        File root = temp.newFolder(); assertTrue(new File(root, "games/gBa").mkdirs());
        assertEquals(new File(root, "games/gBa/Game.gba"), write(root, "Game.gba", 512, bytes()).file());
    }
    @Test public void missingRootIsNotRecreated() throws Exception {
        File root = new File(temp.getRoot(), "offline");
        assertThrows(ImportWriter.Failure.class, () -> write(root, "Game.gb", 512, bytes())); assertFalse(root.exists());
    }
    @Test public void scannerCleansStalePartsAndKeepsOtherDotFiles() throws Exception {
        File root = temp.newFolder(); File folder = new File(root, "Games/GBA"); assertTrue(folder.mkdirs());
        File part = new File(folder, ".Game.gba.part"); Files.write(part.toPath(), bytes());
        File other = new File(folder, ".keep"); Files.write(other.toPath(), bytes());
        assertTrue(new LibraryScanner().scan(root, Cartridge.Platform.GBA, () -> false).isEmpty());
        assertFalse(part.exists()); assertTrue(other.exists());
    }
    @Test public void scannerDoesNotCleanActiveWrite() throws Exception {
        File root = temp.newFolder();
        ImportWriter.write(root, "Game.gba", 512, new ByteArrayInputStream(bytes()), () -> false, count -> {
            try { assertTrue(new LibraryScanner().scan(root, Cartridge.Platform.GBA, () -> false).isEmpty());
                assertTrue(new File(root, "Games/GBA/.Game.gba.part").exists()); }
            catch (IOException error) { throw new AssertionError(error); }
        }); noParts(root);
    }
}
