package net.enilink.platform.lift.util

import net.enilink.platform.lift.selection.SelectionProvider
import net.liftweb.common.{Box, Empty, Full}
import net.liftweb.http.RequestVar
import org.eclipse.core.runtime.Platform

import scala.util.DynamicVariable
import scala.xml.{NamespaceBinding, TopScope}

case class RdfContext(subject: Any, predicate: Any, prefix: NamespaceBinding = TopScope, parent: RdfContext = null) {
  override def equals(that: Any): Boolean = that match {
    case other: RdfContext => subject == other.subject && predicate == other.predicate && prefix == other.prefix
    case _ => false
  }
  override def hashCode: Int = (if (subject != null) subject.hashCode else 0) + (if (predicate != null) predicate.hashCode else 0) + prefix.hashCode

  override def toString: String = {
    "(s = " + subject + ", p = " + predicate + ", prefix = " + prefix + ")"
  }

  def topContext: RdfContext = if (parent == null || (parent eq this)) this else parent.topContext

  def childContext(subject: Any = this.subject, predicate: Any = this.predicate, prefix: NamespaceBinding = this.prefix): RdfContext = RdfContext(subject, predicate, prefix, this)
}

object CurrentContext extends DynamicVariable[Box[RdfContext]](Empty) {
  private object request extends RequestVar[Box[RdfContext]](Empty) {
    override def logUnreadVal = false
  }

  // allows retrieve selection from a registered selection provider (e.g. from an Eclipse RAP instance)
  lazy val selectionProviders: Array[SelectionProvider] = Platform.getExtensionRegistry.getExtensionPoint("net.enilink.platform.lift.selectionProviders").getConfigurationElements.flatMap {
    element => if ("selectionProvider" == element.getName) List(element.createExecutableExtension("class").asInstanceOf[SelectionProvider]) else Nil
  }

  override def value: Box[RdfContext] = super.value match {
    case v @ Full(_) => v
    case _ => selectionProviders.view.map(_.getSelection).find(_ != null).map(RdfContext(_, null)) orElse request.get
  }

  def withSubject[T](s: Any)(thunk: => T): T = withValue(Full(RdfContext(s, null)))(thunk)

  def forRequest(value: Box[RdfContext]) : Unit = { request.set(value) }
}