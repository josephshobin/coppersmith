//
// Copyright 2016 Commonwealth Bank of Australia
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//        http://www.apache.org/licenses/LICENSE-2.0
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//

package commbank.coppersmith.scalding

import scala.collection.JavaConversions._

import com.twitter.scalding.TypedPipe
import com.twitter.algebird.Aggregator

import uk.org.lidalia.slf4jtest.TestLoggerFactory
import uk.org.lidalia.slf4jtest.LoggingEvent.{info, warn}

import au.com.cba.omnia.thermometer.core._

import CoppersmithStats.fromTypedPipe

object CoppersmithStatsSpec extends ThermometerSpec { def is = s2"""
  CoppersmithStats
    should log stats in a sensible order    $orderedLogs
    should log (at least some) zero stats   $zeros
    behaves like this when execution fails  $failedExecution
  """

  override def before = {
    TestLoggerFactory.clearAll()
    super.before
  }

  def orderedLogs = {
    val a1 = TypedPipe.from(List(1, 2, 3, 4, 2, 1)).withCounter("a.1")
    val b1 = TypedPipe.from(List(2, 2, 4, 4, 5)).withCounter("b.1").groupBy((x: Int) => x)
    val a2 = a1.filter(_ > 1).withCounter("a.2")  // result is List(2, 3, 4, 2)
    val c  = a2.groupBy((x: Int) => x).join(b1).aggregate(Aggregator.size).toTypedPipe.withCounter("c")

    val exec = CoppersmithStats.logCountersAfter(c.toIterableExecution)
    executesSuccessfully(exec) must containTheSameElementsAs(List((2, 4), (4, 2)))

    val logger = TestLoggerFactory.getTestLogger("commbank.coppersmith.scalding.CoppersmithStats")
    logger.getAllLoggingEvents().toList must_== List(
      // Logically, a.2 follows directly after a.1, even though the call to withCounter happened after b.1
      info("Coppersmith counters:"                        ),
      info("    a.1                                     6"),
      info("    a.2                                     4"),
      info("    b.1                                     5"),
      info("    c                                       2")
    )
  }

  def zeros = {
    // Same example logic as orderedLogs test, but everything except a1 is an empty pipe.
    val a1 = TypedPipe.from(List(1, 2, 3, 4, 2, 1)).withCounter("a.1")
    val b1 = TypedPipe.empty.withCounter("b.1").groupBy((x: Int) => x)
    val a2 = a1.filter(_ > 10).withCounter("a.2")
    val c  = a2.groupBy((x: Int) => x).join(b1).aggregate(Aggregator.size).toTypedPipe.withCounter("c")

    val exec = CoppersmithStats.logCountersAfter(c.toIterableExecution)
    executesSuccessfully(exec) must be empty

    val logger = TestLoggerFactory.getTestLogger("commbank.coppersmith.scalding.CoppersmithStats")
    logger.getAllLoggingEvents().toList must_== List(
      info("Coppersmith counters:"                        ),
      info("    a.1                                     6"),
      info("    a.2                                     0"),
      info("    b.1                                     0")
      // Note that 'c' is not logged. I suspect this is because the mapper creates no files,
      // and so no reducer is even initialised. Unfortunate, but as long as the last reported
      // count is zero, it should be obvious to the user why the subsequent ones are missing.
    )
  }

  def failedExecution = {
    // simulate a failure after partial processing (on the fourth element of the pipe)
    val a = TypedPipe.from(List(1, 2, 3, 4, 5)).withCounter("a")
    val b = a.map{ (x: Int) => if (x > 3) throw new Exception else x }.withCounter("b")

    val exec = CoppersmithStats.logCountersAfter(b.toIterableExecution)
    execute(exec) must beFailedTry

    val logger = TestLoggerFactory.getTestLogger("commbank.coppersmith.scalding.CoppersmithStats")
    logger.getAllLoggingEvents().toList must_== List(
      // TODO: This shows the current behaviour, but is not what we *actually* want.
      // After all, seeing the partial counts could be very useful for debugging.
      warn("Hadoop counters not available (usually caused by job failure)")
    )
  }
}
