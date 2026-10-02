package me.ele.lancet.plugin;

import com.android.build.api.artifact.ScopedArtifact;
import com.android.build.api.variant.AndroidComponentsExtension;
import com.android.build.api.variant.ScopedArtifacts;
import com.android.build.api.variant.Variant;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.ProjectConfigurationException;
import org.gradle.api.tasks.TaskProvider;

import java.util.Locale;

public class LancetPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        if (project.getPlugins().findPlugin("com.android.application") == null
                && project.getPlugins().findPlugin("com.android.library") == null) {
            throw new ProjectConfigurationException("Need android application/library plugin to be applied first", (Throwable) null);
        }

        LancetExtension lancetExtension = project.getExtensions().create("lancet", LancetExtension.class);
        @SuppressWarnings("rawtypes")
        AndroidComponentsExtension androidComponents =
                project.getExtensions().getByType(AndroidComponentsExtension.class);

        androidComponents.onVariants(androidComponents.selector().all(),
                (Action<Variant>) variant -> registerVariant(project, lancetExtension, variant));
    }

    private void registerVariant(Project project, LancetExtension extension, Variant variant) {
        String variantName = variant.getName();
        String taskName = "transform" + capitalize(variantName) + "ClassesWithLancet";
        TaskProvider<LancetTask> taskProvider = project.getTasks().register(taskName, LancetTask.class, task -> {
            task.setGroup("lancet");
            task.setDescription("Applies Lancet bytecode weaving to the " + variantName + " variant.");
            task.getLogLevel().set(project.provider(() -> extension.getLogLevel().name()));
            task.getLogFileName().set(project.provider(() ->
                    extension.getFileName() == null ? "" : extension.getFileName()));
            task.getWorkDirectory().set(project.getLayout().getBuildDirectory().dir("lancet/" + variantName));
        });

        variant.getArtifacts()
                .forScope(ScopedArtifacts.Scope.ALL)
                .use(taskProvider)
                .toTransform(
                        ScopedArtifact.CLASSES.INSTANCE,
                        LancetTask::getInputJars,
                        LancetTask::getInputDirectories,
                        LancetTask::getOutputJar
                );
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.US) + value.substring(1);
    }
}
