package ev.plugin.fataar

import com.android.build.gradle.BaseExtension
import ev.plugin.fataar.extensions.FatAarExtension
import ev.plugin.fataar.tasks.FatAarBundleTask
import ev.plugin.fataar.tasks.FatAarPomTask
import ev.plugin.fataar.tasks.FatAarProguardTask
import ev.plugin.fataar.utils.DependencyUtil
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.diagnostics.DependencyReportTask

class FatAarPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        def extension = new FatAarExtension()
        project.extensions.add("fatAar", extension)

        //gradle配置结束
        project.afterEvaluate {
            if (!extension.isValid()) {
                throw new IllegalStateException("Config fatAar invalid")
            }

            //依赖的子工程
            def projectSet = DependencyUtil.resolveSubProjects(project)
            projectSet.each {
                project.logger.lifecycle("Dep Module: ${it}" + "\npath :" + it.projectDir.path)
            }

            def ignoredList = projectSet.collect { it.projectDir.path }

            //依赖的库
            def dependencies = DependencyUtil.resolveArtifactView(project).files.filter { File file ->
                project.logger.lifecycle("Dep Aar/Jar: ${file}" + "\npath :" + file.path)

                for (def ignoredDir in ignoredList) {
                    if (file.path.startsWith(ignoredDir)) {
                        project.logger.lifecycle("Ignored: ${file}")
                        return false
                    }
                }
                return true
            }.getFiles()

            def includeArchives = new HashSet<File>()
            if (extension.include != null) {
                extension.include.each { name ->
                    def path = name.replace(":", File.separator)
                    def set = dependencies.findAll { file ->
                        project.logger.lifecycle("include path:" + path + "\n  file.path:" + file.path)
                        file.path.contains(path)
                    }
                    dependencies.removeAll(set)
                    switch (set.size()) {
                        case 0:
                            throw new IllegalArgumentException(name + " not found in dependencies")
                        case 1:
                            includeArchives.add(set[0])
                            break
                        default:
                            throw new IllegalArgumentException(name + " more than one match in dependencies")
                    }
                }
            }

            def taskSet = new HashSet()

            def runnable = new Runnable() {
                @Override
                void run() {
                    if (taskSet.size() == projectSet.size()) {
                        setupFatJarTask(project, extension, projectSet, includeArchives, dependencies, taskSet)
                    }
                }
            }

            for (Project subProject in projectSet) {
                def task = subProject.tasks.findByName("bundleReleaseAar")
                if (task != null) {
                    project.logger.lifecycle("subProject findByName:" + subProject.name + " # task name：bundleReleaseAar")
                    taskSet.add(task)
                    continue
                }
                task = subProject.tasks.findByName("jar")
                if (task != null) {
                    project.logger.lifecycle("subProject findByName:" + subProject.name + " # task name：jar")
                    taskSet.add(task)
                    continue
                }

                subProject.tasks.whenTaskAdded {
                    if (it.name == "bundleReleaseAar") {
                        project.logger.lifecycle("subProject whenTaskAdded:" + subProject.name + " # task name：bundleReleaseAar")
                        taskSet.add(it)
                    } else if (it.name == "jar") {
                        project.logger.lifecycle("subProject whenTaskAdded:" + subProject.name + " # task name：jar")
                        taskSet.add(it)
                    }
                    runnable.run()
                }
            }

            runnable.run()
        }
    }

    private static void setupFatJarTask(
            Project project,
            FatAarExtension extension,
            Set<Project> projects,
            Set<File> archives,
            Set<File> dependencies,
            Set<Task> taskSet
    ) {
        BaseExtension android = project.extensions.findByName("android")

        def tempDir = "${project.buildDir}/intermediates/fat-aar"
        def mergeDir = "${project.buildDir}/outputs/fat-aar"

        def aarTask = project.task(type: FatAarBundleTask, dependsOn: taskSet, "bundleFatAar") {
            intermediatesDir tempDir
            outputDir mergeDir
            projectSet projects
            includeArchives archives
        }

        def depsFile = new File(tempDir, "dependencies.txt")

        //依赖树task
        def depsTask = project.task(type: DependencyReportTask, "releaseRuntimeClasspathDeps") {
            setConfiguration("releaseRuntimeClasspath")
            outputFile = depsFile
        }

        //生成pom文件
        def pomTask = project.task(type: FatAarPomTask, dependsOn: depsTask, "generateFatAarPom") {
            dependenceFile depsFile
            outputDir mergeDir
        }

        //终止任务，类似于finally
        aarTask.finalizedBy(pomTask)

        if (extension.proguardRules != null && !extension.proguardRules.isEmpty()) {
            def proguardTask = project.task(type: FatAarProguardTask, "proguardFatAar") {
                printmapping "${mergeDir}/mapping.txt"
                configuration extension.proguardRules
                libraryjars dependencies
                libraryjars "${android.sdkDirectory}/platforms/${android.compileSdkVersion}/android.jar"

                injars "${tempDir}/classes.jar"
                outjars "${tempDir}/classes_proguard.jar"

                sourceJar "${tempDir}/classes_proguard.jar"
                sourceAar "${mergeDir}/${extension.name}-${extension.version}.aar"
                consumerProguardRules "${tempDir}/proguard.txt"
                outputAar "${mergeDir}/${extension.name}-proguard-${extension.version}.aar"
            }

            aarTask.finalizedBy(proguardTask)
        }
    }
}
