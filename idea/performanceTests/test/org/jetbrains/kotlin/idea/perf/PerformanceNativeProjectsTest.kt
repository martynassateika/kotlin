/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.perf

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.gradle.KotlinSourceSet.Companion.COMMON_TEST_SOURCE_SET_NAME
import org.jetbrains.kotlin.ide.konan.NativeLibraryKind
import org.jetbrains.kotlin.idea.caches.project.isMPPModule
import org.jetbrains.kotlin.idea.configuration.klib.KotlinNativeLibraryNameUtil.parseIDELibraryName
import org.jetbrains.kotlin.idea.facet.KotlinFacet
import org.jetbrains.kotlin.idea.framework.CommonLibraryKind
import org.jetbrains.kotlin.idea.framework.detectLibraryKind
import org.jetbrains.kotlin.idea.perf.PerformanceNativeProjectsTest.TestProject.*
import org.jetbrains.kotlin.idea.perf.PerformanceNativeProjectsTest.TestTarget.IOS
import org.jetbrains.kotlin.idea.perf.Stats.Companion.WARM_UP
import org.jetbrains.kotlin.idea.perf.Stats.Companion.tcSuite
import org.jetbrains.kotlin.idea.testFramework.ProjectOpenAction.GRADLE_PROJECT
import org.jetbrains.kotlin.idea.testFramework.logMessage
import org.jetbrains.kotlin.idea.testFramework.suggestOsNeutralFileName
import org.jetbrains.kotlin.idea.util.projectStructure.allModules
import org.jetbrains.kotlin.library.KOTLIN_STDLIB_NAME
import org.jetbrains.kotlin.platform.konan.isNative
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import java.io.File

@Ignore(value = "[VD] disabled temporary for further investigation: it fails on TC agents")
class PerformanceNativeProjectsTest : AbstractPerformanceProjectsTest() {

    companion object {
        private const val TEST_DATA_PATH = "idea/testData/perfTest/native"

        private var warmedUp: Boolean = false
    }

    private enum class TestTarget {
        IOS;

        val alias get() = name.toLowerCase()
        val enabled get() = true
    }

    private enum class TestProject(
        val templateName: String,
        val filesToHighlight: List<String>
    ) {
        HELLO_WORLD(
            templateName = "HelloWorld",
            filesToHighlight = listOf(
                "src/main/HelloMain.kt", "src/main/HelloMain2.kt", "src/main/HelloMain3.kt",
                "src/test/HelloTest.kt", "src/test/HelloTest2.kt", "src/test/HelloTest3.kt"
            )
        ),
        CSV_PARSER(
            templateName = "CsvParser",
            filesToHighlight = listOf("src/main/CsvParser.kt", "src/main/CsvParser2.kt", "src/main/CsvParser3.kt")
        ),
        UI_KIT_APP(
            templateName = "UIKitApp",
            filesToHighlight = listOf("src/main/UIKitApp.kt", "src/main/UIKitApp2.kt", "src/main/UIKitApp3.kt")
        );

        init {
            assertTrue(filesToHighlight.isNotEmpty())
        }
    }

    override fun setUp() {
        super.setUp()

        // warm up: open simple small project
        if (!warmedUp) {
            val testProject = HELLO_WORLD
            val enableCommonizer = true

            TestTarget.values().forEach { testTarget ->
                if (!testTarget.enabled) {
                    logMessage { "Warm-up for test target $testTarget is disabled" }
                    return@forEach
                }

                val projectName = "${projectName(testTarget, testProject, enableCommonizer)} $WARM_UP"

                // don't share this stats instance with another one used in "Hello World" test
                Stats(projectName).use { stats ->
                    warmUpProject(stats, testProject.filesToHighlight.first()) {
                        perfOpenTemplateGradleProject(stats, testTarget, testProject, enableCommonizer, WARM_UP)
                    }
                }
            }

            warmedUp = true
        }
    }

    fun testIosHelloWorldProjectWithCommonizer() = doTestHighlighting(IOS, HELLO_WORLD, enableCommonizer = true)
    fun testIosHelloWorldProjectWithoutCommonizer() = doTestHighlighting(IOS, HELLO_WORLD, enableCommonizer = false)

    fun testIosCvsParserProjectWithCommonizer() = doTestHighlighting(IOS, CSV_PARSER, enableCommonizer = true)
    fun testIosCvsParserProjectWithoutCommonizer() = doTestHighlighting(IOS, CSV_PARSER, enableCommonizer = false)

    fun testIosUIKitAppProjectWithCommonizer() = doTestHighlighting(IOS, UI_KIT_APP, enableCommonizer = true)
    fun testIosUIKitAppProjectWithoutCommonizer() = doTestHighlighting(IOS, UI_KIT_APP, enableCommonizer = false)

    private fun doTestHighlighting(
        testTarget: TestTarget,
        testProject: TestProject,
        enableCommonizer: Boolean
    ) {
        assumeTrue(testTarget.enabled)

        val projectName = projectName(testTarget, testProject, enableCommonizer)
        tcSuite(projectName) {
            Stats(projectName).use { stats ->
                myProject = perfOpenTemplateGradleProject(stats, testTarget, testProject, enableCommonizer)

                // highlight
                testProject.filesToHighlight.forEach { perfHighlightFile(it, stats) }
            }
        }
    }

    private fun perfOpenTemplateGradleProject(
        stats: Stats,
        testTarget: TestTarget,
        testProject: TestProject,
        enableCommonizer: Boolean,
        note: String = ""
    ): Project {
        val nativeTestsRoot = File(TEST_DATA_PATH)

        val commonRoot = nativeTestsRoot.resolve("_common")
        val targetRoot = nativeTestsRoot.resolve("_${testTarget.alias}")
        val templateRoot = nativeTestsRoot.resolve(testProject.templateName)

        val projectRoot = FileUtil.createTempDirectory("project", "", false)

        commonRoot.walkTopDown()
            .onEnter { !it.name.startsWith('.') } // exclude any directory starting with dot '.'
            .filter { it.isFile }
            .forEach { sourceFile ->
                val destinationFileName = with(sourceFile.name) {
                    when {
                        // choose the right variant based on whether commonizer is enabled or not
                        endsWith(".with-commonizer") -> if (!enableCommonizer) return@forEach else removeSuffix(".with-commonizer")
                        endsWith(".without-commonizer") -> if (enableCommonizer) return@forEach else removeSuffix(".without-commonizer")
                        else -> this
                    }
                }

                val destinationFile = projectRoot.resolve(sourceFile.resolveSibling(destinationFileName).relativeTo(commonRoot))
                sourceFile.copyTo(destinationFile)
            }

        targetRoot.copyRecursively(projectRoot)
        templateRoot.copyRecursively(projectRoot)

        // merge all files with ".header", ".middle", ".footer" suffixes
        projectRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".header") }
            .forEach { headerFile ->
                // locate middle and footer files
                val destinationFileName = headerFile.name.removeSuffix(".header")

                val middleFile = headerFile.resolveSibling("$destinationFileName.middle").also(::assertExists)
                val footerFile = headerFile.resolveSibling("$destinationFileName.footer").also(::assertExists)

                val destinationFile = headerFile.resolveSibling(destinationFileName).also(::assertDoesntExist)
                destinationFile.writeText(headerFile.readText() + middleFile.readText() + footerFile.readText())

                headerFile.delete()
                middleFile.delete()
                footerFile.delete()
            }

        // check no unmerged ".middle" and ".footer" files left
        projectRoot.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".middle") || it.name.endsWith(".footer")) }
            .map { it.relativeTo(projectRoot).path }
            .toList()
            .sorted()
            .takeIf { it.isNotEmpty() }
            ?.let { unmergedFiles ->
                fail("Some files have not been merged in project root directory: $projectRoot: ${unmergedFiles.joinToString()}")
            }

        // check all necessary files are there
        listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties")
            .filter { !projectRoot.resolve(it).exists() }
            .takeIf { it.isNotEmpty() }
            ?.let { missedFiles ->
                fail("Some important files are missed in project root directory $projectRoot: ${missedFiles.joinToString()}")
            }

        val projectName = projectName(testTarget, testProject, enableCommonizer, fileSystemFriendlyName = true)

        val project = perfOpenProject(
            name = projectName,
            stats = stats,
            note = note,
            path = projectRoot.absolutePath,
            openAction = GRADLE_PROJECT
        )
        runProjectSanityChecks(project)

        return project
    }

    // goal: make sure that the project imported from Gradle is valid
    private fun runProjectSanityChecks(project: Project) {

        val nativeModules: Map<Module, Set<String>> = runReadAction {
            project.allModules().mapNotNull { module ->
                val facetSettings = KotlinFacet.get(module)?.configuration?.settings ?: return@mapNotNull null
                if (!facetSettings.isMPPModule || !facetSettings.targetPlatform.isNative()) return@mapNotNull null

                // workaround to skip top-level common test module, which in fact does not get any Kotlin/Native KLIB libraries
                // but accidentally gets Kotlin facet with Native platform
                if (module.name == "${project.name}.$COMMON_TEST_SOURCE_SET_NAME") return@mapNotNull null

                val nativeLibraries = module.rootManager.orderEntries
                    .asSequence()
                    .filterIsInstance<LibraryOrderEntry>()
                    .mapNotNull { it.library }
                    .filter { library ->
                        val libraryKind = detectLibraryKind(library.getFiles(OrderRootType.CLASSES))
                        libraryKind == NativeLibraryKind
                                // TODO: remove this check for CommonLibraryKind when detection of K/N KLIBs in
                                //  org.jetbrains.kotlin.ide.konan.KotlinNativePluginUtilKt.isKonanLibraryRoot
                                //  is correctly implemented
                                || libraryKind == CommonLibraryKind
                    }
                    .mapNotNull inner@{ library ->
                        val libraryNameParts = parseIDELibraryName(library.name.orEmpty()) ?: return@inner null
                        val (_, pureLibraryName, platformPart) = libraryNameParts
                        pureLibraryName + if (platformPart != null) " [$platformPart]" else ""
                    }
                    .toSet()

                module to nativeLibraries
            }.toMap()
        }

        assertTrue("No Native modules found in project $project", nativeModules.isNotEmpty())

        nativeModules.forEach { (module, nativeLibraries) ->
            assertTrue(
                "$KOTLIN_STDLIB_NAME not found for for Native module $module in project $project",
                KOTLIN_STDLIB_NAME in nativeLibraries
            )

            assertTrue(
                "No Native libraries except for $KOTLIN_STDLIB_NAME for Native module $module in project $project",
                (nativeLibraries - KOTLIN_STDLIB_NAME).isNotEmpty()
            )

            logMessage { "Native $module has ${nativeLibraries.size} native libraries" }
        }
    }

    private fun projectName(
        testTarget: TestTarget,
        testProject: TestProject,
        enableCommonizer: Boolean,
        fileSystemFriendlyName: Boolean = false
    ): String {
        val name = "${testProject.templateName} ($testTarget) ${if (enableCommonizer) "with" else "without"} commonizer"
        return if (fileSystemFriendlyName) suggestOsNeutralFileName(name) else name
    }
}
