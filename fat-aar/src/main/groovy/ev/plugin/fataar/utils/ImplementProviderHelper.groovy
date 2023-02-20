package ev.plugin.fataar.utils

import javassist.*
import org.gradle.api.Project
import org.gradle.api.logging.Logger

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class ImplementProviderHelper {

    private static final String REAL_IMPLEMENT_PROVIDER_NAME = "jd.jszt.auto.service.provider.RealImplementProvider"
    private static final String REAL_IMPLEMENT_PROVIDER_ENTRY = "jd/jszt/auto/service/provider/RealImplementProvider.class"

    private final Project project
    private final Logger logger

    ImplementProviderHelper(Project project) {
        this.project = project
        this.logger = project.logger
    }

    def doTransform(File srcJar, File dstJar, List<File> classPathList) {
        def classPool = new ClassPool()
        classPool.appendSystemPath()

        classPathList.each {
            classPool.appendClassPath(it.absolutePath)
        }

        def providerList = new ArrayList()

        project.parent.subprojects.each {
            def name = "jd.jszt.auto.service.provider.${it.name}ImplementProvider"
            try {
                logger.lifecycle(it.name)
                logger.lifecycle("--------------------------------------------")

                CtClass ctClass = classPool.get(name)
                ctClass.detach()
                providerList.add(name)

                logger.lifecycle("$name is found\n")
            } catch (NotFoundException ignored) {
                logger.lifecycle("$name not exist\n")
            }
        }

        CtClass providerClz = classPool.get(REAL_IMPLEMENT_PROVIDER_NAME)

        CtField mapField = new CtField(classPool.get(Map.class.getName()), "map", providerClz)
        mapField.modifiers = Modifier.PRIVATE | Modifier.FINAL
        providerClz.addField(mapField, "new java.util.HashMap()")

        def doWorkAround = shouldWorkAround()
        if (doWorkAround) {
            logger.lifecycle("--------------------------------------------")
            logger.lifecycle("本次编译代码包含未经优化的代码，不应用于生产发布")
        }

        CtConstructor constructor = providerClz.getDeclaredConstructor(new CtClass[0])
        def body = "{\n"
        providerList.each {
            if (doWorkAround) {
                body += ("try{\n"
                        + "java.lang.Class clz = java.lang.Class.forName(\"${it}\");"
                        + "java.lang.Object obj = clz.getDeclaredMethod(\"get\", new java.lang.Class[0]).invoke(clz.newInstance(), new java.lang.Object[0]);"
                        + "map.putAll((java.util.Map)obj);\n"
                        + "} catch(java.lang.Exception ignored) {}\n")
            } else {
                body += "map.putAll(new ${it}().get());\n"
            }
        }
        body += "}\n"
        constructor.body = body

        providerClz.getDeclaredMethod("get").body = "return map;\n"

        new JarOutputStream(new FileOutputStream(dstJar)).withCloseable { jarOutput ->
            jarOutput.putNextEntry(new JarEntry(REAL_IMPLEMENT_PROVIDER_ENTRY))
            jarOutput << providerClz.toBytecode()
            jarOutput.closeEntry()

            new JarFile(srcJar).withCloseable { sourceJar ->
                def entries = sourceJar.entries()
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement()
                    if (entry.name == REAL_IMPLEMENT_PROVIDER_ENTRY) continue

                    jarOutput.putNextEntry(entry)
                    jarOutput << sourceJar.getInputStream(entry)
                    jarOutput.closeEntry()
                }
            }
        }

        providerClz.detach()

        logger.lifecycle("Overwrite: $dstJar\n")
    }

    private boolean shouldWorkAround() {
        def props = new Properties()

        try {
            new File(project.rootProject.projectDir, "local.properties").withInputStream {
                props.load(it)
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace()
        }

        return props.getProperty("implement.provider.workAround", "false").toBoolean()
    }

    static File findProviderJar(List<File> fileList, Logger logger) {
        for (File file : fileList) {
            logger.lifecycle(file.absolutePath)
            if (isRealImplementProviderExist(file)) {
                return file
            }
        }
        return null
    }

    static boolean isRealImplementProviderExist(File file) {
        new JarFile(file).withCloseable {
            return it.getEntry(REAL_IMPLEMENT_PROVIDER_ENTRY) != null
        }
    }
}
