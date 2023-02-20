package ev.plugin.api

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.DependencyResolutionListener
import org.gradle.api.artifacts.ResolvableDependencies

class ApiConfigurationPlugin implements Plugin<Project> {

    private static final String API_PROJECT_NAME_SUFFIX = "-API"

    private static final String CONFIGURATION_PROVIDED = "compileOnly"
    private static final String CONFIGURATION_PROVIDE_API = "provideApi"

    private static final String CONFIGURATION_COMPILE = "api"
    private static final String CONFIGURATION_COMPILE_API = "compileApi"

    @Override
    void apply(Project project) {
        def provideApi = project.configurations.create(CONFIGURATION_PROVIDE_API) {
            it.transitive = false
            it.visible = false
        }

        def compileApi = project.configurations.create(CONFIGURATION_COMPILE_API) {
            it.transitive = false
            it.visible = false
        }

        project.gradle.addListener(new DependencyResolutionListener() {
            @Override
            void beforeResolve(ResolvableDependencies resolvableDependencies) {
                provideApi.dependencies.each {
                    Project target = project.rootProject.findProject(it.name)
                    setupLibDependency(project, target, CONFIGURATION_PROVIDED)
                    setupTaskDependency(project, target)
                }
                compileApi.dependencies.each {
                    Project target = project.rootProject.findProject(it.name)
                    setupLibDependency(project, target, CONFIGURATION_COMPILE)
                    setupTaskDependency(project, target)
                }
                project.gradle.removeListener(this)
            }

            @Override
            void afterResolve(ResolvableDependencies resolvableDependencies) {
            }
        })
    }

    static def setupLibDependency(Project project, Project target, String configuration) {
        def apiJar = project.files("${target.buildDir}/libs/${target.name}${API_PROJECT_NAME_SUFFIX}.jar")
        def dependency = project.dependencies.create(apiJar)
        project.dependencies.add(configuration, dependency)
    }

    static def setupTaskDependency(Project project, Project target) {
        Task compileTask = project.tasks.findByName("javaPreCompileRelease")
        Task bundleApiTask = target.tasks.findByName("bundleApiJar")
        if (compileTask != null && bundleApiTask != null) {
            compileTask.dependsOn(bundleApiTask)
            return
        }

        project.tasks.whenTaskAdded { Task task ->
            if (task.name == "javaPreCompileRelease") {
                compileTask = task
                if (bundleApiTask != null) {
                    compileTask.dependsOn(bundleApiTask)
                }
            }
        }

        target.tasks.whenTaskAdded { Task task ->
            if (task.name == "bundleApiJar") {
                bundleApiTask = task
                if (compileTask != null) {
                    compileTask.dependsOn(bundleApiTask)
                }
            }
        }
    }
}
