package buildlogic;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;
import org.gradle.process.ExecOperations;

public abstract class GitVersionSource implements ValueSource<String, GitVersionSource.Parameters> {
    public interface Parameters extends ValueSourceParameters { DirectoryProperty getRepository(); }
    @Inject public abstract ExecOperations getExecOperations();
    @Override public String obtain() {
        try {
            return GitVersionResolver.resolve(args -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
                List<String> command = new ArrayList<>(List.of("git"));
                command.addAll(List.of(args));
                var result = getExecOperations().exec(spec -> {
                    spec.setWorkingDir(getParameters().getRepository().get().getAsFile());
                    spec.commandLine(command); spec.setStandardOutput(out); spec.setErrorOutput(err);
                    spec.setIgnoreExitValue(true);
                });
                if (result.getExitValue() != 0) throw new GradleException("Git version lookup failed: " + err.toString(StandardCharsets.UTF_8));
                return out.toString(StandardCharsets.UTF_8).trim();
            });
        } catch (Exception e) {
            throw new GradleException("Cannot determine Git version. Use a full Git clone with Git installed. " + e.getMessage(), e);
        }
    }
}
