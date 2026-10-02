package me.ele.lancet.plugin;

import com.android.build.api.transform.Status;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.LocalState;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import me.ele.lancet.plugin.internal.preprocess.AsmClassProcessorImpl;
import me.ele.lancet.plugin.internal.preprocess.MetaGraphGeneratorImpl;
import me.ele.lancet.plugin.internal.preprocess.PreClassProcessor;
import me.ele.lancet.weaver.ClassData;
import me.ele.lancet.weaver.Weaver;
import me.ele.lancet.weaver.internal.AsmWeaver;
import me.ele.lancet.weaver.internal.entity.TransformInfo;
import me.ele.lancet.weaver.internal.graph.CheckFlow;
import me.ele.lancet.weaver.internal.graph.Graph;
import me.ele.lancet.weaver.internal.log.Impl.FileLoggerImpl;
import me.ele.lancet.weaver.internal.log.Log;
import me.ele.lancet.weaver.internal.parser.AsmMetaParser;

/**
 * AGP 8.x adapter for Lancet's existing full-project weaver.
 *
 * <p>The removed Transform API supplied incremental status and separate outputs. ScopedArtifacts
 * supplies the complete class set and expects one output jar, so this adapter intentionally runs a
 * full analysis and keeps the original graph/parser/weaver pipeline unchanged.</p>
 */
@CacheableTask
public abstract class LancetTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ListProperty<RegularFile> getInputJars();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ListProperty<Directory> getInputDirectories();

    @Classpath
    public abstract ListProperty<RegularFile> getBootClasspath();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @Input
    public abstract Property<String> getLogLevel();

    @Input
    public abstract Property<String> getLogFileName();

    @LocalState
    public abstract DirectoryProperty getWorkDirectory();

    @TaskAction
    public void transform() throws IOException {
        configureLogging();

        List<File> directories = getInputDirectories().get().stream()
                .map(Directory::getAsFile)
                .filter(File::exists)
                .collect(Collectors.toList());
        List<File> jars = getInputJars().get().stream()
                .map(RegularFile::getAsFile)
                .filter(File::exists)
                .collect(Collectors.toList());
        List<File> bootClasspath = getBootClasspath().get().stream()
                .map(RegularFile::getAsFile)
                .filter(File::exists)
                .collect(Collectors.toList());

        Log.i("Lancet full analysis started: " + directories.size() + " directories, "
                + jars.size() + " jars, " + bootClasspath.size() + " boot classpath entries");

        AnalysisResult analysis = analyze(directories, jars, bootClasspath);
        Weaver weaver = createWeaver(analysis, directories, jars, bootClasspath);
        writeOutput(weaver, directories, jars, getOutputJar().get().getAsFile());

        Log.i("Lancet bytecode weaving completed");
    }

    private void configureLogging() throws IOException {
        Log.setLevel(Log.Level.valueOf(getLogLevel().get()));
        String fileName = getLogFileName().get();
        if (!fileName.isEmpty()) {
            if (fileName.contains(File.separator)) {
                throw new IllegalArgumentException("Log file name can't contain a file separator");
            }
            File logFile = new File(getWorkDirectory().get().getAsFile(), "log_" + fileName);
            File parent = logFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Cannot create Lancet log directory: " + parent);
            }
            Log.setImpl(FileLoggerImpl.of(logFile.getAbsolutePath()));
        }
    }

    private AnalysisResult analyze(List<File> directories, List<File> jars,
                                   List<File> bootClasspath) throws IOException {
        PreClassProcessor processor = new AsmClassProcessorImpl();
        MetaGraphGeneratorImpl graphGenerator = new MetaGraphGeneratorImpl(new CheckFlow());
        List<String> hookClasses = new ArrayList<>();

        BiConsumer<String, byte[]> analyzer = (relativePath, bytes) -> {
            if (!isAnalyzableClass(relativePath)) {
                return;
            }
            PreClassProcessor.ProcessResult result = processor.process(bytes);
            graphGenerator.add(result.entity, Status.ADDED);
            if (result.isHookClass) {
                hookClasses.add(result.entity.name);
            }
        };

        for (File directory : directories) {
            visitDirectory(directory, analyzer);
        }
        for (File jar : jars) {
            visitJar(jar, analyzer);
        }
        for (File bootClasspathEntry : bootClasspath) {
            visitJar(bootClasspathEntry, analyzer);
        }

        return new AnalysisResult(graphGenerator.generate(), hookClasses);
    }

    private Weaver createWeaver(AnalysisResult analysis, List<File> directories, List<File> jars,
                                List<File> bootClasspath) {
        List<File> classPath = new ArrayList<>(
                directories.size() + jars.size() + bootClasspath.size());
        classPath.addAll(directories);
        classPath.addAll(jars);
        classPath.addAll(bootClasspath);
        URL[] urls = classPath.stream().map(File::toURI).map(uri -> {
            try {
                return uri.toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(e);
            }
        }).toArray(URL[]::new);

        try (URLClassLoader loader = URLClassLoader.newInstance(urls, null)) {
            Graph graph = analysis.graph;
            TransformInfo transformInfo = new AsmMetaParser(loader).parse(analysis.hookClasses, graph);
            return AsmWeaver.newInstance(transformInfo, graph);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to close Lancet class loader", e);
        }
    }

    private void writeOutput(Weaver weaver, List<File> directories, List<File> jars, File output)
            throws IOException {
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create Lancet output directory: " + parent);
        }

        Set<String> writtenEntries = new HashSet<>();
        try (JarOutputStream out = new JarOutputStream(
                new BufferedOutputStream(new FileOutputStream(output)))) {
            BiConsumer<String, byte[]> writer = (relativePath, bytes) -> {
                try {
                    if (isSignatureFile(relativePath)) {
                        return;
                    }
                    if (!relativePath.endsWith(".class") || !isAnalyzableClass(relativePath)) {
                        writeEntry(out, writtenEntries, relativePath, bytes);
                        return;
                    }
                    if (writtenEntries.contains(relativePath)) {
                        Log.w("Duplicate class ignored while merging ScopedArtifacts: " + relativePath);
                        return;
                    }
                    for (ClassData data : weaver.weave(bytes, relativePath)) {
                        writeEntry(out, writtenEntries, data.getClassName() + ".class",
                                data.getClassBytes());
                    }
                } catch (IOException e) {
                    throw new UncheckedIoException(e);
                }
            };

            try {
                for (File directory : directories) {
                    visitDirectory(directory, writer);
                }
                for (File jar : jars) {
                    visitJar(jar, writer);
                }
            } catch (UncheckedIoException e) {
                throw e.getCause();
            }
        }
    }

    private static void visitDirectory(File root, BiConsumer<String, byte[]> consumer) throws IOException {
        Path base = root.toPath();
        try (java.util.stream.Stream<Path> paths = Files.walk(base)) {
            List<Path> files = paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
            for (Path file : files) {
                String relativePath = base.relativize(file).toString().replace(File.separatorChar, '/');
                consumer.accept(relativePath, Files.readAllBytes(file));
            }
        }
    }

    private static void visitJar(File file, BiConsumer<String, byte[]> consumer) throws IOException {
        try (JarFile jar = new JarFile(file)) {
            List<JarEntry> entries = jar.stream()
                    .filter(entry -> !entry.isDirectory())
                    .sorted(Comparator.comparing(JarEntry::getName))
                    .collect(Collectors.toList());
            for (JarEntry entry : entries) {
                try (BufferedInputStream input = new BufferedInputStream(jar.getInputStream(entry))) {
                    consumer.accept(entry.getName(), readAllBytes(input));
                }
            }
        }
    }

    private static byte[] readAllBytes(BufferedInputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void writeEntry(JarOutputStream out, Set<String> writtenEntries,
                                   String name, byte[] bytes) throws IOException {
        if (!writtenEntries.add(name)) {
            return;
        }
        JarEntry entry = new JarEntry(name);
        entry.setTime(0L);
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }

    private static boolean isAnalyzableClass(String relativePath) {
        return relativePath.endsWith(".class")
                && !relativePath.equals("module-info.class")
                && !relativePath.startsWith("META-INF/versions/");
    }

    private static boolean isSignatureFile(String relativePath) {
        String upper = relativePath.toUpperCase(Locale.US);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA"));
    }

    private static class AnalysisResult {
        final Graph graph;
        final List<String> hookClasses;

        AnalysisResult(Graph graph, List<String> hookClasses) {
            this.graph = graph;
            this.hookClasses = hookClasses;
        }
    }

    private static class UncheckedIoException extends RuntimeException {
        private final IOException cause;

        UncheckedIoException(IOException cause) {
            super(cause);
            this.cause = cause;
        }

        @Override
        public synchronized IOException getCause() {
            return cause;
        }
    }
}
