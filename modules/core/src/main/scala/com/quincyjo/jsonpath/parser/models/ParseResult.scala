/*
 * Copyright 2023 Quincy Jo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.quincyjo.jsonpath.parser.models

import cats.{Applicative, Eval, Monad, Traverse}
import com.quincyjo.jsonpath.parser.models.JsonPathParseContext.JsonPathToken

/** Models a parsed right which may have failed.
  *
  * @tparam T
  *   The type that was parsed.
  */
sealed trait ParseResult[+T] {

  /** True if this is a success, false if it is a failure.
    */
  def isSuccess: Boolean

  /** True if this is a failure, false if it is a success.
    */
  def isFailure: Boolean

  /** If this parse result is a success, keep it if the value passes the
    * $predicate, or return the result of the $orElse statement if it fails the
    * $predicate. If this parse result is a failure, then the failure is
    * returned.
    *
    * This is equivalent to:
    * {{{
    * parseResult match {
    *   case Parsed(value) if predicate(value) => Parsed(value)
    *   case Parsed(_) => orElse
    *   case error: ParseError => error
    * }}}
    * This is also equivalent to:
    * {{{
    * parseResult.flatMap { value =>
    *   if (predicate(value)) Parsed(value) else orElse
    * }
    * }}}
    * @param predicate
    *   Filter to apply to this result value.
    * @param orElse
    *   Value to return if the predicate is false.
    * @tparam B
    *   The type of the right to return if the predicate is false.
    * @return
    *   If this is a failure, this failure, otherwise this result if it passes
    *   the predicate, otherwise the provided orElse.
    */
  def filterOrElse[B >: T](
      predicate: T => Boolean,
      orElse: => ParseResult[B]
  ): ParseResult[B]

  /** Returns the result of applying $f to this parse result value if the parse
    * result is a success. Otherwise, evaluates expression `orElse`.
    *
    * This is equivalent to:
    * {{{
    * parseResult match {
    *   case Parsed(x)     => f(x)
    *   case _: ParseError => ifEmpty
    * }
    * }}}
    * This is also equivalent to:
    * {{{
    * parseResult map f getOrElse ifEmpty
    * }}}
    * @param orElse
    *   the expression to evaluate if this result is a failure.
    * @param f
    *   the function to apply if this result is a success.
    */
  def fold[B](orElse: => B)(f: T => B): B

  /** Returns this result right if this is a success, otherwise the result of
    * $default.
    *
    * This is equivalent to:
    * {{{
    * parseResult match {
    *   case Parsed(value) => value
    *   case _: ParseError => default
    * }
    * }}}
    * @param default
    *   Value to return if this result is a failure.
    * @tparam B
    *   The type of the right to return if this result is a failure.
    * @return
    *   This result if this is a success, otherwise the provided default.
    */
  def getOrElse[B >: T](default: => B): B

  /** If this result is a success, return the right, otherwise throw the error.
    * @return
    *   The right if this result is a success.
    */
  @throws[ParseError]("If this result is a ParseError.")
  def get: T
}
object ParseResult {

  implicit val monad: Monad[ParseResult] = new Monad[ParseResult]
    with Traverse[ParseResult] {

    override def pure[A](x: A): ParseResult[A] =
      Parsed(x)

    override def flatMap[A, B](
        fa: ParseResult[A]
    )(f: A => ParseResult[B]): ParseResult[B] =
      fa match {
        case Parsed(value)     => f(value)
        case error: ParseError => error
      }

    @annotation.tailrec
    override def tailRecM[A, B](
        a: A
    )(f: A => ParseResult[Either[A, B]]): ParseResult[B] = f(a) match {
      case failure: ParseError => failure
      case Parsed(Right(b))    => Parsed(b)
      case Parsed(Left(a))     => tailRecM(a)(f)
    }

    override def traverse[G[_]: Applicative, A, B](
        fa: ParseResult[A]
    )(f: A => G[B]): G[ParseResult[B]] =
      fa match {
        case Parsed(a) =>
          Applicative[G].map(f(a))(Applicative[ParseResult].pure)
        case error: ParseError =>
          Applicative[G].pure(error)
      }

    override def foldLeft[A, B](fa: ParseResult[A], b: B)(f: (B, A) => B): B =
      fa match {
        case Parsed(a)     => f(b, a)
        case _: ParseError => b
      }

    override def foldRight[A, B](fa: ParseResult[A], lb: Eval[B])(
        f: (A, Eval[B]) => Eval[B]
    ): Eval[B] =
      fa match {
        case Parsed(value) => f(value, lb)
        case _: ParseError => lb
      }
  }
}

final case class Parsed[T](value: T) extends ParseResult[T] {

  override val isSuccess: Boolean = true

  override val isFailure: Boolean = false

  override def filterOrElse[B >: T](
      predicate: T => Boolean,
      orElse: => ParseResult[B]
  ): ParseResult[B] =
    if (predicate(value)) this else orElse

  def fold[B](orElse: => B)(f: T => B): B = f(value)

  override def getOrElse[B >: T](default: => B): B = value

  @throws[ParseError]("If this result is a ParseError.")
  override def get: T = value
}

final case class ParseError(message: String, index: Int, input: String)
    extends Throwable(
      s"Failed to parse JsonPath due to '$message' at index $index in '$input'"
    )
    // with NoStackTrace
    with ParseResult[Nothing] {

  override val isSuccess: Boolean = false

  override val isFailure: Boolean = true

  override def filterOrElse[B >: Nothing](
      predicate: Nothing => Boolean,
      orElse: => ParseResult[B]
  ): ParseResult[B] = this

  def fold[B](orElse: => B)(f: Nothing => B): B = orElse

  override def getOrElse[B >: Nothing](default: => B): B = default

  @throws[ParseError]("If this result is a ParseError.")
  override def get: Nothing = throw this
}

object ParseError {

  def invalidToken(
      invalidToken: JsonPathToken,
      i: Int,
      input: String,
      validTokens: JsonPathToken*
  ): ParseError =
    new ParseError(
      s"Invalid token $invalidToken at index $i, expected one of: ${validTokens.mkString(", ")}",
      index = i,
      input = input
    )

}
