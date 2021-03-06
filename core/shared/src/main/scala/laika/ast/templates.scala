/*
* Copyright 2012-2020 the original author or authors.
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

package laika.ast

import laika.config.Config.ConfigResult
import laika.config.{ASTValue, ConfigError, ConfigValue, InvalidType, Key, SimpleConfigValue}
import laika.rewrite.ReferenceResolver.CursorKeys
import laika.rewrite.TemplateRewriter

/** Represents a placeholder inline element that needs
 *  to be resolved in a rewrite step.
 *  Useful for elements that need access to the
 *  document, structure, title or configuration before
 *  being fully resolved.
 */
trait SpanResolver extends Span with Unresolved {
  def resolve (cursor: DocumentCursor): Span
}

/** Represents a placeholder block element that needs
 *  to be resolved in a rewrite step.
 *  Useful for elements that need access to the
 *  document, structure, title or configuration before
 *  being fully resolved.
 */
trait BlockResolver extends Block with Unresolved {
  def resolve (cursor: DocumentCursor): Block
}

/** Represents a reference to a value from the context
 *  of the current document. The `ref` attribute
 *  is a simple path expression in dot notation
 *  for accessing properties of an object (or keys
 *  of a Map).
 *
 *  The root elements accessible to such a reference are:
 *
 *  - `document`: the current document with all of its public properties
 *  - `parent`: the parent tree of the current document
 *  - `root`: the root tree
 *  - `config`: all configuration values for the current document,
 *    including those inherited from parent trees
 */
abstract class ContextReference[T <: Span] (ref: Key) extends SpanResolver {

  def result (value: ConfigResult[Option[ConfigValue]]): T

  def resolve (cursor: DocumentCursor): Span = {
    
    cursor.resolveReference(ref) match {
      case Right(Some(ASTValue(element: Element))) => result(Right(Some(ASTValue(TemplateRewriter.rewriteRules(cursor).rewriteElement(element)))))
      case other                                   => result(other)
    }
  }

  private def error(message: String): InvalidElement = InvalidElement(message, "${"+ref+"}")
  protected def missing: InvalidElement = error(s"Missing required reference: '$ref'")
  protected def invalid(cError: ConfigError): InvalidElement = error(s"Error resolving reference: '$ref': ${cError.message}")
  protected def invalidType(value: ConfigValue): InvalidElement = error(s"Error resolving reference: '$ref': " +
    InvalidType("AST Element or Simple Value", value).message)
}

/** A context reference specifically for use in template documents.
 */
case class TemplateContextReference (ref: Key, required: Boolean, options: Options = NoOpt) extends ContextReference[TemplateSpan](ref) with TemplateSpan {
  type Self = TemplateContextReference
  
  def result (value: ConfigResult[Option[ConfigValue]]): TemplateSpan = value match {
    case Right(Some(ASTValue(s: TemplateSpan)))        => s
    case Right(Some(ASTValue(RootElement(content,_)))) => EmbeddedRoot(content)
    case Right(Some(ASTValue(e: Element)))             => TemplateElement(e)
    case Right(Some(simple: SimpleConfigValue))        => TemplateString(simple.render)
    case Right(None) if !required                      => TemplateString("")
    case Right(None)                                   => missing.asTemplateSpan
    case Right(Some(unsupported))                      => invalidType(unsupported).asTemplateSpan
    case Left(configError)                             => invalid(configError).asTemplateSpan
  }
  def withOptions (options: Options): TemplateContextReference = copy(options = options)

  lazy val unresolvedMessage: String = s"Unresolved template context reference with key '${ref.toString}'"
}

/** A context reference specifically for use in markup documents.
 */
case class MarkupContextReference (ref: Key, required: Boolean, options: Options = NoOpt) extends ContextReference[Span](ref) {
  type Self = MarkupContextReference

  def result (value: ConfigResult[Option[ConfigValue]]): Span = value match {
    case Right(Some(ASTValue(s: Span)))         => s
    case Right(Some(ASTValue(e: Element)))      => TemplateElement(e)
    case Right(Some(simple: SimpleConfigValue)) => Text(simple.render)
    case Right(None) if !required               => Text("")
    case Right(None)                            => missing.asSpan
    case Right(Some(unsupported))               => invalidType(unsupported).asSpan
    case Left(configError)                      => invalid(configError).asSpan
  }
  def withOptions (options: Options): MarkupContextReference = copy(options = options)
  lazy val unresolvedMessage: String = s"Unresolved markup context reference with key '${ref.toString}'"
}


/** The base type for all inline elements that
 *  can be found in a template.
 */
trait TemplateSpan extends Span

/** A container of other TemplateSpan elements.
  */
trait TemplateSpanContainer extends ElementContainer[TemplateSpan] with RewritableContainer {

  type Self <: TemplateSpanContainer
  
  /** Rewrites all template span children of this container based on the specified rules.
    *
    * Concrete types are expected to support rewriting at least for all standard block, span and template span
    * elements they contain, plus optionally for any other elements that have custom support for rewriting.
    */
  def rewriteTemplateSpans (rules: RewriteRule[TemplateSpan]): Self = rewriteChildren(RewriteRules(templateRules = Seq(rules)))

  def rewriteChildren (rules: RewriteRules): Self = withContent(rules.rewriteTemplateSpans(content))

  /** Creates a copy of this instance with the specified new content.
    *
    * Implementation note: This method exists to deal with the fact that there is no polymorphic copy method
    * and trades a small bit of boilerplate for avoiding the compile time hit of using shapeless for this.
    */
  def withContent (newContent: Seq[TemplateSpan]): Self
  
}

/** Common methods for simple template span containers (without additional parameters). */
trait TemplateSpanContainerCompanion {

  type ContainerType

  /** Creates an empty instance */
  def empty: ContainerType = createSpanContainer(Nil)

  /** Create an instance only containing a single TemplateString span */
  def apply(text: String): ContainerType = createSpanContainer(Seq(TemplateString(text)))

  /** Create an instance containing a one or more spans */
  def apply(span: TemplateSpan, spans: TemplateSpan*): ContainerType = createSpanContainer(span +: spans.toList)

  protected def createSpanContainer (spans: Seq[TemplateSpan]): ContainerType
  
}

/** Wraps a generic element that otherwise could not be placed directly into
 *  a template document tree. Useful when custom tags which are placed inside
 *  a template produce non-template tree elements.
 */
case class TemplateElement (element: Element, indent: Int = 0, options: Options = NoOpt) extends TemplateSpan with ElementTraversal
                                                                                                              with RewritableContainer {
  type Self = TemplateElement
  def rewriteChildren (rules: RewriteRules): TemplateElement = copy(element = rules.rewriteElement(element))
  def withOptions (options: Options): TemplateElement = copy(options = options)
}

/** A generic container element containing a list of template spans. Can be used where a sequence
 *  of spans must be inserted in a place where a single element is required by the API.
 *  Usually renderers do not treat the container as a special element and render its children
 *  as s sub flow of the parent container.
 */
case class TemplateSpanSequence (content: Seq[TemplateSpan], options: Options = NoOpt) extends TemplateSpan with TemplateSpanContainer {
  type Self = TemplateSpanSequence
  def withContent (newContent: Seq[TemplateSpan]): TemplateSpanSequence = copy(content = newContent)
  def withOptions (options: Options): TemplateSpanSequence = copy(options = options)
}
object TemplateSpanSequence extends TemplateSpanContainerCompanion {
  type ContainerType = TemplateSpanSequence
  protected def createSpanContainer (spans: Seq[TemplateSpan]): ContainerType = TemplateSpanSequence(spans)
}

/** A simple string element, representing the parts of a template
 *  that are not detected as special markup constructs and treated as raw text.
 */
case class TemplateString (content: String, options: Options = NoOpt) extends TemplateSpan with TextContainer {
  type Self = TemplateString
  def withOptions (options: Options): TemplateString = copy(options = options)
}

/** The root element of a template document tree.
 */
case class TemplateRoot (content: Seq[TemplateSpan], options: Options = NoOpt) extends Block with TemplateSpanContainer {
  type Self = TemplateRoot
  def withContent (newContent: Seq[TemplateSpan]): TemplateRoot = copy(content = newContent)
  def withOptions (options: Options): TemplateRoot = copy(options = options)
}
/** Companion with a fallback instance for setups without a default template */
object TemplateRoot extends TemplateSpanContainerCompanion {

  type ContainerType = TemplateRoot
  protected def createSpanContainer (spans: Seq[TemplateSpan]): ContainerType = TemplateRoot(spans)

  /** A fallback instance that can be used when no user-specified template
    * is available. It simply inserts the content of the parsed markup document
    * without any surrounding decoration. */
  val fallback: TemplateRoot = TemplateRoot(TemplateContextReference(CursorKeys.documentContent, required = true))
}

/** The root element of a document tree (originating from text markup) inside a template.
 *  Usually created by a template reference like `\${cursor.currentDocument.content}`.
 */
case class EmbeddedRoot (content: Seq[Block], indent: Int = 0, options: Options = NoOpt) extends TemplateSpan with BlockContainer {
  type Self = EmbeddedRoot
  def withContent (newContent: Seq[Block]): EmbeddedRoot = copy(content = content)
  def withOptions (options: Options): EmbeddedRoot = copy(options = options)
}
object EmbeddedRoot extends BlockContainerCompanion {
  type ContainerType = EmbeddedRoot
  override protected def createBlockContainer (blocks: Seq[Block]) = EmbeddedRoot(blocks)
}
