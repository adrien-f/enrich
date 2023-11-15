/*
 * Copyright (c) 2020-2021 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.kinesis

import scala.collection.mutable.ArrayBuffer

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import cats.effect.kernel.Sync
import cats.effect.{ExitCode, IO, IOApp}

import cats.Parallel
import cats.implicits._

import fs2.aws.kinesis.CommittableRecord

import software.amazon.kinesis.exceptions.ShutdownException

import com.snowplowanalytics.snowplow.enrich.aws.S3Client

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Cloud
import com.snowplowanalytics.snowplow.enrich.common.fs2.Run

import com.snowplowanalytics.snowplow.enrich.kinesis.generated.BuildInfo

object Main extends IOApp {

  // Kinesis records must not exceed 1 MB
  private val MaxRecordSize = 1024 * 1024

  private implicit def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def run(args: List[String]): IO[ExitCode] =
    Run.run[IO, CommittableRecord](
      args,
      BuildInfo.name,
      BuildInfo.version,
      BuildInfo.description,
      DynamoDbConfig.updateCliConfig[IO],
      Source.init[IO],
      out => Sink.initAttributed(out),
      out => Sink.initAttributed(out),
      out => Sink.init(out),
      checkpoint[IO],
      _ => List(S3Client.mk[IO]),
      getPayload,
      MaxRecordSize,
      Some(Cloud.Aws),
      getRuntimeRegion
    )

  def getPayload(record: CommittableRecord): Array[Byte] = {
    val data = record.record.data
    val buffer = ArrayBuffer[Byte]()
    while (data.hasRemaining())
      buffer.append(data.get)
    buffer.toArray
  }

  /** For each shard, the record with the biggest sequence number is found, and checkpointed. */
  private def checkpoint[F[_]: Parallel: Sync](records: List[CommittableRecord]): F[Unit] =
    records
      .groupBy(_.shardId)
      .foldLeft(List.empty[CommittableRecord]) { (acc, shardRecords) =>
        shardRecords._2
          .reduceLeft[CommittableRecord] { (biggest, record) =>
            if (record.sequenceNumber > biggest.sequenceNumber)
              record
            else
              biggest
          } :: acc
      }
      .parTraverse_ { record =>
        record.checkpoint
          .recoverWith {
            case _: ShutdownException =>
              // The ShardRecordProcessor instance has been shutdown. This just means another KCL
              // worker has stolen our lease. It is expected during autoscaling of instances, and is
              // safe to ignore.
              Logger[F].warn(s"Skipping checkpointing of shard ${record.shardId} because this worker no longer owns the lease")

            case _: IllegalArgumentException if record.isLastInShard =>
              // See https://github.com/snowplow/enrich/issues/657
              // This can happen at the shard end when KCL no longer allows checkpointing of the last record in the shard.
              // We need to release the semaphore, so that fs2-aws handles checkpointing the end of the shard.
              Logger[F].warn(
                s"Checkpointing failed on last record in shard. Ignoring error and instead try checkpointing of the shard end"
              ) *>
                Sync[F].delay(record.lastRecordSemaphore.release())

            case _: IllegalArgumentException if record.lastRecordSemaphore.availablePermits === 0 =>
              // See https://github.com/snowplow/enrich/issues/657 and https://github.com/snowplow/enrich/pull/658
              // This can happen near the shard end, e.g. the penultimate batch in the shard, when KCL has already enqueued the final record in the shard to the fs2 queue.
              // We must not release the semaphore yet, because we are not ready for fs2-aws to checkpoint the end of the shard.
              // We can safely ignore the exception and move on.
              Logger[F].warn(
                s"Checkpointing failed on a record which was not the last in the shard. Meanwhile, KCL has already enqueued the final record in the shard to the fs2 queue. Ignoring error and instead continue processing towards the shard end"
              )
          }
      }
}
