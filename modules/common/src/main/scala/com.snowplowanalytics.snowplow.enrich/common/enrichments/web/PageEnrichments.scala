/*
 * Copyright (c) 2012-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich.common
package enrichments
package web

import java.net.URI

import cats.syntax.either._

import com.snowplowanalytics.snowplow.badrows.FailureDetails

import utils.{ConversionUtils => CU}

/** Holds enrichments related to the web page URL, and the document object contained in the page. */
object PageEnrichments {

  /**
   * Extracts the page URI from either the collector's referer* or the appropriate tracker variable.
   * Tracker variable takes precedence as per #268
   * @param fromReferer The page URI reported as the referer to the collector
   * @param fromTracker The page URI reported by the tracker
   * @return either the chosen page URI, or an error, wrapped in a Validation
   */
  def extractPageUri(fromReferer: Option[String], fromTracker: Option[String]): Either[FailureDetails.EnrichmentFailure, Option[URI]] =
    ((fromReferer, fromTracker) match {
      case (Some(r), None) => CU.stringToUri(r)
      case (None, Some(t)) => CU.stringToUri(t)
      // Tracker URL takes precedence
      case (Some(_), Some(t)) => CU.stringToUri(t)
      case (None, None) => None.asRight // No page URI available. Not a failable offence
    }).leftMap(f =>
      FailureDetails.EnrichmentFailure(
        None,
        FailureDetails.EnrichmentFailureMessage.Simple(f)
      )
    )
}
