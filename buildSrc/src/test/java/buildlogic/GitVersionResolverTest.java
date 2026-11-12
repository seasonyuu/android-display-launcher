package buildlogic;

import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class GitVersionResolverTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path repo;
    @Before public void init() throws Exception {
        repo = temp.newFolder().toPath();
        git("init", "-b", "main"); git("config", "user.name", "Version Test");
        git("config", "user.email", "test@example.invalid");
        git("-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "initial");
    }
    private String git(String... args) {
        try {
            List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString())); cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor() != 0) throw new IllegalStateException(out);
            return out;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String[] version() { return GitVersionResolver.resolve(this::git).split("\\|", -1); }
    @Test public void noTagAndExactTag() {
        assertEquals("1", version()[0]); assertTrue(version()[1].startsWith("0.1.0-dev.1+g"));
        git("tag", "v0.1.0"); assertEquals("0.1.0", version()[1]); assertEquals("v0.1.0", version()[3]);
    }
    @Test public void developmentAndDirtyWorktree() throws Exception {
        git("tag", "v1.2.3"); git("-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "next");
        assertEquals("2", version()[0]); assertTrue(version()[1].startsWith("1.2.3-dev.1+g"));
        Files.writeString(repo.resolve("new.txt"), "untracked");
        assertTrue(version()[1].endsWith("-dirty")); assertEquals("true", version()[4]);
    }
    @Test public void nonVersionTagsAreIgnored() {
        git("tag", "v01.2.3"); git("tag", "preview");
        assertEquals("", version()[3]); assertTrue(version()[1].startsWith("0.1.0-dev."));
    }
    @Test public void ambiguousExactTagsFail() {
        git("tag", "v1.0.0"); git("tag", "v2.0.0");
        assertThrows(IllegalStateException.class, this::version);
    }
    @Test public void shallowAndMissingGitFail() {
        assertThrows(IllegalStateException.class, () -> GitVersionResolver.resolve(args -> "true"));
        assertThrows(IllegalStateException.class, () -> GitVersionResolver.resolve(args -> "false"));
    }
    @Test public void nearestAncestorAndTagRemoval() {
        git("tag", "v0.1.0"); git("-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "next");
        git("tag", "v0.2.0"); assertEquals("0.2.0", version()[1]);
        git("tag", "-d", "v0.2.0"); assertTrue(version()[1].startsWith("0.1.0-dev.1+g"));
    }
}
