package controllers

import lila.app._
import views._
import game.Pov
import tournament.{ Created, Started, Finished }
import http.Context
import core.Futuristic.ioToFuture

import scalaz.effects._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import play.api.templates.Html
import play.api.libs.concurrent.Execution.Implicits._

object Tournament extends LilaController {

  private def repo = env.tournament.repo
  private def forms = env.tournament.forms
  private def api = env.tournament.api
  private def socket = env.tournament.socket
  private def messenger = env.tournament.messenger
  private def userRepo = env.user.userRepo
  private def gameRepo = env.game.gameRepo

  val home = Open { implicit ctx ⇒
    Async {
      futureTournaments zip userRepo.sortedByToints(10).toFuture map {
        case (((created, started), finished), leaderboard) ⇒
          Ok(html.tournament.home(created, started, finished, leaderboard))
      } 
    }
  }

  val faq = Open { implicit ctx ⇒ Ok(html.tournament.faqPage()) }

  val homeReload = Open { implicit ctx ⇒
    Async {
      futureTournaments map {
        case ((created, started), finished) ⇒
          Ok(html.tournament.homeInner(created, started, finished))
      } 
    }
  }

  private def futureTournaments =
    repo.created.toFuture zip repo.started.toFuture zip repo.finished(20).toFuture

  def show(id: String) = Open { implicit ctx ⇒
    IOResult(for {
      t ← repo byId id
      res ← t match {
        case Some(tour: Created)  ⇒ showCreated(tour) map { Ok(_) }
        case Some(tour: Started)  ⇒ showStarted(tour) map { Ok(_) } 
        case Some(tour: Finished) ⇒ showFinished(tour) map { Ok(_) }
        case _                    ⇒ io(NotFound(html.tournament.notFound()))
      }
    } yield res)
  }

  private def showCreated(tour: Created)(implicit ctx: Context) = for {
    roomHtml ← messenger render tour
  } yield html.tournament.show.created(
    tour = tour,
    roomHtml = Html(roomHtml),
    version = version(tour.id))

  private def showStarted(tour: Started)(implicit ctx: Context) = for {
    roomHtml ← messenger render tour
    games ← gameRepo games (tour recentGameIds 4)
    pov ← tour.userCurrentPov(ctx.me).fold(io(none[Pov]))(gameRepo.pov)
  } yield html.tournament.show.started(
    tour = tour,
    roomHtml = Html(roomHtml),
    version = version(tour.id),
    games = games,
    pov = pov)

  private def showFinished(tour: Finished)(implicit ctx: Context) = for {
    roomHtml ← messenger render tour
    games ← gameRepo games (tour recentGameIds 4)
  } yield html.tournament.show.finished(
    tour = tour,
    roomHtml = Html(roomHtml),
    version = version(tour.id),
    games = games)

  def join(id: String) = AuthBody { implicit ctx ⇒
    implicit me ⇒
      NoEngine {
        IOptionIORedirect(repo createdById id) { tour ⇒
          api.join(tour, me).fold(
            err ⇒ putStrLn(err.shows) map (_ ⇒ routes.Tournament.home()),
            res ⇒ res map (_ ⇒ routes.Tournament.show(tour.id))
          )
        }
      }
  }

  def withdraw(id: String) = Auth { implicit ctx ⇒
    implicit me ⇒
      IOptionIORedirect(repo byId id) { tour ⇒
        api.withdraw(tour, me.id) inject routes.Tournament.show(tour.id)
      }
  }

  def earlyStart(id: String) = Auth { implicit ctx ⇒
    implicit me ⇒
      IOptionIORedirect(repo.createdByIdAndCreator(id, me.id)) { tour ⇒
        ~api.earlyStart(tour) inject routes.Tournament.show(tour.id)
      }
  }

  def reload(id: String) = Open { implicit ctx ⇒
    IOptionIOk(repo byId id) {
      case tour: Created  ⇒ reloadCreated(tour)
      case tour: Started  ⇒ reloadStarted(tour)
      case tour: Finished ⇒ reloadFinished(tour)
    }
  }

  private def reloadCreated(tour: Created)(implicit ctx: Context) = io {
    val inner = html.tournament.show.createdInner(tour)
    html.tournament.show.inner(none)(inner)
  }

  private def reloadStarted(tour: Started)(implicit ctx: Context) = for {
    games ← gameRepo games (tour recentGameIds 4)
    pov ← tour.userCurrentPov(ctx.me).fold(io(none[Pov]))(gameRepo.pov)
  } yield {
    val pairings = html.tournament.pairings(tour)
    val inner = html.tournament.show.startedInner(tour, games, pov)
    html.tournament.show.inner(pairings.some)(inner)
  }

  private def reloadFinished(tour: Finished)(implicit ctx: Context) =
    gameRepo games (tour recentGameIds 4) map { games ⇒
      val pairings = html.tournament.pairings(tour)
      val inner = html.tournament.show.finishedInner(tour, games)
      html.tournament.show.inner(pairings.some)(inner)
    }

  def form = Auth { implicit ctx ⇒
    me ⇒
      NoEngine {
        Ok(html.tournament.form(forms.create, forms))
      }
  }

  def create = AuthBody { implicit ctx ⇒
    implicit me ⇒
      NoEngine {
        IOResult {
          implicit val req = ctx.body
          forms.create.bindFromRequest.fold(
            err ⇒ io(BadRequest(html.tournament.form(err, forms))),
            setup ⇒ api.createTournament(setup, me) map { tour ⇒
              Redirect(routes.Tournament.show(tour.id))
            })
        }
      }
  }

  def websocket(id: String) = WebSocket.async[JsValue] { req ⇒
    implicit val ctx = reqToCtx(req)
    socket.join(id, getInt("version"), get("sri"), ctx.me).unsafePerformIO
  }

  private def version(tournamentId: String): Int = socket blockingVersion tournamentId
}
