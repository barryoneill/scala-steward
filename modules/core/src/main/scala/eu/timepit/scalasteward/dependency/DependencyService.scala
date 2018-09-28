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

package eu.timepit.scalasteward.dependency

import cats.MonadError
import cats.implicits._
import eu.timepit.scalasteward.git.{GitAlg, Sha1}
import eu.timepit.scalasteward.github.GitHubService
import eu.timepit.scalasteward.github.data.{AuthenticatedUser, Repo, RepoOut}
import eu.timepit.scalasteward.sbt.SbtAlg
import eu.timepit.scalasteward.util.uriUtil

class DependencyService[F[_]](
    dependencyRepository: DependencyRepository[F],
    gitHubService: GitHubService[F],
    gitAlg: GitAlg[F],
    sbtService: SbtAlg[F]
) {
  def refreshDependenciesIfNecessary(
      user: AuthenticatedUser,
      repo: Repo
  )(implicit F: MonadError[F, Throwable]): F[Unit] =
    for {
      res <- gitHubService.createForkAndGetDefaultBranch(user, repo)
      (repoOut, branchOut) = res
      foundSha1 <- dependencyRepository.findSha1(repo)
      latestSha1 = branchOut.commit.sha
      refreshRequired = foundSha1.fold(true)(_ =!= latestSha1)
      _ <- {
        if (refreshRequired) {
          refreshDependencies(user, repo, repoOut, latestSha1)
        } else F.unit
      }
    } yield ()

  def refreshDependencies(
      user: AuthenticatedUser,
      repo: Repo,
      repoOut: RepoOut,
      latestSha1: Sha1
  )(implicit F: MonadError[F, Throwable]): F[Unit] =
    for {
      cloneUrlWithUser <- uriUtil.fromStringWithUser[F](repoOut.clone_url, user)
      _ <- gitAlg.clone(repo, cloneUrlWithUser)
      parent <- repoOut.parentOrRaise[F]
      upstreamUrl <- uriUtil.fromString[F](parent.clone_url)
      _ <- gitAlg.syncFork(repo, upstreamUrl, parent.default_branch)
      dependencies <- sbtService.getDependencies(repo)
      _ <- dependencyRepository.setDependencies(repo, latestSha1, dependencies)
      _ <- gitAlg.removeClone(repo)
    } yield ()
}
