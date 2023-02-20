package ev.plugin

import com.android.build.gradle.BaseExtension
import ev.plugin.utils.DependencyUtil
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar

class ApiJarPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.afterEvaluate {
            def files = DependencyUtil.resolveArtifactView(project).files.collect {
                if (it.name.endsWith('.aar')) {
                    project.zipTree(it)
                } else {
                    it
                }
            }

            def projectSet = DependencyUtil.resolveSubProjects(project, false)
            def taskSet = new HashSet()
            def projectArchives = new ArrayList()

            def runnable = new Runnable() {
                @Override
                void run() {
                    if (taskSet.size() == projectSet.size()) {
                        def dependencies = project.files(files, projectArchives)
                        setupApiJarTask(project, dependencies, taskSet)
                    }
                }
            }

            for (Project subProject in projectSet) {
                def task = subProject.tasks.findByName("bundleReleaseAar")
                if (task != null) {
                    taskSet << task
                    projectArchives << project.zipTree(new File(subProject.buildDir, "outputs/aar/${subProject.name}-release.aar"))
                    continue
                }
                task = subProject.tasks.findByName("jar")
                if (task != null) {
                    taskSet << task
                    projectArchives << new File(subProject.buildDir, "libs/${subProject.name}.jar")
                    continue
                }

                subProject.tasks.whenTaskAdded {
                    if (it.name == "bundleReleaseAar") {
                        taskSet << it
                        projectArchives << project.zipTree(new File(subProject.buildDir, "outputs/aar/${subProject.name}-release.aar"))
                    } else if (it.name == "jar") {
                        taskSet << it
                        projectArchives << new File(subProject.buildDir, "libs/${subProject.name}.jar")
                    }
                    runnable.run()
                }
            }

            runnable.run()
        }
    }

    private static void setupApiJarTask(Project project, FileCollection dependencies, Set<Task> taskSet) {
        BaseExtension android = project.extensions.findByName("android")

        def intermediatesDir = "${project.buildDir}/intermediates"

        def apiSrcDir = project.file("${intermediatesDir}/apiSrc/")
        def copyTask = project.task(type: Copy, "copyApiFiles") {
            android.sourceSets.main.java.srcDirs.each {
                from project.fileTree(dir: it, include: '**/*.api')
            }
            into apiSrcDir
            rename '(.+)\\.api', '$1.java'
            filteringCharset = 'UTF-8'
        }
        taskSet.add(copyTask)

        /*
         * 解决 Gradle 解包某些 aar 文件时，文件已存在且没有写入权限导致的问题
         *
         * 也可通过 clean 再执行相应命令来解决
         */
        def delExpandedArchivesTask = project.task(type: Delete, "deleteExpandedArchives") {
            delete project.file("${project.buildDir}/tmp/expandedArchives")
        }
        taskSet.add(delExpandedArchivesTask)

        def androidJarPath = "${android.sdkDirectory}/platforms/${android.compileSdkVersion}/android.jar"
        def apiClassesDir = project.file("${intermediatesDir}/apiClasses/")
        def compileTask = project.task(type: JavaCompile, dependsOn: taskSet, "compileApiClasses") {
            source apiSrcDir
            classpath = project.files(dependencies, androidJarPath)
            destinationDir = apiClassesDir
            sourceCompatibility = android.compileOptions.sourceCompatibility.toString()
            targetCompatibility = android.compileOptions.targetCompatibility.toString()
            options.encoding = 'UTF-8'
        }

        project.task(type: Jar, dependsOn: compileTask, "bundleApiJar") {
            from apiClassesDir
            archiveAppendix.set('API')
        }
    }
}
