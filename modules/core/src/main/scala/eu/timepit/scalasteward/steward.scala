/*
 * Copyright 2018 scala-steward contributors
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

package eu.timepit.scalasteward

import better.files.File
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import eu.timepit.scalasteward.github.{GitHubService, Repo}
import eu.timepit.scalasteward.github.http4s.Http4sGitHubService
import eu.timepit.scalasteward.model._
import eu.timepit.scalasteward.util._
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import scala.concurrent.ExecutionContext

object steward extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    /*
    Do less
    =======

    - determine dependencies of all projects and keep this list up-to-date
      - initial set of dependencies can be extracted from sbt
      - git can be used to check if we need to use sbt to refresh the list of dependencies

    - find updates for all recorded dependencies

    - create PRs for each of these updates if they don't already exists

    Advantages:
    - fewer sbt calls. sbt need to be called when upstream has changed and not when
      scala-steward runs
    - each dependency need to be checked only once
     */

    val workspace = File.home / "code/scala-steward/workspace"
    log.printTotalTime {
      for {
        _ <- prepareEnv(workspace)
        repos <- getRepos(workspace)
        _ <- BlazeClientBuilder[IO](ExecutionContext.global).resource.use { client =>
          repos.traverse_(stewardRepo(_, workspace, client))
        }
      } yield ExitCode.Success
    }
  }

  def prepareEnv(workspace: File): IO[Unit] =
    for {
      _ <- log.printInfo("Add global sbt plugins")
      _ <- sbt.addGlobalPlugins(File.home)
      _ <- log.printInfo(s"Clean workspace $workspace")
      _ <- io.deleteForce(workspace)
      _ <- io.mkdirs(workspace)
    } yield ()

  def getRepos(workspace: File): IO[List[Repo]] =
    IO {
      val file = workspace / ".." / "repos.md"
      val regex = """-\s+(.+)/(.+)""".r
      file.lines.collect { case regex(owner, repo) => Repo(owner, repo) }.toList
    }

  def stewardRepo(repo: Repo, workspace: File, client: Client[IO]): IO[Unit] = {
    val githubService = new Http4sGitHubService(client)
    val p = for {
      localRepo <- cloneAndUpdate(repo, workspace, githubService)
      _ <- updateDependencies(localRepo, githubService)
      _ <- io.deleteForce(localRepo.dir)
    } yield ()
    log.printTotalTime {
      p.attempt.flatMap {
        case Right(_) => IO.unit
        case Left(t)  => IO(println(t))
      }
    }
  }

  def cloneAndUpdate(
      repo: Repo,
      workspace: File,
      gitHubService: GitHubService[IO]
  ): IO[LocalRepo] =
    for {
      _ <- log.printInfo(s"Clone and update ${repo.show}")
      user <- githubLegacy.authenticatedUser
      repoOut <- gitHubService.createFork(user, repo)
      repoDir = workspace / repo.owner / repo.repo
      _ <- io.mkdirs(repoDir)
      forkUrl <- githubLegacy.httpsUrlWithCredentials(repoOut.repo)
      _ <- git.clone(forkUrl, repoDir, workspace)
      _ <- git.setUserSteward(repoDir)
      _ <- githubLegacy.fetchUpstream(repo, repoDir)
      // TODO: Determine the current default branch
      baseBranch <- git.currentBranch(repoDir)
      _ <- git.exec(List("merge", s"upstream/${baseBranch.name}"), repoDir)
      _ <- git.push(baseBranch, repoDir)
    } yield LocalRepo(repo, repoDir, baseBranch)

  def updateDependencies(localRepo: LocalRepo, gitHubService: GitHubService[IO]): IO[Unit] =
    for {
      _ <- log.printInfo(s"Check updates for ${localRepo.upstream.show}")
      updates <- sbt.allUpdates(localRepo.dir)
      _ <- log.printUpdates(updates)
      filteredUpdates = updates.filterNot(Update.ignore)
      _ <- filteredUpdates.traverse_(
        update => applyUpdate(LocalUpdate(localRepo, update), gitHubService)
      )
    } yield ()

  def applyUpdate(localUpdate: LocalUpdate, gitHubService: GitHubService[IO]): IO[Unit] =
    for {
      _ <- log.printInfo(s"Apply update ${localUpdate.update.show}")
      _ <- git
        .remoteBranchExists(localUpdate.updateBranch, localUpdate.localRepo.dir)
        .ifM(resetAndUpdate(localUpdate, gitHubService), applyNewUpdate(localUpdate, gitHubService))
    } yield ()

  def applyNewUpdate(localUpdate: LocalUpdate, gitHubService: GitHubService[IO]): IO[Unit] = {
    val dir = localUpdate.localRepo.dir
    val update = localUpdate.update
    val updateBranch = localUpdate.updateBranch

    (io.updateDir(dir, update) >> git.containsChanges(dir)).ifM(
      git.returnToCurrentBranch(dir) {
        for {
          _ <- log.printInfo(s"Create branch ${updateBranch.name}")
          _ <- git.createBranch(updateBranch, dir)
          _ <- commitPushAndCreatePullRequest(localUpdate, gitHubService)
        } yield ()
      },
      log.printWarning(s"No files were changed")
    )
  }

  def resetAndUpdate(localUpdate: LocalUpdate, gitHubService: GitHubService[IO]): IO[Unit] = {
    val dir = localUpdate.localRepo.dir
    val updateBranch = localUpdate.updateBranch

    git.returnToCurrentBranch(dir) {
      git.checkoutBranch(updateBranch, dir) >> ifTrue(shouldBeReset(localUpdate)) {
        for {
          _ <- log.printInfo(s"Reset and update branch ${updateBranch.name}")
          _ <- git.exec(List("reset", "--hard", localUpdate.localRepo.base.name), dir)
          _ <- io.updateDir(dir, localUpdate.update)
          _ <- ifTrue(git.containsChanges(dir))(
            commitPushAndCreatePullRequest(localUpdate, gitHubService)
          )
        } yield ()
      }
    }
  }

  def commitPushAndCreatePullRequest(
      localUpdate: LocalUpdate,
      gitHubService: GitHubService[IO]
  ): IO[Unit] = {
    val dir = localUpdate.localRepo.dir
    for {
      _ <- git.commitAll(localUpdate.commitMsg, dir)
      _ <- git.push(localUpdate.updateBranch, dir)
      _ <- githubLegacy.createPullRequestIfNotExists(localUpdate, gitHubService)
    } yield ()
  }

  def shouldBeReset(localUpdate: LocalUpdate): IO[Boolean] = {
    val dir = localUpdate.localRepo.dir
    val baseBranch = localUpdate.localRepo.base
    val updateBranch = localUpdate.updateBranch
    for {
      isMerged <- git.isMerged(updateBranch, baseBranch, dir)
      isBehind <- git.isBehind(updateBranch, baseBranch, dir)
      authors <- git.branchAuthors(updateBranch, baseBranch, dir)
      (result, msg) = {
        val pr = s"PR ${updateBranch.name}"
        if (isMerged)
          (false, s"$pr is already merged")
        else if (authors.distinct.length >= 2)
          (false, s"$pr has commits by ${authors.mkString(", ")}")
        else if (authors.length >= 2)
          (true, s"$pr has multiple commits")
        else if (isBehind)
          (true, s"$pr is behind ${baseBranch.name}")
        else
          (false, s"$pr is up-to-date with ${baseBranch.name}")
        // TODO: Only reset PRs that are still open.
      }
      _ <- log.printInfo(msg)
    } yield result
  }
}
