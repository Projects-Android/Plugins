package ev.plugin.fataar.tasks

import ev.plugin.fataar.extensions.FatAarExtension
import ev.plugin.fataar.utils.DependencyParser
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * 生成pom文件
 */
class FatAarPomTask extends DefaultTask {

    FatAarExtension getExtension() {
        return project.extensions.fatAar
    }

    File dependenceFile

    File outputDir

    def dependenceFile(File dependenceFile) {
        this.dependenceFile = dependenceFile
    }

    def outputDir(String path) {
        outputDir = project.file(path)
    }

    @InputFile
    File getInputFile() {
        return dependenceFile
    }

    @OutputFile
    File getOutputFile() {
        return project.file("${outputDir}/${extension.name}-${extension.version}.pom")
    }

    @TaskAction
    def generatePom() {
        def pom = outputFile

        def indent = " " * 2

        pom << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        pom << "<project xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\" xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"

        pom << indent << "<modelVersion>4.0.0</modelVersion>\n"
        pom << indent << "<groupId>${extension.group}</groupId>\n"
        pom << indent << "<artifactId>${extension.name}</artifactId>\n"
        pom << indent << "<version>${extension.version}</version>\n"
        pom << indent << "<packaging>aar</packaging>\n"

        pom << indent << "<dependencies>\n"

        def pkgs = new DependencyParser(dependenceFile, extension.include).parse()

        pkgs.each { pkg ->
            logger.lifecycle(pkg)

            def splits = pkg.split(":")
            def group = splits[0]
            def name = splits[1]
            def version = splits[2]
            pom << generateDependencyXml(group, name, version, indent)
        }

        pom << indent << "</dependencies>\n"

        pom << "</project>\n"
    }

    static def generateDependencyXml(String group, String name, String version, String indent) {
        def builder = new StringBuilder()

        builder << indent << indent << "<dependency>\n"
        builder << indent << indent << indent << "<groupId>${group}</groupId>\n"
        builder << indent << indent << indent << "<artifactId>${name}</artifactId>\n"
        builder << indent << indent << indent << "<version>${version}</version>\n"
        builder << indent << indent << indent << "<scope>compile</scope>\n"
        builder << indent << indent << "</dependency>\n"

        return builder.toString()
    }
}
