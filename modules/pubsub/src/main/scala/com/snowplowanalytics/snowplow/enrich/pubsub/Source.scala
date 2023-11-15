/*
 * Copyright (c) 2019-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.pubsub

import cats.effect.kernel.Sync

import com.google.api.gax.batching.FlowControlSettings
import com.google.api.gax.batching.FlowController.LimitExceededBehavior

import com.permutive.pubsub.consumer.{ConsumerRecord, Model}
import com.permutive.pubsub.consumer.grpc.{PubsubGoogleConsumer, PubsubGoogleConsumerConfig}

import com.google.pubsub.v1.PubsubMessage

import fs2.Stream

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Input

object Source {

  def init[F[_]: Sync](
    input: Input
  ): Stream[F, ConsumerRecord[F, Array[Byte]]] =
    input match {
      case p: Input.PubSub =>
        pubSub(p)
      case i =>
        Stream.raiseError[F](new IllegalArgumentException(s"Input $i is not PubSub"))
    }

  def pubSub[F[_]: Sync](
    input: Input.PubSub
  ): Stream[F, ConsumerRecord[F, Array[Byte]]] = {
    val onFailedTerminate: Throwable => F[Unit] =
      e => Sync[F].delay(System.err.println(s"Cannot terminate ${e.getMessage}"))

    val flowControlSettings: FlowControlSettings =
      FlowControlSettings
        .newBuilder()
        .setMaxOutstandingElementCount(input.maxQueueSize)
        .setMaxOutstandingRequestBytes(input.maxRequestBytes)
        .setLimitExceededBehavior(LimitExceededBehavior.Block)
        .build()

    val pubSubConfig =
      PubsubGoogleConsumerConfig(
        onFailedTerminate = onFailedTerminate,
        parallelPullCount = input.parallelPullCount,
        maxQueueSize = input.maxQueueSize,
        maxAckExtensionPeriod = input.maxAckExtensionPeriod,
        customizeSubscriber = Some {
          _.setFlowControlSettings(flowControlSettings)
            .setHeaderProvider(Utils.createPubsubUserAgentHeader(input.gcpUserAgent))
        }
      )

    val projectId = Model.ProjectId(input.project)
    val subscriptionId = Model.Subscription(input.name)
    val errorHandler: (PubsubMessage, Throwable, F[Unit], F[Unit]) => F[Unit] = // Should be useless
      (message, error, _, _) =>
        Sync[F].delay(System.err.println(s"Cannot decode message ${message.getMessageId} into array of bytes. ${error.getMessage}"))

    PubsubGoogleConsumer
      .subscribe[F, Array[Byte]](projectId, subscriptionId, errorHandler, pubSubConfig)
  }
}
