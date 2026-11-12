package buildlogic;

import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;

public final class GitVersionResolver {
    @FunctionalInterface public interface Git { String run(String... arguments); }
    private static final Pattern TAG = Pattern.compile("v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");
    private static List<String> tags(String output) {
        return output.lines().filter(s -> TAG.matcher(s).matches()).toList();
    }
    private static int compareVersions(String a, String b) {
        String[] left = a.substring(1).split("\\."), right = b.substring(1).split("\\.");
        for (int i = 0; i < 3; i++) {
            int result = new BigInteger(left[i]).compareTo(new BigInteger(right[i]));
            if (result != 0) return result;
        }
        return 0;
    }
    /** code|base versionName|full SHA|exact tag or empty|dirty */
    public static String resolve(Git git) {
        if (!git.run("rev-parse", "--is-inside-work-tree").equals("true"))
            throw new IllegalStateException("Build from a Git clone, not a source ZIP.");
        if (!git.run("rev-parse", "--is-shallow-repository").equals("false"))
            throw new IllegalStateException("Full Git history required. Run: git fetch --unshallow --tags");
        long count = Long.parseLong(git.run("rev-list", "--count", "HEAD"));
        if (count < 1 || count > 2100000000L) throw new IllegalStateException("Git commit count is outside Android versionCode bounds.");
        String sha = git.run("rev-parse", "HEAD");
        boolean dirty = !git.run("status", "--porcelain", "--untracked-files=normal").isEmpty();
        List<String> exact = tags(git.run("tag", "--points-at", "HEAD"));
        if (exact.size() > 1) throw new IllegalStateException("More than one version tag points at HEAD: " + exact);
        String tag = exact.isEmpty() ? "" : exact.get(0);
        String base = "0.1.0";
        long distance = count;
        String nearest = null;
        for (String candidate : tags(git.run("tag", "--merged", "HEAD"))) {
            long candidateDistance = Long.parseLong(git.run("rev-list", "--count", candidate + "..HEAD"));
            if (nearest == null || candidateDistance < distance ||
                    candidateDistance == distance && compareVersions(candidate, nearest) > 0) {
                nearest = candidate; distance = candidateDistance; base = candidate.substring(1);
            }
        }
        String name = !tag.isEmpty() && !dirty ? tag.substring(1) :
                base + "-dev." + distance + "+g" + sha.substring(0, 7);
        if (dirty) name += "-dirty";
        return count + "|" + name + "|" + sha + "|" + tag + "|" + dirty;
    }
    private GitVersionResolver() {}
}
