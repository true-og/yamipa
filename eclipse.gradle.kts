/* This is free and unencumbered software released into the public domain */

import org.gradle.plugins.ide.eclipse.model.EclipseModel
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

val kotlinAttribute: Attribute<Boolean> by rootProject.extra

val kotlinPluginProjects = configurations
.getByName("compileClasspath")
.allDependencies
    .filterIsInstance<ProjectDependency>()
    .filter { it -> it.attributes.getAttribute(kotlinAttribute) != null }
    .map { it.path }
kotlinPluginProjects.forEach { evaluationDependsOn(it) }

val ideLibs: Configuration by
configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

configure<EclipseModel> {
    project {
        name = "Template-OG-Plugin"
    }
    classpath {
        plusConfigurations.add(ideLibs)
    }
}

val ideLibDir = layout.buildDirectory.dir("ide-libs")
val hashRegex = Regex("-[0-9a-fA-F]{10}(?=\\.jar$)") // Black magic.

val copyTasks = mutableListOf<TaskProvider<Copy>>()

kotlinPluginProjects.forEach { path ->
    val sub = project(path)
    val shadowJarProv = sub.tasks.named("shadowJar")
    val copyTask =
        tasks.register<Copy>("ideCopy${sub.name.replaceFirstChar(Char::titlecase)}") {
            dependsOn(shadowJarProv)
            from(shadowJarProv)
            into(ideLibDir)
            rename { it.replace(hashRegex, "") } // Remove git commit hash from jarfile.
        }
    copyTasks += copyTask
}

/* Ensure the jars exist before .classpath is generated */
tasks.named("eclipseClasspath").configure { dependsOn(copyTasks) }

/* Supply those jars to the ideLibs configuration (after evaluation) */
afterEvaluate { dependencies { add("ideLibs", fileTree(ideLibDir) { include("*.jar") }) } }

val injectIdeLibs =
    tasks.register("injectIdeLibs") {
        dependsOn("eclipse") // run after all eclipse files are generated
        doLast {
            val cpFile = file(".classpath")
            if (!cpFile.exists()) return@doLast

            val jars = fileTree(ideLibDir.get()) { include("*.jar") }.files.sortedBy { it.name }
            if (jars.isEmpty()) return@doLast

            // Parse DOM
            val dbf = DocumentBuilderFactory.newInstance()
            val doc = dbf.newDocumentBuilder().parse(cpFile)
            val root = doc.documentElement

            // Helper to see if an entry already exists
            fun exists(path: String): Boolean =
                root.getElementsByTagName("classpathentry").let { list ->
                    (0 until list.length).any { i ->
                        val n = list.item(i)
                        val kind = n.attributes?.getNamedItem("kind")?.nodeValue
                        val p = n.attributes?.getNamedItem("path")?.nodeValue
                        kind == "lib" && p == path
                    }
                }

            // Remove any entry that points to ide-libs dir (folder or jars) to avoid dupes
            val toRemove = mutableListOf<org.w3c.dom.Node>()
            val list = root.getElementsByTagName("classpathentry")
            val dirPath = ideLibDir.get().asFile.absolutePath
            for (i in 0 until list.length) {
                val n = list.item(i)
                val kind = n.attributes?.getNamedItem("kind")?.nodeValue
                val p = n.attributes?.getNamedItem("path")?.nodeValue ?: ""
                if (kind == "lib" && (p == dirPath || p.startsWith("$dirPath/"))) {
                    toRemove += n
                }
            }
            toRemove.forEach { root.removeChild(it) }

            // Append our jar entries LAST
            jars.forEach { f ->
                val abs = f.absolutePath
                if (!exists(abs)) {
                    val entry = doc.createElement("classpathentry")
                    entry.setAttribute("kind", "lib")
                    entry.setAttribute("path", abs)
                    root.appendChild(entry)
                }
            }

            // Write back pretty
            val tf =
                TransformerFactory.newInstance().newTransformer().apply {
                    setOutputProperty(OutputKeys.INDENT, "yes")
                    setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "1")
                    setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
                    setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                }
            Files.newBufferedWriter(cpFile.toPath()).use { w -> tf.transform(DOMSource(doc), StreamResult(w)) }
        }
    }

tasks.named("eclipse").configure { finalizedBy(injectIdeLibs) }

val eclipseModel = extensions.getByType<EclipseModel>()

fun Project.addResolvableEclipseConfigs() {
    val jarAttr =
        objects.named(org.gradle.api.attributes.LibraryElements::class, org.gradle.api.attributes.LibraryElements.JAR)
    val apiAttr = objects.named(org.gradle.api.attributes.Usage::class, org.gradle.api.attributes.Usage.JAVA_API)

    val compileOnlyRes =
        configurations.create("eclipseCompileOnly") {
            extendsFrom(configurations.getByName("compileOnly"))
            isCanBeResolved = true
            isCanBeConsumed = false
            attributes.attribute(org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE, apiAttr)
            attributes.attribute(org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, jarAttr)
        }
    val compileOnlyApiRes =
        configurations.create("eclipseCompileOnlyApi") {
            extendsFrom(configurations.getByName("compileOnlyApi"))
            isCanBeResolved = true
            isCanBeConsumed = false
            attributes.attribute(org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE, apiAttr)
            attributes.attribute(org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, jarAttr)
        }
    eclipseModel.classpath.plusConfigurations.addAll(listOf(compileOnlyRes, compileOnlyApiRes))
}

addResolvableEclipseConfigs()
