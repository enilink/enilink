package net.enilink.lift.rdfa

object Vocabulary {
  final val nsuri = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
  final val `type` = nsuri + "type"
  final val nil = nsuri + "nil"
  final val first = nsuri + "first"
  final val rest = nsuri + "rest"
  final val XMLLiteral = nsuri + "XMLLiteral"

  // TODO: split xsd out of rdf vocab?
  final val xsd = "http://www.w3.org/2001/XMLSchema#"
  final val integer = xsd + "integer"
  final val double = xsd + "double"
  final val decimal = xsd + "decimal"
  final val boolean = xsd + "boolean"
}

