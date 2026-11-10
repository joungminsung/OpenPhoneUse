package com.phoneuse.ui.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One bundled library as the AboutLibraries Gradle plugin lists it. */
data class LicensedLibrary(
    val name: String,
    val version: String,
    /** Who wrote it, or the publisher; empty when the POM names nobody. */
    val author: String,
    /** Keys into [LicenseNotices.texts]. */
    val licenseIds: List<String>,
)

/** A licence the bundled libraries use; [text] is empty for licences only linked, not shipped. */
data class LicenseText(val id: String, val name: String, val url: String, val text: String)

data class LicenseNotices(val libraries: List<LicensedLibrary>, val texts: List<LicenseText>)

/**
 * Reads `aboutlibraries.json`, which the AboutLibraries plugin writes into res/raw from the release dependencies
 * on every build (app/build.gradle.kts), so the list follows libs.versions.toml without hand edits.
 */
object Licenses {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): LicenseNotices {
        val file = json.decodeFromString<File>(raw)
        val libraries = file.libraries.map { lib ->
            // A POM without a name falls back to the artifact id, as in "play-services-base".
            LicensedLibrary(
                name = lib.name.ifBlank { lib.uniqueId.substringAfter(':') },
                version = lib.artifactVersion.orEmpty(),
                author = lib.developers.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) } ?: lib.organization?.name.orEmpty(),
                licenseIds = lib.licenses,
            )
        }.sortedBy { it.name.lowercase() }
        val used = libraries.flatMap { it.licenseIds }.toSet()
        val texts = file.licenses.filterKeys { it in used }.map { (id, l) ->
            LicenseText(id, l.name.ifBlank { id }, l.url.orEmpty(), l.content.orEmpty().trim())
        }.sortedBy { it.name.lowercase() }
        return LicenseNotices(libraries, texts)
    }

    @Serializable
    private class File(val libraries: List<Library> = emptyList(), val licenses: Map<String, License> = emptyMap())

    @Serializable
    private class Library(
        val uniqueId: String,
        val name: String = "",
        val artifactVersion: String? = null,
        val developers: List<Person> = emptyList(),
        val organization: Person? = null,
        val licenses: List<String> = emptyList(),
    )

    @Serializable
    private class Person(val name: String? = null)

    @Serializable
    private class License(val name: String = "", val url: String? = null, val content: String? = null)
}
