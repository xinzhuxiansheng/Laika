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

package laika.render

import laika.ast._
import laika.render.FOFormatter._

/** Default renderer implementation for the XSL-FO output format.
  *
  * @author Jens Halm
  */
class FORenderer2 (messageLevel: MessageLevel) {

  def render (fmt: FOFormatter, element: Element): String = {

    def include (msg: SystemMessage): Boolean = messageLevel <= msg.level

    def noneIfDefault [T](actual: T, default: T): Option[String] = if (actual == default) None else Some(actual.toString)

    def renderTable (table: Table): String = {
      if (table.caption.content.nonEmpty) {
        // FOP does not support fo:table-caption
        fmt.child(TitledBlock(table.caption.content, List(table.copy(caption = Caption()))))
      }
      else {
        val children = table.columns.content ++ (List(table.head, table.body) filterNot (_.content.isEmpty))
        fmt.indentedElement("fo:table", table, children)
      }
    }

    object WithFallback {
      def unapply (value: Element): Option[Element] = value match {
        case f: Fallback => Some(f.fallback)
        case _ => None
      }
    }

    def renderBlockContainer [T <: BlockContainer[T]](con: BlockContainer[T]): String = {

      def quotedBlockContent (content: Seq[Block], attr: Seq[Span]): Seq[Block] =
        if (attr.isEmpty) content
        else content :+ Paragraph(attr, Styles("attribution"))

      def figureContent (img: Span, caption: Seq[Span], legend: Seq[Block]): List[Block] =
        List(Paragraph(List(img)), Paragraph(caption, Styles("caption")), BlockSequence(legend, Styles("legend")))

      def enumLabel (format: EnumFormat, num: Int): String = {
        import EnumType._
        val pos = format.enumType match {
          case Arabic => num.toString
          case LowerAlpha => ('a' + num - 1).toChar.toString
          case UpperAlpha => ('A' + num - 1).toChar.toString
          case LowerRoman => RomanNumerals.intToRoman(num).toLowerCase
          case UpperRoman => RomanNumerals.intToRoman(num).toUpperCase
        }
        format.prefix + pos + format.suffix
      }

      def bulletLabel (format: BulletFormat): Span = format match {
        case StringBullet(_) => RawContent(Seq("fo"), "&#x2022;")
        case other           => Text(other.toString)
      }

      def replaceSpanContainers (content: Seq[Block]): Seq[Block] = content map {
        case sc: SpanContainer[_] => Paragraph(sc.content, sc.options)
        case other => other
      }

      con match {
        case RootElement(content, _)            => fmt.childPerLine(content)
        case EmbeddedRoot(content,indent,_)     => fmt.withMinIndentation(indent)(_.childPerLine(content))
        case Section(header, content,_)         => fmt.childPerLine(header +: content)
        case e @ TitledBlock(title, content, _) => fmt.blockContainer(e, Paragraph(title,Styles("title")) +: content)
        case e @ QuotedBlock(content,attr,_)    => fmt.blockContainer(e, quotedBlockContent(content,attr))

        case e @ BulletListItem(content,format,_)   => fmt.listItem(e, List(bulletLabel(format)), content)
        case e @ EnumListItem(content,format,num,_) => fmt.listItem(e, List(Text(enumLabel(format,num))), content)
        case e @ DefinitionListItem(term,defn,_)    => fmt.listItem(e, term, defn)
        case e @ ListItemBody(content,_)            => fmt.listItemBody(e, replaceSpanContainers(content))

        case e @ LineBlock(content,_)           => fmt.blockContainer(e, content)
        case e @ Figure(img,caption,legend,_)   => fmt.blockContainer(e, figureContent(img,caption,legend))

        case e @ FootnoteBody(content,_)        => fmt.indentedElement("fo:footnote-body", e, content)
        case _: Footnote                        => "" // rendered in link position
        case _: Citation                        => "" // rendered in link position

        case WithFallback(fallback)         => fmt.child(fallback)
        case c: Customizable                => c match {
          case BlockSequence(content, NoOpt) => fmt.childPerLine(content) // this case could be standalone above, but triggers a compiler bug then
          case unknown                      => fmt.blockContainer(unknown, unknown.content)
        }
        case unknown                        => fmt.blockContainer(unknown, unknown.content)
      }
    }

    def renderSpanContainer [T <: SpanContainer[T]](con: SpanContainer[T]): String = {
      def codeStyles (language: String) = if (language.isEmpty) NoOpt else Styles(language)

      con match {

        case e @ Paragraph(content,_)         => fmt.block(e, content)
        case e @ ParsedLiteralBlock(content,_)=> fmt.blockWithWS(e, content)
        case e @ CodeBlock(lang,content,_)    => fmt.blockWithWS(e.copy(options=e.options + codeStyles(lang)),content)
        case e @ Header(level, content,_)     => fmt.block(e.copy(options=e.options + Styles("level"+level.toString)),content,"keep-with-next"->"always")
        case e @ Title(content,_)             => s"""<fo:marker marker-class-name="chapter"><fo:block>""" + fmt.text(e.extractText) + "</fo:block></fo:marker>" +
          fmt.newLine +
          fmt.block(e.copy(options=e.options),content,"keep-with-next"->"always")

        case e @ Emphasized(content,_)        => fmt.inline(e, content)
        case e @ Strong(content,_)            => fmt.inline(e, content)
        case e @ Deleted(content,_)           => fmt.inline(e, content)
        case e @ Inserted(content,_)          => fmt.inline(e, content)
        case e @ Code(lang,content,_)         => fmt.inline(e.copy(options=e.options + codeStyles(lang)),content)
        case e @ Line(content,_)              => fmt.block(e, content)

        case e @ ExternalLink(content, url, _, _)     => fmt.externalLink(e, url, content)
        case e @ InternalLink(content, ref, _, _)     => fmt.internalLink(e, fmt.buildLocalId(ref), content)
        case e @ CrossLink(content, ref, path, _, _)  => fmt.internalLink(e, fmt.buildId(path.absolute, ref), content)

        case WithFallback(fallback)         => fmt.child(fallback)
        case c: Customizable                => c match {
          case SpanSequence(content, NoOpt) => fmt.children(content) // this case could be standalone above, but triggers a compiler bug then
          case unknown: Block               => fmt.block(unknown, unknown.content)
          case unknown                      => fmt.inline(unknown, unknown.content)
        }
        case unknown                        => fmt.inline(unknown, unknown.content)
      }
    }

    def renderTemplateSpanContainer [T <: TemplateSpanContainer[T]](con: TemplateSpanContainer[T]): String = {
      con match {
        case TemplateRoot(content, NoOpt)         => fmt.children(content)
        case TemplateSpanSequence(content, NoOpt) => fmt.children(content)
        case unknown                              => fmt.inline(unknown, unknown.content)
      }
    }

    def renderListContainer [T <: ListContainer[T]](con: ListContainer[T]): String = con match {
      case e @ EnumList(content,_,_,_)   => fmt.listBlock(e, content)
      case e @ BulletList(content,_,_)   => fmt.listBlock(e, content)
      case e @ DefinitionList(content,_) => fmt.listBlock(e, content)

      case WithFallback(fallback)      => fmt.child(fallback)
      case c: Customizable             => fmt.listBlock(c, c.content)
      case unknown                     => fmt.listBlock(unknown, unknown.content)
    }

    def renderTextContainer (con: TextContainer): String = con match {
      case e @ Text(content,_)           => fmt.text(e, content)
      case e @ TemplateString(content,_) => fmt.rawText(e, content)
      case e @ RawContent(formats, content, _) => if (formats.contains("fo")) fmt.rawText(e, content) else ""
      case e @ Literal(content,_)        => fmt.textWithWS(e, content)
      case e @ LiteralBlock(content,_)   => fmt.textBlockWithWS(e, content)
      case e: BookmarkTitle              => fmt.bookmarkTitle(e)
      case Comment(content,_)            => fmt.comment(content)

      case WithFallback(fallback)      => fmt.child(fallback)
      //case c: Customizable if c.options == NoOpt => fmt.text(c.content) // TODO - 0.12 - is this needed?
      case c: Customizable             => fmt.text(c, c.content)
      case unknown                     => fmt.text(unknown.content)
    }

    def renderSimpleBlock (block: Block): String = block match {
      case e @ ListItemLabel(content,_) => fmt.listItemLabel(e, content)
      case e: Rule                      => fmt.textElement("fo:leader", e, "", "leader-pattern"->"rule")
      case e: InternalLinkTarget        => fmt.internalLinkTarget(e)
      case e: BookmarkTree              => fmt.bookmarkTree(e)
      case e: Bookmark                  => fmt.bookmark(e)
      case e: PageBreak                 => fmt.block(e)
      case TargetFormat("xslfo",e,_)    => fmt.child(e)

      case WithFallback(fallback)       => fmt.child(fallback)
      case _                            => ""
    }

    def renderSimpleSpan (span: Span): String = span match {
      case e @ CitationLink(ref,label,_)  => fmt.withCitation(ref)(c => fmt.footnote(e,label,c.content,c.options))
      case e @ FootnoteLink(ref,label,_)  => fmt.withFootnote(ref)(f => fmt.footnote(e,label,f.content,f.options))
      case SectionNumber(pos, opt)        => fmt.child(Text(pos.mkString(".") + " ", opt + Styles("sectionNumber")))
      case e @ Image(_,uri,width,height,_,_) => fmt.externalGraphic(e, uri.localRef.fold(uri.uri)(_.absolute.toString), width, height) // TODO - ignoring title for now
      case e: Leader                      => fmt.textElement("fo:leader", e, "", "leader-pattern"->"dots")
      case e @ PageNumberCitation(ref,path,_) => s"""<fo:page-number-citation ref-id="${fmt.buildId(path.absolute, ref)}" />"""
      case LineBreak(_)                   => "&#x2028;"
      case TemplateElement(elem,indent,_) => fmt.withMinIndentation(indent)(_.child(elem))

      case WithFallback(fallback)         => fmt.child(fallback)
      case _                              => ""
    }

    def renderTableElement (elem: TableElement): String = elem match {
      case e @ TableHead(rows,_)   => fmt.indentedElement("fo:table-header", e, rows)
      case e @ TableBody(rows,_)   => fmt.indentedElement("fo:table-body", e, rows)
      case Caption(_,_)            => "" // replaced by Table renderer
      case Columns(_,_)            => "" // replaced by Table renderer
      case e: Column               => fmt.emptyElement("fo:table-column", e)
      case e @ Row(cells,_)        => fmt.indentedElement("fo:table-row", e, cells)
      case e @ Cell(_, content, colspan, rowspan, _) => 
        fmt.indentedElement("fo:table-cell", e, content,
          "number-columns-spanned" -> noneIfDefault(colspan,1),
          "number-rows-spanned"    -> noneIfDefault(rowspan,1))
    }

    def renderUnresolvedReference (ref: Reference): String = {
      fmt.child(InvalidElement(s"unresolved reference: $ref", ref.source).asSpan)
    }

    def renderInvalidElement (elem: Invalid[_ <: Element]): String = elem match {
      case InvalidBlock(msg, fallback, opt) => 
        if (include(msg)) fmt.children(List(Paragraph(List(msg),opt), fallback))
        else fmt.child(fallback)
      case e => 
        if (include(e.message)) e.message + " " + fmt.child(e.fallback)
        else fmt.child(e.fallback)
    }

    def renderSystemMessage (message: SystemMessage): String = {
      if (include(message))
        fmt.text(message.copy(options=message.options + Styles(message.level.toString.toLowerCase)), message.content)
      else ""
    }

    element match {
      case e: SystemMessage       => renderSystemMessage(e)
      case e: Table               => renderTable(e)
      case e: TableElement        => renderTableElement(e)
      case e: Reference           => renderUnresolvedReference(e)
      case e: Invalid[_]          => renderInvalidElement(e)
      case e: BlockContainer[_]   => renderBlockContainer(e)
      case e: SpanContainer[_]    => renderSpanContainer(e)
      case e: ListContainer[_]    => renderListContainer(e)
      case e: TextContainer       => renderTextContainer(e)
      case e: TemplateSpanContainer[_] => renderTemplateSpanContainer(e)
      case e: Block               => renderSimpleBlock(e)
      case e: Span                => renderSimpleSpan(e)

      case _                      => ""
    }
  }

}