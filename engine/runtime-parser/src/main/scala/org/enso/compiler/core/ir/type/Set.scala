package org.enso.compiler.core.ir
package `type`

import org.enso.compiler.core.Implicits.{ShowPassData, ToStringHelper}
import org.enso.compiler.core.{IR, Identifier}
import org.enso.compiler.core.ir.Type.Info

import java.util.UUID
import scala.jdk.FunctionConverters.enrichAsScalaFromFunction

/** IR nodes for dealing with typesets. */
sealed trait Set extends Type {

  /** @inheritdoc */
  override def mapExpressions(
    fn: java.util.function.Function[Expression, Expression]
  ): Set

  /** @inheritdoc */
  override def setLocation(location: Option[IdentifiedLocation]): Set

  /** @inheritdoc */
  override def duplicate(
    keepLocations: Boolean   = true,
    keepMetadata: Boolean    = true,
    keepDiagnostics: Boolean = true,
    keepIdentifiers: Boolean = false
  ): Set
}

object Set {

  /** The representation of a typeset member.
    *
    * @param label the member's label, if given
    * @param memberType the member's type, if given
    * @param value the member's value, if given
    * @param identifiedLocation the source location that the node corresponds to
    * @param passData the pass metadata associated with this node
    */
  sealed case class Member(
    label: Name,
    memberType: Expression,
    value: Expression,
    override val identifiedLocation: IdentifiedLocation,
    override val passData: MetadataStorage = new MetadataStorage()
  ) extends Set
      with IRKind.Primitive
      with LazyDiagnosticStorage
      with LazyId {

    /** Creates a copy of `this`.
      *
      * @param label       the member's label, if given
      * @param memberType  the member's type, if given
      * @param value       the member's value, if given
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      label: Name                          = label,
      memberType: Expression               = memberType,
      value: Expression                    = value,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: UUID @Identifier                 = id
    ): Member = {
      if (
        label != this.label
        || memberType != this.memberType
        || value != this.value
        || location != this.location
        || (passData ne this.passData)
        || diagnostics != this.diagnostics
        || id != this.id
      ) {
        val res = Member(label, memberType, value, location.orNull, passData)
        res.diagnostics = diagnostics
        res.id          = id
        res
      } else this
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Member =
      copy(
        label = label.duplicate(
          keepLocations,
          keepMetadata,
          keepDiagnostics,
          keepIdentifiers
        ),
        memberType = memberType
          .duplicate(
            keepLocations,
            keepMetadata,
            keepDiagnostics,
            keepIdentifiers
          ),
        value = value.duplicate(
          keepLocations,
          keepMetadata,
          keepDiagnostics,
          keepIdentifiers
        ),
        location = if (keepLocations) location else None,
        passData =
          if (keepMetadata) passData.duplicate else new MetadataStorage(),
        diagnostics = if (keepDiagnostics) diagnosticsCopy else null,
        id          = if (keepIdentifiers) id else null
      )

    /** @inheritdoc */
    override def setLocation(location: Option[IdentifiedLocation]): Member =
      copy(location = location)

    /** @inheritdoc */
    override def mapExpressions(
      fn: java.util.function.Function[Expression, Expression]
    ): Member = {
      copy(
        label      = label.mapExpressions(fn),
        memberType = fn(memberType),
        value      = fn(value)
      )
    }

    /** String representation. */
    override def toString: String =
      s"""
         |`type`.Set.Member(
         |label = $label,
         |memberType = $memberType,
         |value = $value,
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |)
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] = List(label, memberType, value)

    /** @inheritdoc */
    override def showCode(indent: Int): String = {
      val typeString  = s" : ${memberType.showCode(indent)}"
      val valueString = s" = ${value.showCode(indent)}"
      s"(${label.showCode(indent)}$typeString$valueString)"
    }
  }

  object Member extends Info {
    override val name: String = "_ : _ = _"
  }

  /** The typeset subsumption judgement `<:`.
    *
    * @param left the left operand
    * @param right the right operand
    * @param identifiedLocation the source location that the node corresponds to
    * @param passData the pass metadata associated with this node
    */
  sealed case class Subsumption(
    left: Expression,
    right: Expression,
    override val identifiedLocation: IdentifiedLocation,
    override val passData: MetadataStorage = new MetadataStorage()
  ) extends Set
      with IRKind.Primitive
      with LazyDiagnosticStorage
      with LazyId {

    /** Creates a copy of `this`.
      *
      * @param left        the left operand
      * @param right       the right operand
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      left: Expression                     = left,
      right: Expression                    = right,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: UUID @Identifier                 = id
    ): Subsumption = {
      if (
        left != this.left
        || right != this.right
        || location != this.location
        || (passData ne this.passData)
        || diagnostics != this.diagnostics
        || id != this.id
      ) {
        val res = Subsumption(left, right, location.orNull, passData)
        res.diagnostics = diagnostics
        res.id          = id
        res
      } else this
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Subsumption =
      copy(
        left = left.duplicate(
          keepLocations,
          keepMetadata,
          keepDiagnostics,
          keepIdentifiers
        ),
        right = right.duplicate(
          keepLocations,
          keepMetadata,
          keepDiagnostics,
          keepIdentifiers
        ),
        location = if (keepLocations) location else None,
        passData =
          if (keepMetadata) passData.duplicate else new MetadataStorage(),
        diagnostics = if (keepDiagnostics) diagnosticsCopy else null,
        id          = if (keepIdentifiers) id else null
      )

    /** @inheritdoc */
    override def setLocation(
      location: Option[IdentifiedLocation]
    ): Subsumption = copy(location = location)

    /** @inheritdoc */
    override def mapExpressions(
      fn: java.util.function.Function[Expression, Expression]
    ): Subsumption = {
      copy(left = fn(left), right = fn(right))
    }

    /** String representation. */
    override def toString: String =
      s"""
         |`type`.Set.Subsumption(
         |left = $left,
         |right = $right,
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] = List(left, right)

    /** @inheritdoc */
    override def showCode(indent: Int): String =
      s"(${left.showCode(indent)} <: ${right.showCode(indent)})"
  }

  object Subsumption extends Info {
    override val name: String = "<:"
  }

  /** The typeset equality judgement `~`.
    *
    * @param left the left operand
    * @param right the right operand
    * @param identifiedLocation the source location that the node corresponds to
    * @param passData the pass metadata associated with this node
    */
  sealed case class Equality(
    left: Expression,
    right: Expression,
    override val identifiedLocation: IdentifiedLocation,
    override val passData: MetadataStorage = new MetadataStorage()
  ) extends Set
      with IRKind.Primitive
      with LazyDiagnosticStorage
      with LazyId {

    /** Creates a copy of `this`.
      *
      * @param left        the left operand
      * @param right       the right operand
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      left: Expression                     = left,
      right: Expression                    = right,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: UUID @Identifier                 = id
    ): Equality = {
      if (
        left != this.left
        || right != this.right
        || location != this.location
        || (passData ne this.passData)
        || diagnostics != this.diagnostics
        || id != this.id
      ) {
        val res = Equality(left, right, location.orNull, passData)
        res.diagnostics = diagnostics
        res.id          = id
        res
      } else this
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Equality =
      copy(
        left = left.duplicate(
          keepLocations,
          keepMetadata,
          keepDiagnostics,
          keepIdentifiers
        ),
        right = right.duplicate(
          keepLocations,
          keepMetadata,
          keepDiagnostics,
          keepIdentifiers
        ),
        location = if (keepLocations) location else None,
        passData =
          if (keepMetadata) passData.duplicate else new MetadataStorage(),
        diagnostics = if (keepDiagnostics) diagnosticsCopy else null,
        id          = if (keepIdentifiers) id else null
      )

    /** @inheritdoc */
    override def setLocation(
      location: Option[IdentifiedLocation]
    ): Equality = copy(location = location)

    /** @inheritdoc */
    override def mapExpressions(
      fn: java.util.function.Function[Expression, Expression]
    ): Equality = {
      copy(left = fn(left), right = fn(right))
    }

    /** String representation. */
    override def toString: String =
      s"""
         |`type`.Set.Equality(
         |left = $left,
         |right = $right,
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] = List(left, right)

    /** @inheritdoc */
    override def showCode(indent: Int): String =
      s"(${left.showCode(indent)} ~ ${right.showCode(indent)}"
  }

  object Equality extends Info {
    override val name: String = "~"
  }

  /** The typeset concatenation operator `,`.
    *
    * @param left the left operand
    * @param right the right operand
    * @param identifiedLocation the source location that the node corresponds to
    * @param passData the pass metadata associated with this node
    */
  sealed case class Concat(
    left: Expression,
    right: Expression,
    override val identifiedLocation: IdentifiedLocation,
    override val passData: MetadataStorage = new MetadataStorage()
  ) extends Set
      with IRKind.Primitive
      with LazyDiagnosticStorage
      with LazyId {

    /** Creates a copy of `this`.
      *
      * @param left        the left operand
      * @param right       the right operand
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      left: Expression                     = left,
      right: Expression                    = right,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: UUID @Identifier                 = id
    ): Concat = {
      if (
        left != this.left
        || right != this.right
        || location != this.location
        || (passData ne this.passData)
        || diagnostics != this.diagnostics
        || id != this.id
      ) {
        val res = Concat(left, right, location.orNull, passData)
        res.diagnostics = diagnostics
        res.id          = id
        res
      } else this
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Concat =
      copy(
        left = left.duplicate(
          keepLocations,
          keepMetadata,
          keepDiagnostics,
          keepIdentifiers
        ),
        right = right.duplicate(
          keepLocations,
          keepMetadata,
          keepDiagnostics,
          keepIdentifiers
        ),
        location = if (keepLocations) location else None,
        passData =
          if (keepMetadata) passData.duplicate else new MetadataStorage(),
        diagnostics = if (keepDiagnostics) diagnosticsCopy else null,
        id          = if (keepIdentifiers) id else null
      )

    /** @inheritdoc */
    override def setLocation(location: Option[IdentifiedLocation]): Concat =
      copy(location = location)

    /** @inheritdoc */
    override def mapExpressions(
      fn: java.util.function.Function[Expression, Expression]
    ): Concat = {
      copy(left = fn(left), right = fn(right))
    }

    /** String representation. */
    override def toString: String =
      s"""
         |`type`.Set.Concat(
         |left = $left,
         |right = $right,
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] = List(left, right)

    /** @inheritdoc */
    override def showCode(indent: Int): String =
      s"(${left.showCode(indent)}; ${right.showCode(indent)})"
  }

  object Concat extends Info {
    override val name: String = ";"
  }

  /** The typeset union operator `|`.
    *
    * @param operands the operands
    * @param identifiedLocation the source location that the node corresponds to
    * @param passData the pass metadata associated with this node
    */
  sealed case class Union(
    operands: List[Expression],
    override val identifiedLocation: IdentifiedLocation,
    override val passData: MetadataStorage = new MetadataStorage()
  ) extends Set
      with IRKind.Primitive
      with LazyDiagnosticStorage
      with LazyId {

    /** Creates a copy of `this`.
      *
      * @param operands    the list of expressions
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      operands: List[Expression]           = operands,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: UUID @Identifier                 = id
    ): Union = {
      if (
        operands != this.operands
        || location != this.location
        || (passData ne this.passData)
        || diagnostics != this.diagnostics
        || id != this.id
      ) {
        val res = Union(operands, location.orNull, passData)
        res.diagnostics = diagnostics
        res.id          = id
        res
      } else this
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Union =
      copy(
        operands = operands.map(
          _.duplicate(
            keepLocations,
            keepMetadata,
            keepDiagnostics,
            keepIdentifiers
          )
        ),
        location = if (keepLocations) location else None,
        passData =
          if (keepMetadata) passData.duplicate else new MetadataStorage(),
        diagnostics = if (keepDiagnostics) diagnosticsCopy else null,
        id          = if (keepIdentifiers) id else null
      )

    /** @inheritdoc */
    override def setLocation(location: Option[IdentifiedLocation]): Union =
      copy(location = location)

    /** @inheritdoc */
    override def mapExpressions(
      fn: java.util.function.Function[Expression, Expression]
    ): Union = {
      copy(operands = operands.map(fn.asScala))
    }

    /** String representation. */
    override def toString: String =
      s"""
         |`type`.Set.Union(
         |operands = $operands,
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] = operands.toList

    /** @inheritdoc */
    override def showCode(indent: Int): String =
      operands.map(_.showCode(indent)).toList.mkString(" | ")
  }

  object Union extends Info {
    override val name: String = "|"
  }

  /** The typeset intersection operator `&`.
    *
    * @param left the left operand
    * @param right the right operand
    * @param identifiedLocation the source location that the node corresponds to
    * @param passData the pass metadata associated with this node
    */
  sealed case class Intersection(
    left: Expression,
    right: Expression,
    override val identifiedLocation: IdentifiedLocation,
    override val passData: MetadataStorage = new MetadataStorage()
  ) extends Set
      with IRKind.Primitive
      with LazyDiagnosticStorage
      with LazyId {

    /** Creates a copy of `this`.
      *
      * @param left        the left operand
      * @param right       the right operand
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      left: Expression                     = left,
      right: Expression                    = right,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: UUID @Identifier                 = id
    ): Intersection = {
      if (
        left != this.left
        || right != this.right
        || location != this.location
        || (passData ne this.passData)
        || diagnostics != this.diagnostics
        || id != this.id
      ) {
        val res = Intersection(left, right, location.orNull, passData)
        res.diagnostics = diagnostics
        res.id          = id
        res
      } else this
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Intersection =
      copy(
        left = left.duplicate(
          keepLocations,
          keepMetadata,
          keepDiagnostics,
          keepIdentifiers
        ),
        right = right.duplicate(
          keepLocations,
          keepMetadata,
          keepDiagnostics,
          keepIdentifiers
        ),
        location = if (keepLocations) location else None,
        passData =
          if (keepMetadata) passData.duplicate else new MetadataStorage(),
        diagnostics = if (keepDiagnostics) diagnosticsCopy else null,
        id          = if (keepIdentifiers) id else null
      )

    /** @inheritdoc */
    override def setLocation(
      location: Option[IdentifiedLocation]
    ): Intersection = copy(location = location)

    /** @inheritdoc */
    override def mapExpressions(
      fn: java.util.function.Function[Expression, Expression]
    ): Intersection = {
      copy(left = fn(left), right = fn(right))
    }

    /** String representation. */
    override def toString: String =
      s"""
         |`type`.Set.Intersection(
         |left = $left,
         |right = $right,
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] = List(left, right)

    /** @inheritdoc */
    override def showCode(indent: Int): String =
      s"(${left.showCode(indent)} & ${right.showCode(indent)})"
  }

  object Intersection extends Info {
    override val name: String = "&"
  }
}
