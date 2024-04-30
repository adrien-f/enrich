/*
 * Copyright (c) 2022-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
 * located at https://docs.snowplow.io/limited-use-license-1.0
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.enrich.common.enrichments

import org.slf4j.LoggerFactory

import cats.Monad
import cats.data.Validated.{Invalid, Valid}
import cats.data.NonEmptyList

import cats.implicits._

import com.snowplowanalytics.snowplow.badrows.FailureDetails

import com.snowplowanalytics.snowplow.enrich.common.enrichments.AtomicFields.LimitedAtomicField
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.utils.AtomicFieldValidationError

/**
 * Atomic fields length validation inspired by
 * https://github.com/snowplow/snowplow-scala-analytics-sdk/blob/master/src/main/scala/com.snowplowanalytics.snowplow.analytics.scalasdk/validate/package.scala
 */
object AtomicFieldsLengthValidator {

  private val logger = LoggerFactory.getLogger("InvalidEnriched")

  def validate[F[_]: Monad](
    event: EnrichedEvent,
    acceptInvalid: Boolean,
    invalidCount: F[Unit],
    atomicFields: AtomicFields
  ): F[Either[FailureDetails.SchemaViolation, Unit]] =
    atomicFields.value
      .map(field => validateField(event, field).toValidatedNel)
      .combineAll match {
      case Invalid(errors) if acceptInvalid =>
        handleAcceptableErrors(invalidCount, event, errors) *> Monad[F].pure(Right(()))
      case Invalid(errors) =>
        Monad[F].pure(AtomicFields.errorsToSchemaViolation(errors).asLeft)
      case Valid(()) =>
        Monad[F].pure(Right(()))
    }

  private def validateField(
    event: EnrichedEvent,
    atomicField: LimitedAtomicField
  ): Either[AtomicFieldValidationError, Unit] = {
    val actualValue = atomicField.value.enrichedValueExtractor(event)
    if (actualValue != null && actualValue.length > atomicField.limit)
      AtomicFieldValidationError(
        s"Field is longer than maximum allowed size ${atomicField.limit}",
        atomicField.value.name,
        AtomicFieldValidationError.AtomicFieldLengthExceeded
      ).asLeft
    else
      Right(())
  }

  private def handleAcceptableErrors[F[_]: Monad](
    invalidCount: F[Unit],
    event: EnrichedEvent,
    errors: NonEmptyList[AtomicFieldValidationError]
  ): F[Unit] =
    invalidCount *>
      Monad[F].pure(
        logger.debug(
          s"Enriched event not valid against atomic schema. Event id: ${event.event_id}. Invalid fields: ${errors.map(_.field).toList.flatten.mkString(", ")}"
        )
      )

}
