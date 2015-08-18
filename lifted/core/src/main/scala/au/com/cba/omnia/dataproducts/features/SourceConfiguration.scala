package au.com.cba.omnia.dataproducts.features

import org.apache.hadoop.fs.Path

import com.twitter.scalding.{Execution, MultipleTextLineFiles, TupleSetter, TupleConverter}
import com.twitter.scalding.typed.TypedPipe

import au.com.cba.omnia.maestro.api._

import au.com.cba.omnia.ebenezer.scrooge.ParquetScroogeSource
import au.com.cba.omnia.ebenezer.scrooge.hive.PartitionHiveParquetScroogeSource

import au.com.cba.omnia.etl.util.ParseUtils

object SourceConfiguration {
  case class PartitionPath[S, P](underlying: Partition[S, P], value: P)(implicit ev: PartitionToPath[P]) {
    def toPath = new Path(underlying.pattern.format(ev.toPathComponents((value)): _*))
  }

  case class PartitionToPath[P](toPathComponents: P => List[String])
  import shapeless.syntax.std.tuple.productTupleOps
  implicit val StringToPath       = PartitionToPath[String](List(_))
  implicit val StringTuple2ToPath = PartitionToPath[(String, String)](_.toList)
  implicit val StringTuple3ToPath = PartitionToPath[(String, String, String)](_.toList)
  implicit val StringTuple4ToPath = PartitionToPath[(String, String, String, String)](_.toList)
}

trait SourceConfiguration[S] {
  def load(conf: FeatureJobConfig[_]): TypedPipe[S]
}

case class HiveTextSource[S <: ThriftStruct : Decode, P](
  basePath:  Path,
  partition: SourceConfiguration.PartitionPath[S, P],
  delimiter: String = "|",
  filter:    S => Boolean = (_: S) => true
) extends SourceConfiguration[S] {
  def filter(f: S => Boolean): HiveTextSource[S, P] = copy(filter = (s: S) => filter(s) && f(s))
  def load(conf: FeatureJobConfig[_]) = {
    val inputPath = new Path(basePath, partition.toPath)
    // FIXME: This implementation completely ignores errors
    ParseUtils.decodeHiveTextTable[S](
      MultipleTextLineFiles(inputPath.toString), delimiter
    ).rows.filter(filter)
  }
}

case class HiveParquetSource[S <: ThriftStruct : Manifest : TupleConverter : TupleSetter, P](
  basePath:  Path,
  partition: SourceConfiguration.PartitionPath[S, P],
  filter:    S => Boolean = (_: S) => true
) extends SourceConfiguration[S] {
  def filter(f: S => Boolean): HiveParquetSource[S, P] = copy(filter = (s: S) => filter(s) && f(s))
  def load(conf: FeatureJobConfig[_]) = {
    TypedPipe.from(ParquetScroogeSource[S](new Path(basePath, partition.toPath).toString))
  }
}
