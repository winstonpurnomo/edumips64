/*
 * EduMIPS64 Gradle build configuration
 */
import java.time.LocalDateTime
import ru.vyarus.gradle.plugin.python.task.PythonTask
import ru.vyarus.gradle.plugin.python.PythonExtension.Scope.VIRTUALENV

plugins {
    java
    // The Eclipse plugin adds the "eclipse" task, which generates
    // files needed for Visual Studio Code and other IDEs.
    id ("eclipse")
    id ("application")
    id ("jacoco")
    id ("com.dorongold.task-tree") version "2.1.0"
    id ("us.ascendtech.gwt.classic") version "0.9.2"
    id ("ru.vyarus.use-python") version "2.3.0"
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("com.google.gwt:gwt-user:2.9.0")
    compileOnly("com.google.gwt:gwt-dev:2.9.0")
    compileOnly("com.google.elemental2:elemental2-dom:1.1.0")
    compileOnly("com.vertispan.rpc:workers:1.0-alpha-6")

    implementation("javax.help:javahelp:2.0.05")
    implementation("info.picocli:picocli:4.6.3")

    testImplementation(platform("org.junit:junit-bom:5.8.2"))
	testImplementation("org.junit.jupiter:junit-jupiter")

    // To run JUnit 4 tests.
    testImplementation("junit:junit:4.13.2")    
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

python {
    pip("sphinx:4.5.0")
    pip("rst2pdf:0.99")
    scope = VIRTUALENV
}

application {
  mainClass.set("org.edumips64.Main")
}
val codename: String by project
val version: String by project

// Specify Java source/target version.
tasks.compileJava {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

/* 
 * Documentation tasks. To avoid dependency on GNU Make, these tasks duplicate the commands run by the Sphinx makefiles.
 */
fun buildDocsCmd(language: String, type: String) : String {
    val baseDir = "${buildDir}/docs/${language}"
    return "-m sphinx -N -a -E . ${baseDir}/${type} -b ${type} -d ${baseDir}/doctrees"
}

tasks.register<PythonTask>("htmlDocsEn") {
    workDir = "${projectDir}/docs/user/en/src"
    command = buildDocsCmd("en", "html")
}

tasks.register<PythonTask>("htmlDocsIt") {
    workDir = "${projectDir}/docs/user/en/src"
    command = buildDocsCmd("it", "html")
}

tasks.register<PythonTask>("pdfDocsEn") {
    workDir = "${projectDir}/docs/user/en/src"
    command = buildDocsCmd("en", "pdf")
}

tasks.register<PythonTask>("pdfDocsIt") {
    workDir = "${projectDir}/docs/user/it/src"
    command = buildDocsCmd("it", "pdf")
}


// Catch-all task for documentation
tasks.create<GradleBuild>("allDocs") {
    tasks = listOf("htmlDocsIt", "htmlDocsEn", "pdfDocsEn", "pdfDocsIt")
    description = "Run all documentation tasks"
}

/*
 * Jar tasks
 */
val docsDir = "build/classes/java/main/docs"
// Include the docs folder at the root of the jar, for JavaHelp
tasks.create<Copy>("copyHelpEn") {
    from("${buildDir}/docs/en") {
        include("html/**")
        exclude("**/_sources/**")
    }
    into ("${docsDir}/user/en")
    dependsOn("htmlDocsEn")
}

tasks.create<Copy>("copyHelpIt") {
    from("${buildDir}/docs/it") {
        include("html/**")
        exclude("**/_sources/**")
    }
    into ("${docsDir}/user/it")
    dependsOn("htmlDocsIt")
}

tasks.create<Copy>("copyHelp") {
    from("docs/") {
        exclude("**/src/**", "**/design/**", "**/*.py",  "**/*.pyc", 
            "**/*.md", "**/.buildinfo", "**/objects.inv", "**/*.txt", "**/__pycache__/**")
    }
    into ("${docsDir}")
    dependsOn("copyHelpEn")
    dependsOn("copyHelpIt")
}

/*
 * Helper function to execute a command and return its output.
 */
fun String.runCommand(workingDir: File = file("./")): String {
    val parts = this.split("\\s".toRegex())
    val proc = ProcessBuilder(*parts.toTypedArray())
            .directory(workingDir)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

    proc.waitFor(1, TimeUnit.MINUTES)
    return proc.inputStream.bufferedReader().readText().trim()
}

fun getSourceControlMetadata() : Triple<String, String, String> {
    val branch: String
    val commitHash: String
    val qualifier: String
    if(System.getenv("GITHUB_ACTIONS").isNullOrEmpty()) {
        println("Running locally")
        branch = "git rev-parse --abbrev-ref HEAD".runCommand()
        commitHash = "git rev-parse --verify --short HEAD".runCommand()
        qualifier = ""
    } else {
        println("Running under GitHub Actions")
        branch = System.getenv("GITHUB_REF")
        commitHash = System.getenv("GITHUB_SHA").substring(0, 7)
        qualifier = "alpha"
    }
    return Triple(branch, commitHash, qualifier)
}

val sharedManifest = the<JavaPluginConvention>().manifest {
    attributes["Signature-Version"] = version
    attributes["Codename"] = codename
    attributes["Build-Date"] = LocalDateTime.now()

    val (branch, gitRevision, qualifier) = getSourceControlMetadata()
    attributes["Full-Buildstring"] = "$branch@$gitRevision"
    attributes["Git-Revision"] = gitRevision
    attributes["Build-Qualifier"] = qualifier
}

// Main JAR
tasks.jar {
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().filter { (it.name.contains("picocli") || it.name.contains("javahelp")) && it.name.endsWith("jar") }.map {  println("Adding dependency " + it.name); zipTree(it) }

    })
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        from(sharedManifest)
    }
    dependsOn("copyHelp")
}

tasks.assemble{
    dependsOn("jar") 
}

// NoHelp JAR
tasks.create<Jar>("noHelpJar"){
    classifier = "nohelp"
    dependsOn(configurations.runtimeClasspath)
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().filter { it.name.contains("picocli") && it.name.endsWith("jar") }.map { println("Adding dependency " + it.name); zipTree(it) }
    })
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        from(sharedManifest)
    }
}

/*
 * Code coverage report tasks
 */
jacoco {
    toolVersion = "0.8.7"
}
tasks.jacocoTestReport {
    reports {
        xml.isEnabled = true
        csv.isEnabled = false
        html.isEnabled = false
    }
}

// Add logging of all executed tests.
tasks {
    withType<Test> {
        useJUnitPlatform()
        testLogging.events = setOf(
            org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
        )
    }
}

tasks.check{
    dependsOn("jacocoTestReport")
}

tasks.register("release") {
    group = "Release"
    description = "Creates all artifacts for a given EduMIPS64 release"
    dependsOn("allDocs")
    dependsOn("jar")
    dependsOn("msi")

    doFirst {
        println("Creating artifacts for version $version.")
    }
}

tasks.create<Exec>("msi"){
    group = "Distribution"
    description = "Creates an installable MSI file"
    workingDir = File("${projectDir}")
    dependsOn("jar")

    doFirst{
        var os = System.getProperty("os.name") as String;
        if (!("Windows" in os)){
            throw GradleException("MSI creation must be executed on Windows")
        }
        var majorVersion = System.getProperty("java.version").split(".")[0].toInt()
        
        if (majorVersion < 14) {
            throw GradleException("JDK 14+ is required to create the MSI package.")
        }
        
        if (System.getenv("WIX") == null){
            throw GradleException("Wix is required to create the MSI package.")
        }

        println("Creating EduMIPS64-${version}.msi.");
        val cmd = "jpackage.exe --main-jar edumips64-${version}.jar --input ./build/libs/ --app-version ${version} --name EduMIPS64 --description \"Educational MIPS64 CPU Simulator\" --vendor \"EduMIPS64 Development Team\" --copyright \"Copyright ${LocalDateTime.now().year}, EduMIPS64 development Team\" --license-file ./LICENSE --win-shortcut --win-dir-chooser --win-menu --type msi --icon ./src/main/resources/images/ico.ico --win-per-user-install"
        commandLine(cmd.split(" "));
    }
}

/*
 * GWT tasks
 */
gwt {
    modules.add("org.edumips64.webclient")
    sourceLevel = "1.11"
}
