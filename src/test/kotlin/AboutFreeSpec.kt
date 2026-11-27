import dev.samoylenko.client.snyk.cli.About
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class AboutFreeSpec : FreeSpec({
    "names its component" { About.NAME shouldBe "snyk-cleanup-tool-classic" }
    "describe its component" { About.DESCRIPTION shouldBe "A simple command-line tool to help clean up Snyk orgs." }
})
