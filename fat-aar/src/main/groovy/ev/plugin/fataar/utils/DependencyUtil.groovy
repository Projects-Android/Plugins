package ev.plugin.fataar.utils

import com.android.build.api.attributes.VariantAttr
import org.gradle.api.Project
import org.gradle.api.artifacts.ArtifactView
import org.gradle.api.artifacts.result.ResolvedComponentResult

class DependencyUtil {

    private static final LITERAL_PROJECT = "project"

    private DependencyUtil() {}

    static ArtifactView resolveArtifactView(Project project) {
        project.configurations.releaseRuntimeClasspath.incoming.artifactView {
            it.attributes.attribute(VariantAttr.ATTRIBUTE, new ReleaseVariantAttr())
        }
    }

    static Set<Project> resolveSubProjects(Project project, boolean includeSelf = true) {
        def projectSet = new HashSet<Project>()
        project.configurations.releaseRuntimeClasspath.incoming.resolutionResult.allComponents { ResolvedComponentResult result ->
            def name = result.id.displayName
            if (name.startsWith(LITERAL_PROJECT)) {
                name = name.substring(LITERAL_PROJECT.length()).trim()
                def subProject = project.findProject(name)
                if (subProject != null) {
                    projectSet.add(subProject)
                }
            }
        }
        if (includeSelf) {
            projectSet.add(project)
        } else {
            projectSet.remove(project)
        }
        return projectSet
    }
}
