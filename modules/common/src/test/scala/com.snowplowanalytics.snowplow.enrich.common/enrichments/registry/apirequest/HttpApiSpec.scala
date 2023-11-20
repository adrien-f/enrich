/*
 * Copyright (c) 2012-present Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */
package com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.apirequest

import cats.effect.IO
import cats.effect.testing.specs2.CatsIO

import org.specs2.Specification
import org.specs2.matcher.ValidatedMatchers
import org.specs2.mock.Mockito

import com.snowplowanalytics.iglu.core.{SchemaKey, SchemaVer}

import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf.ApiRequestConf
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent

import com.snowplowanalytics.snowplow.enrich.common.SpecHelpers

class HttpApiSpec extends Specification with ValidatedMatchers with Mockito with CatsIO {
  def is = s2"""
  fail to build request string without all keys $e1
  build request string from template context    $e2
  failure on failed HTTP connection             $e3
  """

  def e1 = {
    val httpApi =
      HttpApi("GET", "http://api.acme.com/{{user}}/{{ time}}/", anyInt, Authentication(None))
    val templateContext = Map("user" -> "admin")
    val request = httpApi.buildUrl(templateContext)
    request must beNone
  }

  def e2 = {
    val httpApi =
      HttpApi(
        anyString,
        "http://thishostdoesntexist31337:8123/{{  user }}/foo/{{ time}}/{{user}}",
        anyInt,
        Authentication(None)
      )

    val templateContext = Map("user" -> "admin", "time" -> "November 2015")
    val request = httpApi.buildUrl(templateContext)
    request must beSome("http://thishostdoesntexist31337:8123/admin/foo/November+2015/admin")
  }

  def e3 = {
    val schemaKey = SchemaKey("vendor", "name", "format", SchemaVer.Full(1, 0, 0))
    SpecHelpers.httpClient.use { http =>
      for {
        enrichment <- ApiRequestConf(
                        schemaKey,
                        Nil,
                        HttpApi("GET", "http://thishostdoesntexist31337:8123/endpoint", 1000, Authentication(None)),
                        List(Output("", Some(JsonOutput("")))),
                        Cache(1, 1),
                        ignoreOnError = false
                      ).enrichment[IO](http)
        event = new EnrichedEvent
        request <- enrichment.lookup(event, Nil, Nil, None)
      } yield request must beInvalid
    }
  }
}
