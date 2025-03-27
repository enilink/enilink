/*
 * Copyright 2010-2011 WorldWide Conferencing, LLC
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
package net.enilink.platform.lift.html

import net.enilink.platform.lift.rdfa.RDFaUtils
import net.liftweb.common.{Box, Empty, Full}
import net.liftweb.util.Helpers
import nu.validator.htmlparser._
import org.xml.sax.{Attributes, InputSource}

import java.io.{ByteArrayInputStream, InputStream}
import scala.xml.{Elem, Node, Null, TopScope}
import scala.xml.parsing.NoBindingFactoryAdapter

/**
 * A utility that supports parsing of HTML5 file.
 * The Parser hooks up nu.validator.htmlparser
 * to
 */
trait Html5ParserWithRDFaPrefixes extends RDFaUtils {
  /**
   * Parse an InputStream as HTML5. A Full(Elem)
   * will be returned on successful parsing, otherwise
   * a Failure.
   */
  def parse(in: InputStream): Box[Elem] = {
    Helpers.tryo {
      val hp = new sax.HtmlParser(common.XmlViolationPolicy.ALLOW)
      hp.setCommentPolicy(common.XmlViolationPolicy.ALLOW)
      hp.setContentNonXmlCharPolicy(common.XmlViolationPolicy.ALLOW)
      hp.setContentSpacePolicy(common.XmlViolationPolicy.FATAL)
      hp.setNamePolicy(common.XmlViolationPolicy.ALLOW)
      val saxer = new NoBindingFactoryAdapter {
        // extracts RDFa prefix mappings (@prefix attribute) and adds them as additional namespace bindings
        override def startElement(uri: String, _localName: String, qname: String, attributes: Attributes): Unit = {
          super.startElement(uri, _localName, qname, attributes)
          val scpe = scopeStack.top
          val prefixIndex = attributes.getIndex("", "prefix")
          if (prefixIndex >= 0) {
            val prefix = attributes.getValue(prefixIndex)
            val newScpe = findPrefixMappings(prefix, scpe)
            if (newScpe ne scpe) {
              // add new mappings to the scope stack
              scopeStack.pop
              scopeStack.push(newScpe)
            }
          }
        }

        override def captureText(): Unit = {
          if (capture) {
            val text = buffer.toString()
            if (text.nonEmpty) {
              hStack.push(createText(text))
            }
          }
          buffer.setLength(0)
        }
      }

      saxer.scopeStack.push(TopScope)
      hp.setContentHandler(saxer)
      val is = new InputSource(in)
      is.setEncoding("UTF-8")
      hp.parse(is)

      saxer.scopeStack.pop

      in.close()
      saxer.rootElem match {
        case null => Empty
        case e: Elem =>
          AutoInsertedBody.unapply(e) match {
            case Some(x) => Full(x)
            case _ => Full(e)
          }
        case _ => Empty
      }
    }.flatMap(a => a)
  }

  private object AutoInsertedBody {
    def checkHead(n: Node): Boolean =
      n match {
        case e: Elem =>
          e.label == "head" && e.prefix == null &&
            e.attributes == Null &&
            e.child.isEmpty
        case _ => false
      }

    def checkBody(n: Node): Boolean =
      n match {
        case e: Elem =>
          e.label == "body" && e.prefix == null &&
            e.attributes == Null &&
            e.child.nonEmpty &&
            e.child.head.isInstanceOf[Elem]
        case _ => false
      }

    def unapply(n: Node): Option[Elem] = n match {
      case e: Elem =>
        if (e.label == "html" && e.prefix == null &&
          e.attributes == Null &&
          e.child.length == 2 &&
          checkHead(e.child.head) &&
          checkBody(e.child(1))) {
          Some(e.child(1).asInstanceOf[Elem].child.head.asInstanceOf[Elem])
        } else {
          None
        }

      case _ => None
    }
  }

  /**
   * Parse an InputStream as HTML5. A Full(Elem)
   * will be returned on successful parsing, otherwise
   * a Failure.
   */
  def parse(str: String): Box[Elem] =
    parse(new ByteArrayInputStream(str.getBytes("UTF-8")))
}