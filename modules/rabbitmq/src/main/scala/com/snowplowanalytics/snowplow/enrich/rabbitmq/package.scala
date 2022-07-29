/*
 * Copyright (c) 2022-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich

import cats.data.NonEmptyList

import cats.effect.{Blocker, ConcurrentEffect, ContextShift}

import dev.profunktor.fs2rabbit.config.{Fs2RabbitConfig, Fs2RabbitNodeConfig}
import dev.profunktor.fs2rabbit.interpreter.RabbitClient

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Input.{RabbitMQ => InputRabbitMQ}
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Output.{RabbitMQ => OutputRabbitMQ}

package object rabbitmq {

  def mapConfig(raw: InputRabbitMQ): Fs2RabbitConfig =
    Fs2RabbitConfig(
      nodes = NonEmptyList.one(
        Fs2RabbitNodeConfig(
          host = raw.hostName,
          port = raw.portNumber
        )
      ),
      username = Some(raw.userName),
      password = Some(raw.password),
      virtualHost = raw.virtualHost,
      ssl = false,
      connectionTimeout = 3,
      requeueOnNack = false,
      requeueOnReject = false,
      internalQueueSize = Some(500),
      automaticRecovery = true,
      requestedHeartbeat = 100
    )

  def mapConfig(raw: OutputRabbitMQ): Fs2RabbitConfig =
    Fs2RabbitConfig(
      nodes = NonEmptyList.one(
        Fs2RabbitNodeConfig(
          host = raw.hostName,
          port = raw.portNumber
        )
      ),
      username = Some(raw.userName),
      password = Some(raw.password),
      virtualHost = raw.virtualHost,
      ssl = false,
      connectionTimeout = 3,
      requeueOnNack = false,
      requeueOnReject = false,
      internalQueueSize = Some(500),
      automaticRecovery = true,
      requestedHeartbeat = 100
    )

  def createClient[F[_]: ConcurrentEffect: ContextShift](
    blocker: Blocker,
    config: Fs2RabbitConfig
  ): F[RabbitClient[F]] =
    RabbitClient[F](config, blocker)
}
