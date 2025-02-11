package org.enso.compiler.core.ir
package expression
package errors

import org.enso.compiler.core.{IR, Identifier}

import java.util.UUID

/** A trait for errors about unexpected language constructs. */
sealed trait Unexpected extends Error {

  /** The unexpected construct. */
  def ir: IR

  /** The name of the unexpected entity. */
  def entity: String

  /** @inheritdoc */
  override def identifiedLocation: IdentifiedLocation =
    ir.identifiedLocation()

  /** @inheritdoc */
  override def message(source: (IdentifiedLocation => String)): String =
    s"Unexpected $entity."

  /** @inheritdoc */
  override def diagnosticKeys(): Array[Any] = Array(entity)

  /** @inheritdoc */
  override def mapExpressions(
    fn: java.util.function.Function[Expression, Expression]
  ): Unexpected

  /** @inheritdoc */
  override def setLocation(location: Option[IdentifiedLocation]): Unexpected

  /** @inheritdoc */
  override def duplicate(
    keepLocations: Boolean   = true,
    keepMetadata: Boolean    = true,
    keepDiagnostics: Boolean = true,
    keepIdentifiers: Boolean = false
  ): Unexpected
}

object Unexpected {

  /** An error representing a type signature not associated with a
    * binding of some kind.
    *
    * @param ir the erroneous signature
    * @param passData any pass metadata associated with this node
    */
  sealed case class TypeSignature(
    override val ir: IR,
    passData: MetadataStorage = new MetadataStorage()
  ) extends Unexpected
      with IRKind.Primitive
      with org.enso.compiler.core.ir.module.scope.Definition
      with LazyDiagnosticStorage
      with LazyId {
    override val entity: String = "type signature"

    /** Creates a copy of `this`.
      *
      * @param ir          the erroneous signature
      * @param passData    any pass metadata associated with this node
      * @param diagnostics any compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      ir: IR                         = ir,
      passData: MetadataStorage      = passData,
      diagnostics: DiagnosticStorage = diagnostics,
      id: UUID @Identifier           = id
    ): TypeSignature = {
      if (
        ir != this.ir
        || (passData ne this.passData)
        || diagnostics != this.diagnostics
        || id != this.id
      ) {
        val res = TypeSignature(ir, passData)
        res.diagnostics = diagnostics
        res.id          = id
        res
      } else this
    }

    /** @inheritdoc */
    override def mapExpressions(
      fn: java.util.function.Function[Expression, Expression]
    ): TypeSignature = this

    /** @inheritdoc */
    override def setLocation(
      location: Option[IdentifiedLocation]
    ): TypeSignature = this

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): TypeSignature =
      copy(
        ir = ir.duplicate(
          keepLocations,
          keepMetadata,
          keepDiagnostics,
          keepIdentifiers
        ),
        passData =
          if (keepMetadata) passData.duplicate else new MetadataStorage(),
        diagnostics = if (keepDiagnostics) diagnosticsCopy else null,
        id          = if (keepIdentifiers) id else null
      )

    /** @inheritdoc */
    override def children: List[IR] = List(ir)

    /** @inheritdoc */
    override def showCode(indent: Int): String =
      s"(Unexpected.TypeSignature ${ir.showCode(indent)})"
  }
}
