package ev.plugin.fataar.tasks

import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.MergingReport
import com.android.utils.NullLogger
import javassist.bytecode.ClassFile
import ev.plugin.fataar.extensions.FatAarExtension
import ev.plugin.fataar.utils.ImplementProviderHelper
import org.apache.commons.io.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class FatAarBundleTask extends DefaultTask {

    public static final Pattern MANIFEST_PACKAGE_PATTERN = Pattern.compile("^.*package=\\\"((\\w*\\.)*\\w+)\\\".*\$")
    public static final Pattern VALUES_ENTRY_PATTERN = Pattern.compile("^(res\\/values[-\\w]*\\/)values[-\\w]*\\.xml\$")
    public static final Pattern LIBS_ENTRY_PATTERN = Pattern.compile("^libs\\/[\\w-]+\\.jar\$")

    public static final String CLASSES_JAR_ENTRY = "classes.jar"
    public static final String ANDROID_MANIFEST_ENTRY = "AndroidManifest.xml"
    public static final String R_TXT_ENTRY = "R.txt"
    public static final String PROGUARD_ENTRY = "proguard.txt"

    public static final String EXT_AAR = ".aar"
    public static final String EXT_JAR = ".jar"
    public static final String EXT_CLASS = ".class"

    public static final List<String> R_CLASSES = Arrays.asList(
            "R", "R\$anim", "R\$animator", "R\$array", "R\$attr", "R\$bool", "R\$color", "R\$dimen",
            "R\$drawable", "R\$fraction", "R\$id", "R\$integer", "R\$interpolator", "R\$layout",
            "R\$menu", "R\$mipmap", "R\$plurals", "R\$raw", "R\$string", "R\$style", "R\$styleable",
            "R\$transition", "R\$xml")

    FatAarExtension getExtension() {
        return project.extensions.fatAar
    }

    File intermediatesDir

    File outputDir

    Set<Project> projectSet

    Set<File> includeArchives

    def intermediatesDir(String path) {
        intermediatesDir = project.file(path)
    }

    def outputDir(String path) {
        outputDir = project.file(path)
    }

    def projectSet(Set<Project> projectSet) {
        this.projectSet = projectSet
    }

    def includeArchives(Set<File> includeArchives) {
        this.includeArchives = includeArchives
    }

    @InputFiles
    List<File> getSourceFiles() {
        if (projectSet == null) {
            throw new NullPointerException("projectSet null")
        }
        if (includeArchives == null) {
            throw new NullPointerException("includeArchives null")
        }

        def files = new ArrayList<File>()
        projectSet.each {
            def android = it.extensions.findByName("android")
            if (android != null) {
                files << new File(it.buildDir, "outputs/aar/${it.name}-release.aar")
            } else {
                files << new File(it.buildDir, "libs/${it.name}.jar")
            }
        }
        includeArchives.each { files << it }

        return files
    }

    @OutputFile
    File getOutputFile() {
        return project.file("${outputDir}/${extension.name}-${extension.version}.aar")
    }

    //task要执行的动作
    @TaskAction
    def transform() {
        intermediatesDir.mkdirs()
        FileUtils.deleteDirectory(intermediatesDir)

        outputDir.mkdirs()
        FileUtils.deleteDirectory(outputDir)

        def mainAar = new File(project.buildDir, "outputs/aar/${project.name}-release.aar")
        def mainPkgName = resolvePackageName(mainAar)

        def mainManifest = null
        def libraryManifests = new ArrayList<File>()

        def classesJarList = new ArrayList<File>()

        def valuesXmlMap = new HashMap<String, List<File>>()

        def aarList = new ArrayList<File>()

        sourceFiles.each {
            if (it.path.endsWith(EXT_AAR)) {
                def aar = it
                aarList.add(aar)

                def dirName = it.name.substring(0, it.name.indexOf(".aar"))
                def sourceDir = new File(intermediatesDir, "${dirName}/")
                sourceDir.mkdirs()
                def sourceJar = unzipClassesJar(aar, sourceDir)

                classesJarList.addAll(unzipLibs(aar, sourceDir))

                def valuesResult = unzipValuesXml(aar, sourceDir)
                valuesResult.keySet().each {
                    def list = valuesXmlMap[it]
                    if (list == null) {
                        list = new ArrayList<File>()
                    }

                    list.add(valuesResult[it])
                    valuesXmlMap.put(it, list)
                }

                def pkgName = resolvePackageName(aar)
                if (pkgName != mainPkgName) {
                    libraryManifests.add(unzipManifest(aar, sourceDir))

                    def transformDir = new File(intermediatesDir, "${dirName}-transform/")
                    transformDir.mkdirs()
                    def transformJar = transformRClass(sourceJar, transformDir, pkgName, mainPkgName)
                    classesJarList.add(transformJar)
                } else {
                    mainManifest = unzipManifest(aar, sourceDir)
                    classesJarList.add(sourceJar)
                }
            } else if (it.path.endsWith(EXT_JAR)) {
                classesJarList.add(it)
            }
        }

        def rTxt = unzipRTxt(mainAar, intermediatesDir)

        unzipProguardRules(mainAar, intermediatesDir)

        logger.lifecycle("--------------------------------------------")
        logger.lifecycle("Merge AndroidManifest")
        logger.lifecycle("--------------------------------------------")
        def mergedManifest = mergeManifest(mainManifest, libraryManifests)

        logger.lifecycle("--------------------------------------------")
        logger.lifecycle("ImplementProvider transform")
        logger.lifecycle("--------------------------------------------")
        def providerJar = ImplementProviderHelper.findProviderJar(classesJarList, logger)
        if (providerJar != null) {
            def helper = new ImplementProviderHelper(project)
            def transformedProviderJarDir = new File(intermediatesDir, "ImplementProvider")
            transformedProviderJarDir.mkdirs()
            def transformedProviderJar = new File(transformedProviderJarDir, "lib.jar")
            helper.doTransform(providerJar, transformedProviderJar, classesJarList)
            classesJarList.remove(providerJar)
            classesJarList.add(transformedProviderJar)
        } else {
            logger.lifecycle("no service loader dependency")
        }

        logger.lifecycle("--------------------------------------------")
        logger.lifecycle("Merge classes.jar")
        logger.lifecycle("--------------------------------------------")
        def mergedClassesJar = mergeClassesJar(classesJarList)

        logger.lifecycle("--------------------------------------------")
        logger.lifecycle("Merge values.xml")
        logger.lifecycle("--------------------------------------------")
        def mergedValuesXmlMap = new HashMap<String, File>()
        valuesXmlMap.keySet().each {
            mergedValuesXmlMap.put(it, mergeValuesXml(it, valuesXmlMap[it]))
        }

        logger.lifecycle("--------------------------------------------")
        logger.lifecycle("Generate aar")
        logger.lifecycle("--------------------------------------------")
        generateAar(mergedManifest, mergedClassesJar, rTxt, mergedValuesXmlMap, aarList)
    }

    static def unzipClassesJar(File aar, File dstDir) {
        unzipFile(aar, dstDir, CLASSES_JAR_ENTRY)
    }

    static def unzipManifest(File aar, File dstDir) {
        unzipFile(aar, dstDir, ANDROID_MANIFEST_ENTRY)
    }

    static def unzipRTxt(File aar, File dstDir) {
        unzipFile(aar, dstDir, R_TXT_ENTRY)
    }

    static def unzipProguardRules(File aar, File dstDir) {
        try {
            unzipFile(aar, dstDir, PROGUARD_ENTRY)
        } catch (Exception ignored) { // may without proguard
        }
    }

    static def unzipLibs(File aar, File dstDir) {
        def entryNames = new ArrayList<String>()

        new ZipFile(aar).withCloseable {
            def entries = it.entries()
            while (entries.hasMoreElements()) {
                def entry = entries.nextElement()

                def matcher = LIBS_ENTRY_PATTERN.matcher(entry.name)
                if (matcher.matches()) {
                    entryNames.add(entry.name)
                }
            }
        }

        def libsDir = new File(dstDir, "libs")
        libsDir.mkdirs()

        return entryNames.collect {
            unzipFile(aar, dstDir, it)
        }
    }

    static def unzipValuesXml(File aar, File dstDir) {
        def map = new HashMap<String, String>()

        new ZipFile(aar).withCloseable {
            def entries = it.entries()
            while (entries.hasMoreElements()) {
                def entry = entries.nextElement()

                def matcher = VALUES_ENTRY_PATTERN.matcher(entry.name)
                if (matcher.matches()) {
                    map.put(matcher.group(1), matcher.group(0))
                }
            }
        }

        def result = new HashMap<String, File>()

        map.keySet().each {
            def resDir = new File(dstDir, it)
            resDir.mkdirs()
            def entry = map[it]
            result.put(entry, unzipFile(aar, dstDir, entry))
        }

        return result
    }

    static def unzipFile(File aar, File dstDir, String name) {
        new ZipFile(aar).withCloseable {
            def entry = it.getEntry(name)
            if (entry == null) {
                throw new IllegalStateException("${aar.name} no ${name} entry found")
            }

            def dst = new File(dstDir, name)
            dst << it.getInputStream(entry)
            return dst
        }
    }

    static def resolvePackageName(File aar) {
        new ZipFile(aar).withCloseable {
            def entry = it.getEntry(ANDROID_MANIFEST_ENTRY)
            if (entry == null) {
                throw new IllegalStateException("${aar.name} no AndroidManifest.xml entry found")
            }

            def lines = it.getInputStream(entry).readLines("utf-8")
            for (String line : lines) {
                def matcher = MANIFEST_PACKAGE_PATTERN.matcher(line)
                if (matcher.matches()) {
                    return matcher.group(1)
                }
            }

            throw new IllegalStateException("${aar.name} no package name found")
        }
    }

    static def transformRClass(File jar, File dstDir, String oldPkgName, String newPkgName) {
        def newJar = new File(dstDir, CLASSES_JAR_ENTRY)

        new JarOutputStream(new FileOutputStream(newJar)).withCloseable { jarOutput ->
            new JarFile(jar).withCloseable { oldJar ->
                def entries = oldJar.entries()
                while (entries.hasMoreElements()) {
                    def entry = entries.nextElement()

                    if (entry.name.endsWith(EXT_CLASS)) {
                        jarOutput.putNextEntry(new JarEntry(entry.name))

                        new DataInputStream(oldJar.getInputStream(entry)).withCloseable {
                            def classFile = new ClassFile(it)
                            R_CLASSES.each {
                                classFile.renameClass("${oldPkgName}.${it}", "${newPkgName}.${it}")
                            }
                            classFile.write(new DataOutputStream(jarOutput))
                        }
                    } else {
                        jarOutput.putNextEntry(entry)
                        jarOutput << oldJar.getInputStream(entry)
                    }

                    jarOutput.closeEntry()
                }
            }
        }

        return newJar
    }

    def mergeManifest(File mainManifest, List<File> libraryManifests) {
        def reportFile = new File(outputDir, "manifest_report")
        def outputFile = new File(intermediatesDir, ANDROID_MANIFEST_ENTRY)

        //官方提供的合并manifest库
        def merger = ManifestMerger2.newMerger(mainManifest, new NullLogger(), ManifestMerger2.MergeType.APPLICATION)
                .withFeatures(ManifestMerger2.Invoker.Feature.NO_PLACEHOLDER_REPLACEMENT)
        libraryManifests.each {
            merger.addLibraryManifest(it)
        }
        merger.mergeReportFile = reportFile

        def report = merger.merge()
        if (report.result.isSuccess()) {
            outputFile.write(report.getMergedDocument(MergingReport.MergedManifestKind.MERGED))
            return outputFile
        } else {
            logger.warn(report.getReportString())
            throw new IllegalStateException("Merge manifest failed, log@ ${reportFile.absolutePath}")
        }
    }

    def mergeClassesJar(List<File> classesJarList) {
        def newJar = new File(intermediatesDir, CLASSES_JAR_ENTRY)

        new JarOutputStream(new FileOutputStream(newJar)).withCloseable { jarOutput ->
            def entryNameSet = new HashSet<String>()

            classesJarList.each {
                logger.lifecycle(it.absolutePath)

                new JarFile(it).withCloseable { oldJar ->
                    def entries = oldJar.entries()
                    while (entries.hasMoreElements()) {
                        def entry = entries.nextElement()

                        if (entry.name in entryNameSet) {
                            logger.warn("duplicate entry: ${entry.name}")
                            continue
                        }
                        entryNameSet.add(entry.name)

                        jarOutput.putNextEntry(entry)
                        jarOutput << oldJar.getInputStream(entry)
                        jarOutput.closeEntry()
                    }
                }
            }
        }

        return newJar
    }

    @SuppressWarnings("GroovyUncheckedAssignmentOfMemberOfRawType")
    def mergeValuesXml(String entry, List<File> valuesList) {
        def outputFile = new File(intermediatesDir, entry)
        outputFile.parentFile.mkdirs()

        if (valuesList.size() == 1) {
            outputFile << valuesList[0].newInputStream()
        } else {
            logger.lifecycle("Merge ${entry}")

            def resourceItemList = new ArrayList<ResourceItem>()

            valuesList.each {
                logger.lifecycle(it.path)

                def parser = new XmlParser()
                def iterator = parser.parse(it).iterator()
                while (iterator.hasNext()) {
                    Node node = iterator.next()
                    resourceItemList.add(new ResourceItem(
                            node.name().toString(),
                            node.attribute("name"),
                            node.attribute("type"),
                            generateXml(node)
                    ))
                }
            }

            def resourceItemSet = new HashSet<ResourceItem>()

            outputFile << "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            outputFile << "<resources>\n"
            resourceItemList.each {
                if (it in resourceItemSet) {
                    logger.warn("remove duplicate resource ${it.xml}")
                } else {
                    resourceItemSet.add(it)
                    outputFile << it.xml
                }
            }
            outputFile << "</resources>"
        }

        return outputFile
    }

    static def generateXml(Node node, String indent = " " * 4, int count = 1) {
        def builder = new StringBuilder()

        builder << (indent * count) << "<${node.name().toString()}"

        Map attributes = node.attributes()
        boolean hasAttributes = attributes != null && !attributes.isEmpty()
        if (hasAttributes) {
            attributes.keySet().each {
                builder << " ${it.toString()}=\"${attributes[it].toString()}\""
            }
        }

        Object children = node.children()
        if (children.isEmpty()) {
            builder << "/>"
        } else if (children.size() == 1) {
            if (children[0] instanceof Node) {
                builder << ">\n"
                builder << generateXml((Node) children[0], indent, count + 1)
                builder << (indent * count) << "</${node.name().toString()}>"
            } else {
                builder << ">${children[0].toString()}</${node.name().toString()}>"
            }
        } else {
            builder << ">\n"
            children.each {
                if (it instanceof Node) {
                    builder << generateXml(it, indent, count + 1)
                } else {
                    throw new IllegalStateException("${node.name().toString()} unsupport format")
                }
            }
            builder << (indent * count) << "</${node.name().toString()}>"
        }

        builder << "\n"

        return builder.toString()
    }

    def generateAar(File manifestFile, File classesJar, File rTxt, Map<String, File> valuesXmlMap, List<File> aarList) {
        new ZipOutputStream(new FileOutputStream(outputFile)).withCloseable { aarOutput ->
            aarOutput.putNextEntry(new ZipEntry(ANDROID_MANIFEST_ENTRY))
            aarOutput << manifestFile.newInputStream()
            aarOutput.closeEntry()

            aarOutput.putNextEntry(new ZipEntry(CLASSES_JAR_ENTRY))
            aarOutput << classesJar.newInputStream()
            aarOutput.closeEntry()

            aarOutput.putNextEntry(new ZipEntry(R_TXT_ENTRY))
            aarOutput << rTxt.newInputStream()
            aarOutput.closeEntry()

            def entryNameSet = new HashSet<String>()
            entryNameSet.add(ANDROID_MANIFEST_ENTRY)
            entryNameSet.add(CLASSES_JAR_ENTRY)
            entryNameSet.add(R_TXT_ENTRY)
            entryNameSet.add(PROGUARD_ENTRY)

            valuesXmlMap.each { entry, file ->
                entryNameSet.add(entry)

                aarOutput.putNextEntry(new ZipEntry(entry))
                aarOutput << file.newInputStream()
                aarOutput.closeEntry()
            }

            aarList.each {
                new ZipFile(it).withCloseable { oldAar ->
                    def entries = oldAar.entries()
                    while (entries.hasMoreElements()) {
                        def entry = entries.nextElement()

                        if (entry.name in entryNameSet) {
                            logger.warn("duplicate entry: ${it.name}[${entry.name}]")
                            continue
                        }
                        if (LIBS_ENTRY_PATTERN.matcher(entry.name).matches()) {
                            logger.warn("ignored entry: ${it.name}[${entry.name}]")
                            continue
                        }

                        entryNameSet.add(entry.name)

                        aarOutput.putNextEntry(entry)
                        aarOutput << oldAar.getInputStream(entry)
                        aarOutput.closeEntry()
                    }
                }
            }
        }
    }

    static class ResourceItem {

        final String category
        final String name
        final String type
        final String xml

        ResourceItem(String category, String name, String type, String xml) {
            this.category = category
            this.name = name
            this.type = type
            this.xml = xml
        }

        boolean equals(o) {
            if (this.is(o)) return true
            if (getClass() != o.class) return false

            ResourceItem that = (ResourceItem) o

            if (category != that.category) return false
            if (name != that.name) return false
            if (type != that.type) return false
            if (xml != that.xml) {
                throw new IllegalStateException("Conflict resource ${category} ${name} ${type}:\n${xml}\n${that.xml}")
            }

            return true
        }

        int hashCode() {
            int result
            result = category.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + (type != null ? type.hashCode() : 0)
            return result
        }
    }
}
