/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package laika.config

import laika.config.Config.ConfigResult
import laika.ast.Path

/**
  * @author Jens Halm
  */
trait Config {

  def origin: Origin
  
  def get[T](key: Path)(implicit decoder: ConfigDecoder[T]): ConfigResult[T]
  
  def get[T](key: String)(implicit decoder: ConfigDecoder[T]): ConfigResult[T] = get[T](Key(key))
  
  def get[T](key: Path, default: => T)(implicit decoder: ConfigDecoder[T]): ConfigResult[T] =
    getOpt(key).map(_.getOrElse(default))

  def get[T](key: String, default: => T)(implicit decoder: ConfigDecoder[T]): ConfigResult[T] = get[T](Key(key), default)
  
  def getOpt[T](key: Path)(implicit decoder: ConfigDecoder[T]): ConfigResult[Option[T]] = get(key).fold(
    e => if (e.isInstanceOf[NotFound]) Right(None) else Left(e),
    r => Right(Some(r))
  )

  def getOpt[T](key: String)(implicit decoder: ConfigDecoder[T]): ConfigResult[Option[T]] = getOpt[T](Key(key))
  
  def get[T](implicit decoder: ConfigDecoder[T], defaultKey: DefaultKey[T]): ConfigResult[T] = get[T](defaultKey.value)
  
  def withValue[T](key: String, value: T)(implicit encoder: ConfigEncoder[T]) : ConfigBuilder = 
    ConfigBuilder.withFallback(this).withValue(key, value)

  def withValue[T](value: T)(implicit encoder: ConfigEncoder[T], defaultKey: DefaultKey[T]): ConfigBuilder =
    ConfigBuilder.withFallback(this).withValue(value)
  
  def withFallback(other: Config): Config
  
}

class ObjectConfig (private[laika] val root: ObjectValue,
                    val origin: Origin,
                    private[laika] val fallback: Config = EmptyConfig) extends Config {

  //  println()
  //  println("=====================================================================")
  //  println(s"  CREATING CONFIG WITH KEYS: '${root.values.map(_.key).mkString(", ")}' FROM '$origin")
  //  fallback.foreach(fb => println(s"          AND FALLBACK KEYS: '${fb.root.values.map(_.key).mkString(", ")}' FROM '${fb.origin}'"))
  //  println("=====================================================================")
  //  println()

  private def lookup(keySegments: List[String], target: ObjectValue): Option[Field] = {
    (target.values.find(_.key == keySegments.head), keySegments.tail) match {
      case (res, Nil) => res
      case (Some(Field(_, ov: ObjectValue, _)), rest) => lookup(rest, ov)
      case _ => None
    }
  }

  private def lookup(path: Path): Option[Field] =
    if (path == Path.Root) Some(Field("", root, origin)) else lookup(path.components, root).orElse {
      if (path.components.head == "config") lookup(Path(path.components.tail)) // legacy path prefix pre-0.12
      else None
    }

  def get[T](key: Path)(implicit decoder: ConfigDecoder[T]): ConfigResult[T] = {
    lookup(key).fold(fallback.get[T](key)) { field =>
      decoder(Traced(field.value, field.origin))
    }
    // TODO - 0.12 - merge objects
  }
  
  def withFallback(other: Config): Config = other match {
    case EmptyConfig => this
    case _           => new ObjectConfig(root, origin, fallback.withFallback(other))
  }
  
  override def hashCode: Int = (root, origin, fallback).hashCode

  override def equals (obj: Any): Boolean = obj match {
    case c: ObjectConfig => (c.root, c.origin, c.fallback).equals((root, origin, fallback))
    case _ => false
  }
  
}

object EmptyConfig extends Config {

  val origin: Origin = Origin.root
  
  def get[T](key: Path)(implicit decoder: ConfigDecoder[T]): ConfigResult[T] = Left(NotFound(key))

  def withFallback(other: Config): Config = other
  
}

object Config {

  type ConfigResult[T] = Either[ConfigError, T]
  
  val empty: Config = EmptyConfig
  
}
