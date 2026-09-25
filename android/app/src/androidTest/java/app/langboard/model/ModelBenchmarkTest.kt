package app.langboard.model

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.langboard.MainActivity
import app.langboard.core.Beams
import app.langboard.core.FillGapRequest
import app.langboard.core.ModelFillGapEngine
import app.langboard.core.PromptBook
import app.langboard.core.Register
import app.langboard.core.ScreenLine
import app.langboard.core.ScreenText
import app.langboard.core.Scored
import app.langboard.core.TextModel
import app.langboard.llama.BeamSearch
import app.langboard.llama.LlamaModel
import app.langboard.llama.Sampling
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Load time, speed and memory of each GGUF in files/models/bench/ on this phone. Skipped when that
 * folder is empty. Copy models in with:
 *
 *   adb push model.gguf /data/local/tmp/
 *   adb shell run-as app.langboard sh -c 'mkdir -p files/models/bench && cp /data/local/tmp/model.gguf files/models/bench/'
 *
 * Optional arguments: -e threads 4 -e ctx 1024 -e only <file name part> -e top true (keeps an
 * activity open so the process runs as top-app and may use the big cores). Results go to logcat
 * (tag LangboardBench) and files/models/bench/report.md. The inputs are synthetic test sentences.
 */
@RunWith(AndroidJUnit4::class)
class ModelBenchmarkTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()
  private val report = StringBuilder()

  private val book = PromptBook.parse(context.assets.open("prompts.json").bufferedReader().use { it.readText() })

  /** Alternating received and sent, as a chat app lays them out. */
  private val chat = ScreenText(listOf(
    "周末有什么安排吗", "还没想好，可能在家躺着", "要不要一起出去玩", "去哪儿啊", "听说新开了一家火锅店，评价还不错",
    "可以啊，几点", "晚上七点怎么样", "行，我叫上小王", "他上次说要来结果又鸽了", "哈哈哈他就那样",
    "这次一定要让他来", "你跟他说好了吗", "我发消息了他还没回", "你本来不是说要去那个聚会吗", "你周末去那个聚会了吗？",
  ).mapIndexed { i, t -> ScreenLine(t, if (i % 2 == 0) ScreenLine.Align.Start else ScreenLine.Align.End) })

  private fun them(vararg lines: String) = ScreenText(lines.map { ScreenLine(it, ScreenLine.Align.Start) })

  private val cases = listOf(
    "fill, short" to FillGapRequest("周末一起去看", "concert", "吗？"),
    "fill, phrase" to FillGapRequest("我本来想去但是 ", "I couldn't be bothered anymore", "", register = Register.Casual),
    "fill, formal" to FillGapRequest("您好，关于明天的会议，我", "might not be able to make it", "，非常抱歉。", register = Register.Formal, screen = them("王总：明天上午十点开会，请准时参加。")),
    "fill, slang" to FillGapRequest("这个事情太", "insane", "了", register = Register.Casual, screen = them("他又把deadline改了")),
    "fill, with chat" to FillGapRequest("我本来想去但是 ", "I couldn't be bothered anymore", "", register = Register.Casual, screen = chat),
    "fill, same chat, next phrase" to FillGapRequest("我本来想去但是 ", "I was too tired", "", register = Register.Casual, screen = chat),
  )

  private fun log(line: String) {
    Log.i("LangboardBench", line)
    report.appendLine(line)
  }

  /** The engines' view of [model], keeping the first call's timings (the greedy pass reads the prompt). */
  private class Timed(private val model: LlamaModel) : TextModel {
    var first: app.langboard.llama.Completion? = null
    /** Milliseconds and tokens generated per pass: greedy, beams, score. */
    val passes = StringBuilder()

    override suspend fun complete(prompt: String, maxTokens: Int, repeatPenalty: Float, banLatin: Boolean, onText: (String) -> Boolean): Scored {
      val c = model.complete(prompt, Sampling(maxTokens = maxTokens, repeatPenalty = repeatPenalty, banLatin = banLatin), onText)
      if (first == null) first = c
      passes.append("greedy ${c.totalMs}ms/${c.generatedTokens}t ")
      return Scored(c.text, c.logprob)
    }

    override suspend fun beams(prompt: String, search: Beams): List<Scored> {
      val (hyps, c) = model.beams(prompt, BeamSearch(search.beams, search.maxTokens, search.lengthAlpha, true, search.banLatin, search.stops, search.stopText, search.keepUnfinished))
      passes.append("beams ${c.totalMs}ms/${c.generatedTokens}t ")
      return hyps.map { Scored(it.text, it.logprob.toDouble()) }
    }

    override suspend fun score(prompt: String, continuations: List<String>): List<Double> {
      val (lps, c) = model.score(prompt, continuations)
      passes.append("score ${c.totalMs}ms/${c.generatedTokens}t")
      return lps.map { it.toDouble() }
    }
  }

  /** kB values from /proc/self/status. */
  private fun mem(): Map<String, Long> = File("/proc/self/status").readLines()
    .mapNotNull { l -> Regex("""^(VmRSS|VmHWM|RssAnon|RssFile):\s+(\d+) kB""").find(l)?.destructured?.let { (k, v) -> k to v.toLong() } }
    .toMap()

  private fun mb(kb: Long?) = "${(kb ?: 0) / 1024} MB"

  @Test fun benchmark(): Unit = runBlocking {
    val scenario = if (args.getString("top") == "true") ActivityScenario.launch(MainActivity::class.java) else null
    val dir = File(context.filesDir, "models/bench")
    val only = args.getString("only")
    val models = dir.listFiles { f -> f.name.endsWith(".gguf") && (only == null || f.name.contains(only)) }?.sortedBy { it.length() }.orEmpty()
    assumeTrue("no models in $dir", models.isNotEmpty())
    val threads = args.getString("threads")?.toInt() ?: LlamaModel.defaultThreads()
    val ctx = args.getString("ctx")?.toInt() ?: 1024

    val cpuset = File("/proc/self/cgroup").readLines().firstOrNull { it.contains("cpuset") }?.substringAfterLast(':')
    log("# Model benchmark: ${android.os.Build.MODEL}, $threads threads, $ctx-token context, cpuset $cpuset")
    log(LlamaModel.systemInfo(context))

    for (file in models) {
      log("\n## ${file.name} (${file.length() / 1_000_000} MB)")
      val before = mem()
      val t0 = System.nanoTime()
      val model = LlamaModel.load(context, file.path, ctx, threads, maxBeams = 16)
      val loadMs = (System.nanoTime() - t0) / 1_000_000
      val loaded = mem()
      log("load ${loadMs} ms; RSS ${mb(loaded["VmRSS"])} (file ${mb(loaded["RssFile"])}, anon ${mb(loaded["RssAnon"])}), was ${mb(before["VmRSS"])}")
      log("prompts ${book.version}; fill: ${book.fill.beams} beams, up to ${book.fill.maxTokens} tokens")
      log("| case | prompt tok (reused) | prompt ms | first answer ms | all options ms | passes | options |")
      log("| --- | --- | --- | --- | --- | --- | --- |")
      val timed = Timed(model)
      val engine = ModelFillGapEngine(timed, file.name) { book }
      for ((_, label) in listOf(0 to "cold", 1 to "warm")) {
        for ((name, r) in cases) {
          timed.first = null
          timed.passes.clear()
          val t = System.nanoTime()
          var firstMs = -1L
          val result = engine.fill(r) { firstMs = (System.nanoTime() - t) / 1_000_000 }
          val totalMs = (System.nanoTime() - t) / 1_000_000
          val c = timed.first
          val options = result?.candidates.orEmpty().joinToString(" / ") { "${it.text} ${((it.share ?: 0f) * 100).toInt()}%" }
          log("| $name ($label) | ${c?.promptTokens} (${c?.reusedTokens}) | ${c?.promptMs} | $firstMs | $totalMs | ${timed.passes} | $options |")
        }
      }
      val e = model.complete(book.explain("你别在那阴阳怪气的", them("我今天又加班了", "你别在那阴阳怪气的")), Sampling(maxTokens = 48)) { !it.contains('\n') }
      log("| explain | ${e.promptTokens} | ${e.promptMs} | ${e.firstTokenMs} | ${e.totalMs} | ${e.generatedTokens} | | ${e.text.trim()} |")
      val peak = mem()
      log("peak RSS ${mb(peak["VmHWM"])}; now ${mb(peak["VmRSS"])} (file ${mb(peak["RssFile"])}, anon ${mb(peak["RssAnon"])})")
      model.close()
    }
    File(dir, "report.md").writeText(report.toString())
    scenario?.close()
  }
}
