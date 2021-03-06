/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.atlas.chart

import java.awt.Color
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

import com.netflix.atlas.chart.model._
import com.netflix.atlas.core.model.ArrayTimeSeq
import com.netflix.atlas.core.model.DsType
import com.netflix.atlas.core.model.FunctionTimeSeq
import com.netflix.atlas.core.model.TimeSeries
import com.netflix.atlas.core.util.PngImage
import com.netflix.atlas.core.util.Streams
import com.netflix.atlas.json.Json
import com.netflix.atlas.test.GraphAssertions
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FunSuite


abstract class PngGraphEngineSuite extends FunSuite with BeforeAndAfterAll {

  private val dataDir   = s"graphengine/data"

  // SBT working directory gets updated with fork to be the dir for the project
  private val baseDir = "."
  private val goldenDir = s"$baseDir/src/test/resources/graphengine/${getClass.getSimpleName}"
  private val targetDir = s"$baseDir/target/${getClass.getSimpleName}"
  private val graphAssertions = new GraphAssertions(goldenDir, targetDir)

  val bless = false

  def prefix: String
  def graphEngine: PngGraphEngine

  val step = 60000

  def constant(v: Double): TimeSeries = {
    TimeSeries(Map("name" -> v.toString), new FunctionTimeSeq(DsType.Gauge, step, _ => v))
  }

  def wave(min: Double, max: Double, wavelength: Duration): TimeSeries = {
    val lambda = 2 * scala.math.Pi / wavelength.toMillis
    def f(t: Long): Double = {
      val amp = (max - min) / 2.0
      val yoffset = min + amp
      amp * scala.math.sin(t * lambda) + yoffset
    }
    TimeSeries(Map("name" -> "wave"), new FunctionTimeSeq(DsType.Gauge, step, f))
  }

  def interval(ts1: TimeSeries, ts2: TimeSeries, s: Long, e: Long): TimeSeries = {
    def f(t: Long): Double = {
      val ts = if (t >= s && t < e) ts2 else ts1
      ts.data(t)
    }
    TimeSeries(Map("name" -> "interval"), new FunctionTimeSeq(DsType.Gauge, step, f))
  }

  def finegrainWave(min: Int, max: Int, hours: Int): TimeSeries = {
    wave(min, max, Duration.ofHours(hours))
  }

  def finegrainSeriesDef(min: Int, max: Int, hours: Int): LineDef = {
    LineDef(finegrainWave(min ,max, hours))
  }

  def simpleWave(min: Int, max: Int): TimeSeries = {
    wave(min, max, Duration.ofDays(1))
  }

  def simpleWave(max: Int): TimeSeries = {
    simpleWave(0, max)
  }

  def simpleSeriesDef(min: Int, max: Int): LineDef = {
    LineDef(simpleWave(min ,max))
  }

  def simpleSeriesDef(max: Int) : LineDef = {
    simpleSeriesDef(0, max)
  }

  def outageSeriesDef(max: Int): LineDef = {
    val start1 = ZonedDateTime.of(2012, 1, 1, 5, 0, 0, 0, ZoneOffset.UTC).toInstant
    val end1 = ZonedDateTime.of(2012, 1, 1, 6, 38, 0, 0, ZoneOffset.UTC).toInstant

    val start2 = ZonedDateTime.of(2012, 1, 1, 7, 4, 0, 0, ZoneOffset.UTC).toInstant
    val end2 = ZonedDateTime.of(2012, 1, 1, 7, 5, 0, 0, ZoneOffset.UTC).toInstant

    val bad = constant(0)
    val normal = interval(simpleWave(max), bad, start1.toEpochMilli, end1.toEpochMilli)
    LineDef(interval(normal, bad, start2.toEpochMilli, end2.toEpochMilli))
  }

  def constantSeriesDef(value: Double): LineDef = {
    LineDef(constant(value))
  }

  def makeTranslucent(c: Color): Color = {
     new Color(c.getRed, c.getGreen, c.getBlue, 75)
  }

  override def afterAll() {
    graphAssertions.generateReport(getClass)
  }

  def label(vs: LineDef*): List[LineDef] = label(0, Palette.default, vs: _*)

  def label(offset: Int, p: Palette, vs: LineDef*): List[LineDef] = {
    vs.toList.zipWithIndex.map { case (v, i) =>
      val c = p.withAlpha(v.color.getAlpha).colors(i + offset)
      v.copy(data = v.data.withLabel(i.toString), color = c)
    }
  }

  def load(resource: String): GraphDef = {
    Streams.scope(Streams.resource(resource)) { in => Json.decode[GraphData](in).toGraphDef }
  }

  /*
  Query to find single wide spikes from garbage collection metrics:
  http://atlas-main.us-east-1.test.netflix.net:7001/api/v1/graph?q=nf.cluster,
  atlas_backend-publish,:eq,class,GarbageCollectorMXBean,:eq,:and,nf.node,i-274a814a,
  :eq,:and,name,collectionTime,:eq,:and,id,PS_MarkSweep,:eq,:and,:sum&s=e-1d
  &format=json
   */
  test("non_uniformly_drawn_spikes") {

    val name         = prefix + "_non_uniformly_drawn_spikes.png"
    val dataFileName = prefix + "_non_uniformly_drawn_spikes.json"

    val graphDef = load(s"$dataDir/$dataFileName").copy(width = 700)
      .adjustPlots(_.copy(axisColor = Some(Color.BLACK)))
    //atlas generated sample is 780 wide less 64 origin less 16 r side padding == 700
    //expect to see width of spikes vary as x values repeat due to rounding
    //RrdGraph calculates x values based on number of pixels/second
    val image = PngImage(graphEngine.createImage(graphDef), Map.empty)
    graphAssertions.assertEquals(image, name, bless)
  }

  test("one_data_point_wide_spike") {

    val name = prefix + "_one_data_point_wide_spike.png"
    val tags = Map("name" -> "beehive_honeycomb.counter.full.index.success")

    // 2 days of samples with 3 minute intervals inclusive start-end
    val sampleCnt =  1 +  (2 * 1440) / 3
    val values = new Array[Double](sampleCnt)

    values(0) =  0.005553

    //examples of spike values skipped with step 288 calculated by RrdGraph
    //based on pixel count of 600
    values(10) =  0.005553

    //roughly at the location of the spike reported missing in jira CLDMTA-1449
    values(690) = 0.005553

    //last data point spike drawn high to end of graph axis,
    //(e.g. one pixel wide trailing bar)
    values(sampleCnt-1)  =  0.005553

    val start = ZonedDateTime.of(2013, 6,  9, 18, 0, 0, 0, ZoneOffset.UTC).toInstant
    val end = ZonedDateTime.of(2013, 6, 11, 18, 0, 0, 0, ZoneOffset.UTC).toInstant
    val step = 3 * 60000 // one sample every 3 minutes

    val seq = new ArrayTimeSeq(DsType.Gauge, start.toEpochMilli, step, values)
    val seriesDef = LineDef(TimeSeries(tags, "0", seq))

    val plotDef = PlotDef(List(seriesDef), upper = Some(0.005553 * 1.5), axisColor = Some(Color.BLACK))

    val graphDef = GraphDef(
      width = 1100,
      height = 200,
      startTime = start,
      endTime = end,
      step = step,  // one sample every 3 minute
      plots = List(plotDef)
    )

    val image = PngImage(graphEngine.createImage(graphDef), Map.empty)
    graphAssertions.assertEquals(image, name, bless)
  }

  private def lines(name: String, vs: Seq[Double], f: GraphDef => GraphDef): Unit = {
    test(name) {
      val series = vs.map { v => if (v.isNaN) constantSeriesDef(v) else simpleSeriesDef(v.toInt) }
      val plotDef = PlotDef(label(series: _*), axisColor = Some(Color.BLACK))

      val graphDef = GraphDef(
        startTime = ZonedDateTime.of(2012, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
        endTime = ZonedDateTime.of(2012, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
        plots = List(plotDef)
      )

      val image = PngImage(graphEngine.createImage(f(graphDef)), Map.empty)
      val fname = s"${prefix}_$name.png"
      graphAssertions.assertEquals(image, fname, bless)
    }
  }

  private def singleLine(name: String, f: GraphDef => GraphDef): Unit = {
    lines(name, Seq(400), f)
  }

  singleLine("single_line",                v => v)
  singleLine("single_line_with_load_time", v => v.copy(loadTime = 5123L))
  singleLine("single_line_only_graph",     v => v.copy(onlyGraph = true))
  singleLine("single_line_no_border",      v => v.copy(showBorder = false))
  singleLine("single_line_title",          v => v.copy(title = Some("A sample title")))
  singleLine("single_line_timezone",       v => v.copy(timezone = ZoneId.of("US/Pacific")))
  singleLine("single_line_no_legend",      v => v.copy(legendType = LegendType.OFF))
  singleLine("single_line_no_legend_stats",v => v.copy(legendType = LegendType.LABELS_ONLY))
  singleLine("single_line_linewidth",      v => v.adjustLines(_.copy(lineWidth = 3.0f)))
  singleLine("single_line_upper",          v => v.adjustPlots(_.copy(upper = Some(200))))
  singleLine("single_line_lower",          v => v.adjustPlots(_.copy(lower = Some(200))))
  singleLine("single_line_ylabel",         v => v.adjustPlots(_.copy(ylabel = Some("something useful"))))
  singleLine("single_line_area",           v => v.adjustLines(_.copy(lineStyle = LineStyle.AREA)))
  singleLine("single_line_stack",          v => v.adjustLines(_.copy(lineStyle = LineStyle.STACK)))
  singleLine("single_line_color",          v => v.adjustLines(_.copy(color = Color.BLUE)))
  singleLine("single_line_logarithmic",    v => v.adjustPlots(_.copy(scale = Scale.LOGARITHMIC)))


  val longLabel =
    """
      |A long ylabel that should cause it to wrap when displayed on the chart. Some more text to
      | ensure that it will wrap when showing in the legend.
    """.stripMargin
  singleLine("single_line_ylabel_wrap",    v => v.adjustPlots(_.copy(ylabel = Some(longLabel))))

  lines("single_line_log_negative", Seq(-400), v => v.adjustPlots(_.copy(scale = Scale.LOGARITHMIC)))
  lines("single_line_log_large", Seq(4.123e9), v => v.adjustPlots(_.copy(scale = Scale.LOGARITHMIC)))

  lines("single_line_stack_negative", Seq(-400), v => v.adjustLines(_.copy(lineStyle = LineStyle.STACK)))

  test("single_line_hspans") {
    def alpha(c: Color): Color = new Color(c.getRed, c.getGreen, c.getBlue, 50)
    val spans = List(
      HSpanDef(300, 400, alpha(Color.RED), None),
      HSpanDef(5, 42, alpha(Color.BLUE), Some("really bad error")))

    val plotDef = PlotDef(spans ::: label(simpleSeriesDef(400)), axisColor = Some(Color.BLACK))

    val graphDef = GraphDef(
      startTime = ZonedDateTime.of(2012, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      endTime = ZonedDateTime.of(2012, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      plots = List(plotDef)
    )

    val image = PngImage(graphEngine.createImage(graphDef), Map.empty)
    val fname = s"${prefix}_single_line_hspans.png"
    graphAssertions.assertEquals(image, fname, bless)
  }

  test("single_line_vspans") {
    def alpha(c: Color): Color = new Color(c.getRed, c.getGreen, c.getBlue, 50)
    val errStart = ZonedDateTime.of(2012, 1, 1, 4, 0, 0, 0, ZoneOffset.UTC).toInstant
    val errEnd = ZonedDateTime.of(2012, 1, 1, 8, 30, 0, 0, ZoneOffset.UTC).toInstant
    val spans = List(VSpanDef(errStart, errEnd, alpha(Color.RED), None))

    val plotDef = PlotDef(spans ::: label(simpleSeriesDef(400)), axisColor = Some(Color.BLACK))

    val graphDef = GraphDef(
      startTime = ZonedDateTime.of(2012, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      endTime = ZonedDateTime.of(2012, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      plots = List(plotDef)
    )

    val image = PngImage(graphEngine.createImage(graphDef), Map.empty)
    val fname = s"${prefix}_single_line_vspans.png"
    graphAssertions.assertEquals(image, fname, bless)
  }

  private def doubleLine(name: String, f: GraphDef => GraphDef): Unit = {
    lines(name, Seq(400, 150), f)
  }

  doubleLine("double_line",        v => v)
  doubleLine("axis_per_line",      v => v.axisPerLine)
  doubleLine("double_line_stack",  v => v.adjustLines(_.copy(lineStyle = LineStyle.STACK)))

  lines("double_line_stack_on_NaN",     Seq(Double.NaN, 150),       v => v.adjustLines(_.copy(lineStyle = LineStyle.STACK)))
  lines("double_line_stack_middle_NaN", Seq(150, Double.NaN, 300),  v => v.adjustLines(_.copy(lineStyle = LineStyle.STACK)))
  lines("double_line_stack_negative",   Seq(-400, 150),             v => v.adjustLines(_.copy(lineStyle = LineStyle.STACK)))

  test("double_yaxis") {
    // Keeping output the same, this is a hold-over from before the rendering supported multi-y.
    // Will fix in a later iteration as I'm trying to ensure all results are the same for
    // refactoring the model.
    val plotDef1 = PlotDef(
      label(0, Palette.default, simpleSeriesDef(40000), simpleSeriesDef(42)) ++
      label(2, Palette.default, simpleSeriesDef(400), simpleSeriesDef(150)),
      axisColor = Some(Color.BLACK))

    val graphDef = GraphDef(
      startTime = ZonedDateTime.of(2012, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      endTime = ZonedDateTime.of(2012, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      plots = List(plotDef1)
    )

    val image = PngImage(graphEngine.createImage(graphDef), Map.empty)
    val fname = s"${prefix}_double_yaxis.png"
    graphAssertions.assertEquals(image, fname, bless)
  }

  test("vspans_from_line") {
    val dataDef = outageSeriesDef(400).copy(lineWidth = 2.0f, color = Color.RED)
    val spanDef = outageSeriesDef(400).copy(lineStyle = LineStyle.VSPAN, color = Colors.withAlpha(Color.GREEN, 40))
    val plotDef = PlotDef(label(dataDef, spanDef), axisColor = Some(Color.BLACK))

    val graphDef = GraphDef(
      width = 1200,
      startTime = ZonedDateTime.of(2012, 1, 1, 4, 0, 0, 0, ZoneOffset.UTC).toInstant,
      endTime = ZonedDateTime.of(2012, 1, 1, 8, 0, 0, 0, ZoneOffset.UTC).toInstant,
      plots = List(plotDef))

    val image = PngImage(graphEngine.createImage(graphDef), Map.empty)
    val name = prefix + "_vspans_from_line.png"
    graphAssertions.assertEquals(image, name, bless)
  }

  test("multiy_no_lines") {
    val graphDef = GraphDef(
      startTime = ZonedDateTime.of(2012, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      endTime = ZonedDateTime.of(2012, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      plots = List(PlotDef(Nil), PlotDef(Nil))
    )
    val image = PngImage(graphEngine.createImage(graphDef), Map.empty)
    val name = prefix + "_multiy_no_lines.png"
    graphAssertions.assertEquals(image, name, bless)
  }

  test("multiy_two") {
    val graphDef = GraphDef(
      startTime = ZonedDateTime.of(2012, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      endTime = ZonedDateTime.of(2012, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      plots = List(PlotDef(label(simpleSeriesDef(100))), PlotDef(label(simpleSeriesDef(100000))))
    )
    val image = PngImage(graphEngine.createImage(graphDef), Map.empty)
    val name = prefix + "_multiy_two.png"
    graphAssertions.assertEquals(image, name, bless)
  }

  test("multiy_two_colors") {
    val p = Palette.default
    val graphDef = GraphDef(
      startTime = ZonedDateTime.of(2012, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      endTime = ZonedDateTime.of(2012, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      plots = List(
        PlotDef(label(0, p, simpleSeriesDef(100))),
        PlotDef(label(1, p, simpleSeriesDef(100000))))
    )
    val image = PngImage(graphEngine.createImage(graphDef), Map.empty)
    val name = prefix + "_multiy_two_colors.png"
    graphAssertions.assertEquals(image, name, bless)
  }

  def multiy(name: String, f: PlotDef => PlotDef): Unit = {
    test(name) {
      val plots = (0 until GraphConstants.MaxYAxis).map { i =>
        val p = PlotDef(label(i, Palette.default, finegrainSeriesDef(i, 50 * i, i)))
        if (i == 1) f(p) else p
      }
      val graphDef = GraphDef(
        startTime = ZonedDateTime.of(2012, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
        endTime = ZonedDateTime.of(2012, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
        plots = plots.toList
      )
      val image = PngImage(graphEngine.createImage(graphDef), Map.empty)
      val fname = s"${prefix}_multiy_n_${name}.png"
      graphAssertions.assertEquals(image, fname, bless)
    }
  }

  multiy("identity",    v => v)
  multiy("upper",       v => v.copy(upper = Some(25.0)))
  multiy("lower",       v => v.copy(lower = Some(25.0)))
  multiy("ylabel",      v => v.copy(ylabel = Some("something useful")))
  multiy("ylabel_wrap", v => v.copy(ylabel = Some(longLabel)))
  multiy("color",       v => v.copy(axisColor = Some(Color.LIGHT_GRAY)))
  multiy("logarithmic", v => v.copy(scale = Scale.LOGARITHMIC))

  // https://github.com/Netflix/atlas/issues/119
  // TODO: fix to show label
  test("issue-119_missing_y_labels") {
    val plotDef = PlotDef(label(constantSeriesDef(2027)), axisColor = Some(Color.BLACK))

    val graphDef = GraphDef(
      startTime = ZonedDateTime.of(2012, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      endTime = ZonedDateTime.of(2012, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      plots = List(plotDef))

    val image = PngImage(graphEngine.createImage(graphDef), Map.empty)
    val name = prefix + "_issue-119_missing_y_labels.png"
    graphAssertions.assertEquals(image, name, bless)
  }

  test("issue-119_small_range_large_base") {
    val plotDef = PlotDef(label(simpleSeriesDef(2026, 2027)), axisColor = Some(Color.BLACK))

    val graphDef = GraphDef(
      startTime = ZonedDateTime.of(2012, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      endTime = ZonedDateTime.of(2012, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      plots = List(plotDef))

    val image = PngImage(graphEngine.createImage(graphDef), Map.empty)
    val name = prefix + "_issue-119_small_range_large_base.png"
    graphAssertions.assertEquals(image, name, bless)
  }

  test("issue-119_small_range_large_negative") {
    val plotDef = PlotDef(label(simpleSeriesDef(-2027, -2026)), axisColor = Some(Color.BLACK))

    val graphDef = GraphDef(
      startTime = ZonedDateTime.of(2012, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      endTime = ZonedDateTime.of(2012, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      plots = List(plotDef))

    val image = PngImage(graphEngine.createImage(graphDef), Map.empty)
    val name = prefix + "_issue-119_small_range_large_negative.png"
    graphAssertions.assertEquals(image, name, bless)
  }

  lines("too_many_lines", (0 until 1024).map(_.toDouble).toSeq, v => v)

  lines("excessive_height", Seq(100), v => v.copy(height = 2048))
  lines("excessive_width",  Seq(100), v => v.copy(width = 2048))

  test("notices") {

    val plotDef = PlotDef(label(simpleSeriesDef(400)), axisColor = Some(Color.BLACK))

    val graphDef = GraphDef(
      startTime = ZonedDateTime.of(2012, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      endTime = ZonedDateTime.of(2012, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC).toInstant,
      plots = List(plotDef),
      warnings = List(
          """
            |This is an information message that is shown on the graph to let the user know
            | about something important. It should be long enough to force the message to wrap.
          """.stripMargin,
          "Something bad happened and we wanted you to know.",
          "Something really bad happened.")
    )

    val image = PngImage(graphEngine.createImage(graphDef), Map.empty)
    val name = prefix + "_notices.png"
    graphAssertions.assertEquals(image, name, bless)
  }

  VisionType.values.foreach { vt =>
    def f(gdef: GraphDef): GraphDef = {
      gdef.adjustLines(_.copy(lineStyle = LineStyle.STACK)).withVisionType(vt)
    }
    lines(s"vision_${vt.name}", (0 until 9).map(_ => 100.0).toSeq, f)
  }

}

case class GraphData(
    start: Long,
    step: Long,
    legend: List[String],
    metrics: List[Map[String, String]],
    values: List[List[Double]]) {

  def toGraphDef: GraphDef = {
    val nbrSteps = values.length - 1
    val s = Instant.ofEpochMilli(start)
    val e = s.plusMillis(step * nbrSteps)

    val seq = new ArrayTimeSeq(DsType.Gauge, s.toEpochMilli, step, values.flatten.toArray)
    val seriesDef = LineDef(TimeSeries(Map.empty, "0", seq))
    val plotDef = PlotDef(List(seriesDef))

    GraphDef(startTime = s, endTime = e, step = step, plots = List(plotDef))
  }
}
