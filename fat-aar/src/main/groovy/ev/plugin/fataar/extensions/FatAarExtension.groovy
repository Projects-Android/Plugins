package ev.plugin.fataar.extensions

class FatAarExtension {

    String group
    String name
    String version

    Set<String> include
    Set<String> proguardRules

    def group(String group) {
        this.group = group
    }

    def name(String name) {
        this.name = name
    }

    def version(String version) {
        this.version = version
    }

    def isValid() {
        if (isBlank(group) || isBlank(name) || isBlank(version)) return false
        return true
    }

    private static def isBlank(String text) {
        return text == null || text.trim().length() == 0
    }
}
