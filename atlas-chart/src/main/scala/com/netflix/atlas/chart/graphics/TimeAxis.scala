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
package com.netflix.atlas.chart.graphics

import java.awt.Graphics2D
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Draws a time based X-axis.
 *
 * @param style
 *     Style to use for the axis and corresponding labels.
 * @param start
 *     Start time in milliseconds since the epoch.
 * @param end
 *     End time in milliseconds since the epoch.
 * @param step
 *     Step size in milliseconds.
 * @param zone
 *     Time zone to use for the labels. This is a presentation detail only and can sometimes
 *     result in duplicates. For example, during a daylight savings transition the same hour
 *     can be used for multiple tick marks.
 */
case class TimeAxis(
    style: Style,
    start: Long,
    end: Long,
    step: Long,
    zone: ZoneId = ZoneOffset.UTC) extends Element with FixedHeight {

  override def height: Int = 10 + Constants.smallFontDims.height

  def scale(p1: Int, p2: Int): Scales.LongScale = {
    Scales.time(start - step, end - step, step, p1, p2)
  }

  def ticks(x1: Int, x2: Int): List[TimeTick] = {
    val numTicks = (x2 - x1) / TimeAxis.minTickLabelWidth
    Ticks.time(start, end, zone, numTicks)
  }

  def draw(g: Graphics2D, x1: Int, y1: Int, x2: Int, y2: Int): Unit = {
    style.configure(g)

    // Horizontal line across the bottom of the chart
    g.drawLine(x1, y1, x2, y1)

    val xscale = scale(x1, x2)
    val majorTicks = ticks(x1, x2)
    majorTicks.foreach { tick =>
      // Vertical tick mark
      val px = xscale(tick.timestamp)
      g.drawLine(px, y1, px, y1 + 4)

      // Label for the tick mark
      val txt = Text(tick.label, font = Constants.smallFont, style = style)
      val txtH = Constants.smallFontDims.height
      txt.draw(g, px - 25, y1 + txtH / 2, px + 25, y1 + txtH)
    }
  }
}

object TimeAxis {
  private val minTickLabelWidth = " 00:00 ".length * Constants.smallFontDims.width
}
