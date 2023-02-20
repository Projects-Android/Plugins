package ev.plugin.fataar.utils

class DependencyParser {

    private static final String MARK_START = "+--- "
    private static final String MARK_END = "\\--- "

    private static final LITERAL_PROJECT = "project"

    private final File file
    private final Set<String> include

    DependencyParser(File file, Set<String> include) {
        this.file = file
        this.include = include
    }

    Set<String> parse() {
        Set<String> set = new HashSet<>()
        new FileInputStream(file).withCloseable {
            List<String> lines = it.readLines()
            int start = lines.findIndexOf {
                it.startsWith(MARK_START) || it.startsWith(MARK_END)
            }
            int end = lines.findLastIndexOf {
                it.trim().startsWith(MARK_END)
            }
            parseNode(set, include, lines.subList(start, end + 1))
        }
        return set
    }

    private static void parseNode(Set<String> set, Set<String> include, List<String> lines) {
        int index = 0
        for (int size = lines.size(); index < size;) {
            String line = lines.get(index)
            if (line.startsWith(MARK_START) || line.startsWith(MARK_END)) {
                line = line.substring(MARK_START.length())
                if (line.startsWith(LITERAL_PROJECT) || contain(include, line)) {
                    List<String> projectLines = getProjectLines(lines, index + 1)
                    if (!projectLines.isEmpty()) {
                        parseNode(set, include, projectLines)
                    }
                    index += (projectLines.size() + 1)
                } else {
                    set.add(parseName(line))
                    index++
                }
            } else {
                index++
            }
        }
    }

    private static List<String> getProjectLines(List<String> lines, int index) {
        List<String> projectLines = new ArrayList<>()
        for (int size = lines.size(); index < size; index++) {
            String line = lines.get(index)
            if (line.startsWith(MARK_START) || line.startsWith(MARK_END)) {
                break
            } else {
                projectLines.add(line.substring(MARK_START.length()))
            }
        }
        return projectLines
    }

    private static String parseName(String text) {
        def splits = text.trim().split(":")
        def versionSplits = splits[2].replaceAll("\\(\\*\\)", "").trim().split("->")
        def version = versionSplits.length == 1 ? versionSplits[0] : versionSplits[1].trim()
        return String.join(":", splits[0], splits[1], version)
    }

    private static boolean contain(Set<String> set, String value) {
        if (set == null || set.isEmpty()) return false

        for (String e : set) {
            if (value.startsWith(e)) return true
        }
        return false
    }
}
