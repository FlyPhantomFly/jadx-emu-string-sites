package jadx.emu.ext.strings

import jadx.api.plugins.pass.JadxPassInfo
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode
import jadx.plugins.emu.api.EmuExtension
import jadx.plugins.emu.api.EmuExtensionContext
import jadx.plugins.emu.api.EmuExtensionInfo
import jadx.plugins.emu.api.ExtensionMode
import jadx.plugins.emu.exec.ExecLimits
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

const val STRING_SITES_VERSION = "0.3.0"

/**
 * Finds decrypted strings in the loaded app and reports every site it could not decode, with the reason.
 * Nothing in the decompiled output is changed; the result is `strings-report.json` / `.txt` in the extension's data directory.
 *
 * Config: `<dataDir>/string-sites.properties` - `autoAnalyze` (default false; the GUI/CLI run does nothing until it is true),
 * `helperMinConstantSites`, `allowNoArgHelpers`, `maxSteps`, `maxMillis`, `maxDepth`, `classPrefix` (analyze only classes whose
 * descriptor starts with it, e.g. `Lcom/example/`).
 */
class StringSitesExtension : EmuExtension {

    override fun info(): EmuExtensionInfo = EmuExtensionInfo(
        id = "string-sites",
        name = "String sites",
        description = "Finds decrypted strings by sink and by usage, and reports what it could not decode and why",
        mode = ExtensionMode.ON_DEMAND,
        requiredEmuVersion = "0.1.0",
    )

    override fun init(ctx: EmuExtensionContext) {
        val cfg = Config.load(ctx.dataDir, ctx.log)
        ctx.log.info("string-sites v{} | autoAnalyze={} helperMinConstantSites={} classPrefix={}", STRING_SITES_VERSION, cfg.auto, cfg.settings.helperMinConstantSites, cfg.classPrefix ?: "(all)")
        ctx.addPass(StringSitesPass(ctx, cfg))
    }

    internal class Config(val auto: Boolean, val settings: AnalyzerSettings, val classPrefix: String?) {
        companion object {
            fun load(dir: Path, log: Logger): Config {
                val f = dir.resolve("string-sites.properties")
                val p = Properties()
                if (Files.exists(f)) runCatching { Files.newInputStream(f).use { p.load(it) } }
                    .onFailure { log.warn("string-sites: cannot read {}: {}", f, it.toString()) }
                else log.info("string-sites: no config file, using defaults. Create {} (autoAnalyze=true) to run.", f)
                fun int(k: String, d: Int) = p.getProperty(k)?.trim()?.toIntOrNull() ?: d
                val base = AnalyzerSettings()
                val settings = AnalyzerSettings(
                    limits = ExecLimits(maxSteps = int("maxSteps", 2_000_000), maxMillis = (p.getProperty("maxMillis")?.trim()?.toLongOrNull() ?: 5_000L), maxDepth = int("maxDepth", 32)),
                    helperMinConstantSites = int("helperMinConstantSites", base.helperMinConstantSites),
                    allowNoArgHelpers = p.getProperty("allowNoArgHelpers")?.trim()?.toBooleanStrictOrNull() ?: base.allowNoArgHelpers,
                )
                return Config(p.getProperty("autoAnalyze")?.trim()?.toBooleanStrictOrNull() ?: false, settings, p.getProperty("classPrefix")?.trim()?.ifEmpty { null })
            }
        }
    }
}

/** Runs the analysis once, before any class is decompiled, when `autoAnalyze=true`. Does not transform decompiled code. */
internal class StringSitesPass(private val ctx: EmuExtensionContext, private val cfg: StringSitesExtension.Config) : JadxDecompilePass {

    @Volatile private var done = false
    private val log: Logger get() = ctx.log

    override fun getInfo(): JadxPassInfo = OrderedJadxPassInfo("StringSites", "Report decrypted string sites and the reasons any were skipped")

    override fun init(root: RootNode) {
        if (!cfg.auto || done) return
        done = true
        log.info("string-sites: analysing")
        runCatching { run() }.onFailure { log.warn("string-sites: analysis failed: {}", it.toString()) }
    }

    private fun run() {
        val started = System.nanoTime()
        val source = ctx.emu.source
        val methods = source.allMethods().filter { cfg.classPrefix == null || it.declClass.startsWith(cfg.classPrefix) }
        val report = StringSiteAnalyzer(source, ctx.emu.engine, cfg.settings).analyze(methods)
        val json = ctx.dataDir.resolve("strings-report.json")
        val txt = ctx.dataDir.resolve("strings-report.txt")
        Files.createDirectories(ctx.dataDir)
        Files.write(json, report.toJson().toByteArray(Charsets.UTF_8))
        Files.write(txt, report.toText(maxSkipped = 100_000).toByteArray(Charsets.UTF_8))
        val s = report.summary()
        log.info(
            "string-sites: {} sites, {} decoded ({} decrypted, {} folded constants), {} skipped, {} ms",
            s.found, s.decoded, report.decrypted.size, s.decoded - report.decrypted.size, s.skipped, (System.nanoTime() - started) / 1_000_000,
        )
        if (s.skipReasons.isNotEmpty()) log.info("string-sites: skipped by reason: {}", s.skipReasons.entries.joinToString { "${it.key}=${it.value}" })
        log.info("string-sites: report written to {} and {}", json, txt)
    }

    override fun visit(cls: ClassNode): Boolean = false
    override fun visit(mth: MethodNode) {}
}
