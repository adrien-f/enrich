/*
 * Copyright (c) 2012-present Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */
package com.snowplowanalytics.snowplow.enrich.common.utils

import org.specs2.mutable.Specification
import org.specs2.matcher.ValidatedMatchers

import cats.effect.testing.specs2.CatsIO

import io.circe.parser.parse

import cats.data.NonEmptyList

import com.snowplowanalytics.iglu.core.{SchemaKey, SchemaVer}

import com.snowplowanalytics.iglu.client.ClientError.{ResolutionError, ValidationError}

import com.snowplowanalytics.snowplow.badrows._

import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.SpecHelpers
import com.snowplowanalytics.snowplow.enrich.common.adapters.RawEvent
import com.snowplowanalytics.snowplow.enrich.common.loaders.CollectorPayload

class IgluUtilsSpec extends Specification with ValidatedMatchers with CatsIO {

  val raw = RawEvent(
    CollectorPayload.Api("vendor", "version"),
    Map.empty[String, Option[String]],
    None,
    CollectorPayload.Source("source", "enc", None),
    CollectorPayload.Context(None, None, None, None, Nil, None)
  )
  val processor = Processor("unit tests SCE", "v42")
  val enriched = new EnrichedEvent()

  val notJson = "foo"
  val notIglu = """{"foo":"bar"}"""
  val unstructSchema =
    SchemaKey(
      "com.snowplowanalytics.snowplow",
      "unstruct_event",
      "jsonschema",
      SchemaVer.Full(1, 0, 0)
    )
  val inputContextsSchema =
    SchemaKey(
      "com.snowplowanalytics.snowplow",
      "contexts",
      "jsonschema",
      SchemaVer.Full(1, 0, 0)
    )
  val emailSentSchema =
    SchemaKey(
      "com.acme",
      "email_sent",
      "jsonschema",
      SchemaVer.Full(1, 0, 0)
    )
  val supersedingExampleSchema100 =
    SchemaKey(
      "com.acme",
      "superseding_example",
      "jsonschema",
      SchemaVer.Full(1, 0, 0)
    )
  val supersedingExampleSchema101 = supersedingExampleSchema100.copy(version = SchemaVer.Full(1, 0, 1))
  val clientSessionSchema =
    SchemaKey(
      "com.snowplowanalytics.snowplow",
      "client_session",
      "jsonschema",
      SchemaVer.Full(1, 0, 1)
    )
  val emailSent1 = s"""{
    "schema": "${emailSentSchema.toSchemaUri}",
    "data": {
      "emailAddress": "hello@world.com",
      "emailAddress2": "foo@bar.org"
    }
  }"""
  val emailSent2 = s"""{
    "schema": "${emailSentSchema.toSchemaUri}",
    "data": {
      "emailAddress": "hello2@world.com",
      "emailAddress2": "foo2@bar.org"
    }
  }"""
  val invalidEmailSent = s"""{
    "schema": "${emailSentSchema.toSchemaUri}",
    "data": {
      "emailAddress": "hello@world.com"
    }
  }"""
  val supersedingExample1 =
    s"""{
    "schema": "${supersedingExampleSchema100.toSchemaUri}",
    "data": {
      "field_a": "value_a",
      "field_b": "value_b"
    }
  }"""
  val supersedingExample2 =
    s"""{
    "schema": "${supersedingExampleSchema100.toSchemaUri}",
    "data": {
      "field_a": "value_a",
      "field_b": "value_b",
      "field_d": "value_d"
    }
  }"""
  val clientSession = s"""{
    "schema": "${clientSessionSchema.toSchemaUri}",
    "data": {
      "sessionIndex": 1,
      "storageMechanism": "LOCAL_STORAGE",
      "firstEventId": "5c33fccf-6be5-4ce6-afb1-e34026a3ca75",
      "sessionId": "21c2a0dd-892d-42d1-b156-3a9d4e147eef",
      "previousSessionId": null,
      "userId": "20d631b8-7837-49df-a73e-6da73154e6fd"
    }
  }"""
  val noSchema =
    """{"schema":"iglu:com.snowplowanalytics.snowplow/foo/jsonschema/1-0-0", "data": {}}"""

  "extractAndValidateUnstructEvent" should {
    "return None if unstruct_event field is empty" >> {
      IgluUtils
        .extractAndValidateUnstructEvent(new EnrichedEvent, SpecHelpers.client)
        .map(_ must beValid(None))
    }

    "return a SchemaViolation.NotJson if unstruct_event does not contain a properly formatted JSON string" >> {
      val input = new EnrichedEvent
      input.setUnstruct_event(notJson)

      IgluUtils
        .extractAndValidateUnstructEvent(input, SpecHelpers.client)
        .map(_ must beInvalid.like {
          case _: FailureDetails.SchemaViolation.NotJson => ok
          case err => ko(s"[$err] is not NotJson")
        })
    }

    "return a SchemaViolation.NotIglu if unstruct_event contains a properly formatted JSON string that is not self-describing" >> {
      val input = new EnrichedEvent
      input.setUnstruct_event(notIglu)

      IgluUtils
        .extractAndValidateUnstructEvent(input, SpecHelpers.client)
        .map(_ must beInvalid.like {
          case _: FailureDetails.SchemaViolation.NotIglu => ok
          case err => ko(s"[$err] is not NotIglu")
        })
    }

    "return a SchemaViolation.CriterionMismatch if unstruct_event contains a self-describing JSON but not with the expected schema for unstructured events" >> {
      val input = new EnrichedEvent
      input.setUnstruct_event(noSchema)

      IgluUtils
        .extractAndValidateUnstructEvent(input, SpecHelpers.client)
        .map(_ must beInvalid.like {
          case _: FailureDetails.SchemaViolation.CriterionMismatch => ok
          case err => ko(s"[$err] is not CriterionMismatch")
        })
    }

    "return a SchemaViolation.NotJson if the JSON in .data is not a JSON" >> {
      val input = new EnrichedEvent
      input.setUnstruct_event(buildUnstruct(notJson))

      IgluUtils
        .extractAndValidateUnstructEvent(input, SpecHelpers.client)
        .map(_ must beInvalid.like {
          case _: FailureDetails.SchemaViolation.NotJson => ok
          case err => ko(s"[$err] is not NotJson")
        })
    }

    "return a SchemaViolation.IgluError containing a ValidationError if the JSON in .data is not self-describing" >> {
      val input = new EnrichedEvent
      input.setUnstruct_event(buildUnstruct(notIglu))

      IgluUtils
        .extractAndValidateUnstructEvent(input, SpecHelpers.client)
        .map(_ must beInvalid.like {
          case FailureDetails.SchemaViolation.IgluError(_, ValidationError(_, _)) => ok
          case ie: FailureDetails.SchemaViolation.IgluError =>
            ko(s"IgluError [$ie] is not ValidationError")
          case err => ko(s"[$err] is not IgluError")
        })
    }

    "return a SchemaViolation.IgluError containing a ValidationError if the JSON in .data is not a valid SDJ" >> {
      val input = new EnrichedEvent
      input.setUnstruct_event(buildUnstruct(invalidEmailSent))

      IgluUtils
        .extractAndValidateUnstructEvent(input, SpecHelpers.client)
        .map(_ must beInvalid.like {
          case FailureDetails.SchemaViolation.IgluError(_, ValidationError(_, _)) => ok
          case ie: FailureDetails.SchemaViolation.IgluError =>
            ko(s"IgluError [$ie] is not ValidationError")
          case err => ko(s"[$err] is not IgluError")
        })
    }

    "return a SchemaViolation.IgluError containing a ResolutionError if the schema of the SDJ in .data can't be resolved" >> {
      val input = new EnrichedEvent
      input.setUnstruct_event(buildUnstruct(noSchema))

      IgluUtils
        .extractAndValidateUnstructEvent(input, SpecHelpers.client)
        .map(_ must beInvalid.like {
          case FailureDetails.SchemaViolation.IgluError(_, ResolutionError(_)) => ok
          case ie: FailureDetails.SchemaViolation.IgluError =>
            ko(s"IgluError [$ie] is not ResolutionError")
          case err => ko(s"[$err] is not IgluError")
        })
    }

    "return the extracted unstructured event if .data is a valid SDJ" >> {
      val input = new EnrichedEvent
      input.setUnstruct_event(buildUnstruct(emailSent1))

      IgluUtils
        .extractAndValidateUnstructEvent(input, SpecHelpers.client)
        .map(_ must beValid.like {
          case Some(IgluUtils.SdjExtractResult(sdj, None)) if sdj.schema == emailSentSchema => ok
          case Some(s) =>
            ko(
              s"unstructured event's schema [${s.sdj.schema}] does not match expected schema [${emailSentSchema}]"
            )
          case None => ko("no unstructured event was extracted")
        })
    }

    "return the extracted unstructured event when schema is superseded by another schema" >> {
      val input1 = new EnrichedEvent
      input1.setUnstruct_event(buildUnstruct(supersedingExample1))

      val input2 = new EnrichedEvent
      input2.setUnstruct_event(buildUnstruct(supersedingExample2))

      val expectedValidationInfo = IgluUtils.ValidationInfo(supersedingExampleSchema100, supersedingExampleSchema101.version)

      IgluUtils
        .extractAndValidateUnstructEvent(input1, SpecHelpers.client)
        .map(_ must beValid.like {
          case Some(IgluUtils.SdjExtractResult(sdj, Some(`expectedValidationInfo`))) if sdj.schema == supersedingExampleSchema101 => ok
          case Some(s) =>
            ko(
              s"unstructured event's schema [${s.sdj.schema}] does not match expected schema [${supersedingExampleSchema101}]"
            )
          case None => ko("no unstructured event was extracted")
        })

      // input2 wouldn't be validated with 1-0-0. It would be validated with 1-0-1 only.
      IgluUtils
        .extractAndValidateUnstructEvent(input2, SpecHelpers.client)
        .map(_ must beValid.like {
          case Some(IgluUtils.SdjExtractResult(sdj, Some(`expectedValidationInfo`))) if sdj.schema == supersedingExampleSchema101 => ok
          case Some(s) =>
            ko(
              s"unstructured event's schema [${s.sdj.schema}] does not match expected schema [${supersedingExampleSchema101}]"
            )
          case None => ko("no unstructured event was extracted")
        })
    }
  }

  "extractAndValidateInputContexts" should {
    "return Nil if contexts field is empty" >> {
      IgluUtils
        .extractAndValidateInputContexts(new EnrichedEvent, SpecHelpers.client)
        .map(_ must beValid(Nil))
    }

    "return a SchemaViolation.NotJson if .contexts does not contain a properly formatted JSON string" >> {
      val input = new EnrichedEvent
      input.setContexts(notJson)

      IgluUtils
        .extractAndValidateInputContexts(input, SpecHelpers.client)
        .map(_ must beInvalid.like {
          case NonEmptyList(_: FailureDetails.SchemaViolation.NotJson, Nil) => ok
          case err => ko(s"[$err] is not one NotJson")
        })
    }

    "return a SchemaViolation.NotIglu if .contexts contains a properly formatted JSON string that is not self-describing" >> {
      val input = new EnrichedEvent
      input.setContexts(notIglu)

      IgluUtils
        .extractAndValidateInputContexts(input, SpecHelpers.client)
        .map(_ must beInvalid.like {
          case NonEmptyList(_: FailureDetails.SchemaViolation.NotIglu, Nil) => ok
          case err => ko(s"[$err] is not one NotIglu")
        })
    }

    "return a SchemaViolation.CriterionMismatch if .contexts contains a self-describing JSON but not with the right schema" >> {
      val input = new EnrichedEvent
      input.setContexts(noSchema)

      IgluUtils
        .extractAndValidateInputContexts(input, SpecHelpers.client)
        .map(_ must beInvalid.like {
          case NonEmptyList(_: FailureDetails.SchemaViolation.CriterionMismatch, Nil) => ok
          case err => ko(s"[$err] is not one CriterionMismatch")
        })
    }

    "return a SchemaViolation.IgluError containing a ValidationError if .data does not contain an array of JSON objects" >> {
      val input = new EnrichedEvent
      val notArrayContexts =
        s"""{"schema": "${inputContextsSchema.toSchemaUri}", "data": ${emailSent1}}"""
      input.setContexts(notArrayContexts)

      IgluUtils
        .extractAndValidateInputContexts(input, SpecHelpers.client)
        .map(_ must beInvalid.like {
          case NonEmptyList(FailureDetails.SchemaViolation.IgluError(_, ValidationError(_, _)), Nil) =>
            ok
          case NonEmptyList(ie: FailureDetails.SchemaViolation.IgluError, Nil) =>
            ko(s"IgluError [$ie] is not ValidationError")
          case err => ko(s"[$err] is not one IgluError")
        })
    }

    "return a SchemaViolation.IgluError containing a ValidationError if .data contains one invalid context" >> {
      val input = new EnrichedEvent
      input.setContexts(buildInputContexts(List(invalidEmailSent)))

      IgluUtils
        .extractAndValidateInputContexts(input, SpecHelpers.client)
        .map(_ must beInvalid.like {
          case NonEmptyList(FailureDetails.SchemaViolation.IgluError(_, ValidationError(_, _)), Nil) =>
            ok
          case NonEmptyList(ie: FailureDetails.SchemaViolation.IgluError, Nil) =>
            ko(s"IgluError [$ie] is not ValidationError")
          case err => ko(s"[$err] is not one IgluError")
        })
    }

    "return a SchemaViolation.IgluError containing a ResolutionError if .data contains one context whose schema can't be resolved" >> {
      val input = new EnrichedEvent
      input.setContexts(buildInputContexts(List(noSchema)))

      IgluUtils
        .extractAndValidateInputContexts(input, SpecHelpers.client)
        .map(_ must beInvalid.like {
          case NonEmptyList(FailureDetails.SchemaViolation.IgluError(_, ResolutionError(_)), Nil) =>
            ok
          case NonEmptyList(ie: FailureDetails.SchemaViolation.IgluError, Nil) =>
            ko(s"IgluError [$ie] is not ResolutionError")
          case err => ko(s"[$err] is not one IgluError")
        })
    }

    "return 2 expected failures for 2 invalid contexts" >> {
      val input = new EnrichedEvent
      input.setContexts(buildInputContexts(List(invalidEmailSent, noSchema)))

      IgluUtils
        .extractAndValidateInputContexts(input, SpecHelpers.client)
        .map(_ must beInvalid.like {
          case NonEmptyList(
                FailureDetails.SchemaViolation.IgluError(_, ValidationError(_, _)),
                List(FailureDetails.SchemaViolation.IgluError(_, ResolutionError(_)))
              ) =>
            ok
          case errs => ko(s"[$errs] is not one ValidationError and one ResolutionError")
        })
    }

    "return an expected failure if one context is valid and the other invalid" >> {
      val input = new EnrichedEvent
      input.setContexts(buildInputContexts(List(emailSent1, noSchema)))

      IgluUtils
        .extractAndValidateInputContexts(input, SpecHelpers.client)
        .map(_ must beInvalid.like {
          case NonEmptyList(_: FailureDetails.SchemaViolation.IgluError, Nil) => ok
          case err => ko(s"[$err] is not one IgluError")
        })
    }

    "return the extracted SDJs for 2 valid input contexts" >> {
      val input = new EnrichedEvent
      input.setContexts(buildInputContexts(List(emailSent1, emailSent2)))

      IgluUtils
        .extractAndValidateInputContexts(input, SpecHelpers.client)
        .map(_ must beValid.like {
          case sdjs if sdjs.size == 2 && sdjs.forall(i => i.sdj.schema == emailSentSchema && i.validationInfo.isEmpty) =>
            ok
          case res =>
            ko(s"[$res] are not 2 SDJs with expected schema [${emailSentSchema.toSchemaUri}]")
        })
    }

    "return the extracted SDJ for an input that has a required property set to null if the schema explicitly allows it" >> {
      val input = new EnrichedEvent
      input.setContexts(buildInputContexts(List(clientSession)))

      IgluUtils
        .extractAndValidateInputContexts(input, SpecHelpers.client)
        .map(_ must beValid.like {
          case sdj if sdj.size == 1 && sdj.forall(_.sdj.schema == clientSessionSchema) =>
            ok
          case _ =>
            ko("$.previousSessionId: is missing but it is required")
        })
    }

    "return the extracted context when schema is superseded by another schema" >> {
      val input = new EnrichedEvent
      input.setContexts(buildInputContexts(List(supersedingExample1, supersedingExample2)))

      IgluUtils
        .extractAndValidateInputContexts(input, SpecHelpers.client)
        .map(_ must beValid.like {
          case sdj if sdj.size == 2 && sdj.forall(_.sdj.schema == supersedingExampleSchema101) =>
            ok
          case _ =>
            ko("Failed to extract context when schema is superseded by another schema")
        })
    }
  }

  "validateEnrichmentsContexts" should {
    "return a BadRow.EnrichmentFailures with one expected failure for one invalid context" >> {
      val contexts = List(
        SpecHelpers.jsonStringToSDJ(invalidEmailSent).right.get
      )

      IgluUtils
        .validateEnrichmentsContexts(SpecHelpers.client, contexts, raw, processor, enriched)
        .value
        .map(_ must beLeft.like {
          case BadRow.EnrichmentFailures(_, failures, _) =>
            failures.messages match {
              case NonEmptyList(
                    FailureDetails.EnrichmentFailure(
                      _,
                      FailureDetails.EnrichmentFailureMessage.IgluError(_, ValidationError(_, _))
                    ),
                    _
                  ) =>
                ok
              case err => ko(s"bad row is EnrichmentFailures but [$err] is not one ValidationError")
            }
          case br => ko(s"bad row [$br] is not EnrichmentFailures")
        })
    }

    "return a BadRow.EnrichmentFailures 2 expected failures for 2 invalid contexts" >> {
      val contexts = List(
        SpecHelpers.jsonStringToSDJ(invalidEmailSent).right.get,
        SpecHelpers.jsonStringToSDJ(noSchema).right.get
      )

      IgluUtils
        .validateEnrichmentsContexts(SpecHelpers.client, contexts, raw, processor, enriched)
        .value
        .map(_ must beLeft.like {
          case BadRow.EnrichmentFailures(_, failures, _) =>
            failures.messages match {
              case NonEmptyList(
                    FailureDetails.EnrichmentFailure(
                      _,
                      FailureDetails.EnrichmentFailureMessage.IgluError(_, ValidationError(_, _))
                    ),
                    List(
                      FailureDetails.EnrichmentFailure(
                        _,
                        FailureDetails.EnrichmentFailureMessage.IgluError(_, ResolutionError(_))
                      )
                    )
                  ) =>
                ok
              case errs =>
                ko(
                  s"bad row is EnrichmentFailures but [$errs] is not one ValidationError and one ResolutionError"
                )
            }
          case br => ko(s"bad row [$br] is not EnrichmentFailures")
        })
    }

    "return a BadRow.EnrichmentFailures with an expected failure for 1 valid context and one invalid" >> {
      val contexts = List(
        SpecHelpers.jsonStringToSDJ(invalidEmailSent).right.get,
        SpecHelpers.jsonStringToSDJ(emailSent1).right.get
      )

      IgluUtils
        .validateEnrichmentsContexts(SpecHelpers.client, contexts, raw, processor, enriched)
        .value
        .map(_ must beLeft.like {
          case BadRow.EnrichmentFailures(_, failures, _) =>
            failures.messages match {
              case NonEmptyList(
                    FailureDetails.EnrichmentFailure(
                      _,
                      FailureDetails.EnrichmentFailureMessage.IgluError(_, ValidationError(_, _))
                    ),
                    Nil
                  ) =>
                ok
              case err => ko(s"bad row is EnrichmentFailures but [$err] is not one ValidationError")
            }
          case br => ko(s"bad row [$br] is not EnrichmentFailures")
        })
    }

    "not return any error for 2 valid contexts" >> {
      val contexts = List(
        SpecHelpers.jsonStringToSDJ(emailSent1).right.get,
        SpecHelpers.jsonStringToSDJ(emailSent2).right.get
      )

      IgluUtils
        .validateEnrichmentsContexts(SpecHelpers.client, contexts, raw, processor, enriched)
        .value
        .map(_ must beRight)
    }
  }

  "extractAndValidateInputJsons" should {
    "return a SchemaViolations containing 1 error if the input event contains an invalid unstructured event" >> {
      val input = new EnrichedEvent
      input.setUnstruct_event(buildUnstruct(invalidEmailSent))

      IgluUtils
        .extractAndValidateInputJsons(
          input,
          SpecHelpers.client,
          raw,
          processor
        )
        .value
        .map(_ must beLeft.like {
          case BadRow.SchemaViolations(_, failure, _) if failure.messages.size == 1 => ok
          case br => ko(s"bad row [$br] is not a SchemaViolations containing 1 error")
        })
    }

    "return a SchemaViolations containing 1 error if the input event contains 1 invalid context" >> {
      val input = new EnrichedEvent
      input.setContexts(buildInputContexts(List(invalidEmailSent)))

      IgluUtils
        .extractAndValidateInputJsons(
          input,
          SpecHelpers.client,
          raw,
          processor
        )
        .value
        .map(_ must beLeft.like {
          case BadRow.SchemaViolations(_, failure, _) if failure.messages.size == 1 => ok
          case br => ko(s"bad row [$br] is not a SchemaViolations containing 1 error")
        })
    }

    "return a SchemaViolations containing 2 errors if the input event contains an invalid unstructured event and 1 invalid context" >> {
      val input = new EnrichedEvent
      input.setUnstruct_event(invalidEmailSent)
      input.setContexts(buildInputContexts(List(invalidEmailSent)))

      IgluUtils
        .extractAndValidateInputJsons(
          input,
          SpecHelpers.client,
          raw,
          processor
        )
        .value
        .map(_ must beLeft.like {
          case BadRow.SchemaViolations(_, failure, _) if failure.messages.size == 2 => ok
          case br => ko(s"bad row [$br] is not a SchemaViolations containing 2 errors")
        })
    }

    "return the extracted unstructured event and the extracted input contexts if they are all valid" >> {
      val input = new EnrichedEvent
      input.setUnstruct_event(buildUnstruct(emailSent1))
      input.setContexts(buildInputContexts(List(emailSent1, emailSent2)))

      IgluUtils
        .extractAndValidateInputJsons(
          input,
          SpecHelpers.client,
          raw,
          processor
        )
        .value
        .map(_ must beRight.like {
          case IgluUtils.EventExtractResult(contexts, Some(unstructEvent), validationInfos)
              if contexts.size == 2
                && validationInfos.isEmpty
                && (unstructEvent :: contexts).forall(_.schema == emailSentSchema) =>
            ok
          case res =>
            ko(
              s"[$res] is not a list with 2 extracted contexts and an option with the extracted unstructured event"
            )
        })
    }

    "return the extracted unstructured event and the extracted input contexts when schema is superseded by another schema" >> {
      val input = new EnrichedEvent
      input.setUnstruct_event(buildUnstruct(supersedingExample1))
      input.setContexts(buildInputContexts(List(supersedingExample1, supersedingExample2)))

      val expectedValidationInfoContext = parse(
        """ {
          | "originalSchema" : "iglu:com.acme/superseding_example/jsonschema/1-0-0",
          | "validatedWith" : "1-0-1"
          |}""".stripMargin
      ).toOption.get

      IgluUtils
        .extractAndValidateInputJsons(
          input,
          SpecHelpers.client,
          raw,
          processor
        )
        .value
        .map(_ must beRight.like {
          case IgluUtils.EventExtractResult(contexts, Some(unstructEvent), List(validationInfo))
              if contexts.size == 2
                && unstructEvent.schema == supersedingExampleSchema101
                && contexts.count(_.schema == supersedingExampleSchema101) == 2
                && validationInfo.schema == IgluUtils.ValidationInfo.schemaKey
                && validationInfo.data == expectedValidationInfoContext =>
            ok
          case res =>
            ko(
              s"[$res] is not a list with 2 extracted contexts and an option with the extracted unstructured event"
            )
        })
    }
  }

  def buildUnstruct(sdj: String) =
    s"""{"schema": "${unstructSchema.toSchemaUri}", "data": $sdj}"""

  def buildInputContexts(sdjs: List[String] = List.empty[String]) =
    s"""{"schema": "${inputContextsSchema.toSchemaUri}", "data": [${sdjs.mkString(",")}]}"""
}
