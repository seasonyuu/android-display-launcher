package buildlogic;

import java.io.IOException;
import java.nio.file.Files;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

public abstract class WriteVersionInfo extends DefaultTask {
    @Input public abstract Property<String> getVersionFields();
    @OutputFile public abstract RegularFileProperty getOutputFile();
    @TaskAction public void write() throws IOException {
        String[] fields = getVersionFields().get().split("\\|", -1);
        String json = String.format("{\"versionCode\":%s,\"versionName\":\"%s\",\"commit\":\"%s\",\"tag\":\"%s\",\"dirty\":%s}%n",
                fields[0], fields[1], fields[2], fields[3], fields[4]);
        var file = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(file.getParent()); Files.writeString(file, json);
        getLogger().lifecycle("Git version: {} ({}), commit {}", fields[1], fields[0], fields[2]);
    }
}
