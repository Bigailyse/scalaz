package scalaz

/**
 * Represents either:
 *  - `Success(a)`, or
 *  - `Failure(e)`.
 *
 * Isomorphic to `scala.Either` and `scalaz.\/`. The motivation for a `Validation` is to provide the instance
 * `Applicative[[a]Validation[E, a]]` that accumulate failures through a [[scalaz.Semigroup]]`[E]`.
 *
 * [[scalaz.NonEmptyList]] is commonly chosen as a type constructor for the type `E`. As a convenience,
 * an alias `scalaz.ValidationNel[E]` is provided as a shorthand for `scalaz.Validation[NonEmptyList[E]]`,
 * and a method `Validation#toValidationNel` converts `Validation[E]` to `ValidationNel[E]`.
 *
 * Example:
 * {{{
 * import scalaz._, std.AllInstances._
 *
 * def parseInt(s: String): Validation[String, Int] =
 *   try { Success(s.toInt) } catch { case ex: NumberFormatException => Failure(ex.getMessage) }
 * val V = Applicative[ValidationNel[String, *]]
 *
 * val x: ValidationNel[String, Int] =
 *   V.apply2(parseInt("1.x").toValidationNel, parseInt("1..0").toValidationNel)(_ * _)
 *   // Failure(NonEmptyList(For input string: "1..0", For input string: "1.x"))
 * }}}
 *
 * @tparam E The type of the `Failure`
 * @tparam A The type of the `Success`
 */
sealed abstract class Validation[E, A] extends Product with Serializable {

  final class SwitchingValidation[X](s: => X){
    def <<?:(fail: X): X =
      Validation.this match {
        case Failure(_) => fail
        case Success(_) => s
      }
  }

  /** If this validation is success, return the given X value, otherwise, return the X value given to the return value. */
  def :?>>[X](success: => X): SwitchingValidation[X] =
    new SwitchingValidation[X](success)

  /** If this validation is success, return `success`, otherwise, return
    * `fail`.
    */
  def foldConst[X](fail: => X, success: => X): X = this match {
    case Failure(_) => fail
    case Success(_) => success
  }

  /** Return `true` if this validation is success. */
  def isSuccess: Boolean = this match {
    case Success(_) => true
    case Failure(_) => false
  }

  /** Return `true` if this validation is failure. */
  def isFailure: Boolean = !isSuccess

  /** Catamorphism. Run the first given function if failure, otherwise, the second given function. */
  def fold[X](fail: E => X, succ: A => X): X = this match {
    case Success(x) => succ(x)
    case Failure(x) => fail(x)
  }

  /** Spin in tail-position on the success value of this validation. */
  def loopSuccess[X](success: A => X \/ Validation[E, A], failure: E => X): X =
    Validation.loopSuccess(this, success, failure)

  /** Spin in tail-position on the failure value of this validation. */
  def loopFailure[X](success: A => X, failure: E => X \/ Validation[E, A]): X =
    Validation.loopFailure(this, success, failure)

  /** Flip the failure/success values in this validation. Alias for `swap` */
  def unary_~ : Validation[A, E] =
    swap


  /** Flip the failure/success values in this validation. Alias for `unary_~` */
  def swap: Validation[A, E] =
    this match {
      case Failure(a) => Success(a)
      case Success(b) => Failure(b)
    }

  /** Run the given function on this swapped value. Alias for `~` */
  def swapped[EE, AA](k: Validation[A, E] => Validation[AA, EE]): Validation[EE, AA] =
    k(swap).swap

  /** Run the given function on this swapped value. Alias for `swapped` */
  def ~[EE, AA](k: Validation[A, E] => Validation[AA, EE]): Validation[EE, AA] =
    swapped(k)

  /** Binary functor map on this validation. */
  def bimap[C, D](f: E => C, g: A => D): Validation[C, D] =
    this match {
      case Failure(a) => Failure(f(a))
      case Success(b) => Success(g(b))
    }

  /** Run the given function on the left value. */
  def leftMap[C](f: E => C): Validation[C, A] =
    this match {
      case a @ Success(_) => a.coerceFailure
      case Failure(e) => Failure(f(e))
    }

  /** Binary functor traverse on this validation. */
  def bitraverse[G[_] : Functor, C, D](f: E => G[C], g: A => G[D]): G[Validation[C, D]] = this match {
    case Failure(a) => Functor[G].map(f(a))(Validation.failure)
    case Success(b) => Functor[G].map(g(b))(Validation.success)
  }

  /** Map on the success of this validation. */
  def map[B](f: A => B): Validation[E, B] = this match {
    case Success(a) => Success(f(a))
    case e @ Failure(_) => e.coerceSuccess
  }

  /** Traverse on the success of this validation. */
  def traverse[G[_] : Applicative, B](f: A => G[B]): G[Validation[E, B]] = this match {
    case Success(a) => Applicative[G].map(f(a))(Validation.success)
    case e @ Failure(_) => Applicative[G].point(e.coerceSuccess)
  }

  /** Run the side-effect on the success of this validation. */
  def foreach(f: A => Unit): Unit = this match {
    case Success(a) => f(a)
    case Failure(_) =>
  }

  /** Apply a function in the environment of the success of this validation, accumulating errors. */
  def ap[B](x: => Validation[E, A => B])(implicit E: Semigroup[E]): Validation[E, B] = (this, x) match {
    case (Success(a), Success(f))   => Success(f(a))
    case (e @ Failure(_), Success(_)) => e.coerceSuccess
    case (Success(_), e @ Failure(_)) => e.coerceSuccess
    case (Failure(e1), Failure(e2)) => Failure(E.append(e2, e1))
  }

  /** Fold on the success of this validation. */
  def foldRight[B](z: => B)(f: (A, => B) => B): B = this match {
    case Success(a) => f(a, z)
    case Failure(_) => z
  }

  /** Filter on the success of this validation. */
  def filter(p: A => Boolean)(implicit M: Monoid[E]): Validation[E, A] =
    this match {
      case Failure(_) => this
      case Success(e) => if(p(e)) this else Failure(M.zero)
    }

  /** Return `true` if this validation is a success value satisfying the given predicate. */
  def exists(f: A => Boolean): Boolean = this match {
    case Success(a) => f(a)
    case Failure(_) => false
  }

  /** Return `true` if this validation is a failure value or the success value satisfies the given predicate. */
  def forall(f: A => Boolean): Boolean = this match {
    case Success(a) => f(a)
    case Failure(_) => true
  }

  /** Return an empty list or a list with one element on the success of this validation. */
  def toList: List[A] =
    this match {
      case Failure(_) => Nil
      case Success(a) => a :: Nil
    }

  /** Return an empty ilist or an ilist with one element on the success of this validation. */
  def toIList[AA >: A]: IList[AA] =
    this match {
      case Failure(_) => INil()
      case Success(a) => IList(a)
    }

  /** Return an empty LazyList or LazyList with one element on the success of this validation. */
  def toLazyList: LazyList[A] =
    this match {
      case Failure(_) => LazyList()
      case Success(a) => LazyList(a)
    }

  /** Return an empty option or option with one element on the success of this validation. Useful to sweep errors under the carpet. */
  def toOption: Option[A] =
    this match {
      case Failure(_) => None
      case Success(a) => Some(a)
    }

  /** Return an empty maybe or maybe with the element on the success of this validation. Useful to sweep errors under the carpet. */
  def toMaybe[AA >: A]: Maybe[AA] =
    this match {
      case Failure(_) => Maybe.empty
      case Success(a) => Maybe.just(a)
    }

  /** Convert to a core `scala.Either` at your own peril. */
  def toEither: Either[E, A] =
    this match {
      case Success(a) => Right(a)
      case Failure(e) => Left(e)
    }

  /** Return the success value of this validation or the given default if failure. Alias for `|` */
  def getOrElse[AA >: A](x: => AA): AA =
    this match {
      case Failure(_) => x
      case Success(a) => a
    }

  /** Return the success value of this validation or the given default if failure. Alias for `getOrElse` */
  def |[AA >: A](x: => AA): AA =
    getOrElse(x)

  /** Return the success value of this validation or run the given function on the failure. */
  def valueOr[AA >: A](x: E => AA): AA =
    this match {
      case Failure(a) => x(a)
      case Success(b) => b
    }

  /** Return this if it is a success, otherwise, return the given value. Alias for `|||` */
  def orElse(x: => Validation[E, A]): Validation[E, A] =
    this match {
      case Failure(_) => x
      case Success(_) => this
    }

  /** Return this if it is a success, otherwise, return the given value. Alias for `orElse` */
  def |||(x: => Validation[E, A]): Validation[E, A] =
    orElse(x)

  /**
   * Sums up values inside validation, if both are success or failure. Returns first failure otherwise.
   * {{{
   * success(v1) +++ success(v2) → success(v1 + v2)
   * success(v1) +++ failure(v2) → failure(v2)
   * failure(v1) +++ success(v2) → failure(v1)
   * failure(v1) +++ failure(v2) → failure(v1 + v2)
   * }}}
   */
  def +++(x: => Validation[E, A])(implicit M1: Semigroup[A], M2: Semigroup[E]): Validation[E, A] =
    this match {
      case Failure(a1) => x match {
        case Failure(a2) => Failure(M2.append(a1, a2))
        case Success(b2) => this
      }
      case Success(b1) => x match {
        case b2 @ Failure(_) => b2
        case Success(b2) => Success(M1.append(b1, b2))
      }
    }

  /** Ensures that the success value of this validation satisfies the given predicate, or fails with the given value. */
  def ensure(onFailure: => E)(f: A => Boolean): Validation[E, A] =
    excepting({ case a if !f(a) => onFailure})

  /** Compare two validations values for equality. */
  def ===(x: Validation[E, A])(implicit EE: Equal[E], EA: Equal[A]): Boolean =
    this match {
      case Failure(e1) => x match {
        case Failure(e2) => EE.equal(e1, e2)
        case Success(_) => false
      }
      case Success(a1) => x match {
        case Success(a2) => EA.equal(a1, a2)
        case Failure(_) => false
      }
    }

  /** Compare two validations values for ordering. */
  def compare(x: Validation[E, A])(implicit OE: Order[E], OA: Order[A]): Ordering =
    this match {
      case Failure(e1) => x match {
        case Failure(e2) => OE.apply(e1, e2)
        case Success(_) => Ordering.LT
      }
      case Success(a1) => x match {
        case Success(a2) => OA.apply(a1, a2)
        case Failure(_) => Ordering.GT
      }
    }

  /** If `this` and `that` are both success, or both a failure, combine them with the provided `Semigroup` for each. Otherwise, return the success. Alias for `+|+` */
  def append(that: Validation[E, A])(implicit es: Semigroup[E], as: Semigroup[A]): Validation[E, A] = (this, that) match {
    case (Success(a1), Success(a2))   => Success(as.append(a1, a2))
    case (Success(_), Failure(_)) => this
    case (Failure(_), Success(_)) => that
    case (Failure(e1), Failure(e2))   => Failure(es.append(e1, e2))
  }

  /** If `this` and `that` are both success, or both a failure, combine them with the provided `Semigroup` for each. Otherwise, return the success. Alias for `append` */
  def +|+(x: Validation[E, A])(implicit es: Semigroup[E], as: Semigroup[A]): Validation[E, A] = append(x)

  /** If `this` is a success, return it; otherwise, if `that` is a success, return it; otherwise, combine the failures with the specified semigroup. */
  def findSuccess(that: => Validation[E, A])(implicit es: Semigroup[E]): Validation[E, A] = this match {
    case Failure(e) => that match {
      case Failure(e0) => Failure(es.append(e, e0))
      case success => success
    }

    case success => success
  }

  /** Wraps the failure value in a [[scalaz.NonEmptyList]] */
  def toValidationNel: ValidationNel[E, A] =
    this match {
      case a @ Success(_) => a.coerceFailure
      case Failure(e) => Failure(NonEmptyList(e))
    }

  /** Convert to a disjunction. */
  def toDisjunction: (E \/ A) =
    this match {
      case Success(a) => \/-(a)
      case Failure(e) => -\/(e)
    }

  /** Run a disjunction function and back to validation again. Alias for `@\/` */
  def disjunctioned[EE, AA](k: (E \/ A) => (EE \/ AA)): Validation[EE, AA] =
    k(toDisjunction).toValidation

  /** Run a disjunction function and back to validation again. Alias for `disjunctioned` */
  def @\/[EE, AA](k: (E \/ A) => (EE \/ AA)): Validation[EE, AA] =
    disjunctioned(k)

  /**
   * Return a Validation formed by the application of a partial function across the
   * success of this value:
   * {{{
   *   strings map (_.parseInt excepting { case i if i < 0 => new Exception(s"Int must be positive: $i") })
   * }}}
   * @since 7.0.2
   */
  def excepting(pf: PartialFunction[A, E]): Validation[E, A] = {
    import syntax.std.option._
    this match {
      case Success(s) => pf.lift(s) toFailure s
      case _          => this
    }
  }

  def andThen[EE >: E, B](f: A => Validation[EE, B]): Validation[EE, B] = fold(Failure(_), f)

}

final case class Success[E, A](a: A) extends Validation[E, A] {
  def coerceFailure[F]: Validation[F, A] = this.asInstanceOf[Validation[F, A]]
}
final case class Failure[E, A](e: E) extends Validation[E, A] {
  def coerceSuccess[B]: Validation[E, B] = this.asInstanceOf[Validation[E, B]]
}

object Validation extends ValidationInstances {

  /** Spin in tail-position on the success value of the given validation. */
  @annotation.tailrec
  final def loopSuccess[E, A, X](d: Validation[E, A], success: A => X \/ Validation[E, A], failure: E => X): X =
    d match {
      case Failure(e) => failure(e)
      case Success(a) => success(a) match {
        case -\/(x) => x
        case \/-(q) => loopSuccess(q, success, failure)
      }
    }

  /** Spin in tail-position on the failure value of the given validation. */
  @annotation.tailrec
  final def loopFailure[E, A, X](d: Validation[E, A], success: A => X, failure: E => X \/ Validation[E, A]): X =
    d match {
      case Failure(e) => failure(e) match {
        case -\/(x) => x
        case \/-(q) => loopFailure(q, success, failure)
      }
      case Success(a) => success(a)
    }

  /** Import this if you wish to use `flatMap` */
  object FlatMap {
    @inline implicit def ValidationFlatMapRequested[E, A](d: Validation[E, A]): ValidationFlatMap[E, A] =
      new ValidationFlatMap(d)
  }

  /** Construct a success validation value. */
  def success[E, A]: A => Validation[E, A] =
    Success(_)

  /** Construct a failure validation value. */
  def failure[E, A]: E => Validation[E, A] =
    Failure(_)

  /** Construct a failure validation value but specify only the `success` type param
   *
   * @example {{{
   *   val x = Validation.f[String](42)
   *   x: Validation[Int, String]
   * }}}
   */
  def f[B]: F[B] =
    new F[B]

  private[scalaz] final class F[B] private[scalaz] (private val dummy: Boolean = true) extends AnyVal {
    def apply[A](failure: A): Validation[A, B] = Failure(failure)
  }

  /** Construct a success validation value but specify only the `failure` type param
   *
   * @example {{{
   *   val x = Validation.s[String](42)
   *   x: Validation[String, Int]
   * }}}
   */
  def s[B]: S[B] =
    new S[B]

  private[scalaz] final class S[A] private[scalaz] (private val dummy: Boolean = true) extends AnyVal {
    def apply[B](success: B): Validation[A, B] = Success(success)
  }

  /** Wrap a value in a `NonEmptyList` and construct a failure validation out of it. */
  def failureNel[E, A](e: E): ValidationNel[E, A] =
    Failure(NonEmptyList(e))

  def lift[E, A](a: A)(f : A => Boolean, fail: E) : Validation[E, A] =
    if (f(a)) failure(fail) else success(a)

  def liftNel[E, A](a: A)(f : A => Boolean, fail: E) : ValidationNel[E, A] =
    lift(a)(f, fail).toValidationNel

  /** Construct a `Validation` from an `Either`. */
  def fromEither[E, A](e: Either[E, A]): Validation[E, A] =
    e.fold(failure, success)
}


sealed abstract class ValidationInstances extends ValidationInstances0 {
  type \?/[E, A] =
  Validation[E, A]
}

sealed abstract class ValidationInstances0 extends ValidationInstances1 {

  implicit def ValidationOrder[E: Order, A: Order]: Order[Validation[E, A]] = new Order[Validation[E, A]] {
    def order(f1: Validation[E, A], f2: Validation[E, A]) =
      f1 compare f2
    override def equal(f1: Validation[E, A], f2: Validation[E, A]) =
      f1 === f2
  }

  implicit def ValidationMonoid[E: Semigroup, A: Monoid]: Monoid[Validation[E, A]] =
    new Monoid[Validation[E, A]] {
      def append(a1: Validation[E, A], a2: => Validation[E, A]) =
        a1 +++ a2
      def zero: Validation[E, A] =
        Success(Monoid[A].zero)
    }

  implicit def ValidationAssociative: Associative[Validation] = new Associative[Validation] {
    override def reassociateLeft[A, B, C](f: Validation[A, Validation[B, C]]): Validation[Validation[A, B], C] =
      f.fold(
        a => Failure(Failure(a)),
        _.fold(
          b => Failure(Success(b)),
          Success(_)
        )
      )

    override def reassociateRight[A, B, C](f: Validation[Validation[A, B], C]): Validation[A, Validation[B, C]] =
      f.fold(
        _.fold(
          Failure(_),
          b => Success(Failure(b))
        ),
        c => Success(Success(c))
      )
  }
}

final class ValidationFlatMap[E, A] private[scalaz](private val self: Validation[E, A]) extends AnyVal {
  /** Bind through the success of this validation. */
  def flatMap[B](f: A => Validation[E, B]): Validation[E, B] =
    self match {
      case Success(a) => f(a)
      case e @ Failure(_) => e.coerceSuccess
    }
}

sealed abstract class ValidationInstances1 extends ValidationInstances2 {
  implicit def ValidationEqual[E: Equal, A: Equal]: Equal[Validation[E, A]] =
      new Equal[Validation[E, A]] {
        def equal(a1: Validation[E, A], a2: Validation[E, A]) =
          a1 === a2
      }

  implicit def ValidationShow[E: Show, A: Show]: Show[Validation[E, A]] = {
    import scalaz.syntax.show._
    Show.show {
      case Failure(e) => cord"Failure($e)"
      case Success(a) => cord"Success($a)"
    }
  }

  implicit def ValidationSemigroup[E: Semigroup, A: Semigroup]: Semigroup[Validation[E, A]] =
    new Semigroup[Validation[E, A]] {
      def append(a1: Validation[E, A], a2: => Validation[E, A]) =
        a1 +++ a2
    }
}

sealed abstract class ValidationInstances2 extends ValidationInstances3 {
  implicit def ValidationInstances1[L]: Traverse[Validation[L, *]] with Cozip[Validation[L, *]] with Optional[Validation[L, *]] =
    new Traverse[Validation[L, *]] with Cozip[Validation[L, *]] with Optional[Validation[L, *]] {

      override def map[A, B](fa: Validation[L, A])(f: A => B) =
        fa map f

      def traverseImpl[G[_] : Applicative, A, B](fa: Validation[L, A])(f: A => G[B]) =
        fa.traverse(f)

      override def foldRight[A, B](fa: Validation[L, A], z: => B)(f: (A, => B) => B) =
        fa.foldRight(z)(f)

      def cozip[A, B](x: Validation[L, A \/ B]) =
        x match {
          case l @ Failure(_) => -\/(l.coerceSuccess)
          case Success(e) => e match {
            case -\/(a) => -\/(Success(a))
            case \/-(b) => \/-(Success(b))
          }
        }

      def pextract[B, A](fa: Validation[L,A]): Validation[L,B] \/ A =
        fa.fold(l => -\/(Failure(l)), \/.right)
    }

  implicit def ValidationPlus[E: Semigroup]: Plus[Validation[E, *]] = new Plus[Validation[E, *]] {
    override def plus[A](a1: Validation[E, A], a2: => Validation[E, A]) =
      a1 findSuccess a2
  }
}

sealed abstract class ValidationInstances3 {
  implicit def ValidationIsCovariantRight[A]: IsCovariant[Validation[A, *]] =
    IsCovariant.force[Validation[A, *]]

  implicit def ValidationIsCovariantLeft[A]: IsCovariant[Validation[*, A]] =
    IsCovariant.force[Validation[*, A]]

  implicit val ValidationInstances0 : Bitraverse[Validation] =
    new Bitraverse[Validation] {
      override def bimap[A, B, C, D](fab: Validation[A, B])
                                    (f: A => C, g: B => D) = fab.bimap(f, g)

      def bitraverseImpl[G[_] : Applicative, A, B, C, D](fab: Validation[A, B])
                                                    (f: A => G[C], g: B => G[D]) =
        fab.bitraverse(f, g)
    }

  implicit def ValidationApplicativeError[L: Semigroup]: ApplicativeError[Validation[L, *], L] with Alt[Validation[L, *]] =
    new ApplicativeError[Validation[L, *], L] with Alt[Validation[L, *]] {
      override def map[A, B](fa: Validation[L, A])(f: A => B) =
        fa map f

      def point[A](a: => A): Validation[L, A] =
        Success(a)

      def ap[A, B](fa: => Validation[L, A])(f: => Validation[L, A => B]) =
        fa ap f

      def alt[A](a: => Validation[L, A], b: => Validation[L, A]) =
        a orElse b

      override def raiseError[A](e: L): Validation[L, A] = Failure(e)

      override def handleError[A](fa: Validation[L, A])(f: L => Validation[L, A]): Validation[L, A] = fa match {
        case x@Success(_) => x
        case Failure(e) => f(e)
      }
    }

}
