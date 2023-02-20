package ev.plugin.fataar.tasks

import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import proguard.gradle.ProGuardTask

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 生成混淆包
 */
class FatAarProguardTask extends ProGuardTask {

    public static final String CLASSES_JAR_ENTRY = "classes.jar"
    public static final String PROGUARD_ENTRY = "proguard.txt"

    File sourceJar

    @InputFile
    File sourceAar

    @InputFile
    File consumerProguardRules

    @OutputFile
    File outputAar

    def sourceJar(String path) {
        sourceJar = project.file(path)
    }

    def sourceAar(String path) {
        sourceAar = project.file(path)
    }

    def consumerProguardRules(String path) {
        consumerProguardRules = project.file(path)
    }

    def outputAar(String path) {
        outputAar = project.file(path)
    }

    @TaskAction
    def generateAar() {
        new ZipOutputStream(new FileOutputStream(outputAar)).withCloseable { aarOutput ->
            aarOutput.putNextEntry(new ZipEntry(CLASSES_JAR_ENTRY))
            aarOutput << sourceJar.newInputStream()
            aarOutput.closeEntry()

            aarOutput.putNextEntry(new ZipEntry(PROGUARD_ENTRY))
            aarOutput << consumerProguardRules.newInputStream()
            aarOutput.closeEntry()

            new ZipFile(sourceAar).withCloseable { oldAar ->
                def entries = oldAar.entries()
                while (entries.hasMoreElements()) {
                    def entry = entries.nextElement()
                    if (entry.name == CLASSES_JAR_ENTRY) continue

                    aarOutput.putNextEntry(entry)
                    aarOutput << oldAar.getInputStream(entry)
                    aarOutput.closeEntry()
                }
            }
        }
    }
}
