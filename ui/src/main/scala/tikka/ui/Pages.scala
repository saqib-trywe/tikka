package tikka.ui

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.Codec

/** Every view is a URL, so any view you can see is a link you can paste to an agent. */
enum Page derives Codec.AsObject:
  /** The search view. The query lives only here and in the search box. */
  case Search(query: Option[String])
  case Issue(id: String)

  /** A project's roots, or one issue's subtree. */
  case Tree(target: String)
  case Projects

object Pages:
  private val searchRoute = Route.onlyQuery[Page.Search, Option[String]](
    encode = page => page.query.filter(_.nonEmpty),
    decode = query => Page.Search(query.filter(_.nonEmpty)),
    pattern = root ? param[String]("q").?
  )

  private val issueRoute = Route[Page.Issue, String](
    encode = page => page.id,
    decode = id => Page.Issue(id),
    pattern = root / "i" / segment[String]
  )

  private val treeRoute = Route[Page.Tree, String](
    encode = page => page.target,
    decode = target => Page.Tree(target),
    pattern = root / "tree" / segment[String]
  )

  private val projectsRoute = Route.staticPartial[Page](Page.Projects, root / "projects")

  val router: Router[Page] = Router[Page](
    routes = List(searchRoute, issueRoute, treeRoute, projectsRoute),
    getPageTitle = title,
    serializePage = page => page.asJson.noSpaces,
    deserializePage = text => decode[Page](text).getOrElse(Page.Search(None)),
    // An unknown URL lands on search rather than failing: the daemon served this page, so the app should render.
    routeFallback = _ => Page.Search(None),
    popStateEvents = windowEvents(_.onPopState),
    owner = unsafeWindowOwner
  )

  def title(page: Page): String = page match
    case Page.Search(query) => query.fold("tikka")(text => s"$text — tikka")
    case Page.Issue(id)     => s"$id — tikka"
    case Page.Tree(target)  => s"$target tree — tikka"
    case Page.Projects      => "projects — tikka"

  def link(page: Page, content: Modifier[HtmlElement]*): HtmlElement =
    a(router.navigateTo(page), content)
