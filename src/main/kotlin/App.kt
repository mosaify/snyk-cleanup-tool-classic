package dev.mosaify.client.snyk.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.italic
import com.github.ajalt.mordant.table.Borders
import com.github.ajalt.mordant.table.ColumnWidth
import com.github.ajalt.mordant.table.grid
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.YesNoPrompt
import dev.mosaify.client.snyk.Extensions.isSupportedByJiraApi
import dev.mosaify.client.snyk.SnykClient
import dev.mosaify.client.snyk.model.response.OrgInfo
import dev.mosaify.client.snyk.model.response.ProjectInfo
import dev.mosaify.client.snyk.model.response.TargetInfo
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

fun main(args: Array<String>) = ClientSnykCli().main(args)

private fun Instant.toLocalDate(): LocalDate = toJavaInstant().atZone(ZoneId.systemDefault()).toLocalDate()

class ClientSnykCli : CliktCommand(name = About.NAME) {

    private val token by option()
    private val org by argument().multiple()
    private val empty by option().flag()
    private val inactive by option().flag()
    private val before by option()
    private val filter by option().multiple()
    private val dryRun by option().flag()

    override fun run() = runBlocking {

        val utils = CleanupUtils(terminal)
        utils.init(token)
        val orgsAll = utils.getAllOrgs()

        if (org.isNotEmpty()) {
            val orgsFiltered = orgsAll.filter { a -> org.any { a.matches(it) } }
            val beforeDate = before?.let { before ->
                runCatching {
                    LocalDate.parse(before)
                }.getOrElse {
                    terminal.println((bold + brightRed)("ERROR: Could not parse \"$before\" as a local date."))
                    val currentDate = LocalDate.now()
                    terminal.println("Date format example: $currentDate")
                    exitProcess(-1)
                }
            }
            utils.cleanupProjects(orgsFiltered, inactive, beforeDate, filter, dryRun = dryRun)
            if (empty) utils.cleanupTargets(orgsFiltered)
        } else {
            utils.displayOrgs(orgsAll)
            terminal.println((brightYellow)("Pick an org(s) to work with"))
            terminal.println((brightRed)("TODO: Help here"))
        }
    }
}

fun OrgInfo.matches(match: String): Boolean =
    match.contentEquals(this.id, ignoreCase = true) ||
            match.contentEquals(this.attributes.slug, ignoreCase = true)

class CleanupUtils(
    private val terminal: Terminal = Terminal(),
) : AutoCloseable {
    private lateinit var client: SnykClient
    private lateinit var orgsAll: Collection<OrgInfo>

    fun getAllOrgs() = orgsAll

    suspend fun init(token: String?) {

        orgsAll = runCatching {
            if (token == null) terminal.println((brightYellow)("Snyk API token was not provided, we will attempt to automatically pick it up from the local configuration"))
            client = SnykClient(token)
            terminal.print("Getting Snyk orgs... ")
            val orgs = client.getOrgs()
            terminal.println("${orgs.size} orgs.")
            orgs
        }.getOrElse {
            terminal.println((brightRed)("Error: ${it.message}"))
            terminal.println("It looks like we were not successful working with Snyk API. This is an alpha build of this tool, so we don't know what happened yet, but that simply could be expired OAUTH token.")
            terminal.println((italic)("Try running 'snyk container test hello-world', and run this tool again.\n"))
            exitProcess(1)
        }
    }

    fun displayOrgs(orgs: Collection<OrgInfo>) {
        terminal.println("List of all Snyk orgs:\n")

        terminal.println(table {

            header {
                style = bold.style
                cellBorders = Borders.BOTTOM

                row { cells("ID", "Slug", "Name") }
            }

            body {
                cellBorders = Borders.NONE

                orgs.forEach {
                    row {
                        cells(it.id, it.attributes.slug, it.attributes.name)
                    }
                }
            }
        })

        terminal.println("\nSnyk org count: ${orgs.size}")
        terminal.println((bold)("\nTo select an org to work with, add it's ID or slug to the command line. E.g., '${orgs.first().attributes.slug}'"))
    }

    fun isOutdatedProject(
        info: ProjectInfo,
        beforeDate: Instant?
    ): Boolean {
        if (beforeDate == null) return false
        return info.meta?.cliMonitoredAt?.let { monitoredAt -> monitoredAt < beforeDate } ?: false
    }

    fun filterProjectInfo(
        projectInfo: Map<TargetInfo, Collection<ProjectInfo>>,
        filters: Collection<String>
    ): Map<TargetInfo, Collection<ProjectInfo>> {
        if (filters.isEmpty()) return projectInfo
        val lowercaseFilters = filters.map {
            it
                .lowercase()
                .removeSurrounding("\"")
                .removeSurrounding("'")
        }


        return projectInfo
            .mapNotNull { (target, projects) ->
                if ((target.id.lowercase() in lowercaseFilters) || (target.attributes.displayName.lowercase() in lowercaseFilters)) return@mapNotNull target to projects
                val filteredProjects = projects.filter { project ->
                    (project.id.lowercase() in lowercaseFilters) || (project.attributes.name.lowercase() in lowercaseFilters)
                }
                if (filteredProjects.isNotEmpty()) target to filteredProjects else null
            }.toMap()
    }

    suspend fun cleanupProjects(
        orgs: Collection<OrgInfo>,
        inactive: Boolean,
        before: LocalDate?,
        filters: Collection<String>,
        @Suppress("UNUSED_PARAMETER") dryRun: Boolean,
    ) {
        val beforeDate = before?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toKotlinInstant()
        orgs.forEach { org ->
            terminal.println("Working with org: ${org.attributes.slug}")

            val projectInfo = getProjectDetails(org)
            val cleanupTargets = filterProjectInfo(projectInfo, filters)
                .asSequence()
                .flatMap { (target, projects) ->

                    projects // !!! important to split by targetReference
                        .groupBy { it.attributes.targetReference }
                        .flatMap { (targetReference, projectsInTargetReference) ->

                            println("Processing ${target.attributes.displayName} :: $targetReference")
                            val latestMonitored = projectsInTargetReference.mapNotNull { it.meta?.cliMonitoredAt }.maxOrNull()
                            val latestMonitoredCutoff = latestMonitored?.minus(24.hours) // Allow scans crossing a day border

                            // TODO: this is global, same happens on target level if this is null
                            val expiredBeforeDate = beforeDate ?: latestMonitoredCutoff

                            projectsInTargetReference
                                .filter { // True for cleanup targets
                                    when {
                                        // Keep inactive
                                        (inactive && it.attributes.status.contentEquals("inactive")) -> true // TODO: enum?
                                        else -> isOutdatedProject(it, expiredBeforeDate)
                                    }
                                }
                                .map { target to it } // flatten. empty projects also get dropped here
                        }
                }
                // TODO: outdated sorting = should sort in the above map probably
                .sortedBy { (_, project) -> project.attributes.name }
                .sortedByDescending { (_, project) -> project.meta?.cliMonitoredAt }
                .sortedBy { (_, project) -> project.attributes.status }
                .sortedBy { (target) -> target.attributes.displayName }
                .toList()

            terminal.println(table {
                header {
                    cellBorders = Borders.BOTTOM
                    row { cells("Target", "Target Reference", "Project", "Project ID", "Status", "Last Monitored") }
                }

                column(2) { width = ColumnWidth.Fixed(40) }

                body {
                    cellBorders = Borders.NONE

                    cleanupTargets.forEach { (target, project) ->
                        row {
                            cells(
                                target.attributes.displayName,
                                project.attributes.targetReference,
                                project.attributes.name,
                                project.id,
                                project.attributes.status,
                                project.meta?.cliMonitoredAt?.toLocalDate()?.toString()
                                    ?: "--"
                            )
                        }
                    }
                }
            })

            println("Getting jira info...")
            val jiraInfo = cleanupTargets
                .filter { (_, project) -> project.isSupportedByJiraApi() }
                .flatMap { (_, project) ->
                    print(".")
                    client.getProjectJiraIssues(org.id, project.id).values.flatten().map { it.jiraIssue.id }
                }

            println()
            println("resolution IS EMPTY AND id IN (${jiraInfo.joinToString()})")

            println("Deleting Snyk Projects...")
            cleanupTargets.forEach { (target, project) ->
                println("https://app.snyk.io/org/${org.attributes.slug}/project/${project.id} :: ${target.attributes.displayName} :: ${project.attributes.name}")
//                 runCatching { client.deleteProject(org.id, project.id) }
//                     .onFailure { println("Error: ${it.message}") }
            }
        }
    }

    suspend fun getProjectDetails(orgInfo: OrgInfo): Map<TargetInfo, Collection<ProjectInfo>> =
        getProjectDetails(orgInfo.id)

    suspend fun getProjectDetails(orgId: String): Map<TargetInfo, Collection<ProjectInfo>> {
        terminal.print("Getting projects... ")
        val projects = client.getOrgProjects(orgId)
        terminal.println("${projects.size} projects.")

        terminal.print("Getting targets... ")
        val targets = client.getOrgTargets(orgId, mapOf("exclude_empty" to "false"))
        terminal.println("${targets.size} targets.")

        return targets.associateWith { target ->
            projects.filter { project ->
                project.relationships?.get("target")?.data?.id.contentEquals(target.id, ignoreCase = true)
            }
        }
    }

    suspend fun cleanupTargets(orgs: Collection<OrgInfo>) {
        terminal.print("Fetching projects and targets... ")
        val orgDetails = orgs.associateWith { org ->
            // TODO: dynamic fancy terminal output
            terminal.println("Working with org: ${org.attributes.slug}")
            val targetsWithProjects = getProjectDetails(org)
            val emptyTargets = targetsWithProjects.filter { it.value.isEmpty() }.keys

            if (emptyTargets.isNotEmpty()) {
                terminal.println("\nEmpty targets detected:\n")
                terminal.println(grid {
                    row {
                        style = bold.style
                        cells("Target", "Created", "Private?", "ID", "Targets with the same name but different ID")
                    }

                    emptyTargets.forEach { emptyTarget ->

                        val sameNameTargets = targetsWithProjects.keys
                            .filter { it.attributes.displayName.contentEquals(emptyTarget.attributes.displayName) && it.id != emptyTarget.id }

                        row {
                            cell(emptyTarget.attributes.displayName)
                            cell(emptyTarget.attributes.createdAt.toLocalDate())
                            cell(emptyTarget.attributes.isPrivate)
                            cell(emptyTarget.id)
                            cell(
                                sameNameTargets.joinToString("\n") { dupe -> "${dupe.attributes.displayName} (${dupe.id})" }
                                    .ifEmpty { "--" }
                            )
                        }
                    }
                    row {}
                })


                val response =
                    YesNoPrompt(prompt = "Do you want to delete these targets?", terminal = terminal).ask()
                terminal.println()

                if (response == true) emptyTargets.maxOf { it.attributes.displayName.length }.let { padding ->
                    emptyTargets.forEach {
                        terminal.print((yellow)("Deleting ${(brightYellow)(it.attributes.displayName.padEnd(padding))}"))
                        terminal.println(" \uD83D\uDC80") // 💀
                    }

                    terminal.println()
                }
            } else {
                terminal.println("No empty targets")
            }
        }

        terminal.println(table {
            header { row { cells("Org Id", "Org Slug", "Org Name", "Project count", "Target count") } }
            body {
                orgDetails.forEach {


                    row { cells("", "") }
                }

            }

        })
    }

    override fun close() {
        client.close()
    }
}
