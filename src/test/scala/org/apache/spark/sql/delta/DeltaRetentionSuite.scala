/*
 * Copyright 2019 Databricks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

import java.io.File

import org.apache.spark.sql.delta.DeltaOperations.Truncate
import org.apache.spark.sql.delta.actions.{Action, AddFile, RemoveFile}
import org.apache.spark.sql.delta.util.FileNames
import org.apache.hadoop.fs.Path

import org.apache.spark.SparkConf
import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.unsafe.types.CalendarInterval
import org.apache.spark.util.ManualClock

// scalastyle:off: removeFile
class DeltaRetentionSuite extends QueryTest
  with SharedSQLContext {

  protected val testOp = Truncate()

  protected override def sparkConf: SparkConf = super.sparkConf
    // Disable the log cleanup because it runs asynchronously and causes test flakiness
    .set("spark.databricks.delta.properties.defaults.enableExpiredLogCleanup", "false")

  protected def intervalStringToMillis(str: String): Long = {
    CalendarInterval.fromString(str).milliseconds()
  }

  protected def getDeltaFiles(dir: File): Seq[File] =
    dir.listFiles().filter(_.getName.endsWith(".json"))

  protected def getCheckpointFiles(dir: File): Seq[File] =
    dir.listFiles().filter(f => FileNames.isCheckpointFile(new Path(f.getCanonicalPath)))

  protected def getLogFiles(dir: File): Seq[File] = getDeltaFiles(dir) ++ getCheckpointFiles(dir)

  test("delete expired logs") {
    withTempDir { tempDir =>
      val clock = new ManualClock(System.currentTimeMillis())
      val log = DeltaLog(spark, new Path(tempDir.getCanonicalPath), clock)
      (1 to 5).foreach { i =>
        val txn = log.startTransaction()
        val file = AddFile(i.toString, Map.empty, 1, 1, true) :: Nil
        val delete: Seq[Action] = if (i > 1) {
          RemoveFile(i - 1 toString, Some(System.currentTimeMillis()), true) :: Nil
        } else {
          Nil
        }
        txn.commit(delete ++ file, testOp)
      }

      val initialFiles = getLogFiles(tempDir)
      // Shouldn't clean up, no checkpoint, no expired files
      log.cleanUpExpiredLogs()

      assert(initialFiles === getLogFiles(tempDir))

      clock.advance(intervalStringToMillis(DeltaConfigs.LOG_RETENTION.defaultValue) +
        intervalStringToMillis("interval 1 day"))

      // Shouldn't clean up, no checkpoint, although all files have expired
      log.cleanUpExpiredLogs()
      assert(initialFiles === getLogFiles(tempDir))

      log.checkpoint()

      val expectedFiles = Seq("04.json", "04.checkpoint.parquet")
      // after checkpointing, the files should be cleared
      log.cleanUpExpiredLogs()
      val afterCleanup = getLogFiles(tempDir)
      assert(initialFiles !== afterCleanup)
      assert(expectedFiles.forall(suffix => afterCleanup.exists(_.getName.endsWith(suffix))),
        s"${afterCleanup.mkString("\n")}\n didn't contain files with suffixes: ${expectedFiles}")
    }
  }

  testQuietly("log files being already deleted shouldn't fail log deletion job") {
    withTempDir { tempDir =>
      val clock = new ManualClock(System.currentTimeMillis())
      val log = DeltaLog(spark, new Path(tempDir.getCanonicalPath), clock)

      (1 to 25).foreach { i =>
        val txn = log.startTransaction()
        val file = AddFile(i.toString, Map.empty, 1, 1, true) :: Nil
        val delete: Seq[Action] = if (i > 1) {
          RemoveFile(i - 1 toString, Some(System.currentTimeMillis()), true) :: Nil
        } else {
          Nil
        }
        val version = txn.commit(delete ++ file, testOp)
        val deltaFile = new File(FileNames.deltaFile(log.logPath, version).toUri)
        deltaFile.setLastModified(clock.getTimeMillis() + i * 10000)
        val crcFile = new File(FileNames.checksumFile(log.logPath, version).toUri)
        crcFile.setLastModified(clock.getTimeMillis() + i * 10000)
        val chk = new File(FileNames.checkpointFileSingular(log.logPath, version).toUri)
        if (chk.exists()) {
          chk.setLastModified(clock.getTimeMillis() + i * 10000)
        }
      }

      // delete some files in the middle
      getDeltaFiles(tempDir).sortBy(_.getName).slice(5, 15).foreach(_.delete())
      clock.advance(intervalStringToMillis(DeltaConfigs.LOG_RETENTION.defaultValue) +
        intervalStringToMillis("interval 1 day"))
      log.cleanUpExpiredLogs()

      val minDeltaFile =
        getDeltaFiles(tempDir).map(f => FileNames.deltaVersion(new Path(f.toString))).min
      val maxChkFile = getCheckpointFiles(tempDir).map(f =>
        FileNames.checkpointVersion(new Path(f.toString))).max

      assert(maxChkFile === minDeltaFile,
        "Delta files before the last checkpoint version should have been deleted")
      assert(getCheckpointFiles(tempDir).length === 1,
        "There should only be the last checkpoint version")
    }
  }

  testQuietly(
    "RemoveFiles persist across checkpoints as tombstones if retention time hasn't expired") {
    withTempDir { tempDir =>
      val clock = new ManualClock(System.currentTimeMillis())
      val log1 = DeltaLog(spark, new Path(tempDir.getCanonicalPath), clock)

      val txn = log1.startTransaction()
      val files1 = (1 to 10).map(f => AddFile(f.toString, Map.empty, 1, 1, true))
      txn.commit(files1, testOp)
      val txn2 = log1.startTransaction()
      val files2 = (1 to 4).map(f => RemoveFile(f.toString, Some(clock.getTimeMillis())))
      txn2.commit(files2, testOp)
      log1.checkpoint()

      DeltaLog.clearCache()
      val log2 = DeltaLog(spark, new Path(tempDir.getCanonicalPath), clock)
      assert(log2.snapshot.tombstones.count() === 4)
      assert(log2.snapshot.allFiles.count() === 6)
    }
  }

  testQuietly("RemoveFiles get deleted during checkpoint if retention time has passed") {
    withTempDir { tempDir =>
      val clock = new ManualClock(System.currentTimeMillis())
      val log1 = DeltaLog(spark, new Path(tempDir.getCanonicalPath), clock)

      val txn = log1.startTransaction()
      val files1 = (1 to 10).map(f => AddFile(f.toString, Map.empty, 1, 1, true))
      txn.commit(files1, testOp)
      val txn2 = log1.startTransaction()
      val files2 = (1 to 4).map(f => RemoveFile(f.toString, Some(clock.getTimeMillis())))
      txn2.commit(files2, testOp)

      clock.advance(
        intervalStringToMillis(DeltaConfigs.TOMBSTONE_RETENTION.defaultValue) + 1000000L)

      log1.checkpoint()

      DeltaLog.clearCache()
      val log2 = DeltaLog(spark, new Path(tempDir.getCanonicalPath), clock)
      assert(log2.snapshot.tombstones.count() === 0)
      assert(log2.snapshot.allFiles.count() === 6)
    }
  }
}
